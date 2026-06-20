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
            [hosted-clay.sprites.client :as sprites])
  (:import (java.net URI)
           (java.net.http HttpClient WebSocket WebSocket$Listener)
           (java.nio ByteBuffer)
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
  "Clay bakes its own host:port into the live-reload page
   (`ws://localhost:<clay-port>`, a port that is never our origin), so
   the browser would dial its own machine instead of reaching the sprite
   through us. Rewrite the socket (and the multi-page fallback redirect)
   to be same-origin so our relay carries it to the sprite's Clay. These
   target Clay's exact snippet, so they're a no-op on any other page.
   Fixes both the owner workspace and the share view."
  [html]
  (-> html
      (str/replace "new WebSocket('ws://localhost:'+clay_port)"
                   "new WebSocket((location.protocol==='https:'?'wss:':'ws:')+'//'+location.host+location.pathname)")
      (str/replace "location.assign('http://localhost:'+clay_port)"
                   "location.assign(location.pathname)")))

;; ---------- plain HTTP ----------

(defn- forward-http [client sprite-url path req strip-framing?]
  (let [url  (target-url sprite-url path (:query-string req))
        {:keys [status headers body error]}
        @(http-client/request {:method           (:request-method req)
                               :url              url
                               :headers          (forward-request-headers req (sprites/bearer client))
                               :body             (:body req)
                               :as               :stream
                               :follow-redirects false})]
    (if error
      (do (log/warn error "sprite request failed" {:url url})
          {:status  502
           :headers {"content-type" "text/plain"}
           :body    "The notebook environment did not answer. It may still be waking up — try again."})
      (let [resp-headers (cond-> (forward-response-headers headers)
                           strip-framing? strip-framing)]
        (if (and body (not (editor-path? path)) (html-response? resp-headers))
          {:status  status
           :headers resp-headers
           :body    (fix-clay-reload (slurp body :encoding "UTF-8"))}
          {:status  status
           :headers resp-headers
           :body    body})))))

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
        (.get 30 TimeUnit/SECONDS))))

(defn- relay-websocket [client sprite-url path req]
  (let [url      (ws-url sprite-url path (:query-string req))
        ;; The upstream connect is async relative to the browser's
        ;; channel opening; park browser frames until it's up.
        upstream (promise)]
    (http-server/as-channel
     req
     {:on-open    (fn [ch]
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
                    (when-let [^WebSocket ws @upstream]
                      (if (string? msg)
                        (.sendText ws ^String msg true)
                        (.sendBinary ws (ByteBuffer/wrap ^bytes msg) true))))
      :on-close   (fn [_ch _status]
                    (when-let [^WebSocket ws @upstream]
                      (.sendClose ws WebSocket/NORMAL_CLOSURE "")))})))

;; ---------- entry point ----------

(defn forward
  "Forward `req` to `sprite-url`, replacing the request path with
   `path` (the wildcard remainder of the matched route). Detects and
   relays WebSocket upgrades. With `:strip-framing? true`, drops the
   response's framing headers so the page can be embedded in our
   same-origin workspace iframes — owner traffic only; the share view
   forwards without it so its clickjacking protection stands."
  ([client sprite-url path req] (forward client sprite-url path req nil))
  ([client sprite-url path req {:keys [strip-framing?]}]
   (if (websocket-upgrade? req)
     (relay-websocket client sprite-url path req)
     (forward-http client sprite-url path req strip-framing?))))
