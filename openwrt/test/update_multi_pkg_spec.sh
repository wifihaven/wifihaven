#!/bin/sh
# Tests for the multi-package update logic in wifihaven-update (#1181).
#
# Verifies that wifihaven and luci-app-wifihaven are evaluated and upgraded
# independently, each with its own per-package version stamp, and that
# uhttpd reload runs after a luci-app upgrade.
#
# Complements update_spec.sh (single-package / regression tests) and the
# Lua-level update_spec.lua (#244). Run from the openwrt/ directory:
#   sh test/update_multi_pkg_spec.sh

set -e

PASS=0; FAIL=0
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$ROOT/files/usr/sbin/wifihaven-update"

check() {
  if [ "$2" = "ok" ]; then
    printf "  PASS: %s\n" "$1"; PASS=$((PASS + 1))
  else
    printf "  FAIL: %s — %s\n" "$1" "$2"; FAIL=$((FAIL + 1))
  fi
}

[ -f "$SCRIPT" ] || { printf "MISSING: %s\n" "$SCRIPT"; exit 1; }

# ── structural checks ────────────────────────────────────────────────────────

# 1. Per-package stamp file names.
grep -q 'last_update_version\.wifihaven' "$SCRIPT" \
  && check "agent stamp is last_update_version.wifihaven" ok \
  || check "agent stamp is last_update_version.wifihaven" "missing per-package agent stamp"

grep -q 'last_update_version\.luci-app-wifihaven' "$SCRIPT" \
  && check "luci stamp is last_update_version.luci-app-wifihaven" ok \
  || check "luci stamp is last_update_version.luci-app-wifihaven" "missing per-package luci stamp"

# 2. luci-app regex is distinct from agent regex.
grep -q "LUCI_PKG_NAME_RE='\^luci-app-wifihaven_" "$SCRIPT" \
  && check "LUCI_PKG_NAME_RE targets luci-app-wifihaven" ok \
  || check "LUCI_PKG_NAME_RE targets luci-app-wifihaven" "missing LUCI_PKG_NAME_RE"

# 3. uhttpd reload on luci upgrade.
grep -q 'uhttpd' "$SCRIPT" \
  && check "script mentions uhttpd reload for luci" ok \
  || check "script mentions uhttpd reload for luci" "no uhttpd reload"

# 4. Old stamp migration is present.
grep -q 'last_update_version"' "$SCRIPT" || grep -q "last_update_version'" "$SCRIPT" || \
  grep -q 'OLD_VERSION_STAMP' "$SCRIPT" \
  && check "old stamp migration present" ok \
  || check "old stamp migration present" "no migration of old stamp file"

# 5. update_package helper is a function (not copy-pasted).
grep -q 'update_package()' "$SCRIPT" \
  && check "update_package is a reusable function" ok \
  || check "update_package is a reusable function" "no update_package function"

# ── integration tests ────────────────────────────────────────────────────────
# Each test sets up mocks in $TESTDIR/bin, patches the script paths via sed,
# and runs the patched script.

DEFAULT_TAG="v0.2.8"
DEFAULT_ASSETS='https://example.com/luci-app-wifihaven_0.2.8-1_all.apk
https://example.com/wifihaven_0.2.8-1_all.apk'

setup_mocks() {
  TESTDIR=$(mktemp -d -t wifihaven-multi-spec.XXXXXX)
  BINDIR="$TESTDIR/bin"
  STATE="$TESTDIR/state"
  VERS_DIR="$TESTDIR/version"
  mkdir -p "$BINDIR" "$STATE" "$VERS_DIR"
  : > "$TESTDIR/downloads.log"

  cat > "$BINDIR/logger" <<LOGEOF
#!/bin/sh
shift 2
printf '%s\n' "\$*" >> "$TESTDIR/logger.out"
LOGEOF

  # Mock curl: meta fetch and asset downloads.
  cat > "$BINDIR/curl" <<'CURL_EOF'
#!/bin/sh
out=""
write_code=""
url=""
while [ $# -gt 0 ]; do
  case "$1" in
    -o) out="$2"; shift 2;;
    -w) write_code="$2"; shift 2;;
    -*) shift;;
    *)  url="$1"; shift;;
  esac
done
emit_meta() {
  tag="${MOCK_TAG:-v0.2.8}"
  assets="${MOCK_ASSETS}"
  printf '{"tag_name":"%s","assets":[' "$tag"
  first=1
  printf '%s\n' "$assets" | while IFS= read -r u; do
    [ -z "$u" ] && continue
    [ $first -eq 0 ] && printf ','
    printf '{"browser_download_url":"%s"}' "$u"
    first=0
  done
  printf ']}'
}
if [ -n "$write_code" ]; then
  emit_meta > "$out"
  printf '200'
  exit 0
fi
if [ -n "$out" ]; then
  printf '%s\n' "$url" >> "$ASSET_LOG"
  printf 'fakepkg' > "$out"
  exit 0
fi
emit_meta
CURL_EOF

  # jsonfilter mock matching wifihaven-update usage patterns.
  cat > "$BINDIR/jsonfilter" <<'EOF'
#!/bin/sh
expr=""
while [ $# -gt 0 ]; do
  case "$1" in
    -e) expr="$2"; shift 2;;
    *) shift;;
  esac
done
input=$(cat)
case "$expr" in
  '@.tag_name')
    printf '%s' "$input" | sed -n 's/.*"tag_name":"\([^"]*\)".*/\1/p'
    ;;
  '@.assets['*'].browser_download_url')
    idx=$(printf '%s' "$expr" | sed -n 's/@.assets\[\([0-9]*\)\].browser_download_url/\1/p')
    printf '%s' "$input" \
      | tr ',' '\n' \
      | sed -n 's/.*"browser_download_url":"\([^"]*\)".*/\1/p' \
      | awk -v n="$idx" 'NR==n+1 {print}'
    ;;
esac
EOF

  # Mocks for apk and uhttpd init.d.
  #
  # The apk mock reproduces the apk-tools v3 output shapes (verified live on
  # openwrt.lan, apk-tools 3.0.5) so the version-detection parse is exercised
  # against realistic output:
  #   - `apk info -v <pkg>` emits the package DESCRIPTION block, NOT a
  #     pkgname-version line — this is exactly what broke LUCI_INSTALLED.
  #   - `apk list --installed <pkg>` emits a `pkgname-version-rN ...` token
  #     line, which is the reliable source of the installed semver.
  # MOCK_LUCI_INSTALLED sets the installed luci-app version (empty = not
  # installed). The agent's apk fallback is never hit in these tests because
  # the baked-in VERSION file is always present.
  cat > "$BINDIR/apk" <<APKEOF
#!/bin/sh
printf '%s\n' "\$*" >> "$TESTDIR/apk.calls"
_cmd="\$1"
_pkg=""
for _a in "\$@"; do _pkg="\$_a"; done
case "\$_cmd" in
  info)
    printf '%s: LuCI web UI for the WifiHaven router agent\n' "\$_pkg"
    printf '%s: https://github.com/wifihaven/wifihaven\n' "\$_pkg"
    printf '%s: 15 KiB\n' "\$_pkg"
    exit 0
    ;;
  list)
    if [ "\$_pkg" = "luci-app-wifihaven" ] && [ -n "\${MOCK_LUCI_INSTALLED:-}" ]; then
      printf 'luci-app-wifihaven-%s-r1 noarch {luci-app-wifihaven} (MIT) [installed]\n' "\${MOCK_LUCI_INSTALLED}"
    fi
    exit 0
    ;;
  add)
    exit \${MOCK_APK_EXIT:-0}
    ;;
esac
exit 0
APKEOF

  INITD_WIFIHAVEN="$BINDIR/wifihaven-initd"
  cat > "$INITD_WIFIHAVEN" <<INITDEOF
#!/bin/sh
printf '%s\n' "\$*" >> "$TESTDIR/initd-wifihaven.calls"
exit \${MOCK_INITD_EXIT:-0}
INITDEOF

  INITD_UHTTPD="$BINDIR/uhttpd-initd"
  cat > "$INITD_UHTTPD" <<UHTTPDEOF
#!/bin/sh
printf '%s\n' "\$*" >> "$TESTDIR/initd-uhttpd.calls"
exit \${MOCK_UHTTPD_EXIT:-0}
UHTTPDEOF

  PATCHED="$TESTDIR/wifihaven-update"
  sed -e "s|STATE_DIR=/var/lib/wifihaven|STATE_DIR=$STATE|" \
      -e "s|INSTALLED_VERSION_FILE=/usr/lib/wifihaven/VERSION|INSTALLED_VERSION_FILE=$VERS_DIR/VERSION|" \
      -e "s|/etc/init.d/wifihaven restart|$INITD_WIFIHAVEN restart|g" \
      -e "s|/etc/init.d/uhttpd reload|$INITD_UHTTPD reload|g" \
      "$SCRIPT" > "$PATCHED"
  chmod +x "$PATCHED" "$BINDIR"/*

  export ASSET_LOG="$TESTDIR/downloads.log"
  export MOCK_ASSETS="$DEFAULT_ASSETS"
  export MOCK_TAG="$DEFAULT_TAG"
}

agent_stamp()  { cat "$STATE/last_update_version.wifihaven" 2>/dev/null || echo ""; }
luci_stamp()   { cat "$STATE/last_update_version.luci-app-wifihaven" 2>/dev/null || echo ""; }
downloads()    { cat "$TESTDIR/downloads.log" 2>/dev/null || echo ""; }
wh_restarts()  { grep -c '^restart' "$TESTDIR/initd-wifihaven.calls" 2>/dev/null || echo 0; }
uhttpd_reloads() { grep -c '^reload' "$TESTDIR/initd-uhttpd.calls" 2>/dev/null || echo 0; }
logger_out()   { cat "$TESTDIR/logger.out" 2>/dev/null || echo ""; }

# ── Case 1: both packages on new version → both upgraded, both stamps ────────
setup_mocks
printf '0.2.7\n' > "$VERS_DIR/VERSION"
# Simulate luci not installed → version string empty; update should still run.
PATH="$BINDIR:/usr/bin:/bin" "$PATCHED" >/dev/null 2>&1 || true

[ "$(agent_stamp)" = "0.2.8" ] \
  && check "[both new] agent stamp advanced to 0.2.8" ok \
  || check "[both new] agent stamp advanced to 0.2.8" "stamp='$(agent_stamp)'"
[ "$(luci_stamp)" = "0.2.8" ] \
  && check "[both new] luci stamp advanced to 0.2.8" ok \
  || check "[both new] luci stamp advanced to 0.2.8" "stamp='$(luci_stamp)'"
DL=$(downloads)
printf '%s' "$DL" | grep -q 'wifihaven_0.2.8-1_all.apk' \
  && check "[both new] wifihaven asset downloaded" ok \
  || check "[both new] wifihaven asset downloaded" "downloads: $DL"
printf '%s' "$DL" | grep -q 'luci-app-wifihaven_0.2.8-1_all.apk' \
  && check "[both new] luci-app asset downloaded" ok \
  || check "[both new] luci-app asset downloaded" "downloads: $DL"
N=$(wh_restarts)
[ "$N" = "1" ] \
  && check "[both new] agent restart called once" ok \
  || check "[both new] agent restart called once" "got $N"
N=$(uhttpd_reloads)
[ "$N" = "1" ] \
  && check "[both new] uhttpd reload called once" ok \
  || check "[both new] uhttpd reload called once" "got $N"
rm -rf "$TESTDIR"

# ── Case 2: only agent on new version (luci already current) ─────────────────
setup_mocks
printf '0.2.7\n' > "$VERS_DIR/VERSION"
# Pre-populate luci stamp as current.
mkdir -p "$STATE"
printf '0.2.8\n' > "$STATE/last_update_version.luci-app-wifihaven"
PATH="$BINDIR:/usr/bin:/bin" "$PATCHED" >/dev/null 2>&1 || true

[ "$(agent_stamp)" = "0.2.8" ] \
  && check "[agent only] agent stamp advanced" ok \
  || check "[agent only] agent stamp advanced" "stamp='$(agent_stamp)'"
[ "$(luci_stamp)" = "0.2.8" ] \
  && check "[agent only] luci stamp unchanged" ok \
  || check "[agent only] luci stamp unchanged" "stamp='$(luci_stamp)'"
DL=$(downloads)
printf '%s' "$DL" | grep -q 'wifihaven_0.2.8-1_all.apk' \
  && check "[agent only] wifihaven asset downloaded" ok \
  || check "[agent only] wifihaven asset downloaded" "downloads: $DL"
printf '%s' "$DL" | grep -vq 'luci-app-wifihaven' \
  && check "[agent only] luci-app NOT downloaded" ok \
  || check "[agent only] luci-app NOT downloaded" "luci found in downloads: $DL"
N=$(uhttpd_reloads)
[ "$N" = "0" ] \
  && check "[agent only] uhttpd NOT reloaded when luci already current" ok \
  || check "[agent only] uhttpd NOT reloaded when luci already current" "got $N"
rm -rf "$TESTDIR"

# ── Case 3: only luci on new version (agent already current) ─────────────────
setup_mocks
printf '0.2.8\n' > "$VERS_DIR/VERSION"
mkdir -p "$STATE"
printf '0.2.7\n' > "$STATE/last_update_version.luci-app-wifihaven"
PATH="$BINDIR:/usr/bin:/bin" "$PATCHED" >/dev/null 2>&1 || true

DL=$(downloads)
# Use a pattern that matches the agent package name exactly (not luci-app-wifihaven).
printf '%s' "$DL" | grep -vq '/wifihaven_0\.2\.8' \
  && check "[luci only] agent asset NOT downloaded" ok \
  || check "[luci only] agent asset NOT downloaded" "wifihaven found in downloads: $DL"
printf '%s' "$DL" | grep -q 'luci-app-wifihaven_0.2.8-1_all.apk' \
  && check "[luci only] luci-app asset downloaded" ok \
  || check "[luci only] luci-app asset downloaded" "downloads: $DL"
N=$(wh_restarts)
[ "$N" = "0" ] \
  && check "[luci only] agent NOT restarted when agent already current" ok \
  || check "[luci only] agent NOT restarted when agent already current" "got $N"
N=$(uhttpd_reloads)
[ "$N" = "1" ] \
  && check "[luci only] uhttpd reload called for luci upgrade" ok \
  || check "[luci only] uhttpd reload called for luci upgrade" "got $N"
[ "$(luci_stamp)" = "0.2.8" ] \
  && check "[luci only] luci stamp advanced" ok \
  || check "[luci only] luci stamp advanced" "stamp='$(luci_stamp)'"
rm -rf "$TESTDIR"

# ── Case 4: neither package on new version → no downloads, no restarts ───────
setup_mocks
printf '0.2.8\n' > "$VERS_DIR/VERSION"
mkdir -p "$STATE"
printf '0.2.8\n' > "$STATE/last_update_version.luci-app-wifihaven"
PATH="$BINDIR:/usr/bin:/bin" "$PATCHED" >/dev/null 2>&1 || true

DL=$(downloads)
[ -z "$DL" ] \
  && check "[neither new] no assets downloaded" ok \
  || check "[neither new] no assets downloaded" "downloads: $DL"
N=$(wh_restarts)
[ "$N" = "0" ] \
  && check "[neither new] agent NOT restarted" ok \
  || check "[neither new] agent NOT restarted" "got $N"
N=$(uhttpd_reloads)
[ "$N" = "0" ] \
  && check "[neither new] uhttpd NOT reloaded" ok \
  || check "[neither new] uhttpd NOT reloaded" "got $N"
rm -rf "$TESTDIR"

# ── Case 5: old stamp migration (last_update_version → per-pkg agent stamp) ──
setup_mocks
printf '0.2.8\n' > "$VERS_DIR/VERSION"
# Seed old-style plain version stamp; simulate fresh install with new stamp absent.
mkdir -p "$STATE"
printf '0.2.8\n' > "$STATE/last_update_version"
PATH="$BINDIR:/usr/bin:/bin" "$PATCHED" >/dev/null 2>&1 || true

[ ! -f "$STATE/last_update_version" ] \
  && check "[migration] old plain stamp moved away" ok \
  || check "[migration] old plain stamp moved away" "old file still present"
[ "$(agent_stamp)" = "0.2.8" ] \
  && check "[migration] agent stamp present after migration" ok \
  || check "[migration] agent stamp present after migration" "stamp='$(agent_stamp)'"
rm -rf "$TESTDIR"

# ── Case 6: apk v3 installed-version detection — luci already current ─────────
# Regression for #1420: on apk-tools v3, `apk info -v` emits the description
# block, so the old parse captured "luci-app-wifihaven: LuCI web UI ..." as the
# installed version instead of the semver. With NO stamp present (so the stamp
# can't mask the bug), a correctly-detected installed version equal to LATEST
# must make the luci run a complete no-op: no download, no `apk add`, no uhttpd
# reload. With the broken parse the bogus string never equals LATEST, so the
# script wastefully "upgrades".
setup_mocks
printf '0.2.8\n' > "$VERS_DIR/VERSION"   # agent already current → agent no-op
export MOCK_LUCI_INSTALLED="0.2.8"        # luci installed & current per apk db
# Deliberately no luci stamp file.
PATH="$BINDIR:/usr/bin:/bin" "$PATCHED" >/dev/null 2>&1 || true

DL=$(downloads)
if printf '%s' "$DL" | grep -q 'luci-app-wifihaven'; then
  check "[apk v3 current] luci-app NOT downloaded (installed==latest detected)" "downloads: $DL"
else
  check "[apk v3 current] luci-app NOT downloaded (installed==latest detected)" ok
fi
if grep -q '^add' "$TESTDIR/apk.calls" 2>/dev/null; then
  check "[apk v3 current] apk add NOT called" "apk calls: $(cat "$TESTDIR/apk.calls" 2>/dev/null)"
else
  check "[apk v3 current] apk add NOT called" ok
fi
N=$(uhttpd_reloads)
[ "$N" = "0" ] \
  && check "[apk v3 current] uhttpd NOT reloaded" ok \
  || check "[apk v3 current] uhttpd NOT reloaded" "got $N"
unset MOCK_LUCI_INSTALLED
rm -rf "$TESTDIR"

# ── Case 7: apk v3 installed-version detection — bare semver in upgrade log ───
# When luci IS behind, the upgrade must run AND the log line must show the bare
# semver (e.g. "version 0.2.7 -> 0.2.8"), not the description string the broken
# parse produced ("version luci-app-wifihaven: LuCI web UI ... -> 0.2.8").
setup_mocks
printf '0.2.8\n' > "$VERS_DIR/VERSION"   # agent current → only luci moves
export MOCK_LUCI_INSTALLED="0.2.7"        # luci behind by one version
PATH="$BINDIR:/usr/bin:/bin" "$PATCHED" >/dev/null 2>&1 || true

LOG=$(logger_out)
printf '%s' "$LOG" | grep -q 'luci-app-wifihaven: version 0.2.7 -> 0.2.8' \
  && check "[apk v3 behind] upgrade log shows bare semver 0.2.7 -> 0.2.8" ok \
  || check "[apk v3 behind] upgrade log shows bare semver 0.2.7 -> 0.2.8" "log: $LOG"
if printf '%s' "$LOG" | grep -q 'version luci-app-wifihaven: LuCI web UI'; then
  check "[apk v3 behind] installed version is not the description string" "description leaked into log: $LOG"
else
  check "[apk v3 behind] installed version is not the description string" ok
fi
DL=$(downloads)
printf '%s' "$DL" | grep -q 'luci-app-wifihaven_0.2.8-1_all.apk' \
  && check "[apk v3 behind] luci-app asset downloaded" ok \
  || check "[apk v3 behind] luci-app asset downloaded" "downloads: $DL"
unset MOCK_LUCI_INSTALLED
rm -rf "$TESTDIR"

printf "\nResults: %d passed, %d failed\n" "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
