(ns hosted-clay.ui.pages.dashboard
  (:require [hosted-clay.ui.layout :as layout]))

(defn- header []
  (layout/site-header
   [:form {:method "post" :action "/logout"}
    [:button {:type "submit"} "Sign out"]]))

(defn- no-notebook []
  [:section
   [:h2 "Create your notebook"]
   [:p
    "You get one free notebook: a private Linux machine with Clay, "
    "Noj, and a browser editor, ready in seconds."]
   [:form {:method "post" :action "/notebooks"}
    [:label {:for "title"} "Name"]
    [:input {:type "text" :id "title" :name "title"
             :value "My notebook" :required true :maxlength 120}]
    [:p [:button {:type "submit"} "Create notebook"]]]
   [:p.muted
    "If no pre-warmed environment is available, creation can take a few "
    "minutes while one is built for you."]])

(defn- notebook-card [notebook base-url]
  (let [id        (:notebooks/id notebook)
        share-url (str base-url "/s/" (:notebooks/share-token notebook) "/")]
    [:section
     [:h2 (:notebooks/title notebook)]
     [:dl
      [:dt "Open"]
      [:dd [:a {:href (str "/n/" id "/")} "Rendered notebook"]
       " · "
       [:a {:href (str "/n/" id "/edit/")} "Editor"]]
      [:dt "Share"]
      [:dd [:a {:href share-url} [:code share-url]]
       [:p.muted "Anyone with this link can view the rendered notebook (read-only)."]]
      [:dt "Created"]
      [:dd (:notebooks/created-at notebook)]]
     [:hr]
     [:form {:method "post" :action (str "/notebooks/" id "/delete")
             :onsubmit "return confirm('Delete this notebook and its environment? This cannot be undone.')"}
      [:button {:type "submit"} "Delete notebook"]]
     [:p.muted "Notebooks untouched for 30 days are deleted automatically; "
      "we email a warning first."]]))

(defn render [user notebook base-url]
  (layout/page
   "Dashboard"
   [:div
    (header)
    [:main
     [:h1 "Dashboard"]
     [:p.muted (:users/email user)]
     (if notebook
       (notebook-card notebook base-url)
       (no-notebook))]]))
