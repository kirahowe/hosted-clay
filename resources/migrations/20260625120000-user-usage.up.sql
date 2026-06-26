-- Move monthly active-hours metering off the notebooks table and onto the user.
-- Stored per-notebook, a user could reset their monthly usage simply by deleting
-- and recreating their notebook (the accrued total died with the row). Usage now
-- lives in its own table keyed by (user_id, month), so it survives notebook
-- deletion and is cumulative across all of a user's notebooks in a month.
--
-- One row per user per UTC 'YYYY-MM'. awake_seconds is the running total the
-- census accrues; warned_at marks that the near-the-limit email went out for that
-- month. A new month is simply a new row, so the monthly reset is implicit (the
-- current-month lookup just finds nothing).
CREATE TABLE user_usage (
  id            TEXT PRIMARY KEY,
  user_id       TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  usage_month   TEXT NOT NULL,
  awake_seconds INTEGER NOT NULL DEFAULT 0,
  warned_at     TEXT,
  created_at    TEXT NOT NULL,
  UNIQUE (user_id, usage_month)
);
--;;
-- Carry the current month's accrued usage forward so this migration doesn't hand
-- everyone a one-time free reset. Earlier months are intentionally left behind —
-- they no longer count toward any live budget. id is an opaque unique string;
-- created_at borrows the notebook's so the row has a sane timestamp.
INSERT INTO user_usage (id, user_id, usage_month, awake_seconds, warned_at, created_at)
SELECT lower(hex(randomblob(16))), user_id, usage_month, awake_seconds, usage_warned_at, created_at
FROM notebooks
WHERE usage_month = strftime('%Y-%m', 'now')
  AND awake_seconds > 0;
--;;
ALTER TABLE notebooks DROP COLUMN usage_warned_at;
--;;
ALTER TABLE notebooks DROP COLUMN awake_seconds;
--;;
ALTER TABLE notebooks DROP COLUMN usage_month;
