(ns hosted-clay.notebooks
  "Notebook domain logic: creating (claiming a warm sprite or
   provisioning one on the spot), sharing, activity tracking, and
   deletion."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [hosted-clay.db.crud :as crud]
            [hosted-clay.pool :as pool]
            [hosted-clay.snapshot :as snapshot]
            [hosted-clay.sprites.client :as sprites]
            [hosted-clay.sprites.provision :as provision])
  (:import (java.time Duration Instant)))

(defn for-user [ds user-id]
  (crud/find-1 ds :notebooks {:user-id user-id}))

(defn by-id [ds id]
  (when id (crud/by-id ds :notebooks id)))

(defn new-share-token
  "An unguessable, URL-safe share token (128 bits, base32)."
  []
  (.toString (java.math.BigInteger. 128 (java.security.SecureRandom.)) 32))

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
  [ds client {:keys [max-sprites sprite-tag]} user-id title]
  (if (for-user ds user-id)
    ::already-exists
    (if-let [{:keys [sprite-name sprite-url]} (pool/claim! ds)]
      (insert-notebook! ds client user-id title
                        {:sprite-name sprite-name
                         :sprite-url  sprite-url
                         :status      "ready"}
                        sprite-name)
      (let [held (pool/sprite-count ds)]
        (when (>= held max-sprites)
          (log/warn "sprite budget cap reached; refusing new notebook"
                    {:held held :max-sprites max-sprites})
          (throw (ex-info "sprite budget cap reached" {:type ::budget-exceeded})))
        ;; The sprite-url is filled by finish-provisioning!; it's never read
        ;; while the row is still 'provisioning' (the proxy and workspace
        ;; both gate on the ready state), so an empty placeholder is safe.
        (insert-notebook! ds client user-id title
                          {:sprite-name (pool/new-sprite-name sprite-tag)
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

(defn reconcile-provisioning!
  "Recover notebooks stranded mid-provision. `finish-provisioning!` runs on a
   background thread; if the process dies before it finishes (a kill, crash, or
   deploy mid-build) that thread dies with it and nothing ever flips the row out
   of 'provisioning' — so the workspace page polls a status that never changes
   and spins forever, with no recovery (the Retry path only fires on 'failed').
   The app is single-instance (one local SQLite file), so at startup no build is
   in flight: every 'provisioning' row is necessarily orphaned. Reset each to
   'failed' — the same state a real provisioning failure lands in — so the owner
   gets the error page and its Retry button, which rebuilds the sprite and
   cleans any half-built one via finish-provisioning!'s own failure handling.
   Returns the rows it reset. Meant to run once at startup."
  [ds]
  (let [stranded (crud/find-many ds :notebooks {:status "provisioning"})]
    (doseq [nb stranded]
      (crud/update! ds :notebooks (:notebooks/id nb) {:status "failed"})
      (log/warn "reset notebook stranded mid-provision to failed"
                {:notebook-id (:notebooks/id nb)}))
    (when (seq stranded)
      (log/info "reconciled stranded provisioning notebooks" {:count (count stranded)}))
    stranded))

(defn delete!
  "Delete a notebook, its sprite, and its stored snapshot files. The sprite goes
   first (and `delete-sprite!` retries a transient 5xx and tolerates a 404): if
   it still fails the row survives so a re-delete can finish the job, whereas a
   dangling sprite with no row would leak money invisibly. The row delete
   cascades the snapshot row; the snapshot *files* on the volume have no such
   cascade, so we remove them explicitly (best-effort). Safe to call twice for
   the same notebook — the sprite delete is 404-tolerant, the row delete is a
   0-row no-op, and the file delete tolerates already-gone — so the
   two-tabs/double-submit race converges instead of erroring."
  [ds client snapshots-dir notebook]
  (sprites/delete-sprite! client (:notebooks/sprite-name notebook))
  (crud/delete! ds :notebooks (:notebooks/id notebook))
  (snapshot/delete-files! snapshots-dir (:notebooks/id notebook))
  (log/info "notebook deleted" {:notebook-id (:notebooks/id notebook)}))

(defn suspended?
  "True when the owner has manually suspended this notebook (distinct from the
   automatic monthly usage pause)."
  [notebook]
  (some? (:notebooks/suspended-at notebook)))

(defn suspend!
  "Manually suspend a notebook: the proxy stops forwarding, so the sprite gets
   no traffic and idle-suspends until the owner resumes. Suspending is a
   deliberate keep-this act, so it counts as activity — it resets the 30-day
   idle-deletion clock (and clears any pending warning), so a parked notebook
   isn't swept out from under the owner."
  [ds notebook]
  (crud/update! ds :notebooks (:notebooks/id notebook)
                {:suspended-at (crud/now) :last-accessed-at (crud/now) :warned-at nil}))

(defn resume!
  "Clear a manual suspend and reset the activity clock, so the next request
   wakes the sprite and the idle-deletion sweep sees it as freshly used."
  [ds notebook]
  (crud/update! ds :notebooks (:notebooks/id notebook)
                {:suspended-at nil :last-accessed-at (crud/now) :warned-at nil}))

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
