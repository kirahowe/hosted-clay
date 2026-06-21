;; # Welcome to your Clay notebook
;;
;; You're looking at a **Clojure namespace**. The editor is on the left;
;; the rendered notebook is on the right. Comment lines like this one
;; become prose, and the value of each piece of code shows up right below
;; it.
;;
;; There are two ways to run your code:
;;
;; 1. **Save to re-render.** Every time you save (`Cmd`/`Ctrl`+`S`), the
;;    page on the right re-renders from this file. This is the main way to
;;    work — edit, save, look right.
;; 2. **Evaluate at the REPL.** A *REPL* (read-eval-print loop) runs one
;;    expression at a time and shows the result instantly, without
;;    re-rendering the whole page — handy for quick experiments. One is
;;    already connected for you; the next form lets you try it.

(ns notebook
  (:require [tablecloth.api :as tc]
            [scicloj.tableplot.v1.plotly :as plotly]))

;; ## Try the REPL
;;
;; Click anywhere inside the parentheses below and press `Alt`+`Enter`.
;; The result (`2`) appears right next to the code — no save, no page
;; reload. That's the REPL. (Saving still works too; you'll see the same
;; `2` render on the right.)

(+ 1 1)

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
