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
            [hosted-clay.lifecycle :as lifecycle]
            [hosted-clay.pool :as pool]))

(defn- run-quietly! [task-name f]
  (try
    (f)
    (catch Throwable t
      (log/error t "scheduled task failed" {:task task-name}))))

(defn- loop!
  [{:keys [datasource sprites-client email tick-ms sweep-every-ticks census-every-ticks
           pool-target max-sprites max-running warn-after-days delete-after-days base-url]}
   running?]
  (loop [tick 0]
    (when @running?
      (run-quietly! :replenish-pool
                    #(pool/replenish! datasource sprites-client
                                      {:target      pool-target
                                       :max-sprites max-sprites}))
      (when (zero? (mod tick census-every-ticks))
        (run-quietly! :sprite-census
                      #(census/log-census! datasource sprites-client
                                           {:max-sprites max-sprites
                                            :max-running max-running})))
      (when (zero? (mod tick sweep-every-ticks))
        (run-quietly! :lifecycle-sweep
                      #(lifecycle/sweep! datasource sprites-client email
                                         {:warn-after-days   warn-after-days
                                          :delete-after-days delete-after-days
                                          :base-url          base-url})))
      (Thread/sleep ^long tick-ms)
      (recur (inc tick)))))

(defmethod ig/init-key :hosted-clay/scheduler
  [_ config]
  (let [config   (merge {:tick-ms 60000 :sweep-every-ticks 60 :census-every-ticks 5} config)
        running? (atom true)]
    (log/info "scheduler starting" {:tick-ms      (:tick-ms config)
                                    :pool-target  (:pool-target config)
                                    :max-sprites  (:max-sprites config)
                                    :max-running  (:max-running config)})
    {:running? running?
     :thread   (async/thread (loop! config running?))}))

(defmethod ig/halt-key! :hosted-clay/scheduler [_ {:keys [running? thread]}]
  (log/info "scheduler stopping")
  (reset! running? false)
  ;; Don't block shutdown on a provisioning run; the thread exits at the
  ;; next tick boundary.
  (async/poll! thread))
