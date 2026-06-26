(ns hosted-clay.usage
  "Per-user monthly active-hours metering and budget. Sprites bill only while
   awake, and the Sprites API exposes no usage data (no usage/billing endpoint,
   no cumulative counter on the sprite object), so we meter it ourselves: each
   census pass adds the sample interval to every awake notebook's owner's running
   total for the current month.

   Usage is keyed on the *user*, not the notebook — one row per (user, month) in
   `user_usage`. That's deliberate: stored on the notebook, a user could reset
   their monthly usage by deleting and recreating their notebook (the total died
   with the row). Keyed on the user it survives notebook deletion and is
   cumulative across all of a user's notebooks in a month. One notebook per user
   today, but the budget is the user's, not any single notebook's. A new month is
   simply a new row, so the monthly reset is implicit — the current-month lookup
   just finds nothing.

   This namespace only accrues time and sends the near-the-limit warning. The
   budget is enforced on the request path via `user-over-limit?`: an over-limit
   user's notebook handlers refuse to forward, so no *new* request wakes its
   sprite. A connection that's already open (a live-reload or editor WebSocket)
   can outlive the limit until it next idles and drops — idle-suspend bounds that
   to minutes — so the cap is soft, not an instant kill.

   Metering assumes a single scheduler (one app instance) accruing off one census
   poll; two schedulers would double-count. The find-or-create in `record!` is
   likewise safe only under that single-writer assumption.

   Accuracy: it's wall-clock awake time sampled at the census interval, not
   billed CPU/GB-hours. A session shorter than one interval can go unsampled and
   accrue nothing, so the meter errs in the user's favor — good enough for a
   soft budget, not an invoice."
  (:require [clojure.tools.logging :as log]
            [hosted-clay.db.crud :as crud]
            [hosted-clay.users :as users]))

(defn current-month
  "The current UTC year-month as 'YYYY-MM' — the bucket usage accrues into.
   Derived from `crud/now` (an ISO-8601 UTC instant), so it shares the app's
   one clock and needs no timezone handling."
  []
  (subs (crud/now) 0 7))

(defn usage-row
  "The user's `user_usage` row for the *current* month, or nil if nothing has
   accrued yet this month. Because each month is its own row, a previous month's
   total never leaks into this one — the lookup simply doesn't find it."
  [ds user-id]
  (when user-id
    (crud/find-1 ds :user-usage {:user-id user-id :usage-month (current-month)})))

(defn awake-seconds-this-month
  "A user's accrued awake seconds for the current month across all their
   notebooks — 0 when no row exists yet (a fresh month, or a user who's never
   run a notebook awake)."
  [ds user-id]
  (or (:user-usage/awake-seconds (usage-row ds user-id)) 0))

(defn user-over-limit?
  "True when a user has spent their monthly active-hours budget across all their
   notebooks. `limit-hours` nil/0 disables the cap (metering still runs)."
  [ds user-id limit-hours]
  (and limit-hours (pos? limit-hours)
       (>= (awake-seconds-this-month ds user-id) (* limit-hours 3600))))

(defn- warning-message [user notebook seconds limit-hours base-url]
  {:to      (:users/email user)
   :subject "Your Clay notebook is close to its monthly limit"
   :text    (str "Hi,\n\n"
                 "Your notebook \"" (:notebooks/title notebook) "\" has used about "
                 (Math/round (/ (double seconds) 3600)) " of " limit-hours
                 " active hours this month. When it reaches " limit-hours
                 " hours it will pause until the start of next month — your work "
                 "is saved, and it resumes automatically.\n\n"
                 "Open it: " base-url "/n/" (:notebooks/id notebook) "/\n\n"
                 "— Clay notebooks")})

(defn- warn! [ds send-email usage-row-id user notebook seconds {:keys [limit-hours base-url]}]
  ;; warned-at is written only after send-email returns — send-email throws on a
  ;; failed delivery, so a failure leaves the row un-warned and the next census
  ;; retries it (same pattern as the idle-deletion warning).
  (send-email (warning-message user notebook seconds limit-hours base-url))
  (crud/update! ds :user-usage usage-row-id {:warned-at (crud/now)})
  (log/info "usage warning sent" {:user-id (:users/id user)
                                  :hours   (/ (double seconds) 3600)}))

(defn record!
  "Accrue `interval-seconds` of awake time to each awake notebook's *owner* for
   the current month, and send the near-the-limit warning once per month when a
   user first crosses `warn-hours`. Awake notebooks are grouped by owner, so a
   user with several awake notebooks accrues one interval per notebook into their
   single monthly bucket (each sprite bills independently). Each user is handled
   independently; one failure doesn't block the rest. Always accrues —
   `warn-hours` nil skips only the warning."
  [ds send-email awake-notebooks {:keys [interval-seconds warn-hours] :as opts}]
  (let [month (current-month)]
    (doseq [[user-id nbs] (group-by :notebooks/user-id awake-notebooks)]
      (try
        (let [added    (* interval-seconds (count nbs))
              existing (crud/find-1 ds :user-usage {:user-id user-id :usage-month month})
              seconds  (+ (or (:user-usage/awake-seconds existing) 0) added)
              row      (if existing
                         (crud/update! ds :user-usage (:user-usage/id existing)
                                       {:awake-seconds seconds})
                         (crud/create! ds :user-usage
                                       {:user-id     user-id
                                        :usage-month month
                                        :awake-seconds seconds}))]
          (when (and warn-hours (pos? warn-hours)
                     (nil? (:user-usage/warned-at existing))
                     (>= seconds (* warn-hours 3600)))
            (if-let [user (users/by-id ds user-id)]
              (warn! ds send-email (:user-usage/id row) user (first nbs) seconds opts)
              (log/error "cannot send usage warning: notebook has no user"
                         {:user-id user-id}))))
        (catch Throwable t
          (log/error t "usage metering failed" {:user-id user-id}))))))
