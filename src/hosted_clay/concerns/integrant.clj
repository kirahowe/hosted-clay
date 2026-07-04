(ns hosted-clay.concerns.integrant
  "Integrant concerns: EDN reader literals and constant-value
  init-keys. Required (transitively) by any entry point that reads
  the system config or calls `ig/load-namespaces`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [integrant.core :as ig]))

;; ---------- Constants ----------

(defmethod ig/init-key :hosted-clay/const [_ v] v)

;; Derive each constant key from :hosted-clay/const so its value flows
;; through the init-method above and can be `#ig/ref`'d by components.
(derive :hosted-clay/base-url :hosted-clay/const)
(derive :hosted-clay/sprite-tag :hosted-clay/const)
(derive :hosted-clay/max-sprites :hosted-clay/const)
(derive :hosted-clay/max-running :hosted-clay/const)
(derive :hosted-clay/usage-limit-hours :hosted-clay/const)
(derive :hosted-clay/usage-warn-hours :hosted-clay/const)
(derive :hosted-clay/idle-suspend-minutes :hosted-clay/const)

;; ---------- EDN reader literals ----------

(defrecord Secret [value]
  Object
  (toString [_] "<secret>"))

(defmethod print-method Secret [_ ^java.io.Writer w]
  (.write w "#secret \"<redacted>\""))

(defn- env-value
  "Looks up an env var, treating an unset or blank value as absent.
  `arg` is \"VAR\" or [\"VAR\" default]. Returns [found? value]; found?
  is true when the var is set to a non-blank value or a default was
  supplied (the default may legitimately be nil or false)."
  [arg]
  (let [[var-name & default] (if (vector? arg) arg [arg])
        v                     (System/getenv var-name)]
    (cond
      (not (str/blank? v)) [true v]
      (seq default)        [true (first default)]
      :else                [false nil])))

(defn env
  [arg]
  (let [[found? v] (env-value arg)]
    (if found?
      v
      (throw (ex-info (str "missing required env var: " arg) {:env arg})))))

(defn env-opt
  [arg]
  (second (env-value arg)))

(defn env-long
  [arg]
  (some-> (env arg) str Long/parseLong))

(defn env-secret
  "#env/secret -- required env var wrapped in a Secret that redacts itself."
  [arg]
  (->Secret (env arg)))

(def readers
  {'env        env
   'env/opt    env-opt
   'env/long   env-long
   'env/secret env-secret
   'secret     ->Secret
   'resource   io/resource})
