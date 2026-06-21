(ns hosted-clay.email
  "Outbound email. The component is a function of one message map
   ({:to :subject :text}); the only sender the MVP needs is Resend's
   HTTP API. With no API key configured (dev, test) the component logs
   the message instead of sending, so the deletion-warning flow is
   exercisable everywhere."
  (:require [charred.api :as json]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [org.httpkit.client :as http]))

(defn- resend-request
  "The http-kit request map for one Resend send. Pure, so the request
   shape can be tested without the wire."
  [{:keys [api-key from]} {:keys [to subject text]}]
  {:method  :post
   :url     "https://api.resend.com/emails"
   :headers {"Authorization" (str "Bearer " (:value api-key))
             "Content-Type"  "application/json"}
   :body    (json/write-json-str {:from from :to [to] :subject subject :text text})
   :as      :text})

(defn- send-via-resend! [config {:keys [to subject] :as msg}]
  (let [{:keys [status body error]} @(http/request (resend-request config msg))]
    ;; Throw on failure rather than swallowing it: the caller (lifecycle)
    ;; only records that a warning was sent if this returns, so a failed
    ;; send is retried on the next sweep instead of silently marking the
    ;; notebook warned and deleting it unannounced.
    (when (or error (not (<= 200 status 299)))
      (log/error error "email send failed" {:to to :status status :body body})
      (throw (ex-info "email send failed" {:to to :status status})))
    (log/info "email sent" {:to to :subject subject})))

(defmethod ig/init-key :hosted-clay/email [_ {:keys [api-key from] :as config}]
  (if (some-> api-key :value)
    (do (log/info "email sender: resend" {:from from})
        (partial send-via-resend! config))
    (do (log/warn "email sender: log only — no RESEND_API_KEY set;"
                  "deletion-warning emails will NOT be delivered")
        (fn [{:keys [to subject]}]
          (log/info "email (not sent, no api key)" {:to to :subject subject})))))
