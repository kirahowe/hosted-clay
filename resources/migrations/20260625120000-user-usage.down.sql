-- Put the per-notebook usage columns back, then restore the current month's
-- usage onto each notebook (one notebook per user, so a user's row maps to its
-- single notebook). Earlier months are dropped with the table.
ALTER TABLE notebooks ADD COLUMN usage_month TEXT;
--;;
ALTER TABLE notebooks ADD COLUMN awake_seconds INTEGER NOT NULL DEFAULT 0;
--;;
ALTER TABLE notebooks ADD COLUMN usage_warned_at TEXT;
--;;
UPDATE notebooks
SET usage_month     = (SELECT u.usage_month   FROM user_usage u
                       WHERE u.user_id = notebooks.user_id AND u.usage_month = strftime('%Y-%m', 'now')),
    awake_seconds   = COALESCE((SELECT u.awake_seconds FROM user_usage u
                       WHERE u.user_id = notebooks.user_id AND u.usage_month = strftime('%Y-%m', 'now')), 0),
    usage_warned_at = (SELECT u.warned_at     FROM user_usage u
                       WHERE u.user_id = notebooks.user_id AND u.usage_month = strftime('%Y-%m', 'now'))
WHERE EXISTS (SELECT 1 FROM user_usage u
              WHERE u.user_id = notebooks.user_id AND u.usage_month = strftime('%Y-%m', 'now'));
--;;
DROP TABLE user_usage;
