(ns hosted-clay.ui.pages.dashboard
  (:require [hosted-clay.routes :as routes]
            [hosted-clay.ui.layout :as layout]))

(defn- header []
  (layout/site-header
   [:form {:method "post" :action (routes/logout)}
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
                  :href  (routes/notebook id)} "Open notebook"]
      [:form.inline-form {:method "post"
                          :action (if suspended (routes/notebook-resume id) (routes/notebook-suspend id))}
       [:input {:type "hidden" :name "return" :value (routes/dashboard)}]
       [:button {:type "submit" :class (when suspended "button--primary")}
        (if suspended "Resume" "Suspend")]]]]))

(defn- section-head
  "The shared eyebrow + heading rhythm used across the homepage — reused here
   so the dashboard reads in the same voice as the landing page. A nil `title`
   renders the eyebrow alone."
  ([eyebrow title]
   [:div.section-head
    (when eyebrow [:p.eyebrow eyebrow])
    (when title [:h2 title])]))

(defn- hours-minutes
  "Whole-minute 'Xh Ym' for `seconds`, dropping a zero hours or zero minutes
   ('25m', '2h', '2h 25m', '0m'). Minute granularity — the census samples awake
   time about once a minute, so the figure is meaningful down to the minute
   rather than rounded to the nearest hour."
  [seconds]
  (let [total-min (Math/round (/ (double seconds) 60))
        h         (quot total-min 60)
        m         (rem total-min 60)]
    (cond
      (zero? total-min) "0m"
      (zero? m)         (str h "h")
      (zero? h)         (str m "m")
      :else             (str h "h " m "m"))))

(defn- usage-section
  "Approximate monthly active-hours — the user's total across all their
   notebooks, passed in by the handler. Sampled, not billed, so the copy stays a
   friendly estimate, but shown to the minute. A nil/0 limit drops the bar and
   the framing."
  [awake-seconds limit-hours]
  (let [used    (hours-minutes awake-seconds)
        hours   (/ awake-seconds 3600.0)
        capped? (and limit-hours (pos? limit-hours))
        pct     (when capped? (min 100 (Math/round (* 100.0 (/ hours limit-hours)))))]
    [:section.notebook__section.usage
     (section-head "Usage this month" "Active hours")
     [:div.usage-head
      [:span.usage-figure "≈ " used (when capped? (str " of " limit-hours "h"))]
      (when (some? pct) [:span.usage-pct (str pct "%")])]
     (when (some? pct)
       [:div.usage-track {:role "progressbar" :aria-valuemin 0 :aria-valuemax 100
                          :aria-valuenow pct :aria-label "Monthly active-hours used"
                          :aria-valuetext (str used " of " limit-hours "h (" pct "%)")}
        [:div.usage-fill {:style (str "width:" pct "%")}]])
     [:p.hint
      (if capped?
        (str "Usage is free while we're subsidizing it during this demo phase. "
             "Everyone gets about " limit-hours " hours of active notebook time per "
             "month. A notebook only counts as active while it's running, and each "
             "user's hours reset at the start of each month. We sample usage rather "
             "than measure it exactly, so the " limit-hours "-hour limit is "
             "approximate, not a hard cutoff.")
        (str "Usage is free while we're subsidizing it during this demo phase. "
             "There's no limit right now — a notebook only counts as active while "
             "it's running, and your hours reset at the start of each month."))]]))

(defn- share-section [id share-url]
  [:section.notebook__section
   (section-head "Share" "Read-only link")
   [:div.copyable
    [:code share-url]
    [:button.copy {:type "button" :data-copy share-url} "Copy"]]
   [:p.hint
    "Anyone with the link sees the rendered notebook — no account needed. "
    "Just need the code? "
    [:a {:href (routes/notebook-source id)} "View the raw source"]
    " (works even while it's paused)."]])

(defn- danger-footer [id]
  [:section.notebook__section.notebook__danger
   (section-head "Danger zone" "Delete notebook")
   [:form {:method "post" :action (routes/notebook-delete id)
           :onsubmit "return confirm('Delete this notebook and its environment? This cannot be undone.')"}
    [:button.button--danger {:type "submit"} "Delete notebook"]]
   [:p.hint
    "This deletes the notebook and everything in its environment. Idle for 30 "
    "days, a notebook is deleted automatically — we email a warning first."]])

(defn- ready-body [notebook share-url limit-hours awake-seconds]
  (let [suspended (suspended? notebook)]
    (list
     (action-bar notebook)
     [:p.hint.notebook__state-hint
      (if suspended
        "Its sprite is asleep and not billing — resume to pick up where you left off."
        "Open it to edit the source and watch the rendered output update as you save.")]
     (usage-section awake-seconds limit-hours)
     (share-section (:notebooks/id notebook) share-url)
     (danger-footer (:notebooks/id notebook)))))

(defn- provisioning-body [notebook]
  (list
   [:div.notebook__bar (status-readout notebook)]
   [:p.status-line
    [:span.spinner {:role "status" :aria-label "Setting up"}]
    "Setting up your environment — this can take a minute."]
   [:div.actions
    [:a.button.button--primary {:href (routes/notebook (:notebooks/id notebook))} "View progress"]]))

(defn- failed-body [notebook]
  (let [id (:notebooks/id notebook)]
    (list
     [:div.notebook__bar (status-readout notebook)]
     [:p.notice.notice--error "Setup didn't finish. Open it to try again, or delete it and start over."]
     [:div.actions
      [:a.button.button--primary {:href (routes/notebook id)} "Open notebook"]
      [:form.inline-form {:method "post" :action (routes/notebook-delete id)
                          :onsubmit "return confirm('Delete this notebook? This cannot be undone.')"}
       [:button.button--danger {:type "submit"} "Delete"]]])))

(defn- notebook-card [notebook base-url limit-hours awake-seconds]
  (let [status    (:notebooks/status notebook)
        share-url (routes/absolute base-url (routes/share (:notebooks/id notebook)))]
    [:section.card.notebook
     (case status
       "ready"  (ready-body notebook share-url limit-hours awake-seconds)
       "failed" (failed-body notebook)
       (provisioning-body notebook))]))

(defn- no-notebook []
  [:section.card.card--accent.notebook
   (section-head "Start here" "Create your notebook")
   [:p.lead
    "You get one free notebook: a private Linux machine with Clay, "
    "Noj, and a browser editor, ready to go."]
   [:form {:method "post" :action (routes/notebooks) :data-submit-label "Creating…"}
    [:div.field
     [:label {:for "title"} "Name"]
     [:input {:type "text" :id "title" :name "title"
              :placeholder "My notebook" :required true :maxlength 120}]]
    [:button.button--primary {:type "submit"} "Create notebook"]]
   [:p.hint
    "If no pre-warmed environment is free, creating one takes a couple of "
    "minutes while it's built — we'll show progress as it goes."]])

(defn- page-header [user notebook]
  [:section.dash-head
   [:p.eyebrow "Dashboard"]
   [:h1 (if notebook (:notebooks/title notebook) "Your notebook")]
   [:p.dash-meta
    [:span.mono (:users/email user)]
    (when notebook (list " · created " (day (:notebooks/created-at notebook))))]])

(defn render [user notebook base-url limit-hours awake-seconds]
  (layout/page
   "Dashboard"
   [:div
    (header)
    [:main
     (page-header user notebook)
     (if notebook
       (notebook-card notebook base-url limit-hours awake-seconds)
       (no-notebook))]]))
