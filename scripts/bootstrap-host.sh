#!/usr/bin/env bash
# One-shot bootstrap for a fresh Linux deploy target.
#
# Designed to be curl-piped:
#
#   curl -fsSL https://raw.githubusercontent.com/sameerparekh/familydns/main/scripts/bootstrap-host.sh | bash
#
# Or run from a checkout:  scripts/bootstrap-host.sh
#
# Run as your normal login user (NOT root). The script will use `sudo` for the
# small set of operations that genuinely need root (apt, /usr/local/bin,
# useradd, /etc/systemd, /etc/sudoers.d, /opt, /var/lib, /var/log, /etc).
# Everything else (the repo clone, scalafmt install via coursier, builds) runs
# as the familydns service user, never root.
#
# Idempotent: safe to re-run.

set -euo pipefail

REPO_URL="${FAMILYDNS_REPO_URL:-https://github.com/sameerparekh/familydns.git}"
BRANCH="${FAMILYDNS_BRANCH:-production}"
PREFIX="${FAMILYDNS_PREFIX:-/opt/familydns}"
USER_NAME="${FAMILYDNS_USER:-familydns}"
MILL_VERSION="${FAMILYDNS_MILL_VERSION:-1.1.5}"

if [ "$(uname -s)" != "Linux" ]; then
  echo "bootstrap-host.sh only supports Linux" >&2
  exit 1
fi

if [ "$EUID" -eq 0 ]; then
  echo "Do NOT run this script as root. Run as your login user; sudo will be invoked where needed." >&2
  exit 1
fi

# Make sure we have sudo and it works (prompt once up-front).
command -v sudo >/dev/null 2>&1 || { echo "sudo is required" >&2; exit 1; }
sudo -v

log() { echo "[bootstrap] $*"; }

# ── 1. System packages (root) ─────────────────────────────────────────────
log "Installing system packages (apt)..."
if command -v apt-get >/dev/null 2>&1; then
  sudo apt-get update -qq
  sudo apt-get install -y -qq \
    openjdk-21-jdk-headless ca-certificates curl git gnupg sudo
  if ! command -v node >/dev/null 2>&1 \
     || ! node --version 2>/dev/null | grep -qE '^v(22|23|24)\.'; then
    curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
    sudo apt-get install -y -qq nodejs
  fi
else
  echo "Only apt-based distros (Debian/Ubuntu) are supported by bootstrap." >&2
  exit 1
fi

# ── 2. Mill + coursier (root, into /usr/local/bin) ────────────────────────
if ! command -v mill >/dev/null 2>&1 \
   || ! mill --version 2>/dev/null | grep -q "$MILL_VERSION"; then
  log "Installing mill $MILL_VERSION..."
  TMP_MILL=$(mktemp)
  curl -fsSL "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/${MILL_VERSION}/mill-dist-${MILL_VERSION}-mill.sh" \
    -o "$TMP_MILL"
  chmod +x "$TMP_MILL"
  sudo mv "$TMP_MILL" /usr/local/bin/mill
fi

if ! command -v cs >/dev/null 2>&1; then
  log "Installing coursier..."
  ARCH="$(uname -m)"
  case "$ARCH" in
    x86_64)
      # Native launcher exists for x86_64 linux — use it.
      TMP_CS=$(mktemp)
      curl -fsSL "https://github.com/coursier/coursier/releases/latest/download/cs-x86_64-pc-linux.gz" \
        | gunzip > "$TMP_CS"
      chmod +x "$TMP_CS"
      sudo mv "$TMP_CS" /usr/local/bin/cs
      ;;
    aarch64|arm64)
      # No native launcher published for aarch64-linux — wrap the jar.
      sudo curl -fsSL \
        "https://github.com/coursier/coursier/releases/latest/download/coursier.jar" \
        -o /usr/local/lib/coursier.jar
      sudo tee /usr/local/bin/cs >/dev/null <<'CS'
#!/usr/bin/env bash
exec java -jar /usr/local/lib/coursier.jar "$@"
CS
      sudo chmod +x /usr/local/bin/cs
      ;;
    *) echo "unsupported arch $ARCH" >&2; exit 1 ;;
  esac
fi

# scalafmt is just a coursier app — install into /usr/local/bin as root so
# both the deploy user and admins can use it.
log "Installing scalafmt..."
sudo /usr/local/bin/cs install --install-dir /usr/local/bin --quiet scalafmt

# ── 3. Service user + filesystem layout (root) ────────────────────────────
if ! id -u "$USER_NAME" >/dev/null 2>&1; then
  log "Creating system user $USER_NAME..."
  sudo useradd --system --create-home --home-dir /var/lib/"$USER_NAME" \
               --shell /bin/bash "$USER_NAME"
fi

sudo install -d -o "$USER_NAME" -g "$USER_NAME" "$PREFIX"
sudo install -d -o "$USER_NAME" -g "$USER_NAME" /var/lib/"$USER_NAME"
sudo install -d -o "$USER_NAME" -g "$USER_NAME" /var/log/familydns
sudo install -d -m 0750 -o "$USER_NAME" -g "$USER_NAME" /etc/familydns

# ── 4. Clone or update the repo (as the deploy user) ──────────────────────
if [ ! -d "$PREFIX/repo/.git" ]; then
  log "Cloning $REPO_URL → $PREFIX/repo (branch $BRANCH)..."
  sudo -u "$USER_NAME" git clone --depth 50 --branch "$BRANCH" \
       "$REPO_URL" "$PREFIX/repo"
else
  log "Updating existing checkout in $PREFIX/repo..."
  sudo -u "$USER_NAME" git -C "$PREFIX/repo" fetch --prune origin
  sudo -u "$USER_NAME" git -C "$PREFIX/repo" checkout "$BRANCH"
  sudo -u "$USER_NAME" git -C "$PREFIX/repo" reset --hard "origin/$BRANCH"
fi

# ── 5. systemd units (symlinked from the repo so updates flow with git) ────
log "Installing systemd units..."
sudo install -d /etc/systemd/system
sudo ln -sfn "$PREFIX/repo/deploy/familydns-api.service" \
     /etc/systemd/system/familydns-api.service

sudo tee /etc/systemd/system/familydns-deploy.service >/dev/null <<EOF
[Unit]
Description=Pull and deploy FamilyDNS from $BRANCH
After=network-online.target

[Service]
Type=oneshot
User=$USER_NAME
Environment=FAMILYDNS_BRANCH=$BRANCH
WorkingDirectory=$PREFIX/repo
ExecStart=$PREFIX/repo/scripts/deploy.sh
EOF

sudo tee /etc/systemd/system/familydns-deploy.timer >/dev/null <<EOF
[Unit]
Description=Run familydns-deploy hourly

[Timer]
OnBootSec=2min
OnUnitActiveSec=1h
Unit=familydns-deploy.service

[Install]
WantedBy=timers.target
EOF

sudo systemctl daemon-reload

# ── 6. Sample config ──────────────────────────────────────────────────────
if [ ! -f /etc/familydns/application.conf ]; then
  log "Seeding /etc/familydns/application.conf from example..."
  sudo install -m 0640 -o "$USER_NAME" -g "$USER_NAME" \
    "$PREFIX/repo/config/application.conf.example" \
    /etc/familydns/application.conf
fi

# ── 7. Sudoers rule for the deploy user ───────────────────────────────────
sudo tee /etc/sudoers.d/familydns-deploy >/dev/null <<EOF
$USER_NAME ALL=(root) NOPASSWD: /bin/systemctl restart familydns-api.service, /usr/bin/install, /bin/mv, /bin/rm, /bin/cp, /usr/bin/tee
EOF
sudo chmod 0440 /etc/sudoers.d/familydns-deploy

cat <<MSG

[bootstrap] Done.

Next steps:
  1. Edit /etc/familydns/application.conf — set jwt.secret + db.password.
  2. (Optional) /etc/familydns/api.env for environment overrides.
  3. sudo systemctl enable --now familydns-api.service
  4. sudo systemctl enable --now familydns-deploy.timer
  5. Initial build/deploy:
       sudo -u $USER_NAME FAMILYDNS_BRANCH=$BRANCH $PREFIX/repo/scripts/deploy.sh

Tracking branch: $BRANCH (e2e-tested before promotion).
MSG
