#!/usr/bin/env bb
(ns watch-sprites
  "Poll the Sprites API and report when nothing is running.

  Sprites bill compute only while *running* (awake); a suspended sprite is
  ~free. This polls the org's sprite list on an interval, prints how many are
  awake (and which), and announces — with a timestamp — the moment the running
  count first hits zero, then exits. Handy for confirming everything actually
  went idle after you closed your notebook tabs.

  When it reaches zero it also lists each sprite's last suspend time and (on
  macOS) rings the bell and pops a desktop notification.

      scripts/watch-sprites.bb [interval-seconds]   ;; default: 15

  Requires the `sprite` CLI (logged in)."
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import (java.time LocalTime LocalDateTime ZoneId)
           (java.time.format DateTimeFormatter)))

(def interval-secs
  (or (some-> *command-line-args* first parse-long) 15))

(def ^DateTimeFormatter hms  (DateTimeFormatter/ofPattern "HH:mm:ss"))
(def ^DateTimeFormatter full (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))

(defn now-hms [] (.format (LocalTime/now) hms))

(defn fetch
  "Read the org's sprite list from the Sprites API. Returns the parsed body
   map, or nil if the call failed (so the caller can just retry next tick).
   `-- -s` silences curl's progress meter; the API banner goes to stderr,
   which `p/sh` keeps out of `:out`."
  []
  (let [{:keys [out exit]} (p/sh "sprite" "api" "/v1/sprites" "--" "-s")]
    (when (and (zero? exit) (not (str/blank? out)))
      (try (json/parse-string out true) (catch Exception _ nil)))))

(defn notify!
  "Ping the human: terminal bell always, desktop notification on macOS."
  []
  (print "") (flush)
  (when (str/includes? (str/lower-case (System/getProperty "os.name")) "mac")
    (p/sh "osascript" "-e"
          "display notification \"All sprites are suspended.\" with title \"Sprites idle\" sound name \"Glass\"")))

(defn report-idle!
  "Everything is suspended — stamp the moment and list each sprite's last
   suspend time. `last_warming_at` is when a sprite last went warm; fall back
   to `updated_at` for anything that never reported a warming timestamp."
  [sprites]
  (println (format "%s  ✓ 0 running — all sprites suspended." (now-hms)))
  (println)
  (println (format "No more running sprites as of %s %s."
                   (.format (LocalDateTime/now) full)
                   (ZoneId/systemDefault)))
  (println)
  (println "Per-sprite last suspend time (UTC):")
  (doseq [{:keys [name status last_warming_at updated_at]} sprites]
    (println (format "  %s  %s  suspended=%s"
                     name status (or last_warming_at updated_at "?"))))
  (notify!))

(defn running-names [sprites]
  (->> sprites (filter #(= "running" (:status %))) (map :name) (str/join " ")))

(println (format "Watching sprites every %ds (Ctrl-C to stop)…" interval-secs))

(loop []
  (if-let [{:keys [running sprites]} (fetch)]
    (if (zero? running)
      (do (report-idle! sprites)
          (System/exit 0))
      (println (format "%s  running=%d  [%s]" (now-hms) running (running-names sprites))))
    (println (format "%s  ⚠ API read failed; retrying next tick" (now-hms))))
  (Thread/sleep (* 1000 interval-secs))
  (recur))
