(ns hosted-clay.ui.pages.workspace
  "The editing workspace: the code-server editor and the live
   Clay-rendered output side by side in one screen. Both panes are
   same-origin iframes onto the existing owner proxy, so saving in the
   editor re-renders the output via Clay's live-reload."
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
        [:button.workspace-restart {:type "button"
                                    :title "Restart the notebook environment if the output stops responding"}
         "Restart"]
        [:a {:href editor-src :target "_blank" :rel "noopener"} "Editor ↗"]
        [:a {:href (str "/n/" id "/") :target "_blank" :rel "noopener"} "Output ↗"]
        [:a {:href share-url :target "_blank" :rel "noopener"} "Share link ↗"]]]
      [:div.workspace-panes
       [:section.workspace-pane.workspace-editor
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
