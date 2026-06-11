;; # Welcome to your Clay notebook
;;
;; This file is a regular Clojure namespace. Clay renders it: comments
;; like this one become prose, and the value of each top-level form is
;; displayed below it. Edit the source in the editor tab — every save
;; re-renders this page.

(ns notebook
  (:require [tablecloth.api :as tc]
            [scicloj.tableplot.v1.plotly :as plotly]))

;; ## Data with Tablecloth
;;
;; Tablecloth is a dataset-processing library on top of tech.ml.dataset.
;; Here is a tiny dataset built from scratch:

(def flowers
  (tc/dataset {:species ["setosa" "versicolor" "virginica"]
               :sepal-length [5.0 5.9 6.6]
               :sepal-width  [3.4 2.8 3.0]}))

flowers

;; ## Plots with Tableplot
;;
;; Tableplot draws plots from datasets with a layered grammar:

(-> flowers
    (plotly/layer-bar {:=x :species
                       :=y :sepal-length}))

;; ## Where to go next
;;
;; - Everything in [Noj](https://scicloj.github.io/noj/) is on the
;;   classpath: Tablecloth, Tableplot, Fastmath, and friends.
;; - The [Clay documentation](https://scicloj.github.io/clay/) shows
;;   what renders and how to control it.
;; - Connect a REPL: the editor runs Calva, and an nREPL server is
;;   already listening on localhost:1339.
