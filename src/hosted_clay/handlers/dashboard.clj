(ns hosted-clay.handlers.dashboard
  (:require [integrant.core :as ig]
            [hosted-clay.notebooks :as notebooks]
            [hosted-clay.ui.pages.dashboard :as dashboard]
            [hosted-clay.usage :as usage]
            [hosted-clay.web.response :as response]))

(defmethod ig/init-key :hosted-clay.handlers/dashboard [_ {:keys [datasource base-url usage-limit-hours]}]
  (fn [req]
    (let [notebook      (notebooks/for-user datasource (:user-id req))
          ;; The meter is the user's monthly total across all their notebooks,
          ;; read from user_usage — not off the notebook row — so it's stable
          ;; across a delete/recreate.
          awake-seconds (usage/awake-seconds-this-month datasource (:user-id req))]
      (response/html (dashboard/render (:user req) notebook base-url usage-limit-hours awake-seconds)))))
