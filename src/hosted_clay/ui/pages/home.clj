(ns hosted-clay.ui.pages.home
  (:require [hiccup2.core :as h]
            [hosted-clay.ui.layout :as layout]))

(def ^:private icon-editor
  [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
         :stroke-width "1.5" :stroke-linecap "round" :stroke-linejoin "round"
         :aria-hidden "true"}
   [:path {:d "M16 18l6-6-6-6"}]
   [:path {:d "M8 6l-6 6 6 6"}]])

(def ^:private icon-live
  [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
         :stroke-width "1.5" :stroke-linecap "round" :stroke-linejoin "round"
         :aria-hidden "true"}
   [:circle {:cx "12" :cy "12" :r "3"}]
   [:path {:d "M5 12a7 7 0 0 1 14 0M2 12a10 10 0 0 1 20 0"}]])

(def ^:private icon-share
  [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
         :stroke-width "1.5" :stroke-linecap "round" :stroke-linejoin "round"
         :aria-hidden "true"}
   [:circle {:cx "18" :cy "5" :r "3"}]
   [:circle {:cx "6" :cy "12" :r "3"}]
   [:circle {:cx "18" :cy "19" :r "3"}]
   [:path {:d "M8.6 13.5l6.8 4M15.4 6.5l-6.8 4"}]])

(def ^:private icon-stack
  [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
         :stroke-width "1.5" :stroke-linecap "round" :stroke-linejoin "round"
         :aria-hidden "true"}
   [:path {:d "M12 2l9 5-9 5-9-5 9-5z"}]
   [:path {:d "M3 12l9 5 9-5"}]
   [:path {:d "M3 17l9 5 9-5"}]])

(def ^:private icon-sandbox
  [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
         :stroke-width "1.5" :stroke-linecap "round" :stroke-linejoin "round"
         :aria-hidden "true"}
   [:rect {:x "3" :y "3" :width "18" :height "18" :rx "0"}]
   [:path {:d "M9 9h6v6H9z"}]])

(def ^:private icon-zero
  [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
         :stroke-width "1.5" :stroke-linecap "round" :stroke-linejoin "round"
         :aria-hidden "true"}
   [:circle {:cx "12" :cy "12" :r "9"}]
   [:path {:d "M8 12h8"}]])

(def ^:private preview-code
  ;; Hand-highlighted Clojure snippet — the welcome cell of a fresh
  ;; notebook. Spans are decorative; the meaning is in the markup.
  (h/raw
   (str "<pre><code>"
        "<span class=\"c-com\">;; Welcome. Save this cell — Clay re-renders it instantly.</span>\n"
        "(<span class=\"c-fn\">ns</span> notebook)\n"
        "(<span class=\"c-fn\">require</span> '[scicloj.tableplot])\n\n"
        "(<span class=\"c-fn\">-&gt;&gt;</span> \"/data/iris.csv\"\n"
        "  (<span class=\"c-fn\">tablecloth/read-csv</span>)\n"
        "  (<span class=\"c-fn\">tableplot/layer-histogram</span> \n"
        "    {:x <span class=\"c-str\">:sepal-width</span>}))\n\n"
        "<span class=\"c-com\">;; → renders the histogram beside this cell.</span>\n"
        "</code></pre>")))

(defn- hero []
  [:section.hero
   [:p.eyebrow "Clay · Noj · Calva — in the browser"]
   [:h1.display
    "Clojure data science," [:br]
    "zero setup."]
   [:p.lead
    "Hosted " [:a {:href "https://scicloj.github.io/clay/"} "Clay"]
    " notebooks for the " [:a {:href "https://scicloj.github.io/"} "scicloj"]
    " ecosystem. Sign in, create a notebook, and you're editing Clojure in the "
    "browser — " [:a {:href "https://scicloj.github.io/noj/"} "Noj"]
    " (Tablecloth, Tableplot, and the rest) already on the classpath."]
   [:div.actions
    [:a.button.button--primary.button--lg {:href "/dashboard"}
     "Create your notebook"]
    [:a.button.button--lg {:href "https://scicloj.github.io/clay/"}
     "What's Clay?"]]
   [:p.subtle "Free during the prototype — one notebook per account."]])

(defn- preview []
  [:section
   [:div.preview
    [:div.preview-bar
     [:span.preview-dot] [:span.preview-dot] [:span.preview-dot]
     [:span.mono "notebook.clj"]]
    [:div.preview-body preview-code]]])

(defn- features []
  [:section
   [:div.section-head
    [:p.eyebrow "What's in the box"]
    [:h2 "A real environment, not a toy."]
    [:p "Everything you'd set up locally, already wired together and running the second you hit create."]]
   [:div.features
    [:div.feature
     [:span.feature-icon icon-editor]
     [:h3 "A real editor"]
     [:p "VS Code with Calva, wired to a live nREPL. The same workflow you'd run on your own machine, in a browser tab."]]
    [:div.feature
     [:span.feature-icon icon-live]
     [:h3 "Live re-render"]
     [:p "Clay re-renders the notebook on every save. Source and output sit side by side — no context switching."]]
    [:div.feature
     [:span.feature-icon icon-stack]
     [:h3 "Noj preloaded"]
     [:p "Tablecloth, Tableplot, Kindly, and the rest of the scicloj stack, already on the classpath. No deps.edn fiddling."]]
    [:div.feature
     [:span.feature-icon icon-sandbox]
     [:h3 "Your own sandbox"]
     [:p "Each notebook runs in its own Sprite — a stateful Linux machine. You have a shell, a filesystem, your own state."]]
    [:div.feature
     [:span.feature-icon icon-share]
     [:h3 "Share read-only"]
     [:p "A single link — anyone can view the rendered notebook. No accounts, no installs, no setup on their end either."]]
    [:div.feature
     [:span.feature-icon icon-zero]
     [:h3 "Zero setup"]
     [:p "No local install, no JVM wrangling, no tooling drift. Sign in and you're writing Clojure in seconds."]]]])

(defn- cta []
  [:section.cta
   [:p.eyebrow "Start now"]
   [:h2 "Your first notebook is seconds away."]
   [:p "Sign in with a passkey or a one-time email code. New here? Signing in creates your account."]
   [:a.button.button--primary.button--lg {:href "/dashboard"}
    "Create your notebook"]])

(defn render []
  (layout/page
   "Clay notebooks"
   [:div
    (layout/site-header
     [:a {:href "/dashboard"} "Dashboard"]
     [:a {:href "/login"} "Sign in"])
    [:main
     (hero)
     (preview)
     (features)
     (cta)]
    (layout/site-footer
     [:a {:href "https://scicloj.github.io/clay/"} "Clay"]
     [:a {:href "https://scicloj.github.io/"} "scicloj"]
     [:a {:href "https://sprites.dev"} "Sprites"]
     [:a {:href "https://github.com/kirahowe/hosted-clay"} "GitHub"])]))
