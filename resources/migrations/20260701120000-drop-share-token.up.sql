-- Drop the now-redundant share_token column. Notebook ids are random UUIDs,
-- so the public share view keys on the notebook id directly (/s/:id) instead
-- of a separate unguessable token — one identifier, not two.
--
-- SQLite can't DROP a UNIQUE column (it's backed by an implicit index), so we
-- rebuild the table. DROP TABLE fires notebook_snapshots' ON DELETE CASCADE
-- (foreign keys are enforced — see the JDBC url), which would take those rows
-- down with the old table, so we stash them into a scratch table first and
-- restore them after the rebuild: every id is copied across unchanged, so each
-- snapshot's notebook_id still resolves. Runs in migratus's default (single
-- connection) transaction, so it's all-or-nothing. We deliberately do NOT
-- turn foreign keys off with a PRAGMA: that PRAGMA is a no-op inside a
-- transaction, and running the migration with transactions disabled is worse
-- (migratus then executes each statement on its own fresh connection, which
-- re-enables foreign keys from the url, so it never reaches the DROP). The
-- stash/restore is what actually keeps the snapshots safe.
CREATE TABLE notebooks_new (
  id               TEXT PRIMARY KEY,
  user_id          TEXT NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
  title            TEXT NOT NULL,
  sprite_name      TEXT NOT NULL UNIQUE,
  sprite_url       TEXT NOT NULL,
  created_at       TEXT NOT NULL,
  last_accessed_at TEXT NOT NULL,
  warned_at        TEXT,
  status           TEXT NOT NULL DEFAULT 'ready',
  suspended_at     TEXT
);
--;;
INSERT INTO notebooks_new
  (id, user_id, title, sprite_name, sprite_url, created_at, last_accessed_at, warned_at, status, suspended_at)
  SELECT id, user_id, title, sprite_name, sprite_url, created_at, last_accessed_at, warned_at, status, suspended_at
  FROM notebooks;
--;;
CREATE TEMP TABLE notebook_snapshots_backup (
  id          TEXT,
  notebook_id TEXT,
  source      TEXT,
  html        TEXT,
  captured_at TEXT,
  created_at  TEXT
);
--;;
INSERT INTO notebook_snapshots_backup
  (id, notebook_id, source, html, captured_at, created_at)
  SELECT id, notebook_id, source, html, captured_at, created_at FROM notebook_snapshots;
--;;
DROP TABLE notebooks;
--;;
ALTER TABLE notebooks_new RENAME TO notebooks;
--;;
INSERT INTO notebook_snapshots
  (id, notebook_id, source, html, captured_at, created_at)
  SELECT id, notebook_id, source, html, captured_at, created_at FROM notebook_snapshots_backup;
--;;
DROP TABLE notebook_snapshots_backup;
