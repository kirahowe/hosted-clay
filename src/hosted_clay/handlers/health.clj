(ns hosted-clay.handlers.health
  (:require [charred.api :as charred]
            [integrant.core :as ig]))

(defmethod ig/init-key :hosted-clay.handlers/health [_ _]
  (fn [_req]
    {:status  200
     :headers {"content-type" "application/json"}
     :body    (charred/write-json-str {"status" "ok"})}))
