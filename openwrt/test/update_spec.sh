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

# 4a. Keeps the openwrt-latest URL only as a 404 fallback.
grep -q 'releases/tags/openwrt-latest' "$SCRIPT" \
  && check "keeps openwrt-latest as fallback" ok \
  || check "keeps openwrt-latest as fallback" "missing fallback endpoint"

# 4b. Persists the last-installed version under /var/lib/wifihaven.
grep -q 'last_update_version' "$SCRIPT" \
  && check "persists last-installed version under /var/lib/wifihaven" ok \
  || check "persists last-installed version under /var/lib/wifihaven" "missing version stamp"

# 4c. No leftover #870/published_at digest logic.
grep -q '@.published_at\|@.assets\[.*\].digest' "$SCRIPT" \
  && check "no leftover digest/published_at decision logic" "still references digest/published_at" \
  || check "no leftover digest/published_at decision logic" ok

# 4d. Installs via apk add --allow-untrusted on apk path.
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

setup_mocks() {
  TESTDIR=$(mktemp -d -t wifihaven-update-spec.XXXXXX)
  BINDIR="$TESTDIR/bin"
  STATE="$TESTDIR/state"
  VERS_DIR="$TESTDIR/version"
  mkdir -p "$BINDIR" "$STATE" "$VERS_DIR"

  cat > "$BINDIR/logger" <<EOF
#!/bin/sh
shift 2
printf '%s\n' "\$*" >> "$TESTDIR/logger.out"
EOF

  # Mock curl: META fetch (no -o, or -o /tmp/wifihaven-update.meta with -w
  # for HTTP code capture) vs asset download (-o TMP).
  # $MOCK_HTTP_CODE controls the simulated HTTP status (default 200).
  # $CURL_DOWNLOAD_FAIL=1 makes asset download fail.
  cat > "$BINDIR/curl" <<'CURL_EOF'
#!/bin/sh
out=""
write_code=""
while [ $# -gt 0 ]; do
  case "$1" in
    -o) out="$2"; shift 2;;
    -w) write_code="$2"; shift 2;;
    -*) shift;;
    *) url="$1"; shift;;
  esac
done
if [ -n "$write_code" ]; then
  # Meta fetch with HTTP code capture. Without -f, real curl exits 0 even
  # on 4xx and just emits the body + write_out code; mirror that here.
  code="${MOCK_HTTP_CODE:-200}"
  case "$code" in
    200) printf '%s' "${MOCK_META_200:-$DEFAULT_META}" > "$out";;
    *)   : > "$out";;
  esac
  printf '%s' "$code"
  exit 0
fi
if [ -n "$out" ]; then
  # Asset download.
  if [ "${CURL_DOWNLOAD_FAIL:-0}" = "1" ]; then exit 22; fi
  printf 'fakepkg' > "$out"
  exit 0
fi
# Bare GET (used for the fallback openwrt-latest fetch).
printf '%s\n' "${MOCK_META_FALLBACK:-$DEFAULT_FALLBACK_META}"
CURL_EOF

  # Pin-mock jsonfilter: only the expressions wifihaven-update queries.
  # META lists luci-app first to verify pick logic skips it, then the
  # apk + ipk siblings.
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
tag=$(printf '%s' "$input" | sed -n 's/.*"tag_name":"\([^"]*\)".*/\1/p')
case "$expr" in
  '@.tag_name') echo "$tag";;
  '@.assets[0].name') echo "luci-app-wifihaven_0.2.8-1_all.ipk";;
  '@.assets[0].browser_download_url') echo "https://example.com/luci.ipk";;
  '@.assets[1].name') echo "wifihaven_0.2.8-1_all.apk";;
  '@.assets[1].browser_download_url') echo "https://example.com/wifihaven.apk";;
  '@.assets[2].name') echo "wifihaven_0.2.8-1_all.ipk";;
  '@.assets[2].browser_download_url') echo "https://example.com/wifihaven.ipk";;
  *) ;;
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

  DEFAULT_META='{"tag_name":"v0.2.8","assets":[{},{},{}]}'
  DEFAULT_FALLBACK_META='{"tag_name":"openwrt-latest","assets":[{},{},{}]}'
  export DEFAULT_META DEFAULT_FALLBACK_META
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

stamp_value() {
  cat "$STATE/last_update_version" 2>/dev/null || echo ""
}

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

# Case G: /releases/latest 404 → falls back to openwrt-latest, logs WARNING.
setup_mocks
mock_apk
printf '0.2.7\n' > "$VERS_DIR/VERSION"
MOCK_HTTP_CODE=404 PATH="$BINDIR:/usr/bin:/bin" "$PATCHED" >/dev/null 2>&1 || true
grep -q 'returned 404; falling back to openwrt-latest' "$TESTDIR/logger.out" \
  && check "[404 fallback] WARNING logged about fallback" ok \
  || check "[404 fallback] WARNING logged about fallback" "no fallback warning"
N=$(count_restart_calls)
[ "$N" = "1" ] \
  && check "[404 fallback] still installs via fallback tag" ok \
  || check "[404 fallback] still installs via fallback tag" "expected 1, got $N"
rm -rf "$TESTDIR"

printf "\nResults: %d passed, %d failed\n" "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
