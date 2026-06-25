(ns hosted-clay.db
  "SQLite datasource and migrator components.

   The datasource is a plain next.jdbc datasource over a file-backed
   SQLite database — no connection pool. SQLite connections are cheap,
   and the WAL + busy_timeout pragmas (set via the JDBC URL) make
   concurrent readers and the occasional contended write behave. At
   MVP scale (≤50 users, one always-on machine) this is plenty; revisit
   if write contention ever shows up in the logs."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc]))

(defn- jdbc-url [db-path]
  (str "jdbc:sqlite:" db-path
       "?journal_mode=WAL&busy_timeout=5000&foreign_keys=true"))

(defn datasource
  "A next.jdbc datasource over the SQLite file at `db-path`, with the WAL +
   busy_timeout pragmas set on the JDBC URL. Creates the parent directory.
   The component init-key and the read-only admin tool both build their
   datasource through here so the URL/pragmas stay in one place."
  [db-path]
  (when-let [parent (.getParentFile (io/file db-path))]
    (.mkdirs parent))
  (jdbc/get-datasource (jdbc-url db-path)))

(defmethod ig/init-key :hosted-clay.db/datasource [_ {:keys [db-path]}]
  (log/info "datasource starting" {:db-path db-path})
  (datasource db-path))

(defmethod ig/halt-key! :hosted-clay.db/datasource [_ _]
  ;; Nothing to close: connections are opened per call and closed by
  ;; next.jdbc; the datasource itself holds no resources.
  nil)

(defmethod ig/init-key :hosted-clay.db/migrator
  [_ {:keys [datasource migrations-path]}]
  (log/info "migrator starting" {:migrations-path migrations-path})
  (migratus/migrate {:store         :database
                     :migration-dir migrations-path
                     :db            {:datasource datasource}})
  (log/info "migrator done")
  ;; Return the datasource so components can express "depend on a
  ;; migrated database" by #ig/ref-ing this key.
  datasource)

(defmethod ig/halt-key! :hosted-clay.db/migrator [_ _])
