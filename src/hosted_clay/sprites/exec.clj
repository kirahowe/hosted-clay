(ns hosted-clay.sprites.exec
  "Run a command inside a sprite over the exec WebSocket.

   Protocol (matches the official SDKs): the command and its arguments
   go in the URL as repeated `cmd` query params; binary frames carry a
   one-byte stream id prefix — 0 stdin, 1 stdout, 2 stderr, 3 exit
   (payload byte 0 is the exit code), 4 stdin-EOF.

   Built on java.net.http's WebSocket so the control plane needs no
   extra dependency. Used only for provisioning (low concurrency, big
   timeouts), not on any request path."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [hosted-clay.sprites.client :as client])
  (:import (java.io ByteArrayOutputStream)
           (java.net URI URLEncoder)
           (java.net.http HttpClient WebSocket WebSocket$Listener)
           (java.nio ByteBuffer)
           (java.nio.charset StandardCharsets)
           (java.time Duration)
           (java.util.concurrent CompletableFuture TimeUnit TimeoutException)))

(def ^:private stream-stdin (byte 0))
(def ^:private stream-stdout (byte 1))
(def ^:private stream-stderr (byte 2))
(def ^:private stream-exit (byte 3))
(def ^:private stream-stdin-eof (byte 4))

(defn- url-encode [s]
  (URLEncoder/encode (str s) StandardCharsets/UTF_8))

(defn exec-url
  "The wss:// exec URL for running `cmd` (a vector of program + args) in
   `sprite-name`. `stdin?` tells the server whether to expect input."
  [api-url sprite-name cmd stdin?]
  (let [ws-base (str/replace api-url #"^http" "ws")
        params  (concat (map #(str "cmd=" (url-encode %)) cmd)
                        [(str "path=" (url-encode (first cmd)))
                         (str "stdin=" (if stdin? "true" "false"))])]
    (str ws-base "/v1/sprites/" sprite-name "/exec?" (str/join "&" params))))

(defn- frame
  "A binary frame: stream id byte followed by `payload` bytes."
  [stream-id ^bytes payload]
  (let [buf (ByteBuffer/allocate (inc (alength payload)))]
    (.put buf ^byte stream-id)
    (.put buf payload)
    (.flip buf)
    buf))

(defn- listener
  "A WebSocket$Listener that demultiplexes stream frames into `out`/`err`
   and completes `result` with the exit code. WebSocket messages can
   arrive fragmented; the stream id byte is on the first fragment, so
   fragments accumulate in `pending` until `last?`."
  [^ByteArrayOutputStream out ^ByteArrayOutputStream err ^CompletableFuture result]
  (let [pending (ByteArrayOutputStream.)]
    (reify WebSocket$Listener
      (onOpen [_ ws]
        (.request ^WebSocket ws 1))
      (onBinary [_ ws data last?]
        (let [^ByteBuffer data data
              chunk            (byte-array (.remaining data))]
          (.get data chunk)
          (.write pending chunk))
        (when last?
          (let [msg (.toByteArray pending)]
            (.reset pending)
            (when (pos? (alength msg))
              (let [stream-id (aget msg 0)
                    payload   (java.util.Arrays/copyOfRange msg 1 (alength msg))]
                (condp = stream-id
                  stream-stdout (.write out payload)
                  stream-stderr (.write err payload)
                  stream-exit   (.complete result
                                           (if (pos? (alength payload))
                                             (int (aget payload 0))
                                             0))
                  (log/debug "ignoring exec frame" {:stream stream-id}))))))
        (.request ^WebSocket ws 1)
        nil)
      (onText [_ ws _data _last?]
        ;; Text messages are server-side session metadata; irrelevant to
        ;; a non-interactive run.
        (.request ^WebSocket ws 1)
        nil)
      (onClose [_ _ws _status _reason]
        ;; A close without an exit frame means the command was cut short.
        (.completeExceptionally result (ex-info "exec socket closed before exit" {}))
        nil)
      (onError [_ _ws throwable]
        (.completeExceptionally result throwable)))))

(defn exec!
  "Run `cmd` (vector of program + args) inside `sprite-name`, optionally
   feeding `stdin` (a string), and wait for it to finish. Returns
   {:exit int :out string :err string}; throws on transport failure or
   `timeout-ms` (default 10 minutes) elapsing."
  [client sprite-name cmd & {:keys [stdin timeout-ms] :or {timeout-ms 600000}}]
  (let [out    (ByteArrayOutputStream.)
        err    (ByteArrayOutputStream.)
        result (CompletableFuture.)
        url    (exec-url (:api-url client) sprite-name cmd (some? stdin))
        ws     (-> (HttpClient/newHttpClient)
                   (.newWebSocketBuilder)
                   (.header "Authorization" (client/bearer client))
                   (.connectTimeout (Duration/ofSeconds 30))
                   (.buildAsync (URI/create url)
                                (listener out err result))
                   (.get 60 TimeUnit/SECONDS))]
    (try
      (when stdin
        (-> ^WebSocket ws
            (.sendBinary (frame stream-stdin (.getBytes ^String stdin StandardCharsets/UTF_8)) true)
            (.get 30 TimeUnit/SECONDS))
        (-> ^WebSocket ws
            (.sendBinary (frame stream-stdin-eof (byte-array 0)) true)
            (.get 30 TimeUnit/SECONDS)))
      (let [exit (.get result timeout-ms TimeUnit/MILLISECONDS)]
        {:exit exit
         :out  (.toString out StandardCharsets/UTF_8)
         :err  (.toString err StandardCharsets/UTF_8)})
      (catch TimeoutException _
        (throw (ex-info "exec timed out"
                        {:sprite sprite-name :cmd cmd :timeout-ms timeout-ms})))
      (finally
        (.abort ^WebSocket ws)))))
