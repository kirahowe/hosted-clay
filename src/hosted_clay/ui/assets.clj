(ns hosted-clay.ui.assets
  "Cache-busting for our own static assets. `versioned` appends a
   ?v=<content-hash> to a /static/* URL, so the URL changes whenever the
   file's bytes do and a browser can't serve a stale cached copy across a
   deploy. This only forces freshness — we send no long-lived Cache-Control,
   so unchanged bytes are still revalidated.

   Scoped to assets under resources/public: the vendored hanko bundle
   carries its version in its filename, and off-origin scripts (Umami) are
   out of reach.

   The hash is memoised per resource, keyed on last-modified, so a dev edit
   re-versions on the next render while a prod uberjar hashes each file
   once. An unreadable last-modified degrades to a one-time hash; an
   unresolvable path degrades to the bare URL."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private cache (atom {}))

(defn- resource-for
  "The classpath resource backing a /static/* URL (handler root is `public`,
   see hosted-clay.handlers/static), or nil for anything we don't serve that
   way (absolute third-party URLs, unknown paths)."
  [path]
  (when (str/starts-with? path "/static/")
    (io/resource (str "public" (subs path (count "/static"))))))

(defn- last-modified [url]
  (try
    (.getLastModified (.openConnection url))
    (catch Exception _ -1)))

(defn- content-hash [url]
  (let [crc (java.util.zip.CRC32.)
        buf (byte-array 8192)]
    (with-open [in (io/input-stream url)]
      (loop []
        (let [n (.read in buf)]
          (when (pos? n)
            (.update crc buf 0 n)
            (recur)))))
    (Long/toHexString (.getValue crc))))

(defn versioned
  "`path` (a /static/* URL) with a ?v=<content-hash> cache-buster appended,
   or `path` unchanged when the asset can't be resolved."
  [path]
  (if-let [url (resource-for path)]
    (let [lm     (last-modified url)
          cached (get @cache path)]
      (if (= (:lm cached) lm)
        (:url cached)
        (let [out (str path "?v=" (content-hash url))]
          (swap! cache assoc path {:lm lm :url out})
          out)))
    path))
