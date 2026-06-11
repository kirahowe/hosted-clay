(ns build
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def uber-file "target/hosted-clay.jar")

(defn- basis []
  (b/create-basis {:project "deps.edn"}))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uberjar [_]
  (clean nil)
  (let [b (basis)]
    (b/copy-dir {:src-dirs   ["src" "resources"]
                 :target-dir class-dir})
    (b/compile-clj {:basis      b
                    :ns-compile '[hosted-clay.main]
                    :class-dir  class-dir})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis     b
             :main      'hosted-clay.main}))
  (println "built" uber-file))
