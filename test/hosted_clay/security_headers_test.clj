(ns hosted-clay.security-headers-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hosted-clay.test-system :as ts]
            [hosted-clay.web.security-headers :as sec]))

(defn- echo-handler [resp]
  (fn [_req] resp))

(deftest full-set-on-our-pages-hsts-only-on-proxied
  (let [handler (sec/wrap-security-headers
                 (echo-handler {:status 200 :headers {}})
                 {:site-origin   "https://clay.example"
                  :hanko-api-url "https://abc123.hanko.io/"})]
    (testing "our own pages get the whole baseline"
      (let [{:keys [headers]} (handler {:uri "/login"})]
        (is (str/includes? (get headers "content-security-policy")
                           "script-src 'self' https://kirasumami.pikapod.net")
            "the Umami analytics script is whitelisted alongside 'self'")
        (is (str/includes? (get headers "content-security-policy")
                           "connect-src 'self' https://kirasumami.pikapod.net https://abc123.hanko.io")
            "both the analytics beacon and the login island's Hanko API are whitelisted")
        (is (= "SAMEORIGIN" (get headers "x-frame-options")))
        (is (= "nosniff" (get headers "x-content-type-options")))
        (is (str/starts-with? (get headers "strict-transport-security") "max-age="))))
    (testing "proxied notebook surfaces keep the proxy's header policy — HSTS only"
      (doseq [uri ["/n/abc/view/" "/n/abc/view/counter" "/n/abc/edit/main.js"
                   "/s/tok123" "/s/tok123/"]]
        (let [{:keys [headers]} (handler {:uri uri})]
          (is (nil? (get headers "content-security-policy")) uri)
          (is (nil? (get headers "x-frame-options")) uri)
          (is (some? (get headers "strict-transport-security")) uri))))
    (testing "the rest of /n/:id — the workspace page and its control actions —
              is our own HTML and gets the full set"
      (doseq [uri ["/n/abc" "/n/abc/status" "/n/abc/source"]]
        (let [{:keys [headers]} (handler {:uri uri})]
          (is (some? (get headers "content-security-policy")) uri)
          (is (= "SAMEORIGIN" (get headers "x-frame-options")) uri))))))

(deftest no-hsts-over-plain-http
  (let [handler (sec/wrap-security-headers
                 (echo-handler {:status 200 :headers {}})
                 {:site-origin "http://localhost:4000"})]
    (is (nil? (get-in (handler {:uri "/"}) [:headers "strict-transport-security"]))
        "an http dev origin must not pin HSTS")))

(deftest handler-set-headers-win
  (let [handler (sec/wrap-security-headers
                 (echo-handler {:status 200
                                :headers {"x-frame-options" "DENY"}})
                 {:site-origin "https://clay.example"})]
    (is (= "DENY" (get-in (handler {:uri "/"}) [:headers "x-frame-options"]))
        "a header the handler set deliberately is never clobbered")))

(deftest websocket-upgrades-pass-through-untouched
  ;; http-kit's as-channel returns a map with no :status; decorating it
  ;; would be meaningless (headers are sent at upgrade time), so it must
  ;; come back exactly as produced.
  (let [resp    {:body :async-channel}
        handler (sec/wrap-security-headers (echo-handler resp)
                                           {:site-origin "https://clay.example"})]
    (is (identical? resp (handler {:uri "/n/abc/view/live-reload"})))))

(deftest wired-into-the-system
  (ts/with-system [:hosted-clay.web/security-headers]
    (fn [system]
      (let [handler (:hosted-clay.web/security-headers system)]
        (testing "the home page carries the baseline through the real stack"
          (let [{:keys [status headers]} (handler {:request-method :get :uri "/"})]
            (is (= 200 status))
            (is (some? (get headers "content-security-policy")))))
        (testing "even unmatched 404s are covered (the wrap sits outside the router)"
          (let [{:keys [status headers]} (handler {:request-method :get :uri "/no-such-route"})]
            (is (= 404 status))
            (is (some? (get headers "content-security-policy")))))))))
