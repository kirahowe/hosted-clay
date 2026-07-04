(ns hosted-clay.scheduler
  "Integrant-owned background loop for the platform's periodic work:
   keeping the warm pool full (every tick) and running the idle-deletion
   sweep (hourly). One thread, serial tasks — pool provisioning is slow
   but nothing here is latency-sensitive, and serializing avoids any
   coordination."
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [hosted-clay.census :as census]
            [hosted-clay.idle :as idle]
            [hosted-clay.lifecycle :as lifecycle]
            [hosted-clay.notebooks :as notebooks]
            [hosted-clay.pool :as pool]
            [hosted-clay.snapshot :as snapshot]
            [hosted-clay.usage :as usage]))

(defn- run-quietly! [task-name f]
  (try
    (f)
    (catch Throwable t
      (log/error t "scheduled task failed" {:task task-name}))))

(defn- loop!
  [{:keys [datasource sprites-client email tick-ms sweep-every-ticks census-every-ticks
           census-log-every-ticks pool-target max-sprites max-running usage-limit-hours
           usage-warn-hours snapshot-refresh-minutes warn-after-days delete-after-days base-url
           idle-suspend-minutes sprite-tag]}
   running?]
  ;; Each awake sample during a census run is worth one census interval of awake
  ;; time (nominal — close enough for a soft monthly budget). A short interval
  ;; keeps the usage meter fine-grained: at a 1-minute census the meter advances
  ;; ~1 minute at a time, tracking real usage to within roughly a minute.
  (let [census-interval-seconds (long (/ (* tick-ms census-every-ticks) 1000))]
    (loop [tick 0]
      (when @running?
        (run-quietly! :replenish-pool
                      #(pool/replenish! datasource sprites-client
                                        {:target      pool-target
                                         :max-sprites max-sprites
                                         :sprite-tag  sprite-tag}))
        ;; Every tick: force-suspend any sprite a left-open tab is pinning awake
        ;; past the idle window (the client-side pause can't run when the tab's
        ;; machine is asleep or frozen). The proxy tracks last-activity in
        ;; memory, so this is a cheap non-blocking read — no per-sprite polling.
        (run-quietly! :idle-suspend
                      #(idle/sweep! idle-suspend-minutes))
        (when (zero? (mod tick census-every-ticks))
          (run-quietly! :sprite-census
                        (fn []
                          ;; One status poll feeds both the cost log and the
                          ;; per-user usage meter. Poll + metering run every
                          ;; census tick (fine granularity for the meter); the
                          ;; cost log is throttled to census-log-every-ticks so
                          ;; frequent sampling doesn't drown the greppable signal.
                          (let [c (census/gather datasource sprites-client)]
                            (when (zero? (mod tick census-log-every-ticks))
                              (census/log! c {:max-sprites max-sprites :max-running max-running}))
                            (usage/record! datasource email (:awake-notebooks c)
                                           {:interval-seconds census-interval-seconds
                                            :warn-hours       usage-warn-hours
                                            :limit-hours      usage-limit-hours
                                            :base-url         base-url})
                            ;; Refresh static snapshots off the same awake set, so
                            ;; the share/source views never wake a sprite.
                            ;; (Internally throttled to snapshot-refresh-minutes,
                            ;; so a frequent census just checks age more often.)
                            (snapshot/refresh-awake! datasource sprites-client
                                                     (:awake-notebooks c)
                                                     snapshot-refresh-minutes)))))
        (when (zero? (mod tick sweep-every-ticks))
          (run-quietly! :lifecycle-sweep
                        #(lifecycle/sweep! datasource sprites-client email
                                           {:warn-after-days   warn-after-days
                                            :delete-after-days delete-after-days
                                            :base-url          base-url})))
        (Thread/sleep ^long tick-ms)
        (recur (inc tick))))))

(defmethod ig/init-key :hosted-clay/scheduler
  [_ config]
  (let [config   (merge {:tick-ms 60000 :sweep-every-ticks 60
                         :census-every-ticks 1 :census-log-every-ticks 5} config)
        running? (atom true)]
    (log/info "scheduler starting" {:sprite-tag           (:sprite-tag config)
                                    :tick-ms              (:tick-ms config)
                                    :pool-target          (:pool-target config)
                                    :max-sprites          (:max-sprites config)
                                    :max-running          (:max-running config)
                                    :idle-suspend-minutes (:idle-suspend-minutes config)})
    {:running? running?
     :thread   (async/thread
                 ;; One-time startup recovery before the periodic loop: any
                 ;; notebook left in 'provisioning' is orphaned (its builder
                 ;; thread died with the previous process), so unstick it.
                 (run-quietly! :reconcile-provisioning
                               #(notebooks/reconcile-provisioning! (:datasource config)))
                 (loop! config running?))}))

(defmethod ig/halt-key! :hosted-clay/scheduler [_ {:keys [running? thread]}]
  (log/info "scheduler stopping")
  (reset! running? false)
  ;; Don't block shutdown on a provisioning run; the thread exits at the
  ;; next tick boundary.
  (async/poll! thread))
