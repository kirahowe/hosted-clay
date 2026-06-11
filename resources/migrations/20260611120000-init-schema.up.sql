-- Users are identity within this product; identities link a user to an
-- external auth provider. Adding a provider later (atProto, email/password)
-- is a new `provider` value, not a migration.
CREATE TABLE users (
  id           TEXT PRIMARY KEY,
  email        TEXT NOT NULL UNIQUE COLLATE NOCASE,
  display_name TEXT,
  created_at   TEXT NOT NULL
);
--;;
CREATE TABLE identities (
  id               TEXT PRIMARY KEY,
  user_id          TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  provider         TEXT NOT NULL,
  provider_subject TEXT NOT NULL,
  email            TEXT COLLATE NOCASE,
  created_at       TEXT NOT NULL,
  UNIQUE (provider, provider_subject)
);
--;;
CREATE INDEX identities_user_id ON identities(user_id);
--;;
-- One notebook per user is the free tier; the UNIQUE on user_id enforces it
-- in the database, not just in application code. Relaxing it for a paid tier
-- is a future migration.
CREATE TABLE notebooks (
  id               TEXT PRIMARY KEY,
  user_id          TEXT NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
  title            TEXT NOT NULL,
  sprite_name      TEXT NOT NULL UNIQUE,
  sprite_url       TEXT NOT NULL,
  share_token      TEXT NOT NULL UNIQUE,
  created_at       TEXT NOT NULL,
  last_accessed_at TEXT NOT NULL,
  warned_at        TEXT
);
--;;
-- Pre-warmed sprites waiting to be claimed as notebooks. `state` is
-- 'provisioning' while the setup script runs, 'ready' once claimable.
CREATE TABLE sprite_pool (
  id          TEXT PRIMARY KEY,
  sprite_name TEXT NOT NULL UNIQUE,
  sprite_url  TEXT NOT NULL,
  state       TEXT NOT NULL DEFAULT 'provisioning',
  created_at  TEXT NOT NULL
);
