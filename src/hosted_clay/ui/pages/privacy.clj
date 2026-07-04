(ns hosted-clay.ui.pages.privacy
  (:require [clojure.java.io :as io]
            [hosted-clay.routes :as routes]
            [hosted-clay.ui.layout :as layout]
            [hosted-clay.ui.prose :as prose]))

;; The page body is authored as plain text in resources so the policy can be
;; edited without touching Clojure. Slurped per request (the file is small)
;; so an edit shows on the next refresh, no restart.
(def ^:private content-path "content/privacy.txt")

(defn- content []
  (if-let [res (io/resource content-path)]
    (slurp res)
    (throw (ex-info "Privacy content file is missing from resources"
                    {:path content-path}))))

(defn render []
  (layout/page
   "Privacy & terms"
   [:div
    (layout/site-header
     [:a {:href (routes/home)} "Home"]
     [:a {:href (routes/dashboard)} "Dashboard"])
    [:main
     [:section
      [:p.eyebrow "Data policy"]
      [:h1 "Privacy & terms"]]
     (into [:section.prose] (prose/render (content)))]
    (layout/site-footer
     [:a {:href (routes/home)} "Home"]
     [:a {:href "https://github.com/kirahowe/hosted-clay"} "GitHub"]
     [:a {:href "mailto:contact@kirahowe.com"} "Contact"])]))
