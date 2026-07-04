(ns hosted-clay.proxy
  "Forwarding browser traffic to a notebook's sprite URL. Sprite URLs
   are private (Sprites' own auth), so every forwarded request carries
   the platform API token — access control happens in our handlers
   before anything reaches this namespace.

   Plain requests are forwarded with http-kit's client; WebSocket
   upgrades (Clay live-reload, code-server) are relayed frame-by-frame
   between the browser (http-kit channel) and the sprite (java.net.http
   WebSocket)."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [org.httpkit.client :as http-client]
            [org.httpkit.server :as http-server]
            [hosted-clay.sprites.client :as sprites]
            [hosted-clay.ui.pages.waking :as waking])
  (:import (java.net URI)
           (java.net.http HttpClient WebSocket WebSocket$Listener)
           (java.nio ByteBuffer)
           (java.time Instant)
           (java.util.concurrent TimeUnit)))

;; ---------- header plumbing ----------

(def ^:private hop-by-hop
  #{"connection" "keep-alive" "proxy-authenticate" "proxy-authorization"
    "te" "trailers" "transfer-encoding" "upgrade"})

(def ^:private request-drop
  ;; host: the sprite proxy routes on its own hostname.
  ;; cookie: our hanko session must never reach a sprite, and a share
  ;;   viewer's cookies must never reach the notebook owner's VM.
  ;; authorization: replaced with the sprites API token.
  ;; accept-encoding: http-kit's client transparently decompresses, so
  ;;   negotiating identity end-to-end keeps bodies and headers honest.
  (into hop-by-hop #{"host" "cookie" "authorization" "accept-encoding"
                     "content-length"}))

(defn- forward-request-headers [req bearer]
  (-> (:headers req)
      (->> (remove (fn [[k _]] (contains? request-drop (str/lower-case k))))
           (into {}))
      (assoc "authorization" bearer)))

(defn- forward-response-headers [headers]
  ;; http-kit's client keywordizes header names and inflates compressed
  ;; bodies; content-encoding/length no longer describe the body we
  ;; return, so they're recomputed downstream.
  (->> headers
       (remove (fn [[k _]]
                 (contains? (into hop-by-hop #{"content-encoding" "content-length"})
                            (name k))))
       (map (fn [[k v]] [(name k) v]))
       (into {})))

(def ^:private framing-headers
  ;; X-Frame-Options / a CSP frame-ancestors from code-server or Clay
  ;; would stop the page being embedded in our same-origin workspace
  ;; iframes. We drop them on owner traffic only — the owner framing
  ;; their own VM in their own session, where clickjacking is meaningless
  ;; — so the public share view keeps its protection (see `forward`).
  #{"x-frame-options" "content-security-policy"})

(defn- strip-framing [headers]
  (into {} (remove (fn [[k _]] (contains? framing-headers (str/lower-case k))) headers)))

(defn- target-url [sprite-url path query-string]
  (str sprite-url "/" path (when query-string (str "?" query-string))))

;; ---------- Clay live-reload fixup ----------

(defn- editor-path? [path]
  ;; code-server lives under /edit/*; its traffic is left completely
  ;; untouched (not even buffered) — its own socket is already
  ;; same-origin and must not be rewritten.
  (str/starts-with? (str path) "edit"))

(defn- html-response? [headers]
  (some-> (get headers "content-type") str/lower-case (str/includes? "text/html")))

(defn- fix-clay-reload
  "Clay's live-reload page is written for a direct `localhost:<clay-port>`
   origin; served through our `/n/:id/view/` prefix several of its URLs point
   at the wrong place. Rewrite them to be same-origin / prefix-relative so
   they reach the sprite's Clay through our proxy:

     - the live-reload WebSocket (`ws://localhost:<port>`) -> same-origin
       wss/ws on the current path;
     - the multi-page fallback redirect -> the current path;
     - the `/counter` staleness poll and the `/Clay.svg.png` header logo,
       both root-absolute, -> page-relative so they resolve under the
       notebook prefix. (A 404 on `/counter` returns an empty body, which
       Clay then feeds to `JSON.parse` on every page load — and the dead
       poll silently disables the on-load staleness check that catches a
       save landing while the page is still loading.)

   Each targets Clay's exact snippet, so it's a no-op on any other page.
   Fixes both the owner workspace and the share view. Assumes the
   single-page notebook layout (the MVP); a multi-page book would need the
   notebook-root prefix, not a page-relative URL."
  [html]
  (-> html
      (str/replace "new WebSocket('ws://localhost:'+clay_port)"
                   "new WebSocket((location.protocol==='https:'?'wss:':'ws:')+'//'+location.host+location.pathname)")
      (str/replace "location.assign('http://localhost:'+clay_port)"
                   "location.assign(location.pathname)")
      (str/replace "fetch('/counter')" "fetch('counter')")
      (str/replace "\"/Clay.svg.png\"" "\"Clay.svg.png\"")))

;; ---------- plain HTTP ----------

(defn- wants-html?
  "True for a top-level document request (the iframe navigating), so we can
   answer a dead sprite with a styled, self-refreshing page rather than a
   bare line of text. Sub-resource and API requests get the text fallback."
  [req]
  (some-> (get-in req [:headers "accept"]) str/lower-case (str/includes? "text/html")))

(defn- waking-response [req]
  (if (wants-html? req)
    {:status 502 :headers {"content-type" "text/html; charset=utf-8"} :body (waking/render)}
    {:status 502 :headers {"content-type" "text/plain"}
     :body   "The notebook environment did not answer. It may still be waking up — try again."}))

(def ^:private proxy-http-keepalive-ms
  ;; http-kit's client pools keep-alive connections for 2 minutes by default.
  ;; An idle pooled connection to a sprite's URL still counts as activity, so it
  ;; would keep the sprite awake (and billing) for that whole window after the
  ;; browser's last request. A short reuse window keeps a page-load burst of
  ;; asset requests on one connection but lets it close — and the sprite suspend
  ;; — promptly once traffic stops. (WebSocket relays don't go through here.)
  15000)

(defn- forward-http [client sprite-url path req strip-framing?]
  (let [url  (target-url sprite-url path (:query-string req))
        {:keys [status headers body error]}
        @(http-client/request {:method           (:request-method req)
                               :url              url
                               :headers          (forward-request-headers req (sprites/bearer client))
                               :body             (:body req)
                               :as               :stream
                               :keepalive        proxy-http-keepalive-ms
                               :follow-redirects false})]
    (if error
      (do (log/warn error "sprite request failed" {:url url})
          (waking-response req))
      (let [resp-headers (cond-> (forward-response-headers headers)
                           strip-framing? strip-framing)]
        (if (and body (not (editor-path? path)) (html-response? resp-headers))
          {:status  status
           :headers resp-headers
           :body    (fix-clay-reload (slurp body :encoding "UTF-8"))}
          {:status  status
           :headers resp-headers
           :body    body})))))

;; ---------- idle-suspend support ----------

;; The workspace pauses itself client-side when you step away (the "Still
;; working?" veil tears the iframes down, closing their sockets). But that JS
;; can't run when the tab's machine is asleep or the browser has frozen it, so
;; a left-open tab keeps its editor / Clay live-reload WebSocket open — pinning
;; the sprite awake (and billing) indefinitely. As a server-side backstop the
;; proxy tracks the WebSocket relays it's holding, keyed by notebook id, along
;; with the last time each notebook sent a browser->sprite frame (a keystroke,
;; an eval, a save — real user activity). The scheduler's idle sweep
;; (hosted-clay.idle) reads that timestamp and, once a notebook has gone quiet
;; past the window, aborts each upstream socket to the sprite *directly* (and
;; closes the browser channel to tear the relay down). Aborting is the
;; load-bearing step — it's the connection Sprites counts as activity — so we do
;; it ourselves rather than closing the browser side and trusting :on-close to
;; fire the abort, whose timing is least predictable against exactly the
;; frozen/asleep tab this backstop is for. With its last inbound connection gone
;; the sprite idle-suspends. A frozen/asleep tab sends no frames, so it goes
;; stale and suspends; an actively-used tab keeps its timestamp fresh and never
;; does. Only WebSocket relays register; plain HTTP can't pin the sprite (it
;; drains on its own short keep-alive).
;;
;; Shape: notebook-id -> {:conns {browser-ch -> upstream-promise} :active-at <Instant>}.
;; The upstream is a promise because the connect is async relative to the
;; browser channel opening; a sweep skips one still in flight (see below).
(defonce ^:private relays (atom {}))

(defn- register! [notebook-id ch upstream]
  (when notebook-id
    (swap! relays update notebook-id
           (fn [entry]
             (-> (or entry {:conns {}})
                 (assoc-in [:conns ch] upstream)
                 (assoc :active-at (Instant/now)))))))

(defn- deregister! [notebook-id ch]
  (when notebook-id
    (swap! relays (fn [m]
                    (let [remaining (dissoc (get-in m [notebook-id :conns]) ch)]
                      (if (empty? remaining)
                        (dissoc m notebook-id)
                        (assoc-in m [notebook-id :conns] remaining)))))))

(defn- touch! [notebook-id]
  ;; Stamp a browser->sprite frame as activity. Only touches a notebook that's
  ;; still registered, so a frame racing with deregistration can't resurrect it.
  (when notebook-id
    (swap! relays (fn [m]
                    (if (contains? m notebook-id)
                      (assoc-in m [notebook-id :active-at] (Instant/now))
                      m)))))

(defn activity-snapshot
  "A point-in-time view of the notebooks the proxy is relaying a live WebSocket
   for, as notebook-id -> the Instant of the last browser->sprite frame (or of
   the connection opening). The idle sweep compares these against now; a notebook
   with no live relay isn't here and suspends on its own."
  []
  (update-vals @relays :active-at))

(defn disconnect-notebook!
  "Drop every relay we're holding for `notebook-id` so the sprite loses its
   inbound connections and idle-suspends. For each: abort the upstream sprite
   socket *directly* — the load-bearing step, done here rather than left to the
   browser close firing :on-close — then close the browser channel so the relay
   deregisters. An upstream whose connect is still in flight (promise not yet
   delivered) is skipped without blocking; its own :on-close aborts it once it
   resolves. `.abort` is idempotent, so a later :on-close re-abort is harmless.
   Idempotent overall; a no-op when nothing is held."
  [notebook-id]
  (doseq [[ch upstream] (get-in @relays [notebook-id :conns])]
    (when-let [ws (deref upstream 0 nil)]
      (.abort ^WebSocket ws))
    (http-server/close ch)))

;; ---------- WebSocket relay ----------

(defn- websocket-upgrade? [req]
  (some-> (get-in req [:headers "upgrade"]) str/lower-case (= "websocket")))

(defn- ws-url [sprite-url path query-string]
  (str/replace (target-url sprite-url path query-string) #"^http" "ws"))

(defn- downstream-listener
  "Pipes frames arriving from the sprite to the browser channel.
   java.net.http delivers messages in fragments; accumulate until the
   final fragment so the browser sees whole messages."
  [browser-ch]
  (let [text-buf (StringBuilder.)
        bin-buf  (java.io.ByteArrayOutputStream.)]
    (reify WebSocket$Listener
      (onOpen [_ ws]
        (.request ^WebSocket ws 1))
      (onText [_ ws data last?]
        (.append text-buf ^CharSequence data)
        (when last?
          (http-server/send! browser-ch (.toString text-buf))
          (.setLength text-buf 0))
        (.request ^WebSocket ws 1)
        nil)
      (onBinary [_ ws data last?]
        (let [^ByteBuffer data data
              chunk            (byte-array (.remaining data))]
          (.get data chunk)
          (.write bin-buf chunk))
        (when last?
          (http-server/send! browser-ch (.toByteArray bin-buf))
          (.reset bin-buf))
        (.request ^WebSocket ws 1)
        nil)
      (onClose [_ _ws _status _reason]
        (http-server/close browser-ch)
        nil)
      (onError [_ _ws t]
        (log/debug t "sprite websocket error")
        (http-server/close browser-ch)))))

(def ^:private upstream-connect-timeout-secs
  ;; The browser's HTTP request to the page already succeeded before any
  ;; WebSocket upgrade, so the sprite is awake and this connect is a fast
  ;; internal hop — seconds, not tens of seconds. Cap it tightly: a
  ;; browser-side socket that retries (code-server reconnecting every few
  ;; seconds) spawns one upstream connect per attempt, and a long timeout
  ;; lets failed attempts pile up and starve HTTP forwarding.
  10)

(defn- connect-upstream
  "Open the sprite-side WebSocket. Returns the WebSocket; throws on
   failure (which surfaces as the browser channel closing)."
  [client url subprotocol browser-ch]
  (let [builder (-> (HttpClient/newHttpClient)
                    (.newWebSocketBuilder)
                    (.header "Authorization" (sprites/bearer client)))
        builder (if subprotocol
                  (.subprotocols builder subprotocol (make-array String 0))
                  builder)]
    (-> builder
        (.buildAsync (URI/create url) (downstream-listener browser-ch))
        (.get upstream-connect-timeout-secs TimeUnit/SECONDS))))

(defn- relay-websocket [client sprite-url path req notebook-id]
  (let [url      (ws-url sprite-url path (:query-string req))
        ;; The upstream connect is async relative to the browser's
        ;; channel opening; park browser frames until it's up.
        upstream (promise)]
    (http-server/as-channel
     req
     {:on-open    (fn [ch]
                    ;; Track the channel + its upstream so the idle sweep can
                    ;; abort and close it if this tab goes quiet (see the relay
                    ;; registry above). `upstream` is delivered by the future
                    ;; below; storing the promise now lets the sweep abort it
                    ;; directly once it resolves.
                    (register! notebook-id ch upstream)
                    (future
                      (try
                        (deliver upstream
                                 (connect-upstream client url
                                                   (get-in req [:headers "sec-websocket-protocol"])
                                                   ch))
                        (catch Throwable t
                          (log/warn t "sprite websocket connect failed" {:url url})
                          (deliver upstream nil)
                          (http-server/close ch)))))
      :on-receive (fn [_ch msg]
                    ;; A browser->sprite frame is real user activity (a
                    ;; keystroke, an eval, a save); stamp it so the idle sweep
                    ;; sees a live tab and leaves the sprite awake.
                    (touch! notebook-id)
                    (when-let [^WebSocket ws @upstream]
                      (if (string? msg)
                        (.sendText ws ^String msg true)
                        (.sendBinary ws (ByteBuffer/wrap ^bytes msg) true))))
      :on-close   (fn [ch _status]
                    (deregister! notebook-id ch)
                    ;; The browser is gone. Drop the upstream connection to the
                    ;; sprite *hard* — `abort` closes the TCP socket at once,
                    ;; where a graceful `sendClose` only half-closes and can
                    ;; linger until the sprite echoes it. That matters for cost:
                    ;; Sprites treat "an open TCP connection to its URL" as
                    ;; activity, so a lingering upstream socket pins the sprite
                    ;; awake (and billing) indefinitely — which is exactly what
                    ;; the workspace's idle-suspend is trying to avoid. Run it
                    ;; off a future so we neither block http-kit's callback
                    ;; thread on `upstream` resolving nor miss a connect still
                    ;; in flight.
                    (future
                      (when-let [^WebSocket ws @upstream]
                        (.abort ws))))})))

;; ---------- entry point ----------

(defn forward
  "Forward `req` to `sprite-url`, replacing the request path with
   `path` (the wildcard remainder of the matched route). Detects and
   relays WebSocket upgrades. With `:strip-framing? true`, drops the
   response's framing headers so the page can be embedded in our
   same-origin workspace iframes — owner traffic only; the share view
   forwards without it so its clickjacking protection stands. With
   `:notebook-id`, a relayed WebSocket is tracked under that id so the idle
   sweep can reclaim it (see the relay registry)."
  ([client sprite-url path req] (forward client sprite-url path req nil))
  ([client sprite-url path req {:keys [strip-framing? notebook-id]}]
   (if (websocket-upgrade? req)
     (relay-websocket client sprite-url path req notebook-id)
     (forward-http client sprite-url path req strip-framing?))))
