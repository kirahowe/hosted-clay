# hosted-clay

A browser-based hosted environment for [Clay](https://scicloj.github.io/clay/)
notebooks: sign in, create a notebook, edit Clojure in the browser, see
Clay-rendered output, share a read-only link. Each notebook runs in its own
[Sprite](https://sprites.dev) (Fly.io's stateful sandbox).

## Run it locally

```
bb dev      # start the dev system on http://localhost:3000
bb repl     # nREPL with dev+test on the classpath; (dev) (go) (reset)
bb test     # kaocha
bb ci       # lint + fmt check + tests
```

Dev wants two env vars to exercise the full flow (both optional to boot):
`HANKO_API_URL` (a free Hanko Cloud project) and `SPRITES_TOKEN` (a Sprites
org token).

## Deploy

`bb deploy` (flyctl). One always-on Fly machine with a volume mounted at
`/data` for the SQLite file. Secrets: `SPRITES_TOKEN`, `HANKO_API_URL`,
`BASE_URL`, optionally `RESEND_API_KEY` + `EMAIL_FROM`, plus the Litestream
backup secrets (see **Backups** below).

### Backups

The SQLite file lives on a single Fly volume, so back it up two ways:

- **Litestream** (point-in-time recovery, strongly recommended): stream the
  SQLite file to an S3-compatible bucket. On Fly the easiest bucket is Tigris —
  `fly storage create` provisions one and sets its `AWS_ACCESS_KEY_ID` /
  `AWS_SECRET_ACCESS_KEY` as app secrets, which Litestream reads automatically;
  then set `LITESTREAM_REPLICA_URL` to
  `s3://<bucket>?endpoint=fly.storage.tigris.dev&region=auto`. For another S3
  provider (R2, B2, MinIO) swap in that provider's `endpoint=` host and also set
  `LITESTREAM_ACCESS_KEY_ID` / `LITESTREAM_SECRET_ACCESS_KEY`. Pass the endpoint
  and region as URL query params — a bare `s3://bucket.host/path` is treated as
  AWS S3, so the endpoint must be explicit. The container then replicates every
  change to the bucket and, on boot with an empty volume, restores the latest
  replica automatically (see `docker/start.sh`).
  Without the secret the app runs fine but warns loudly that the database
  exists only on the volume.
- **Fly volume snapshots** (coarse safety net): taken daily automatically;
  raise retention with `fly volumes update <id> --snapshot-retention 14`.

### Inspecting the prod database

For a quick read-only look (e.g. "is that sprite the warm pool or an orphan?"),
`fly ssh` in and query with the JVM and uberjar already on the machine. The
runtime image is JRE-only (no `sqlite3` CLI), but the uberjar bundles the same
`sqlite-jdbc` the app uses, so nothing needs installing:

```
fly ssh console -a hosted-clay
java --enable-native-access=ALL-UNNAMED -cp /app/hosted-clay.jar clojure.main -e '
(require (quote [next.jdbc :as jdbc]))
(run! prn (jdbc/execute! (jdbc/get-datasource "jdbc:sqlite:/data/hosted-clay.db")
                         ["SELECT sprite_name, state FROM sprite_pool"]))'
```

Swap the SQL for whatever you need. Reads are safe against the live app: WAL mode
lets a reader run without blocking (or being blocked by) the app's writer, so a
`SELECT` never contends. `--enable-native-access=ALL-UNNAMED` only silences
sqlite-jdbc's native-load warnings (same flag as the `:admin` alias); the query
works without it.

Don't `apt-get install sqlite3` on the machine — it works, but the rootfs is
ephemeral, so it re-downloads ~25 MB on every restart. For anything heavier than
a quick read, pull a consistent copy locally instead of touching the live file:
`litestream restore` from the backup bucket (see **Backups**), then query
`./prod.db` with `sqlite3` or `clojure -M:admin --env prod --db-path ./prod.db`.

### Email

Idle-notebook warnings only *count* when they're actually delivered:
without `RESEND_API_KEY` the warning is logged instead of sent, the
notebook is never marked warned, and idle deletion never fires. To make
the idle policy (warn at 23 days, delete at 30) operative, set
`RESEND_API_KEY` and an `EMAIL_FROM` on a domain verified in Resend — the
default (`onboarding@resend.dev`) is Resend's sandbox sender and only
delivers to addresses verified on your own Resend account.

## Docs

Architecture and decisions live in [docs/](docs/).

## License

MIT
