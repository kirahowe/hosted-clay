(ns hosted-clay.pool
  "The warm pool of pre-provisioned sprites. New-notebook creation
   claims a ready sprite here so the user never waits on provisioning;
   the scheduler refills the pool in the background."
  (:require [clojure.tools.logging :as log]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [hosted-clay.db.crud :as crud]
            [hosted-clay.sprites.client :as sprites]
            [hosted-clay.sprites.provision :as provision]))

(defn new-sprite-name
  "A fresh sprite name: unguessable, DNS-safe, recognizably ours."
  []
  (str "nb-" (.toString (java.math.BigInteger. 64 (java.security.SecureRandom.)) 32)))

(defn claim!
  "Take one ready sprite out of the pool. Returns {:sprite-name
   :sprite-url} or nil when the pool is empty. A single DELETE ...
   RETURNING so two concurrent creates can't claim the same sprite —
   one statement is atomic where a select-then-delete would race."
  [ds]
  (when-let [row (jdbc/execute-one!
                  ds
                  (sql/format {:delete-from :sprite-pool
                               :where       [:in :id {:select [:id]
                                                      :from   [:sprite-pool]
                                                      :where  [:= :state "ready"]
                                                      :limit  1}]
                               :returning   [:*]})
                  crud/opts)]
    {:sprite-name (:sprite-pool/sprite-name row)
     :sprite-url  (:sprite-pool/sprite-url row)}))

(defn provision-one!
  "Create and provision a single sprite, tracked through the pool table
   so a crash mid-provision leaves a visible 'provisioning' row instead
   of an orphaned sprite. Returns the ready pool row; on failure deletes
   the sprite and the row, then rethrows."
  [ds client]
  (let [sprite-name (new-sprite-name)
        sprite      (sprites/create-sprite! client sprite-name)
        row         (crud/create! ds :sprite-pool {:sprite-name sprite-name
                                                   :sprite-url  (:url sprite)
                                                   :state       "provisioning"})]
    (try
      (provision/provision! client sprite-name)
      (crud/update! ds :sprite-pool (:sprite-pool/id row) {:state "ready"})
      (catch Throwable t
        (log/error t "pool provisioning failed" {:sprite sprite-name})
        (crud/delete! ds :sprite-pool (:sprite-pool/id row))
        (sprites/delete-sprite! client sprite-name)
        (throw t)))))

(defn sprite-count
  "Every sprite this app is paying for: live notebooks plus the pool."
  [ds]
  (+ (crud/count-rows ds :notebooks)
     (crud/count-rows ds :sprite-pool)))

(defn replenish!
  "Top the pool back up to `target` ready-or-provisioning sprites,
   without pushing the total sprite count past `max-sprites` (the budget
   cap). Provisions serially — this runs on the scheduler thread and
   speed doesn't matter."
  [ds client {:keys [target max-sprites]}]
  (let [deficit (- target (crud/count-rows ds :sprite-pool))
        room    (- max-sprites (sprite-count ds))
        n       (max 0 (min deficit room))]
    (cond
      (pos? n)
      (do (log/info "replenishing pool" {:adding n})
          (dotimes [_ n]
            (provision-one! ds client)))

      ;; Want to refill but the sprite ceiling leaves no room: the pool runs
      ;; dry and the next "New notebook" takes the slow path. Worth a WARN —
      ;; it means the deployment is at capacity, not idling.
      (and (pos? deficit) (<= room 0))
      (log/warn "pool below target but at sprite ceiling; not replenishing"
                {:deficit deficit :max-sprites max-sprites :held (sprite-count ds)}))))
