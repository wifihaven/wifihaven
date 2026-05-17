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

# 4. Rolling-release mode: compares release published_at stamp instead of semver.
#    Reverts to semver compare alongside #244.
grep -q '@.published_at' "$SCRIPT" \
  && check "uses release published_at as freshness signal" ok \
  || check "uses release published_at as freshness signal" "missing published_at compare"

grep -q 'last_update_stamp' "$SCRIPT" \
  && check "persists last-applied stamp under /var/lib/wifihaven" ok \
  || check "persists last-applied stamp under /var/lib/wifihaven" "missing stamp file"

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

printf "\nResults: %d passed, %d failed\n" "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
