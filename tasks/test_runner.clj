(ns test-runner
  "Runs the bb-task test suite under bb itself. Necessary because the
   tested namespaces (`dev-task`, `repl`) use bb's built-in stdlib
   (`bencode.core`, `babashka.fs`, `babashka.process`) which isn't
   available under the JVM-Clojure / kaocha runner."
  (:require [clojure.test :as t]
            tasks.dev-test
            tasks.repl-test))

(defn run-tests []
  (let [{:keys [fail error]} (t/run-tests 'tasks.dev-test
                                          'tasks.repl-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
