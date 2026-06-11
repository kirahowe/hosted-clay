(ns hosted-clay.exec-test
  (:require [clojure.test :refer [deftest is]]
            [hosted-clay.sprites.exec :as exec]))

(deftest exec-url-shape
  (is (= (str "wss://api.sprites.dev/v1/sprites/nb-1/exec"
              "?cmd=bash&cmd=-s&path=bash&stdin=true")
         (exec/exec-url "https://api.sprites.dev" "nb-1" ["bash" "-s"] true)))
  (is (= (str "wss://api.sprites.dev/v1/sprites/nb-1/exec"
              "?cmd=echo&cmd=hello+world&path=echo&stdin=false")
         (exec/exec-url "https://api.sprites.dev" "nb-1" ["echo" "hello world"] false))))
