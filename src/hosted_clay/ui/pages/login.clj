(ns hosted-clay.ui.pages.login
  "The sign-in page. Hanko's <hanko-auth> web component (a JS island)
   runs the passkey/passcode flow and sets the `hanko` session cookie;
   on session creation login.js redirects to the dashboard. The
   hanko-elements bundle is vendored under /static/js/vendor (pinned,
   checksum-verified against npm) and the API URL travels on a data
   attribute, so the page needs no inline script and no third-party
   CDN — it works under the site's script-src 'self' CSP."
  (:require [hosted-clay.ui.layout :as layout]))

(defn render [api-url]
  (layout/page
   "Sign in"
   [:div
    (layout/site-header)
    [:main
     [:div.status
      [:div.status-card
       [:p.eyebrow "Sign in"]
       [:h1 "Welcome"]
       [:p.lead
        "Sign in with a passkey or a one-time email code. New here? "
        "Signing in creates your account."]
       [:hanko-auth {:data-api-url api-url}]
       [:script {:type "module" :src "/static/js/login.js"}]]]]]))
