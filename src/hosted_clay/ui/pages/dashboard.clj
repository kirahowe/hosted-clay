(ns hosted-clay.ui.pages.dashboard
  (:require [hosted-clay.ui.layout :as layout]
            [hosted-clay.usage :as usage]))

(defn- header []
  (layout/site-header
   [:form {:method "post" :action "/logout"}
    [:button {:type "submit"} "Sign out"]]))

(defn- day
  "The date portion of an ISO-8601 timestamp, for display."
  [ts]
  (when ts (subs ts 0 (min 10 (count ts)))))

(defn- suspended? [notebook]
  (some? (:notebooks/suspended-at notebook)))

(defn- status-badge [notebook]
  (if (suspended? notebook)
    [:span.badge.badge--suspended "suspended"]
    (let [status (:notebooks/status notebook)
          cls    (case status
                   "ready"  "badge--ready"
                   "failed" "badge--failed"
                   "badge--provisioning")]
      [:span.badge {:class cls} status])))

(defn- suspend-toggle
  "Suspend or Resume, whichever applies, as a one-button form back to the
   dashboard."
  [id suspended?]
  [:form.inline-form {:method "post"
                      :action (str "/notebooks/" id "/" (if suspended? "resume" "suspend"))}
   [:input {:type "hidden" :name "return" :value "/dashboard"}]
   (if suspended?
     [:button.button--primary {:type "submit"} "Resume"]
     [:button {:type "submit"} "Suspend"])])

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

(defn- ready-body [id share-url suspended?]
  (list
   [:div.actions
    [:a.button.button--primary {:href (str "/notebooks/" id)} "Open notebook"]
    (suspend-toggle id suspended?)]
   (if suspended?
     [:p.muted "Suspended — its sprite is asleep and not billing; resume to pick "
      "up where you left off."]
     [:p.muted "Edit the source and see the rendered output side by side."])
   [:h3 "Share"]
   [:div.copyable
    [:code share-url]
    [:button.copy {:type "button" :data-copy share-url} "Copy"]]
   [:p.muted "Anyone with this link can view the rendered notebook (read-only)."]
   [:p.subtle "Need just the code? "
    [:a {:href (str "/notebooks/" id "/source")} "View the raw source"]
    " — it stays available even if the notebook is paused for the month."]
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
       [:dt "status"]  [:dd (status-badge notebook)]]]
     (case status
       "ready"  (ready-body id share-url (suspended? notebook))
       "failed" (failed-body id)
       (provisioning-body id))]))

(defn- usage-meter
  "An approximate monthly active-hours meter. The hours are sampled, not billed,
   so the copy stays relaxed — a friendly estimate, not an invoice. A nil/0
   limit (the cap disabled) drops the bar and the framing."
  [notebook limit-hours]
  (let [hours   (/ (usage/awake-seconds-this-month notebook) 3600.0)
        whole   (Math/round hours)
        capped? (and limit-hours (pos? limit-hours))
        pct     (when capped? (min 100 (Math/round (* 100.0 (/ hours limit-hours)))))]
    [:section.card.usage
     [:p.eyebrow "Usage"]
     [:div.usage-head
      [:span.usage-figure
       "≈ " (str whole) (when capped? (str " of " limit-hours)) " hours this month"]
      (when (some? pct) [:span.usage-pct (str pct "%")])]
     (when (some? pct)
       [:div.usage-track {:role "progressbar" :aria-valuemin 0 :aria-valuemax 100
                          :aria-valuenow pct :aria-label "Monthly active-hours used"
                          :aria-valuetext (str whole " of " limit-hours " hours (" pct "%)")}
        [:div.usage-fill {:style (str "width:" pct "%")}]])
     [:p.subtle
      (if capped?
        (str "Everyone gets about " limit-hours " free hours of active notebook "
             "time per month for now. We sample this roughly, so it's a friendly "
             "estimate, not an exact bill — your notebook only counts time while "
             "it's awake, and it resets at the start of next month.")
        (str "Roughly how much active notebook time you've used this month. "
             "There's no limit right now — your notebook only counts time while "
             "it's awake, and this resets at the start of next month."))]]))

(defn render [user notebook base-url limit-hours]
  (layout/page
   "Dashboard"
   [:div
    (header)
    [:main
     [:p.eyebrow "Dashboard"]
     [:h1 "Your notebook"]
     [:p.lead [:span.mono (:users/email user)]]
     (if notebook
       (list
        (notebook-card notebook base-url)
        (usage-meter notebook limit-hours))
       (no-notebook))]]))
