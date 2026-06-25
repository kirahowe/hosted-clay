-- Static snapshots of a notebook, captured into the control plane so its
-- content can be served without waking (and billing) the sprite — and so it
-- stays reachable when the notebook is paused for the month. The owner's
-- raw-source view reads `source`; the read-only share view will read a rendered
-- `html` snapshot (added next). The scheduler's census refreshes a snapshot
-- while the sprite is already awake, so capturing never causes a wake.
--
-- A side table, not columns on `notebooks`, so the frequent SELECT * over
-- notebooks (census every few ticks, dashboard every load) never drags these
-- potentially large blobs along. One row per notebook (the UNIQUE), dropped
-- with it (ON DELETE CASCADE).
CREATE TABLE notebook_snapshots (
  id          TEXT PRIMARY KEY,
  notebook_id TEXT NOT NULL UNIQUE REFERENCES notebooks(id) ON DELETE CASCADE,
  source      TEXT,
  html        TEXT,
  captured_at TEXT NOT NULL,
  created_at  TEXT NOT NULL
);
