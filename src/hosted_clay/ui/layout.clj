(ns hosted-clay.ui.layout
  (:require [hiccup2.core :as h]))

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
           [:title title]
           [:link {:rel "stylesheet" :href "/static/css/tokens.css"}]
           [:link {:rel "stylesheet" :href "/static/css/main.css"}]
           head]
          [:body {:class body-class} body]]))))

(defn site-header
  "The shared header: wordmark plus nav links."
  [& links]
  [:header.site
   [:a {:href "/"} "Clay notebooks"]
   (into [:nav] links)])

(defn not-found
  "The body for a 404, wrapped in the standard layout."
  [message]
  (page "Not found"
        [:main
         [:h1 "Not found"]
         [:p.muted message]
         [:p [:a {:href "/"} "Back to the homepage"]]]))

(defn forbidden
  "The body for a 403. Offers a sign-out so a signed-in visitor who hit
   a wall isn't stuck — `/logout` is public, so it always works."
  [message]
  (page "Not allowed"
        [:main
         [:h1 "Not allowed"]
         [:p.muted message]
         [:form {:method "post" :action "/logout"}
          [:button {:type "submit"} "Sign out"]]]))
