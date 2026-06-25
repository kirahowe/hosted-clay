(ns hosted-clay.admin.cli
  "Entry point for the admin usage tool: lists every user, whether they hold
   a notebook, its awake-hours this month, and the *estimated* spend that
   implies. Read-only and pointable at any deployment's SQLite file.

   By default it hands a live terminal off to the charm.clj TUI; with
   `--plain` (or no interactive terminal) it prints a `clojure.pprint`
   table instead. Kept thin: all reporting math is in `hosted-clay.admin.data`
   and all rendering in `hosted-clay.admin.tui`."
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [hosted-clay.admin.data :as data]))

(def ^:private usage-text
  (str/join
   "\n"
   ["Usage: clojure -M:admin [options]"
    ""
    "  --env dev|prod      which deployment's config to read (default: dev)"
    "  --cpus N            assumed CPUs per sprite for the spend estimate (default: 1)"
    "  --gb-ram N          assumed GB resident RAM per sprite (default: 1)"
    "  --db-path PATH      override the SQLite file from the config"
    "  --plain             force the plain table (no TUI)"
    "  --help              show this help"]))

(defn- parse-num
  "Parse a numeric flag value, keeping a whole number integral (so `2` prints
   as 2, not 2.0) and a fractional one a double. Throws ::usage on garbage."
  [flag s]
  (or (parse-long s)
      (parse-double s)
      (throw (ex-info (str "not a number for " flag ": " s) {:type ::usage}))))

(defn- parse-args
  "Fold `args` into an options map. Throws ex-info with ::usage on a bad flag
   or missing value, so -main can print usage and exit non-zero."
  [args]
  (loop [opts {:env "dev" :cpus data/default-cpus :gb-ram data/default-gb-ram
               :plain? false :help? false}
         args args]
    (if-let [arg (first args)]
      (let [next-val #(or (second args)
                          (throw (ex-info (str "missing value for " arg) {:type ::usage})))]
        (case arg
          "--env"     (recur (assoc opts :env (next-val)) (drop 2 args))
          "--cpus"    (recur (assoc opts :cpus (parse-num arg (next-val))) (drop 2 args))
          "--gb-ram"  (recur (assoc opts :gb-ram (parse-num arg (next-val))) (drop 2 args))
          "--db-path" (recur (assoc opts :db-path (next-val)) (drop 2 args))
          "--plain"   (recur (assoc opts :plain? true) (rest args))
          ("--help" "-h") (recur (assoc opts :help? true) (rest args))
          (throw (ex-info (str "unknown argument: " arg) {:type ::usage}))))
      opts)))

(defn- interactive?
  "True when stdin is a real terminal — the only case the TUI can drive."
  []
  (some? (System/console)))

(defn print-plain
  "Print `report` as a pprint table plus a header and totals footer. The
   plain fallback (and the `--plain` path); no TUI, no charm.clj."
  [{:keys [month cpus gb-ram rows totals]} env]
  (println (format "Clay usage — env=%s  month=%s  assumed size=%scpu/%sgb  (~spend @ $%.4f/cpu-hr + $%.5f/gb-hr, awake-only — approx)"
                   env month cpus gb-ram data/cpu-hour-rate data/gb-hour-rate))
  (println)
  (if (seq rows)
    (pp/print-table
     [:email :notebooks :status :awake-hours :approx-spend]
     (for [{:keys [email notebook-count status awake-hours spend]} rows]
       {:email        email
        :notebooks    notebook-count
        :status       (or status "-")
        :awake-hours  (format "%.2f" awake-hours)
        :approx-spend (format "$%.2f" spend)}))
    (println "(no users)"))
  (println)
  (println (format "Totals: %d users, %d notebooks, %.2f awake-hours, ~$%.2f spend (approx)"
                   (:users totals) (:notebooks totals)
                   (:awake-hours totals) (:spend totals))))

(defn- die!
  "Print `msg` and the usage text to stderr and exit 2 — for a bad flag or a
   bad env/db-path (e.g. an unknown --env, or a path with no database)."
  [msg]
  (binding [*out* *err*]
    (println msg)
    (println)
    (println usage-text))
  (System/exit 2))

(defn -main [& args]
  (let [opts (try (parse-args args)
                  (catch clojure.lang.ExceptionInfo e (die! (ex-message e))))]
    (when (:help? opts)
      (println usage-text)
      (System/exit 0))
    (let [report (try
                   (let [ds (data/open-datasource (:env opts) (:db-path opts))]
                     (data/report ds (select-keys opts [:cpus :gb-ram])))
                   (catch clojure.lang.ExceptionInfo e (die! (ex-message e))))]
      (if (or (:plain? opts) (not (interactive?)))
        (print-plain report (:env opts))
        ;; Resolve the TUI lazily so charm.clj stays off the classpath for the
        ;; plain path and the test run.
        ((requiring-resolve 'hosted-clay.admin.tui/start!) report (:env opts)))
      (System/exit 0))))
