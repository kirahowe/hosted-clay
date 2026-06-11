(ns hosted-clay.main
  "Entry point. Loads, preps, and starts the Integrant system from
  a list of EDN profiles layered with meta-merge. The first arg to
  `-main` (if given) is treated as the name of an extra profile
  resource that is appended to `core-profiles`."
  (:require [clojure.java.io :as io]
            [integrant.core :as ig]
            [meta-merge.core :as mm]
            [taoensso.telemere :as tel]
            [hosted-clay.concerns.integrant :as igc])
  (:gen-class))

(defn read-config [config]
  (ig/read-string {:readers igc/readers} config))

(defn load-config
  "Reads a single profile to a config map. Accepts:
    - a config map (returned as-is)
    - nil (returns {})
    - a string filename (resolved on the classpath via io/resource)
    - any other slurpable value (URL, File, ...)."
  [profile]
  (cond
    (map? profile)    profile
    (nil? profile)    {}
    (string? profile) (if-let [url (io/resource profile)]
                        (read-config (slurp url))
                        (throw (ex-info (str "config resource not found on classpath: " profile)
                                        {:profile profile})))
    :else             (->> profile slurp read-config)))

(defn merge-profiles [profiles]
  (apply mm/meta-merge (map load-config profiles)))

(defn prep-config
  "Reads, merges, and loads namespaces for `profiles`. Returns the
  merged config map ready for `ig/init`."
  [profiles]
  (let [config (doto (merge-profiles profiles)
                 (ig/load-namespaces))]
    (tel/event! ::config-prepped {:level :debug :data {:profiles profiles}})
    config))

(def core-profiles [(io/resource "base-system.edn")])

(defn exec-config
  "Preps and inits the given profiles. Returns the running system."
  [profiles]
  (-> profiles prep-config ig/init))

(defn- on-shutdown! [f]
  (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable f)))

(defn -main [& args]
  (tel/event! ::starting {:level :info :data {:args args}})
  (let [profiles (if-let [supplied (first args)]
                   (concat core-profiles [supplied])
                   core-profiles)
        system   (exec-config profiles)]
    (on-shutdown! #(do (tel/event! ::shutdown {:level :info})
                       (ig/halt! system)))
    (tel/event! ::ready {:level :info})))
