(ns hosted-clay.auth-test
  (:require [clojure.test :refer [deftest is testing]]
            [hosted-clay.auth :as auth]))

(deftest claims-mapping
  (testing "Hanko's object-shaped email claim"
    (is (= {:provider         "hanko"
            :provider-subject "subj-1"
            :email            "a@example.com"}
           (auth/claims->identity-attrs
            {:sub "subj-1" :email {:address "a@example.com" :is_primary true}}))))

  (testing "a bare-string email claim still maps"
    (is (= "a@example.com"
           (:email (auth/claims->identity-attrs {:sub "s" :email "a@example.com"})))))

  (testing "a missing email maps to nil, not an error"
    (is (nil? (:email (auth/claims->identity-attrs {:sub "s"}))))))

(deftest jwks-url-derivation
  (is (= "https://x.hanko.io/.well-known/jwks.json"
         (auth/jwks-url-for "https://x.hanko.io")))
  (is (= "https://x.hanko.io/.well-known/jwks.json"
         (auth/jwks-url-for "https://x.hanko.io//"))))

(deftest verify-token-rejects-garbage
  (testing "missing/blank/malformed tokens read as unauthenticated"
    (is (nil? (auth/verify-token "https://example.invalid/jwks.json" nil)))
    (is (nil? (auth/verify-token "https://example.invalid/jwks.json" "")))
    (is (nil? (auth/verify-token "https://example.invalid/jwks.json" "not-a-jwt")))
    (is (nil? (auth/verify-token nil "x.y.z")))))
