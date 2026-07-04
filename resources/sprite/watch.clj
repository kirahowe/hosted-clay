(ns watch
  "In-sprite entry point: an nREPL server for Calva on localhost:1339
   and Clay in live-reload mode serving the rendered notebook on 1971.
   Run as `clojure -M:watch` by the `notebook` sprite service."
  (:require [cider.nrepl :refer [cider-nrepl-handler]]
            [nrepl.server :as nrepl]
            [scicloj.clay.v2.api :as clay]))

(defn ready!
  "Called by Calva's `autoEvaluateCode.onConnect` hook the moment it connects.
   Two jobs, matching what Calva's default onConnect would do plus our marker:
   load the standard REPL helpers (doc/source/pprint — `repl-requires`) into
   the session namespace, then write the readiness marker Caddy serves at
   /repl-ready. The workspace overlay polls that marker and drops only once
   this eval has provably round-tripped, so the editor is revealed only when
   eval genuinely works. `require` refers into `*ns*`, which the nREPL eval
   binds to the connecting session's namespace (`user`) — the same target as
   when this body was inlined in the onConnect string. Lives here as a named
   fn so the string Calva echoes into the REPL on connect stays a clean
   one-liner instead of the whole do-form."
  []
  (when-let [requires (resolve 'clojure.main/repl-requires)]
    (apply require @requires))
  (spit "/home/sprite/repl-ready" "ok"))

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
