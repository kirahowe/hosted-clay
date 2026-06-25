(ns hosted-clay.census
  "Periodic visibility into sprite spend. Sprites bill compute only while
   awake and suspend after ~30s idle, so the number that drives cost is how
   many are *running* right now, not how many exist. `gather` polls the Sprites
   API for the live status of every sprite this deployment holds (notebooks +
   warm pool) and tallies it; `log!` writes that tally to the logs, WARNing as a
   limit nears. `gather` also returns which notebooks are awake, so the
   scheduler can meter per-user usage off the same poll (see `hosted-clay.usage`)."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [hosted-clay.db.crud :as crud]
            [hosted-clay.sprites.client :as sprites]))

(def ^:private suspended-statuses
  ;; Per Sprites' billing model compute stops when a sprite is 'warm'
  ;; (suspended, the steady idle state) or 'cold' (not started this life);
  ;; only an awake sprite bills CPU/RAM. Anything we don't recognise is
  ;; counted as awake, so an unexpected status inflates the cost signal
  ;; rather than hiding spend.
  #{"warm" "cold" "suspended" "stopped" "idle"})

(defn- awake? [status]
  (and status (not (contains? suspended-statuses (str/lower-case (str status))))))

(defn- poll-status [client sprite-name]
  ;; The GET is a control-plane call (api.sprites.dev), not traffic to the
  ;; sprite's own URL, so it does not wake a suspended sprite. ::unreachable is
  ;; a notebook still provisioning (a name before it has a sprite) or one
  ;; deleted mid-census — never counted as awake.
  (try
    (:status (sprites/get-sprite client sprite-name))
    (catch Throwable _ ::unreachable)))

(defn gather
  "Poll the live status of every sprite this deployment holds and tally it.
   Returns the counts (for `log!`) plus `:awake-notebooks` — the notebook rows
   whose sprite is currently awake, for usage metering."
  [ds client]
  (let [notebooks  (crud/find-many ds :notebooks)
        pool       (crud/find-many ds :sprite-pool)
        nb+status  (map (fn [n] [n (poll-status client (:notebooks/sprite-name n))]) notebooks)
        pool-stat  (map #(poll-status client (:sprite-pool/sprite-name %)) pool)
        all-status (concat (map second nb+status) pool-stat)
        reached    (remove #{::unreachable} all-status)]
    {:notebooks       (count notebooks)
     :pool            (count pool)
     :total           (+ (count notebooks) (count pool))
     :running         (count (filter awake? reached))
     :suspended       (count (remove awake? reached))
     :unreachable     (count (filter #{::unreachable} all-status))
     ;; raw histogram, so the real platform enum is visible in the logs and the
     ;; awake?/suspended split can be refined against it.
     :by-status       (frequencies (map #(if (= ::unreachable %) "unreachable" (str %))
                                        all-status))
     :awake-notebooks (keep (fn [[n status]] (when (awake? status) n)) nb+status)}))

(defn log!
  "Log a census map from `gather`. INFO every run; WARN when the running count
   reaches the concurrency soft-cap or the total nears the registration ceiling.
   Drops `:awake-notebooks` from the line — it's for metering, not logging."
  [census {:keys [max-sprites max-running]}]
  (let [{:keys [total running]} census]
    (log/info "sprite census" (-> census
                                  (dissoc :awake-notebooks)
                                  (assoc :max-sprites max-sprites :max-running max-running)))
    (when (and max-running (>= running max-running))
      (log/warn "sprites awake at/over the concurrency soft-cap"
                {:running running :max-running max-running}))
    (when (and max-sprites (>= total (long (Math/ceil (* 0.9 max-sprites)))))
      (log/warn "registered sprites nearing the budget ceiling"
                {:total total :max-sprites max-sprites}))))
