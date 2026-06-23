(ns hosted-clay.ui.pages.dashboard
  (:require [hosted-clay.ui.layout :as layout]))

(defn- header []
  (layout/site-header
   [:form {:method "post" :action "/logout"}
    [:button {:type "submit"} "Sign out"]]))

(defn- day
  "The date portion of an ISO-8601 timestamp, for display."
  [ts]
  (when ts (subs ts 0 (min 10 (count ts)))))

(defn- status-badge [status]
  (let [cls (case status
              "ready"  "badge--ready"
              "failed" "badge--failed"
              "badge--provisioning")]
    [:span.badge {:class cls} status]))

(defn- no-notebook []
  [:section.card.card--accent
   [:p.eyebrow "Start here"]
   [:h2 "Create your notebook"]
   [:p.lead
    "You get one free notebook: a private Linux machine with Clay, "
    "Noj, and a browser editor, ready in seconds."]
   [:form {:method "post" :action "/notebooks" :data-submit-label "Creating…"}
    [:div.field
     [:label {:for "title"} "Name"]
     [:input {:type "text" :id "title" :name "title"
              :value "My notebook" :required true :maxlength 120}]]
    [:button.button--primary {:type "submit"} "Create notebook"]]
   [:p.subtle
    "If no pre-warmed environment is free, creating one takes a couple of "
    "minutes while it's built — we'll show progress."]])

(defn- delete-form [id]
  [:form {:method "post" :action (str "/notebooks/" id "/delete")
          :onsubmit "return confirm('Delete this notebook and its environment? This cannot be undone.')"}
   [:button.button--danger {:type "submit"} "Delete notebook"]])

(defn- ready-body [id share-url]
  (list
   [:div.actions
    [:a.button.button--primary {:href (str "/notebooks/" id)} "Open notebook"]
    [:span.muted "Edit the source and see the rendered output side by side."]]
   [:h3 "Share"]
   [:div.copyable
    [:code share-url]
    [:button.copy {:type "button" :data-copy share-url} "Copy"]]
   [:p.muted "Anyone with this link can view the rendered notebook (read-only)."]
   [:hr]
   (delete-form id)
   [:p.subtle
    "Untouched for 30 days, a notebook is deleted automatically; "
    "we email a warning first."]))

(defn- provisioning-body [id]
  (list
   [:p.status-line [:span.spinner] "Setting up the environment…"]
   [:div.actions
    [:a.button {:href (str "/notebooks/" id)} "View progress"]]))

(defn- failed-body [id]
  (list
   [:p.notice.notice--error "Setup didn't finish."]
   [:a.button.button--primary {:href (str "/notebooks/" id)} "Open notebook"]
   [:hr]
   (delete-form id)))

(defn- notebook-card [notebook base-url]
  (let [id        (:notebooks/id notebook)
        status    (:notebooks/status notebook)
        share-url (str base-url "/s/" (:notebooks/share-token notebook) "/")]
    [:section.card
     [:div.section-head
      [:h2 (:notebooks/title notebook)]
      [:dl.meta
       [:dt "created"] [:dd (day (:notebooks/created-at notebook))]
       [:dt "status"]  [:dd (status-badge status)]]]
     (case status
       "ready"  (ready-body id share-url)
       "failed" (failed-body id)
       (provisioning-body id))]))

(defn render [user notebook base-url]
  (layout/page
   "Dashboard"
   [:div
    (header)
    [:main
     [:p.eyebrow "Dashboard"]
     [:h1 "Your notebook"]
     [:p.lead [:span.mono (:users/email user)]]
     (if notebook
       (notebook-card notebook base-url)
       (no-notebook))]]))
