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

(defn not-found
  "The body for a 404, wrapped in the standard layout."
  [message]
  (page "Not found"
        [:div
         (site-header)
         [:main
          [:section.status
           [:div.status-card
            [:p.eyebrow "404"]
            [:h1 "Not found"]
            [:p.lead message]
            [:a.button {:href "/"} "Back to the homepage"]]]]]))

(defn forbidden
  "The body for a 403. Offers a sign-out so a signed-in visitor who hit
   a wall isn't stuck — `/logout` is public, so it always works."
  [message]
  (page "Not allowed"
        [:div
         (site-header)
         [:main
          [:section.status
           [:div.status-card
            [:p.eyebrow "403"]
            [:h1 "Not allowed"]
            [:p.lead message]
            [:form {:method "post" :action "/logout"}
             [:button.button--primary {:type "submit"} "Sign out"]]]]]]))

(defn error
  "The body for a 500. Deliberately static — it must render even when the
   thing that failed is the database or a domain component."
  []
  (page "Something went wrong"
        [:div
         (site-header)
         [:main
          [:section.status
           [:div.status-card
            [:p.eyebrow "500"]
            [:h1 "Something went wrong"]
            [:p.lead "An unexpected error occurred on our end. Please try again in a moment."]
            [:a.button {:href "/"} "Back to the homepage"]]]]]))
