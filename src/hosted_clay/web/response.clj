(ns hosted-clay.web.response
  "Small ring-response helpers shared across handlers, so the handlers
   stay glue: read input, call the domain, pick a response."
  (:require [charred.api :as charred]
            [hosted-clay.ui.layout :as layout]))

(defn html
  "An HTML response. Status defaults to 200."
  ([body] (html 200 body))
  ([status body]
   {:status  status
    :headers {"content-type" "text/html; charset=utf-8"}
    :body    body}))

(defn see-other
  "A 303 redirect (POST -> GET, the form post/redirect/get pattern)."
  [location]
  {:status 303 :headers {"location" location}})

(defn no-content
  "A 204, for an action whose result the client reads from the status
   alone (e.g. a fetch checking `resp.ok`)."
  []
  {:status 204})

(defn text
  "A plain-text response. Status defaults to 200."
  ([body] (text 200 body))
  ([status body]
   {:status  status
    :headers {"content-type" "text/plain; charset=utf-8"}
    :body    body}))

(defn json
  "A JSON response from a Clojure value. Status defaults to 200."
  ([body] (json 200 body))
  ([status body]
   {:status  status
    :headers {"content-type" "application/json; charset=utf-8"}
    :body    (charred/write-json-str body)}))

(defn not-found
  "A 404 HTML page."
  [message]
  (html 404 (layout/not-found message)))

(defn forbidden
  "A 403 HTML page."
  [message]
  (html 403 (layout/forbidden message)))

(defn unavailable
  "A 503 HTML page — a temporary pause that resolves on its own, not a
   permanent denial."
  [message]
  (html 503 (layout/unavailable message)))

(defn expire-cookie
  "Add a Set-Cookie header to `response` that immediately expires cookie `name`."
  [response name]
  (assoc-in response [:headers "set-cookie"]
            (str name "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax")))
