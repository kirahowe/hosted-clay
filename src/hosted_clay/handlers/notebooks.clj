(ns hosted-clay.handlers.notebooks
  "Owner-facing notebook endpoints: create, delete, and the
   authenticated proxy into the notebook's sprite."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [hosted-clay.notebooks :as notebooks]
            [hosted-clay.proxy :as proxy]
            [hosted-clay.sprites.exec :as exec]
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

(defmethod ig/init-key :hosted-clay.handlers.notebooks/restart
  [_ {:keys [datasource sprites-client]}]
  ;; Bounce a notebook when Clay/the REPL has died and `/n/:id/` is 502ing.
  ;; Restarts BOTH the `notebook` service (Clay + nREPL) and `code-server`:
  ;; Calva's auto-connect is one-shot at editor activation, so without also
  ;; restarting code-server it would be left pointing at the now-dead REPL.
  ;; Order matters — notebook first so its nREPL port drops, then
  ;; code-server, whose wait-repl.sh gate reconnects to the fresh nREPL.
  ;; Ownership-gated like the proxy; a miss is a 404. `restart` returns once
  ;; the processes are relaunched, not once Clay is serving again (~30s), so
  ;; this returns promptly and the workspace polls for readiness before
  ;; reloading. This is the one request path that uses the exec socket; a
  ;; restart is rare, deliberate, and one-at-a-time (the button disables
  ;; itself), so the low-concurrency assumption in hosted-clay.sprites.exec
  ;; holds.
  (fn [req]
    (let [notebook (notebooks/by-id datasource (get-in req [:path-params :id]))]
      (if (and notebook (notebooks/owned-by? notebook (:user-id req)))
        (let [{:keys [exit err]}
              (exec/exec! sprites-client (:notebooks/sprite-name notebook)
                          ["bash" "-c"
                           "sprite-env services restart notebook && sprite-env services restart code-server"]
                          :timeout-ms 30000)]
          (if (zero? exit)
            (response/no-content)
            (do (log/warn "notebook restart failed"
                          {:notebook-id (:notebooks/id notebook) :exit exit :err err})
                (response/text 502 "Could not restart the notebook environment."))))
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
