(ns hosted-clay.db.crud
  "Generic, data-driven CRUD against any table via HoneySQL + next.jdbc.
   App code uses kebab-case keys and table names; HoneySQL converts on
   the way out, the result-set builder converts on the way in. Tables
   that need real domain logic get their own namespace; bare CRUD
   lives here.

   SQLite specifics: ids are TEXT uuids generated here, timestamps are
   ISO-8601 TEXT set by `now` (lexicographic order == chronological
   order, so range queries on them work)."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import (java.time Instant)))

(def opts
  "next.jdbc options every db call in this app threads through:
   kebab-case qualified keys on returned rows."
  {:builder-fn rs/as-kebab-maps})

(defn now
  "The current instant as the ISO-8601 string stored in timestamp columns."
  []
  (str (Instant/now)))

(defn new-id []
  (str (random-uuid)))

(defn- where-clause [where]
  (when (seq where)
    (into [:and] (for [[col v] where] [:= col v]))))

(defn by-id [ds table id]
  (jdbc/execute-one! ds
                     (sql/format {:select [:*] :from [table] :where [:= :id id]})
                     opts))

(defn find-many
  ([ds table] (find-many ds table {}))
  ([ds table where]
   (jdbc/execute! ds
                  (sql/format (cond-> {:select [:*] :from [table]}
                                (seq where) (assoc :where (where-clause where))))
                  opts)))

(defn find-1
  "Like `find-many` but expects the `where` to identify at most one row.
   Returns nil for no match, the row for exactly one, and throws
   ExceptionInfo on more than one (LIMIT 2 detects the multi-match cheaply
   rather than silently picking an arbitrary winner)."
  [ds table where]
  (let [rows (jdbc/execute! ds
                            (sql/format (cond-> {:select [:*] :from [table] :limit 2}
                                          (seq where) (assoc :where (where-clause where))))
                            opts)]
    (case (count rows)
      0 nil
      1 (first rows)
      (throw (ex-info "find-1 matched more than one row"
                      {:table table :where where})))))

(defn count-rows
  ([ds table] (count-rows ds table {}))
  ([ds table where]
   (:n (jdbc/execute-one! ds
                          (sql/format (cond-> {:select [[[:count :*] :n]] :from [table]}
                                        (seq where) (assoc :where (where-clause where))))
                          opts))))

(defn create!
  "Insert `attrs`, filling in :id and :created-at when absent.
   Returns the inserted row."
  [ds table attrs]
  (let [attrs (merge {:id (new-id) :created-at (now)} attrs)]
    (jdbc/execute-one! ds
                       (sql/format {:insert-into [table]
                                    :values      [attrs]
                                    :returning   [:*]})
                       opts)))

(defn update-where!
  "Update rows of `table` matching the HoneySQL `where` form, returning
   the first updated row (nil if none matched)."
  [ds table where attrs]
  (jdbc/execute-one! ds
                     (sql/format {:update    table
                                  :set       attrs
                                  :where     where
                                  :returning [:*]})
                     opts))

(defn update! [ds table id attrs]
  (update-where! ds table [:= :id id] attrs))

(defn delete! [ds table id]
  (jdbc/execute-one! ds
                     (sql/format {:delete-from table
                                  :where       [:= :id id]
                                  :returning   [:*]})
                     opts))
