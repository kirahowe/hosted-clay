(ns hosted-clay.http-test
  "Integration tests for the HTTP layer: the real router + middleware
   stack driven with synthetic ring requests, and one full-system test
   over a real socket."
  (:require [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as http-kit]
            [hosted-clay.notebooks :as notebooks]
            [hosted-clay.sprites.client :as sprites]
            [hosted-clay.sprites.provision :as provision]
            [hosted-clay.test-system :as ts]
            [hosted-clay.users :as users])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))

(defn- GET [port path]
  (let [client (HttpClient/newHttpClient)
        req    (-> (HttpRequest/newBuilder)
                   (.uri (URI/create (str "http://localhost:" port path)))
                   .GET
                   .build)]
    (.send client req (HttpResponse$BodyHandlers/ofString))))

(deftest routes-via-handler
  (ts/with-system [:hosted-clay.concerns.reitit/ring-handler]
    (fn [system]
      (let [handler (:hosted-clay.concerns.reitit/ring-handler system)]

        (testing "GET / returns the home page"
          (let [{:keys [status headers body]} (handler {:request-method :get :uri "/"})]
            (is (= 200 status))
            (is (re-find #"(?i)text/html" (get headers "content-type")))
            (is (re-find #"Clojure data science" body))))

        (testing "GET /health returns JSON"
          (let [{:keys [status body]} (handler {:request-method :get :uri "/health"})]
            (is (= 200 status))
            (is (re-find #"\"status\":\"ok\"" body))))

        (testing "GET /static/css/tokens.css is served from resources/public"
          (let [{:keys [status body]} (handler {:request-method :get :uri "/static/css/tokens.css"})]
            (is (= 200 status))
            (is (re-find #":root" (slurp body)))))

        (testing "the dashboard requires a session"
          (let [{:keys [status headers]} (handler {:request-method :get :uri "/dashboard"})]
            (is (= 303 status))
            (is (= "/login" (get headers "location")))))

        (testing "the notebook proxy requires a session"
          (let [{:keys [status]} (handler {:request-method :get :uri "/n/some-id/"})]
            (is (= 303 status))))

        (testing "the workspace page requires a session"
          (let [{:keys [status headers]} (handler {:request-method :get :uri "/notebooks/some-id"})]
            (is (= 303 status))
            (is (= "/login" (get headers "location")))))

        (testing "creating a notebook without a session is a 401, not a redirect"
          (let [{:keys [status]} (handler {:request-method :post :uri "/notebooks"})]
            (is (= 401 status))))

        (testing "an unknown share token is a 404 and needs no session"
          (let [{:keys [status]} (handler {:request-method :get :uri "/s/nope/"})]
            (is (= 404 status))))

        (testing "a cross-origin POST is refused"
          (let [{:keys [status]} (handler {:request-method :post :uri "/notebooks"
                                           :headers {"origin" "https://evil.test"}})]
            (is (= 403 status))))

        (testing "unknown routes return 404"
          (let [{:keys [status]} (handler {:request-method :get :uri "/no-such-route"})]
            (is (= 404 status))))))))

(def ^:private test-client {:api-url "https://api.example.invalid" :token {:value "t"}})

(deftest workspace-renders-for-owner-only
  (ts/with-system [:hosted-clay.handlers.notebooks/workspace]
    (fn [system]
      (let [ds      (:hosted-clay.db/migrator system)
            handler (:hosted-clay.handlers.notebooks/workspace system)]
        (with-redefs [sprites/create-sprite! (fn [_ name] {:name name :url (str "https://" name ".sprites.test")})
                      provision/provision!   (fn [_ _])]
          (let [user (users/provision! ds {:provider         "hanko"
                                           :provider-subject (str (random-uuid))
                                           :email            (str (random-uuid) "@example.com")})
                nb   (notebooks/create! ds test-client {:max-sprites 10} (:users/id user) "My notebook")
                id   (:notebooks/id nb)]

            (testing "the owner gets a split-view page embedding the editor and output"
              (let [{:keys [status body]} (handler {:user-id     (:users/id user)
                                                    :path-params {:id id}})]
                (is (= 200 status))
                (is (re-find #"<iframe" body))
                (is (re-find (re-pattern (str "/n/" id "/edit/")) body))
                (is (re-find (re-pattern (str "/n/" id "/\"")) body))))

            (testing "a non-owner gets a 404, not a 403 (ids stay unprobeable)"
              (let [{:keys [status]} (handler {:user-id     "someone-else"
                                               :path-params {:id id}})]
                (is (= 404 status))))))))))

(deftest end-to-end-server-binds-and-serves
  (ts/with-system [:hosted-clay.concerns/http-kit]
    (fn [system]
      (let [server (:hosted-clay.concerns/http-kit system)
            port   (http-kit/server-port server)]
        (is (pos? port) "server bound to an ephemeral port")
        (let [resp (GET port "/health")]
          (is (= 200 (.statusCode resp)))
          (is (re-find #"\"status\":\"ok\"" (.body resp))))))))
