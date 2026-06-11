(ns hosted-clay.handlers.dashboard
  (:require [integrant.core :as ig]
            [hosted-clay.notebooks :as notebooks]
            [hosted-clay.ui.pages.dashboard :as dashboard]
            [hosted-clay.web.response :as response]))

(defmethod ig/init-key :hosted-clay.handlers/dashboard [_ {:keys [datasource base-url]}]
  (fn [req]
    (let [notebook (notebooks/for-user datasource (:user-id req))]
      (response/html (dashboard/render (:user req) notebook base-url)))))
