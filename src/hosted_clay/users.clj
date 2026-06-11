(ns hosted-clay.users
  "User domain logic. Users are our product-level identity; identities
   are links to external auth providers (Hanko today). Provisioning from
   a verified identity finds-or-creates along both axes so adding a
   provider later needs no migration."
  (:require [next.jdbc :as jdbc]
            [hosted-clay.db.crud :as crud]))

(defn by-id [ds id]
  (crud/by-id ds :users id))

(defn- by-identity [ds provider subject]
  (when-let [identity-row (crud/find-1 ds :identities {:provider         provider
                                                       :provider-subject subject})]
    (crud/by-id ds :users (:identities/user-id identity-row))))

(defn- create-with-identity!
  "Provision a new user and its identity row in one transaction."
  [ds {:keys [provider provider-subject email]}]
  (jdbc/with-transaction [tx ds]
    (let [user (crud/create! tx :users {:email email})]
      (crud/create! tx :identities {:user-id          (:users/id user)
                                    :provider         provider
                                    :provider-subject provider-subject
                                    :email            email})
      user)))

(defn- link-identity!
  "Attach a new provider identity to an existing user (same verified
   email seen through a new provider, or a Hanko account re-created
   under a new subject)."
  [ds user {:keys [provider provider-subject email]}]
  (crud/create! ds :identities {:user-id          (:users/id user)
                                :provider         provider
                                :provider-subject provider-subject
                                :email            email})
  user)

(defn provision!
  "The local user for verified identity `attrs` ({:provider
   :provider-subject :email}): an existing user found by identity, an
   existing user found by email (gaining a new linked identity), or a
   freshly created one. Two concurrent first requests can race
   create-with-identity!; the loser hits the unique email constraint —
   recover by re-resolving, so the race admits the user instead of
   500ing. Any other SQL failure is genuine and rethrows."
  [ds {:keys [provider provider-subject email] :as attrs}]
  (or (by-identity ds provider provider-subject)
      (when-let [user (and email (crud/find-1 ds :users {:email email}))]
        (link-identity! ds user attrs))
      (try
        (create-with-identity! ds attrs)
        (catch java.sql.SQLException e
          (or (by-identity ds provider provider-subject)
              (when-let [user (and email (crud/find-1 ds :users {:email email}))]
                (link-identity! ds user attrs))
              (throw e))))))
