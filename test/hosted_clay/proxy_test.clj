(ns hosted-clay.proxy-test
  "Unit tests for the proxy. The response fixups are pure transforms; the
   idle-suspend activity registry — register on WebSocket open, stamp on each
   browser->sprite frame, deregister + abort the upstream on close — is driven
   here with a faked browser channel and a stubbed upstream connect, so it needs
   no real sprite. Only the upstream connect itself does, and that's the one
   thing we stub out."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [org.httpkit.server :as http-server]
            [hosted-clay.proxy :as proxy])
  (:import (java.net.http WebSocket)
           (java.time Instant)
           (java.util.concurrent CompletableFuture)))

(def ^:private fix-clay-reload #'proxy/fix-clay-reload)
(def ^:private editor-path? #'proxy/editor-path?)

(deftest clay-reload-rewrite
  (testing "Clay's hardcoded localhost socket is rewritten to same-origin"
    (let [html "<body><script>clay_port=1971;clay_socket = new WebSocket('ws://localhost:'+clay_port);</script></body>"
          out  (fix-clay-reload html)]
      (is (not (str/includes? out "new WebSocket('ws://localhost:'+clay_port)"))
          "the localhost socket is gone")
      (is (str/includes? out "location.host+location.pathname")
          "the socket now derives host + path from the page origin")))

  (testing "the multi-page fallback redirect is made relative"
    (let [html "<script>location.assign('http://localhost:'+clay_port);</script>"]
      (is (str/includes? (fix-clay-reload html) "location.assign(location.pathname)"))))

  (testing "the /counter staleness poll is made prefix-relative"
    ;; root-absolute /counter resolves to the control-plane root through
    ;; our /n/:id/view/ prefix -> 404 -> empty body -> JSON.parse throws.
    (let [html "<script>const r = await fetch('/counter'); r.json();</script>"
          out  (fix-clay-reload html)]
      (is (str/includes? out "fetch('counter')"))
      (is (not (str/includes? out "fetch('/counter')")))))

  (testing "the header logo path is made prefix-relative"
    (let [html "<img src=\"/Clay.svg.png\" alt=\"Clay logo\">"
          out  (fix-clay-reload html)]
      (is (str/includes? out "src=\"Clay.svg.png\""))
      (is (not (str/includes? out "src=\"/Clay.svg.png\"")))))

  (testing "a page without Clay's snippet (e.g. code-server) is untouched"
    (let [html "<html><head></head><body>editor with a ws://localhost:9 mention</body></html>"]
      (is (= html (fix-clay-reload html))))))

(deftest editor-path-detection
  (testing "code-server paths are recognized so they're never rewritten"
    (is (editor-path? "edit/"))
    (is (editor-path? "edit/stable-abc/static/out/vs/workbench.js")))
  (testing "the rendered notebook and its assets are not editor paths"
    (is (not (editor-path? "")))
    (is (not (editor-path? "counter")))))

;; ---------- idle-suspend activity registry ----------

;; The relay's WebSocket path can't reach a real sprite in a unit test, but its
;; bookkeeping — the piece the scheduler's idle sweep depends on to find and
;; suspend a left-open tab — is exercisable by faking the two edges: http-kit's
;; browser channel (as-channel captures the callbacks; close fires :on-close,
;; the way http-kit does) and the upstream connect (stubbed). We then drive
;; :on-open / :on-receive / :on-close by hand and watch the activity snapshot.

(def ^:private ws-req
  {:request-method :get
   :query-string   nil
   :headers        {"upgrade" "websocket"}})

(use-fixtures :each
  (fn [run]
    ;; relays is a process-wide defonce; isolate each test from the others.
    (reset! @#'proxy/relays {})
    (run)))

(defn- wait-for
  "Poll `pred` until truthy or `timeout-ms` elapses (the :on-close abort runs on
   a future, so it's observed asynchronously). Returns whether it became truthy."
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred)                                   true
        (>= (System/currentTimeMillis) deadline) false
        :else                                    (do (Thread/sleep 5) (recur))))))

(defn- recording-websocket
  "Stand-in for the upstream java.net.http WebSocket that records whether it was
   aborted; every other method is an inert stub."
  [aborted?]
  (reify WebSocket
    (sendText       [this _ _] (CompletableFuture/completedFuture this))
    (sendBinary     [this _ _] (CompletableFuture/completedFuture this))
    (sendPing       [this _]   (CompletableFuture/completedFuture this))
    (sendPong       [this _]   (CompletableFuture/completedFuture this))
    (sendClose      [this _ _] (CompletableFuture/completedFuture this))
    (request        [_ _])
    (getSubprotocol [_] nil)
    (isOutputClosed [_] false)
    (isInputClosed  [_] false)
    (abort          [_] (reset! aborted? true))))

(deftest relay-lifecycle-tracks-activity
  (let [captured (atom nil)
        closed   (atom #{})
        ch       (Object.)]
    (with-redefs [proxy/connect-upstream (fn [& _] nil)
                  http-server/as-channel (fn [_ cbs] (reset! captured cbs) :ok)
                  http-server/close      (fn [c]
                                           (swap! closed conj c)
                                           ((:on-close @captured) c nil))]
      ;; forward sees the WebSocket upgrade and hands relay-websocket's callback
      ;; map to our stubbed as-channel instead of opening a real channel.
      (proxy/forward nil "http://sprite" "view/" ws-req {:notebook-id "nb1"})

      (testing "opening the relay registers the notebook with an activity stamp"
        ((:on-open @captured) ch)
        (let [t1 (get (proxy/activity-snapshot) "nb1")]
          (is (instance? Instant t1))

          (testing "a browser->sprite frame stamps fresh activity"
            (Thread/sleep 5)
            ((:on-receive @captured) ch "keystroke")
            (is (.isAfter ^Instant (get (proxy/activity-snapshot) "nb1") t1)))))

      (testing "disconnecting closes the browser channel and deregisters it"
        (proxy/disconnect-notebook! "nb1")
        (is (contains? @closed ch) "the browser channel was closed")
        (is (nil? (get (proxy/activity-snapshot) "nb1"))
            "the notebook is gone from the snapshot, so the sweep leaves it alone")))))

(deftest relay-without-notebook-id-is-not-tracked
  ;; forward is called without a :notebook-id for relays the sweep shouldn't
  ;; manage; register! guards on it, so such a relay never enters the snapshot
  ;; (and, conversely, forgetting to pass the id would silently un-manage a tab).
  (let [captured (atom nil)]
    (with-redefs [proxy/connect-upstream (fn [& _] nil)
                  http-server/as-channel (fn [_ cbs] (reset! captured cbs) :ok)]
      (proxy/forward nil "http://sprite" "view/" ws-req {})
      ((:on-open @captured) (Object.))
      (is (empty? (proxy/activity-snapshot))))))

(deftest disconnect-aborts-the-upstream-socket
  (let [captured (atom nil)
        aborted? (atom false)
        upstream (recording-websocket aborted?)]
    (with-redefs [proxy/connect-upstream (fn [& _] upstream)
                  http-server/as-channel (fn [_ cbs] (reset! captured cbs) :ok)
                  http-server/close      (fn [c] ((:on-close @captured) c nil))]
      (proxy/forward nil "http://sprite" "view/" ws-req {:notebook-id "nb1"})
      ((:on-open @captured) (Object.))
      ;; Closing the browser side must tear the upstream socket down *hard*: a
      ;; lingering connection to the sprite's URL keeps it awake (and billing),
      ;; which is the whole thing the idle sweep exists to prevent.
      (proxy/disconnect-notebook! "nb1")
      (is (wait-for #(deref aborted?) 2000)
          "the sprite-side socket was aborted"))))
