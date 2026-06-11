(ns hosted-clay.http-test
  "Integration tests for the HTTP layer: the real router + middleware
   stack driven with synthetic ring requests, and one full-system test
   over a real socket."
  (:require [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as http-kit]
            [hosted-clay.test-system :as ts])
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

(deftest end-to-end-server-binds-and-serves
  (ts/with-system [:hosted-clay.concerns/http-kit]
    (fn [system]
      (let [server (:hosted-clay.concerns/http-kit system)
            port   (http-kit/server-port server)]
        (is (pos? port) "server bound to an ephemeral port")
        (let [resp (GET port "/health")]
          (is (= 200 (.statusCode resp)))
          (is (re-find #"\"status\":\"ok\"" (.body resp))))))))
