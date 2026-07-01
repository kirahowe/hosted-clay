(ns hosted-clay.lifecycle-test
  (:require [clojure.test :refer [deftest is testing]]
            [hosted-clay.db.crud :as crud]
            [hosted-clay.lifecycle :as lifecycle]
            [hosted-clay.notebooks :as notebooks]
            [hosted-clay.sprites.client :as sprites]
            [hosted-clay.sprites.provision :as provision]
            [hosted-clay.test-system :as ts]
            [hosted-clay.users :as users])
  (:import (java.time Duration Instant)))

(def client {:api-url "https://api.example.invalid" :token {:value "t"}})

(defn- row [days-idle warned?]
  {:notebooks/id               (str (random-uuid))
   :notebooks/last-accessed-at (str (.minus (Instant/now) (Duration/ofDays days-idle)))
   :notebooks/warned-at        (when warned? (crud/now))})

(deftest warn-and-delete-selection
  (let [now    (Instant/now)
        fresh  (row 1 false)
        idle   (row 24 false)
        warned (row 31 true)]
    (testing "only unwarned notebooks past the warning threshold get warned"
      (is (= [idle] (lifecycle/to-warn [fresh idle warned] now 23))))
    (testing "only warned notebooks past the deletion threshold get deleted"
      (is (= [warned] (lifecycle/to-delete [fresh idle warned] now 30))))
    (testing "an unwarned notebook past 30 days is warned first, never deleted cold"
      (let [unwarned-31 (row 31 false)]
        (is (= [] (lifecycle/to-delete [unwarned-31] now 30)))
        (is (= [unwarned-31] (lifecycle/to-warn [unwarned-31] now 23)))))))

(deftest sweep-warns-then-deletes
  (ts/with-db
    (fn [ds]
      (with-redefs [sprites/create-sprite! (fn [_ name] {:url (str "https://" name ".sprites.test")})
                    sprites/delete-sprite! (fn [_ _])
                    provision/provision!   (fn [_ _])]
        (let [user  (users/provision! ds {:provider "hanko" :provider-subject "s"
                                          :email "kira@example.com"})
              nb    (notebooks/create! ds client {:max-sprites 10} (:users/id user) "T")
              sent  (atom [])
              email #(swap! sent conj %)
              opts  {:warn-after-days 23 :delete-after-days 30 :base-url "https://clay.test"}]

          (testing "an idle notebook is warned once"
            (crud/update! ds :notebooks (:notebooks/id nb)
                          {:last-accessed-at (str (.minus (Instant/now) (Duration/ofDays 24)))})
            (lifecycle/sweep! ds client email opts)
            (is (= 1 (count @sent)))
            (is (= "kira@example.com" (:to (first @sent))))
            (is (some? (:notebooks/warned-at (notebooks/by-id ds (:notebooks/id nb)))))

            (lifecycle/sweep! ds client email opts)
            (is (= 1 (count @sent)) "no second warning"))

          (testing "a warned notebook past the deletion threshold is deleted"
            (crud/update! ds :notebooks (:notebooks/id nb)
                          {:last-accessed-at (str (.minus (Instant/now) (Duration/ofDays 31)))})
            (lifecycle/sweep! ds client email opts)
            (is (nil? (notebooks/by-id ds (:notebooks/id nb))))))))))

(deftest log-only-email-never-marks-warned-or-deletes
  ;; The prod failsafe: with no RESEND_API_KEY the email component returns
  ;; falsey (nothing was delivered), so warned-at must never be set — and
  ;; since deletion requires warned-at, an idle notebook survives
  ;; indefinitely rather than being deleted unannounced.
  (ts/with-db
    (fn [ds]
      (with-redefs [sprites/create-sprite! (fn [_ name] {:url (str "https://" name ".sprites.test")})
                    sprites/delete-sprite! (fn [_ _])
                    provision/provision!   (fn [_ _])]
        (let [user     (users/provision! ds {:provider "hanko" :provider-subject "s"
                                             :email "kira@example.com"})
              nb       (notebooks/create! ds client {:max-sprites 10} (:users/id user) "T")
              log-only (constantly false)
              opts     {:warn-after-days 23 :delete-after-days 30 :base-url "https://clay.test"}]
          (crud/update! ds :notebooks (:notebooks/id nb)
                        {:last-accessed-at (str (.minus (Instant/now) (Duration/ofDays 99)))})
          (lifecycle/sweep! ds client log-only opts)
          (is (nil? (:notebooks/warned-at (notebooks/by-id ds (:notebooks/id nb))))
              "an undelivered warning leaves the notebook un-warned")
          (lifecycle/sweep! ds client log-only opts)
          (is (some? (notebooks/by-id ds (:notebooks/id nb)))
              "and an un-warned notebook is never deleted, however idle"))))))

(deftest nil-thresholds-disable-the-policy
  (ts/with-db
    (fn [ds]
      (with-redefs [sprites/create-sprite! (fn [_ name] {:url (str "https://" name ".sprites.test")})
                    sprites/delete-sprite! (fn [_ _])
                    provision/provision!   (fn [_ _])]
        (let [user (users/provision! ds {:provider "hanko" :provider-subject "s"
                                         :email "kira@example.com"})
              nb   (notebooks/create! ds client {:max-sprites 10} (:users/id user) "T")
              sent (atom [])]
          (crud/update! ds :notebooks (:notebooks/id nb)
                        {:last-accessed-at (str (.minus (Instant/now) (Duration/ofDays 99)))
                         :warned-at        (crud/now)})
          (lifecycle/sweep! ds client #(swap! sent conj %)
                            {:warn-after-days nil :delete-after-days nil :base-url "https://clay.test"})
          (is (empty? @sent) "nil warn threshold sends nothing")
          (is (some? (notebooks/by-id ds (:notebooks/id nb)))
              "nil delete threshold deletes nothing"))))))
