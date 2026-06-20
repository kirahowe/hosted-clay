(ns hosted-clay.handlers.notebooks
  "Owner-facing notebook endpoints: create, delete, and the
   authenticated proxy into the notebook's sprite."
  (:require [clojure.string :as str]
            [integrant.core :as ig]
            [hosted-clay.notebooks :as notebooks]
            [hosted-clay.proxy :as proxy]
            [hosted-clay.ui.pages.workspace :as workspace]
            [hosted-clay.web.response :as response]))

(defmethod ig/init-key :hosted-clay.handlers.notebooks/create
  [_ {:keys [datasource sprites-client max-sprites]}]
  (fn [req]
    (let [title  (let [t (str (get-in req [:params "title"]))]
                   (if (str/blank? t) "My notebook" (subs t 0 (min 120 (count t)))))
          result (try
                   (notebooks/create! datasource sprites-client
                                      {:max-sprites max-sprites}
                                      (:user-id req) title)
                   (catch clojure.lang.ExceptionInfo e
                     (if (= ::notebooks/budget-exceeded (:type (ex-data e)))
                       ::at-capacity
                       (throw e))))]
      (case result
        ::notebooks/already-exists (response/see-other "/dashboard")
        ::at-capacity (response/html 503
                                     (str "We're at capacity right now — no new notebooks "
                                          "can be created. Please try again later."))
        (response/see-other (str "/notebooks/" (:notebooks/id result)))))))

(defmethod ig/init-key :hosted-clay.handlers.notebooks/workspace
  [_ {:keys [datasource base-url]}]
  ;; The editing workspace page: editor and live output side by side.
  ;; Ownership-gated like the proxy, and a miss is a 404 (not a 403) for
  ;; the same reason — notebook ids stay unprobeable.
  (fn [req]
    (let [notebook (notebooks/by-id datasource (get-in req [:path-params :id]))]
      (if (and notebook (notebooks/owned-by? notebook (:user-id req)))
        (response/html (workspace/render notebook base-url))
        (response/not-found "No such notebook.")))))

(defmethod ig/init-key :hosted-clay.handlers.notebooks/delete
  [_ {:keys [datasource sprites-client]}]
  (fn [req]
    (let [notebook (notebooks/by-id datasource (get-in req [:path-params :id]))]
      (if (and notebook (notebooks/owned-by? notebook (:user-id req)))
        (do (notebooks/delete! datasource sprites-client notebook)
            (response/see-other "/dashboard"))
        (response/not-found "No such notebook.")))))

(defmethod ig/init-key :hosted-clay.handlers.notebooks/open
  [_ {:keys [datasource sprites-client]}]
  ;; The catch-all proxy: /n/:id/{*path} for every method, WebSockets
  ;; included. Ownership is the only access check — share links go
  ;; through /s/ instead. A miss is a 404, not a 403, so notebook ids
  ;; aren't probeable.
  (fn [req]
    (let [notebook (notebooks/by-id datasource (get-in req [:path-params :id]))]
      (if (and notebook (notebooks/owned-by? notebook (:user-id req)))
        (do (notebooks/touch! datasource notebook)
            (proxy/forward sprites-client
                           (:notebooks/sprite-url notebook)
                           (get-in req [:path-params :path])
                           req
                           {:strip-framing? true}))
        (response/not-found "No such notebook.")))))

(defmethod ig/init-key :hosted-clay.handlers.notebooks/open-root [_ _]
  ;; /n/:id -> /n/:id/ so relative asset URLs in the proxied pages
  ;; resolve under the notebook prefix.
  (fn [req]
    (response/see-other (str "/n/" (get-in req [:path-params :id]) "/"))))
