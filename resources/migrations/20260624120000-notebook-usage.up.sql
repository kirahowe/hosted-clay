-- Per-user monthly active-hours metering. Sprites bill only while awake and
-- the Sprites API exposes no usage data, so the scheduler's census meters
-- awake wall-clock time into each notebook (one notebook per user, so this is
-- per-user). usage_month is the UTC 'YYYY-MM' the running total belongs to;
-- it resets when the month rolls over. usage_warned_at marks that the
-- near-the-limit warning email went out this month (cleared on rollover).
ALTER TABLE notebooks ADD COLUMN usage_month TEXT;
--;;
ALTER TABLE notebooks ADD COLUMN awake_seconds INTEGER NOT NULL DEFAULT 0;
--;;
ALTER TABLE notebooks ADD COLUMN usage_warned_at TEXT;
