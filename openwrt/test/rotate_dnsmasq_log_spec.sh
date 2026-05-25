#!/bin/sh
# Tests for openwrt/files/usr/sbin/wifihaven-rotate-dnsmasq-log and its
# integration into the package build / install plumbing.
# Run from the openwrt/ directory:  sh test/rotate_dnsmasq_log_spec.sh
set -e

PASS=0; FAIL=0
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$ROOT/files/usr/sbin/wifihaven-rotate-dnsmasq-log"
MAKEFILE="$ROOT/Makefile"
BUILDER_IPK="$ROOT/build-ipk.sh"
BUILDER_APK="$ROOT/build-apk.sh"

check() {
  if [ "$2" = "ok" ]; then
    printf "  PASS: %s\n" "$1"; PASS=$((PASS + 1))
  else
    printf "  FAIL: %s — %s\n" "$1" "$2"; FAIL=$((FAIL + 1))
  fi
}

[ -f "$SCRIPT" ] || { printf "MISSING: %s\n" "$SCRIPT"; exit 1; }

# ---------------------------------------------------------------------------
# 1. Static checks on the rotation script itself
# ---------------------------------------------------------------------------

[ -x "$SCRIPT" ] \
  && check "rotation script is executable" ok \
  || check "rotation script is executable" "missing +x bit"

grep -q '/tmp/wifihaven-dnsmasq.log' "$SCRIPT" \
  && check "rotation script targets /tmp/wifihaven-dnsmasq.log" ok \
  || check "rotation script targets /tmp/wifihaven-dnsmasq.log" "wrong log path"

# Must use in-place truncation (: > "$LOG") so dnsmasq's open fd stays on the
# same inode. A rename would send dnsmasq's writes to an invisible inode.
grep -q ': > ' "$SCRIPT" \
  && check "rotation script truncates in place (copytruncate pattern)" ok \
  || check "rotation script truncates in place (copytruncate pattern)" \
           "missing in-place truncation — rename would orphan dnsmasq's fd"

# Size cap must be referenced.
grep -q 'MAX_BYTES\|20971520' "$SCRIPT" \
  && check "rotation script enforces a size cap (MAX_BYTES)" ok \
  || check "rotation script enforces a size cap (MAX_BYTES)" "no size cap found"

# ---------------------------------------------------------------------------
# 2. Package install plumbing: cron entry registered in all three producers
# ---------------------------------------------------------------------------

grep -q 'wifihaven-rotate-dnsmasq-log' "$MAKEFILE" \
  && check "Makefile installs wifihaven-rotate-dnsmasq-log" ok \
  || check "Makefile installs wifihaven-rotate-dnsmasq-log" \
           "missing from Makefile Package/wifihaven/install"

grep -q 'wifihaven-rotate-dnsmasq-log' "$MAKEFILE" \
  && grep -q '/etc/crontabs' "$MAKEFILE" \
  && check "Makefile postinst adds rotation cron entry" ok \
  || check "Makefile postinst adds rotation cron entry" \
           "cron install missing in Makefile postinst"

grep -q 'wifihaven-rotate-dnsmasq-log' "$BUILDER_IPK" \
  && check "build-ipk.sh postinst adds rotation cron entry" ok \
  || check "build-ipk.sh postinst adds rotation cron entry" \
           "missing rotation cron entry in build-ipk.sh"

grep -q 'wifihaven-rotate-dnsmasq-log' "$BUILDER_APK" \
  && check "build-apk.sh post-install adds rotation cron entry" ok \
  || check "build-apk.sh post-install adds rotation cron entry" \
           "missing rotation cron entry in build-apk.sh post-install"

# #898: the apk trigger script (fires on reinstall) must also carry the entry
# so force-reinstall repairs a missing cron line.
awk '/cat > "\$WORK\/trigger"/,/^TRIGGER$/' "$BUILDER_APK" \
  | grep -q 'wifihaven-rotate-dnsmasq-log' \
  && check "build-apk.sh trigger script adds rotation cron entry" ok \
  || check "build-apk.sh trigger script adds rotation cron entry" \
           "trigger heredoc missing wifihaven-rotate-dnsmasq-log"

# Cron expression: every 10 minutes.
grep -q '\*/10 \* \* \* \* /usr/sbin/wifihaven-rotate-dnsmasq-log' "$MAKEFILE" \
  && check "Makefile rotation cron runs every 10 minutes" ok \
  || check "Makefile rotation cron runs every 10 minutes" "wrong cron expression"

grep -q '\*/10 \* \* \* \* /usr/sbin/wifihaven-rotate-dnsmasq-log' "$BUILDER_IPK" \
  && check "build-ipk.sh rotation cron runs every 10 minutes" ok \
  || check "build-ipk.sh rotation cron runs every 10 minutes" "wrong cron expression"

grep -q '\*/10 \* \* \* \* /usr/sbin/wifihaven-rotate-dnsmasq-log' "$BUILDER_APK" \
  && check "build-apk.sh rotation cron runs every 10 minutes" ok \
  || check "build-apk.sh rotation cron runs every 10 minutes" "wrong cron expression"

# ---------------------------------------------------------------------------
# 3. Behavioural simulation: run the script with a fake LOG path
# ---------------------------------------------------------------------------

TESTDIR=$(mktemp -d)
trap 'rm -rf "$TESTDIR"' EXIT

FAKE_LOG="$TESTDIR/wifihaven-dnsmasq.log"
FAKE_LOG_PREV="${FAKE_LOG}.1"

# Rewrite the LOG variable in a copy of the script so it targets our tmpdir.
PATCHED="$TESTDIR/wifihaven-rotate-dnsmasq-log"
sed "s|^LOG=/tmp/wifihaven-dnsmasq.log|LOG=$FAKE_LOG|" "$SCRIPT" > "$PATCHED"
chmod +x "$PATCHED"

# Case A: file absent — script exits 0, no .1 created.
"$PATCHED"
[ ! -f "$FAKE_LOG_PREV" ] \
  && check "[no file] exits cleanly when log is absent" ok \
  || check "[no file] exits cleanly when log is absent" ".1 unexpectedly created"

# Case B: file present but small — no rotation.
printf 'small content\n' > "$FAKE_LOG"
"$PATCHED"
[ -f "$FAKE_LOG" ] \
  && check "[small file] log still present after no-op run" ok \
  || check "[small file] log still present after no-op run" "log was removed"
[ ! -f "$FAKE_LOG_PREV" ] \
  && check "[small file] no .1 created for small log" ok \
  || check "[small file] no .1 created for small log" ".1 unexpectedly created"

# Case C: file at or above threshold — rotation fires.
# Write exactly MAX_BYTES + 1 bytes (21 MB + 1 byte) to trigger rotation.
python3 -c "
import os, sys
path = sys.argv[1]
with open(path, 'wb') as f:
    f.write(b'A' * (20 * 1024 * 1024 + 1))
" "$FAKE_LOG"

"$PATCHED"

[ -f "$FAKE_LOG_PREV" ] \
  && check "[large file] .1 backup created" ok \
  || check "[large file] .1 backup created" ".1 not created"

[ -f "$FAKE_LOG" ] \
  && check "[large file] original log still exists after rotation" ok \
  || check "[large file] original log still exists after rotation" "log was deleted"

actual_size=$(wc -c < "$FAKE_LOG" 2>/dev/null)
[ "$actual_size" -eq 0 ] \
  && check "[large file] original log truncated to zero bytes" ok \
  || check "[large file] original log truncated to zero bytes" \
           "expected 0 bytes, got $actual_size"

prev_size=$(wc -c < "$FAKE_LOG_PREV" 2>/dev/null)
[ "$prev_size" -gt 0 ] \
  && check "[large file] .1 backup contains the original content" ok \
  || check "[large file] .1 backup contains the original content" ".1 is empty"

# Case D: simulate tail -F behaviour across rotation.
# After rotation, new content appended to the (now-empty) original is
# independent of .1. wifihaven-dns-tail uses `tail -n 0 -F` which follows
# by name; it detects the truncation within its next stat interval and
# reseeks to 0. Verify that the rotated .1 holds exactly the pre-rotation
# content and the live log is writable.
printf 'post-rotation line\n' >> "$FAKE_LOG"
grep -q 'post-rotation line' "$FAKE_LOG" \
  && check "[tail-F compat] new writes go to truncated original" ok \
  || check "[tail-F compat] new writes go to truncated original" "write to live log failed"
! grep -q 'post-rotation line' "$FAKE_LOG_PREV" \
  && check "[tail-F compat] pre-rotation content stays in .1" ok \
  || check "[tail-F compat] pre-rotation content stays in .1" ".1 was modified after rotation"

# Case E: second rotation discards the old .1 (no unbounded accumulation).
python3 -c "
import os, sys
path = sys.argv[1]
with open(path, 'wb') as f:
    f.write(b'B' * (20 * 1024 * 1024 + 1))
" "$FAKE_LOG"
"$PATCHED"

# .1 must now contain B's, not A's — the old .1 was overwritten.
old_prev=$(python3 -c "
import sys
with open(sys.argv[1], 'rb') as f:
    sample = f.read(4)
print('B' if sample == b'BBBB' else 'other')
" "$FAKE_LOG_PREV" 2>/dev/null || echo 'error')
[ "$old_prev" = "B" ] \
  && check "[second rotation] .1 overwritten with latest rotation" ok \
  || check "[second rotation] .1 overwritten with latest rotation" \
           "expected B content in .1, got: $old_prev"

printf "\nResults: %d passed, %d failed\n" "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
