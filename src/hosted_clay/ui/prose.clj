(ns hosted-clay.ui.prose
  "A tiny plain-text -> hiccup renderer for the editable prose pages (the
   privacy/terms page). The copy lives in a .txt file under resources so it can
   be edited without touching Clojure; this turns that text into the site's own
   elements (h2/p/ul/a/strong) so it inherits the page styles for free.

   The format is a deliberately small, obvious subset — enough for a policy
   page, no more:
     - blank lines separate blocks;
     - a block that is a single line of '## ' (up to '#### ') is a heading;
     - a block whose every line starts with '- ' is a bullet list;
     - anything else is a paragraph (its lines joined into one flow).
   Inline, within any text: [label](url) is a link and **text** is bold.
   Everything else is literal — hiccup2 escapes it, so the file can't inject
   markup."
  (:require [clojure.string :as str]))

(def ^:private inline-re
  ;; A link — [label](url) — or bold — **text**. Group 1/2 are the link label
  ;; and url; group 3 is the bold text. Exactly one side matches per hit.
  #"\[([^\]]+)\]\(([^)]+)\)|\*\*([^*]+)\*\*")

(defn- inline
  "Expand the inline markup in `s` into a seq of hiccup nodes and literal
   strings, ready to splice into a block element. Plain runs stay strings so
   hiccup escapes them."
  [s]
  (let [m (re-matcher inline-re s)]
    (loop [out [] last-end 0]
      (if (.find m)
        (let [lead (subs s last-end (.start m))
              node (if-let [label (.group m 1)]
                     [:a {:href (.group m 2)} label]
                     [:strong (.group m 3)])]
          (recur (cond-> out
                   (seq lead) (conj lead)
                   :always    (conj node))
                 (.end m)))
        (let [tail (subs s last-end)]
          (cond-> out (seq tail) (conj tail)))))))

(defn- block->hiccup [block]
  (let [lines   (->> (str/split-lines block) (map str/trim) (remove str/blank?))
        heading (when (= 1 (count lines))
                  (re-matches #"(#{2,4})\s+(.*)" (first lines)))]
    (cond
      (empty? lines) nil

      heading
      (let [[_ hashes text] heading]
        (into [(keyword (str "h" (count hashes)))] (inline text)))

      (every? #(str/starts-with? % "- ") lines)
      (into [:ul] (map #(into [:li] (inline (subs % 2))) lines))

      :else
      (into [:p] (inline (str/join " " lines))))))

(defn render
  "Parse `text` (the raw contents of a prose .txt file) into a seq of block
   hiccup — headings, paragraphs, and lists — ready to drop into a page."
  [text]
  (->> (str/split (str/trim text) #"\n[ \t]*\n")
       (remove str/blank?)
       (keep block->hiccup)))
