(ns hosted-clay.test-system
  "Helpers for standing up subsets of the real Integrant system in
   tests. Each call gets its own SQLite file under target/test/ so
   tests can run in any order and in parallel."
  (:require [clojure.java.io :as io]
            [integrant.core :as ig]
            [hosted-clay.main :as main]))

(defn- fresh-db-profile []
  {:hosted-clay.db/datasource {:db-path (str "target/test/" (random-uuid) ".db")}})

(defn prepped-config []
  (main/prep-config [(io/resource "base-system.edn")
                     (io/resource "test.edn")
                     (fresh-db-profile)]))

(defn with-system
  "Init the system restricted to `keys`, call `f` with it, halt."
  [keys f]
  (let [system (ig/init (prepped-config) keys)]
    (try (f system) (finally (ig/halt! system)))))

(defn with-db
  "Run `f` with a migrated, empty datasource."
  [f]
  (with-system [:hosted-clay.db/migrator]
    (fn [system] (f (:hosted-clay.db/migrator system)))))
