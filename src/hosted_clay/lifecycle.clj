(ns hosted-clay.lifecycle
  "The 30-day idle-deletion policy. The decisions (who to warn, who to
   delete) are pure functions over notebook rows and a clock; `sweep!`
   applies them. A notebook is warned once when it crosses the warning
   threshold and deleted only after it has both crossed the deletion
   threshold and been warned — touch! clears the warning, so activity
   after a warning restarts the whole clock."
  (:require [clojure.tools.logging :as log]
            [hosted-clay.db.crud :as crud]
            [hosted-clay.notebooks :as notebooks]
            [hosted-clay.users :as users])
  (:import (java.time Duration Instant)))

(defn- idle-since [now days]
  (str (.minus ^Instant now (Duration/ofDays days))))

(defn to-warn
  "Notebooks idle longer than `warn-after-days` that haven't been warned."
  [rows now warn-after-days]
  (let [cutoff (idle-since now warn-after-days)]
    (filter #(and (nil? (:notebooks/warned-at %))
                  (neg? (compare (:notebooks/last-accessed-at %) cutoff)))
            rows)))

(defn to-delete
  "Notebooks idle longer than `delete-after-days` that have been warned."
  [rows now delete-after-days]
  (let [cutoff (idle-since now delete-after-days)]
    (filter #(and (some? (:notebooks/warned-at %))
                  (neg? (compare (:notebooks/last-accessed-at %) cutoff)))
            rows)))

(defn- warning-message [user notebook base-url delete-after-days]
  {:to      (:users/email user)
   :subject "Your Clay notebook will be deleted soon"
   :text    (str "Hi,\n\n"
                 "Your notebook \"" (:notebooks/title notebook) "\" hasn't been "
                 "opened in a while. Notebooks idle for " delete-after-days
                 " days are deleted automatically.\n\n"
                 "Open it to keep it: " base-url "/n/" (:notebooks/id notebook) "/\n\n"
                 "— Clay notebooks")})

(defn- warn! [ds send-email notebook {:keys [base-url delete-after-days]}]
  ;; warned-at is written only after send-email returns — send-email throws
  ;; on a failed delivery, so a failure leaves the notebook un-warned and the
  ;; next sweep retries it rather than deleting it unannounced.
  (if-let [user (users/by-id ds (:notebooks/user-id notebook))]
    (do
      (send-email (warning-message user notebook base-url delete-after-days))
      (crud/update! ds :notebooks (:notebooks/id notebook) {:warned-at (crud/now)})
      (log/info "deletion warning sent" {:notebook-id (:notebooks/id notebook)}))
    (log/error "cannot send deletion warning: notebook has no user"
               {:notebook-id (:notebooks/id notebook)
                :user-id     (:notebooks/user-id notebook)})))

(defn sweep!
  "One pass of the idle policy: warn, then delete. Each notebook is
   handled independently so one failure doesn't block the rest."
  [ds client send-email {:keys [warn-after-days delete-after-days] :as opts}]
  (let [rows (crud/find-many ds :notebooks)
        now  (Instant/now)]
    (doseq [nb (to-warn rows now warn-after-days)]
      (try
        (warn! ds send-email nb opts)
        (catch Throwable t
          (log/error t "warning failed" {:notebook-id (:notebooks/id nb)}))))
    (doseq [nb (to-delete rows now delete-after-days)]
      (try
        (notebooks/delete! ds client nb)
        (catch Throwable t
          (log/error t "idle deletion failed" {:notebook-id (:notebooks/id nb)}))))))
