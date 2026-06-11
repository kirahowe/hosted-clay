(ns hosted-clay.concerns.http-kit
  (:require [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [org.httpkit.server :as http]))

(defmethod ig/init-key :hosted-clay.concerns/http-kit [_ {:keys [handler opts]}]
  (log/info "http server starting" {:opts opts})
  (let [server (http/run-server handler (assoc opts :legacy-return-value? false))]
    (log/info "http server started" {:port (http/server-port server)})
    server))

(defmethod ig/halt-key! :hosted-clay.concerns/http-kit [_ server]
  (log/info "http server stopping")
  (http/server-stop! server))
