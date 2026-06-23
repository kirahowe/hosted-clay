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
  post-create redirect both land here. The editor iframe carries
  `?folder=/home/sprite/notebook` so code-server pins the workspace from
  the browser side regardless of persisted state. A **Restart** action
  (`POST /notebooks/:id/restart`) bounces both the sprite's `notebook` and
  `code-server` services over the exec socket for when Clay dies and
  `/n/:id/` starts 502ing (code-server too, so Calva's one-shot auto-connect
  re-attaches to the fresh nREPL); the page then polls `/n/:id/counter` for
  liveness and reloads both panes.
- **Proxy** — all browser traffic to a notebook flows through the
  control plane (`hosted-clay.proxy`): sprite URLs stay on Sprites' own
  auth and the proxy attaches the org API token. Owner traffic
  (`/n/:id/*`, all methods + WebSockets) is gated on ownership and has
  its framing headers (`X-Frame-Options`, CSP `frame-ancestors`)
  stripped so it can be embedded in the workspace iframes; share traffic
  (`/s/:token/*`) is GET/HEAD only, keeps its framing headers, and
  `/edit/*` is blocked, so a share link can never reach the editor.
  A dead/​waking sprite is answered with a styled, self-refreshing page
  for document requests (`hosted-clay.ui.pages.waking`) and a plain line
  otherwise.
- **Email** — only the 23-day deletion warning, sent via Resend's HTTP
  API (`hosted-clay.email`). With no `RESEND_API_KEY` the component logs
  the message instead of sending and warns loudly at startup, so the flow
  is exercisable in dev. A failed send *throws*, so the notebook is left
  un-warned and retried on the next sweep rather than marked warned and
  deleted unannounced.

## Notebook sprite

Identical setup on every sprite (uniformity is what makes the pool
work), installed by `resources/sprite/setup.sh` over the exec WebSocket:

- Caddy on 8080 (the sprite URL's http_port): `/edit/*` → code-server
  (8443), everything else → Clay (1971).
- One JVM (`clojure -M:watch`, see `resources/sprite/watch.clj`):
  nREPL on localhost:1339 for Calva + Clay in live-reload mode. `watch.clj`
  writes an `.nrepl-port` file after the server binds so Calva can find it.
- code-server with Calva, `--auth none` — only reachable through the
  authenticated proxy. It launches on the notebook **folder** (a single
  positional, so it opens as a real workspace — Calva's project detection
  needs that), shaped into a focused single-file editor by a baked
  `settings.json`:
  - **Calva startup tamed** — jack-in versions pinned, and clojure-lsp pinned
    to a pre-installed binary (`calva.clojureLspPath`) whose analysis cache is
    pre-built during provisioning. On a constrained free sprite, lsp classpath
    indexing + `find-versions` otherwise peg the CPU and stall saves and the
    live-reload WebSocket; paying the indexing in the warm pool keeps
    autocomplete on without that first-open stall (jack-in isn't needed at all —
    we connect to the running nREPL).
  - **REPL connected, not jacked-in** — a `replConnectSequences` entry
    auto-connects to the already-running nREPL with no prompts, and
    `bin/wait-repl.sh` gates code-server's launch until the nREPL port is
    up (Calva's auto-connect is a one-shot check at activation, with no
    retry). Eval results surface inline; the rendered pane only changes on
    save, so they don't fight. (clojure-lsp is static analysis, independent
    of the REPL.)
  - **Focused chrome** — activity bar + minimap hidden, AI/agent surface
    off, scaffolding hidden from the explorer (`files.exclude`) and Quick
    Open (`search.exclude`); the sidebar is collapsed on startup by a small
    first-party extension (no setting exists for it), which also pulls focus
    into the editor once the notebook opens, so first keystrokes land in the
    code rather than a tree or the terminal. The terminal is kept. The editor
    follows the browser's light/dark preference (`window.autoDetectColorScheme`),
    matching the rest of the workspace chrome.
  - **Auto-open** — `.vscode/tasks.json` `folderOpen` tasks open
    `notebook.clj` and an interactive terminal; the editor iframe also
    carries `?folder=` to pin the workspace from the browser side.
  Several of these are out-of-band workarounds because code-server doesn't
  expose the initial layout as configuration — see "Deliberate deviations".
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
3. **Clay's reload URLs behind the prefix** — confirmed Clay 2.0 bakes
   `localhost:<port>` + root-absolute URLs (the reload socket, `/counter`,
   `/Clay.svg.png`) into the page, which do NOT survive the `/n/:id`
   prefix; `proxy/fix-clay-reload` rewrites them to same-origin/relative.
   Upstreaming this to Clay would let us drop the rewrite — see
   `docs/clay-reverse-proxy-proposal.md`.
4. **SDKMAN java path inside the sprite base image** — setup.sh probes
   for it and fails loudly if the probe misses.
5. **Email delivery** — deletion warnings only send when `RESEND_API_KEY`
   is set as a Fly secret. `EMAIL_FROM` must be a domain verified on the
   Resend account; the prod default (`onboarding@resend.dev`) is Resend's
   sandbox sender and only delivers to verified recipients. Without the
   key the app runs fine but logs every "email" instead of sending it.

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
- **code-server bent into a focused editor via out-of-band glue.** Setting
  the initial layout (open file, hidden sidebar, open terminal) and a
  prompt-less REPL connect aren't expressible as code-server configuration,
  so we use a launch gate (`bin/wait-repl.sh`), `folderOpen` tasks, and a
  tiny first-party sidebar extension. Each is defensible for the MVP, but if
  "focused notebook editor" stays the product the durable path is our own
  thin VS Code **workbench host** — set
  `IWorkbenchConstructionOptions.defaultLayout` directly, keeping the
  server + Calva + REPL and replacing only the front-end bootstrap. Parked;
  start with a `defaultLayout` patch on code-server's bootstrap before a
  full custom host.
