(ns hosted-clay.handlers.share
  "The public read-only view: /s/:token/* proxies the Clay-rendered
   notebook to anyone holding the share token. Reads only — non-GET
   methods are refused — and the editor is unreachable: /edit/* (and so
   code-server, WebSockets included) never leaves this handler. Clay's
   live-reload WebSocket is a GET off a non-edit path, so viewers still
   get re-renders pushed."
  (:require [clojure.string :as str]
            [integrant.core :as ig]
            [hosted-clay.notebooks :as notebooks]
            [hosted-clay.proxy :as proxy]
            [hosted-clay.usage :as usage]
            [hosted-clay.web.response :as response]))

(defn- editor-path? [path]
  (let [p (str/lower-case (str path))]
    (or (= p "edit") (str/starts-with? p "edit/"))))

(defmethod ig/init-key :hosted-clay.handlers/share
  [_ {:keys [datasource sprites-client usage-limit-hours]}]
  (fn [req]
    (let [notebook (notebooks/by-share-token datasource (get-in req [:path-params :token]))
          path     (get-in req [:path-params :path])]
      (cond
        (nil? notebook)
        (response/not-found "This share link doesn't point anywhere (anymore).")

        (not (contains? #{:get :head} (:request-method req)))
        {:status 405 :headers {"allow" "GET, HEAD"} :body ""}

        (editor-path? path)
        (response/forbidden "The editor isn't part of the read-only view.")

        ;; Block the read-only view too once the owner is over budget, so
        ;; viewers can't keep the sprite awake (and billing) on the owner's tab.
        ;; A 503 (temporary), not a 403 — it un-pauses on the month rollover.
        (usage/notebook-over-limit? notebook usage-limit-hours)
        (response/unavailable "This notebook has reached its monthly limit and is paused until next month.")

        :else
        (proxy/forward sprites-client (:notebooks/sprite-url notebook) path req)))))

(defmethod ig/init-key :hosted-clay.handlers/share-root [_ _]
  (fn [req]
    (response/see-other (str "/s/" (get-in req [:path-params :token]) "/"))))
