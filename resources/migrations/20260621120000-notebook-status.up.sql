-- Async provisioning: a notebook now exists in the DB the moment creation
-- begins, in the 'provisioning' state, and flips to 'ready' once its sprite
-- is built ('failed' if that didn't finish). Existing notebooks are ready.
ALTER TABLE notebooks ADD COLUMN status TEXT NOT NULL DEFAULT 'ready';
