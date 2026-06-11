(ns hosted-clay.auth.middleware
  "The authentication gate. A reitit middleware mounted on the whole
   router that lets `:public?` routes through and, for everything else,
   verifies the Hanko session cookie, provisions/loads the local user,
   and attaches `:user`/`:user-id` to the request. Unauthenticated
   requests redirect a browser GET/HEAD to `/login` and answer other
   methods with 401."
  (:require [integrant.core :as ig]
            [hosted-clay.auth :as auth]
            [hosted-clay.users :as users]
            [hosted-clay.web.response :as response]))

(defn- public? [req]
  (boolean (-> req :reitit.core/match :data :public?)))

(defn- session-token
  "The raw `hanko` session JWT from the Cookie header, or nil."
  [req]
  (some->> (get-in req [:headers "cookie"])
           (re-find #"(?:^|;\s*)hanko=([^;]+)")
           second))

(defn- unauthenticated [req]
  ;; HEAD is a safe browser/crawler method that should mirror GET, so it
  ;; gets the same /login redirect; other methods (POST etc.) get a 401.
  (if (contains? #{:get :head} (:request-method req))
    (response/see-other "/login")
    {:status 401 :headers {"content-type" "text/plain"} :body "unauthenticated"}))

(defn wrap-auth [handler {:keys [jwks-url datasource]}]
  (fn [req]
    (if (public? req)
      (handler req)
      (if-let [claims (auth/verify-token jwks-url (session-token req))]
        (let [user (users/provision! datasource (auth/claims->identity-attrs claims))]
          (handler (assoc req :user user :user-id (:users/id user))))
        (unauthenticated req)))))

(defmethod ig/init-key :hosted-clay.auth/middleware
  [_ {:keys [jwks-url api-url datasource]}]
  ;; Derive the JWKS URL from the Hanko API base; an explicit `jwks-url`
  ;; still wins so tests verify against a committed local JWKS.
  (let [jwks-url (or jwks-url (auth/jwks-url-for api-url))]
    {:name ::auth
     :wrap (fn [handler]
             (wrap-auth handler {:jwks-url   jwks-url
                                 :datasource datasource}))}))
