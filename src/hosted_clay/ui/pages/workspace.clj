(ns hosted-clay.ui.pages.workspace
  "The editing workspace: the code-server editor and the live
   Clay-rendered output side by side in one screen. Both panes are
   same-origin iframes onto the existing owner proxy, so saving in the
   editor re-renders the output via Clay's live-reload. While a notebook's
   sprite is still provisioning (or if it failed) this same route shows a
   status page instead."
  (:require [hosted-clay.routes :as routes]
            [hosted-clay.ui.layout :as layout]))

(defn render [notebook base-url]
  (let [id         (:notebooks/id notebook)
        title      (:notebooks/title notebook)
        editor-src (routes/editor id)
        share-url  (routes/absolute base-url (routes/share (:notebooks/share-token notebook)))]
    (layout/page
     title
     [:div.workspace {:data-notebook-id id}
      [:header.workspace-bar
       [:a.workspace-home {:href (routes/dashboard)} "← Dashboard"]
       [:span.workspace-title title]
       [:nav.workspace-actions
        [:form.inline-form {:method "post" :action (routes/notebook-suspend id)}
         [:input {:type "hidden" :name "return" :value (routes/notebook id)}]
         [:button.workspace-action
          {:type "submit"
           :title "Suspend the notebook so its sprite sleeps and stops billing"}
          "Suspend"]]
        [:button.workspace-action.workspace-restart
         {:type "button"
          :title "Restart the notebook environment if the output stops responding"}
         "Restart"]
        [:button.workspace-action {:type "button" :data-copy share-url}
         "Copy share link"]]]
      [:div.workspace-panes
       [:section.workspace-pane.workspace-editor
        [:div.editor-loading {:data-editor-loading true}
         [:div.status-card
          [:div.spinner {:role "status" :aria-label "Starting the editor"}]
          [:p.muted "Starting the editor, opening your notebook, and "
           "connecting the REPL. This can take a few seconds."]]]
        [:iframe {:src   editor-src
                  :title "Editor"
                  :allow "clipboard-read; clipboard-write"}]]
       [:div.workspace-divider {:role "separator" :aria-orientation "vertical"}]
       [:section.workspace-pane.workspace-output
        [:iframe {:src   (routes/notebook-view id)
                  :title "Rendered output"}]]]
      [:script {:src "/static/js/workspace.js"}]]
     {:head       [:link {:rel "stylesheet" :href "/static/css/workspace.css"}]
      :body-class "workspace-body"})))

(defn render-source
  "The notebook's raw .clj, served from the last snapshot so the owner can copy
   their code anywhere — even while the notebook is paused for the month.
   `source` is nil until the first snapshot has been captured."
  [notebook source]
  (let [title (:notebooks/title notebook)]
    (layout/page
     (str title " — source")
     [:div
      (layout/site-header [:a {:href (routes/dashboard)} "← Dashboard"])
      [:main
       [:p.eyebrow "Source"]
       [:h1 title]
       (if source
         (list
          [:p.lead
           "Your most recently saved notebook source code, available even while the notebook is paused."]
          [:div.actions
           [:button.button.copy {:type "button" :data-copy source} "Copy source"]]
          [:pre.source [:code source]])
         [:p.lead
          "We haven't captured a snapshot of this notebook yet. Snapshots are "
          "taken while it's awake, so check back in a few minutes."])]])))

(defn- status-page
  "Every non-ready notebook state renders through the shared `layout/status-page`
   template, with a back-to-dashboard link in the header and the notebook title
   as the document title — so the provisioning / suspended / over-limit / failed
   screens stay visually identical to each other and to the 4xx/5xx pages."
  [notebook suffix opts]
  (layout/status-page
   (merge {:title (str (:notebooks/title notebook) suffix)
           :nav   [[:a {:href (routes/dashboard)} "← Dashboard"]]}
          opts)))

(defn render-provisioning [notebook]
  (status-page
   notebook " — setting up"
   {:main-attrs {:data-provision (:notebooks/id notebook)}
    :spinner?   true
    :eyebrow    "Setting up"
    :heading    "Spinning up your notebook"
    :lead       "This can sometimes take a few minutes."}))

(defn render-over-limit
  "Shown when a notebook has spent its monthly active-hours budget: the sprite
   stays suspended (so it isn't billed) until the month rolls over, and the work
   is untouched."
  [notebook limit-hours]
  (status-page
   notebook " — paused for the month"
   {:eyebrow "Monthly limit reached"
    :heading "This notebook is paused for the month"
    :lead    (str "It's used its " limit-hours " active hours for this month, so it's paused for now. Your work is saved and stays available read-only, and the limit resets at the start of next month.")
    :actions [[:a.button.button--primary {:href (routes/dashboard)} "← Back to dashboard"]
              [:a.button {:href (routes/notebook-source (:notebooks/id notebook))}
               "View source"]]}))

(defn render-suspended
  "Shown for a notebook the owner has manually suspended: its sprite is asleep
   (not billing) and stays that way until they resume. No iframes render, so the
   page itself never wakes the sprite."
  [notebook]
  (let [id (:notebooks/id notebook)]
    (status-page
     notebook " — suspended"
     {:eyebrow "Suspended"
      :heading "This notebook is suspended"
      :lead    (str "Suspended notebooks do not count toward your monthly usage. "
                    "Your work and running session are saved, you can resume to pick up right where you left off.")
      :actions [[:form.inline-form {:method "post" :action (routes/notebook-resume id)}
                 [:input {:type "hidden" :name "return" :value (routes/notebook id)}]
                 [:button.button--primary {:type "submit"} "Resume notebook"]]
                [:a.button {:href (routes/dashboard)} "← Back to dashboard"]]})))

(defn render-failed [notebook]
  (let [id (:notebooks/id notebook)]
    (status-page
     notebook " — setup failed"
     {:eyebrow "Setup failed"
      :heading "Setup didn't finish"
      :lead    "Something went wrong while building your environment. You can try again, or delete this notebook and start over."
      :actions [[:form.inline-form {:method "post" :action (routes/notebook-retry id)
                                    :data-submit-label "Retrying…"}
                 [:button.button--primary {:type "submit"} "Try again"]]
                [:form.inline-form {:method "post" :action (routes/notebook-delete id)
                                    :onsubmit "return confirm('Delete this notebook? This cannot be undone.')"}
                 [:button.button--danger {:type "submit"} "Delete"]]]})))
