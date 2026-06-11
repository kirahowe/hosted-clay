(ns hosted-clay.ui.pages.home
  (:require [hosted-clay.ui.layout :as layout]))

(defn render []
  (layout/page
   "Clay notebooks"
   [:div
    (layout/site-header
     [:a {:href "/dashboard"} "Dashboard"]
     [:a {:href "/login"} "Sign in"])
    [:main
     [:h1 "Clojure data science, zero setup"]
     [:p
      "Hosted " [:a {:href "https://scicloj.github.io/clay/"} "Clay"]
      " notebooks for the " [:a {:href "https://scicloj.github.io/"} "scicloj"]
      " ecosystem. Sign in, create a notebook, and you're editing Clojure "
      "in the browser with Tablecloth, Tableplot, and the rest of "
      [:a {:href "https://scicloj.github.io/noj/"} "Noj"]
      " already on the classpath."]
     [:ul
      [:li "A real editor (VS Code with Calva) and a live nREPL"]
      [:li "Clay re-renders the notebook on every save"]
      [:li "Share a read-only link with anyone"]]
     [:p [:a {:href "/dashboard"} "Create your notebook"] " — it's free."]]]))
