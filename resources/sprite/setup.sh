#!/usr/bin/env bash
# Provision a notebook sprite. Runs once per sprite, as the `sprite` user
# (passwordless sudo), fed to `bash -s` over the exec socket after the
# notebook files have been uploaded. Idempotent so a failed run can be
# retried on the same sprite.
set -euo pipefail

export DEBIAN_FRONTEND=noninteractive

# --- Clojure CLI ---
if ! command -v clojure >/dev/null 2>&1; then
  curl -fsSL -o /tmp/clojure-install.sh \
    https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh
  sudo bash /tmp/clojure-install.sh
fi

# --- Caddy (in-sprite reverse proxy on 8080) ---
if ! command -v caddy >/dev/null 2>&1; then
  sudo apt-get update -qq
  sudo apt-get install -y -qq caddy
fi

# --- code-server + Calva ---
if ! command -v code-server >/dev/null 2>&1; then
  curl -fsSL https://code-server.dev/install.sh | sh
fi
code-server --install-extension betterthantomorrow.calva

# Shape code-server into a focused single-namespace editor and auto-connect
# the REPL. Three things going on here:
#
#  1. Quiet Calva down. The primary loop is save -> Clay live-reload; it
#     needs neither clojure-lsp nor Calva jack-in, both of which otherwise
#     saturate a constrained sprite on first editor open (clojure-lsp
#     indexes the whole Noj classpath; jack-in shells out `clojure -X:deps
#     find-versions`, dragging down the Maven tooling tree) and stall saves
#     and the live-reload WebSocket. So lsp-on-start is off and jack-in
#     versions are pinned (no "latest version" resolution).
#  2. Auto-connect the REPL with no prompts. At extension-host activation
#     `autoConnectRepl` looks *once* for the `.nrepl-port` file and connects
#     if it's there (it does not watch for it or retry — see the gate in
#     bin/wait-repl.sh that guarantees the file is present by then). The
#     `replConnectSequences` entry (autoSelectForConnect + pinned
#     projectType/root + the `nReplPortFile` the connect needs + fallbackPort
#     1339 + cljsType none) suppresses the project-type/root/cljs menus that
#     otherwise block the connection and pins the target port. Connect, not
#     jack-in — the nREPL is already running. The `notebook` ns is loaded by
#     Clay at boot, so eval works immediately (no "load file" step). lsp is
#     static analysis, independent of the running REPL.
#  3. Strip the IDE chrome. Hide the activity bar/minimap and the
#     scaffolding files (deps.edn, watch.clj, Caddyfile, bin/, dotdirs) from
#     the explorer (`files.exclude`, which also keeps them out of Quick
#     Open) and from full-text search (`search.exclude`) so the user sees
#     only their notebook; turn off the AI/agent surface and workspace-trust
#     prompt. Neither exclude lists `notebook.clj`, so the user's file stays
#     findable. deps.edn is hidden because editing it does nothing until the
#     JVM re-resolves — a future "restart REPL with new deps" button would
#     change that.
#  4. Auto-open notebook.clj and a terminal. code-server has no CLI flag to
#     open a file alongside the workspace folder, so `.vscode/tasks.json`
#     runs two runOn:folderOpen tasks once the folder opens as a workspace:
#     one opens the file via the code-server CLI, one leaves an interactive
#     terminal panel open (data-science code often prints useful things).
#     Trust + automatic-tasks are enabled so they run without a prompt.
#     These need the folder open as a workspace — hence the single-folder
#     launch in bin/code-server.sh.
mkdir -p /home/sprite/.local/share/code-server/User
cat > /home/sprite/.local/share/code-server/User/settings.json <<'EOF'
{
  "calva.enableClojureLspOnStart": "never",
  "calva.autoConnectRepl": true,
  "calva.jackInDependencyVersions": {
    "nrepl": "1.7.0",
    "cider-nrepl": "0.59.0",
    "cider/piggieback": "0.6.1"
  },
  "calva.replConnectSequences": [
    {
      "name": "Notebook (deps.edn)",
      "projectType": "deps.edn",
      "projectRootPath": ["."],
      "nReplPortFile": [".nrepl-port"],
      "fallbackPort": 1339,
      "autoSelectForConnect": true,
      "cljsType": "none"
    }
  ],
  "editor.formatOnSave": false,
  "editor.minimap.enabled": false,
  "files.autoSave": "off",
  "workbench.activityBar.location": "hidden",
  "workbench.startupEditor": "none",
  "window.restoreWindows": "all",
  "files.hotExit": "onExitAndWindowClose",
  "security.workspace.trust.enabled": false,
  "task.allowAutomaticTasks": "on",
  "chat.disableAIFeatures": true,
  "chat.commandCenter.enabled": false,
  "telemetry.telemetryLevel": "off",
  "extensions.autoCheckUpdates": false,
  "update.mode": "none",
  "files.exclude": {
    "deps.edn": true,
    "watch.clj": true,
    "Caddyfile": true,
    ".nrepl-port": true,
    ".vscode": true,
    "bin": true,
    ".calva": true,
    ".clj-kondo": true,
    ".lsp": true,
    ".cpcache": true
  },
  "search.exclude": {
    "deps.edn": true,
    "watch.clj": true,
    "Caddyfile": true,
    ".nrepl-port": true,
    ".vscode": true,
    "bin": true,
    ".calva": true,
    ".clj-kondo": true,
    ".lsp": true,
    ".cpcache": true
  }
}
EOF

# --- Java: Temurin ships via SDKMAN, which needs sourcing. Resolve it once
# and pin JAVA_HOME into an env file the services source, so they don't
# depend on shell profile behaviour.
if ! command -v java >/dev/null 2>&1; then
  for init in /.sprite/languages/java/sdkman/bin/sdkman-init.sh \
              /.sprite/languages/java/candidates/java/current/bin \
              "$HOME/.sdkman/bin/sdkman-init.sh"; do
    if [ -s "$init" ] && [[ "$init" == *.sh ]]; then
      set +u
      # shellcheck disable=SC1090
      source "$init"
      set -u
      break
    fi
  done
fi
command -v java >/dev/null 2>&1 || { echo "java not found on sprite" >&2; exit 1; }
JAVA_BIN="$(readlink -f "$(command -v java)")"
JAVA_HOME="$(dirname "$(dirname "$JAVA_BIN")")"

mkdir -p /home/sprite/notebook/bin
cat > /home/sprite/notebook/bin/env.sh <<EOF
export JAVA_HOME="$JAVA_HOME"
export PATH="$JAVA_HOME/bin:/usr/local/bin:$HOME/.local/bin:\$PATH"
EOF

cat > /home/sprite/notebook/bin/start.sh <<'EOF'
#!/usr/bin/env bash
source /home/sprite/notebook/bin/env.sh
cd /home/sprite/notebook
exec clojure -M:watch
EOF

# Gate code-server on the nREPL being up. Calva's `autoConnectRepl` is a
# one-shot check at extension-host activation: it looks for `.nrepl-port`
# *once* and connects only if it's already there — it does NOT watch for the
# file to appear or retry. On a cold boot the `notebook` and `code-server`
# services start in parallel, but the JVM takes ~30s to bind the nREPL while
# code-server activates in seconds, so the check almost always loses the race
# and Calva silently never connects. Blocking code-server's launch until the
# nREPL is up fixes that. We probe the live port (127.0.0.1:1339) rather than
# the `.nrepl-port` file: the port is always 1339, and on a cold reboot a
# stale port file from the previous boot is already on disk before the new
# JVM has rebound — only a real connection proves the server is accepting
# connections. (`watch.clj` writes the file right after binding, so it's
# present by the time code-server activates seconds later.) On a warm wake
# the port is already open, so this returns immediately and adds no latency.
cat > /home/sprite/notebook/bin/wait-repl.sh <<'EOF'
#!/usr/bin/env bash
# A failed /dev/tcp open aborts the subshell (not this script), so the
# connection probe is isolated; the fd closes when the subshell exits.
for _ in $(seq 1 150); do
  if (exec 3<>/dev/tcp/127.0.0.1/1339) 2>/dev/null; then break; fi
  sleep 1
done
exec /home/sprite/notebook/bin/code-server.sh
EOF

cat > /home/sprite/notebook/bin/code-server.sh <<'EOF'
#!/usr/bin/env bash
source /home/sprite/notebook/bin/env.sh
# Open the notebook folder as the workspace — and ONLY the folder.
# code-server (like `code`) takes a single workspace/dir positional arg;
# passing a folder AND a file makes it open the file in a window with no
# folder attached, which breaks Quick Open, Search, folderOpen tasks, and
# Calva's deps.edn project detection. Opening the folder as the workspace
# is what makes those work; notebook.clj is opened on top by the
# folderOpen task in .vscode/tasks.json. code-server persists session
# state on disk, so the open file/terminal stick across warm wakes.
exec code-server --auth none --disable-telemetry --bind-addr 127.0.0.1:8443 \
  /home/sprite/notebook
EOF
chmod +x /home/sprite/notebook/bin/*.sh

# --- Pre-fetch notebook dependencies. This is the slow step (minutes);
# the warm pool exists so users never wait on it.
# shellcheck disable=SC1091
source /home/sprite/notebook/bin/env.sh
cd /home/sprite/notebook
clojure -P -M:watch

# Pre-warm the tools.deps machinery Calva's jack-in version check shells
# out to (`clojure -X:deps find-versions`). The artifacts it needs aren't
# pulled by the prefetch above, so without this the first editor open
# triggers a Maven download storm on top of everything else. Best-effort:
# a metadata hiccup must not fail provisioning.
for lib in nrepl/nrepl cider/cider-nrepl cider/piggieback; do
  clojure -X:deps find-versions :lib "$lib" :n 1 >/dev/null 2>&1 || true
done

# --- Services: owned by the sprite runtime, restarted on cold boot.
# caddy gets the http_port, so the sprite URL routes to it.
if ! sprite-env services get caddy >/dev/null 2>&1; then
  sprite-env services create caddy \
    --cmd /usr/bin/caddy --args "run,--config,/home/sprite/Caddyfile" \
    --http-port 8080 --no-stream
fi
if ! sprite-env services get notebook >/dev/null 2>&1; then
  sprite-env services create notebook \
    --cmd /home/sprite/notebook/bin/start.sh --no-stream
fi
if ! sprite-env services get code-server >/dev/null 2>&1; then
  sprite-env services create code-server \
    --cmd /home/sprite/notebook/bin/wait-repl.sh --no-stream
fi

# --- Readiness: a pool sprite is only useful once the whole stack is
# serving, so wait for Clay (the slow JVM start) and code-server before
# reporting, then confirm both routes answer through Caddy.
for _ in $(seq 1 120); do
  if curl -fsS -o /dev/null http://127.0.0.1:1971/ \
     && curl -fsS -o /dev/null http://127.0.0.1:8443/; then
    break
  fi
  sleep 2
done
curl -fsS -o /dev/null http://127.0.0.1:1971/
curl -fsS -o /dev/null http://127.0.0.1:8443/
curl -fsS -o /dev/null http://127.0.0.1:8080/
curl -fsS -o /dev/null http://127.0.0.1:8080/edit/

echo provisioned
