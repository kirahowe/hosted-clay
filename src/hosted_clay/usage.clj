(ns hosted-clay.usage
  "Per-user monthly active-hours metering and budget. Sprites bill only while
   awake, and the Sprites API exposes no usage data (no usage/billing endpoint,
   no cumulative counter on the sprite object), so we meter it ourselves: each
   census pass adds the sample interval to every awake notebook's running total
   for the current month, resetting when the month rolls over. One notebook per
   user, so per-notebook == per-user.

   This namespace only accrues time and sends the near-the-limit warning. The
   budget is enforced on the request path via `notebook-over-limit?`: an
   over-limit notebook's handlers refuse to forward, so no *new* request wakes
   its sprite. A connection that's already open (a live-reload or editor
   WebSocket) can outlive the limit until it next idles and drops — idle-suspend
   bounds that to minutes — so the cap is soft, not an instant kill.

   Metering assumes a single scheduler (one app instance) accruing off one
   census poll; two schedulers would double-count.

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

(defn awake-seconds-this-month
  "A notebook's accrued awake seconds for the *current* month — 0 once the
   stored total belongs to an earlier month (it resets lazily on next accrual),
   so callers see the monthly reset immediately rather than waiting on a sweep."
  [notebook]
  (if (= (current-month) (:notebooks/usage-month notebook))
    (or (:notebooks/awake-seconds notebook) 0)
    0))

(defn notebook-over-limit?
  "True when a notebook has spent its monthly active-hours budget. `limit-hours`
   nil/0 disables the cap (metering still runs)."
  [notebook limit-hours]
  (and limit-hours (pos? limit-hours)
       (>= (awake-seconds-this-month notebook) (* limit-hours 3600))))

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

(defn- warn! [ds send-email notebook seconds {:keys [limit-hours base-url]}]
  ;; usage-warned-at is written only after send-email returns — send-email
  ;; throws on a failed delivery, so a failure leaves the notebook un-warned and
  ;; the next census retries it (same pattern as the idle-deletion warning).
  (if-let [user (users/by-id ds (:notebooks/user-id notebook))]
    (do
      (send-email (warning-message user notebook seconds limit-hours base-url))
      (crud/update! ds :notebooks (:notebooks/id notebook) {:usage-warned-at (crud/now)})
      (log/info "usage warning sent" {:notebook-id (:notebooks/id notebook)
                                      :hours       (/ (double seconds) 3600)}))
    (log/error "cannot send usage warning: notebook has no user"
               {:notebook-id (:notebooks/id notebook)})))

(defn record!
  "Accrue `interval-seconds` of awake time to each notebook in `awake-notebooks`
   for the current month, rolling the bucket over (and clearing the warning) on
   a new month, and sending the near-the-limit warning once per month when a
   notebook first crosses `warn-hours`. Each notebook is handled independently;
   one failure doesn't block the rest. Always accrues — `warn-hours` nil skips
   only the warning."
  [ds send-email awake-notebooks {:keys [interval-seconds warn-hours] :as opts}]
  (let [month (current-month)]
    (doseq [nb awake-notebooks]
      (try
        (let [rolled?   (not= month (:notebooks/usage-month nb))
              seconds   (+ (if rolled? 0 (or (:notebooks/awake-seconds nb) 0))
                           interval-seconds)
              warned-at (when-not rolled? (:notebooks/usage-warned-at nb))]
          (crud/update! ds :notebooks (:notebooks/id nb)
                        (cond-> {:usage-month month :awake-seconds seconds}
                          rolled? (assoc :usage-warned-at nil)))
          (when (and warn-hours (pos? warn-hours) (nil? warned-at)
                     (>= seconds (* warn-hours 3600)))
            (warn! ds send-email nb seconds opts)))
        (catch Throwable t
          (log/error t "usage metering failed" {:notebook-id (:notebooks/id nb)}))))))
