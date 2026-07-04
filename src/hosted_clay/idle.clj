(ns hosted-clay.idle
  "Server-side idle-suspend backstop. The workspace pauses itself client-side
   when you step away (the \"Still working?\" veil tears the iframes down), but
   that JS can't run when the tab's machine is asleep or the browser has frozen
   it — so a left-open tab keeps its editor / Clay live-reload WebSocket open
   and pins the sprite awake (and billing) indefinitely. This closes that gap
   from the server: the proxy stamps every browser->sprite frame as activity
   (see hosted-clay.proxy), and every tick this sweep force-suspends any
   notebook whose last frame is older than `idle-minutes` — a frozen/asleep tab
   sends no frames, so it goes quiet and drops; an actively-used tab keeps its
   timestamp fresh and is left alone. Suspending just closes the relayed
   channels, which aborts the upstream sockets so the sprite loses its last
   inbound connection and idle-suspends. The owner's next keystroke wakes it —
   sub-second, REPL and editor state intact.

   The decision is a pure function of the proxy's activity snapshot and the
   current time (`stale-ids`); `sweep!` reads the snapshot and applies it. No
   state of its own — the activity clock lives in the proxy's relay registry,
   so a notebook that drops its connection simply falls out of the snapshot."
  (:require [clojure.tools.logging :as log]
            [hosted-clay.proxy :as proxy])
  (:import (java.time Duration Instant)))

(defn- stale? [^Instant active-at ^Instant now idle-minutes]
  (>= (.toMinutes (Duration/between active-at now)) (long idle-minutes)))

(defn stale-ids
  "The notebook ids in `activity` (notebook-id -> Instant of last
   browser->sprite frame) whose last activity is at least `idle-minutes` in the
   past as of `now` — the set the sweep suspends."
  [activity now idle-minutes]
  (into #{}
        (comp (filter (fn [[_ active-at]] (stale? active-at now idle-minutes)))
              (map key))
        activity))

(defn sweep!
  "One idle-suspend pass. Force-suspends every notebook the proxy is relaying a
   WebSocket for whose last browser->sprite frame is older than `idle-minutes`,
   by closing its relayed channels (which aborts the upstream sockets, so the
   sprite loses its last inbound connection and idle-suspends). nil/0
   `idle-minutes` disables the sweep. Returns the set of suspended ids."
  [idle-minutes]
  (when (and idle-minutes (pos? idle-minutes))
    (let [activity (proxy/activity-snapshot)
          stale    (stale-ids activity (Instant/now) idle-minutes)]
      (log/debug "idle sweep" {:held (count activity) :suspending (count stale)})
      (doseq [id stale]
        (log/info "idle-suspending notebook (no activity within window)"
                  {:notebook-id id :idle-minutes idle-minutes})
        (proxy/disconnect-notebook! id))
      stale)))
