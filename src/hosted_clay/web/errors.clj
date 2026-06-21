(ns hosted-clay.web.errors
  "A catch-all that turns an unhandled exception into a styled 500 page
   instead of a blank response or a leaked stack trace. Mounted as the
   outermost middleware so it covers the auth and CSRF layers too."
  (:require [clojure.tools.logging :as log]
            [hosted-clay.ui.layout :as layout]))

(defn wrap-exception [handler]
  (fn [req]
    (try
      (handler req)
      (catch Throwable t
        (log/error t "unhandled exception"
                   {:uri (:uri req) :method (:request-method req)})
        {:status  500
         :headers {"content-type" "text/html; charset=utf-8"}
         :body    (layout/error)}))))
