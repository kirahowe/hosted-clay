(ns hosted-clay.ui.pages.workspace
  "The editing workspace: the code-server editor and the live
   Clay-rendered output side by side in one screen. Both panes are
   same-origin iframes onto the existing owner proxy, so saving in the
   editor re-renders the output via Clay's live-reload. While a notebook's
   sprite is still provisioning (or if it failed) this same route shows a
   status page instead."
  (:require [hosted-clay.ui.layout :as layout]))

(defn render [notebook base-url]
  (let [id         (:notebooks/id notebook)
        title      (:notebooks/title notebook)
        ;; `?folder` pins the workspace from the browser side — code-server
        ;; resolves the folder as query-param > CLI arg > last-opened, so the
        ;; editor reliably opens the notebook folder (and Calva sees the
        ;; project) regardless of any stale persisted state.
        editor-src (str "/n/" id "/edit/?folder=/home/sprite/notebook")
        share-url  (str base-url "/s/" (:notebooks/share-token notebook) "/")]
    (layout/page
     title
     [:div.workspace {:data-notebook-id id}
      [:header.workspace-bar
       [:a.workspace-home {:href "/dashboard"} "← Dashboard"]
       [:span.workspace-title title]
       [:nav.workspace-actions
        [:form.inline-form {:method "post" :action (str "/notebooks/" id "/suspend")}
         [:input {:type "hidden" :name "return" :value (str "/notebooks/" id)}]
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
          [:p.muted "Starting the editor — opening your notebook and "
           "connecting the REPL. This takes a few seconds."]]]
        [:iframe {:src   editor-src
                  :title "Editor"
                  :allow "clipboard-read; clipboard-write"}]]
       [:div.workspace-divider {:role "separator" :aria-orientation "vertical"}]
       [:section.workspace-pane.workspace-output
        [:iframe {:src   (str "/n/" id "/")
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
      (layout/site-header [:a {:href "/dashboard"} "← Dashboard"])
      [:main
       [:p.eyebrow "Source"]
       [:h1 title]
       (if source
         (list
          [:p.lead
           "Your most recently saved notebook source, served from a snapshot — "
           "so you can copy it anywhere, even while the notebook is paused."]
          [:div.actions
           [:button.button.copy {:type "button" :data-copy source} "Copy source"]]
          [:pre.source [:code source]])
         [:p.lead
          "We haven't captured a snapshot of this notebook yet — snapshots are "
          "taken while it's awake, so check back in a few minutes."])]])))

(defn- status-page
  "Every non-ready notebook state renders through the shared `layout/status-page`
   template, with a back-to-dashboard link in the header and the notebook title
   as the document title — so the provisioning / suspended / over-limit / failed
   screens stay visually identical to each other and to the 4xx/5xx pages."
  [notebook suffix opts]
  (layout/status-page
   (merge {:title (str (:notebooks/title notebook) suffix)
           :nav   [[:a {:href "/dashboard"} "← Dashboard"]]}
          opts)))

(defn render-provisioning [notebook]
  (status-page
   notebook " — setting up"
   {:main-attrs {:data-provision (:notebooks/id notebook)}
    :spinner?   true
    :eyebrow    "Setting up"
    :heading    "Spinning up your notebook"
    :lead       "This can sometimes take a couple of minutes."}))

(defn render-over-limit
  "Shown when a notebook has spent its monthly active-hours budget: the sprite
   stays suspended (so it isn't billed) until the month rolls over, and the work
   is untouched."
  [notebook limit-hours]
  (status-page
   notebook " — paused for the month"
   {:eyebrow "Monthly limit reached"
    :heading "This notebook is paused for the month"
    :lead    (str "It's used its " limit-hours " active hours for this month, so "
                  "it's paused to keep costs in check. Your work is saved — it'll "
                  "be available again at the start of next month.")
    :actions [[:a.button.button--primary {:href "/dashboard"} "← Back to dashboard"]
              [:a.button {:href (str "/notebooks/" (:notebooks/id notebook) "/source")}
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
      :lead    (str "You suspended it, so its sprite is asleep and not billing. "
                    "Resume to pick up right where you left off — your work and "
                    "running session are saved.")
      :actions [[:form.inline-form {:method "post" :action (str "/notebooks/" id "/resume")}
                 [:input {:type "hidden" :name "return" :value (str "/notebooks/" id)}]
                 [:button.button--primary {:type "submit"} "Resume notebook"]]
                [:a.button {:href "/dashboard"} "← Back to dashboard"]]})))

(defn render-failed [notebook]
  (let [id (:notebooks/id notebook)]
    (status-page
     notebook " — setup failed"
     {:eyebrow "Setup failed"
      :heading "Setup didn't finish"
      :lead    (str "Something went wrong while building your environment. You can "
                    "try again, or delete it and start over.")
      :actions [[:form.inline-form {:method "post" :action (str "/notebooks/" id "/retry")
                                    :data-submit-label "Retrying…"}
                 [:button.button--primary {:type "submit"} "Try again"]]
                [:form.inline-form {:method "post" :action (str "/notebooks/" id "/delete")
                                    :onsubmit "return confirm('Delete this notebook? This cannot be undone.')"}
                 [:button.button--danger {:type "submit"} "Delete"]]]})))
