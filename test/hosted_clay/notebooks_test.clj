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

(deftest reconcile-unsticks-stranded-provisioning
  ;; A notebook whose build thread died with a killed/crashed process is left
  ;; in 'provisioning' forever; startup reconciliation resets it to 'failed' so
  ;; the owner's Retry path can recover it.
  (ts/with-db
    (fn [ds]
      (stub-sprites
       (fn [_]
         (let [user    (make-user ds)
               ;; create! leaves a 'provisioning' row; the build never runs
               ;; here, standing in for a process killed mid-provision.
               stranded (notebooks/create! ds client limits (:users/id user) "T")
               other    (make-user ds)
               ready    (notebooks/create! ds client limits (:users/id other) "R")]
           (crud/update! ds :notebooks (:notebooks/id ready) {:status "ready"})
           (let [reset (notebooks/reconcile-provisioning! ds)]
             (testing "it resets exactly the stranded provisioning notebook"
               (is (= [(:notebooks/id stranded)] (map :notebooks/id reset)))
               (is (= "failed" (:notebooks/status (notebooks/by-id ds (:notebooks/id stranded))))))
             (testing "a ready notebook is left untouched"
               (is (= "ready" (:notebooks/status (notebooks/by-id ds (:notebooks/id ready)))))))
           (testing "a second pass is a no-op once nothing is provisioning"
             (is (empty? (notebooks/reconcile-provisioning! ds))))))))))

(deftest pool-claim-is-exclusive
  (ts/with-db
    (fn [ds]
      (crud/create! ds :sprite-pool {:sprite-name "nb-1"
                                     :sprite-url "https://nb-1.sprites.test"
                                     :state "ready"})
      (is (= "nb-1" (:sprite-name (pool/claim! ds))))
      (is (nil? (pool/claim! ds))))))

(deftest view-proxy-blocks-over-limit-notebook
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
               handler   (ig/init-key :hosted-clay.handlers.notebooks/view
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
               (crud/create! ds :user-usage {:user-id     (:users/id user)
                                             :usage-month (usage/current-month)
                                             :awake-seconds (* 10 3600)})
               (let [resp (handler req)]
                 (is (= 200 (:status resp)))
                 (is (= 1 @forwarded))
                 (is (= 1 @touched))))
             (testing "over the limit, refused with 429 — never forwarded, never touched"
               (crud/update-where! ds :user-usage
                                   [:and [:= :user-id (:users/id user)]
                                    [:= :usage-month (usage/current-month)]]
                                   {:awake-seconds (* 50 3600)})
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
             (crud/create! ds :user-usage {:user-id     (:users/id user)
                                           :usage-month (usage/current-month)
                                           :awake-seconds (* 99 3600)})
             (let [resp (handler req)]
               (is (str/includes? (:body resp) "(ns notebook)"))))
           (testing "a notebook you don't own is a 404, not a peek at someone's code"
             (let [other (make-user ds)
                   resp  (handler {:user-id (:users/id other) :path-params {:id id}})]
               (is (= 404 (:status resp)))))))))))

(deftest suspend!-and-resume!-toggle-the-flag
  (ts/with-db
    (fn [ds]
      (stub-sprites
       (fn [_]
         (let [user (make-user ds)
               nb   (notebooks/create! ds client limits (:users/id user) "T")
               id   (:notebooks/id nb)]
           (is (not (notebooks/suspended? nb)))
           (notebooks/suspend! ds nb)
           (is (notebooks/suspended? (notebooks/by-id ds id)))
           (testing "resume clears the flag and bumps last-accessed (off the delete clock)"
             (let [before (:notebooks/last-accessed-at (notebooks/by-id ds id))]
               (notebooks/resume! ds (notebooks/by-id ds id))
               (let [row (notebooks/by-id ds id)]
                 (is (not (notebooks/suspended? row)))
                 (is (>= (compare (:notebooks/last-accessed-at row) before) 0)))))))))))

(deftest suspend-resume-handlers-gate-ownership-and-redirect-safely
  (ts/with-db
    (fn [ds]
      (stub-sprites
       (fn [_]
         (let [user    (make-user ds)
               nb      (notebooks/create! ds client limits (:users/id user) "T")
               id      (:notebooks/id nb)
               suspend (ig/init-key :hosted-clay.handlers.notebooks/suspend {:datasource ds})
               resume  (ig/init-key :hosted-clay.handlers.notebooks/resume {:datasource ds})
               req     (fn [uid return] {:user-id uid :path-params {:id id} :params {"return" return}})
               nb-now  #(notebooks/by-id ds id)]
           (testing "suspending a not-yet-ready notebook is a no-op (no sprite to suspend)"
             (suspend (req (:users/id user) "/dashboard"))
             (is (not (notebooks/suspended? (nb-now)))))
           (crud/update! ds :notebooks id {:status "ready"})
           (testing "owner suspend sets the flag and redirects to the safe return"
             (let [resp (suspend (req (:users/id user) "/dashboard"))]
               (is (= 303 (:status resp)))
               (is (= "/dashboard" (get-in resp [:headers "location"])))
               (is (notebooks/suspended? (nb-now)))))
           (testing "tricky returns are rejected — resume falls back to the workspace"
             (doseq [bad ["//evil.example.com" "/\\evil.example.com" "/ spaced"]]
               (notebooks/suspend! ds (nb-now))
               (let [resp (resume (req (:users/id user) bad))]
                 (is (= (str "/n/" id) (get-in resp [:headers "location"])) (str "rejected: " bad))
                 (is (not (notebooks/suspended? (nb-now)))))))
           (testing "a non-owner gets a 404 and can't toggle the flag"
             (notebooks/suspend! ds (nb-now))
             (let [other (make-user ds)]
               (is (= 404 (:status (resume (req (:users/id other) "/dashboard")))))
               (is (notebooks/suspended? (nb-now)) "still suspended")))))))))

(deftest suspended-notebook-is-refused-by-proxy-and-shown-by-workspace
  (ts/with-db
    (fn [ds]
      (stub-sprites
       (fn [_]
         (let [user      (make-user ds)
               nb        (notebooks/create! ds client limits (:users/id user) "T")
               id        (:notebooks/id nb)
               forwarded (atom 0)
               view      (ig/init-key :hosted-clay.handlers.notebooks/view
                                      {:datasource ds :sprites-client client :usage-limit-hours 50})
               workspace (ig/init-key :hosted-clay.handlers.notebooks/workspace
                                      {:datasource ds :base-url "https://clay.test" :usage-limit-hours 50})]
           (crud/update! ds :notebooks id {:status "ready" :sprite-url "https://nb.sprites.test"})
           (notebooks/suspend! ds (notebooks/by-id ds id))
           (with-redefs [proxy/forward (fn [& _] (swap! forwarded inc) {:status 200})]
             (testing "the proxy refuses with 503 and never forwards"
               (let [resp (view {:user-id (:users/id user) :path-params {:id id :path ""}
                                 :headers {"accept" "text/html"}})]
                 (is (= 503 (:status resp)))
                 (is (zero? @forwarded))))
             (testing "the workspace shows the suspended page (no editor iframes)"
               (let [resp (workspace {:user-id (:users/id user) :path-params {:id id}})]
                 (is (= 200 (:status resp)))
                 (is (str/includes? (:body resp) "suspended"))
                 (is (not (str/includes? (:body resp) "<iframe"))))))))))))
