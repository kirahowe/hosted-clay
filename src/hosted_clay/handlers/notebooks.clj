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

(defn- owned-notebook
  "The request's target notebook if the caller owns it, else nil."
  [datasource req]
  (let [nb (notebooks/by-id datasource (get-in req [:path-params :id]))]
    (when (and nb (notebooks/owned-by? nb (:user-id req))) nb)))

(defn- with-owned-notebook
  "Pass the request's notebook to `f` if the caller owns it, otherwise 404.
   Ownership is the only access check, and a miss is a 404 (not a 403) so
   notebook ids stay unprobeable."
  [datasource req f]
  (if-let [nb (owned-notebook datasource req)]
    (f nb)
    (response/not-found "No such notebook.")))

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
      (cond
        (= ::notebooks/already-exists result)
        (response/see-other "/dashboard")

        (= ::at-capacity result)
        (response/html 503
                       (str "We're at capacity right now — no new notebooks "
                            "can be created. Please try again later."))

        :else
        (do
          ;; An empty pool yields a 'provisioning' row; build its sprite off
          ;; the request thread so the POST returns at once and the workspace
          ;; page can show progress.
          (when (= "provisioning" (:notebooks/status result))
            (future (notebooks/finish-provisioning! datasource sprites-client result)))
          (response/see-other (str "/notebooks/" (:notebooks/id result))))))))

(defmethod ig/init-key :hosted-clay.handlers.notebooks/workspace
  [_ {:keys [datasource base-url]}]
  ;; The editing workspace page: editor and live output side by side once
  ;; the notebook is ready; a progress page while its sprite provisions; an
  ;; error page if that failed. Ownership-gated like the proxy, and a miss
  ;; is a 404 (not a 403) so notebook ids stay unprobeable.
  (fn [req]
    (with-owned-notebook datasource req
      (fn [notebook]
        (response/html
         (case (:notebooks/status notebook)
           "ready"  (workspace/render notebook base-url)
           "failed" (workspace/render-failed notebook)
           (workspace/render-provisioning notebook)))))))

(defmethod ig/init-key :hosted-clay.handlers.notebooks/status
  [_ {:keys [datasource]}]
  ;; The provisioning page polls this to learn when its sprite is ready.
  (fn [req]
    (with-owned-notebook datasource req
      (fn [notebook]
        (response/json {:status (:notebooks/status notebook)})))))

(defmethod ig/init-key :hosted-clay.handlers.notebooks/retry
  [_ {:keys [datasource sprites-client]}]
  ;; Re-provision a notebook whose first build failed. Re-provisioning a
  ;; notebook in any other state is a no-op; either way we land back on it.
  (fn [req]
    (with-owned-notebook datasource req
      (fn [notebook]
        (when (= "failed" (:notebooks/status notebook))
          (when-let [reset (notebooks/retry-provisioning! datasource notebook)]
            (future (notebooks/finish-provisioning! datasource sprites-client reset))))
        (response/see-other (str "/notebooks/" (:notebooks/id notebook)))))))

(defmethod ig/init-key :hosted-clay.handlers.notebooks/delete
  [_ {:keys [datasource sprites-client]}]
  (fn [req]
    (with-owned-notebook datasource req
      (fn [notebook]
        (notebooks/delete! datasource sprites-client notebook)
        (response/see-other "/dashboard")))))

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
    (with-owned-notebook datasource req
      (fn [notebook]
        (let [{:keys [exit err]}
              (exec/exec! sprites-client (:notebooks/sprite-name notebook)
                          ["bash" "-c"
                           "sprite-env services restart notebook && sprite-env services restart code-server"]
                          :timeout-ms 30000)]
          (if (zero? exit)
            (response/no-content)
            (do (log/warn "notebook restart failed"
                          {:notebook-id (:notebooks/id notebook) :exit exit :err err})
                (response/text 502 "Could not restart the notebook environment."))))))))

(defmethod ig/init-key :hosted-clay.handlers.notebooks/open
  [_ {:keys [datasource sprites-client]}]
  ;; The catch-all proxy: /n/:id/{*path} for every method, WebSockets
  ;; included. Ownership is the only access check — share links go
  ;; through /s/ instead. A miss is a 404, not a 403, so notebook ids
  ;; aren't probeable.
  (fn [req]
    (with-owned-notebook datasource req
      (fn [notebook]
        (notebooks/touch! datasource notebook)
        (proxy/forward sprites-client
                       (:notebooks/sprite-url notebook)
                       (get-in req [:path-params :path])
                       req
                       {:strip-framing? true})))))

(defmethod ig/init-key :hosted-clay.handlers.notebooks/open-root [_ _]
  ;; /n/:id -> /n/:id/ so relative asset URLs in the proxied pages
  ;; resolve under the notebook prefix.
  (fn [req]
    (response/see-other (str "/n/" (get-in req [:path-params :id]) "/"))))
