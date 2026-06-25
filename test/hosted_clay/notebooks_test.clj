(ns hosted-clay.notebooks-test
  "Notebook domain tests. The Sprites API edge is stubbed with
   with-redefs — these tests are about our orchestration (pool claim,
   one-per-user, lifecycle bookkeeping), not the wire."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [hosted-clay.db.crud :as crud]
            [hosted-clay.notebooks :as notebooks]
            [hosted-clay.pool :as pool]
            [hosted-clay.proxy :as proxy]
            [hosted-clay.snapshot :as snapshot]
            [hosted-clay.sprites.client :as sprites]
            [hosted-clay.sprites.exec :as exec]
            [hosted-clay.sprites.provision :as provision]
            [hosted-clay.test-system :as ts]
            [hosted-clay.usage :as usage]
            [hosted-clay.users :as users])
  (:import (java.time Duration Instant)))

;; Load the handlers ns for its ig/init-key defmethods (the proxy handler under
;; test); required for the side effect, so it's not an aliased ns require.
(require 'hosted-clay.handlers.notebooks)

(def client {:api-url "https://api.example.invalid" :token {:value "t"}})
(def limits {:max-sprites 10})

(defn- stub-sprites [f]
  (let [deleted (atom [])]
    (with-redefs [sprites/create-sprite! (fn [_ name] {:name name :url (str "https://" name ".sprites.test")})
                  sprites/delete-sprite! (fn [_ name] (swap! deleted conj name))
                  provision/provision!   (fn [_ _])]
      (f deleted))))

(defn- make-user [ds]
  (users/provision! ds {:provider "hanko" :provider-subject (str (random-uuid))
                        :email (str (random-uuid) "@example.com")}))

(deftest create-from-empty-pool
  (ts/with-db
    (fn [ds]
      (stub-sprites
       (fn [_]
         (let [user (make-user ds)
               nb   (notebooks/create! ds client limits (:users/id user) "My notebook")]
           (is (= "My notebook" (:notebooks/title nb)))
           (is (seq (:notebooks/share-token nb)))
           (is (= nb (notebooks/by-share-token ds (:notebooks/share-token nb))))

           (testing "second create reports the existing notebook"
             (is (= ::notebooks/already-exists
                    (notebooks/create! ds client limits (:users/id user) "Another"))))))))))

(deftest create-claims-warm-sprite
  (ts/with-db
    (fn [ds]
      (stub-sprites
       (fn [_]
         (crud/create! ds :sprite-pool {:sprite-name "nb-warm"
                                        :sprite-url  "https://nb-warm.sprites.test"
                                        :state       "ready"})
         (let [user (make-user ds)
               nb   (notebooks/create! ds client limits (:users/id user) "T")]
           (is (= "nb-warm" (:notebooks/sprite-name nb)))
           (is (zero? (crud/count-rows ds :sprite-pool)) "pool sprite was claimed")))))))

(deftest create-respects-budget-cap
  (ts/with-db
    (fn [ds]
      (stub-sprites
       (fn [_]
         (let [user (make-user ds)]
           (is (thrown-with-msg? clojure.lang.ExceptionInfo #"budget"
                                 (notebooks/create! ds client {:max-sprites 0}
                                                    (:users/id user) "T")))))))))

(deftest delete-removes-sprite-then-row
  (ts/with-db
    (fn [ds]
      (stub-sprites
       (fn [deleted]
         (let [user (make-user ds)
               nb   (notebooks/create! ds client limits (:users/id user) "T")]
           (notebooks/delete! ds client nb)
           (is (= [(:notebooks/sprite-name nb)] @deleted))
           (is (nil? (notebooks/by-id ds (:notebooks/id nb))))))))))

(deftest touch-throttles-and-clears-warning
  (ts/with-db
    (fn [ds]
      (stub-sprites
       (fn [_]
         (let [user (make-user ds)
               nb   (notebooks/create! ds client limits (:users/id user) "T")]
           (testing "a just-created notebook isn't re-touched"
             (is (nil? (notebooks/touch! ds nb))))

           (testing "a stale notebook is touched and its warning cleared"
             (let [old (str (.minus (Instant/now) (Duration/ofDays 25)))]
               (crud/update! ds :notebooks (:notebooks/id nb)
                             {:last-accessed-at old :warned-at (crud/now)})
               (let [touched (notebooks/touch! ds (notebooks/by-id ds (:notebooks/id nb)))]
                 (is (some? touched))
                 (is (nil? (:notebooks/warned-at touched))))))))))))

(deftest provisioning-lifecycle
  (ts/with-db
    (fn [ds]
      (stub-sprites
       (fn [_]
         (let [user (make-user ds)
               nb   (notebooks/create! ds client limits (:users/id user) "T")]
           (testing "an empty pool yields a provisioning notebook with no sprite yet"
             (is (= "provisioning" (:notebooks/status nb)))
             (is (= "" (:notebooks/sprite-url nb))))
           (testing "finishing provisioning makes it ready with a sprite url"
             (notebooks/finish-provisioning! ds client nb)
             (let [done (notebooks/by-id ds (:notebooks/id nb))]
               (is (= "ready" (:notebooks/status done)))
               (is (re-find #"sprites.test" (:notebooks/sprite-url done)))))))))))

(deftest provisioning-failure-then-retry
  (ts/with-db
    (fn [ds]
      (let [deleted (atom [])
            fail?   (atom true)]
        (with-redefs [sprites/create-sprite! (fn [_ name] {:name name :url (str "https://" name ".sprites.test")})
                      sprites/delete-sprite! (fn [_ name] (swap! deleted conj name))
                      provision/provision!   (fn [_ _] (when @fail? (throw (ex-info "boom" {}))))]
          (let [user (make-user ds)
                nb   (notebooks/create! ds client limits (:users/id user) "T")]
            (notebooks/finish-provisioning! ds client nb)
            (testing "a failed build marks the notebook failed and frees the sprite"
              (let [failed (notebooks/by-id ds (:notebooks/id nb))]
                (is (= "failed" (:notebooks/status failed)))
                (is (= [(:notebooks/sprite-name nb)] @deleted))
                (testing "retry then a successful build makes it ready"
                  (reset! fail? false)
                  (let [reset (notebooks/retry-provisioning! ds failed)]
                    (is (= "provisioning" (:notebooks/status reset)))
                    (notebooks/finish-provisioning! ds client reset)
                    (is (= "ready" (:notebooks/status (notebooks/by-id ds (:notebooks/id nb)))))))))))))))

(deftest pool-claim-is-exclusive
  (ts/with-db
    (fn [ds]
      (crud/create! ds :sprite-pool {:sprite-name "nb-1"
                                     :sprite-url "https://nb-1.sprites.test"
                                     :state "ready"})
      (is (= "nb-1" (:sprite-name (pool/claim! ds))))
      (is (nil? (pool/claim! ds))))))

(deftest open-proxy-blocks-over-limit-notebook
  ;; The whole point of the usage budget: an over-limit notebook's proxy refuses
  ;; to forward, so no request reaches (or wakes) the sprite. Stub the forward
  ;; and touch! seams so we can assert they're skipped, not exercised.
  (ts/with-db
    (fn [ds]
      (stub-sprites
       (fn [_]
         (let [user      (make-user ds)
               nb        (notebooks/create! ds client limits (:users/id user) "T")
               id        (:notebooks/id nb)
               forwarded (atom 0)
               touched   (atom 0)
               handler   (ig/init-key :hosted-clay.handlers.notebooks/open
                                      {:datasource ds :sprites-client client :usage-limit-hours 50})
               req       {:user-id     (:users/id user)
                          :path-params {:id id :path ""}
                          :headers     {"accept" "text/html"}}]
           ;; Put it in the state that actually reaches the proxy: ready, with a
           ;; sprite url (an empty-pool create starts out "provisioning").
           (crud/update! ds :notebooks id {:status "ready" :sprite-url "https://nb.sprites.test"})
           (with-redefs [proxy/forward    (fn [& _] (swap! forwarded inc) {:status 200})
                         notebooks/touch! (fn [& _] (swap! touched inc) nil)]
             (testing "under the limit, the request is forwarded and the sprite touched"
               (crud/update! ds :notebooks id {:usage-month   (usage/current-month)
                                               :awake-seconds (* 10 3600)})
               (let [resp (handler req)]
                 (is (= 200 (:status resp)))
                 (is (= 1 @forwarded))
                 (is (= 1 @touched))))
             (testing "over the limit, refused with 429 — never forwarded, never touched"
               (crud/update! ds :notebooks id {:usage-month   (usage/current-month)
                                               :awake-seconds (* 50 3600)})
               (let [resp (handler req)]
                 (is (= 429 (:status resp)))
                 (is (= 1 @forwarded) "not forwarded again")
                 (is (= 1 @touched) "not touched again"))))))))))

(deftest source-handler-serves-stored-source-without-a-usage-check
  (ts/with-db
    (fn [ds]
      (stub-sprites
       (fn [_]
         (let [user    (make-user ds)
               nb      (notebooks/create! ds client limits (:users/id user) "T")
               id      (:notebooks/id nb)
               handler (ig/init-key :hosted-clay.handlers.notebooks/source {:datasource ds})
               req     {:user-id (:users/id user) :path-params {:id id}}]
           (testing "before any snapshot, a friendly pending message"
             (let [resp (handler req)]
               (is (= 200 (:status resp)))
               (is (str/includes? (:body resp) "captured a snapshot of this notebook yet"))))
           (testing "after a snapshot, the stored source is shown — even over the limit"
             (with-redefs [exec/exec! (fn [_ _ _ & _] {:exit 0 :out "(ns notebook)\n42" :err ""})]
               (snapshot/capture! ds client nb))
             (crud/update! ds :notebooks id {:usage-month   (usage/current-month)
                                             :awake-seconds (* 99 3600)})
             (let [resp (handler req)]
               (is (str/includes? (:body resp) "(ns notebook)"))))
           (testing "a notebook you don't own is a 404, not a peek at someone's code"
             (let [other (make-user ds)
                   resp  (handler {:user-id (:users/id other) :path-params {:id id}})]
               (is (= 404 (:status resp)))))))))))
