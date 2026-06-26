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

(defn- status-readout
  "The notebook's current state as a square swatch + mono label."
  [notebook]
  (let [[label cls]
        (cond
          (suspended? notebook)                     ["Suspended"    "nb-status--suspended"]
          (= "ready"  (:notebooks/status notebook)) ["Ready"        "nb-status--ready"]
          (= "failed" (:notebooks/status notebook)) ["Setup failed" "nb-status--failed"]
          :else                                     ["Setting up"   "nb-status--provisioning"])]
    [:span.nb-status {:class cls} label]))

(defn- action-bar
  "The notebook's state on the left, its primary controls on the right. Exactly
   one accent button: Open (active) or Resume (suspended)."
  [notebook]
  (let [id        (:notebooks/id notebook)
        suspended (suspended? notebook)]
    [:div.notebook__bar
     (status-readout notebook)
     [:div.notebook__actions
      [:a.button {:class (when-not suspended "button--primary")
                  :href  (str "/notebooks/" id)} "Open notebook"]
      [:form.inline-form {:method "post"
                          :action (str "/notebooks/" id "/" (if suspended "resume" "suspend"))}
       [:input {:type "hidden" :name "return" :value "/dashboard"}]
       [:button {:type "submit" :class (when suspended "button--primary")}
        (if suspended "Resume" "Suspend")]]]]))

(defn- usage-section
  "Approximate monthly active-hours. Sampled, not billed, so the copy stays a
   friendly estimate. A nil/0 limit drops the bar and the framing."
  [notebook limit-hours]
  (let [hours   (/ (usage/awake-seconds-this-month notebook) 3600.0)
        whole   (Math/round hours)
        capped? (and limit-hours (pos? limit-hours))
        pct     (when capped? (min 100 (Math/round (* 100.0 (/ hours limit-hours)))))]
    [:section.notebook__section.usage
     [:p.eyebrow "Usage this month"]
     [:div.usage-head
      [:span.usage-figure "≈ " (str whole) (when capped? (str " of " limit-hours)) " hours"]
      (when (some? pct) [:span.usage-pct (str pct "%")])]
     (when (some? pct)
       [:div.usage-track {:role "progressbar" :aria-valuemin 0 :aria-valuemax 100
                          :aria-valuenow pct :aria-label "Monthly active-hours used"
                          :aria-valuetext (str whole " of " limit-hours " hours (" pct "%)")}
        [:div.usage-fill {:style (str "width:" pct "%")}]])
     [:p.subtle
      (if capped?
        (str "About " limit-hours " free hours of active notebook time a month, for "
             "now — a rough estimate, not a bill. Only awake time counts, and it "
             "resets at the start of next month.")
        (str "Roughly the active notebook time you've used this month. No limit "
             "right now — only awake time counts, and it resets next month."))]]))

(defn- share-section [id share-url]
  [:section.notebook__section
   [:p.eyebrow "Share"]
   [:div.copyable
    [:code share-url]
    [:button.copy {:type "button" :data-copy share-url} "Copy"]]
   [:p.muted
    "Read-only — anyone with the link sees the rendered notebook. Just need the "
    "code? "
    [:a {:href (str "/notebooks/" id "/source")} "View the raw source"]
    " (works even while it's paused)."]])

(defn- danger-footer [id]
  [:div.notebook__danger
   [:form.inline-form {:method "post" :action (str "/notebooks/" id "/delete")
                       :onsubmit "return confirm('Delete this notebook and its environment? This cannot be undone.')"}
    [:button.button--danger {:type "submit"} "Delete notebook"]]
   [:p.subtle "Idle for 30 days, a notebook is deleted automatically — we email a warning first."]])

(defn- ready-body [notebook share-url limit-hours]
  (let [id        (:notebooks/id notebook)
        suspended (suspended? notebook)]
    (list
     (action-bar notebook)
     [:p.notebook__hint
      (if suspended
        "Its sprite is asleep and not billing — resume to pick up where you left off."
        "Open it to edit the source and watch the rendered output update as you save.")]
     (usage-section notebook limit-hours)
     (share-section id share-url)
     (danger-footer id))))

(defn- provisioning-body [notebook]
  (list
   [:div.notebook__bar (status-readout notebook)]
   [:p.status-line
    [:span.spinner {:role "status" :aria-label "Setting up"}]
    "Setting up your environment — this can take a minute."]
   [:div.actions
    [:a.button.button--primary {:href (str "/notebooks/" (:notebooks/id notebook))} "View progress"]]))

(defn- failed-body [notebook]
  (let [id (:notebooks/id notebook)]
    (list
     [:div.notebook__bar (status-readout notebook)]
     [:p.notice.notice--error "Setup didn't finish. Open it to try again, or delete it and start over."]
     [:div.actions
      [:a.button.button--primary {:href (str "/notebooks/" id)} "Open notebook"]
      [:form.inline-form {:method "post" :action (str "/notebooks/" id "/delete")
                          :onsubmit "return confirm('Delete this notebook? This cannot be undone.')"}
       [:button.button--danger {:type "submit"} "Delete"]]])))

(defn- notebook-card [notebook base-url limit-hours]
  (let [status    (:notebooks/status notebook)
        share-url (str base-url "/s/" (:notebooks/share-token notebook) "/")]
    [:section.card.notebook
     (case status
       "ready"  (ready-body notebook share-url limit-hours)
       "failed" (failed-body notebook)
       (provisioning-body notebook))]))

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
              :placeholder "My notebook" :required true :maxlength 120}]]
    [:button.button--primary {:type "submit"} "Create notebook"]]
   [:p.subtle
    "If no pre-warmed environment is free, creating one takes a couple of "
    "minutes while it's built — we'll show progress."]])

(defn- page-header [user notebook]
  (list
   [:p.eyebrow "Dashboard"]
   [:h1 (if notebook (:notebooks/title notebook) "Your notebook")]
   [:p.dash-meta
    [:span.mono (:users/email user)]
    (when notebook (list " · created " (day (:notebooks/created-at notebook))))]))

(defn render [user notebook base-url limit-hours]
  (layout/page
   "Dashboard"
   [:div
    (header)
    [:main.dashboard
     (page-header user notebook)
     (if notebook
       (notebook-card notebook base-url limit-hours)
       (no-notebook))]]))
