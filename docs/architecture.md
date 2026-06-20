# Architecture

Two components: a control plane (this app) and one Sprite per notebook.

## Control plane

A single always-on Clojure web app (Integrant + http-kit + reitit +
hiccup), state in file-backed SQLite on a Fly volume. It owns:

- **Auth** — Hanko's `<hanko-auth>` element on `/login` sets the `hanko`
  session cookie; the auth middleware verifies the JWT against the
  project's JWKS on every request and provisions a local user on first
  sight (`users` + `identities`, so more providers can be added without
  migration).
- **Notebook lifecycle** — create (claim a warm sprite, or provision one
  inline as the slow path), delete, idle warning at 23 days, deletion at
  30 (`hosted-clay.lifecycle`, run by the scheduler).
- **Warm pool** — the scheduler keeps `pool-target` provisioned sprites
  ready so "New notebook" is instant. A budget cap (`max-sprites`)
  bounds total spend.
- **Workspace** — the editing page at `/notebooks/:id`
  (`hosted-clay.ui.pages.workspace`) puts the code-server editor and the
  live Clay output side by side, as two same-origin iframes onto the
  owner proxy. Saving in the editor re-renders the output via Clay's
  live-reload. The dashboard's "Open notebook" link and a new notebook's
  post-create redirect both land here.
- **Proxy** — all browser traffic to a notebook flows through the
  control plane (`hosted-clay.proxy`): sprite URLs stay on Sprites' own
  auth and the proxy attaches the org API token. Owner traffic
  (`/n/:id/*`, all methods + WebSockets) is gated on ownership and has
  its framing headers (`X-Frame-Options`, CSP `frame-ancestors`)
  stripped so it can be embedded in the workspace iframes; share traffic
  (`/s/:token/*`) is GET/HEAD only, keeps its framing headers, and
  `/edit/*` is blocked, so a share link can never reach the editor.

## Notebook sprite

Identical setup on every sprite (uniformity is what makes the pool
work), installed by `resources/sprite/setup.sh` over the exec WebSocket:

- Caddy on 8080 (the sprite URL's http_port): `/edit/*` → code-server
  (8443), everything else → Clay (1971).
- One JVM (`clojure -M:watch`, see `resources/sprite/watch.clj`):
  nREPL on localhost:1339 for Calva + Clay in live-reload mode.
- code-server with Calva, `--auth none` — it is only reachable through
  the authenticated proxy. Calva is configured (baked-in `settings.json`)
  with clojure-lsp-on-start disabled and jack-in versions pinned: on a
  constrained free sprite, clojure-lsp indexing the Noj classpath and
  Calva's `find-versions` resolution otherwise saturate the CPU on first
  editor open, stalling saves and Clay's live-reload WebSocket. The
  save -> live-reload MVP loop needs neither; a future REPL connect is
  unaffected (lsp is static analysis, independent of nREPL).
- Services are registered with the sprite runtime, so a cold boot
  restarts them; a warm wake resumes the running JVM in place.

## Known risks / day-one verifications

These are the things the spec flags as "verify on day one", plus what
this implementation assumes:

1. **WebSocket relay end-to-end** — Clay live-reload and code-server
   both need WS through our proxy and the sprite URL. The relay
   (`hosted-clay.proxy`) is implemented but must be verified against a
   real sprite.
2. **Private sprite URL auth** — the proxy assumes a sprite URL with
   default ("sprite") auth accepts `Authorization: Bearer <org token>`.
3. **Clay options** — `watch.clj` uses Clay ≥ 2.0 with `:live-reload`;
   the rendered page's reload socket path must survive the `/n/:id`
   path prefix (Clay uses relative URLs, as does code-server, but
   confirm).
4. **SDKMAN java path inside the sprite base image** — setup.sh probes
   for it and fails loudly if the probe misses.

## Deliberate deviations from the original spec

- **Hanko instead of GitHub OAuth** (operator decision). The
  users/identities split still accommodates other providers.
- **No checkpoint/restore calls.** Sprites' native warm-wake already
  gives sub-second resume with the JVM intact; checkpoints add API
  surface with no MVP flow that needs them. Cold boots restart the JVM
  via services (~30s to first render) — acceptable for the prototype,
  revisit if cold boots turn out to be common.
- **Proxy buffers bodies** (http-kit client semantics). Notebook pages
  and editor assets are small-to-medium; fine at MVP scale.
