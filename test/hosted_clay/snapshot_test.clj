(ns hosted-clay.snapshot-test
  "Snapshot capture is stubbed at the exec edge (with-redefs) — these tests are
   about our storage, upsert, staleness, and best-effort failure handling, not
   the sprite wire. The capture reads two files (the .clj source and the
   rendered .html), so the stub answers by path."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hosted-clay.db.crud :as crud]
            [hosted-clay.notebooks :as notebooks]
            [hosted-clay.snapshot :as snapshot]
            [hosted-clay.sprites.client :as sprites]
            [hosted-clay.sprites.exec :as exec]
            [hosted-clay.sprites.provision :as provision]
            [hosted-clay.test-system :as ts]
            [hosted-clay.users :as users]))

(def client {:api-url "https://api.example.invalid" :token {:value "t"}})

(defn- cat-stub
  "Stub exec/exec! as a `cat`: source for the .clj path, html for the .html."
  [source html]
  (fn [_ _ cmd & _]
    {:exit 0 :err ""
     :out  (if (str/ends-with? (last cmd) ".html") html source)}))

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

(deftest capture!-stores-source-and-html-and-upserts
  (with-notebook
    (fn [ds nb]
      (with-redefs [exec/exec! (cat-stub "(ns notebook)\n(+ 1 1)" "<html>RENDER</html>")]
        (testing "a capture stores both the source and the rendered html"
          (is (true? (snapshot/capture! ds client nb)))
          (let [snap (snapshot/for-notebook ds (:notebooks/id nb))]
            (is (= "(ns notebook)\n(+ 1 1)" (:notebook-snapshots/source snap)))
            (is (= "<html>RENDER</html>" (:notebook-snapshots/html snap)))
            (is (some? (:notebook-snapshots/captured-at snap)))))
        (testing "a second capture upserts the same row rather than duplicating"
          (snapshot/capture! ds client nb)
          (is (= 1 (crud/count-rows ds :notebook-snapshots))))))))

(deftest capture!-keeps-prior-value-when-a-file-is-missing
  (with-notebook
    (fn [ds nb]
      (with-redefs [exec/exec! (cat-stub "source-v1" "html-v1")]
        (snapshot/capture! ds client nb))
      (testing "an html read that fails leaves the previously stored html intact"
        (with-redefs [exec/exec! (fn [_ _ cmd & _]
                                   (if (str/ends-with? (last cmd) ".html")
                                     {:exit 1 :out "" :err "no such file"}
                                     {:exit 0 :out "source-v2" :err ""}))]
          (snapshot/capture! ds client nb))
        (let [snap (snapshot/for-notebook ds (:notebooks/id nb))]
          (is (= "source-v2" (:notebook-snapshots/source snap)) "source refreshed")
          (is (= "html-v1" (:notebook-snapshots/html snap)) "html left as the prior value"))))))

(deftest capture!-is-best-effort
  (with-notebook
    (fn [ds nb]
      (testing "both reads failing stores nothing and returns nil"
        (with-redefs [exec/exec! (fn [_ _ _ & _] {:exit 1 :out "" :err "cat: no such file"})]
          (is (nil? (snapshot/capture! ds client nb)))
          (is (nil? (snapshot/for-notebook ds (:notebooks/id nb))))))
      (testing "a thrown transport error is swallowed — it must never break the census"
        (with-redefs [exec/exec! (fn [_ _ _ & _] (throw (ex-info "socket closed" {})))]
          (is (nil? (snapshot/capture! ds client nb))))))))

(deftest stale?-tracks-the-refresh-window
  (with-notebook
    (fn [ds nb]
      (is (snapshot/stale? nil 15) "a missing snapshot is always stale")
      (with-redefs [exec/exec! (cat-stub "code" "<html>r</html>")]
        (run! deref (snapshot/refresh-awake! ds client [nb] 15)))
      (let [snap (snapshot/for-notebook ds (:notebooks/id nb))]
        (is (= "code" (:notebook-snapshots/source snap)) "refresh-awake! captured the stale notebook")
        (is (= "<html>r</html>" (:notebook-snapshots/html snap)))
        (is (not (snapshot/stale? snap 15)) "a fresh capture isn't stale")
        (is (snapshot/stale? snap 0) "a 0-minute window makes everything stale")))))

(deftest refresh-awake!-skips-fresh-notebooks
  (with-notebook
    (fn [ds nb]
      (let [captures (atom 0)]
        (with-redefs [exec/exec! (fn [_ _ cmd & _]
                                   (when (str/ends-with? (last cmd) ".clj") (swap! captures inc))
                                   {:exit 0 :out "x" :err ""})]
          (run! deref (snapshot/refresh-awake! ds client [nb] 15))   ; first: captures
          (run! deref (snapshot/refresh-awake! ds client [nb] 15)))  ; second: fresh, skipped
        (is (= 1 @captures) "the second pass found a fresh snapshot and didn't re-capture")))))
