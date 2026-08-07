#!/bin/sh
# Functional test for #2608: the websocket transport is the DEFAULT transport.
#
# Three things are pinned here:
#
#   1. The shipped default is ON when the option is UNSET. Every place that
#      reads `wifihaven.ws.enabled` — the agent, the ws sidecar, the init
#      script — must fall back to "1", and the shipped /etc/config/wifihaven
#      must NOT carry an `option enabled` line inside `config ws 'ws'` (a
#      literal value there is what made "unset" unobservable in the first
#      place; see the migration note below).
#
#   2. The upgrade migration (/etc/uci-defaults/97-wifihaven-ws-default-on)
#      moves a router that never expressed an opinion onto ws, and NEVER
#      clobbers an explicit opt-out. UCI cannot distinguish "unset" from "set
#      to the shipped default", so explicitness is recorded out-of-band by a
#      one-shot marker (`wifihaven.ws.default_on_migrated`):
#        - marker absent + enabled=0  → that 0 came from the old shipped
#          conffile (the ONLY way a pre-#2608 router could carry it), so the
#          option is deleted and the new default (on) takes over.
#        - marker absent + enabled=1  → an explicit opt-IN; left alone.
#        - marker PRESENT             → the migration already ran once, so any
#          value now on disk is the operator's own choice. Never touched again.
#          This is what makes `enabled=0` a durable opt-out.
#      An operator who wants to stay on poll across the flip can pre-pin by
#      setting the marker themselves before upgrading.
#
#   3. The migration actually RUNS on an in-place package upgrade. uci-defaults
#      scripts are only executed by /etc/init.d/boot at boot; `wifihaven-update`
#      installs the package and restarts the service without rebooting, so each
#      postinst producer must invoke the script directly (same idiom
#      openwrt/install.sh already uses for 96-wifihaven-settings).
set -e

PASS=0; FAIL=0
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AGENT="$ROOT/files/usr/sbin/wifihaven-agent"
SIDECAR="$ROOT/files/usr/sbin/wifihaven-ws"
CONFIG="$ROOT/files/etc/config/wifihaven"
MIGRATION="$ROOT/files/etc/uci-defaults/97-wifihaven-ws-default-on"

check() {
  if [ "$2" = "ok" ]; then
    printf "  PASS: %s\n" "$1"; PASS=$((PASS + 1))
  else
    printf "  FAIL: %s — %s\n" "$1" "$2"; FAIL=$((FAIL + 1))
  fi
}

for f in "$AGENT" "$SIDECAR" "$CONFIG"; do
  [ -f "$f" ] || { printf "MISSING: %s\n" "$f"; exit 1; }
done

# ── 1. Code defaults ────────────────────────────────────────────────────────
grep -q 'uci_get_ws("enabled", "1")' "$AGENT" \
  && check "agent defaults ws.enabled to 1 when unset" ok \
  || check "agent defaults ws.enabled to 1 when unset" "not found in $AGENT"

grep -q 'uci_get_ws("enabled", "1")' "$SIDECAR" \
  && check "ws sidecar defaults ws.enabled to 1 when unset" ok \
  || check "ws sidecar defaults ws.enabled to 1 when unset" "not found in $SIDECAR"

# The shipped conffile must leave `enabled` UNSET inside `config ws 'ws'`, so a
# fresh install lands on the code default and the migration marker is the only
# thing that ever writes the key.
ws_section=$(awk "/^config ws 'ws'/{f=1;next} /^config /{f=0} f" "$CONFIG")
if printf '%s\n' "$ws_section" | grep -qE "^[[:space:]]*option[[:space:]]+enabled"; then
  check "shipped config leaves wifihaven.ws.enabled unset" "found an 'option enabled' line in config ws 'ws'"
else
  check "shipped config leaves wifihaven.ws.enabled unset" ok
fi

# ── 2. Upgrade migration ────────────────────────────────────────────────────
[ -f "$MIGRATION" ] \
  && check "migration script ships at /etc/uci-defaults/97-wifihaven-ws-default-on" ok \
  || check "migration script ships at /etc/uci-defaults/97-wifihaven-ws-default-on" "missing $MIGRATION"

# Fake `uci` over a flat key=value store so the migration's decisions are
# exercised for real (no OpenWrt/libuci needed on the dev host or in CI).
run_migration() {
  # args: zero or more "key=value" seed lines, e.g. ws.enabled=0
  [ -f "$MIGRATION" ] || { echo "MIGRATION-MISSING"; return 0; }
  WS_TMP=$(mktemp -d)
  : > "$WS_TMP/store"
  for kv in "$@"; do printf '%s\n' "$kv" >> "$WS_TMP/store"; done

  cat > "$WS_TMP/uci" <<'UCI'
#!/bin/sh
# Minimal uci stand-in: get/set/delete/commit over $WS_STORE (key=value lines).
store="$WS_STORE"
[ "$1" = "-q" ] && shift
cmd="$1"; shift
key="${1%%=*}"
key="${key#wifihaven.}"
val="${1#*=}"
case "$cmd" in
  get)
    line=$(grep "^$key=" "$store" | tail -1) || true
    [ -n "$line" ] || exit 1
    printf '%s\n' "${line#*=}" ;;
  set)
    grep -v "^$key=" "$store" > "$store.new" 2>/dev/null || true
    mv "$store.new" "$store"
    printf '%s=%s\n' "$key" "$val" >> "$store" ;;
  delete)
    grep -v "^$key=" "$store" > "$store.new" 2>/dev/null || true
    mv "$store.new" "$store" ;;
  commit) : ;;
  *) exit 1 ;;
esac
exit 0
UCI
  chmod 0755 "$WS_TMP/uci"
  WS_STORE="$WS_TMP/store" PATH="$WS_TMP:$PATH" sh "$MIGRATION" >/dev/null 2>&1 || true
  # Re-run once: the migration must be idempotent across the boot pass that
  # follows a postinst-triggered run.
  WS_STORE="$WS_TMP/store" PATH="$WS_TMP:$PATH" sh "$MIGRATION" >/dev/null 2>&1 || true
  cat "$WS_TMP/store"
  rm -rf "$WS_TMP"
}

store_get() { printf '%s\n' "$1" | grep "^$2=" | tail -1 | sed "s/^$2=//"; }

# (a) Fresh install: nothing set → enabled stays unset (code default = on),
#     marker recorded so the migration never re-runs.
out=$(run_migration)
if [ -z "$(store_get "$out" ws.enabled)" ] && [ -n "$(store_get "$out" ws.default_on_migrated)" ]; then
  check "fresh install: enabled stays unset (ws on) + marker set" ok
else
  check "fresh install: enabled stays unset (ws on) + marker set" "store=[$(echo "$out" | tr '\n' ' ')]"
fi

# (b) Pre-#2608 router carrying the OLD shipped default → migrated onto ws.
out=$(run_migration "ws=ws" "ws.enabled=0")
if [ -z "$(store_get "$out" ws.enabled)" ] && [ -n "$(store_get "$out" ws.default_on_migrated)" ]; then
  check "upgrade with no explicit setting (shipped 0): moves to ws" ok
else
  check "upgrade with no explicit setting (shipped 0): moves to ws" "store=[$(echo "$out" | tr '\n' ' ')]"
fi

# (c) Explicit opt-IN survives untouched.
out=$(run_migration "ws=ws" "ws.enabled=1")
if [ "$(store_get "$out" ws.enabled)" = "1" ]; then
  check "upgrade with explicit enabled=1: stays on ws" ok
else
  check "upgrade with explicit enabled=1: stays on ws" "store=[$(echo "$out" | tr '\n' ' ')]"
fi

# (d) THE critical case: an explicit opt-out (marker already present, so the
#     0 on disk is the operator's, not the old shipped default) is preserved.
out=$(run_migration "ws=ws" "ws.enabled=0" "ws.default_on_migrated=1")
if [ "$(store_get "$out" ws.enabled)" = "0" ]; then
  check "explicit opt-out (enabled=0 + marker): stays on poll after upgrade" ok
else
  check "explicit opt-out (enabled=0 + marker): stays on poll after upgrade" "store=[$(echo "$out" | tr '\n' ' ')]"
fi

# (e) A router that opted out BEFORE the flip can pre-pin with the marker alone.
out=$(run_migration "ws=ws" "ws.default_on_migrated=1")
if [ -z "$(store_get "$out" ws.enabled)" ]; then
  check "pre-pinned marker with no value: migration writes nothing" ok
else
  check "pre-pinned marker with no value: migration writes nothing" "store=[$(echo "$out" | tr '\n' ' ')]"
fi

# ── 3. The migration runs on an in-place upgrade, not just at boot ──────────
missing=""
for p in "$ROOT/Makefile" "$ROOT/build-ipk.sh" "$ROOT/build-apk.sh"; do
  grep -q '97-wifihaven-ws-default-on' "$p" || missing="$missing $(basename "$p")"
done
if [ -z "$missing" ]; then
  check "every postinst producer invokes the ws migration" ok
else
  check "every postinst producer invokes the ws migration" "missing in:$missing"
fi

printf "\nResults: %d passed, %d failed\n" "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
