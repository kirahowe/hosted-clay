(ns hosted-clay.ui.pages.waking
  "The page shown inside a notebook iframe when its sprite isn't answering
   yet — a cold VM waking, or Clay still starting. It refreshes itself
   (meta refresh) so it's replaced by the real notebook the moment the
   sprite responds; no header, since it renders embedded in the workspace."
  (:require [hosted-clay.ui.layout :as layout]))

(defn render []
  (layout/page
   "Waking up…"
   [:main
    [:section.status
     [:div.status-card
      [:div.spinner {:role "status" :aria-label "Waking up"}]
      [:h1 "Waking up"]
      [:p.muted
       "This notebook is starting back up. This can take up to a minute; "
       "the page refreshes itself."]]]]
   {:head [:meta {:http-equiv "refresh" :content "4"}]}))
