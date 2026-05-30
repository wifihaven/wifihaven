#!/bin/sh
# Shell-level smoke tests for openwrt/files/usr/sbin/wifihaven-update
# and the cron-install postinst hook in openwrt/build-ipk.sh and Makefile.
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

# 4. Rolling-release mode: compares the matched asset's sha256 digest. GitHub
#    does NOT bump published_at on rolling-asset replacement (see #870), so
#    keying off published_at means routers never auto-update. Reverts to semver
#    compare alongside #244.
grep -q '@.assets\[.*\].digest' "$SCRIPT" \
  && check "uses asset digest as freshness signal" ok \
  || check "uses asset digest as freshness signal" "missing assets[*].digest extraction"

grep -q '@.published_at' "$SCRIPT" \
  && check "does not key off release published_at (#870)" "still references @.published_at" \
  || check "does not key off release published_at (#870)" ok

grep -q 'last_update_digest' "$SCRIPT" \
  && check "persists last-applied digest under /var/lib/wifihaven" ok \
  || check "persists last-applied digest under /var/lib/wifihaven" "missing digest file"

# 4b. Same-digest → no install: when STAMP_FILE matches LATEST_STAMP, exit 0
#     before downloading or invoking the package manager.
awk '/LAST_STAMP=.*STAMP_FILE/,/^fi/' "$SCRIPT" | grep -q 'exit 0' \
  && check "same digest short-circuits before install" ok \
  || check "same digest short-circuits before install" "no exit 0 in digest-match branch"

# 4c. Different-digest → install: the digest is written to STAMP_FILE only
#     after a successful install, so a failed install retries next run.
awk '/^esac/,0' "$SCRIPT" | grep -q "STAMP_FILE" \
  && check "different digest writes STAMP_FILE after install" ok \
  || check "different digest writes STAMP_FILE after install" "stamp not written post-install"

# 4c. Installs via apk add --allow-untrusted on apk path
grep -q 'apk add --allow-untrusted' "$SCRIPT" \
  && check "uses apk add --allow-untrusted on apk path" ok \
  || check "uses apk add --allow-untrusted on apk path" "missing apk install command"

# 4d. Filters for .apk assets on apk path (and still filters .ipk on opkg path)
grep -q "'\\\\.apk\$'" "$SCRIPT" \
  && check "filters .apk asset on apk path" ok \
  || check "filters .apk asset on apk path" "missing .apk regex"
grep -q "'\\\\.ipk\$'" "$SCRIPT" \
  && check "filters .ipk asset on opkg path" ok \
  || check "filters .ipk asset on opkg path" "missing .ipk regex"

# 4e. Rolling mode doesn't need to read installed version — the published_at
#     stamp is authoritative. Restored alongside #244.

# 5. Hits the rolling openwrt-latest pre-release (see #245; reverts with #244).
grep -q 'releases/tags/openwrt-latest' "$SCRIPT" \
  && check "fetches rolling openwrt-latest release" ok \
  || check "fetches rolling openwrt-latest release" "wrong API endpoint"

# 6. Installs with --force-reinstall (preserves conffiles)
grep -q 'opkg install --force-reinstall' "$SCRIPT" \
  && check "uses opkg install --force-reinstall" ok \
  || check "uses opkg install --force-reinstall" "must --force-reinstall"

# 7. Cleans up the downloaded package after install
grep -qE 'rm -f .*(\.ipk|\.apk|\$TMP|"\$TMP")' "$SCRIPT" \
  && check "cleans up downloaded package after install" ok \
  || check "cleans up downloaded package after install" "no rm -f for downloaded package"

# 8. Makefile installs the update script
grep -q 'wifihaven-update' "$MAKEFILE" \
  && check "Makefile installs wifihaven-update" ok \
  || check "Makefile installs wifihaven-update" "not referenced in Makefile"

# 9. Makefile declares /etc/config/wifihaven as conffile
grep -A1 'Package/wifihaven/conffiles' "$MAKEFILE" | grep -q '/etc/config/wifihaven' \
  && check "Makefile lists wifihaven conffile" ok \
  || check "Makefile lists wifihaven conffile" "missing conffiles stanza"

# 10. Makefile postinst installs the cron entry
grep -q "wifihaven-update" "$MAKEFILE" && \
grep -q "/etc/crontabs/root" "$MAKEFILE" \
  && check "Makefile postinst adds cron entry" ok \
  || check "Makefile postinst adds cron entry" "cron install missing in postinst"

# 11. build-ipk.sh ships the update script (it cp -r's files/, so reference is implicit;
#     check it does not exclude the new script and that postinst+conffiles are present)
grep -q '/etc/crontabs/root' "$BUILDER" \
  && check "build-ipk.sh postinst installs cron entry" ok \
  || check "build-ipk.sh postinst installs cron entry" "missing cron install"

grep -q 'conffiles' "$BUILDER" && \
grep -A2 'ctrl/conffiles' "$BUILDER" | grep -q '/etc/config/wifihaven' \
  && check "build-ipk.sh declares conffile" ok \
  || check "build-ipk.sh declares conffile" "missing conffiles file"

# 12. Cron interval is daily at 04:00 (issue #254 — see deploy.md §1.3 / §2.3)
grep -q '0 4 \* \* \* /usr/sbin/wifihaven-update' "$MAKEFILE" \
  && check "Makefile cron is daily at 04:00" ok \
  || check "Makefile cron is daily at 04:00" "wrong cron expression"

grep -q '0 4 \* \* \* /usr/sbin/wifihaven-update' "$BUILDER" \
  && check "build-ipk.sh cron is daily at 04:00" ok \
  || check "build-ipk.sh cron is daily at 04:00" "wrong cron expression"

# ---- Integration tests: run the script with mocked PATH ----
# #909: confirm that wifihaven-update calls /etc/init.d/wifihaven restart on the
# success paths (apk + opkg), skips it on the no-op and failure paths, and
# always writes the stamp before attempting the restart so a transient procd
# failure can't trigger an install loop.

# Each case writes mocks into $TESTDIR/bin, rewrites the script with sed to
# redirect STATE_DIR and the init.d path, runs it under a PATH that contains
# only our mocks, and inspects the resulting call logs.

setup_mocks() {
  TESTDIR=$(mktemp -d -t wifihaven-update-spec.XXXXXX)
  BINDIR="$TESTDIR/bin"
  STATE="$TESTDIR/state"
  mkdir -p "$BINDIR" "$STATE"

  cat > "$BINDIR/logger" <<EOF
#!/bin/sh
# usage: logger -t TAG message...; record full message minus '-t TAG'
shift 2
printf '%s\n' "\$*" >> "$TESTDIR/logger.out"
EOF

  cat > "$BINDIR/curl" <<'CURL_EOF'
#!/bin/sh
# Two callsites: META fetch (no -o) and asset download (-o TMP).
out=""
while [ $# -gt 0 ]; do
  case "$1" in
    -o) out="$2"; shift 2;;
    -*) shift;;
    *) shift;;
  esac
done
if [ -n "$out" ]; then
  if [ "${CURL_DOWNLOAD_FAIL:-0}" = "1" ]; then exit 22; fi
  printf 'fakepkg' > "$out"
else
  printf '%s\n' '{"assets":[{"browser_download_url":"https://example.com/wifihaven_0.2.8-1_all.apk","digest":"sha256:NEW"},{"browser_download_url":"https://example.com/wifihaven_0.2.8-1_all.ipk","digest":"sha256:NEW"}]}'
fi
CURL_EOF

  cat > "$BINDIR/jsonfilter" <<'EOF'
#!/bin/sh
# Pin-mock: only the expressions wifihaven-update queries. The META JSON is
# fixed in the mock curl above and lists an apk asset at index 0 and an ipk
# asset at index 1 so we can drive both PM branches without changing fixtures.
expr=""
while [ $# -gt 0 ]; do
  case "$1" in
    -e) expr="$2"; shift 2;;
    *) shift;;
  esac
done
cat >/dev/null
case "$expr" in
  '@.assets[0].browser_download_url') echo "https://example.com/wifihaven_0.2.8-1_all.apk";;
  '@.assets[0].digest') echo "sha256:NEW";;
  '@.assets[1].browser_download_url') echo "https://example.com/wifihaven_0.2.8-1_all.ipk";;
  '@.assets[1].digest') echo "sha256:NEW";;
  *) ;;  # empty → caller treats as end-of-array
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
      -e "s|/etc/init.d/wifihaven|$INITD|g" \
      "$SCRIPT" > "$PATCHED"
  chmod +x "$PATCHED" "$BINDIR"/*
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
  cat "$STATE/last_update_digest" 2>/dev/null || echo ""
}

# Case A: successful apk add → restart called exactly once, stamp written
setup_mocks
mock_apk
PATH="$BINDIR:/usr/bin:/bin" "$PATCHED" >/dev/null 2>&1 || true
N=$(count_restart_calls)
[ "$N" = "1" ] \
  && check "[apk ok] restart called exactly once" ok \
  || check "[apk ok] restart called exactly once" "expected 1, got $N"
[ "$(stamp_value)" = "sha256:NEW" ] \
  && check "[apk ok] stamp written on success" ok \
  || check "[apk ok] stamp written on success" "stamp = '$(stamp_value)'"
grep -q 'wifihaven service restarted' "$TESTDIR/logger.out" \
  && check "[apk ok] success log line emitted" ok \
  || check "[apk ok] success log line emitted" "no success log"
rm -rf "$TESTDIR"

# Case B: successful opkg install → restart called exactly once
setup_mocks
mock_opkg
PATH="$BINDIR:/usr/bin:/bin" "$PATCHED" >/dev/null 2>&1 || true
N=$(count_restart_calls)
[ "$N" = "1" ] \
  && check "[opkg ok] restart called exactly once" ok \
  || check "[opkg ok] restart called exactly once" "expected 1, got $N"
[ "$(stamp_value)" = "sha256:NEW" ] \
  && check "[opkg ok] stamp written on success" ok \
  || check "[opkg ok] stamp written on success" "stamp = '$(stamp_value)'"
rm -rf "$TESTDIR"

# Case C: no-op (stamp already matches) → restart NOT called, no apk invocation
setup_mocks
mock_apk
printf 'sha256:NEW\n' > "$STATE/last_update_digest"
PATH="$BINDIR:/usr/bin:/bin" "$PATCHED" >/dev/null 2>&1 || true
N=$(count_restart_calls)
[ "$N" = "0" ] \
  && check "[no-op] restart NOT called when stamp matches" ok \
  || check "[no-op] restart NOT called when stamp matches" "expected 0, got $N"
[ ! -f "$TESTDIR/apk.calls" ] \
  && check "[no-op] apk NOT invoked when stamp matches" ok \
  || check "[no-op] apk NOT invoked when stamp matches" "apk.calls exists"
rm -rf "$TESTDIR"

# Case D: install failure (apk exits nonzero) → restart NOT called, stamp not advanced
setup_mocks
mock_apk
MOCK_APK_EXIT=1 PATH="$BINDIR:/usr/bin:/bin" "$PATCHED" >/dev/null 2>&1 || true
N=$(count_restart_calls)
[ "$N" = "0" ] \
  && check "[apk fail] restart NOT called after failed install" ok \
  || check "[apk fail] restart NOT called after failed install" "expected 0, got $N"
[ -z "$(stamp_value)" ] \
  && check "[apk fail] stamp NOT advanced after failed install" ok \
  || check "[apk fail] stamp NOT advanced after failed install" "stamp = '$(stamp_value)'"
rm -rf "$TESTDIR"

# Case E: download failure → restart NOT called, no install attempted
setup_mocks
mock_apk
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
# Per #909: a wedged procd must NOT prevent the digest from advancing —
# otherwise every cron tick would re-install and never settle.
setup_mocks
mock_apk
MOCK_INITD_EXIT=1 PATH="$BINDIR:/usr/bin:/bin" "$PATCHED" >/dev/null 2>&1 || true
[ "$(stamp_value)" = "sha256:NEW" ] \
  && check "[restart fail] stamp still written" ok \
  || check "[restart fail] stamp still written" "stamp = '$(stamp_value)'"
grep -q 'restart failed' "$TESTDIR/logger.out" \
  && check "[restart fail] warning logged" ok \
  || check "[restart fail] warning logged" "no warning in log"
rm -rf "$TESTDIR"

# ---- #1178: asset selection across multi-version, multi-package manifests ----
# Before the fix, the asset regex was '\.apk$' / '\.ipk$' and the script took
# the FIRST matching asset. With #499's coordinated semver tags and #1041's
# luci-app-wifihaven package, `openwrt-latest` now publishes four matching
# .apk assets per release: {luci-app-,}wifihaven_{old,new}_all.apk. The first
# match was usually luci-app or an older wifihaven, and the script silently
# either installed the wrong package or short-circuited on a stale digest.

setup_multi_mocks() {
  TESTDIR=$(mktemp -d -t wifihaven-update-multi.XXXXXX)
  BINDIR="$TESTDIR/bin"
  STATE="$TESTDIR/state"
  mkdir -p "$BINDIR" "$STATE"
  : > "$TESTDIR/downloads.log"

  cat > "$BINDIR/logger" <<EOF
#!/bin/sh
shift 2
printf '%s\n' "\$*" >> "$TESTDIR/logger.out"
EOF

  # curl records the URL it was asked to download so the test can assert
  # which asset was picked. Meta fetches read MOCK_ASSETS_JSON; the smarter
  # jsonfilter mock below indexes into MOCK_ASSETS instead, so meta body is
  # only used to satisfy `[ -z "$META" ]`.
  cat > "$BINDIR/curl" <<EOF
#!/bin/sh
out=""
url=""
while [ \$# -gt 0 ]; do
  case "\$1" in
    -o) out="\$2"; shift 2;;
    -*) shift;;
    *) url="\$1"; shift;;
  esac
done
if [ -n "\$out" ]; then
  if [ "\${CURL_DOWNLOAD_FAIL:-0}" = "1" ]; then exit 22; fi
  printf '%s\n' "\$url" >> "$TESTDIR/downloads.log"
  printf 'fakepkg' > "\$out"
else
  printf '%s\n' "{\"assets\":[]}"
fi
EOF

  # Smart jsonfilter mock: walks MOCK_ASSETS (newline-separated "url|digest")
  # and answers any '@.assets[<i>].browser_download_url' / '...digest' query.
  cat > "$BINDIR/jsonfilter" <<'EOF'
#!/bin/sh
expr=""
while [ $# -gt 0 ]; do
  case "$1" in
    -e) expr="$2"; shift 2;;
    *) shift;;
  esac
done
cat >/dev/null
case "$expr" in
  *.browser_download_url) field=url ;;
  *.digest) field=digest ;;
  *) exit 0 ;;
esac
idx=$(printf '%s' "$expr" | sed -n 's/.*\[\([0-9]*\)\].*/\1/p')
line=$(printf '%s\n' "$MOCK_ASSETS" | sed -n "$((idx + 1))p")
[ -z "$line" ] && exit 0
url=${line%%|*}
digest=${line##*|}
case "$field" in
  url) printf '%s\n' "$url" ;;
  digest) printf '%s\n' "$digest" ;;
esac
EOF

  INITD="$BINDIR/wifihaven-initd"
  cat > "$INITD" <<EOF
#!/bin/sh
printf '%s\n' "\$*" >> "$TESTDIR/initd.calls"
exit 0
EOF

  PATCHED="$TESTDIR/wifihaven-update"
  sed -e "s|STATE_DIR=/var/lib/wifihaven|STATE_DIR=$STATE|" \
      -e "s|/etc/init.d/wifihaven|$INITD|g" \
      "$SCRIPT" > "$PATCHED"
  chmod +x "$PATCHED" "$BINDIR"/*
}

picked_url() { tail -n1 "$TESTDIR/downloads.log" 2>/dev/null; }

# Case G (#1178): multi-asset manifest with luci-app listed FIRST and the old
# wifioven listed BEFORE the new one. Fix must (a) reject luci-app and
# (b) pick the highest-version wifihaven, not the first matching asset.
setup_multi_mocks
mock_apk
export MOCK_ASSETS="https://example.com/luci-app-wifihaven_0.1.0-1_all.apk|sha256:LUCI_OLD
https://example.com/luci-app-wifihaven_0.2.8-1_all.apk|sha256:LUCI_NEW
https://example.com/wifihaven_0.1.0-1_all.apk|sha256:WH_OLD
https://example.com/wifihaven_0.2.8-1_all.apk|sha256:WH_NEW"
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
[ "$(stamp_value)" = "sha256:WH_NEW" ] \
  && check "[#1178 multi apk] stamp recorded matches the new asset" ok \
  || check "[#1178 multi apk] stamp recorded matches the new asset" "stamp = '$(stamp_value)'"
rm -rf "$TESTDIR"
unset MOCK_ASSETS

# Case H (#1178): manifest carries ONLY the old wifihaven version, and the
# stamp already matches that digest. Script must short-circuit — no download,
# no apk invocation. Guards against the regression where luci-app in the
# manifest fooled the script into "upgrading" to an older asset.
setup_multi_mocks
mock_apk
export MOCK_ASSETS="https://example.com/luci-app-wifihaven_0.1.0-1_all.apk|sha256:LUCI_OLD
https://example.com/wifihaven_0.1.0-1_all.apk|sha256:WH_OLD"
printf 'sha256:WH_OLD\n' > "$STATE/last_update_digest"
PATH="$BINDIR:/usr/bin:/bin" "$PATCHED" >/dev/null 2>&1 || true
[ ! -s "$TESTDIR/downloads.log" ] \
  && check "[#1178 old-only] no download when wifihaven stamp matches" ok \
  || check "[#1178 old-only] no download when wifihaven stamp matches" "downloaded: $(picked_url)"
[ ! -f "$TESTDIR/apk.calls" ] \
  && check "[#1178 old-only] apk NOT invoked when wifihaven stamp matches" ok \
  || check "[#1178 old-only] apk NOT invoked when wifihaven stamp matches" "apk.calls exists"
rm -rf "$TESTDIR"
unset MOCK_ASSETS

# Case I (#1178): opkg path gets the same tightening. .ipk asset names carry
# an architecture suffix (e.g. _all.ipk), so the ipk regex shape
# differs from apk's.
setup_multi_mocks
rm -f "$BINDIR/apk"
mock_opkg
export MOCK_ASSETS="https://example.com/luci-app-wifihaven_0.1.0-1_all.ipk|sha256:LUCI_OLD_IPK
https://example.com/luci-app-wifihaven_0.2.8-1_all.ipk|sha256:LUCI_NEW_IPK
https://example.com/wifihaven_0.1.0-1_all.ipk|sha256:WH_OLD_IPK
https://example.com/wifihaven_0.2.8-1_all.ipk|sha256:WH_NEW_IPK"
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
