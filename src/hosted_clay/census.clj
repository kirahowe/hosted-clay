(ns hosted-clay.census
  "Periodic visibility into sprite spend. Sprites bill compute only while
   awake and suspend after ~30s idle, so the number that drives cost is how
   many are *running* right now, not how many exist. This polls the Sprites
   API for the live status of every sprite this deployment holds (notebooks +
   warm pool) and logs the tally — total held, how many awake, how many
   suspended — so cost is greppable in the app logs alongside the Sprites
   dashboard/CLI, and an approaching-the-limit condition WARNs rather than
   passing silently."
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

(defn gather
  "Poll the Sprites API for the live status of every sprite this deployment
   holds and tally it. The GET is a control-plane call (not traffic to the
   sprite's own service), so it does not itself wake a suspended sprite. A
   sprite the API doesn't return — a notebook still provisioning has a name
   before it has a sprite, or one was deleted mid-census — counts as
   `unreachable`, never as awake."
  [ds client]
  (let [nb-names   (map :notebooks/sprite-name (crud/find-many ds :notebooks))
        pool-names (map :sprite-pool/sprite-name (crud/find-many ds :sprite-pool))
        names      (concat nb-names pool-names)
        statuses   (for [name names]
                     (try
                       (:status (sprites/get-sprite client name))
                       (catch Throwable _ ::unreachable)))
        reached    (remove #{::unreachable} statuses)]
    {:notebooks   (count nb-names)
     :pool        (count pool-names)
     :total       (count names)
     :running     (count (filter awake? reached))
     :suspended   (count (remove awake? reached))
     :unreachable (count (filter #{::unreachable} statuses))
     ;; the raw status histogram, so the real platform enum is visible in the
     ;; logs and the awake?/suspended split can be refined against it.
     :by-status   (frequencies (map #(if (= ::unreachable %) "unreachable" (str %))
                                    statuses))}))

(defn log-census!
  "Gather and log the sprite census. INFO every run; WARN when the running
   count reaches the concurrency soft-cap or the total nears the registration
   ceiling. Never throws — the scheduler runs it best-effort."
  [ds client {:keys [max-sprites max-running]}]
  (let [{:keys [total running] :as census} (gather ds client)]
    (log/info "sprite census" (assoc census :max-sprites max-sprites :max-running max-running))
    (when (and max-running (>= running max-running))
      (log/warn "sprites awake at/over the concurrency soft-cap"
                {:running running :max-running max-running}))
    (when (and max-sprites (>= total (long (Math/ceil (* 0.9 max-sprites)))))
      (log/warn "registered sprites nearing the budget ceiling"
                {:total total :max-sprites max-sprites}))
    census))
