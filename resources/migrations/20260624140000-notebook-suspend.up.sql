-- User-initiated suspend. A notebook the owner has manually suspended: the
-- proxy refuses to forward to it (so it gets no traffic and idle-suspends,
-- stops billing, and isn't woken again) until the owner resumes. NULL means
-- active; a timestamp records when it was suspended. Distinct from the monthly
-- usage pause (that's automatic + un-pauses on the month rollover; this is
-- manual + reversible any time).
ALTER TABLE notebooks ADD COLUMN suspended_at TEXT;
