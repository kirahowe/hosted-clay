(ns hosted-clay.share-test
  "The read-only share view: once a notebook has a rendered snapshot, /s/ serves
   that static HTML straight from the control plane (no sprite contact, works
   while paused); until then it falls back to the live proxy. proxy/forward is
   stubbed so we can assert when the sprite is — and isn't — touched. The view is
   keyed on the share token (separate from the notebook id), so the path-param is
   that token."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [hosted-clay.db.crud :as crud]
            [hosted-clay.notebooks :as notebooks]
            [hosted-clay.proxy :as proxy]
            [hosted-clay.snapshot :as snapshot]
            [hosted-clay.sprites.client :as sprites]
            [hosted-clay.sprites.exec :as exec]
            [hosted-clay.sprites.provision :as provision]
            [hosted-clay.test-system :as ts]
            [hosted-clay.usage :as usage]
            [hosted-clay.users :as users]))

(require 'hosted-clay.handlers.share)

(def client {:api-url "https://api.example.invalid" :token {:value "t"}})

(defn- with-ready-notebook [f]
  (ts/with-db
    (fn [ds]
      (with-redefs [sprites/create-sprite! (fn [_ name] {:name name :url (str "https://" name ".sprites.test")})
                    sprites/delete-sprite! (fn [_ _])
                    provision/provision!   (fn [_ _])]
        (let [user (users/provision! ds {:provider "hanko" :provider-subject "s"
                                         :email "kira@example.com"})
              nb   (notebooks/create! ds client {:max-sprites 10} (:users/id user) "T")
              id   (:notebooks/id nb)]
          (crud/update! ds :notebooks id {:status "ready" :sprite-url "https://nb.sprites.test"})
          (f ds (notebooks/by-id ds id)))))))

(defn- snapshot-html! [ds nb html]
  (with-redefs [exec/exec! (fn [_ _ cmd & _]
                             {:exit 0 :err ""
                              :out  (if (str/ends-with? (last cmd) ".html") html "(ns notebook)")})]
    (snapshot/capture! ds client nb)))

(defn- share-handler [ds]
  (ig/init-key :hosted-clay.handlers/share
               {:datasource ds :sprites-client client :usage-limit-hours 50}))

(defn- over-limit! [ds nb]
  (crud/create! ds :user-usage {:user-id     (:notebooks/user-id nb)
                                :usage-month (usage/current-month)
                                :awake-seconds (* 99 3600)}))

(deftest share-prefers-the-static-snapshot
  (with-ready-notebook
    (fn [ds nb]
      (let [token     (:notebooks/share-token nb)
            forwarded (atom 0)
            handler   (share-handler ds)
            get-doc   #(handler {:request-method :get :path-params {:token token :path ""}})]
        (with-redefs [proxy/forward (fn [& _] (swap! forwarded inc) {:status 200 :body "LIVE"})]
          (testing "no snapshot yet → falls back to the live proxy"
            (let [resp (get-doc)]
              (is (= 200 (:status resp)))
              (is (= 1 @forwarded))))
          (testing "with a snapshot → serves the static html, never touching the sprite"
            (snapshot-html! ds nb "<html>SNAPSHOT</html>")
            (let [resp (get-doc)]
              (is (= 200 (:status resp)))
              (is (str/includes? (:body resp) "<html>SNAPSHOT</html>"))
              (is (= 1 @forwarded) "the static path did not proxy")))
          (testing "the static snapshot is served even when the owner is over the limit"
            (over-limit! ds nb)
            (let [resp (get-doc)]
              (is (= 200 (:status resp)))
              (is (str/includes? (:body resp) "SNAPSHOT"))
              (is (= 1 @forwarded) "still no proxy — no wake, no bill"))))))))

(deftest share-pauses-over-limit-without-a-snapshot
  (with-ready-notebook
    (fn [ds nb]
      (over-limit! ds nb)
      (let [token     (:notebooks/share-token nb)
            forwarded (atom 0)
            handler   (share-handler ds)]
        (with-redefs [proxy/forward (fn [& _] (swap! forwarded inc) {:status 200})]
          (testing "no snapshot and over the limit → 503, and the sprite is left alone"
            (let [resp (handler {:request-method :get :path-params {:token token :path ""}})]
              (is (= 503 (:status resp)))
              (is (zero? @forwarded)))))))))

(deftest share-snapshot-sets-no-cache-and-strips-head-body
  (with-ready-notebook
    (fn [ds nb]
      (let [token   (:notebooks/share-token nb)
            handler (share-handler ds)]
        (snapshot-html! ds nb "<html>SNAP</html>")
        (testing "GET carries no-cache so a viewer always revalidates"
          (let [resp (handler {:request-method :get :path-params {:token token :path ""}})]
            (is (= "no-cache" (get-in resp [:headers "cache-control"])))
            (is (str/includes? (:body resp) "SNAP"))))
        (testing "HEAD gets the headers but not the (large) body"
          (let [resp (handler {:request-method :head :path-params {:token token :path ""}})]
            (is (= 200 (:status resp)))
            (is (= "" (:body resp)))
            (is (= "no-cache" (get-in resp [:headers "cache-control"])))))))))

(deftest share-blocks-the-live-fallback-when-suspended
  (with-ready-notebook
    (fn [ds nb]
      (notebooks/suspend! ds nb)
      (let [token     (:notebooks/share-token nb)
            forwarded (atom 0)
            handler   (share-handler ds)
            get-doc   #(handler {:request-method :get :path-params {:token token :path ""}})]
        (with-redefs [proxy/forward (fn [& _] (swap! forwarded inc) {:status 200})]
          (testing "no snapshot + suspended → 503, the sprite is left asleep"
            (let [resp (get-doc)]
              (is (= 503 (:status resp)))
              (is (zero? @forwarded))))
          (testing "a snapshot still serves (free, no wake) even while suspended"
            (snapshot-html! ds nb "<html>SNAP</html>")
            (let [resp (get-doc)]
              (is (= 200 (:status resp)))
              (is (str/includes? (:body resp) "SNAP"))
              (is (zero? @forwarded)))))))))

(deftest share-refuses-non-get-and-the-editor
  (with-ready-notebook
    (fn [ds nb]
      (let [token   (:notebooks/share-token nb)
            handler (share-handler ds)]
        (testing "a non-GET method is 405"
          (is (= 405 (:status (handler {:request-method :post
                                        :path-params {:token token :path ""}})))))
        (testing "the editor path is 403 even with a snapshot present"
          (snapshot-html! ds nb "<html>x</html>")
          (is (= 403 (:status (handler {:request-method :get
                                        :path-params {:token token :path "edit/"}})))))
        (testing "an unknown token is a 404"
          (is (= 404 (:status (handler {:request-method :get
                                        :path-params {:token "nope" :path ""}})))))))))
