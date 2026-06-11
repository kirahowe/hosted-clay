(ns watch
  "In-sprite entry point: an nREPL server for Calva on localhost:1339
   and Clay in live-reload mode serving the rendered notebook on 1971.
   Run as `clojure -M:watch` by the `notebook` sprite service."
  (:require [cider.nrepl :refer [cider-nrepl-handler]]
            [nrepl.server :as nrepl]
            [scicloj.clay.v2.api :as clay]))

(defn -main [& _]
  (nrepl/start-server :port 1339 :bind "127.0.0.1" :handler cider-nrepl-handler)
  (clay/make! {:source-path "notebook.clj"
               :live-reload true
               :browse      false
               :port        1971})
  @(promise))
