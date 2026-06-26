(ns hosted-clay.admin.data
  "Read-only reporting for the admin usage tool: who has a notebook, how
   much awake time it spent this month, and the *estimated* spend that
   implies. TUI-agnostic on purpose — no rendering, no charm.clj — so it's
   testable and lints clean without that dependency on the classpath.

   It opens a next.jdbc datasource straight onto an environment's SQLite
   file (reusing the app's layered config) and never inits the migrator: an
   admin pointing this at prod must not silently run migrations against a
   live database."
  (:require [clojure.java.io :as io]
            [hosted-clay.db :as db]
            [hosted-clay.db.crud :as crud]
            [hosted-clay.main :as main]
            [hosted-clay.usage :as usage]))

;; ---------- spend model ----------

;; Sprites bill usage-based and awake-only; the API exposes no usage data and
;; we store no per-sprite CPU/RAM size, so spend is an ESTIMATE from an
;; admin-supplied assumed size times these published rates.
(def ^:const cpu-hour-rate
  "USD per awake CPU-hour."
  0.07)

(def ^:const gb-hour-rate
  "USD per awake GB-hour of resident RAM."
  0.04375)

(def default-cpus 1)
(def default-gb-ram 1)

(defn hourly-rate
  "USD per awake hour for an assumed sprite size of `cpus` / `gb-ram`."
  [cpus gb-ram]
  (+ (* cpu-hour-rate cpus) (* gb-hour-rate gb-ram)))

(defn spend
  "Estimated awake-only spend for `awake-hours` at an assumed `cpus`/`gb-ram`
   size. An estimate, not an invoice — there's no stored sprite size."
  [awake-hours cpus gb-ram]
  (* awake-hours (hourly-rate cpus gb-ram)))

;; ---------- datasource ----------

(def ^:private env->profile
  "The per-environment overlay resource layered on top of base-system.edn.
   Both must be on the classpath (the :admin alias puts dev + prod there)."
  {"dev"  "dev.edn"
   "prod" "prod.edn"})

(defn db-path
  "The SQLite db-path the merged config selects for `env` (\"dev\"/\"prod\")."
  [env]
  (let [profile (or (env->profile env)
                    (throw (ex-info (str "unknown env: " env " (expected dev or prod)")
                                    {:env env})))
        config  (main/merge-profiles [(io/resource "base-system.edn")
                                      (io/resource profile)])]
    (get-in config [:hosted-clay.db/datasource :db-path])))

(defn open-datasource
  "A read-only next.jdbc datasource onto `env`'s SQLite file, built the same
   way the running app builds it (WAL + busy_timeout pragmas via the JDBC
   URL). `db-path-override`, when given, wins over the config's path so an
   admin can point at any deployment's file. Does NOT migrate — and throws
   rather than silently creating an empty database if the file is missing (a
   wrong --env/--db-path would otherwise report zero users against a blank DB)."
  ([env] (open-datasource env nil))
  ([env db-path-override]
   (let [path (or db-path-override (db-path env))]
     (when-not (.exists (io/file path))
       (throw (ex-info (str "no database at " path
                            " — check --env (dev/prod) or pass --db-path")
                       {:path path})))
     (db/datasource path))))

;; ---------- report ----------

(defn- notebook-counts
  "Map of user-id -> notebook count. One notebook per user (DB-enforced), so
   every value is 0 or 1, but counting is robust to that assumption."
  [notebooks]
  (frequencies (map :notebooks/user-id notebooks)))

(defn- row
  "One report row for `user`, given its notebook (or nil), its accrued awake
   seconds this month, and the assumed size."
  [user notebook counts awake-seconds cpus gb-ram]
  (let [awake-hours (/ awake-seconds 3600.0)]
    {:email          (:users/email user)
     :user-id        (:users/id user)
     :notebook-count (get counts (:users/id user) 0)
     :status         (when notebook (:notebooks/status notebook))
     :awake-hours    awake-hours
     :spend          (spend awake-hours cpus gb-ram)}))

(defn report
  "Read-only usage report for the deployment behind `ds`, for the current
   month. `opts` is the assumed sprite size ({:cpus :gb-ram}, defaulting to
   1/1) used for the spend estimate. Returns

     {:month   \"YYYY-MM\"
      :cpus N :gb-ram N
      :rows    [{:email :user-id :notebook-count :status :awake-hours :spend} ...]
      :totals  {:users N :notebooks N :awake-hours H :spend S}}

   Rows are sorted by spend descending (then email) so the costliest users
   surface first. All spend/hours math lives here, not in the UI."
  [ds {:keys [cpus gb-ram] :or {cpus default-cpus gb-ram default-gb-ram}}]
  (let [month     (usage/current-month)
        users     (crud/find-many ds :users)
        notebooks (crud/find-many ds :notebooks)
        counts    (notebook-counts notebooks)
        by-user   (into {} (map (juxt :notebooks/user-id identity)) notebooks)
        ;; This month's accrued usage is keyed on the user (in user_usage), not
        ;; the notebook — so it's reported even for a user between notebooks, and
        ;; a previous month's row is excluded by the month filter.
        usage-by-user (into {} (map (juxt :user-usage/user-id :user-usage/awake-seconds))
                            (crud/find-many ds :user-usage {:usage-month month}))
        rows      (->> users
                       (map #(row % (get by-user (:users/id %)) counts
                                  (get usage-by-user (:users/id %) 0) cpus gb-ram))
                       (sort-by (juxt (comp - :spend) :email))
                       vec)]
    {:month  month
     :cpus   cpus
     :gb-ram gb-ram
     :rows   rows
     :totals {:users       (count users)
              :notebooks   (count notebooks)
              :awake-hours (reduce + 0.0 (map :awake-hours rows))
              :spend       (reduce + 0.0 (map :spend rows))}}))
