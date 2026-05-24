#!/usr/bin/env bash
#
# WifiHaven API — first-install bootstrap.
#
#   curl -fsSL https://raw.githubusercontent.com/wifihaven/wifihaven/main/deploy/install.sh | bash
#
# Or, to review first (recommended):
#   curl -fsSL https://raw.githubusercontent.com/wifihaven/wifihaven/main/deploy/install.sh -o install.sh
#   less install.sh
#   bash install.sh
#
# Re-running on an existing install is safe: the script detects an existing
# .env and offers to keep it, and `docker compose up -d` is idempotent.
#
# Env vars to skip prompts (useful for unattended installs):
#   WIFIHAVEN_PREFIX         install path. Default: $HOME/.wifihaven when run
#                            as a normal user (no sudo needed), /opt/wifihaven
#                            when run as root. Set to /opt/wifihaven explicitly
#                            for a system-wide install (requires sudo/root).
#   WIFIHAVEN_INSTALL_DIR    legacy alias for WIFIHAVEN_PREFIX.
#   WIFIHAVEN_API_HOST_PORT  host port to bind           (default: 8080)
#   WIFIHAVEN_API_BIND       host interface to bind on   (default: 0.0.0.0)
#   WIFIHAVEN_UI_ALLOWED_HOSTS  comma-separated hostnames the admin UI is
#                            reachable at (e.g. "api.lan" or
#                            "wifihaven.example,api.wifihaven.example"). These
#                            are always allowed by every profile so a paused
#                            household member can still reach the UI to
#                            unpause themselves (#944). Hostname-only for
#                            now; port-aware allow/block tracked in #296.
#                            Empty = no global allow list. (default: prompt)
#   WIFIHAVEN_NEW_ADMIN_PW   new admin password          (default: prompt)
#   WIFIHAVEN_NONINTERACTIVE if set, never prompt; fail if any value missing.

set -euo pipefail

case "${1:-}" in
  -h|--help)
    sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'
    exit 0
    ;;
esac

REPO_RAW="${WIFIHAVEN_REPO_RAW:-https://raw.githubusercontent.com/wifihaven/wifihaven/main}"
COMPOSE_URL="${REPO_RAW}/deploy/docker-compose.prod.yml"
ENV_EXAMPLE_URL="${REPO_RAW}/deploy/.env.example"
HELPER_SCRIPTS=(start stop restart logs status update)

c_red()    { printf '\033[31m%s\033[0m\n' "$*"; }
c_green()  { printf '\033[32m%s\033[0m\n' "$*"; }
c_yellow() { printf '\033[33m%s\033[0m\n' "$*"; }
c_bold()   { printf '\033[1m%s\033[0m\n' "$*"; }

step() { echo; c_bold "▸ $*"; }
ok()   { c_green "  ✓ $*"; }
warn() { c_yellow "  ! $*"; }
die()  { c_red "  ✗ $*"; exit 1; }

# is_tty: stdin AND stdout are both terminals — true for `bash install.sh`,
# false under `curl | bash` (stdin is the pipe).
is_tty() { [ -t 0 ] && [ -t 1 ]; }

# can_prompt: we have *some* way to ask the user. Either stdin is a tty
# (normal invocation), or /dev/tty is readable (the common `curl | bash`
# case — the user has a controlling terminal, just not on stdin). The
# `read` calls in prompt()/prompt_secret() already redirect from /dev/tty,
# so checking /dev/tty here is consistent with what the prompts use.
# Returns false in true non-interactive environments (CI, no controlling
# tty), where we should fall back to env-var-or-default.
can_prompt() { is_tty || { [ -r /dev/tty ] && [ -w /dev/tty ]; }; }

noninteractive() { [ -n "${WIFIHAVEN_NONINTERACTIVE:-}" ] || ! can_prompt; }

prompt() {
  # prompt VAR "Question" "default"
  local var="$1" question="$2" default="${3:-}"
  local current="${!var:-}"
  if [ -n "$current" ]; then
    return 0
  fi
  if noninteractive; then
    if [ -n "$default" ]; then
      printf -v "$var" '%s' "$default"
      return 0
    fi
    die "$var not set and running non-interactively"
  fi
  local answer
  if [ -n "$default" ]; then
    read -r -p "  $question [$default]: " answer </dev/tty
    answer="${answer:-$default}"
  else
    read -r -p "  $question: " answer </dev/tty
  fi
  printf -v "$var" '%s' "$answer"
}

prompt_secret() {
  # prompt_secret VAR "Question"
  local var="$1" question="$2"
  local current="${!var:-}"
  if [ -n "$current" ]; then return 0; fi
  if noninteractive; then die "$var not set and running non-interactively"; fi
  local answer
  read -r -s -p "  $question: " answer </dev/tty
  echo
  printf -v "$var" '%s' "$answer"
}

genpw()    { openssl rand -base64 24 | tr -d '\n=/+' | cut -c1-24; }
gensecret(){ openssl rand -base64 48 | tr -d '\n'; }

# ── 1. Sanity checks ──────────────────────────────────────────────────────

step "Checking prerequisites"

command -v docker >/dev/null 2>&1 || die "docker not found. Install Docker Engine first: https://docs.docker.com/engine/install/"
docker compose version >/dev/null 2>&1 || die "Docker Compose v2 plugin not found. Run 'docker compose version' to verify."
command -v openssl >/dev/null 2>&1 || die "openssl not found (needed to generate secrets)."
command -v curl    >/dev/null 2>&1 || die "curl not found."

if ! docker info >/dev/null 2>&1; then
  die "Cannot talk to the Docker daemon. Run with sudo, or add yourself to the 'docker' group and re-login."
fi

ok "docker $(docker --version | awk '{print $3}' | tr -d ',') / compose $(docker compose version --short)"

# ── 2. Collect inputs ─────────────────────────────────────────────────────

step "Configuration"

# WIFIHAVEN_PREFIX is the preferred name; WIFIHAVEN_INSTALL_DIR is the legacy alias.
if [ -n "${WIFIHAVEN_PREFIX:-}" ] && [ -z "${WIFIHAVEN_INSTALL_DIR:-}" ]; then
  WIFIHAVEN_INSTALL_DIR="$WIFIHAVEN_PREFIX"
fi

# Default prefix: user-writable when not root (so `curl|bash` works without
# sudo), /opt/wifihaven when running as root.
if [ "$(id -u)" -eq 0 ]; then
  DEFAULT_PREFIX="/opt/wifihaven"
else
  DEFAULT_PREFIX="${HOME}/.wifihaven"
fi

prompt WIFIHAVEN_INSTALL_DIR    "Install directory"                           "$DEFAULT_PREFIX"
prompt WIFIHAVEN_API_HOST_PORT  "Host port for the API"                       "8080"
prompt WIFIHAVEN_API_BIND       "Bind address (0.0.0.0 or 127.0.0.1)"         "0.0.0.0"
# #944: hostnames the admin UI is reachable at — always allowed by every
# profile so a paused household member can still reach the UI to unpause.
# Empty default keeps the strict self-hosted behavior intact; the operator
# can paste in e.g. "api.lan" or "wifihaven.example,api.wifihaven.example".
# Port-aware allow/block tracked in #296.
prompt WIFIHAVEN_UI_ALLOWED_HOSTS \
                                "Admin UI hostname(s) (comma-sep, blank = none)" \
                                ""

# ── 3. Install directory ──────────────────────────────────────────────────

step "Preparing $WIFIHAVEN_INSTALL_DIR"

if [ ! -d "$WIFIHAVEN_INSTALL_DIR" ]; then
  parent_dir="$(dirname "$WIFIHAVEN_INSTALL_DIR")"
  if [ -w "$parent_dir" ]; then
    mkdir -p "$WIFIHAVEN_INSTALL_DIR"
  elif [ "$(id -u)" -eq 0 ]; then
    mkdir -p "$WIFIHAVEN_INSTALL_DIR"
  else
    # Need sudo to create the install dir. If stdin is not a tty (e.g.
    # `curl … | bash`), sudo can't prompt for a password and will fail
    # silently with "a password is required". Detect that up front and
    # tell the user how to recover.
    if ! [ -t 0 ]; then
      # NOTE: heredoc assigned via `read` instead of `die "$(cat <<EOF…)"`
      # because bash 3.2 (still default on macOS) can't parse a heredoc
      # inside command substitution.
      IFS= read -r -d '' msg <<EOF || true

  This install needs root to write to ${WIFIHAVEN_INSTALL_DIR}, but stdin is
  not a tty (curl|bash detected) so sudo cannot prompt for a password. Run
  one of:

    # 1. User-mode install (no sudo needed):
    curl -fsSL https://raw.githubusercontent.com/wifihaven/wifihaven/main/deploy/install.sh \\
      | WIFIHAVEN_PREFIX=\$HOME/.wifihaven bash

    # 2. Save and run with sudo:
    curl -fsSL https://raw.githubusercontent.com/wifihaven/wifihaven/main/deploy/install.sh -o install.sh
    sudo bash install.sh

    # 3. Warm up sudo first, then pipe:
    sudo -v && curl -fsSL https://raw.githubusercontent.com/wifihaven/wifihaven/main/deploy/install.sh | bash
EOF
      die "$msg"
    fi
    sudo mkdir -p "$WIFIHAVEN_INSTALL_DIR"
    sudo chown "$USER" "$WIFIHAVEN_INSTALL_DIR"
  fi
fi
cd "$WIFIHAVEN_INSTALL_DIR"
ok "$WIFIHAVEN_INSTALL_DIR ready"

# ── 4. Fetch compose file ─────────────────────────────────────────────────

step "Fetching deploy files"

curl -fsSL "$COMPOSE_URL"     -o docker-compose.prod.yml
curl -fsSL "$ENV_EXAMPLE_URL" -o .env.example
for s in "${HELPER_SCRIPTS[@]}"; do
  curl -fsSL "${REPO_RAW}/deploy/scripts/${s}.sh" -o "${s}.sh"
  chmod +x "${s}.sh"
done
ok "docker-compose.prod.yml + .env.example + ${#HELPER_SCRIPTS[@]} helper scripts downloaded"

# ── 5. Refuse to clobber an existing install ──────────────────────────────
#
# This script is for first-install only. If an .env already exists in the
# install dir, a previous install owns this directory — we refuse rather
# than risk regenerating credentials that don't match the existing pgdata
# volume. Update / restart / reconfigure flows live in a separate tool.
if [ -f .env ]; then
  IFS= read -r -d '' msg <<EOF || true

  An existing .env was found at ${WIFIHAVEN_INSTALL_DIR}/.env — this looks
  like an existing install. install.sh only handles first-install and will
  not overwrite an existing one.

  To manage an existing install, use the helper scripts in the install
  dir:

    ${WIFIHAVEN_INSTALL_DIR}/update.sh    # pull latest images and restart
    ${WIFIHAVEN_INSTALL_DIR}/restart.sh   # restart in place
    ${WIFIHAVEN_INSTALL_DIR}/stop.sh      # stop (data preserved)
    ${WIFIHAVEN_INSTALL_DIR}/start.sh     # bring back up
    ${WIFIHAVEN_INSTALL_DIR}/logs.sh      # tail api logs
    ${WIFIHAVEN_INSTALL_DIR}/status.sh    # container status

  ⚠  DANGER — only run the next block if you intend to PERMANENTLY DELETE
     all WifiHaven data on this host (devices, profiles, query logs,
     admin user, everything). There is no undo.

    ${WIFIHAVEN_INSTALL_DIR}/stop.sh -v
    rm ${WIFIHAVEN_INSTALL_DIR}/.env
    # then re-run install.sh
EOF
  die "$msg"
fi

# Compose derives the project name from the install dir's basename:
# lowercased, with non [a-z0-9_-] stripped and any leading non-alphanum
# removed. The pgdata volume is then named "<project>_pgdata".
proj="$(basename "$PWD" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9_-' | sed 's/^[^a-z0-9]*//')"
if [ -n "$proj" ] && docker volume inspect "${proj}_pgdata" >/dev/null 2>&1; then
  IFS= read -r -d '' msg <<EOF || true

  A postgres data volume already exists for this install:

      docker volume: ${proj}_pgdata

  but no .env was found alongside it. Either a previous install was
  aborted partway through, or the .env was deleted while the data was
  left behind. Either way, install.sh will not auto-wipe a data volume —
  doing so could destroy real user data.

  Inspect what's in the volume before deciding:

    docker volume inspect ${proj}_pgdata
    docker run --rm -v ${proj}_pgdata:/var/lib/postgresql/data \\
      postgres:16-alpine ls /var/lib/postgresql/data

  ⚠  DANGER — only run this if you are CERTAIN the volume is empty or
     contains nothing you care about. This PERMANENTLY DELETES all data
     in it. There is no undo.

    docker volume rm ${proj}_pgdata
    # then re-run install.sh
EOF
  die "$msg"
fi

# ── 5a. Generate a fresh .env ─────────────────────────────────────────────

step "Generating .env"

DB_PASSWORD="$(genpw)"
JWT_SECRET="$(gensecret)"
umask 077
cat > .env <<EOF
# Generated by deploy/install.sh on $(date -u +%Y-%m-%dT%H:%M:%SZ)
# Keep this file private — chmod 600.

WIFIHAVEN_DB_NAME=wifihaven
WIFIHAVEN_DB_USER=wifihaven
WIFIHAVEN_DB_PASSWORD=${DB_PASSWORD}

WIFIHAVEN_JWT_SECRET=${JWT_SECRET}
WIFIHAVEN_JWT_HOURS=24

WIFIHAVEN_API_BIND=${WIFIHAVEN_API_BIND}
WIFIHAVEN_API_PORT=${WIFIHAVEN_API_HOST_PORT}

# #944: hostnames always added to every profile's snapshot extraAllowed so a
# paused household member can still reach the admin UI to unpause themselves.
WIFIHAVEN_UI_ALLOWED_HOSTS=${WIFIHAVEN_UI_ALLOWED_HOSTS}
EOF
chmod 600 .env
ok "Wrote .env (db password and JWT secret auto-generated, chmod 600)"


# ── 6. Pull + start ───────────────────────────────────────────────────────

step "Pulling image"
docker compose -f docker-compose.prod.yml --env-file .env pull
ok "Image pulled"

step "Starting stack"
docker compose -f docker-compose.prod.yml --env-file .env up -d
ok "Containers started"

# ── 7. Wait for health ────────────────────────────────────────────────────

API_URL="http://127.0.0.1:${WIFIHAVEN_API_HOST_PORT}"

# wait_for_api polls the api until it's accepting JSON requests, or until
# the timeout (WIFIHAVEN_WAIT_TIMEOUT seconds, default 90) elapses. On
# failure it dumps the last 100 lines of the api container logs plus
# `compose ps` so the user can diagnose without re-running anything by hand.
#
# We probe POST /api/auth/login with an empty body and accept 400/401 as
# proof of life. Two reasons over a dedicated /api/health endpoint:
#   1. /api/auth/login has existed since v0.1 — works against every
#      published image, including older :latest tags users may have
#      pulled before /api/health was added.
#   2. It mirrors the compose healthcheck, so install.sh and `compose ps`
#      agree on what "healthy" means.
wait_for_api() {
  local url="${API_URL}/api/auth/login"
  local timeout="${WIFIHAVEN_WAIT_TIMEOUT:-90}"
  local start elapsed code
  start=$(date +%s)
  elapsed=0
  step "Waiting for API to come up"
  printf "  "
  while [ "$elapsed" -lt "$timeout" ]; do
    code="$(curl -s -o /dev/null -w '%{http_code}' \
      -X POST -H 'content-type: application/json' -d '{}' \
      "$url" 2>/dev/null || true)"
    if [ "$code" = "400" ] || [ "$code" = "401" ]; then
      echo
      ok "API healthy at ${API_URL}"
      return 0
    fi
    printf "."
    sleep 2
    elapsed=$(( $(date +%s) - start ))
  done
  echo
  c_red "  ✗ API did not become healthy in ${timeout}s"
  echo
  echo "Recent api container logs:"
  echo "------------------------------------------------------------------"
  docker compose -f docker-compose.prod.yml --env-file .env logs api --tail=100 || true
  echo "------------------------------------------------------------------"
  echo "Container status:"
  docker compose -f docker-compose.prod.yml --env-file .env ps || true
  echo
  echo "Common causes:"
  echo "  - DB credentials in .env don't match what postgres was created with"
  echo "    (try: docker compose -f docker-compose.prod.yml --env-file .env down -v"
  echo "     and re-run install.sh)"
  echo "  - WIFIHAVEN_JWT_SECRET missing or empty in .env"
  echo "  - Postgres still initializing (rare; retry install.sh once)"
  return 1
}

wait_for_api || exit 1

# ── 8. Rotate admin password ──────────────────────────────────────────────

step "Rotating default admin password"

NEW_PW="${WIFIHAVEN_NEW_ADMIN_PW:-}"
ALREADY_ROTATED=0

# If 'changeme' no longer logs in, the password has already been rotated.
login_code="$(curl -s -o /dev/null -w '%{http_code}' \
  -X POST -H 'content-type: application/json' \
  -d '{"username":"admin","password":"changeme"}' \
  "${API_URL}/api/auth/login" || true)"

if [ "$login_code" != "200" ]; then
  ALREADY_ROTATED=1
  ok "admin password is already rotated (login with 'changeme' returned ${login_code})"
fi

if [ "$ALREADY_ROTATED" -eq 0 ]; then
  if [ -z "$NEW_PW" ]; then
    if noninteractive; then
      warn "WIFIHAVEN_NEW_ADMIN_PW not set — leaving the default 'admin/changeme' in place."
      warn "Rotate it manually before exposing the API."
    else
      while [ -z "$NEW_PW" ]; do
        read -r -s -p "  New password for admin (min 8 chars): " NEW_PW </dev/tty; echo
        if [ "${#NEW_PW}" -lt 8 ]; then
          warn "Too short. Try again."
          NEW_PW=""
          continue
        fi
        read -r -s -p "  Confirm: " CONFIRM </dev/tty; echo
        if [ "$NEW_PW" != "$CONFIRM" ]; then
          warn "Mismatch. Try again."
          NEW_PW=""
        fi
      done
    fi
  fi

  if [ -n "$NEW_PW" ]; then
    # /api/auth/change-password requires a valid bearer token and takes
    # {currentPassword, newPassword} (the username comes from the JWT
    # subject). Log in with admin/changeme to get the token first, then
    # post the change.
    json_pw="$(printf '%s' "$NEW_PW" \
      | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))' 2>/dev/null \
      || printf '"%s"' "${NEW_PW//\"/\\\"}")"
    login_body='{"username":"admin","password":"changeme"}'
    token="$(curl -s -X POST -H 'content-type: application/json' \
      -d "$login_body" "${API_URL}/api/auth/login" \
      | python3 -c 'import json,sys; print(json.load(sys.stdin).get("token",""))' 2>/dev/null \
      || true)"
    if [ -z "$token" ]; then
      warn "Could not obtain admin token to rotate password. Rotate manually."
    else
      body="$(printf '{"currentPassword":"changeme","newPassword":%s}' "$json_pw")"
      rot_code="$(curl -s -o /dev/null -w '%{http_code}' \
        -X POST -H 'content-type: application/json' \
        -H "authorization: Bearer ${token}" \
        -d "$body" "${API_URL}/api/auth/change-password" || true)"
      if [ "$rot_code" = "200" ]; then
        ok "admin password rotated"
      else
        warn "Password rotation returned HTTP ${rot_code}. Rotate manually:"
        warn "  TOKEN=\$(curl -s -X POST -H 'content-type: application/json' \\"
        warn "    -d '{\"username\":\"admin\",\"password\":\"changeme\"}' \\"
        warn "    ${API_URL}/api/auth/login | jq -r .token)"
        warn "  curl -X POST -H 'content-type: application/json' \\"
        warn "    -H \"authorization: Bearer \$TOKEN\" \\"
        warn "    -d '{\"currentPassword\":\"changeme\",\"newPassword\":\"...\"}' \\"
        warn "    ${API_URL}/api/auth/change-password"
      fi
    fi
  fi
fi

# ── 9. Ensure stack restarts on boot ──────────────────────────────────────
#
# The compose file already declares `restart: unless-stopped`, so the
# api + postgres containers come back whenever the docker daemon does.
# That only helps across reboots if docker.service is enabled at boot.
# Most distros enable it by default at install time, but not all (e.g.
# fresh Debian, some minimal cloud images), so check and offer to fix.
step "Configuring restart-on-reboot"

if ! command -v systemctl >/dev/null 2>&1; then
  warn "systemctl not found — can't verify docker is enabled at boot."
  warn "Make sure your init system starts docker on boot, or the stack"
  warn "won't come back after a reboot."
elif systemctl is-enabled docker.service >/dev/null 2>&1 \
     || systemctl is-enabled docker.socket >/dev/null 2>&1; then
  ok "docker is enabled at boot — stack will restart automatically (compose 'restart: unless-stopped')"
else
  warn "docker is not enabled to start at boot — the stack will NOT come back after a reboot."
  enable_now=""
  if noninteractive; then
    enable_now="N"
    warn "Enable it with:  sudo systemctl enable --now docker"
  else
    read -r -p "  Run 'sudo systemctl enable --now docker' now? [Y/n]: " enable_now </dev/tty
    enable_now="${enable_now:-Y}"
  fi
  if [[ "$enable_now" =~ ^[Yy] ]]; then
    if sudo systemctl enable --now docker 2>&1 | sed 's/^/    /'; then
      ok "docker enabled at boot"
    else
      warn "Could not enable docker. Run manually:  sudo systemctl enable --now docker"
    fi
  fi
fi

# ── 9b. Install auto-update timer ─────────────────────────────────────────
#
# Drops wifihaven-update.service + .timer into /etc/systemd/system/ and
# enables the timer so the host pulls a fresh image every 5 minutes. This
# is what closes the deploy gap for issue #254: without it, fixes that
# land on `main` don't reach existing installs until someone ssh's in to
# run update.sh by hand.
#
# Idempotent: `install -m 0644` overwrites cleanly, daemon-reload is a
# no-op when nothing changed, and `systemctl enable --now` on an
# already-enabled/active timer succeeds without error.
step "Installing auto-update timer"

SUDO=""
if ! command -v systemctl >/dev/null 2>&1; then
  warn "systemctl not found — skipping auto-update timer."
  warn "Update manually with ${WIFIHAVEN_INSTALL_DIR}/update.sh, or install a"
  warn "cron entry that runs it on your preferred cadence."
else
  if [ "$(id -u)" -ne 0 ]; then
    SUDO="sudo"
  fi

  UNIT_TMP="$(mktemp -d)"
  if curl -fsSL "${REPO_RAW}/deploy/systemd/wifihaven-update.service" \
        -o "${UNIT_TMP}/wifihaven-update.service" \
     && curl -fsSL "${REPO_RAW}/deploy/systemd/wifihaven-update.timer" \
        -o "${UNIT_TMP}/wifihaven-update.timer"; then
    # The shipped unit hard-codes /opt/wifihaven/deploy as the
    # WorkingDirectory (matches the documented system-wide install
    # path). Patch it to the actual install dir so user-mode installs
    # at $HOME/.wifihaven also get auto-update.
    if [ "$WIFIHAVEN_INSTALL_DIR" != "/opt/wifihaven" ]; then
      sed -i.bak "s|^WorkingDirectory=/opt/wifihaven/deploy$|WorkingDirectory=${WIFIHAVEN_INSTALL_DIR}|" \
        "${UNIT_TMP}/wifihaven-update.service"
      rm -f "${UNIT_TMP}/wifihaven-update.service.bak"
    fi

    install_ok=1
    $SUDO install -m 0644 "${UNIT_TMP}/wifihaven-update.service" \
      /etc/systemd/system/wifihaven-update.service || install_ok=0
    $SUDO install -m 0644 "${UNIT_TMP}/wifihaven-update.timer" \
      /etc/systemd/system/wifihaven-update.timer || install_ok=0

    if [ "$install_ok" -eq 1 ] \
       && $SUDO systemctl daemon-reload \
       && $SUDO systemctl enable --now wifihaven-update.timer; then
      ok "wifihaven-update.timer enabled (polls ghcr.io daily)"
      ok "Disable with:  ${SUDO:+sudo }systemctl disable --now wifihaven-update.timer"
    else
      warn "Could not install/enable wifihaven-update.timer."
      warn "Install manually — see docs/deploy.md §1.3."
    fi
  else
    warn "Could not download systemd unit files from ${REPO_RAW}/deploy/systemd/."
    warn "Auto-update timer not installed — see docs/deploy.md §1.3."
  fi
  rm -rf "${UNIT_TMP}"
fi

# ── 10. Done ──────────────────────────────────────────────────────────────

echo
c_green "═══════════════════════════════════════════════════════════════"
c_green "  WifiHaven API is up at ${API_URL}"
c_green "═══════════════════════════════════════════════════════════════"
cat <<EOF

  Install dir : ${WIFIHAVEN_INSTALL_DIR}
  Update      : ${WIFIHAVEN_INSTALL_DIR}/update.sh    # pull latest images and restart
  Restart     : ${WIFIHAVEN_INSTALL_DIR}/restart.sh
  Stop        : ${WIFIHAVEN_INSTALL_DIR}/stop.sh      # data preserved
  Start       : ${WIFIHAVEN_INSTALL_DIR}/start.sh
  Logs        : ${WIFIHAVEN_INSTALL_DIR}/logs.sh      # tail api logs
  Status      : ${WIFIHAVEN_INSTALL_DIR}/status.sh

Next steps:
  1. (optional) Put a TLS-terminating reverse proxy (Caddy / nginx) in
     front of the API. See docs/install-api.md §7.
  2. Log in at ${API_URL} as 'admin' and head to Routers → Add router to
     enroll your OpenWRT gateway. Then follow docs/install-openwrt.md.

  Auto-update is on by default (wifihaven-update.timer, every 5 minutes).
  To turn it off: ${SUDO:+sudo }systemctl disable --now wifihaven-update.timer
     See docs/deploy.md §1.3.
EOF
