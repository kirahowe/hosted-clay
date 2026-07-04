(ns hosted-clay.auth
  "Authentication: verifying the Hanko session JWT and mapping its claims
   to identity attrs. Verifying a token is the one IO edge here — clj-jwt
   fetches and caches Hanko's JWKS and handles key rotation. The
   claim→attrs mapping is pure."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.github.sikt-no.clj-jwt :as clj-jwt]))

(defn session-token
  "The raw `hanko` session JWT from a Ring request's Cookie header, or nil
   when there's no session cookie. A cheap, unverified read of the cookie
   value — presence is not proof of a valid session."
  [req]
  (some->> (get-in req [:headers "cookie"])
           (re-find #"(?:^|;\s*)hanko=([^;]+)")
           second))

(defn session-present?
  "Whether the request carries a `hanko` session cookie at all. A cheap
   signed-in hint for public pages: it does NOT verify the token, so a
   truthy result only means \"there's a session cookie worth trying\" — the
   real auth gate still runs on protected routes."
  [req]
  (boolean (session-token req)))

(defn verify-token
  "Verify a Hanko session JWT against the JWKS at `jwks-url`. Returns the
   claims map on success, or nil when the token is missing, malformed,
   expired, lacks an `exp` claim, or is signed by an unknown key.

   The `exp` is required, not merely honoured-when-present: buddy only
   checks expiry if the claim exists, so a token without one would
   otherwise verify forever — a session must have a lifetime.

   Catches Throwable on purpose: the token is fully attacker-controlled
   (a raw cookie value), and clj-jwt signals a malformed token with an
   AssertionError rather than an Exception — both should read as \"not
   authenticated\", never a 500."
  [jwks-url token]
  (when (and jwks-url (not (str/blank? token)))
    (try
      (let [claims (clj-jwt/unsign jwks-url token)]
        (when (:exp claims) claims))
      (catch Throwable t
        (log/debug t "rejected session token")
        nil))))

(defn claims->identity-attrs
  "The identity attrs implied by a verified Hanko claims map: the Hanko
   subject becomes the provider subject, and the email claim's `address`
   becomes `email`. Hanko's `email` claim is an object ({address,
   is_primary, is_verified}); we tolerate a bare-string email too, so a
   token-shape change can't silently map `email` to nil and lock out an
   otherwise-valid identity."
  [{:keys [sub email]}]
  {:provider         "hanko"
   :provider-subject sub
   :email            (if (map? email) (:address email) email)})

(defn jwks-url-for
  "Hanko serves its JWK Set at the well-known path off the API base, so
   the JWKS URL is fully determined by `api-url`."
  [api-url]
  (str (str/replace (str api-url) #"/+$" "") "/.well-known/jwks.json"))
