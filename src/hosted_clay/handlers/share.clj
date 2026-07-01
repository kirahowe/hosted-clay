(ns hosted-clay.handlers.share
  "The public read-only view: /s/:id/*, keyed on the notebook id (a random UUID,
   so unguessable). Once a notebook has a rendered snapshot, this serves that
   static HTML straight from the control plane — no sprite contact, so it costs
   nothing and works even while the notebook is paused for the month. Until the
   first snapshot lands (a brand-new notebook), it falls back to proxying the
   live Clay-rendered notebook. Reads only — non-GET methods are refused — and
   the editor is unreachable: /edit/* (and so code-server, WebSockets included)
   never leaves this handler."
  (:require [clojure.string :as str]
            [integrant.core :as ig]
            [hosted-clay.notebooks :as notebooks]
            [hosted-clay.proxy :as proxy]
            [hosted-clay.routes :as routes]
            [hosted-clay.snapshot :as snapshot]
            [hosted-clay.usage :as usage]
            [hosted-clay.web.response :as response]))

(defn- editor-path? [path]
  (let [p (str/lower-case (str path))]
    (or (= p "edit") (str/starts-with? p "edit/"))))

(defn- doc-path?
  "The share view's root document — Clay serves the notebook at /. The snapshot
   is self-contained (CDN assets, no sub-resources), so only the root maps to it."
  [path]
  (let [p (str path)]
    (or (str/blank? p) (= p "index.html"))))

(defn- snapshot-response
  "Serve the stored render as the document. `no-cache` so a viewer always
   revalidates and never sees a render older than the latest snapshot; a HEAD
   gets the headers without the (~MB) body."
  [req html]
  (cond-> (assoc-in (response/html html) [:headers "cache-control"] "no-cache")
    (= :head (:request-method req)) (assoc :body "")))

(defmethod ig/init-key :hosted-clay.handlers/share
  [_ {:keys [datasource sprites-client usage-limit-hours]}]
  (fn [req]
    (let [notebook (notebooks/by-id datasource (get-in req [:path-params :id]))
          path     (get-in req [:path-params :path])
          html     (when notebook
                     (:notebook-snapshots/html
                      (snapshot/for-notebook datasource (:notebooks/id notebook))))]
      (cond
        (nil? notebook)
        (response/not-found "This share link doesn't point anywhere (anymore).")

        (not (contains? #{:get :head} (:request-method req)))
        {:status 405 :headers {"allow" "GET, HEAD"} :body ""}

        (editor-path? path)
        (response/forbidden "The editor isn't part of the read-only view.")

        ;; Prefer the static snapshot: the last rendered notebook, served from
        ;; the control plane with no sprite contact — so it never wakes (or
        ;; bills) the sprite and works even while the notebook is paused. The
        ;; render is self-contained, so the whole view is this one document; any
        ;; sub-path isn't part of it.
        (and html (doc-path? path))
        (snapshot-response req html)

        html
        (response/not-found "This is a static snapshot; that path isn't part of it.")

        ;; No snapshot yet (brand-new notebook) → fall back to the live sprite,
        ;; unless the owner is over budget, in which case stay paused so viewers
        ;; can't keep it awake (and billing) on the owner's behalf.
        (usage/user-over-limit? datasource (:notebooks/user-id notebook) usage-limit-hours)
        (response/unavailable "This notebook has reached its monthly limit and is paused until next month.")

        ;; Likewise don't wake a sprite the owner has manually suspended.
        (notebooks/suspended? notebook)
        (response/unavailable "The owner has suspended this notebook. Check back later.")

        :else
        (proxy/forward sprites-client (:notebooks/sprite-url notebook) path req)))))

(defmethod ig/init-key :hosted-clay.handlers/share-root [_ _]
  (fn [req]
    (response/see-other (routes/share (get-in req [:path-params :id])))))
