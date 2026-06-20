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

cat > /home/sprite/notebook/bin/code-server.sh <<'EOF'
#!/usr/bin/env bash
source /home/sprite/notebook/bin/env.sh
exec code-server --auth none --disable-telemetry --bind-addr 127.0.0.1:8443 /home/sprite/notebook
EOF
chmod +x /home/sprite/notebook/bin/*.sh

# --- Pre-fetch notebook dependencies. This is the slow step (minutes);
# the warm pool exists so users never wait on it.
# shellcheck disable=SC1091
source /home/sprite/notebook/bin/env.sh
cd /home/sprite/notebook
clojure -P -M:watch

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
    --cmd /home/sprite/notebook/bin/code-server.sh --no-stream
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
