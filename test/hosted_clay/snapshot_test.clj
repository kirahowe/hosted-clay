(ns hosted-clay.snapshot-test
  "Snapshot capture is stubbed at the exec edge (with-redefs) — these tests are
   about our storage, upsert, staleness, and best-effort failure handling, not
   the sprite wire."
  (:require [clojure.test :refer [deftest is testing]]
            [hosted-clay.db.crud :as crud]
            [hosted-clay.notebooks :as notebooks]
            [hosted-clay.snapshot :as snapshot]
            [hosted-clay.sprites.client :as sprites]
            [hosted-clay.sprites.exec :as exec]
            [hosted-clay.sprites.provision :as provision]
            [hosted-clay.test-system :as ts]
            [hosted-clay.users :as users]))

(def client {:api-url "https://api.example.invalid" :token {:value "t"}})

(defn- with-notebook [f]
  (ts/with-db
    (fn [ds]
      (with-redefs [sprites/create-sprite! (fn [_ name] {:name name :url (str "https://" name ".sprites.test")})
                    sprites/delete-sprite! (fn [_ _])
                    provision/provision!   (fn [_ _])]
        (let [user (users/provision! ds {:provider "hanko" :provider-subject "s"
                                         :email "kira@example.com"})
              nb   (notebooks/create! ds client {:max-sprites 10} (:users/id user) "T")]
          (f ds nb))))))

(deftest capture-source!-stores-and-upserts
  (with-notebook
    (fn [ds nb]
      (with-redefs [exec/exec! (fn [_ _ _ & _] {:exit 0 :out "(ns notebook)\n(+ 1 1)" :err ""})]
        (testing "a capture stores the source and stamps captured-at"
          (is (true? (snapshot/capture-source! ds client nb)))
          (let [snap (snapshot/for-notebook ds (:notebooks/id nb))]
            (is (= "(ns notebook)\n(+ 1 1)" (:notebook-snapshots/source snap)))
            (is (some? (:notebook-snapshots/captured-at snap)))))
        (testing "a second capture upserts the same row rather than duplicating"
          (snapshot/capture-source! ds client nb)
          (is (= 1 (crud/count-rows ds :notebook-snapshots))))))))

(deftest capture-source!-is-best-effort
  (with-notebook
    (fn [ds nb]
      (testing "a non-zero exit stores nothing and returns nil"
        (with-redefs [exec/exec! (fn [_ _ _ & _] {:exit 1 :out "" :err "cat: no such file"})]
          (is (nil? (snapshot/capture-source! ds client nb)))
          (is (nil? (snapshot/for-notebook ds (:notebooks/id nb))))))
      (testing "a thrown transport error is swallowed — it must never break the census"
        (with-redefs [exec/exec! (fn [_ _ _ & _] (throw (ex-info "socket closed" {})))]
          (is (nil? (snapshot/capture-source! ds client nb))))))))

(deftest stale?-tracks-the-refresh-window
  (with-notebook
    (fn [ds nb]
      (is (snapshot/stale? nil 15) "a missing snapshot is always stale")
      (with-redefs [exec/exec! (fn [_ _ _ & _] {:exit 0 :out "code" :err ""})]
        (run! deref (snapshot/refresh-awake! ds client [nb] 15)))
      (let [snap (snapshot/for-notebook ds (:notebooks/id nb))]
        (is (= "code" (:notebook-snapshots/source snap)) "refresh-awake! captured the stale notebook")
        (is (not (snapshot/stale? snap 15)) "a fresh capture isn't stale")
        (is (snapshot/stale? snap 0) "a 0-minute window makes everything stale")))))

(deftest refresh-awake!-skips-fresh-notebooks
  (with-notebook
    (fn [ds nb]
      (let [calls (atom 0)]
        (with-redefs [exec/exec! (fn [_ _ _ & _] (swap! calls inc) {:exit 0 :out "code" :err ""})]
          (run! deref (snapshot/refresh-awake! ds client [nb] 15))   ; first: captures
          (run! deref (snapshot/refresh-awake! ds client [nb] 15)))  ; second: fresh, skipped
        (is (= 1 @calls) "the second pass found a fresh snapshot and didn't re-capture")))))
