(ns hosted-clay.log
  "Logging lifecycle. Routes clojure.tools.logging through Telemere and
  applies the configured minimum level. Not referenced by any other
  component — it is initialized purely for its side effects."
  (:require [integrant.core :as ig]
            [taoensso.telemere :as tel]
            [taoensso.telemere.tools-logging :as tel-tools]))

(defmethod ig/init-key :hosted-clay.log/telemere [_ {:keys [min-level]}]
  (when min-level (tel/set-min-level! min-level))
  (tel-tools/tools-logging->telemere!)
  {:min-level min-level})

(defmethod ig/halt-key! :hosted-clay.log/telemere [_ _]
  nil)
