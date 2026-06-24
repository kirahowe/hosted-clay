(ns hosted-clay.notebooks
  "Notebook domain logic: creating (claiming a warm sprite or
   provisioning one on the spot), sharing, activity tracking, and
   deletion."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
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

(defn- insert-notebook!
  "Insert a notebook row, returning it; on a lost race with a concurrent
   create from the same user (the UNIQUE on user_id) release any
   already-claimed sprite and report ::already-exists instead."
  [ds client user-id title attrs claimed-sprite-name]
  (try
    (crud/create! ds :notebooks
                  (merge {:user-id          user-id
                          :title            title
                          :share-token      (new-share-token)
                          :last-accessed-at (crud/now)}
                         attrs))
    (catch java.sql.SQLException e
      ;; Only a UNIQUE violation (the one-per-user index) means "already
      ;; exists" — any other SQL error propagates rather than being mistaken
      ;; for a race and silently dropping the user's claimed sprite.
      (if (and (str/includes? (.getMessage e) "UNIQUE")
               (for-user ds user-id))
        (do (when claimed-sprite-name
              (sprites/delete-sprite! client claimed-sprite-name))
            ::already-exists)
        (throw e)))))

(defn create!
  "Create the user's notebook and return its row (or ::already-exists for
   the one-per-user limit). A warm-pool hit yields a ready notebook
   immediately; an empty pool yields a 'provisioning' row with no sprite
   yet — the caller runs `finish-provisioning!` (in the background) to
   build it. Throws ::budget-exceeded when the slow path would push past
   `max-sprites`."
  [ds client {:keys [max-sprites]} user-id title]
  (if (for-user ds user-id)
    ::already-exists
    (if-let [{:keys [sprite-name sprite-url]} (pool/claim! ds)]
      (insert-notebook! ds client user-id title
                        {:sprite-name sprite-name
                         :sprite-url  sprite-url
                         :status      "ready"}
                        sprite-name)
      (do
        (when (>= (pool/sprite-count ds) max-sprites)
          (throw (ex-info "sprite budget cap reached" {:type ::budget-exceeded})))
        ;; The sprite-url is filled by finish-provisioning!; it's never read
        ;; while the row is still 'provisioning' (the proxy and workspace
        ;; both gate on the ready state), so an empty placeholder is safe.
        (insert-notebook! ds client user-id title
                          {:sprite-name (pool/new-sprite-name)
                           :sprite-url  ""
                           :status      "provisioning"}
                          nil)))))

(defn finish-provisioning!
  "Build the sprite for a notebook begun in the 'provisioning' state, then
   mark it ready. On failure mark it 'failed' and delete any half-built
   sprite, leaving a row the owner can retry or delete. Meant to run on a
   background thread; it never throws."
  [ds client notebook]
  (let [id          (:notebooks/id notebook)
        sprite-name (:notebooks/sprite-name notebook)]
    (try
      (let [sprite (sprites/create-sprite! client sprite-name)]
        (provision/provision! client sprite-name)
        (crud/update! ds :notebooks id {:sprite-url (:url sprite) :status "ready"})
        (log/info "notebook provisioned" {:notebook-id id}))
      (catch Throwable t
        (log/error t "notebook provisioning failed" {:notebook-id id})
        (try (sprites/delete-sprite! client sprite-name) (catch Throwable _))
        (crud/update! ds :notebooks id {:status "failed"})))))

(defn retry-provisioning!
  "Move a failed notebook back to 'provisioning' and return the fresh row,
   so the caller can run `finish-provisioning!` again."
  [ds notebook]
  (crud/update! ds :notebooks (:notebooks/id notebook) {:status "provisioning"}))

(defn delete!
  "Delete a notebook and its sprite. The sprite goes first (and
   `delete-sprite!` retries a transient 5xx and tolerates a 404): if it
   still fails the row survives so a re-delete can finish the job, whereas a
   dangling sprite with no row would leak money invisibly. Safe to call
   twice for the same notebook — the sprite delete is 404-tolerant and the
   row delete is a 0-row no-op — so the two-tabs/double-submit race converges
   instead of erroring."
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
