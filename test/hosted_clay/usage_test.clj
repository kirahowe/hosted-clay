(ns hosted-clay.usage-test
  (:require [clojure.test :refer [deftest is testing]]
            [hosted-clay.db.crud :as crud]
            [hosted-clay.notebooks :as notebooks]
            [hosted-clay.sprites.client :as sprites]
            [hosted-clay.sprites.provision :as provision]
            [hosted-clay.test-system :as ts]
            [hosted-clay.usage :as usage]
            [hosted-clay.users :as users]))

(def client {:api-url "https://api.example.invalid" :token {:value "t"}})

(deftest current-month-format
  (is (re-matches #"\d{4}-\d{2}" (usage/current-month))))

(deftest over-limit?-respects-month-and-disabled
  (let [month (usage/current-month)]
    (testing "under the limit this month"
      (is (not (usage/notebook-over-limit?
                {:notebooks/usage-month month :notebooks/awake-seconds (* 10 3600)} 50))))
    (testing "at/over the limit this month"
      (is (usage/notebook-over-limit?
           {:notebooks/usage-month month :notebooks/awake-seconds (* 50 3600)} 50)))
    (testing "a total from a previous month doesn't count — the budget reset"
      (is (not (usage/notebook-over-limit?
                {:notebooks/usage-month "2000-01" :notebooks/awake-seconds (* 999 3600)} 50))))
    (testing "nil/0 limit disables the cap"
      (let [maxed {:notebooks/usage-month month :notebooks/awake-seconds (* 999 3600)}]
        (is (not (usage/notebook-over-limit? maxed nil)))
        (is (not (usage/notebook-over-limit? maxed 0)))))))

(deftest record!-accrues-rolls-over-and-warns
  (ts/with-db
    (fn [ds]
      (with-redefs [sprites/create-sprite! (fn [_ name] {:url (str "https://" name ".sprites.test")})
                    sprites/delete-sprite! (fn [_ _])
                    provision/provision!   (fn [_ _])]
        (let [user  (users/provision! ds {:provider "hanko" :provider-subject "s"
                                          :email "kira@example.com"})
              nb    (notebooks/create! ds client {:max-sprites 10} (:users/id user) "T")
              id    (:notebooks/id nb)
              sent  (atom [])
              email #(swap! sent conj %)
              ;; warn at 1h, hard limit 2h, one census sample = 1h, for clean math
              opts  {:interval-seconds 3600 :warn-hours 1 :limit-hours 2 :base-url "https://clay.test"}
              recur-once! #(usage/record! ds email [(notebooks/by-id ds id)] opts)]

          (testing "a fresh notebook accrues into the current month"
            (recur-once!)
            (let [row (notebooks/by-id ds id)]
              (is (= (usage/current-month) (:notebooks/usage-month row)))
              (is (= 3600 (:notebooks/awake-seconds row)))
              (is (= 1 (count @sent)) "crossing the warn threshold sends one warning")
              (is (= "kira@example.com" (:to (first @sent))))
              (is (some? (:notebooks/usage-warned-at row)))))

          (testing "further samples accumulate without re-warning"
            (recur-once!)
            (let [row (notebooks/by-id ds id)]
              (is (= 7200 (:notebooks/awake-seconds row)))
              (is (= 1 (count @sent)) "no second warning in the same month")
              (is (usage/notebook-over-limit? row 2) "now at the hard limit")))

          (testing "a new month rolls the total over and re-arms the warning"
            (crud/update! ds :notebooks id {:usage-month "2000-01"
                                            :awake-seconds (* 999 3600)
                                            :usage-warned-at (crud/now)})
            (recur-once!)
            (let [row (notebooks/by-id ds id)]
              (is (= (usage/current-month) (:notebooks/usage-month row)))
              (is (= 3600 (:notebooks/awake-seconds row)) "reset to this sample, not 999h+")
              (is (= 2 (count @sent)) "warning re-armed for the new month"))))))))
