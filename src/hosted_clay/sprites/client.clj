(ns hosted-clay.sprites.client
  "Client for the Sprites REST API (api.sprites.dev). The component is a
   plain map ({:api-url :token}) that every function takes as its first
   argument; the token stays wrapped in a Secret until the moment it
   goes on the wire.

   Command execution inside a sprite goes over the WebSocket exec
   endpoint and lives in hosted-clay.sprites.exec."
  (:require [charred.api :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [org.httpkit.client :as http]))

(defn bearer [client]
  (str "Bearer " (:value (:token client))))

(defn- parse-body [body]
  (when-not (str/blank? body)
    (json/read-json body :key-fn keyword)))

(defn- request!
  "One synchronous JSON call against the API. Returns the parsed body
   (nil for 204); throws ex-info on transport errors and non-2xx."
  [client method path & [body-map]]
  (let [url  (str (:api-url client) path)
        {:keys [status body error]}
        @(http/request (cond-> {:method  method
                                :url     url
                                :headers {"Authorization" (bearer client)
                                          "Accept"        "application/json"}
                                :as      :text}
                         body-map (-> (assoc :body (json/write-json-str body-map))
                                      (assoc-in [:headers "Content-Type"] "application/json"))))]
    (cond
      error
      (throw (ex-info "sprites api transport error" {:method method :path path} error))

      (<= 200 status 299)
      (parse-body body)

      :else
      (throw (ex-info "sprites api error"
                      {:method method :path path :status status :body body})))))

(defn create-sprite!
  "Create a sprite named `name`. Sprite URLs are left on the default
   'sprite' auth — private — so the only ways in are this app's proxy
   (which attaches the API token) and the owner's own Sprites account."
  [client name]
  (request! client :post "/v1/sprites" {:name name}))

(defn get-sprite [client name]
  (request! client :get (str "/v1/sprites/" name)))

(def ^:private delete-retry-waits-ms
  ;; The API documents only 204/401/404 for delete, so a 5xx is an
  ;; undocumented, typically transient failure — or a concurrent delete (two
  ;; tabs / a double-click) catching the sprite mid-teardown. Retry a couple
  ;; of times with backoff before surfacing it: a sprite the API refuses to
  ;; delete leaks money. Each element is the wait before the next attempt, so
  ;; this is N attempts after the first.
  [1000 2000])

(defn delete-sprite!
  "Delete a sprite and all its state. Idempotent and self-healing: tolerates
   404 (already gone is the goal, not an error) and retries a 5xx with
   backoff (a transient platform error, or a racing concurrent delete that
   caught the sprite mid-teardown). Any other non-2xx propagates. Safe to
   call twice for the same sprite — the loser of a delete race just sees the
   404 and returns."
  [client name]
  (loop [waits delete-retry-waits-ms]
    (let [retry?
          (try
            (request! client :delete (str "/v1/sprites/" name))
            false
            (catch clojure.lang.ExceptionInfo e
              (let [status (:status (ex-data e))]
                (cond
                  (= 404 status)
                  (do (log/info "sprite already gone" {:sprite name}) false)

                  (and (some? status) (<= 500 status 599) (seq waits))
                  true

                  :else
                  (throw e)))))]
      (when retry?
        (log/warn "sprite delete returned 5xx; retrying" {:sprite name :wait-ms (first waits)})
        (Thread/sleep (long (first waits)))
        (recur (rest waits))))))

(defmethod ig/init-key :hosted-clay.sprites/client [_ {:keys [api-url token]}]
  {:api-url (str/replace (str api-url) #"/+$" "")
   :token   token})
