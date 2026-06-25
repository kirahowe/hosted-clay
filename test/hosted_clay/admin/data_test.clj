(ns hosted-clay.admin.data-test
  "Reporting math and shape for the admin usage tool. Only requires
   hosted-clay.admin.data — NOT admin.tui — so charm.clj stays off the test
   classpath. Sprite calls are stubbed at the client edge, like the other
   db-backed tests."
  (:require [clojure.test :refer [deftest is testing]]
            [hosted-clay.admin.data :as data]
            [hosted-clay.db.crud :as crud]
            [hosted-clay.notebooks :as notebooks]
            [hosted-clay.sprites.client :as sprites]
            [hosted-clay.sprites.provision :as provision]
            [hosted-clay.test-system :as ts]
            [hosted-clay.usage :as usage]
            [hosted-clay.users :as users]))

(def client {:api-url "https://api.example.invalid" :token {:value "t"}})

(defn- provision-user! [ds email]
  (users/provision! ds {:provider "hanko" :provider-subject email :email email}))

(defn- with-notebook!
  "Provision a user with a notebook, accruing `awake-seconds` into the current
   month. Returns the user row."
  [ds email awake-seconds]
  (let [user (provision-user! ds email)
        nb   (notebooks/create! ds client {:max-sprites 10} (:users/id user) "T")]
    ;; The empty test pool yields a 'provisioning' row; mark it ready (and set
    ;; usage) so the report surfaces a realistic, deterministic status.
    (crud/update! ds :notebooks (:notebooks/id nb)
                  {:status "ready"
                   :usage-month (usage/current-month) :awake-seconds awake-seconds})
    user))

(defn- with-stubbed-sprites [f]
  (ts/with-db
    (fn [ds]
      (with-redefs [sprites/create-sprite! (fn [_ name] {:name name :url (str "https://" name ".sprites.test")})
                    sprites/delete-sprite! (fn [_ _])
                    provision/provision!   (fn [_ _])]
        (f ds)))))

(defn- row-for [report email]
  (->> (:rows report) (filter #(= email (:email %))) first))

(deftest spend-arithmetic
  (testing "the documented worked example: 10 awake-hours @ 2 cpu / 4 gb"
    (is (= 3.15 (data/spend 10 2 4))))           ; 10 * (0.07*2 + 0.04375*4) = 10 * 0.315
  (testing "the hourly rate is published cpu + ram rates"
    (is (= 0.315 (data/hourly-rate 2 4))))
  (testing "default 1cpu/1gb"
    (is (= (+ data/cpu-hour-rate data/gb-hour-rate)
           (data/hourly-rate data/default-cpus data/default-gb-ram)))))

(deftest report-notebook-count-and-spend
  (with-stubbed-sprites
    (fn [ds]
      ;; one user with 10 awake-hours of notebook, one with no notebook
      (with-notebook! ds "has@example.com" (* 10 3600))
      (provision-user! ds "none@example.com")
      (let [report (data/report ds {:cpus 2 :gb-ram 4})
            has    (row-for report "has@example.com")
            none   (row-for report "none@example.com")]
        (testing "the month is this month"
          (is (= (usage/current-month) (:month report)))
          (is (= 2 (:cpus report)))
          (is (= 4 (:gb-ram report))))
        (testing "a user with a notebook reports notebook-count 1 + its status"
          (is (= 1 (:notebook-count has)))
          (is (= "ready" (:status has))))
        (testing "month-scoped awake-hours read off the notebook"
          (is (== 10.0 (:awake-hours has))))
        (testing "spend uses the assumed size: 10 * (0.07*2 + 0.04375*4) = 3.15"
          (is (= 3.15 (:spend has))))
        (testing "a user with no notebook reports 0 across the board"
          (is (= 0 (:notebook-count none)))
          (is (nil? (:status none)))
          (is (== 0.0 (:awake-hours none)))
          (is (== 0.0 (:spend none))))))))

(deftest report-ignores-previous-month-usage
  (with-stubbed-sprites
    (fn [ds]
      (let [user (provision-user! ds "stale@example.com")
            nb   (notebooks/create! ds client {:max-sprites 10} (:users/id user) "T")]
        (crud/update! ds :notebooks (:notebooks/id nb)
                      {:usage-month "2000-01" :awake-seconds (* 999 3600)})
        (let [row (row-for (data/report ds {}) "stale@example.com")]
          (testing "a total from a previous month doesn't count toward this month"
            (is (== 0.0 (:awake-hours row)))
            (is (== 0.0 (:spend row)))))))))

(deftest report-totals-and-sort
  (with-stubbed-sprites
    (fn [ds]
      (with-notebook! ds "big@example.com" (* 10 3600))   ; 10h
      (with-notebook! ds "small@example.com" (* 2 3600))  ; 2h
      (provision-user! ds "idle@example.com")             ; no notebook
      (let [report (data/report ds {:cpus 1 :gb-ram 1})
            totals (:totals report)
            rate   (data/hourly-rate 1 1)]
        (testing "totals sum users, notebooks, hours, and spend"
          (is (= 3 (:users totals)))
          (is (= 2 (:notebooks totals)))
          (is (== 12.0 (:awake-hours totals)))
          (is (== (* 12.0 rate) (:spend totals))))
        (testing "rows are sorted by spend descending"
          (is (= ["big@example.com" "small@example.com" "idle@example.com"]
                 (map :email (:rows report)))))))))

;; db-path/open-datasource read the dev/prod overlays via io/resource, which
;; are only on the :admin classpath (not :test), so they're not exercised here
;; — the :admin alias provides them and the --plain path verifies them
;; end-to-end against a real DB.
