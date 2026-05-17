# Manual QA — single-device walkthrough

Validates a fresh install end-to-end with one real device. Mirrors the
VM e2e suite under [`scripts/e2e/scenarios/`](../scripts/e2e/scenarios/) so the same
behavioral contracts get exercised when the VM harness isn't available —
bringup, post-rename smoke, hardware shakeouts, or anything the VMs can't
reproduce.

## Keep in sync with the e2e suite

One manual section per e2e scenario file. The scenario filename appears in
brackets in every section header so drift can be `grep`-ed.

When you:

- **Add** a scenario to [`scripts/e2e/scenarios/`](../scripts/e2e/scenarios/), add a matching
  section here. Mirror the docstring's "Verifies" line verbatim.
- **Change** a scenario's contract, update both.
- **Spot drift**, treat it as a bug — either delete the manual section
  (the scenario was removed) or update both (the contract changed).

Reviewer rule of thumb: any PR that touches `scripts/e2e/scenarios/*.py`
should touch this file too, or explain why not.

## Prereqs

- Agent installed per [install-openwrt.md](install-openwrt.md) and API
  installed per [install-api.md](install-api.md).
- Admin UI login (`ADMIN_USER` / `ADMIN_PASS`).
- SSH to router (`root@…`) and API host.
- One device on the LAN. Capture its MAC and current IP.

Set these once and reuse them in every snippet below:

```sh
export DEVICE_MAC=aa:bb:cc:dd:ee:ff
export ROUTER=192.168.1.1
export API=http://api.example.com:8080
export ADMIN_USER=admin
export ADMIN_PASS='…'

# Admin JWT for the rest of this doc.
ADMIN_TOKEN=$(curl -fsS -X POST "$API/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" \
  | jq -r .token)
AUTH=(-H "Authorization: Bearer $ADMIN_TOKEN")
```

Cleanup between scenarios: unpause any profile you paused, clear
`extraBlocked` entries you added, and reset `timeLimit.dailyMinutes` to 0.
Each section assumes the previous one was undone.

---

## QA-1: Enrollment [`test_01_enrollment.py`](../scripts/e2e/scenarios/test_01_enrollment.py)

**Verifies:** the router appears in the admin API after install and its
`last_seen_at` advances on every poll.

```sh
# Router is registered.
curl -fsS "${AUTH[@]}" "$API/api/admin/routers" \
  | jq '.[] | {id, name, lastSeenAt}'

# Agent UCI matches.
ssh root@"$ROUTER" 'uci get wifihaven.wifihaven.router_id'

# last_seen_at should advance within one poll (default 60s).
for i in 1 2; do
  curl -fsS "${AUTH[@]}" "$API/api/admin/routers" | jq '.[].lastSeenAt'
  sleep 65
done
```

**Expected:** a row matching the router's UCI `router_id`; `lastSeenAt`
strictly increases between samples.

---

## QA-2: Allowed browsing [`test_02_allowed_browsing.py`](../scripts/e2e/scenarios/test_02_allowed_browsing.py)

**Verifies:** with a default (no-block) profile, HTTP succeeds end-to-end
and a `connection_event` lands with the correct MAC.

```sh
# 1. Admin UI → create a profile, assign $DEVICE_MAC to it.
#    (Or do it via the API — see api.ts for the endpoints.)

# 2. From the device:
curl -v http://example.com/    # → 200, real example.com body

# 3. Event recorded.
curl -fsS "${AUTH[@]}" "$API/api/logs?limit=20" \
  | jq --arg m "$DEVICE_MAC" '.[] | select(.deviceMac==$m and .host=="example.com")'
```

**Expected:** real response (not the block page); event with
`allowed=true, host="example.com", deviceMac=$DEVICE_MAC` within ~30 s.

---

## QA-3: Blocked domain [`test_03_blocked_domain.py`](../scripts/e2e/scenarios/test_03_blocked_domain.py)

**Verifies:** an `extraBlocked` domain has its HTTP/80 traffic DNAT'd to
the local block page (Truth 1 — no DNS interception, no TLS interception).

```sh
# 1. Admin UI → device's profile → add example.org to extraBlocked.

# 2. Wait one poll (~60s) and confirm the nft per-(mac, host) rule landed:
ssh root@"$ROUTER" 'nft list table inet wifihaven' | grep -iE 'example|extra' | head

# 3. From the device:
curl -v http://example.org/
```

**Expected:** HTTP 200 with `wifihaven`/`blocked` in the body (the local
block page), **not** the real example.org content.

---

## QA-4: Daily time-limit [`test_04_daily_limit.py`](../scripts/e2e/scenarios/test_04_daily_limit.py)

**Verifies:** once `timeLimit.dailyMinutes` is exhausted, further traffic
from the device is blocked and a `reason=timeLimit` event is recorded.

```sh
# 1. Admin UI → profile → timeLimit.dailyMinutes = 1.

# 2. From the device, generate ~90 s of activity:
for i in $(seq 1 90); do curl -sS -o /dev/null http://example.com/; sleep 1; done

# 3. Wait one usage_report_interval + one policy_poll_interval
#    (defaults: 300s + 60s = ~6 min worst case).

# 4. Re-probe:
curl -v http://example.com/   # → block page

# 5. Event recorded.
curl -fsS "${AUTH[@]}" "$API/api/logs?limit=20" \
  | jq --arg m "$DEVICE_MAC" '.[] | select(.deviceMac==$m and .allowed==false)'
```

**Expected:** block page after the cap is exceeded; event with
`allowed=false, reason="timeLimit"`.

---

## QA-5: Usage in API [`test_05_usage_in_api.py`](../scripts/e2e/scenarios/test_05_usage_in_api.py)

**Verifies:** `/api/logs` shows the device's events and `/api/time/status`
attributes used minutes to the device.

```sh
curl -fsS "${AUTH[@]}" "$API/api/logs?limit=50" \
  | jq --arg m "$DEVICE_MAC" '[.[] | select(.deviceMac==$m)] | length'

curl -fsS "${AUTH[@]}" "$API/api/time/status" \
  | jq --arg m "$DEVICE_MAC" '.[].devices[] | select(.deviceMac==$m)'
```

**Expected:** non-zero log count for the MAC; a `{deviceMac, deviceName,
usedMins}` entry with `usedMins > 0` after QA-2/QA-4 traffic. Note the
field is `deviceMac`, not `mac` (see `shared/Models.scala`).

---

## QA-6: Block page rendering [`test_06_blocked_page.py`](../scripts/e2e/scenarios/test_06_blocked_page.py)

**Verifies:** the block page is rendered by `uhttpd-mod-lua` →
`handler.lua` and reflects the actually-requested host.

```sh
# With example.org still in extraBlocked from QA-3:
curl -sS http://example.org/some/path | head -40
```

**Expected:** HTML body that names `example.org` and the device. If you
see a raw 200 with empty body, `uhttpd` or the lua handler is wrong — check
`logread | grep uhttpd` on the router.

---

## QA-A: Pause [`test_pause.py`](../scripts/e2e/scenarios/test_pause.py)

**Verifies:** pausing a profile adds every assigned MAC to the router's
`blocked_macs` nft set within one poll; unpausing removes them; events
carry `reason=paused`.

```sh
# 1. Pause via admin UI (or PUT /api/profiles/<id> with paused=true).

# 2. Within ~60 s the MAC lands in blocked_macs (case-insensitive):
ssh root@"$ROUTER" 'nft list set inet wifihaven blocked_macs' | grep -i "$DEVICE_MAC"

# 3. From the device:
curl -v http://example.com/   # → block page

# 4. Event:
curl -fsS "${AUTH[@]}" "$API/api/logs?limit=20" \
  | jq --arg m "$DEVICE_MAC" '.[] | select(.deviceMac==$m and .reason=="paused")'

# 5. Unpause. Within ~60 s:
ssh root@"$ROUTER" 'nft list set inet wifihaven blocked_macs' | grep -i "$DEVICE_MAC" || echo "(absent — restored)"
curl -v http://example.com/   # → real example.com
```

**Expected:** all five checkpoints pass.

---

## QA-B: extraBlocked scoping [`test_extra_blocked.py`](../scripts/e2e/scenarios/test_extra_blocked.py)

**Verifies:** Truth 1 — DNS still returns the real public IP (no DNS
interception); HTTP/80 still DNATs to the block page; and `extraBlocked`
is per-profile (a device on a different profile is unaffected).

```sh
# example.org in extraBlocked on $DEVICE's profile:

# DNS is honest:
dig +short @"$ROUTER" example.org   # → public A record, never 127.0.0.1

# HTTP is intercepted:
curl -v http://example.org/         # → block page

# Per-profile: create a second profile without extraBlocked, reassign,
# wait one poll, retry.
curl -v http://example.org/         # → succeeds
```

---

## QA-C: Schedule [`test_schedule.py`](../scripts/e2e/scenarios/test_schedule.py)

**Verifies:** schedule windows block in-window, allow out-of-window; pause
overrides schedule; windows respect the configured TZ across midnight.

```sh
# 1. Schedule a "block now" window covering the current minute for today's weekday.
curl -v http://example.com/   # → block page

# 2. Edit the window end to one minute ago; wait one poll.
curl -v http://example.com/   # → real example.com

# 3. With the window back in "allow" state, pause the profile.
curl -v http://example.com/   # → block page (pause wins, per #406)
```

Cross-midnight + TZ correctness is hard to manual-QA quickly; rely on the
e2e `test_cross_midnight_LA_tz` and only re-validate manually if the
schedule evaluator changes.

---

## QA-D: Time-limit edge cases [`test_time_limit.py`](../scripts/e2e/scenarios/test_time_limit.py)

**Verifies:** minute-granularity (49 s ≠ 1 min) and cross-day rollover.

```sh
# Granularity: dailyMinutes=1, generate 49 s of traffic — still allowed.
for i in $(seq 1 49); do curl -sS -o /dev/null http://example.com/; sleep 1; done
curl -v http://example.com/   # → real example.com

# Continue past 60 s total — block kicks in once usage is ingested.
for i in $(seq 1 20); do curl -sS -o /dev/null http://example.com/; sleep 1; done
sleep 360
curl -v http://example.com/   # → block page

# Rollover: re-test the next calendar day (or fast-forward
# WIFIHAVEN_DEBUG_NOW on the API) — should be allowed again.
```

---

## QA-E: Reassignment [`test_reassignment.py`](../scripts/e2e/scenarios/test_reassignment.py)

**Verifies:** reassigning a device to a paused profile blocks it within
one poll; deleting a device row clears its rules.

```sh
# 1. Create profile "paused-bin" with paused=true.
# 2. Reassign $DEVICE_MAC to it. Within ~60 s:
ssh root@"$ROUTER" 'nft list set inet wifihaven blocked_macs' | grep -i "$DEVICE_MAC"
curl -v http://example.com/   # → block page

# 3. Delete the device row. Within ~60 s the MAC should be gone from any
#    set, and traffic from it falls through to the unknown-device path
#    (QA-F) rather than the paused rules.
```

---

## QA-F: Unknown-device autocreation [`test_unknown_device.py`](../scripts/e2e/scenarios/test_unknown_device.py)

**Verifies:** traffic from a never-registered MAC auto-creates a device
row with `profile_id=NULL` and a DHCP hostname (when one is supplied).

```sh
# Connect a brand-new device (or randomize the MAC of the test device).
# Note the new MAC as $NEW_MAC, then from that device:
curl -sS http://example.com/

# Within ~30 s:
curl -fsS "${AUTH[@]}" "$API/api/devices" \
  | jq --arg m "$NEW_MAC" '.[] | select(.mac==$m)'
```

**Expected:** row with `mac=$NEW_MAC`, `profileId=null`, and `name` set to
the DHCP hostname (or empty if the device didn't supply one).

---

## Triage cheats

- **Agent log:** `ssh root@$ROUTER 'logread -f | grep wifihaven'`
- **Agent UCI:** `ssh root@$ROUTER 'uci show wifihaven'`
- **Live nft state:** `ssh root@$ROUTER 'nft list table inet wifihaven'`
- **DNS log (extraBlocked set source of truth):**
  `ssh root@$ROUTER 'tail -n 50 /tmp/wifihaven-dnsmasq.log'`
- **API events for a MAC:**
  `curl -fsS "${AUTH[@]}" "$API/api/logs?limit=200" | jq --arg m "$DEVICE_MAC" '[.[] | select(.deviceMac==$m)]'`
- **Force a poll:** restart the agent — `ssh root@$ROUTER /etc/init.d/wifihaven restart`

## Reference

- [`scripts/e2e/scenarios/`](../scripts/e2e/scenarios/) — the e2e files this
  document mirrors.
- [`scripts/e2e/README.md`](../scripts/e2e/README.md) — VM harness details.
- [`docs/architecture.md`](architecture.md) — Truths 1–N referenced above.
