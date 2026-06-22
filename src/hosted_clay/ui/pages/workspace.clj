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
        [:button.workspace-action.workspace-restart
         {:type "button"
          :title "Restart the notebook environment if the output stops responding"}
         "Restart"]
        [:button.workspace-action {:type "button" :data-copy share-url}
         "Copy share link"]
        (layout/theme-toggle)]]
      [:div.workspace-panes
       [:section.workspace-pane.workspace-editor
        [:div.editor-loading {:data-editor-loading true}
         [:div.status-card
          [:div.spinner {:role "status" :aria-label "Starting the editor"}]
          [:p.muted "Starting the editor — opening your notebook and a "
           "terminal. This takes a few seconds."]]]
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

(defn- status-page
  "The shared chrome for the non-ready states: standard layout, a back
   link, and a centred status card holding `card` (a seq of children).
   `main-attrs` lets the provisioning page hang its poll hook on <main>."
  [notebook suffix main-attrs card]
  (layout/page
   (str (:notebooks/title notebook) suffix)
   [:div
    (layout/site-header [:a {:href "/dashboard"} "← Dashboard"])
    [:main main-attrs
     [:section.status
      [:div.status-card card]]]]))

(defn render-provisioning [notebook]
  (status-page
   notebook " — setting up" {:data-provision (:notebooks/id notebook)}
   (list
    [:div.spinner {:role "status" :aria-label "Setting up"}]
    [:h1 "Spinning up your notebook"]
    [:p.muted "This can sometimes take a couple of minutes."])))

(defn render-failed [notebook]
  (let [id (:notebooks/id notebook)]
    (status-page
     notebook " — setup failed" {}
     (list
      [:h1 "Setup didn't finish"]
      [:p.muted
       "Something went wrong while building your environment. You can try "
       "again, or delete it and start over."]
      [:div.actions
       [:form {:method "post" :action (str "/notebooks/" id "/retry")
               :data-submit-label "Retrying…"}
        [:button.button--primary {:type "submit"} "Try again"]]
       [:form {:method "post" :action (str "/notebooks/" id "/delete")
               :onsubmit "return confirm('Delete this notebook? This cannot be undone.')"}
        [:button.button--danger {:type "submit"} "Delete"]]]))))
