(ns hosted-clay.notebooks
  "Notebook domain logic: creating (claiming a warm sprite or
   provisioning one on the spot), sharing, activity tracking, and
   deletion."
  (:require [clojure.tools.logging :as log]
            [hosted-clay.db.crud :as crud]
            [hosted-clay.pool :as pool]
            [hosted-clay.sprites.client :as sprites]
            [hosted-clay.sprites.provision :as provision])
  (:import (java.time Duration Instant)))

(defn new-share-token
  "An unguessable, URL-safe share token (128 bits, base32)."
  []
  (.toString (java.math.BigInteger. 128 (java.security.SecureRandom.)) 32))

(defn for-user [ds user-id]
  (crud/find-1 ds :notebooks {:user-id user-id}))

(defn by-id [ds id]
  (when id (crud/by-id ds :notebooks id)))

(defn by-share-token [ds token]
  (when token (crud/find-1 ds :notebooks {:share-token token})))

(defn owned-by? [notebook user-id]
  (= (:notebooks/user-id notebook) user-id))

(defn- acquire-sprite!
  "A provisioned sprite for a new notebook: the warm pool's if one is
   ready (instant), otherwise created and provisioned inline (slow path,
   minutes — the user sees a spinner, not a failure). The budget cap is
   only checked on the slow path; a pool sprite is already paid for."
  [ds client {:keys [max-sprites]}]
  (or (pool/claim! ds)
      (do
        (when (>= (pool/sprite-count ds) max-sprites)
          (throw (ex-info "sprite budget cap reached" {:type ::budget-exceeded})))
        (let [sprite-name (pool/new-sprite-name)
              sprite      (sprites/create-sprite! client sprite-name)]
          (try
            (provision/provision! client sprite-name)
            (catch Throwable t
              (sprites/delete-sprite! client sprite-name)
              (throw t)))
          {:sprite-name sprite-name
           :sprite-url  (:url sprite)}))))

(defn create!
  "Create the user's notebook. Returns the new row, or ::already-exists
   when the free-tier one-notebook limit is hit (also enforced by the
   UNIQUE index on notebooks.user_id, which this maps to the same
   result under a race)."
  [ds client limits user-id title]
  (if (for-user ds user-id)
    ::already-exists
    (let [{:keys [sprite-name sprite-url]} (acquire-sprite! ds client limits)]
      (try
        (crud/create! ds :notebooks {:user-id          user-id
                                     :title            title
                                     :sprite-name      sprite-name
                                     :sprite-url       sprite-url
                                     :share-token      (new-share-token)
                                     :last-accessed-at (crud/now)})
        (catch java.sql.SQLException e
          ;; Lost a race with another create from the same user: release
          ;; the sprite we acquired and report the existing notebook.
          (if (for-user ds user-id)
            (do (sprites/delete-sprite! client sprite-name)
                ::already-exists)
            (throw e)))))))

(defn delete!
  "Delete a notebook and its sprite. The sprite goes first: if that
   fails the row survives and the reaper or a retry can finish the job,
   whereas a dangling sprite with no row would leak money invisibly."
  [ds client notebook]
  (sprites/delete-sprite! client (:notebooks/sprite-name notebook))
  (crud/delete! ds :notebooks (:notebooks/id notebook))
  (log/info "notebook deleted" {:notebook-id (:notebooks/id notebook)}))

(def ^:private touch-granularity (Duration/ofSeconds 60))

(defn touch!
  "Record owner activity on a notebook. Writes at most once per minute
   so the proxy path isn't a write-per-request, and clears any pending
   deletion warning — activity resets the 30-day clock."
  [ds notebook]
  (let [stale-before (str (.minus (Instant/now) touch-granularity))]
    (crud/update-where! ds :notebooks
                        [:and
                         [:= :id (:notebooks/id notebook)]
                         [:< :last-accessed-at stale-before]]
                        {:last-accessed-at (crud/now)
                         :warned-at        nil})))
