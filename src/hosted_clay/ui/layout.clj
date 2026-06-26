(ns hosted-clay.ui.layout
  (:require [hiccup2.core :as h]))

(def ^:private description
  "Hosted Clay notebooks for Clojure data science — a real editor, a live REPL, zero setup.")

(defn page
  "Wraps `body` in a complete HTML document with the standard head and
   stylesheets. `body` is a hiccup form that becomes the contents of
   <body>. The optional opts map may supply extra `:head` hiccup (e.g. a
   page-specific stylesheet) and a `:body-class`."
  ([title body] (page title body nil))
  ([title body {:keys [head body-class]}]
   (str "<!DOCTYPE html>\n"
        (h/html
         {:mode :html}
         [:html {:lang "en"}
          [:head
           [:meta {:charset "utf-8"}]
           [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
           [:meta {:name "description" :content description}]
           [:meta {:name "color-scheme" :content "light dark"}]
           ;; Two theme-colors, one per scheme, so the browser chrome tracks
           ;; the OS preference on its own — these mirror --bg in tokens.css.
           [:meta {:name "theme-color" :content "#ffffff" :media "(prefers-color-scheme: light)"}]
           [:meta {:name "theme-color" :content "#0d0d0f" :media "(prefers-color-scheme: dark)"}]
           [:title title]
           [:link {:rel "icon" :type "image/svg+xml" :href "/static/favicon.svg"}]
           [:link {:rel "stylesheet" :href "/static/css/tokens.css"}]
           [:link {:rel "stylesheet" :href "/static/css/main.css"}]
           [:script {:src "/static/js/app.js" :defer true}]
           head]
          [:body {:class body-class} body]]))))

(defn site-header
  "The shared header: wordmark and page nav links."
  [& links]
  [:header.site
   [:a.wordmark {:href "/"} "clay notebooks"]
   (-> [:nav] (into links))])

(defn site-footer
  "The shared footer: a copyright line and a set of links."
  [& links]
  [:footer.site
   [:span "© Clay Notebooks · MIT"]
   (-> [:nav] (into links))])

(defn status-page
  "The single template behind every full-page status screen — the 4xx/5xx
   error pages, a waking or provisioning notebook, a suspended / over-limit
   notebook, a failed setup. Routing them all through here keeps every
   \"something is happening\" page visually identical: one centred status card
   with an optional spinner, an eyebrow, a heading, a lead, and an action row.

   opts:
     :title       <title> text — defaults to :heading
     :header?     render the site header (default true); false for pages shown
                  embedded in an iframe (e.g. a waking notebook)
     :nav         seq of header nav hiccup (a back link, a sign-out form, …)
     :spinner?    show the loading spinner above the eyebrow
     :eyebrow     short status label (\"404\", \"Setup failed\", \"Suspended\")
     :heading     the h1 (required)
     :lead        the explanatory paragraph — string or hiccup
     :actions     seq of action hiccup (buttons / links / inline-forms)
     :head        extra <head> hiccup (e.g. a meta-refresh)
     :main-attrs  attrs hung on <main> (e.g. a provisioning poll hook)
     :body-class  passed through to `page`"
  [{:keys [title header? nav spinner? eyebrow heading lead actions head main-attrs body-class]
    :or   {header? true}}]
  (page
   (or title heading)
   [:div
    (when header? (apply site-header nav))
    [:main (or main-attrs {})
     [:section.status
      (cond-> [:div.status-card]
        spinner?      (conj [:div.spinner {:role "status" :aria-label (or eyebrow "Loading")}])
        eyebrow       (conj [:p.eyebrow eyebrow])
        :always       (conj [:h1 heading])
        lead          (conj [:p.lead lead])
        (seq actions) (conj (into [:div.actions] actions)))]]]
   {:head head :body-class body-class}))

(defn not-found
  "The body for a 404, wrapped in the standard layout."
  [message]
  (status-page
   {:eyebrow "404"
    :heading "Not found"
    :lead    message
    :actions [[:a.button {:href "/"} "Back to the homepage"]]}))

(defn forbidden
  "The body for a 403. Offers a sign-out so a signed-in visitor who hit
   a wall isn't stuck — `/logout` is public, so it always works."
  [message]
  (status-page
   {:eyebrow "403"
    :heading "Not allowed"
    :lead    message
    :actions [[:form.inline-form {:method "post" :action "/logout"}
               [:button.button--primary {:type "submit"} "Sign out"]]]}))

(defn unavailable
  "The body for a 503 — a temporary, self-resolving pause (e.g. a notebook
   that's spent its monthly budget), not a permanent denial like a 403."
  [message]
  (status-page
   {:eyebrow "503"
    :heading "Temporarily paused"
    :lead    message
    :actions [[:a.button {:href "/"} "Back to the homepage"]]}))

(defn error
  "The body for a 500. Deliberately static — it must render even when the
   thing that failed is the database or a domain component."
  []
  (status-page
   {:eyebrow "500"
    :heading "Something went wrong"
    :lead    "An unexpected error occurred on our end. Please try again in a moment."
    :actions [[:a.button {:href "/"} "Back to the homepage"]]}))
