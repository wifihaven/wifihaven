#!/bin/sh
# Shell-level smoke tests for openwrt/files/usr/sbin/familydns-update
# and the cron-install postinst hook in openwrt/build-ipk.sh and Makefile.
# Run from the openwrt/ directory:  sh test/update_spec.sh
set -e

PASS=0; FAIL=0
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$ROOT/files/usr/sbin/familydns-update"
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

# 2. Logs to syslog under familydns tag
grep -q 'logger -t familydns' "$SCRIPT" \
  && check "logs to syslog tag familydns" ok \
  || check "logs to syslog tag familydns" "no logger -t familydns"

# 3. Bails silently on apk-only systems (no opkg)
grep -q 'command -v opkg' "$SCRIPT" \
  && check "checks for opkg presence (apk-only safe)" ok \
  || check "checks for opkg presence (apk-only safe)" "missing command -v opkg guard"

# 4. Uses opkg compare-versions for robust version compare
grep -q 'opkg compare-versions' "$SCRIPT" \
  && check "uses opkg compare-versions" ok \
  || check "uses opkg compare-versions" "missing — string compare alone breaks 1.10 vs 1.9"

# 5. Hits GitHub releases/latest API
grep -q 'releases/latest' "$SCRIPT" \
  && check "fetches GitHub latest release" ok \
  || check "fetches GitHub latest release" "wrong API endpoint"

# 6. Installs with --force-reinstall (preserves conffiles)
grep -q 'opkg install --force-reinstall' "$SCRIPT" \
  && check "uses opkg install --force-reinstall" ok \
  || check "uses opkg install --force-reinstall" "must --force-reinstall"

# 7. Cleans up the downloaded .ipk
grep -qE 'rm -f .*(\.ipk|\$TMP|"\$TMP")' "$SCRIPT" \
  && check "cleans up downloaded .ipk after install" ok \
  || check "cleans up downloaded .ipk after install" "no rm -f for downloaded .ipk"

# 8. Makefile installs the update script
grep -q 'familydns-update' "$MAKEFILE" \
  && check "Makefile installs familydns-update" ok \
  || check "Makefile installs familydns-update" "not referenced in Makefile"

# 9. Makefile declares /etc/config/familydns as conffile
grep -A1 'Package/familydns/conffiles' "$MAKEFILE" | grep -q '/etc/config/familydns' \
  && check "Makefile lists familydns conffile" ok \
  || check "Makefile lists familydns conffile" "missing conffiles stanza"

# 10. Makefile postinst installs the cron entry
grep -q "familydns-update" "$MAKEFILE" && \
grep -q "/etc/crontabs/root" "$MAKEFILE" \
  && check "Makefile postinst adds cron entry" ok \
  || check "Makefile postinst adds cron entry" "cron install missing in postinst"

# 11. build-ipk.sh ships the update script (it cp -r's files/, so reference is implicit;
#     check it does not exclude the new script and that postinst+conffiles are present)
grep -q '/etc/crontabs/root' "$BUILDER" \
  && check "build-ipk.sh postinst installs cron entry" ok \
  || check "build-ipk.sh postinst installs cron entry" "missing cron install"

grep -q 'conffiles' "$BUILDER" && \
grep -A2 'ctrl/conffiles' "$BUILDER" | grep -q '/etc/config/familydns' \
  && check "build-ipk.sh declares conffile" ok \
  || check "build-ipk.sh declares conffile" "missing conffiles file"

# 12. Cron interval is every 6 hours (matches design)
grep -q '0 \*/6 \* \* \* /usr/sbin/familydns-update' "$MAKEFILE" \
  && check "Makefile cron is every 6 hours" ok \
  || check "Makefile cron is every 6 hours" "wrong cron expression"

grep -q '0 \*/6 \* \* \* /usr/sbin/familydns-update' "$BUILDER" \
  && check "build-ipk.sh cron is every 6 hours" ok \
  || check "build-ipk.sh cron is every 6 hours" "wrong cron expression"

printf "\nResults: %d passed, %d failed\n" "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
