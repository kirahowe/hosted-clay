(ns hosted-clay.email-test
  "Email component tests. The Resend request shape is built by a pure
   helper and checked directly; the send outcome (throw on failure,
   log-only without a key) is checked through the component's init."
  (:require [clojure.test :refer [deftest is testing]]
            [charred.api :as json]
            [integrant.core :as ig]
            [org.httpkit.client :as http]
            [hosted-clay.email :as email]))

(deftest builds-the-resend-request
  (let [req  (#'email/resend-request {:api-key {:value "key_123"} :from "Clay <c@example.com>"}
                                     {:to "a@b.com" :subject "Hi" :text "Body"})
        body (json/read-json (:body req))]
    (is (= :post (:method req)))
    (is (= "https://api.resend.com/emails" (:url req)))
    (is (= "Bearer key_123" (get-in req [:headers "Authorization"])))
    (is (= ["a@b.com"] (get body "to")))
    (is (= "Clay <c@example.com>" (get body "from")))
    (is (= "Hi" (get body "subject")))
    (is (= "Body" (get body "text")))))

(deftest log-only-without-api-key
  (testing "with no api key the sender logs and never throws"
    (let [send (ig/init-key :hosted-clay/email {:from "Clay <c@example.com>"})]
      (is (fn? send))
      (is (nil? (send {:to "a@b.com" :subject "s" :text "t"}))))))

(deftest resend-failure-throws
  (testing "a non-2xx response throws so the caller can retry instead of marking warned"
    (with-redefs [http/request (fn [_] (delay {:status 422 :body "invalid"}))]
      (let [send (ig/init-key :hosted-clay/email {:api-key {:value "k"} :from "f"})]
        (is (thrown? clojure.lang.ExceptionInfo
                     (send {:to "a@b.com" :subject "s" :text "t"})))))))
