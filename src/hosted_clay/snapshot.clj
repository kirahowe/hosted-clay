(ns hosted-clay.snapshot
  "Static snapshots of a notebook, captured into the control plane so its
   content can be served without waking (and billing) the sprite — and so it
   stays reachable when the notebook is paused for the month.

   The render (Clay's self-contained docs/notebook.html, ~1 MB) and the raw
   source (notebook.clj) are stored as *files* on the control-plane volume,
   next to the SQLite DB. Only the capture timestamp lives in the DB — a side
   table, one row per notebook (1:1) — so the blobs never drag along the
   frequent SELECT * over `notebooks` and never bloat the DB/WAL/backups. The
   share view streams the stored .html; the owner's raw-source view reads the
   stored .clj.

   Capture pulls each file over plain HTTP from the sprite's Caddy, which serves
   the pristine on-disk files under /snapshot/* (bypassing Clay, so no live-
   reload is injected — see the sprite Caddyfile). The scheduler's census
   refreshes a snapshot only while the sprite is already awake, so capturing
   never causes a wake; the GET goes to the sprite URL, which *would* wake a
   suspended sprite, so it must stay awake-gated (see `refresh-awake!`). (The
   original mechanism was a `cat` over the exec socket; its 64 KiB stdout cap
   truncated the ~1 MB render mid-<script>, which is what broke the share view.)"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [org.httpkit.client :as http]
            [hosted-clay.db.crud :as crud]
            [hosted-clay.sprites.client :as sprites])
  (:import (java.io File InputStream)
           (java.nio.file CopyOption Files StandardCopyOption)
           (java.nio.file.attribute FileAttribute)
           (java.time Duration Instant)))

;; The sprite's Caddy serves the pristine on-disk files at these paths: the
;; last render and the raw source, straight from disk with no Clay live-reload
;; injected (see the /snapshot/* routes in resources/sprite/Caddyfile).
(def ^:private render-path "/snapshot/notebook.html")
(def ^:private source-path "/snapshot/notebook.clj")

(defn html-file
  "The File the rendered-HTML snapshot for `notebook-id` is stored at."
  [snapshots-dir notebook-id]
  (io/file snapshots-dir (str notebook-id ".html")))

(defn source-file
  "The File the raw-source snapshot for `notebook-id` is stored at."
  [snapshots-dir notebook-id]
  (io/file snapshots-dir (str notebook-id ".clj")))

(defn for-notebook
  "The stored snapshot row for a notebook, or nil. The row carries only the
   capture timestamp; the content lives in the files above."
  [ds notebook-id]
  (crud/find-1 ds :notebook-snapshots {:notebook-id notebook-id}))

(defn stale?
  "True when `snapshot` is missing or its last capture is older than
   `refresh-minutes` — the cue for the census to refresh it. ISO-8601 instants
   sort lexicographically, so a string compare is a time compare."
  [snapshot refresh-minutes]
  (or (nil? snapshot)
      (nil? (:notebook-snapshots/captured-at snapshot))
      (let [cutoff (str (.minus (Instant/now) (Duration/ofMinutes refresh-minutes)))]
        (neg? (compare (:notebook-snapshots/captured-at snapshot) cutoff)))))

(defn- store!
  "Upsert the snapshot row for `notebook-id`, stamping `captured-at` and
   merging in `attrs` (the file ETags). The insert can race a concurrent
   capture of the same notebook (two overlapping census ticks before the
   first row exists); a lost race shows up as a UNIQUE violation, which we
   fold into the update — the same convergence pattern as
   notebooks/insert-notebook!."
  [ds notebook-id attrs]
  (let [attrs    (assoc attrs :captured-at (crud/now))
        update-1 #(crud/update-where! ds :notebook-snapshots [:= :notebook-id notebook-id] attrs)]
    (if (for-notebook ds notebook-id)
      (update-1)
      (try
        (crud/create! ds :notebook-snapshots (assoc attrs :notebook-id notebook-id))
        (catch java.sql.SQLException e
          (if (str/includes? (.getMessage e) "UNIQUE")
            (update-1)
            (throw e)))))))

(defn- write-file!
  "Stream `in` to `dest` atomically: write a uniquely-named temp sibling, then
   move it into place, so a reader (the share view) never sees a half-written
   file and two overlapping captures of the same notebook can't clobber each
   other's temp. The temp is cleaned up if the move never happens (a failed
   copy); after a successful move it no longer exists, so the delete is a no-op."
  [^InputStream in ^File dest]
  (let [tmp (Files/createTempFile (.toPath (.getParentFile dest))
                                  (str (.getName dest) ".") ".tmp"
                                  (make-array FileAttribute 0))]
    (try
      (with-open [i in] (io/copy i (.toFile tmp)))
      (Files/move tmp (.toPath dest)
                  (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                          StandardCopyOption/REPLACE_EXISTING]))
      (finally
        (Files/deleteIfExists tmp)))))

(defn- fetch-file!
  "GET `path` from the sprite and write the body to `dest` atomically —
   conditionally when we hold a validator: `etag` (from the last capture) is
   sent as If-None-Match, so an unchanged file answers 304 with no body and
   the refresh costs bytes of headers instead of a ~1 MB re-stream. The
   validator is only sent while `dest` still exists — if the local file is
   gone (a wiped volume with the DB intact), a 304 would leave nothing to
   serve, so we re-fetch in full. Returns {:ok? bool :etag validator}:
   200 → written, with the response's ETag (nil if the server sent none);
   304 → untouched, keeping `etag`; anything else → {:ok? false}, leaving
   any existing file intact. `:keepalive -1` drops the connection at once so
   this background read can't pin the sprite awake past the moment it runs."
  [client sprite-url path ^File dest etag]
  (let [url  (str sprite-url path)
        etag (when (.exists dest) etag)
        {:keys [status headers body error]}
        @(http/request {:method           :get
                        :url              url
                        :headers          (cond-> {"Authorization" (sprites/bearer client)}
                                            etag (assoc "if-none-match" etag))
                        :as               :stream
                        :keepalive        -1
                        :timeout          60000
                        :follow-redirects false})]
    (cond
      error
      (do (log/warn error "snapshot fetch failed" {:url url}) {:ok? false})

      (= 304 status)
      (do (when (instance? InputStream body) (.close ^InputStream body))
          {:ok? true :etag etag})

      (not= 200 status)
      (do (when (instance? InputStream body) (.close ^InputStream body))
          (log/warn "snapshot fetch returned non-200" {:url url :status status})
          {:ok? false})

      :else
      (do (write-file! body dest)
          {:ok? true :etag (:etag headers)}))))

(defn capture!
  "Snapshot a notebook's last render and raw source from its sprite and store
   them as files under `snapshots-dir`, revalidating with the ETags from the
   last capture so an unchanged file is never re-shipped (see `fetch-file!`).
   Best-effort: logs and returns nil on any failure — it must never break the
   census. The sprite must already be awake (the census only calls this for
   awake notebooks), so the GETs never cause a wake. Only files that fetch
   cleanly are rewritten, so a momentarily missing file leaves the prior
   snapshot intact."
  [ds client snapshots-dir notebook]
  (try
    (let [id         (:notebooks/id notebook)
          sprite-url (:notebooks/sprite-url notebook)
          stored     (for-notebook ds id)]
      (.mkdirs (io/file snapshots-dir))
      (let [html (fetch-file! client sprite-url render-path (html-file snapshots-dir id)
                              (:notebook-snapshots/html-etag stored))
            src  (fetch-file! client sprite-url source-path (source-file snapshots-dir id)
                              (:notebook-snapshots/source-etag stored))]
        (when (or (:ok? html) (:ok? src))
          ;; Both validators are (re)written every capture — a nil clears a
          ;; stale one (e.g. the server stopped sending ETags), so the next
          ;; pass falls back to a full GET rather than revalidating against
          ;; a validator the server no longer recognises.
          (store! ds id {:html-etag (:etag html) :source-etag (:etag src)})
          (log/info "notebook snapshot captured"
                    {:notebook-id id :render (:ok? html) :source (:ok? src)})
          true)))
    (catch Throwable t
      (log/error t "notebook snapshot failed"
                 {:notebook-id (:notebooks/id notebook)})
      nil)))

(defn delete-files!
  "Remove a notebook's stored snapshot files (best-effort). Called on notebook
   deletion so the volume doesn't accrue orphaned renders."
  [snapshots-dir notebook-id]
  (io/delete-file (html-file snapshots-dir notebook-id) true)
  (io/delete-file (source-file snapshots-dir notebook-id) true))

(defn refresh-awake!
  "For each awake notebook whose stored snapshot is missing or stale, refresh it
   off-thread (the sprite is already awake, so this never causes a wake). Called
   from the census; each capture is best-effort and independent. Returns the
   futures it fired (the census ignores them; tests deref them)."
  [ds client snapshots-dir awake-notebooks refresh-minutes]
  (doall
   (for [nb    awake-notebooks
         :when (stale? (for-notebook ds (:notebooks/id nb)) refresh-minutes)]
     (future (capture! ds client snapshots-dir nb)))))
