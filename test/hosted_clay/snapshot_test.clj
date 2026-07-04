(ns hosted-clay.snapshot-test
  "Snapshot capture is stubbed at the HTTP edge (with-redefs on the http-kit
   client) — these tests are about our file storage, the captured-at upsert,
   staleness, and best-effort failure handling, not the sprite wire. Capture
   GETs two files from the sprite's Caddy (/snapshot/notebook.clj and
   /snapshot/notebook.html), so the stub answers by path; the bytes land as
   files under a temp snapshots dir."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hosted-clay.db.crud :as crud]
            [hosted-clay.notebooks :as notebooks]
            [hosted-clay.snapshot :as snapshot]
            [hosted-clay.sprites.client :as sprites]
            [hosted-clay.sprites.provision :as provision]
            [hosted-clay.test-system :as ts]
            [hosted-clay.users :as users]
            [org.httpkit.client :as http]
            [org.httpkit.server :as http-server])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def client {:api-url "https://api.example.invalid" :token {:value "t"}})

(defn- http-stub
  "Stub the http-kit client as the sprite's /snapshot/* file server: 200 + body
   for a path present in `by-path`, 404 otherwise. Returns a delivered promise,
   like http/request does."
  [by-path]
  (fn [{:keys [url]}]
    (let [path    (subs url (str/index-of url "/snapshot"))
          content (get by-path path)]
      (doto (promise)
        (deliver (if content
                   {:status 200 :body (io/input-stream (.getBytes ^String content "UTF-8"))}
                   {:status 404 :body (io/input-stream (.getBytes "not found" "UTF-8"))}))))))

(defn- temp-dir []
  (str (Files/createTempDirectory "snap" (make-array FileAttribute 0))))

(defn- delete-dir! [dir]
  (run! #(io/delete-file % true) (reverse (file-seq (io/file dir)))))

(defn- with-notebook [f]
  (ts/with-db
    (fn [ds]
      (with-redefs [sprites/create-sprite! (fn [_ name] {:name name :url (str "https://" name ".sprites.test")})
                    sprites/delete-sprite! (fn [_ _])
                    provision/provision!   (fn [_ _])]
        (let [user (users/provision! ds {:provider "hanko" :provider-subject "s"
                                         :email "kira@example.com"})
              nb   (notebooks/create! ds client {:max-sprites 10} (:users/id user) "T")
              dir  (temp-dir)]
          (try (f ds nb dir)
               (finally (delete-dir! dir))))))))

(deftest capture!-stores-source-and-html-and-upserts
  (with-notebook
    (fn [ds nb dir]
      (let [id (:notebooks/id nb)]
        (with-redefs [http/request (http-stub {"/snapshot/notebook.clj"  "(ns notebook)\n(+ 1 1)"
                                               "/snapshot/notebook.html" "<html>RENDER</html>"})]
          (testing "a capture writes both files and records a row"
            (is (true? (snapshot/capture! ds client dir nb)))
            (is (= "(ns notebook)\n(+ 1 1)" (slurp (snapshot/source-file dir id))))
            (is (= "<html>RENDER</html>" (slurp (snapshot/html-file dir id))))
            (is (some? (:notebook-snapshots/captured-at (snapshot/for-notebook ds id)))))
          (testing "a second capture upserts the same row rather than duplicating"
            (snapshot/capture! ds client dir nb)
            (is (= 1 (crud/count-rows ds :notebook-snapshots)))))))))

(defn- etag-of [content]
  (str "\"" (count content) "-" (hash content) "\""))

(defn- revalidating-stub
  "Like `http-stub`, but behaves as a real conditional file server: every 200
   carries an ETag derived from the content, and a request whose If-None-Match
   matches answers 304 with no body. `log` (an atom) collects
   [path if-none-match status] so tests can assert what actually crossed the
   wire."
  [by-path log]
  (fn [{:keys [url headers]}]
    (let [path    (subs url (str/index-of url "/snapshot"))
          content (get by-path path)
          inm     (get headers "if-none-match")
          resp    (cond
                    (nil? content)             {:status 404 :body (io/input-stream (.getBytes "not found" "UTF-8"))}
                    (= inm (etag-of content))  {:status 304}
                    :else                      {:status  200
                                                :headers {:etag (etag-of content)}
                                                :body    (io/input-stream (.getBytes ^String content "UTF-8"))})]
      (swap! log conj [path inm (:status resp)])
      (doto (promise) (deliver resp)))))

(deftest capture!-revalidates-with-etags
  (with-notebook
    (fn [ds nb dir]
      (let [id  (:notebooks/id nb)
            log (atom [])]
        (with-redefs [http/request (revalidating-stub {"/snapshot/notebook.clj"  "code-v1"
                                                       "/snapshot/notebook.html" "<html>v1</html>"} log)]
          (testing "the first capture fetches in full and stores the validators"
            (is (true? (snapshot/capture! ds client dir nb)))
            (is (= [nil nil] (map second @log)) "nothing to revalidate against yet")
            (let [snap (snapshot/for-notebook ds id)]
              (is (= (etag-of "<html>v1</html>") (:notebook-snapshots/html-etag snap)))
              (is (= (etag-of "code-v1") (:notebook-snapshots/source-etag snap)))))
          (testing "an unchanged notebook answers 304s — nothing re-shipped, captured-at still bumps"
            (reset! log [])
            (let [before (:notebook-snapshots/captured-at (snapshot/for-notebook ds id))]
              (Thread/sleep 5)
              (is (true? (snapshot/capture! ds client dir nb)))
              (is (= [304 304] (map last @log)) "both files revalidated, no bodies")
              (is (= "<html>v1</html>" (slurp (snapshot/html-file dir id))) "file untouched")
              (is (pos? (compare (:notebook-snapshots/captured-at (snapshot/for-notebook ds id)) before))
                  "captured-at advanced, so the staleness window resets"))))
        (testing "a changed file is re-fetched in full and its validator updated"
          (reset! log [])
          (with-redefs [http/request (revalidating-stub {"/snapshot/notebook.clj"  "code-v1"
                                                         "/snapshot/notebook.html" "<html>v2</html>"} log)]
            (is (true? (snapshot/capture! ds client dir nb)))
            (is (= "<html>v2</html>" (slurp (snapshot/html-file dir id))))
            (is (= {"/snapshot/notebook.html" 200 "/snapshot/notebook.clj" 304}
                   (into {} (map (juxt first last)) @log))
                "only the changed render was re-shipped")
            (is (= (etag-of "<html>v2</html>")
                   (:notebook-snapshots/html-etag (snapshot/for-notebook ds id))))))
        (testing "a validator is never sent for a file that's gone locally —
                  a 304 would leave nothing to serve, so it re-fetches in full"
          (reset! log [])
          (io/delete-file (snapshot/html-file dir id))
          (with-redefs [http/request (revalidating-stub {"/snapshot/notebook.clj"  "code-v1"
                                                         "/snapshot/notebook.html" "<html>v2</html>"} log)]
            (is (true? (snapshot/capture! ds client dir nb)))
            (is (= "<html>v2</html>" (slurp (snapshot/html-file dir id))) "file restored")
            (is (= [nil 200] (->> @log (filter #(= "/snapshot/notebook.html" (first %))) first rest))
                "no If-None-Match went out for the missing file")))))))

(deftest capture!-keeps-prior-file-when-a-fetch-fails
  (with-notebook
    (fn [ds nb dir]
      (let [id (:notebooks/id nb)]
        (with-redefs [http/request (http-stub {"/snapshot/notebook.clj"  "source-v1"
                                               "/snapshot/notebook.html" "html-v1"})]
          (snapshot/capture! ds client dir nb))
        (testing "an html fetch that 404s leaves the previously stored html intact"
          ;; only the .clj is present now → the .html GET 404s
          (with-redefs [http/request (http-stub {"/snapshot/notebook.clj" "source-v2"})]
            (snapshot/capture! ds client dir nb))
          (is (= "source-v2" (slurp (snapshot/source-file dir id))) "source refreshed")
          (is (= "html-v1" (slurp (snapshot/html-file dir id))) "html left as the prior value"))))))

(deftest capture!-is-best-effort
  (with-notebook
    (fn [ds nb dir]
      (let [id (:notebooks/id nb)]
        (testing "both fetches 404 → writes no files, stores nothing, returns nil"
          (with-redefs [http/request (http-stub {})]
            (is (nil? (snapshot/capture! ds client dir nb)))
            (is (nil? (snapshot/for-notebook ds id)))
            (is (not (.exists (snapshot/html-file dir id))))))
        (testing "a transport :error is swallowed — it must never break the census"
          (with-redefs [http/request (fn [_] (doto (promise) (deliver {:error (ex-info "boom" {})})))]
            (is (nil? (snapshot/capture! ds client dir nb)))
            (is (nil? (snapshot/for-notebook ds id)))))
        (testing "a thrown transport error is swallowed too"
          (with-redefs [http/request (fn [_] (throw (ex-info "socket closed" {})))]
            (is (nil? (snapshot/capture! ds client dir nb)))))))))

(deftest stale?-tracks-the-refresh-window
  (with-notebook
    (fn [ds nb dir]
      (let [id (:notebooks/id nb)]
        (is (snapshot/stale? nil 15) "a missing snapshot is always stale")
        (with-redefs [http/request (http-stub {"/snapshot/notebook.clj"  "code"
                                               "/snapshot/notebook.html" "<html>r</html>"})]
          (run! deref (snapshot/refresh-awake! ds client dir [nb] 15)))
        (let [snap (snapshot/for-notebook ds id)]
          (is (= "code" (slurp (snapshot/source-file dir id))) "refresh-awake! captured the stale notebook")
          (is (= "<html>r</html>" (slurp (snapshot/html-file dir id))))
          (is (not (snapshot/stale? snap 15)) "a fresh capture isn't stale")
          (is (snapshot/stale? snap 0) "a 0-minute window makes everything stale"))))))

(deftest capture!-over-a-real-http-server
  ;; The other tests stub http/request; this one drives the actual http-kit
  ;; client against a real server, so it exercises :as :stream + :keepalive -1
  ;; and the InputStream → atomic-file write for real (the path the stub can't
  ;; cover). Stands in for the sprite's /snapshot/* Caddy routes.
  (with-notebook
    (fn [ds nb dir]
      (let [handler (fn [req]
                      (case (:uri req)
                        "/snapshot/notebook.html" {:status 200 :body "<html>REAL</html>"}
                        "/snapshot/notebook.clj"  {:status 200 :body "(ns real)\n42"}
                        {:status 404 :body "nope"}))
            stop    (http-server/run-server handler {:port 0})
            port    (:local-port (meta stop))
            nb      (assoc nb :notebooks/sprite-url (str "http://localhost:" port))
            id      (:notebooks/id nb)]
        (try
          (is (true? (snapshot/capture! ds client dir nb)))
          (is (= "<html>REAL</html>" (slurp (snapshot/html-file dir id))))
          (is (= "(ns real)\n42" (slurp (snapshot/source-file dir id))))
          (finally (stop)))))))

(deftest refresh-awake!-skips-fresh-notebooks
  (with-notebook
    (fn [ds nb dir]
      (let [captures (atom 0)]
        (with-redefs [http/request (fn [{:keys [url]}]
                                     (when (str/ends-with? url ".clj") (swap! captures inc))
                                     (doto (promise)
                                       (deliver {:status 200
                                                 :body   (io/input-stream (.getBytes "x" "UTF-8"))})))]
          (run! deref (snapshot/refresh-awake! ds client dir [nb] 15))   ; first: captures
          (run! deref (snapshot/refresh-awake! ds client dir [nb] 15)))  ; second: fresh, skipped
        (is (= 1 @captures) "the second pass found a fresh snapshot and didn't re-capture")))))
