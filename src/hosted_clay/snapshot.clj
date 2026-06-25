(ns hosted-clay.snapshot
  "Static snapshots of a notebook, captured into the control plane so its
   content can be served without waking (and billing) the sprite — and so it
   stays reachable when the notebook is paused for the month.

   The owner's raw-source view reads `source` here; the read-only share view
   reads a rendered `html` snapshot (capture of that is added next). The
   scheduler's census refreshes a notebook's snapshot while its sprite is
   already awake, so capturing never causes a wake. One row per notebook (1:1),
   in a side table so the frequent SELECT * over `notebooks` never drags the
   blobs along."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [hosted-clay.db.crud :as crud]
            [hosted-clay.sprites.exec :as exec])
  (:import (java.time Duration Instant)))

(def ^:private notebook-path "/home/sprite/notebook/notebook.clj")

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

(defn capture-source!
  "Grab the notebook's raw .clj source from its sprite and store it. Best-effort:
   logs and returns nil on any failure — it must never break the census. The
   sprite must already be awake (the census only calls this for awake
   notebooks), so the cheap `cat` never causes a wake."
  [ds client notebook]
  (try
    (when-let [source (exec-out client (:notebooks/sprite-name notebook)
                                ["cat" notebook-path])]
      (store! ds (:notebooks/id notebook) {:source source})
      (log/info "notebook source snapshot captured"
                {:notebook-id (:notebooks/id notebook) :bytes (count source)})
      true)
    (catch Throwable t
      (log/error t "notebook source snapshot failed"
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
     (future (capture-source! ds client nb)))))
