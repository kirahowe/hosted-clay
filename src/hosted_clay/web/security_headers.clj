(ns hosted-clay.web.security-headers
  "Baseline security headers for the control plane's own responses.

   Wraps the whole ring handler (outside the router), so every response —
   pages, static assets, 404s from the default handler, even the 500 page —
   carries the baseline. Two tiers:

   - Our own pages/assets get the full set: a strict CSP (script-src 'self'
     works because nothing is inline — the login island is a static module
     and destructive-form confirms are data-confirm hooks in app.js),
     clickjacking protection, nosniff, and a referrer policy.

   - The proxied notebook surfaces (/n/:id/view/*, /n/:id/edit/*, /s/*) get
     ONLY Strict-Transport-Security. Their bodies are Clay / code-server /
     snapshot content whose framing and script needs are managed deliberately
     elsewhere (the proxy strips or preserves framing headers per route — see
     hosted-clay.proxy), and a control-plane CSP stapled onto the editor
     would break it. Kept in step with the path scheme in hosted-clay.routes.

   Existing response headers always win, and responses without a :status
   (http-kit WebSocket upgrades via as-channel) pass through untouched.

   HSTS is emitted only for an https base-url: it's meaningless (and
   ignored by browsers) over plain http, so dev at http://localhost never
   pins anything."
  (:require [clojure.string :as str]
            [integrant.core :as ig]))

(def ^:private hsts-value
  ;; Two years, the preload-list threshold. No `preload`: submitting to the
  ;; Chrome list is a deliberate, hard-to-undo step the operator should take
  ;; explicitly, not a default.
  "max-age=63072000; includeSubDomains")

(defn- origin-of
  "The scheme://host[:port] origin of a URL string, or nil. Used to turn the
   configured Hanko API URL into a CSP connect-src source."
  [url]
  (try
    (let [u      (java.net.URI. (str url))
          scheme (.getScheme u)
          host   (.getHost u)
          port   (.getPort u)]
      (when (and scheme host)
        (str scheme "://" host (when-not (neg? port) (str ":" port)))))
    (catch Exception _ nil)))

(defn content-security-policy
  "The site CSP. `hanko-origin` (the auth project's API origin) is the one
   external endpoint the browser must reach — the <hanko-auth> island calls
   it from /login; everything else is same-origin. style-src allows inline
   styles because web components (hanko-auth) inject <style> into their
   shadow roots, which CSP still gates."
  [hanko-origin]
  (str/join "; "
            ["default-src 'self'"
             "script-src 'self'"
             "style-src 'self' 'unsafe-inline'"
             "img-src 'self' data:"
             (str "connect-src 'self'" (when hanko-origin (str " " hanko-origin)))
             "frame-src 'self'"
             "frame-ancestors 'self'"
             "base-uri 'self'"
             "form-action 'self'"
             "object-src 'none'"]))

(def ^:private proxied-uri
  ;; The route trees whose response bodies come from (or stand in for) a
  ;; notebook sprite, where the page headers must stay the proxy's call: the
  ;; owner's live output and editor panes nested under /n/:id, and the whole
  ;; public share tree. The rest of /n/:id (the workspace page and its
  ;; control actions) is our own HTML and gets the full set. Mirrors the
  ;; path scheme documented in hosted-clay.routes.
  #"^/(?:s(?:/.*)?|n/[^/]+/(?:view|edit)(?:/.*)?)$")

(defn- proxied? [uri]
  (boolean (re-matches proxied-uri uri)))

(defn wrap-security-headers
  [handler {:keys [site-origin hanko-api-url]}]
  (let [https?   (str/starts-with? (str site-origin) "https://")
        csp      (content-security-policy (origin-of hanko-api-url))
        page-set (cond-> {"content-security-policy" csp
                          "x-frame-options"         "SAMEORIGIN"
                          "x-content-type-options"  "nosniff"
                          "referrer-policy"         "strict-origin-when-cross-origin"}
                   https? (assoc "strict-transport-security" hsts-value))
        hsts-set (if https? {"strict-transport-security" hsts-value} {})]
    (fn [req]
      (let [resp    (handler req)
            headers (if (proxied? (str (:uri req))) hsts-set page-set)]
        ;; Only decorate a materialized response; merge under, so anything
        ;; the handler set deliberately survives.
        (if (and (map? resp) (:status resp))
          (update resp :headers #(merge headers %))
          resp)))))

(defmethod ig/init-key :hosted-clay.web/security-headers
  [_ {:keys [handler base-url hanko-api-url]}]
  (wrap-security-headers handler {:site-origin   base-url
                                  :hanko-api-url hanko-api-url}))
