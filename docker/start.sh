#!/usr/bin/env bash
# Container entrypoint. With a Litestream replica configured (the
# LITESTREAM_REPLICA_URL secret), run the app under `litestream replicate`:
# -restore-if-db-not-exists first restores the SQLite file from the replica
# when the volume is empty (a fresh volume after a disaster self-heals to the
# newest backup, and a truly first boot with no replica just proceeds), then
# Litestream replicates every change to the bucket and -exec supervises the
# JVM (the app's exit is the container's exit). Without the secret, run the
# app directly and say so loudly: the database then lives only on the single
# Fly volume.
#
# The replica URL is S3-compatible; a non-AWS store needs its endpoint and
# region as query params (e.g.
# s3://bucket?endpoint=fly.storage.tigris.dev&region=auto for Tigris) — a bare
# s3://bucket.host/path is treated as AWS S3. Credentials ride in as
# AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY (what `fly storage create` sets for
# a Tigris bucket) or the equivalent LITESTREAM_ACCESS_KEY_ID /
# LITESTREAM_SECRET_ACCESS_KEY.
set -euo pipefail

DB_PATH=/data/hosted-clay.db
APP="java -cp /app/conf:/app/hosted-clay.jar hosted_clay.main prod.edn"

if [ -n "${LITESTREAM_REPLICA_URL:-}" ]; then
  exec litestream replicate -restore-if-db-not-exists \
    -exec "$APP" "$DB_PATH" "$LITESTREAM_REPLICA_URL"
else
  echo "WARNING: LITESTREAM_REPLICA_URL not set — running WITHOUT streaming" \
       "backups; the database exists only on this machine's volume." >&2
  exec $APP
fi
