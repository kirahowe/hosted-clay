(ns hosted-clay.handlers.privacy
  (:require [integrant.core :as ig]
            [hosted-clay.ui.pages.privacy :as privacy]
            [hosted-clay.web.response :as response]))

(defmethod ig/init-key :hosted-clay.handlers/privacy [_ _]
  (fn [_req]
    (response/html (privacy/render))))
