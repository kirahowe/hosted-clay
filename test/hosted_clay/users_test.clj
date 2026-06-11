(ns hosted-clay.users-test
  (:require [clojure.test :refer [deftest is testing]]
            [hosted-clay.db.crud :as crud]
            [hosted-clay.test-system :as ts]
            [hosted-clay.users :as users]))

(def hanko-attrs
  {:provider "hanko" :provider-subject "subj-1" :email "kira@example.com"})

(deftest provisioning
  (ts/with-db
    (fn [ds]
      (testing "first sight of an identity creates user + identity"
        (let [user (users/provision! ds hanko-attrs)]
          (is (= "kira@example.com" (:users/email user)))
          (is (= 1 (crud/count-rows ds :users)))
          (is (= 1 (crud/count-rows ds :identities)))

          (testing "second sight finds the same user"
            (is (= (:users/id user)
                   (:users/id (users/provision! ds hanko-attrs)))))

          (testing "same email through a new provider links, not duplicates"
            (let [via-github (users/provision! ds {:provider         "github"
                                                   :provider-subject "gh-9"
                                                   :email            "kira@example.com"})]
              (is (= (:users/id user) (:users/id via-github)))
              (is (= 1 (crud/count-rows ds :users)))
              (is (= 2 (crud/count-rows ds :identities))))))))))

(deftest distinct-emails-are-distinct-users
  (ts/with-db
    (fn [ds]
      (users/provision! ds hanko-attrs)
      (users/provision! ds {:provider "hanko" :provider-subject "subj-2"
                            :email "other@example.com"})
      (is (= 2 (crud/count-rows ds :users))))))
