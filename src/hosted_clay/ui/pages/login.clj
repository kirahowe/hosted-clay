(ns hosted-clay.ui.pages.login
  "The sign-in page. Hanko's <hanko-auth> web component (a JS island)
   runs the passkey/passcode flow and sets the `hanko` session cookie;
   on session creation we redirect to the dashboard."
  (:require [hiccup2.core :as h]
            [hosted-clay.routes :as routes]
            [hosted-clay.ui.layout :as layout]))

(defn- hanko-island [api-url]
  ;; TODO: pin the hanko-elements version (esm.run/@teamhanko/hanko-elements@x.y.z)
  ;; and add SRI, or vendor the module under resources/public/js, before
  ;; this grows past a prototype. The redirect uses the `hanko` client
  ;; returned by register() — its onSessionCreated, not a DOM event.
  (h/raw
   (str "import { register } from "
        "'https://esm.run/@teamhanko/hanko-elements';\n"
        "const { hanko } = await register('" api-url "');\n"
        "hanko.onSessionCreated(() => { document.location.href = '" (routes/dashboard) "'; });\n")))

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
       [:hanko-auth]
       [:script {:type "module"} (hanko-island api-url)]]]]]))
