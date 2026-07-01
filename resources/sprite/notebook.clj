;; # Welcome to your Clojure data science notebook
;;
;; If you already know what you're here for, feel free to delete all of this and start fresh. Otherwise keep reading.
;;
;; You're looking at a **Clojure namespace**. The editor is on the left, the rendered notebook is on the right. Comment lines like this one become prose, and the value of each piece of code shows up right below it.
;;
;; This notebook is being rendered on a real JVM, running on your very own [sprite](https://sprites.dev) with the full [Noj](https://scicloj.github.io/noj/) data science stack available — sample datasets, columnar data processing, plotting, statistics, and more.
;;
;; The REPL is already connected and [Calva power tools](https://marketplace.visualstudio.com/items?itemName=betterthantomorrow.calva-power-tools) are pre-installed, so there are a couple of ways to see your code run:
;;
;; 1. **Evaluate a form and watch it render.** Put your cursor anywhere inside a top-level form in the editor and hit `Ctrl`+`Shift`+`Space`, then `a`, then `a`. The form is evaluated in the REPL and Clay renders *just that result* in the panel on the right. This is a typical Clojure workflow: edit a form, run it, look at the result, repeat. (`Ctrl`+`Shift`+`Space`, `a`, `c` renders the smaller form right at the cursor instead of the whole top-level one.)
;; 2. **Re-render the entire namespace.** Either hit `Ctrl`+`Shift`+`Space`, `a`,
;;    `f`, or save the file (`Cmd`/`Ctrl`+`S`) to re-render the whole notebook.
;;
;; ## Why Clojure?
;;
;; Clojure has a comprehensive data science ecosystem that offers a lot of benefits that solve many of the common pain points that come up when working with data:
;; - **Immutability by default.** Your data doesn't change out from under you, because it can't. Clojure data structures are immutable and the entire stack is built on top of them.
;; - **Stability.** In [one study by Pimentel et al.](https://pmc.ncbi.nlm.nih.gov/articles/PMC8106381/) that looked at 1.4 million Jupyter notebooks on GitHub, fewer than a quarter ran top to bottom without errors, and fewer than 4% reproduced their original results. Clojure, by contrast, has a uniquely strong [culture of stability](https://clojure.org/about/history). The community works hard to avoid breaking changes, so a lot of code written years ago still runs unchanged today.
;; - **Your notebook is your program.** This namespace *is* the notebook, so your exploratory work and your production code can't drift apart, they're literally the same file.
;; - **One small, composable vocabulary** for wrangling data.
;; - **Real parallelism for free** on the JVM.
;; - **One language end to end.** You explore, prototype, and deploy all from the same place.

;; ## 1. The namespace *is* the notebook
;;
;; Clojure's data science community has tried to solve [many of the problems with computational notebooks](https://www.microsoft.com/en-us/research/wp-content/uploads/2020/03/chi20c-sub8173-cam-i16.pdf) by taking a different approach to live programming. There are no cells with ambiguous execution order. This file is your notebook *and* an ordinary Clojure source file at the same time, and it loads top to bottom, in the same order every time. You still develop interactively by sending forms to the REPL, or by re-evaluating the whole namespace as you go. Here, it re-renders automatically every time you save.
;;
;; We use [Clay](https://scicloj.github.io/clay/) to render this namespace. Comments become prose, forms become code blocks, and each form is followed by its result in a relevant format. Strings render as text, datasets as tables, plots as charts, and so on. This idea -- values "knowing" how to present themselves -- is the [kindly](https://github.com/scicloj/kindly) convention. Clay implements it, and so can any other rendering library, which is how one renderer handles everything below without any special type handling.
;;
;; Here we load some of the most common tools in Clojure's data science toolkit:

(ns notebook
  (:require [scicloj.metamorph.ml.rdatasets :as rdatasets]
            [tablecloth.api :as tc]
            [tablecloth.column.api :as tcc]
            [scicloj.tableplot.v1.plotly :as plotly]
            [scicloj.plotje.api :as pj]
            [fastmath.ml.regression :as reg]))

;; If you're new to Clojure or working with a REPL, the idea is that you evaluate tiny pieces of your program as you develop it, which makes for a very tight feedback loop and the ability to iterate toward working software very smooth and fast.
;;
;; Here is one very tiny example to run. Put your cursor inside the form below and press `Ctrl`+`Shift`+`Space`, `a`, `a`. The result (`2`) is evaluated by the running REPL, re-rendered by Clay, and shows up on the right. Every code block below runs the same way, so try them as you go.

(+ 1 1)

;; ## 2. Data is just data
;;
;; In the Clojure world you'll hear people say things like "data is just data" a lot. What they mean is that there are no special objects or wrappers or layers to navigate in order to access and manipulate your data. It is fundamentally just made of the same ordinary language-level data structures as everything else (including the Clojure language itself). Even specialized types, like tablecloth datasets, are not parochial objects with special access patterns. They behave just like familiar Clojure data structures in many ways. For example let's load the class iris-ds measurement dataset -- Edgar Anderson's measurements of 150 flowers across three species. `rdatasets` is included in noj and ships the R datasets collection, so there's nothing to download in a separate step.

(def iris-ds
  (rdatasets/datasets-iris))

;; Conceptually, you can think of a tablecloth dataset as a map of column names to values in that column. We can access a column the same way we'd access a value from any other map:

(:petal-length iris-ds-ds)

;; You'll notice that the tablecloth column is a specialized type, but we can still treat it like plain data using the entire Clojure standard library:

(take 15 (sort (:petal-length iris-ds-ds)))

;; The specialized type (in this case a tablecloth column) is there as an optimization to make column-wise operations efficient, but it does not add friction if you just want to access your data like you would any other Clojure data structure.

;; A Tablecloth dataset renders as a table because Clay knows its kind:

(tc/head iris-ds)

;; Its shape and column names are just functions that return plain data:

(tc/shape iris-ds)

(tc/column-names iris-ds)

;; Because Clojure data structures are immutable, any operation on a dataset returns a *new* value — the original is never changed in place, because it can't be. Here we add a column of sepal lengths in millimetres:

(def iris-ds-mm
  (tc/map-columns iris-ds :sepal-length-mm [:sepal-length]
                  (fn [cm] (* 10 cm))))

;; The new dataset has the extra column:

(tc/column-names iris-ds-mm)

;; ...but the original is untouched. There's no ambiguity about what state your dataset is in, because nothing can mutate it:

(tc/column-names iris-ds)

;; ## 3. A single, small vocabulary that threads together
;;
;; Wrangling data in Clojure is typically done as a pipeline you read top to bottom, starting with `->` (the "thread first" macro). It works like a Unix pipe, passing the result of each step as the first argument to the next. [Tablecloth](https://github.com/scicloj/tablecloth) is the main data wrangling library — a collection of functions for transforming datasets that compose cleanly. It also includes the [column operators](https://scicloj.github.io/tablecloth/#column-api) (imported here as `tcc`) for fast column-wise work on large datasets.
;;
;; Let's group the flowers by species and summarise each group:

(-> iris-ds
    (tc/group-by [:species])
    (tc/aggregate {:n              tc/row-count
                   :mean-petal-len #(tcc/mean (:petal-length %))
                   :sd-petal-len   #(tcc/standard-deviation (:petal-length %))
                   :mean-petal-wid #(tcc/mean (:petal-width %))})
    (tc/order-by [:mean-petal-len] :desc))

;; We can already see some information emerging from this dataset -- *setosa* petals are tiny, *virginica* petals are large.

;; ## 4. Visualization is just data too
;;
;; [Plotje](https://scicloj.github.io/plotje/) is Clojure's take on the grammar of graphics. The vocabulary is small and composable, and it threads together the same way the data pipelines do. It's also opinionated: when you don't give it many details, it makes a reasonable guess about how to plot your data. A simple scatter plot is one function call:

(pj/lay-point iris-ds :sepal-length :sepal-width)

;; To split the points by group, map a column to `:color` and Plotje will colour
;; them for you:

(pj/lay-point iris-ds :sepal-length :sepal-width {:color :species})

;; You can also build a plot up incrementally with `pj/pose`. A "pose" is the description of a plot as plain data — which columns become colour, which become axes, what kind of marks to use — before it's turned into a graphic. Here we map petal length and width and group by species:

(-> iris-ds
    (pj/pose :petal-length :petal-width {:color :species})
    pj/lay-point)

;; Different kinds of layers can sit on the same pose. We can add a linear regression to each group above by laying a stat layer on top:

(-> iris-ds
    (pj/pose :petal-length :petal-width {:color :species})
    pj/lay-point
    (pj/lay-smooth {:stat :linear-model}))

;; `pj/options` customises titles, labels, and dimensions — all, of course, just
;; plain Clojure data:

(-> iris-ds
    (pj/pose :petal-length :petal-width {:color :species})
    pj/lay-point
    (pj/lay-smooth {:stat :linear-model})
    (pj/options {:width 560 :height 380
                 :title "Iris-ds petals separate cleanly by species"
                 :x-label "Petal length (cm)"
                 :y-label "Petal width (cm)"}))

;; And `pj/arrange` lays out a little dashboard from a vector of plots:

(pj/arrange
 [(pj/lay-point iris-ds :sepal-length :sepal-width {:color :species})
  (pj/lay-histogram iris-ds :sepal-length {:color :species})]
 {:cols 2})

;; There are many more details and examples in the [Plotje docs](https://scicloj.github.io/plotje/).
;;
;; Plotje isn't your only option, though. [Tableplot](https://scicloj.github.io/tableplot/) builds plots from datasets with a layered grammar to but uses Plotly under the hood so it can render them as interactive charts. Below you can hover over a point to read its values, drag to zoom, and double-click to reset:

(-> iris-ds
    (plotly/layer-point {:=x            :petal-length
                         :=y            :petal-width
                         :=color        :species
                         :=mark-size    9
                         :=mark-opacity 0.7}))

;; The three species fall into clean clusters — the reason iris-ds is the
;; "hello, world" of classification.

;; ## 5. Real statistics
;;
;; The trend lines back in section 4 are backed by linear regression run on the JVM, not the browser. We can fit one manually with [fastmath](https://github.com/generateme/fastmath) and see the full summary with coefficients, standard errors, p-values, and R². We'll predict petal width from petal length:

(reg/lm (:petal-width iris-ds)    ; what we're predicting
        (:petal-length iris-ds)   ; the predictor, one feature per row
        {:names ["petal-length"]})


;; In the summary, we can see R² is around 0.93, meaning petal length explains most of the variation in petal width .

;; ## Where to go next
;;
;; This was a tiny demonstration of how you'd approach a typical data workflow, **load → wrangle → visualise → model**, all in one place.
;;
;; To learn more you can:
;; - Experiment with another bundled dataset. `(rdatasets/datasets-mtcars)` or `(rdatasets/datasets-titanic)` are popular demo ones that are already available here.
;; - Browse the [Noj book](https://scicloj.github.io/noj/) for machine learning, deeper statistics, and more visualisation recipes.
;; - The [Clay documentation](https://scicloj.github.io/clay/) has many more details about what's possible with Clay.
