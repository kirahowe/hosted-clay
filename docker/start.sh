#!/usr/bin/env bash
# Container entrypoint. With a Litestream replica configured (the
# LITESTREAM_REPLICA_URL secret), restore the SQLite file from the replica
# if the volume is empty — a fresh volume after a disaster — and then run
# the app under `litestream replicate -exec`, which streams every WAL frame
# to the bucket and supervises the JVM (the app's exit is the container's
# exit). Without the secret, run the app directly and say so loudly: the
# database then lives only on the single Fly volume.
#
# The replica URL is S3-compatible and carries the endpoint for non-AWS
# stores (e.g. s3://bucket.ENDPOINT/path for R2/MinIO/B2). Credentials ride
# in as LITESTREAM_ACCESS_KEY_ID / LITESTREAM_SECRET_ACCESS_KEY.
set -euo pipefail

DB_PATH=/data/hosted-clay.db
APP="java -cp /app/conf:/app/hosted-clay.jar hosted_clay.main prod.edn"

if [ -n "${LITESTREAM_REPLICA_URL:-}" ]; then
  litestream restore -if-db-not-exists -if-replica-exists \
    -o "$DB_PATH" "$LITESTREAM_REPLICA_URL"
  exec litestream replicate -exec "$APP" "$DB_PATH" "$LITESTREAM_REPLICA_URL"
else
  echo "WARNING: LITESTREAM_REPLICA_URL not set — running WITHOUT streaming" \
       "backups; the database exists only on this machine's volume." >&2
  exec $APP
fi
