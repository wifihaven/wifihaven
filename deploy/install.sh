#!/usr/bin/env bash
#
# FamilyDNS API — first-install bootstrap.
#
#   curl -fsSL https://raw.githubusercontent.com/sameerparekh/familydns/main/deploy/install.sh | bash
#
# Or, to review first (recommended):
#   curl -fsSL https://raw.githubusercontent.com/sameerparekh/familydns/main/deploy/install.sh -o install.sh
#   less install.sh
#   bash install.sh
#
# Re-running on an existing install is safe: the script detects an existing
# .env and offers to keep it, and `docker compose up -d` is idempotent.
#
# Env vars to skip prompts (useful for unattended installs):
#   FAMILYDNS_PREFIX         install path. Default: $HOME/.familydns when run
#                            as a normal user (no sudo needed), /opt/familydns
#                            when run as root. Set to /opt/familydns explicitly
#                            for a system-wide install (requires sudo/root).
#   FAMILYDNS_INSTALL_DIR    legacy alias for FAMILYDNS_PREFIX.
#   FAMILYDNS_API_HOST_PORT  host port to bind           (default: 8080)
#   FAMILYDNS_API_BIND       host interface to bind on   (default: 0.0.0.0)
#   FAMILYDNS_DNS_LOCATION   free-form location label    (default: home)
#   FAMILYDNS_NEW_ADMIN_PW   new admin password          (default: prompt)
#   FAMILYDNS_NONINTERACTIVE if set, never prompt; fail if any value missing.

set -euo pipefail

REPO_RAW="https://raw.githubusercontent.com/sameerparekh/familydns/main"
COMPOSE_URL="${REPO_RAW}/deploy/docker-compose.prod.yml"
ENV_EXAMPLE_URL="${REPO_RAW}/deploy/.env.example"

c_red()    { printf '\033[31m%s\033[0m\n' "$*"; }
c_green()  { printf '\033[32m%s\033[0m\n' "$*"; }
c_yellow() { printf '\033[33m%s\033[0m\n' "$*"; }
c_bold()   { printf '\033[1m%s\033[0m\n' "$*"; }

step() { echo; c_bold "▸ $*"; }
ok()   { c_green "  ✓ $*"; }
warn() { c_yellow "  ! $*"; }
die()  { c_red "  ✗ $*"; exit 1; }

is_tty() { [ -t 0 ] && [ -t 1 ]; }
noninteractive() { [ -n "${FAMILYDNS_NONINTERACTIVE:-}" ] || ! is_tty; }

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

# FAMILYDNS_PREFIX is the preferred name; FAMILYDNS_INSTALL_DIR is the legacy alias.
if [ -n "${FAMILYDNS_PREFIX:-}" ] && [ -z "${FAMILYDNS_INSTALL_DIR:-}" ]; then
  FAMILYDNS_INSTALL_DIR="$FAMILYDNS_PREFIX"
fi

# Default prefix: user-writable when not root (so `curl|bash` works without
# sudo), /opt/familydns when running as root.
if [ "$(id -u)" -eq 0 ]; then
  DEFAULT_PREFIX="/opt/familydns"
else
  DEFAULT_PREFIX="${HOME}/.familydns"
fi

prompt FAMILYDNS_INSTALL_DIR    "Install directory"                           "$DEFAULT_PREFIX"
prompt FAMILYDNS_API_HOST_PORT  "Host port for the API"                       "8080"
prompt FAMILYDNS_API_BIND       "Bind address (0.0.0.0 or 127.0.0.1)"         "0.0.0.0"
prompt FAMILYDNS_DNS_LOCATION   "Location label for query logs"               "home"

# ── 3. Install directory ──────────────────────────────────────────────────

step "Preparing $FAMILYDNS_INSTALL_DIR"

if [ ! -d "$FAMILYDNS_INSTALL_DIR" ]; then
  parent_dir="$(dirname "$FAMILYDNS_INSTALL_DIR")"
  if [ -w "$parent_dir" ]; then
    mkdir -p "$FAMILYDNS_INSTALL_DIR"
  elif [ "$(id -u)" -eq 0 ]; then
    mkdir -p "$FAMILYDNS_INSTALL_DIR"
  else
    # Need sudo to create the install dir. If stdin is not a tty (e.g.
    # `curl … | bash`), sudo can't prompt for a password and will fail
    # silently with "a password is required". Detect that up front and
    # tell the user how to recover.
    if ! [ -t 0 ]; then
      die "$(cat <<EOF

  This install needs root to write to ${FAMILYDNS_INSTALL_DIR}, but stdin is
  not a tty (curl|bash detected) so sudo cannot prompt for a password. Run
  one of:

    # 1. User-mode install (no sudo needed):
    curl -fsSL https://raw.githubusercontent.com/sameerparekh/familydns/main/deploy/install.sh \\
      | FAMILYDNS_PREFIX=\$HOME/.familydns bash

    # 2. Save and run with sudo:
    curl -fsSL https://raw.githubusercontent.com/sameerparekh/familydns/main/deploy/install.sh -o install.sh
    sudo bash install.sh

    # 3. Warm up sudo first, then pipe:
    sudo -v && curl -fsSL https://raw.githubusercontent.com/sameerparekh/familydns/main/deploy/install.sh | bash
EOF
)"
    fi
    sudo mkdir -p "$FAMILYDNS_INSTALL_DIR"
    sudo chown "$USER" "$FAMILYDNS_INSTALL_DIR"
  fi
fi
cd "$FAMILYDNS_INSTALL_DIR"
ok "$FAMILYDNS_INSTALL_DIR ready"

# ── 4. Fetch compose file ─────────────────────────────────────────────────

step "Fetching deploy files"

curl -fsSL "$COMPOSE_URL"     -o docker-compose.prod.yml
curl -fsSL "$ENV_EXAMPLE_URL" -o .env.example
ok "docker-compose.prod.yml + .env.example downloaded"

# ── 5. Create or reuse .env ───────────────────────────────────────────────

step "Generating .env"

KEEP_EXISTING_ENV=0
if [ -f .env ]; then
  if noninteractive; then
    warn "Existing .env found — keeping it (non-interactive)."
    KEEP_EXISTING_ENV=1
  else
    read -r -p "  An .env already exists. Keep it? [Y/n]: " keep </dev/tty
    keep="${keep:-Y}"
    if [[ "$keep" =~ ^[Yy] ]]; then
      KEEP_EXISTING_ENV=1
      ok "Keeping existing .env"
    fi
  fi
fi

if [ "$KEEP_EXISTING_ENV" -eq 0 ]; then
  DB_PASSWORD="$(genpw)"
  JWT_SECRET="$(gensecret)"
  umask 077
  cat > .env <<EOF
# Generated by deploy/install.sh on $(date -u +%Y-%m-%dT%H:%M:%SZ)
# Keep this file private — chmod 600.

FAMILYDNS_DB_NAME=familydns
FAMILYDNS_DB_USER=familydns
FAMILYDNS_DB_PASSWORD=${DB_PASSWORD}

FAMILYDNS_JWT_SECRET=${JWT_SECRET}
FAMILYDNS_JWT_HOURS=24

FAMILYDNS_API_BIND=${FAMILYDNS_API_BIND}
FAMILYDNS_API_PORT=${FAMILYDNS_API_HOST_PORT}

FAMILYDNS_DNS_LOCATION=${FAMILYDNS_DNS_LOCATION}
EOF
  chmod 600 .env
  ok "Wrote .env (db password and JWT secret auto-generated, chmod 600)"
fi

# ── 6. Pull + start ───────────────────────────────────────────────────────

step "Pulling image"
docker compose -f docker-compose.prod.yml --env-file .env pull
ok "Image pulled"

step "Starting stack"
docker compose -f docker-compose.prod.yml --env-file .env up -d
ok "Containers started"

# ── 7. Wait for health ────────────────────────────────────────────────────

step "Waiting for API to come up"

API_URL="http://127.0.0.1:${FAMILYDNS_API_HOST_PORT}"
HEALTHY=0
for i in $(seq 1 60); do
  code="$(curl -s -o /dev/null -w '%{http_code}' \
    -X POST -H 'content-type: application/json' \
    -d '{}' "${API_URL}/api/auth/login" || true)"
  if [ "$code" = "400" ] || [ "$code" = "401" ]; then
    HEALTHY=1
    break
  fi
  sleep 2
done

if [ "$HEALTHY" -eq 0 ]; then
  warn "API didn't become healthy within ~120s. Check 'docker compose logs api'."
  docker compose -f docker-compose.prod.yml --env-file .env ps || true
  exit 1
fi
ok "API is healthy at ${API_URL}"

# ── 8. Rotate admin password ──────────────────────────────────────────────

step "Rotating default admin password"

NEW_PW="${FAMILYDNS_NEW_ADMIN_PW:-}"
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
      warn "FAMILYDNS_NEW_ADMIN_PW not set — leaving the default 'admin/changeme' in place."
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
    body="$(printf '{"username":"admin","oldPassword":"changeme","newPassword":%s}' \
      "$(printf '%s' "$NEW_PW" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))' 2>/dev/null \
        || printf '"%s"' "${NEW_PW//\"/\\\"}")")"
    rot_code="$(curl -s -o /dev/null -w '%{http_code}' \
      -X POST -H 'content-type: application/json' \
      -d "$body" "${API_URL}/api/auth/change-password" || true)"
    if [ "$rot_code" = "200" ]; then
      ok "admin password rotated"
    else
      warn "Password rotation returned HTTP ${rot_code}. Rotate manually:"
      warn "  curl -X POST -H 'content-type: application/json' \\"
      warn "    -d '{\"username\":\"admin\",\"oldPassword\":\"changeme\",\"newPassword\":\"...\"}' \\"
      warn "    ${API_URL}/api/auth/change-password"
    fi
  fi
fi

# ── 9. Done ───────────────────────────────────────────────────────────────

echo
c_green "═══════════════════════════════════════════════════════════════"
c_green "  FamilyDNS API is up at ${API_URL}"
c_green "═══════════════════════════════════════════════════════════════"
cat <<EOF

  Install dir : ${FAMILYDNS_INSTALL_DIR}
  Compose     : docker compose -f docker-compose.prod.yml --env-file .env <cmd>
  Logs        : docker compose -f docker-compose.prod.yml --env-file .env logs -f api
  Stop        : docker compose -f docker-compose.prod.yml --env-file .env down

Next steps:
  1. (optional) Put a TLS-terminating reverse proxy (Caddy / nginx) in
     front of the API. See docs/install-api.md §7.
  2. (optional) Install the systemd auto-update timer. See docs/deploy.md §1.3.
  3. Log in at ${API_URL} as 'admin' and head to Routers → Add router to
     enroll your OpenWRT gateway. Then follow docs/install-openwrt.md.
EOF
