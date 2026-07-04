-- Snapshots now live as files on the control-plane volume (see
-- hosted-clay.snapshot); the row keeps only the capture timestamp that paces
-- the census refresh. The rendered notebook.html is ~1 MB and the raw source
-- can be large too, so holding them as TEXT here bloated the DB, its WAL, and
-- every backup for no benefit — the bytes are now served straight from disk,
-- never dragged through JDBC. (The old `cat`-over-exec capture also capped at
-- 64 KiB and truncated the stored render mid-<script>, which broke the share
-- view; the file capture streams over plain HTTP with no such cap.)
ALTER TABLE notebook_snapshots DROP COLUMN html;
--;;
ALTER TABLE notebook_snapshots DROP COLUMN source;
