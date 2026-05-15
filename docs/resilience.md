# Resilience — Failure Modes and Intended Behavior

Status: **PROPOSAL — awaiting sign-off.** Tracks #269.

This document defines how familydns SHOULD behave under five failure scenarios.
It is the durable design contract. Implementation may lag; gaps are filed as
individual follow-up issues that reference #269.

The decision in §4 (per-profile three-mode failover; #385) is a policy
call that needs explicit operator sign-off before implementation lands.
§5 was previously a sign-off item too, but is now settled by design: the
router carries no time-based logic (Truth 2 / #350), so clock skew on the
router is not a familydns concern.

---

## 1. Router power loss mid-day

### Intended behavior
- **Identity survives.** On boot, the agent restores `router_id` and
  `router_token` from `/etc/config/familydns`. It never re-enrolls if a token
  is already present — re-enrollment must be an explicit operator action.
- **No traffic forwards before policy is applied.** Between kernel boot and
  the agent's first successful `policy.apply()`, forwarded LAN→WAN traffic
  must NOT pass unfiltered. The router installs a **default-deny nft skeleton
  at boot** (via a uci-defaults / firewall include) that drops forwarded
  traffic until the agent replaces it with rendered rules. The skeleton is
  intentionally restrictive: DNS to the router itself is permitted (so devices
  don't see hard failures), forwarded TCP/UDP is dropped, and the block-page
  served by uhttpd on the router stays reachable.
- **Pre-power-loss usage may be lost — and that's acceptable.** Usage buckets
  live in tmpfs (`/tmp`) by design; flash wear from per-MAC counter writes
  every few seconds would be unacceptable. The policy is: **usage is
  best-effort; a sudden power loss forfeits the current bucket window.**
  We do NOT attempt to persist in-flight buckets to flash.

### Why
The cost of leaking 15 seconds of unfiltered traffic on every reboot (the
window between network-up and policy-applied) is unacceptable for a parental
controls product. The boot ordering of OpenWRT's `procd` does not guarantee
the agent runs before forwarding is enabled, so the gate must be at the nft
layer, not the init-script-ordering layer.

Persisting usage buckets to flash is rejected on hardware-lifetime grounds.
A clean power-cycle losing < 60 seconds of usage data is materially harmless
— the next bucket continues accumulating against the same daily limit.

### Implementation
- Boot-time default-deny: `openwrt/files/etc/uci-defaults/` adds an
  nft include that loads on firewall start, before the agent runs.
- Agent removes the deny rules atomically as part of its first
  `policy.apply()`.
- `familydns-agent` startup logic (already present): exit non-zero if
  router_id/token absent; never auto-enroll.

### How to verify
1. SSH to a provisioned router; `reboot`.
2. From a client behind the LAN, watch a long-running ping to 1.1.1.1
   across the reboot. Expectation: ping fails throughout the reboot
   window and only resumes after the agent logs `policy applied` —
   no "unfiltered window" where ping resumes ahead of policy.
3. Confirm `/etc/config/familydns` retains `router_id`/`router_token`
   across reboots; agent log shows "using cached credentials," not
   "enrolling."

---

## 2. API server restart

### Intended behavior
- **Agent rides through.** While the API is unreachable, the agent continues
  enforcing from its **last-applied policy snapshot**, which is persisted to
  flash (`/etc/familydns/policy.json` or similar) on every successful poll.
  Tmpfs is not enough — a reboot during the API outage would otherwise drop
  the router to default-deny (§1), which is correct for safety but disruptive
  for adults; the on-flash snapshot lets the router come back up enforcing
  the last-known policy.
- **Usage retried with backoff.** Failed usage POSTs are retried with
  exponential backoff (base 30s, max 15 min, jitter 10%). Buckets remain
  in tmpfs queued for retry. On API return, the agent **drains the queue
  sequentially in chronological order** — not batched into one request —
  so server-side dedup (per #294) operates on coherent per-bucket payloads.
- **Conntrack events follow the same retry-queue policy** (#330): base 30s,
  max 15 min, ±10% jitter, drained oldest-first on API return. Events are
  best-effort under prolonged outage — the in-memory queue is capped at
  ~1000 batches and on cap-exceeded the **oldest** batches are dropped to
  preserve recent activity. This is intentional: when memory is constrained
  we want the most recent traffic on the Activity tab, not stale history.
- **Polling resumes on API return.** Poll cadence is unchanged during the
  outage (default 30s); the agent does not back off poll attempts as
  aggressively as usage POSTs because policy freshness matters more than
  usage freshness.
- **Max API-down window before failover.** After **5 minutes** of consecutive
  unreachable polls, the agent transitions to the per-profile failure mode
  defined in §4. Until then, the cached snapshot stands. See §4 for the
  rationale on the 5-minute threshold.

### Why
The asymmetry — sequential drain for usage, fixed cadence for policy — is
deliberate: a long API outage followed by recovery should not stampede the
DB with one giant batched usage POST per router, but it SHOULD restore
correct policy within one poll interval.

### Implementation
- `openwrt/files/usr/lib/lua/familydns/policy.lua`: write snapshot to
  `/etc/familydns/policy.json` after each successful apply.
- `openwrt/files/usr/lib/lua/familydns/usage.lua`: maintain in-memory
  retry queue; on failure, exponential backoff per-bucket.
- `openwrt/files/usr/lib/lua/familydns/conntrack.lua`: same retry-queue
  pattern for events POSTs (#330); cap = 1000 batches, drop-oldest on
  overflow; drained from the conntrack watcher loop on every tick.
- Agent main loop: track `last_successful_poll_ts`; if `now -
  last_successful_poll_ts > 300s`, transition enforcement per §4.

### How to verify
- Stop the API container for 60s; agent should log retries and resume
  cleanly with no policy change.
- Stop the API for 6+ minutes; agent transitions per §4.
- Confirm queued usage drains sequentially after API return (check API
  logs for spaced POSTs, not one batch).

---

## 3. Database blip

### Intended behavior
- **API returns 503, not 500, when DB is unavailable.** This includes all
  router-facing endpoints (`/api/router/policy`, `/api/router/usage`,
  `/api/router/events`) and admin endpoints that need DB access. 503 lets
  the agent distinguish "service degraded, retry later" from "bad request,
  do not retry."
- **Agent treats 503 like a network failure.** Same retry/backoff path as §2.
- **Health endpoint already correct.** `GET /api/health` returns 503 with
  `{"status":"error","db":"<reason>"}` on DB failure today; preserve this.
- **Admin UI sessions ride through.** Authentication is JWT-stateless
  (HS256 HMAC) — token verification has no DB round-trip, so already-logged-in
  admins do not get bounced on a DB blip. Login itself requires the DB
  and will fail with 503; this is acceptable.

### Why
Returning bare 500 today loses the "this is transient, retry me" signal.
503 with `Retry-After` is the HTTP-native way to communicate this to a
client (the agent) without parsing error bodies.

### Implementation
- `api/src/routes/*Routes.scala`: replace `.orElseFail(Response.internalServerError(""))`
  with a typed handler that maps DB exceptions to 503 + JSON body. Bare 500
  remains for genuinely unexpected (non-DB) failures.
- Optionally add `Retry-After: 30` header on 503 responses.

### How to verify
- `docker compose stop postgres`; hit `/api/router/policy` with a valid
  token. Expect HTTP 503. Hit `/api/health`. Expect HTTP 503.
- Confirm an existing admin UI session keeps loading non-DB pages.

---

## 4. Internet outage on router (agent can't reach API) — KEY DESIGN DECISION

### Intended behavior
- **Cached policy stands for the first 5 minutes.** Most outages are
  shorter than this; the cached snapshot is the correct enforcement
  posture during transient failures.
- **After 5 minutes of consecutive failed polls**, the agent transitions
  enforcement per **per-profile `failureMode`**. Three modes (#385) —
  named identically across architecture.md §0.2, this section,
  `shared/src/Models.scala`, the wire JSON, and the DB column:
  - **`BlockAll`** (wire `"block-all"`, recommended for child profiles):
    the profile's devices have all forwarded traffic dropped, with the
    block page served for HTTP. Same posture as "all categorical blocks
    active + paused."
  - **`LastKnownGood`** (wire `"last-known-good"`, default and
    recommended for adult/admin profiles): the profile's devices
    continue under the cached snapshot exactly as-is. Categorical
    blocks, schedules, and per-MAC overrides all keep enforcing because
    they live in the cached snapshot; only the
    API-unreachable-specific transition is a no-op.
  - **`AllowAll`** (wire `"allow-all"`, opt-in only): the profile's
    devices are explicitly carved out of every drop rule — the
    `@blocked_macs` membership, the per-`(MAC, host)` extraBlocked
    drops, and the per-`(MAC, blocklistId)` category drops are all
    suppressed for these MACs during failover, and the block-page DNAT
    is suppressed too. Cached enforcement is intentionally erased for
    these MACs; admins must pick this deliberately for trusted profiles
    where lockout risk outweighs the cached-snapshot defence.
- **On API return**, the agent applies the fresh snapshot and clears the
  failover state immediately. There is no hysteresis — recovery is instant.

### Why
This is the **central parental-controls correctness decision.** A single
"pass everything" mode for kids is a complete bypass — "yank the WAN
cable" defeats the filter. A single "block everything" mode for adults
locks the household out of their own LAN during ordinary ISP blips.
Per-profile `failureMode` resolves the conflict.

The original design (#311) shipped only two modes — `Open` and
`Closed` — and that binary collapsed two semantically distinct
behaviours: "pass everything with no enforcement" (AllowAll) and "keep
the cached snapshot enforcing exactly" (LastKnownGood). The agent
shipped one interpretation (`Open` = LastKnownGood); the doc shipped the
other (`Open` = AllowAll). #385 separates them.

The 5-minute threshold balances two costs:
- Too short (< 1 min): every flaky cellular failover triggers a
  failover transition, which kids will notice and learn to exploit
  (wait it out).
- Too long (> 15 min): a determined kid who unplugs the WAN can browse
  freely for that whole window. 5 minutes is short enough to be
  annoying rather than useful.

Defaults reflect the asymmetric blast radius. For a child profile,
"BlockAll" trades a false-block (kid loses access during a real ISP
blip) for the guarantee that "unplug the WAN" is not a bypass. For an
adult profile, "LastKnownGood" is the conservative choice: the cached
snapshot keeps enforcing whatever categorical blocks / schedules were
already in place without taking the household offline. `AllowAll` is
never a default — it must be picked deliberately.

### Implementation
- `shared/src/Models.scala`: `enum FailureMode { case BlockAll, AllowAll,
  LastKnownGood }`. JSON codec emits lower-kebab wire forms.
- `api/resources/db/migration/V12__failure_mode_three_modes.sql`:
  migrate existing rows (`closed` → `block-all`, `open` →
  `last-known-good`) and add a CHECK constraint pinning the new
  vocabulary. Column default switches to `last-known-good`.
- `api/src/routes/Routes.scala`: profile create / update preserves an
  explicit `failureMode` from the request body; falls back to
  `LastKnownGood` only when the field is absent (column default mirrors
  this). Role-aware defaulting (child → BlockAll) is a UI concern —
  the server does not infer a default from linked-user roles.
- `api/src/policy/PolicyService.scala`: includes `failureMode` per
  `ProfilePolicy` in the rendered `PolicySnapshot`. The ETag covers
  this value so a mode change invalidates client caches.
- `openwrt/files/usr/lib/lua/familydns/policy.lua`: tracks
  `last_successful_poll_ts`; on `now - last > 300s`, renders nft with
  `opts.poll_age_seconds`.
- `openwrt/files/usr/lib/lua/familydns/render.lua`: branches by mode.
  `BlockAll` emits a dedicated `failover_drop` set and drop chain.
  `AllowAll` suppresses the profile's MACs from every drop list before
  the `familydns_block` and `familydns_block_nat` chains are rendered.
  `LastKnownGood` is the no-op default — nothing additional is
  rendered, and the cached snapshot rules keep enforcing exactly.
- Admin UI: three-option radio on the profile edit page (`ProfilesPage`)
  with explanatory copy per mode.

### How to verify
- Block the API at the router (firewall rule on the router itself
  blocking egress to the API host). Wait 5 minutes. Confirm that:
  - A `BlockAll` profile's device cannot reach the internet but the
    block page loads;
  - A `LastKnownGood` profile's device continues to work under cached
    policy, including any categorical / schedule blocks that were
    already in effect;
  - An `AllowAll` profile's device passes traffic with no enforcement,
    even if the cached snapshot has `extraBlocked` entries for it.
- Restore API reachability; all three profiles return to normal within
  one poll cycle.

---

## 5. Time skew / NTP

Router does not perform time-based decisions; API server's clock is
authoritative. See #334 for API-side time handling.

All schedule windows, daily-limit rollovers, and schedule-day-of-week
evaluations happen on the API server and are baked into the rendered
`blockedMacs` set inside the policy snapshot before it ships to the
agent (Truth 2 / #350). The agent applies the snapshot verbatim and
never consults its own clock for enforcement, so router clock drift
cannot misattribute usage or skip a schedule transition. Operators
must keep NTP running on the **API host**; the router clock is
incidental.

The previous agent-side skew detector (Date-header capture,
`clockSkewSeconds` on usage POSTs, admin UI banner) was removed in
\#415 once #350 confirmed the router carries no time-based logic.

---

## Summary of audit findings

| Scenario | Current behavior | Status |
| --- | --- | --- |
| 1. Power loss | Identity persists; **no default-deny gate before agent renders** — traffic forwards unfiltered for the boot window. Usage buckets in tmpfs (intentional). | **GAP** |
| 2. API restart | No persisted policy cache (tmpfs only); usage POSTs have **no retry/backoff**; no 5-minute failover threshold. | **GAP** |
| 3. DB blip | Health endpoint correct (503). All other routes return **bare 500 on DB failure**. JWT auth rides through correctly. | **GAP** |
| 4. Internet outage | No `failureMode` field exists. Agent is **fail-open** on cold start with no cached policy; rules persist in tmpfs only. | **GAP** |
| 5. Time skew | Router does no time-based evaluation (Truth 2 / #350); API clock is authoritative. See #334 for API-side time handling. | **N/A (by design)** |

All five scenarios have implementation gaps. Each is filed as its own
follow-up issue referencing #269.
