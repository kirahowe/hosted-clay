# Data model

SQLite, one file on the control plane's volume. Ids are TEXT uuids;
timestamps are ISO-8601 TEXT (lexicographic order == chronological, so
the idle-cutoff queries are plain string comparisons).

## users

Identity within this product. `email` is unique (NOCASE): two providers
asserting the same verified address are the same person.

## identities

Links a user to an external auth provider: `(provider,
provider_subject)` unique. Hanko is the only provider today; adding
atProto/email-password later is a new `provider` value, not a schema
change. A returning email seen through a new provider gets a new
identity row linked to the existing user.

## notebooks

One row per live notebook. `user_id` is UNIQUE — the free-tier
one-notebook limit is a database constraint, not just app logic.
`share_token` (unguessable, unique) is the capability for the public
read-only view. `last_accessed_at` is bumped by owner traffic through
the proxy (throttled to once a minute); `warned_at` records the idle
warning and is cleared by any owner activity, so deletion requires
*warned and still idle past 30 days*.

## sprite_pool

Pre-warmed sprites not yet claimed: `state` is `provisioning` while
setup runs, then `ready`. Claiming is a transactional select+delete;
the row's data moves onto the new `notebooks` row. A crash
mid-provision leaves a visible `provisioning` row rather than an
invisible orphaned sprite.
