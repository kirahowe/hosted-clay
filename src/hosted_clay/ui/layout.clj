(ns hosted-clay.ui.layout
  (:require [hiccup2.core :as h]))

(def ^:private description
  "Hosted Clay notebooks for Clojure data science — a real editor, a live REPL, zero setup.")

(def ^:private theme-bootstrap
  ;; Inline and first in <head> so the stored preference is applied
  ;; before first paint — no flash of the wrong theme. The matching
  ;; toggle behaviour lives in app.js; this only restores the choice.
  (h/raw
   (str "(function(){try{var t=localStorage.getItem('theme');"
        "if(t==='dark'||t==='light')document.documentElement.dataset.theme=t;}catch(e){}})();")))

(def ^:private icon-sun
  [:svg.icon-sun {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
                  :stroke-width "2" :stroke-linecap "round" :aria-hidden "true"}
   [:circle {:cx "12" :cy "12" :r "4"}]
   [:path {:d (str "M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4"
                   "M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4")}]])

(def ^:private icon-moon
  [:svg.icon-moon {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
                   :stroke-width "2" :stroke-linecap "round" :stroke-linejoin "round"
                   :aria-hidden "true"}
   [:path {:d "M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"}]])

(defn theme-toggle
  "The light/dark switch. CSS shows the icon for the active scheme; app.js
   flips html[data-theme] and persists the choice."
  []
  [:button.theme-toggle {:type "button" :data-theme-toggle true
                         :aria-label "Toggle dark mode" :title "Toggle dark mode"}
   icon-sun icon-moon])

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
           [:meta {:name "theme-color" :content "#ffffff" :media "(prefers-color-scheme: light)"}]
           [:meta {:name "theme-color" :content "#0d0d0f" :media "(prefers-color-scheme: dark)"}]
           [:title title]
           [:script theme-bootstrap]
           [:link {:rel "icon" :type "image/svg+xml" :href "/static/favicon.svg"}]
           [:link {:rel "stylesheet" :href "/static/css/tokens.css"}]
           [:link {:rel "stylesheet" :href "/static/css/main.css"}]
           [:script {:src "/static/js/app.js" :defer true}]
           head]
          [:body {:class body-class} body]]))))

(defn site-header
  "The shared header: wordmark, page nav links, and the theme toggle
   (always last)."
  [& links]
  [:header.site
   [:a.wordmark {:href "/"} "clay notebooks"]
   (-> [:nav] (into links) (conj (theme-toggle)))])

(defn not-found
  "The body for a 404, wrapped in the standard layout."
  [message]
  (page "Not found"
        [:div
         (site-header)
         [:main
          [:h1 "Not found"]
          [:p.muted message]
          [:p [:a {:href "/"} "Back to the homepage"]]]]))

(defn forbidden
  "The body for a 403. Offers a sign-out so a signed-in visitor who hit
   a wall isn't stuck — `/logout` is public, so it always works."
  [message]
  (page "Not allowed"
        [:div
         (site-header)
         [:main
          [:h1 "Not allowed"]
          [:p.muted message]
          [:form {:method "post" :action "/logout"}
           [:button {:type "submit"} "Sign out"]]]]))

(defn error
  "The body for a 500. Deliberately static — it must render even when the
   thing that failed is the database or a domain component."
  []
  (page "Something went wrong"
        [:div
         (site-header)
         [:main
          [:h1 "Something went wrong"]
          [:p.muted "An unexpected error occurred on our end. Please try again in a moment."]
          [:p [:a {:href "/"} "Back to the homepage"]]]]))
