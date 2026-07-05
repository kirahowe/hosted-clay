(ns hosted-clay.ui.pages.home
  (:require [hosted-clay.routes :as routes]
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

(defn- hero []
  [:section.hero
   [:p.eyebrow "Clay · Noj · Calva — in the browser"]
   [:h1.display
    "Clojure data science," [:br]
    "no setup required."]
   [:p.lead
    "Hosted " [:a {:href "https://scicloj.github.io/clay/"} "Clay"]
    " notebooks for the " [:a {:href "https://scicloj.github.io/"} "scicloj"]
    " community. Sign in, spin up a notebook, and try real Clojure data science in the browser, with "
    [:a {:href "https://scicloj.github.io/noj/"} "Noj"]
    " loaded and ready to go."]
   [:div.actions
    [:a.button.button--primary.button--lg {:href (routes/dashboard)}
     "Create your notebook"]]
   ;; Placeholder href — swap "#" for the real read-only share link when ready.
   [:p.hero-example [:a {:href "#"} "See a live example →"]]
   [:p.subtle "One free notebook per person while this is a prototype."]])

(defn- preview []
  [:section
   [:div.preview
    [:img.preview-img
     {:src     "/static/img/notebook-split.png"
      :alt     "The notebook open in the browser: a VS Code editor with Calva on the left showing a Clojure ns form and an iris scatter-plot snippet, and the live Clay-rendered notebook on the right — the same code above a petal-length vs petal-width scatter plot of the iris dataset, coloured by species with linear-model trend lines."
      :width   "2000"
      :height  "993"
      :loading "lazy"}]]])

(defn- features []
  [:section
   [:div.section-head
    [:p.eyebrow "What's in the box"]
    [:h2 "A real environment, ready to go."]
    [:p "Everything you need to get started, already wired together and running in the browser."]]
   [:div.features
    [:div.feature
     [:span.feature-icon icon-editor]
     [:h3 "A real editor"]
     [:p "VS Code with Calva and power tools already installed, wired to a live nREPL with Clojure's language server already running."]]
    [:div.feature
     [:span.feature-icon icon-live]
     [:h3 "Re-render on demand"]
     [:p "The environment is set up to re-render the whole notebook with Clay on every save. Or render form-by-form with Calva's Clay integration."]]
    [:div.feature
     [:span.feature-icon icon-stack]
     [:h3 "Noj preloaded"]
     [:p "Tablecloth, Tableplot, Plotje, Kindly, and the rest of the scicloj stack, already loaded."]]
    [:div.feature
     [:span.feature-icon icon-sandbox]
     [:h3 "Your own sandbox"]
     [:p "Each notebook runs in its own " [:a {:href "https://sprites.dev"} "Sprite"] ", a stateful Linux machine."]]
    [:div.feature
     [:span.feature-icon icon-share]
     [:h3 "Share read-only"]
     [:p "Anyone can view the rendered notebook without needing to log in or install anything."]]
    [:div.feature
     [:span.feature-icon icon-zero]
     [:h3 "Zero setup"]
     [:p "No local install, no JVM management, no tooling to set up. Just sign in and you're writing Clojure right away."]]]])

(defn- faq-item
  [question & answer]
  [:div.faq-item
   [:h3.faq-question question]
   (into [:div.faq-answer] answer)])

(defn- faq []
  [:section
   [:div.section-head
    [:p.eyebrow "More information"]
    [:h2 "FAQs"]]
   [:div.faq
    (faq-item
     "What is this?"
     [:p
      "This is an experiment. Clojure has a lot of benefits when it comes to working with data, but it can be a bit daunting knowing where to start. Clojure's data science community, "
      [:a {:href "https://scicloj.github.io"} "Scicloj"]
      ", has put tremendous effort into making Clojure's data science toolkit easier to use and more approachable in recent years, and this is one more contribution toward that effort — a way to test how much of the barrier to adoption is really just the setup. If we can remove all of that friction by offering a truly zero-setup environment to play with, does getting started feel easier?"]
     [:p
      "Here you'll get a fully functional, free notebook with Clojure's full data science stack loaded and running on a real JVM, backed by your very own "
      [:a {:href "https://sprites.dev"} "Sprite"] "."])
    (faq-item
     "How is it free?"
     [:p
      "For now, I am subsidizing this first phase of the experiment. It is free thanks to my generous "
      [:a {:href "https://github.com/sponsors/kirahowe"} "GitHub Sponsors"]
      ". If it goes well, I will find a way to make it sustainable by adding paid tiers, or something, but for now I am happy to fund this initial prototype phase to see if this is even useful. If you would like to see this continue, please try it out and "
      [:a {:href "mailto:contact@kirahowe.com"} "let me know what you think"]
      ". You can email me, or find me in the Clojurians "
      [:a {:href "https://clojurians.zulipchat.com/#user/383513"} "Zulip"]
      " or "
      [:a {:href "https://clojurians.slack.com/team/UPGS9BS0L"} "Slack"]
      ". You can also contribute to the project financially by "
      [:a {:href "https://github.com/sponsors/kirahowe"} "sponsoring me on GitHub."]])
    (faq-item
     "What are the limitations of this?"
     [:p
      "This initial demo is limited to 40 users, each getting approximately 50 hours of notebook usage, with up to 10 users active at any one time. These limitations are in place to keep costs in check until I decide what to do with this project, if anything."])
    (faq-item
     "What happens to my notebook and my data?"
     [:p
      "Your notebook and its files live on your own Sprite and persist between sessions — close the tab and everything is exactly where you left it, REPL state and all, when you come back. When you step away the sprite suspends itself so it stops using resources, and your next keystroke wakes it."]
     [:p
      "Because this is a free prototype with a limited number of machines available, notebooks that sit unused eventually get deleted. You'll get a warning email after 23 days of inactivity, and the notebook is deleted after 30. And since this is an experimental environment and notebooks are publicly shareable for now, please don't put anything sensitive in a notebook —  no passwords, API keys, or private data. Anything you share with a read-only link becomes visible to anyone who has the link."]
     [:p
      "The full details are on the "
      [:a {:href (routes/privacy)} "privacy & terms"]
      " page."])
    (faq-item
     "Who are you?"
     [:p
      "I'm Kira Howe (formerly McLean). I gave a "
      [:a {:href "https://www.youtube.com/watch?v=MguatDl5u2Q"} "talk about Noj and Clojure's data science stack"]
      " when it was mostly just a dream back at the conj in 2023. "
      [:em "So much"]
      " has happened since then, and I feel like one of the new biggest hurdles to overcome is making Clojure's ecosystem approachable and easy to use. This is one experiment in that direction."])]])

(defn- cta []
  [:section.cta
   [:p.eyebrow "Get started"]
   [:h2 "Your free notebook is waiting."]
   [:p "Sign in or create an account to get started."]
   [:a.button.button--primary.button--lg {:href (routes/dashboard)}
    "Create your notebook"]
   [:p.subtle
    "This project is open source — "
    [:a {:href "https://github.com/kirahowe/hosted-clay"} "browse the code on GitHub"]
    "."]])

(defn render [signed-in?]
  (layout/page
   "Clojure notebooks"
   [:div
    (layout/site-header
     (if signed-in?
       [:a.button {:href (routes/dashboard)} "Dashboard"]
       [:a.button {:href (routes/login)} "Sign in"]))
    [:main
     (hero)
     (preview)
     (features)
     (faq)
     (cta)]
    (layout/site-footer
     [:a {:href (routes/privacy)} "Privacy & terms"]
     [:a {:href "https://scicloj.github.io/clay/"} "Clay"]
     [:a {:href "https://scicloj.github.io/"} "scicloj"]
     [:a {:href "https://sprites.dev"} "Sprites"]
     [:a {:href "https://github.com/kirahowe/hosted-clay"} "GitHub"])]))
