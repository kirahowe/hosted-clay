-- Reverse the drop: re-add share_token (NOT NULL UNIQUE), backfilled from the
-- notebook id — the original random tokens are gone, so the id (also unique
-- and non-null) is a valid stand-in. Rebuild for the same reasons as the up
-- migration (can't ALTER-ADD a UNIQUE column), and stash/restore the
-- notebook_snapshots rows so the DROP TABLE cascade doesn't take them — see the
-- up migration for why a foreign_keys PRAGMA can't do that job here.
CREATE TABLE notebooks_new (
  id               TEXT PRIMARY KEY,
  user_id          TEXT NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
  title            TEXT NOT NULL,
  sprite_name      TEXT NOT NULL UNIQUE,
  sprite_url       TEXT NOT NULL,
  share_token      TEXT NOT NULL UNIQUE,
  created_at       TEXT NOT NULL,
  last_accessed_at TEXT NOT NULL,
  warned_at        TEXT,
  status           TEXT NOT NULL DEFAULT 'ready',
  suspended_at     TEXT
);
--;;
INSERT INTO notebooks_new
  (id, user_id, title, sprite_name, sprite_url, share_token, created_at, last_accessed_at, warned_at, status, suspended_at)
  SELECT id, user_id, title, sprite_name, sprite_url, id, created_at, last_accessed_at, warned_at, status, suspended_at
  FROM notebooks;
--;;
CREATE TABLE notebook_snapshots_backup (
  id          TEXT,
  notebook_id TEXT,
  source      TEXT,
  html        TEXT,
  captured_at TEXT,
  created_at  TEXT
);
--;;
INSERT INTO notebook_snapshots_backup SELECT * FROM notebook_snapshots;
--;;
DROP TABLE notebooks;
--;;
ALTER TABLE notebooks_new RENAME TO notebooks;
--;;
INSERT INTO notebook_snapshots SELECT * FROM notebook_snapshots_backup;
--;;
DROP TABLE notebook_snapshots_backup;
