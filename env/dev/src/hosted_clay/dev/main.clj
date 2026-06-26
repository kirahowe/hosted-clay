(ns hosted-clay.dev.main
  "Entry point for `bb dev` when no REPL is already running. Drives the
   Integrant lifecycle via `integrant.repl` — reusing the `dev` ns's
   profile setup so cold-boot and an editor jack-in share one definition
   of \"the dev system\" — and binds a cider-nrepl server on an
   OS-assigned port, advertising it in `.nrepl-port` so editors and bb
   tasks discover and connect to it (no hardcoded port). The running
   system is reachable at `integrant.repl.state/system`, which is what
   the `bb dev` reuse path evals against."
  (:require [cider.nrepl :refer [cider-nrepl-handler]]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [integrant.repl :as igr]
            [integrant.repl.state :as igs]
            [nrepl.server :as nrepl])
  (:gen-class))

(def port-file ".nrepl-port")

(defn -main [& _]
  ;; Loading `dev` runs its top-level integrant.repl/set-prep! (the dev
  ;; profile stack), so this cold-boot path and an editor jack-in that
  ;; `(go)`s by hand boot the very same system.
  (require 'dev)
  (igr/go)
  (let [server (nrepl/start-server :port 0 :handler cider-nrepl-handler)
        port   (:port server)]
    (spit port-file (str port))
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable
                               (fn []
                                 (log/info "dev shutdown")
                                 (nrepl/stop-server server)
                                 (io/delete-file port-file true)
                                 (when igs/system (igr/halt)))))
    (log/info "dev ready" {:nrepl-port port})
    (println "hosted-clay dev ready. cider-nrepl on" port "(advertised in" (str port-file ")"))))
