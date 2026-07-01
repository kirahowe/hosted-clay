(ns hosted-clay.handlers.auth
  (:require [integrant.core :as ig]
            [hosted-clay.routes :as routes]
            [hosted-clay.ui.pages.login :as login]
            [hosted-clay.web.response :as response]))

(defmethod ig/init-key :hosted-clay.handlers.auth/login [_ {:keys [api-url]}]
  (fn [_req]
    (response/html (login/render api-url))))

(defmethod ig/init-key :hosted-clay.handlers.auth/logout [_ _]
  (fn [_req]
    ;; Clear the session cookie locally and bounce home. The cookie is
    ;; what our app reads, so dropping it logs the user out here;
    ;; revoking the Hanko session itself can come later via the
    ;; frontend SDK if needed.
    (response/expire-cookie (response/see-other (routes/home)) "hanko")))
