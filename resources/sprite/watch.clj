(ns watch
  "In-sprite entry point: an nREPL server for Calva on localhost:1339
   and Clay in live-reload mode serving the rendered notebook on 1971.
   Run as `clojure -M:watch` by the `notebook` sprite service."
  (:require [cider.nrepl :refer [cider-nrepl-handler]]
            [nrepl.server :as nrepl]
            [scicloj.clay.v2.api :as clay]))

(defn -main [& _]
  (nrepl/start-server :port 1339 :bind "127.0.0.1" :handler cider-nrepl-handler)
  ;; Drop an `.nrepl-port` file so Calva's `autoConnectRepl` finds the
  ;; already-running server and connects on editor open — no jack-in, no
  ;; host:port prompt. Written after `start-server` returns (the socket is
  ;; bound and listening by then), so the file's presence means the nREPL is
  ;; accepting connections. `autoConnectRepl` checks for this file *once* at
  ;; editor activation and does not retry, so the code-server service is
  ;; gated on the nREPL port being reachable (bin/wait-repl.sh) to keep the
  ;; JVM's slow cold start from racing that check. (It's a dotfile, not a
  ;; .clj, so Clay's directory watcher ignores it.)
  (spit ".nrepl-port" "1339")
  (clay/make! {:source-path "notebook.clj"
               :live-reload true
               :browse      false
               :port        1971})
  @(promise))
