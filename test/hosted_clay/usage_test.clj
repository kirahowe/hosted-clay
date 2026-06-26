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

(defn- with-stubbed-sprites [f]
  (ts/with-db
    (fn [ds]
      (with-redefs [sprites/create-sprite! (fn [_ name] {:url (str "https://" name ".sprites.test")})
                    sprites/delete-sprite! (fn [_ _])
                    provision/provision!   (fn [_ _])]
        (f ds)))))

(defn- provision-user! [ds email]
  (users/provision! ds {:provider "hanko" :provider-subject email :email email}))

(defn- set-usage!
  "Upsert a user's accrued seconds for `month` (create or replace)."
  [ds user-id month seconds]
  (if (crud/find-1 ds :user-usage {:user-id user-id :usage-month month})
    (crud/update-where! ds :user-usage
                        [:and [:= :user-id user-id] [:= :usage-month month]]
                        {:awake-seconds seconds})
    (crud/create! ds :user-usage {:user-id user-id :usage-month month :awake-seconds seconds})))

(deftest current-month-format
  (is (re-matches #"\d{4}-\d{2}" (usage/current-month))))

(deftest user-over-limit?-respects-month-and-disabled
  (with-stubbed-sprites
    (fn [ds]
      (let [month (usage/current-month)
            uid   (:users/id (provision-user! ds "k@example.com"))]
        (testing "no usage row yet → under the limit"
          (is (not (usage/user-over-limit? ds uid 50))))
        (testing "under the limit this month"
          (set-usage! ds uid month (* 10 3600))
          (is (not (usage/user-over-limit? ds uid 50))))
        (testing "at/over the limit this month"
          (set-usage! ds uid month (* 50 3600))
          (is (usage/user-over-limit? ds uid 50)))
        (testing "nil/0 limit disables the cap even when maxed out"
          (is (not (usage/user-over-limit? ds uid nil)))
          (is (not (usage/user-over-limit? ds uid 0))))
        (testing "a total from a previous month doesn't count — the budget reset"
          (let [stale (:users/id (provision-user! ds "stale@example.com"))]
            (set-usage! ds stale "2000-01" (* 999 3600))
            (is (not (usage/user-over-limit? ds stale 50)))))))))

(deftest record!-accrues-and-warns
  (with-stubbed-sprites
    (fn [ds]
      (let [user (provision-user! ds "kira@example.com")
            uid  (:users/id user)
            id   (:notebooks/id (notebooks/create! ds client {:max-sprites 10} uid "T"))
            sent (atom [])
            email #(swap! sent conj %)
            ;; warn at 1h, hard limit 2h, one census sample = 1h, for clean math
            opts  {:interval-seconds 3600 :warn-hours 1 :limit-hours 2 :base-url "https://clay.test"}
            row   #(usage/usage-row ds uid)
            recur-once! #(usage/record! ds email [(notebooks/by-id ds id)] opts)]

        (testing "a fresh user accrues into the current month and warns once"
          (recur-once!)
          (is (= (usage/current-month) (:user-usage/usage-month (row))))
          (is (= 3600 (:user-usage/awake-seconds (row))))
          (is (= 1 (count @sent)) "crossing the warn threshold sends one warning")
          (is (= "kira@example.com" (:to (first @sent))))
          (is (some? (:user-usage/warned-at (row)))))

        (testing "further samples accumulate without re-warning"
          (recur-once!)
          (is (= 7200 (:user-usage/awake-seconds (row))))
          (is (= 1 (count @sent)) "no second warning in the same month")
          (is (usage/user-over-limit? ds uid 2) "now at the hard limit"))

        (testing "a new month starts a fresh bucket and re-arms the warning"
          ;; Simulate the calendar moving on: relabel this month's row to the past
          ;; so the current-month lookup finds nothing and accrual starts over.
          (crud/update-where! ds :user-usage
                              [:and [:= :user-id uid] [:= :usage-month (usage/current-month)]]
                              {:usage-month "2000-01"})
          (recur-once!)
          (is (= (usage/current-month) (:user-usage/usage-month (row))))
          (is (= 3600 (:user-usage/awake-seconds (row))) "reset to this sample, not 999h+")
          (is (= 2 (count @sent)) "warning re-armed for the new month"))))))

(deftest usage-survives-deleting-and-recreating-the-notebook
  ;; The whole point of keying usage on the user: a user must not be able to zero
  ;; out their monthly budget by deleting their notebook and creating a fresh one.
  (with-stubbed-sprites
    (fn [ds]
      (let [user (provision-user! ds "kira@example.com")
            uid  (:users/id user)
            nb   (notebooks/create! ds client {:max-sprites 10} uid "T")
            opts {:interval-seconds 3600 :warn-hours nil :limit-hours 2 :base-url "https://clay.test"}]
        (usage/record! ds (fn [_]) [nb] opts)
        (usage/record! ds (fn [_]) [(notebooks/for-user ds uid)] opts)
        (is (= 7200 (usage/awake-seconds-this-month ds uid)))
        (is (usage/user-over-limit? ds uid 2) "over the limit before deletion")

        (testing "deleting the notebook leaves the user's usage intact"
          (notebooks/delete! ds client (notebooks/for-user ds uid))
          (is (nil? (notebooks/for-user ds uid)) "notebook really gone")
          (is (= 7200 (usage/awake-seconds-this-month ds uid))
              "usage is on the user, not the deleted notebook"))

        (testing "a fresh notebook is still over the user's monthly limit"
          (notebooks/create! ds client {:max-sprites 10} uid "T2")
          (is (= 7200 (usage/awake-seconds-this-month ds uid)) "not reset by the new notebook")
          (is (usage/user-over-limit? ds uid 2)))))))
