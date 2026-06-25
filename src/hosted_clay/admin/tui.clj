(ns hosted-clay.admin.tui
  "The charm.clj (Bubble Tea / Lipgloss port) rendering of the admin usage
   report: a scrollable table with a header (env, month, assumed size, rates)
   and a totals footer. The ONLY namespace that requires charm.clj — kept off
   the test and plain-CLI classpaths — so clj-kondo's unresolved-namespace
   linter is told about these requires in .clj-kondo/config.edn.

   Elm-architecture: `init` builds the state, `update-state` folds messages
   (scroll keys handled by the table component, `q`/Ctrl-C quit), `view`
   renders. `run!` is the handoff `hosted-clay.admin.cli` resolves lazily."
  (:require [charm.components.table :as table]
            [charm.message :as msg]
            [charm.program :as program]
            [charm.style.core :as style]
            [hosted-clay.admin.data :as data]))

(def ^:private columns
  [{:title "Email"       :width 34}
   {:title "Notebooks"   :width 10}
   {:title "Status"      :width 13}
   {:title "Awake hrs"   :width 10}
   {:title "~Spend"      :width 10}])

;; charm.style.core/with-bold takes only the style map (it hard-codes true), so
;; thread, don't pass a flag.
(def ^:private bold-style (-> (style/style) style/with-bold))

(defn- ->table-row [{:keys [email notebook-count status awake-hours spend]}]
  [(str email)
   (str notebook-count)
   (or status "-")
   (format "%.2f" awake-hours)
   (format "$%.2f" spend)])

(defn- header-line [{:keys [month cpus gb-ram]} env]
  (style/render
   bold-style
   (format "Clay usage   env=%s   month=%s   assumed=%scpu/%sgb   ~spend @ $%.4f/cpu-hr + $%.5f/gb-hr (awake-only, approx)"
           env month cpus gb-ram data/cpu-hour-rate data/gb-hour-rate)))

(defn- footer-line [{:keys [totals]}]
  (style/render
   bold-style
   (format "Totals: %d users   %d notebooks   %.2f awake-hrs   ~$%.2f spend (approx)    [q to quit, ↑/↓ to scroll]"
           (:users totals) (:notebooks totals) (:awake-hours totals) (:spend totals))))

(defn- init [report env]
  (let [rows (mapv ->table-row (:rows report))]
    {:report report
     :env    env
     :table  (table/table columns rows
                          :cursor (when (seq rows) 0)
                          :height 20
                          :header-style bold-style
                          :cursor-style bold-style)}))

(defn- update-state [state m]
  (cond
    (or (msg/key-match? m "q") (msg/key-match? m "ctrl+c"))
    [state program/quit-cmd]

    :else
    (let [[tbl cmd] (table/table-update (:table state) m)]
      [(assoc state :table tbl) cmd])))

(defn- view [{:keys [report env table] :as _state}]
  (str (header-line report env) "\n\n"
       (table/table-view table) "\n\n"
       (footer-line report)))

(defn start!
  "Run the interactive TUI for `report` (env label for the header). Blocks
   until the user quits."
  [report env]
  (program/run {:init   #(init report env)
                :update update-state
                :view   view}))
