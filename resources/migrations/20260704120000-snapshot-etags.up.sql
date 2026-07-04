-- Validators for the snapshot files, taken verbatim from the ETag the
-- sprite's Caddy returns when a file is fetched. Sent back as If-None-Match
-- on the next capture, so an unchanged file answers 304 with no body — the
-- census's refresh then costs two tiny conditional GETs per awake notebook
-- instead of re-streaming the ~1 MB render every window for as long as the
-- sprite stays awake. NULL means "no validator" (pre-migration rows, or a
-- server that sent no ETag): the next capture just does the full GET.
ALTER TABLE notebook_snapshots ADD COLUMN html_etag TEXT;
--;;
ALTER TABLE notebook_snapshots ADD COLUMN source_etag TEXT;
