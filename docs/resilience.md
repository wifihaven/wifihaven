# Resilience — Failure Modes and Intended Behavior

Status: **PROPOSAL — awaiting sign-off.** Tracks #269.

This document defines how familydns SHOULD behave under five failure scenarios.
It is the durable design contract. Implementation may lag; gaps are filed as
individual follow-up issues that reference #269.

The decisions in §4 (fail-closed for child profiles) and §5 (NTP-skew handling)
are policy calls that need explicit operator sign-off before implementations land.

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
  enforcement per **per-profile `failureMode`**:
  - **`closed` (default for child profiles):** the profile's devices have
    all forwarded traffic dropped, with the block page served for HTTP.
    This is the same posture as "all categorical blocks active + paused."
  - **`open` (default for adult / admin profiles):** the profile's devices
    continue under the cached policy with no new restrictions. Existing
    blocks (categorical, schedule) still apply because they're in the
    cached snapshot; only the API-unreachable-specific transition is a
    no-op.
- **On API return**, the agent applies the fresh snapshot and clears the
  failover state immediately. There is no hysteresis — recovery is instant.

### Why
This is the **central parental-controls correctness decision.** Fail-open
for kids means "yank the WAN cable" is a complete bypass — unacceptable.
Fail-closed for adults means an ISP blip locks the household out of their
own LAN — also unacceptable. Per-profile `failureMode` resolves the conflict.

The 5-minute threshold balances two costs:
- Too short (< 1 min): every flaky cellular failover triggers a fail-closed
  event, which kids will notice and learn to exploit (wait it out).
- Too long (> 15 min): a determined kid who unplugs the WAN can browse
  freely for that whole window. 5 minutes is short enough to be annoying
  rather than useful.

Default `closed` for child role and `open` for adult/admin reflects the
asymmetric blast radius: a false-closed for an adult is "my work Zoom
dropped"; a false-open for a child is "they got around the filter."

### Implementation
- `shared/src/Models.scala`: add `failureMode: FailureMode` to
  `PolicyProfile` with `FailureMode = Open | Closed`. Default derived from
  profile role.
- `api/src/policy/PolicyService.scala`: include `failureMode` in the rendered
  `PolicySnapshot`.
- `openwrt/files/usr/sbin/familydns-agent`: track `last_successful_poll_ts`.
  When `now - last > 300s`, render a "failover" nft variant — drop forwarded
  traffic for devices whose profile is `closed`; pass-through for `open`.
- Admin UI: add a "Failure mode" radio on the profile edit page with a
  short explanation. Default value baked in by role on profile creation.

### How to verify
- Block the API at the router (firewall rule on the router itself
  blocking egress to the API host). Wait 5 minutes. Confirm that a
  child-profile device cannot reach the internet but the block page
  loads; confirm an adult-profile device continues to work under cached
  policy.
- Restore API reachability; both profiles return to normal within one
  poll cycle.

---

## 5. Time skew / NTP

### Intended behavior
- **Router clock matters only for usage timestamps.** Schedule enforcement
  is computed server-side (`PolicyService.scheduleBlock` bakes the current
  in/out-of-window state into the snapshot) — the agent does not evaluate
  `meta hour`/`meta day` rules. So router clock skew does NOT break
  schedule-based blocking today.
- **But the agent's clock IS used for usage period boundaries.** A router
  with a 6-hour-skewed clock will attribute usage to the wrong day window,
  breaking daily limits.
- **Mitigation: clock-sanity check on poll.** Each policy poll response
  includes the API's `Date` header. The agent compares `os.time()` to the
  API time; if drift exceeds **60 seconds**, the agent:
  1. Logs a warning ("router clock skew Xs vs API; usage may be misattributed").
  2. Surfaces a banner on the admin UI for that router (via a new
     `clockSkewSeconds` field reported in usage POSTs and rendered on the
     router status page).
  3. **Continues operating.** Some deployments genuinely have no NTP
     (cellular failover, isolated networks); refusing to enforce is worse
     than enforcing with skewed usage windows.
- **API server clock IS authoritative for schedules.** Operators must run
  NTP on the API host. This is documented as a deploy prerequisite.
- **Future-proofing:** if schedules ever move to in-kernel `meta hour`
  evaluation on the router (the original #305 design that was rejected),
  this section needs revisiting — at that point, large clock skew SHOULD
  cause the agent to refuse to enforce schedule-only rules and fall back
  to "schedule treated as allow." Categorical blocks and pause still
  enforce regardless.

### Why
The previous design assumed router clock drives schedule enforcement;
the actual implementation puts that on the API side. So clock paranoia
on the router is overkill today. But ignoring the issue is wrong —
silent usage misattribution is exactly the kind of bug that erodes trust
in daily limits. A loud-but-non-blocking warning is the right posture.

### Implementation
- `openwrt/files/usr/lib/lua/familydns/policy.lua`: capture API `Date`
  header on each successful poll; compute drift; store on agent.
- `openwrt/files/usr/lib/lua/familydns/usage.lua`: include
  `clockSkewSeconds` in usage POST body.
- `api/src/routes/RouterIngestRoutes.scala`: persist last reported skew
  per-router.
- Admin UI router page: display warning banner if `|skew| > 60s`.
- `docs/install-api.md`: document NTP as a prerequisite for the API host.

### How to verify
- On a test router, `date -s '2020-01-01'`. Within one poll cycle, agent
  log shows "clock skew" warning; admin UI shows banner; daily limits
  continue to enforce (best-effort).
- Reset clock; banner clears on next poll.

---

## Summary of audit findings

| Scenario | Current behavior | Status |
| --- | --- | --- |
| 1. Power loss | Identity persists; **no default-deny gate before agent renders** — traffic forwards unfiltered for the boot window. Usage buckets in tmpfs (intentional). | **GAP** |
| 2. API restart | No persisted policy cache (tmpfs only); usage POSTs have **no retry/backoff**; no 5-minute failover threshold. | **GAP** |
| 3. DB blip | Health endpoint correct (503). All other routes return **bare 500 on DB failure**. JWT auth rides through correctly. | **GAP** |
| 4. Internet outage | No `failureMode` field exists. Agent is **fail-open** on cold start with no cached policy; rules persist in tmpfs only. | **GAP** |
| 5. Time skew | No NTP / clock-sync check. Schedules enforced server-side (clock skew doesn't break them today), but usage attribution silently drifts. | **GAP** |

All five scenarios have implementation gaps. Each is filed as its own
follow-up issue referencing #269.
