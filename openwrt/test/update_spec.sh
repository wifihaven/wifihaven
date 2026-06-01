#!/bin/sh
# Shell-level smoke tests for openwrt/files/usr/sbin/wifihaven-update
# and the cron-install postinst hook in openwrt/build-ipk.sh and Makefile.
#
# Decision logic is unit-tested in test/update_spec.lua (#244); this script
# drives the wrapper end-to-end with mocked curl/jsonfilter/apk/opkg.
#
# Run from the openwrt/ directory:  sh test/update_spec.sh
set -e

PASS=0; FAIL=0
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$ROOT/files/usr/sbin/wifihaven-update"
MAKEFILE="$ROOT/Makefile"
BUILDER="$ROOT/build-ipk.sh"

check() {
  if [ "$2" = "ok" ]; then
    printf "  PASS: %s\n" "$1"; PASS=$((PASS + 1))
  else
    printf "  FAIL: %s — %s\n" "$1" "$2"; FAIL=$((FAIL + 1))
  fi
}

[ -f "$SCRIPT" ] || { printf "MISSING: %s\n" "$SCRIPT"; exit 1; }

# 1. Executable bit
[ -x "$SCRIPT" ] \
  && check "update script is executable" ok \
  || check "update script is executable" "missing +x bit"

# 2. Logs to syslog under wifihaven tag
grep -q 'logger -t wifihaven' "$SCRIPT" \
  && check "logs to syslog tag wifihaven" ok \
  || check "logs to syslog tag wifihaven" "no logger -t wifihaven"

# 3. Detects both package managers (apk for 24.10+, opkg for older)
grep -q 'command -v apk' "$SCRIPT" && grep -q 'command -v opkg' "$SCRIPT" \
  && check "detects both apk and opkg package managers" ok \
  || check "detects both apk and opkg package managers" "missing apk/opkg detection"

# 4. Hits /releases/latest (versioned semver release; #244).
grep -q 'releases/latest' "$SCRIPT" \
  && check "fetches /releases/latest" ok \
  || check "fetches /releases/latest" "wrong API endpoint"

# 4a. #1170: no openwrt-latest fallback endpoint — a 404 is a hard skip.
grep -q 'releases/tags/openwrt-latest' "$SCRIPT" \
  && check "[#1170] no openwrt-latest fallback endpoint remains" "still references fallback endpoint" \
  || check "[#1170] no openwrt-latest fallback endpoint remains" ok

# 4b. Persists the last-installed version under /var/lib/wifihaven.
grep -q 'last_update_version' "$SCRIPT" \
  && check "persists last-installed version under /var/lib/wifihaven" ok \
  || check "persists last-installed version under /var/lib/wifihaven" "missing version stamp"

# 4c. No leftover #870/published_at digest logic.
grep -q '@.published_at\|@.assets\[.*\].digest' "$SCRIPT" \
  && check "no leftover digest/published_at decision logic" "still references digest/published_at" \
  || check "no leftover digest/published_at decision logic" ok

# 4d. #1178: rejects luci-app via a tightened regex (not bare extension).
grep -q "PKG_NAME_RE='\^wifihaven_" "$SCRIPT" \
  && check "[#1178] uses tightened PKG_NAME_RE to reject luci-app" ok \
  || check "[#1178] uses tightened PKG_NAME_RE to reject luci-app" "missing PKG_NAME_RE"

# 4e. Installs via apk add --allow-untrusted on apk path.
grep -q 'apk add --allow-untrusted' "$SCRIPT" \
  && check "uses apk add --allow-untrusted on apk path" ok \
  || check "uses apk add --allow-untrusted on apk path" "missing apk install command"

# 5. Installs with --force-reinstall (preserves conffiles)
grep -q 'opkg install --force-reinstall' "$SCRIPT" \
  && check "uses opkg install --force-reinstall" ok \
  || check "uses opkg install --force-reinstall" "must --force-reinstall"

# 6. Cleans up the downloaded package after install
grep -qE 'rm -f .*(\.ipk|\.apk|\$TMP|"\$TMP")' "$SCRIPT" \
  && check "cleans up downloaded package after install" ok \
  || check "cleans up downloaded package after install" "no rm -f for downloaded package"

# 7. Makefile installs the update script
grep -q 'wifihaven-update' "$MAKEFILE" \
  && check "Makefile installs wifihaven-update" ok \
  || check "Makefile installs wifihaven-update" "not referenced in Makefile"

# 8. Makefile declares /etc/config/wifihaven as conffile
grep -A1 'Package/wifihaven/conffiles' "$MAKEFILE" | grep -q '/etc/config/wifihaven' \
  && check "Makefile lists wifihaven conffile" ok \
  || check "Makefile lists wifihaven conffile" "missing conffiles stanza"

# 9. Makefile postinst installs the cron entry
grep -q "wifihaven-update" "$MAKEFILE" && \
grep -q "/etc/crontabs/root" "$MAKEFILE" \
  && check "Makefile postinst adds cron entry" ok \
  || check "Makefile postinst adds cron entry" "cron install missing in postinst"

# 10. build-ipk.sh ships the update script
grep -q '/etc/crontabs/root' "$BUILDER" \
  && check "build-ipk.sh postinst installs cron entry" ok \
  || check "build-ipk.sh postinst installs cron entry" "missing cron install"

grep -q 'conffiles' "$BUILDER" && \
grep -A2 'ctrl/conffiles' "$BUILDER" | grep -q '/etc/config/wifihaven' \
  && check "build-ipk.sh declares conffile" ok \
  || check "build-ipk.sh declares conffile" "missing conffiles file"

# 11. Cron interval is daily at 04:00 (issue #254 — see deploy.md §1.3 / §2.3)
grep -q '0 4 \* \* \* /usr/sbin/wifihaven-update' "$MAKEFILE" \
  && check "Makefile cron is daily at 04:00" ok \
  || check "Makefile cron is daily at 04:00" "wrong cron expression"

grep -q '0 4 \* \* \* /usr/sbin/wifihaven-update' "$BUILDER" \
  && check "build-ipk.sh cron is daily at 04:00" ok \
  || check "build-ipk.sh cron is daily at 04:00" "wrong cron expression"

# ---- Integration tests: run the script with mocked PATH ----
# Each case writes mocks into $TESTDIR/bin, rewrites the script with sed to
# redirect STATE_DIR, the init.d path, and the VERSION file, then runs it
# under a PATH containing only the mocks. Inspects resulting call logs.

# MOCK_TAG     — tag_name returned by the META JSON (default "v0.2.8").
# MOCK_ASSETS  — newline-separated list of asset URLs (no digest needed).
#                Default lists luci-app + agent (apk + ipk) for v0.2.8.
# MOCK_HTTP_CODE — http_code returned for the /releases/latest fetch.

export DEFAULT_TAG="v0.2.8"
export DEFAULT_ASSETS='https://example.com/luci-app-wifihaven_0.2.8-1_all.apk
https://example.com/luci-app-wifihaven_0.2.8-1_all.ipk
https://example.com/wifihaven_0.2.8-1_all.apk
https://example.com/wifihaven_0.2.8-1_all.ipk'

setup_mocks() {
  TESTDIR=$(mktemp -d -t wifihaven-update-spec.XXXXXX)
  BINDIR="$TESTDIR/bin"
  STATE="$TESTDIR/state"
  VERS_DIR="$TESTDIR/version"
  mkdir -p "$BINDIR" "$STATE" "$VERS_DIR"
  : > "$TESTDIR/downloads.log"

  cat > "$BINDIR/logger" <<EOF
#!/bin/sh
shift 2
printf '%s\n' "\$*" >> "$TESTDIR/logger.out"
EOF

  # Mock curl. Two call shapes the script uses:
  #   1) Meta fetch with -o + -w (HTTP code capture): /releases/latest path.
  #   2) Asset download with -o (no -w): record URL into downloads.log.
  # (The bare-GET branch below is vestigial — the openwrt-latest fallback
  # was removed in #1170 — but harmless if ever hit.)
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
  tag="${MOCK_TAG:-$DEFAULT_TAG}"
  printf '{"tag_name":"%s","assets":[' "$tag"
  first=1
  printf '%s\n' "${MOCK_ASSETS:-$DEFAULT_ASSETS}" | while IFS= read -r u; do
    [ -z "$u" ] && continue
    [ $first -eq 0 ] && printf ','
    printf '{"browser_download_url":"%s"}' "$u"
    first=0
  done
  printf ']}'
}
if [ -n "$write_code" ]; then
  code="${MOCK_HTTP_CODE:-200}"
  case "$code" in
    200) emit_meta > "$out";;
    *)   : > "$out";;
  esac
  printf '%s' "$code"
  exit 0
fi
if [ -n "$out" ]; then
  if [ "${CURL_DOWNLOAD_FAIL:-0}" = "1" ]; then exit 22; fi
  printf '%s\n' "$url" >> "$ASSET_LOG"
  printf 'fakepkg' > "$out"
  exit 0
fi
# Bare GET — fallback openwrt-latest fetch.
emit_meta
CURL_EOF

  # Real-ish jsonfilter mock: reads stdin and extracts tag_name or
  # @.assets[N].browser_download_url. Supports only the queries used by
  # wifihaven-update.
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
    # Extract the Nth browser_download_url (0-indexed) from the JSON.
    printf '%s' "$input" \
      | tr ',' '\n' \
      | sed -n 's/.*"browser_download_url":"\([^"]*\)".*/\1/p' \
      | awk -v n="$idx" 'NR==n+1 {print}'
    ;;
esac
EOF

  INITD="$BINDIR/wifihaven-initd"
  cat > "$INITD" <<EOF
#!/bin/sh
printf '%s\n' "\$*" >> "$TESTDIR/initd.calls"
exit \${MOCK_INITD_EXIT:-0}
EOF

  PATCHED="$TESTDIR/wifihaven-update"
  sed -e "s|STATE_DIR=/var/lib/wifihaven|STATE_DIR=$STATE|" \
      -e "s|INSTALLED_VERSION_FILE=/usr/lib/wifihaven/VERSION|INSTALLED_VERSION_FILE=$VERS_DIR/VERSION|" \
      -e "s|/etc/init.d/wifihaven|$INITD|g" \
      "$SCRIPT" > "$PATCHED"
  chmod +x "$PATCHED" "$BINDIR"/*

  export ASSET_LOG="$TESTDIR/downloads.log"
}

mock_apk() {
  cat > "$BINDIR/apk" <<EOF
#!/bin/sh
printf '%s\n' "\$*" >> "$TESTDIR/apk.calls"
exit \${MOCK_APK_EXIT:-0}
EOF
  chmod +x "$BINDIR/apk"
}

mock_opkg() {
  cat > "$BINDIR/opkg" <<EOF
#!/bin/sh
printf '%s\n' "\$*" >> "$TESTDIR/opkg.calls"
exit \${MOCK_OPKG_EXIT:-0}
EOF
  chmod +x "$BINDIR/opkg"
}

count_restart_calls() {
  if [ -f "$TESTDIR/initd.calls" ]; then
    grep -c '^restart' "$TESTDIR/initd.calls" 2>/dev/null || echo 0
  else
    echo 0
  fi
}

stamp_value() { cat "$STATE/last_update_version" 2>/dev/null || echo ""; }
picked_url() { tail -n1 "$TESTDIR/downloads.log" 2>/dev/null; }

# Case A: installed != latest on apk → restart called once, stamp = 0.2.8.
setup_mocks
mock_apk
printf '0.2.7\n' > "$VERS_DIR/VERSION"
PATH="$BINDIR:/usr/bin:/bin" "$PATCHED" >/dev/null 2>&1 || true
N=$(count_restart_calls)
[ "$N" = "1" ] \
  && check "[apk install] restart called exactly once" ok \
  || check "[apk install] restart called exactly once" "expected 1, got $N"
[ "$(stamp_value)" = "0.2.8" ] \
  && check "[apk install] stamp written with new version" ok \
  || check "[apk install] stamp written with new version" "stamp = '$(stamp_value)'"
grep -q '^add --allow-untrusted /tmp/wifihaven-update.apk' "$TESTDIR/apk.calls" 2>/dev/null \
  && check "[apk install] apk add invoked on downloaded asset" ok \
  || check "[apk install] apk add invoked on downloaded asset" "apk not called with install command"
rm -rf "$TESTDIR"

# Case B: installed != latest on opkg → restart called once.
setup_mocks
mock_opkg
printf '0.2.7\n' > "$VERS_DIR/VERSION"
PATH="$BINDIR:/usr/bin:/bin" "$PATCHED" >/dev/null 2>&1 || true
N=$(count_restart_calls)
[ "$N" = "1" ] \
  && check "[opkg install] restart called exactly once" ok \
  || check "[opkg install] restart called exactly once" "expected 1, got $N"
[ "$(stamp_value)" = "0.2.8" ] \
  && check "[opkg install] stamp written with new version" ok \
  || check "[opkg install] stamp written with new version" "stamp = '$(stamp_value)'"
rm -rf "$TESTDIR"

# Case C: installed == latest → restart NOT called, no apk invocation.
setup_mocks
mock_apk
printf '0.2.8\n' > "$VERS_DIR/VERSION"
PATH="$BINDIR:/usr/bin:/bin" "$PATCHED" >/dev/null 2>&1 || true
N=$(count_restart_calls)
[ "$N" = "0" ] \
  && check "[no-op] restart NOT called when version matches" ok \
  || check "[no-op] restart NOT called when version matches" "expected 0, got $N"
[ ! -f "$TESTDIR/apk.calls" ] \
  && check "[no-op] apk NOT invoked when version matches" ok \
  || check "[no-op] apk NOT invoked when version matches" "apk.calls exists"
rm -rf "$TESTDIR"

# Case D: install failure (apk exits nonzero) → restart NOT called, stamp not advanced.
setup_mocks
mock_apk
printf '0.2.7\n' > "$VERS_DIR/VERSION"
MOCK_APK_EXIT=1 PATH="$BINDIR:/usr/bin:/bin" "$PATCHED" >/dev/null 2>&1 || true
N=$(count_restart_calls)
[ "$N" = "0" ] \
  && check "[apk fail] restart NOT called after failed install" ok \
  || check "[apk fail] restart NOT called after failed install" "expected 0, got $N"
[ -z "$(stamp_value)" ] \
  && check "[apk fail] stamp NOT advanced after failed install" ok \
  || check "[apk fail] stamp NOT advanced after failed install" "stamp = '$(stamp_value)'"
rm -rf "$TESTDIR"

# Case E: download failure → restart NOT called, no install attempted.
setup_mocks
mock_apk
printf '0.2.7\n' > "$VERS_DIR/VERSION"
CURL_DOWNLOAD_FAIL=1 PATH="$BINDIR:/usr/bin:/bin" "$PATCHED" >/dev/null 2>&1 || true
N=$(count_restart_calls)
[ "$N" = "0" ] \
  && check "[download fail] restart NOT called" ok \
  || check "[download fail] restart NOT called" "expected 0, got $N"
[ ! -f "$TESTDIR/apk.calls" ] \
  && check "[download fail] apk NOT invoked" ok \
  || check "[download fail] apk NOT invoked" "apk.calls exists"
rm -rf "$TESTDIR"
unset CURL_DOWNLOAD_FAIL

# Case F: stamp is written even when restart itself fails (no install loop).
setup_mocks
mock_apk
printf '0.2.7\n' > "$VERS_DIR/VERSION"
MOCK_INITD_EXIT=1 PATH="$BINDIR:/usr/bin:/bin" "$PATCHED" >/dev/null 2>&1 || true
[ "$(stamp_value)" = "0.2.8" ] \
  && check "[restart fail] stamp still written" ok \
  || check "[restart fail] stamp still written" "stamp = '$(stamp_value)'"
grep -q 'restart failed' "$TESTDIR/logger.out" \
  && check "[restart fail] warning logged" ok \
  || check "[restart fail] warning logged" "no warning in log"
rm -rf "$TESTDIR"

# Case G (#1170): /releases/latest 404 → hard skip. Logs an error, does NOT
# fall back to openwrt-latest, and never installs/restarts.
setup_mocks
mock_apk
printf '0.2.7\n' > "$VERS_DIR/VERSION"
MOCK_HTTP_CODE=404 PATH="$BINDIR:/usr/bin:/bin" "$PATCHED" >/dev/null 2>&1 || true
grep -q 'returned 404 (no versioned release published); skipping' "$TESTDIR/logger.out" \
  && check "[#1170 404 skip] error logged about missing versioned release" ok \
  || check "[#1170 404 skip] error logged about missing versioned release" "no 404-skip error"
N=$(count_restart_calls)
[ "$N" = "0" ] \
  && check "[#1170 404 skip] does NOT install/restart" ok \
  || check "[#1170 404 skip] does NOT install/restart" "expected 0, got $N"
[ ! -f "$TESTDIR/apk.calls" ] \
  && check "[#1170 404 skip] apk NOT invoked" ok \
  || check "[#1170 404 skip] apk NOT invoked" "apk.calls exists"
rm -rf "$TESTDIR"
unset MOCK_HTTP_CODE

# Case H (#1178): multi-asset manifest with luci-app listed FIRST and an
# older wifihaven listed BEFORE the new one. Script must (a) reject luci-app
# and (b) pick the highest-version wifihaven .apk, not the first match.
setup_mocks
mock_apk
printf '0.1.0-1\n' > "$VERS_DIR/VERSION"
export MOCK_ASSETS='https://example.com/luci-app-wifihaven_0.1.0-1_all.apk
https://example.com/luci-app-wifihaven_0.2.8-1_all.apk
https://example.com/wifihaven_0.1.0-1_all.apk
https://example.com/wifihaven_0.2.8-1_all.apk'
PATH="$BINDIR:/usr/bin:/bin" "$PATCHED" >/dev/null 2>&1 || true
URL=$(picked_url)
case "$URL" in
  *luci-app*) check "[#1178 multi apk] does not pick luci-app asset" "picked $URL" ;;
  *) check "[#1178 multi apk] does not pick luci-app asset" ok ;;
esac
case "$URL" in
  *wifihaven_0.2.8-1_all.apk) check "[#1178 multi apk] picks highest-version wifihaven asset" ok ;;
  *) check "[#1178 multi apk] picks highest-version wifihaven asset" "picked '$URL'" ;;
esac
rm -rf "$TESTDIR"
unset MOCK_ASSETS

# Case I (#1178): same tightening on the opkg/.ipk path.
setup_mocks
rm -f "$BINDIR/apk"
mock_opkg
printf '0.1.0-1\n' > "$VERS_DIR/VERSION"
export MOCK_ASSETS='https://example.com/luci-app-wifihaven_0.1.0-1_all.ipk
https://example.com/luci-app-wifihaven_0.2.8-1_all.ipk
https://example.com/wifihaven_0.1.0-1_all.ipk
https://example.com/wifihaven_0.2.8-1_all.ipk'
PATH="$BINDIR:/usr/bin:/bin" "$PATCHED" >/dev/null 2>&1 || true
URL=$(picked_url)
case "$URL" in
  *luci-app*) check "[#1178 multi ipk] does not pick luci-app .ipk" "picked $URL" ;;
  *) check "[#1178 multi ipk] does not pick luci-app .ipk" ok ;;
esac
case "$URL" in
  *wifihaven_0.2.8-1_all.ipk) check "[#1178 multi ipk] picks highest-version wifihaven .ipk" ok ;;
  *) check "[#1178 multi ipk] picks highest-version wifihaven .ipk" "picked '$URL'" ;;
esac
rm -rf "$TESTDIR"
unset MOCK_ASSETS

printf "\nResults: %d passed, %d failed\n" "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
