(ns hosted-clay.snapshot
  "Static snapshots of a notebook, captured into the control plane so its
   content can be served without waking (and billing) the sprite — and so it
   stays reachable when the notebook is paused for the month.

   The owner's raw-source view reads `source` here; the read-only share view
   reads the rendered `html` snapshot. Both are cheap `cat`s of files Clay
   already maintains on the sprite — no render runs here. The scheduler's census
   refreshes a notebook's snapshot while its sprite is already awake, so
   capturing never causes a wake. One row per notebook (1:1), in a side table so
   the frequent SELECT * over `notebooks` never drags the blobs along."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [hosted-clay.db.crud :as crud]
            [hosted-clay.sprites.exec :as exec])
  (:import (java.time Duration Instant)))

(def ^:private notebook-dir "/home/sprite/notebook")
(def ^:private source-path (str notebook-dir "/notebook.clj"))
;; Clay writes the rendered notebook here on every save (its default
;; base-target-path is "docs"). The on-disk file is self-contained — CDN assets,
;; no root-absolute paths — and the live-reload WebSocket is injected only at
;; serve-time, never baked into the file, so we can serve this verbatim from the
;; control plane with no sprite contact.
(def ^:private html-path (str notebook-dir "/docs/notebook.html"))

(defn for-notebook
  "The stored snapshot row for a notebook, or nil."
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
  "Upsert the snapshot row for `notebook-id`, stamping `captured-at`. `attrs`
   carries only the columns being refreshed (e.g. just :source), so a source
   capture leaves any stored :html untouched. The insert can race a concurrent
   capture of the same notebook (two overlapping census ticks before the first
   snapshot exists); a lost race shows up as a UNIQUE violation, which we fold
   into the update — the same convergence pattern as notebooks/insert-notebook!."
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

(defn- exec-out
  "Run `cmd` in the notebook's sprite, returning its stdout on a clean exit,
   else nil (logging a non-zero exit with a clipped stderr)."
  [client sprite-name cmd]
  (let [{:keys [exit out err]} (exec/exec! client sprite-name cmd :timeout-ms 60000)]
    (if (zero? exit)
      out
      (do (log/warn "snapshot command exited non-zero"
                    {:sprite sprite-name :exit exit
                     :err    (some-> err (subs 0 (min 500 (count err))))})
          nil))))

(defn capture!
  "Snapshot a notebook's raw source and its last rendered HTML from the sprite
   and store them. Both are cheap `cat`s of files Clay already maintains (the
   .clj and the self-contained docs/notebook.html it writes on each save), so no
   render runs here. Best-effort: logs and returns nil on any failure — it must
   never break the census. The sprite must already be awake (the census only
   calls this for awake notebooks), so the reads never cause a wake. Only the
   columns actually read are written, so a momentarily missing file leaves the
   prior value intact."
  [ds client notebook]
  (try
    (let [sprite (:notebooks/sprite-name notebook)
          source (exec-out client sprite ["cat" source-path])
          html   (exec-out client sprite ["cat" html-path])
          attrs  (cond-> {}
                   source (assoc :source source)
                   html   (assoc :html html))]
      (when (seq attrs)
        (store! ds (:notebooks/id notebook) attrs)
        (log/info "notebook snapshot captured"
                  {:notebook-id  (:notebooks/id notebook)
                   :source-bytes (some-> source count)
                   :html-bytes   (some-> html count)})
        true))
    (catch Throwable t
      (log/error t "notebook snapshot failed"
                 {:notebook-id (:notebooks/id notebook)})
      nil)))

(defn refresh-awake!
  "For each awake notebook whose stored snapshot is missing or stale, refresh it
   off-thread (the sprite is already awake, so this never causes a wake). Called
   from the census; each capture is best-effort and independent. Returns the
   futures it fired (the census ignores them; tests deref them)."
  [ds client awake-notebooks refresh-minutes]
  (doall
   (for [nb    awake-notebooks
         :when (stale? (for-notebook ds (:notebooks/id nb)) refresh-minutes)]
     (future (capture! ds client nb)))))
