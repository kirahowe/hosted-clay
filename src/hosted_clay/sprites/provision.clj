(ns hosted-clay.sprites.provision
  "Turn a freshly created sprite into a notebook sprite: upload the
   notebook skeleton (deps.edn, starter notebook, watch entry point,
   Caddyfile), then run setup.sh, which installs the toolchain,
   pre-fetches dependencies, and registers the caddy/notebook/code-server
   services with the sprite runtime."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [hosted-clay.sprites.exec :as exec]))

(def ^:private sprite-files
  "resource path -> absolute path inside the sprite"
  {"sprite/deps.edn"     "/home/sprite/notebook/deps.edn"
   "sprite/notebook.clj" "/home/sprite/notebook/notebook.clj"
   "sprite/watch.clj"    "/home/sprite/notebook/watch.clj"
   "sprite/tasks.json"   "/home/sprite/notebook/.vscode/tasks.json"
   "sprite/Caddyfile"    "/home/sprite/Caddyfile"})

(defn- run!*
  [client sprite-name cmd {:keys [stdin timeout-ms step]}]
  (let [{:keys [exit err] :as result}
        (exec/exec! client sprite-name cmd :stdin stdin :timeout-ms timeout-ms)]
    (when-not (zero? exit)
      (throw (ex-info "provisioning step failed"
                      {:sprite sprite-name :step step :exit exit :err err})))
    result))

(defn- upload! [client sprite-name resource-path target-path]
  (let [dir (str (.getParent (io/file target-path)))]
    (run!* client sprite-name
           ["bash" "-c" (str "mkdir -p '" dir "' && cat > '" target-path "'")]
           {:stdin      (slurp (io/resource resource-path))
            :timeout-ms 60000
            :step       [:upload target-path]})))

(defn provision!
  "Provision `sprite-name` end to end. Blocking and slow (minutes) — the
   pool replenisher calls this off the request path. Throws on any
   failed step; setup.sh is idempotent, so a retry on the same sprite
   is safe."
  [client sprite-name]
  (log/info "provisioning sprite" {:sprite sprite-name})
  (doseq [[resource-path target-path] sprite-files]
    (upload! client sprite-name resource-path target-path))
  (run!* client sprite-name ["bash" "-s"]
         {:stdin      (slurp (io/resource "sprite/setup.sh"))
          :timeout-ms (* 25 60 1000)
          :step       :setup})
  (log/info "provisioned sprite" {:sprite sprite-name}))
