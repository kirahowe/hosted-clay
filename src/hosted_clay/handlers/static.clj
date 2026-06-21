(ns hosted-clay.handlers.static
  (:require [integrant.core :as ig]
            [reitit.ring :as ring]))

(defmethod ig/init-key :hosted-clay.handlers/static [_ {:keys [root]}]
  (ring/create-resource-handler {:root (or root "public")}))

(defmethod ig/init-key :hosted-clay.handlers/favicon [_ _]
  ;; Browsers request /favicon.ico from the root regardless of the <link>
  ;; in <head>; point them at the real (themed, SVG) icon under /static.
  (fn [_req]
    {:status 301 :headers {"location" "/static/favicon.svg"}}))
