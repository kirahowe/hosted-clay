(ns hosted-clay.handlers.home
  (:require [integrant.core :as ig]
            [hosted-clay.auth :as auth]
            [hosted-clay.ui.pages.home :as home]
            [hosted-clay.web.response :as response]))

(defmethod ig/init-key :hosted-clay.handlers/home [_ _]
  (fn [req]
    (response/html (home/render (auth/session-present? req)))))
