(ns hosted-clay.proxy-test
  "Unit tests for the proxy's response fixups. The HTTP/WebSocket relay
   itself needs a real sprite; these cover the pure transforms."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hosted-clay.proxy :as proxy]))

(def ^:private fix-clay-reload #'proxy/fix-clay-reload)
(def ^:private inject-theme #'proxy/inject-theme)
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
    ;; our /n/:id/ prefix -> 404 -> empty body -> JSON.parse throws.
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

(deftest clay-dark-mode-injection
  (testing "the dark-mode head is spliced in just before </head>"
    (let [html "<html><head><title>nb</title></head><body>x</body></html>"
          out  (inject-theme html)]
      (is (str/includes? out "name=\"color-scheme\"")
          "the iframe declares it supports both schemes")
      (is (str/includes? out "data-bs-theme")
          "the prefers-color-scheme -> data-bs-theme bridge is present")
      (is (str/includes? out "prefers-color-scheme: dark")
          "the dark stylesheet is inlined")
      (is (< (str/index-of out "data-bs-theme") (str/index-of out "</head>"))
          "the injection lands inside <head>, before its close")))

  (testing "a page without a </head> (e.g. a fragment) is left untouched"
    (let [html "<div>just a fragment</div>"]
      (is (= html (inject-theme html))))))

(deftest editor-path-detection
  (testing "code-server paths are recognized so they're never rewritten"
    (is (editor-path? "edit/"))
    (is (editor-path? "edit/stable-abc/static/out/vs/workbench.js")))
  (testing "the rendered notebook and its assets are not editor paths"
    (is (not (editor-path? "")))
    (is (not (editor-path? "counter")))))
