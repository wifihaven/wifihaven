# Architecture critique — pre-rename, pre-go-live

Status: **review draft.** Tracks [#368](https://github.com/sameerparekh/familydns/issues/368).

This is a hostile-critic review of the system architecture, written after the
cluster of foundation-level fixes that produced #350 (snapshot shape), #321
(dead failover render branch), #297 (broken DNAT chain), #135 (silent column
rename), and #249 (first-seen race). Each was a bug; the cluster suggests
structural patterns worth naming explicitly before the rename + going-live
cliff locks the current shape in.

The deliverable is this document plus the sub-issues filed against it. The
sub-issues are the actionable output; this document is the durable record of
*why* those issues exist.

Scope notes:

- Security has its own audit ([#369](https://github.com/sameerparekh/familydns/issues/369)).
  Where a finding has both structural and security dimensions, this doc owns
  the structural framing and flags the overlap.
- This is a critique, not a redesign. Where a fix is concrete and bounded
  (a sub-issue worth filing), it is named. Where it is "the architecture
  should be different," it is named *and a concrete savings is required* —
  no vapor alternatives.

---

## Executive summary — top findings ranked by payoff:cost

1. **The snapshot is still a hybrid of decision-inputs and decision-outputs**
   ([§3](#3-snapshot-contract-post-354)). The canonical model in
   `docs/architecture.md` §0.2 says the snapshot ships `BlockRules`; the
   wire shape still ships `schedules`, `dailyMinutes`, `siteLimits`,
   `timeUsedToday`, `extensionsTodayMinutes`, plus a parallel `blockedMacs`
   list that re-encodes the *output* of evaluating those inputs. The agent
   has all the inputs it would need to disagree with the API. Today it
   doesn't (it reads only `blockedMacs`), but nothing structural stops a
   future contributor from "fixing" something by evaluating those inputs.
   This is the root of #305, #302, and #354. **Payoff: high. Cost: bounded
   (collapse fields server-side, ship one minimal snapshot).** Sub-issue
   filed.

2. **Generated rulesets are not validated against the target tool in CI**
   ([§5](#5-failure-modes-and-rendering-pipeline)). `render_spec.lua`
   asserts the string content of the generated nft file; no test runs
   `nft -c -f` against it on an OpenWRT-compatible nftables version. #297
   (DNAT-in-filter), #287 (duplicate dnsmasq keyword), and the
   "render branch exists but never fires" class (#321/#331) all share this
   property: passing unit tests, broken production ruleset. **Payoff: high.
   Cost: low (one CI job that runs `nft -c` and `dnsmasq --test-config`
   against rendered fixtures).** Sub-issue filed.

3. **State machines are implicit and scattered**
   ([§4](#4-state-management)). Device lifecycle (first_seen → DHCP-named
   → admin-named) and agent-side failover (cached → in_failover_render →
   recovered) are both encoded across multiple route handlers / modules
   with no single owner. #249 (first_seen_mac race), #321 (failover never
   triggered), and #331 (failover render branch dead) all derive from this.
   **Payoff: medium-high. Cost: medium (refactor each into an explicit
   state object with a single transition surface).** Sub-issue filed for
   the device-state case.

4. **Tests assert shape, not behavior**
   ([§10](#10-maintenance--evolution)). `policy_spec.lua` asserts the
   parsed snapshot keys; `RouterApiSpec` asserts the JSON shape; nothing
   end-to-end asserts that a policy edit in the API actually produces a
   blocked packet on a real router. The contract between the API and the
   agent is tested twice (once per side) and never together. **Payoff:
   high. Cost: medium (a docker-compose-driven e2e harness that posts a
   policy, polls, applies, and sends a probe packet).** Sub-issue filed.

5. **MAC-randomization bypass: unassigned devices have no enforcement
   surface.** A device whose MAC is not in any profile is not in
   `profile_macs`, not in `blocked_macs`, and the post-boot ruleset
   neither drops nor counts its forwarded traffic. The boot default-deny
   skeleton (#308) only covers the gap between boot and first apply.
   After the first apply, a kid who flips iOS Private Address gets a
   fresh MAC that hits an empty-rule policy. **Payoff: very high
   (load-bearing for the entire product claim). Cost: low (add an
   unmanaged-MAC drop chain).** Coordinated with #369 — the security
   audit has the same finding under a different framing; structural fix
   belongs here. Sub-issue filed.

The remaining ten audit areas surface no further must-fix findings of
this severity. They are documented below for traceability so readers
know they were visited.

---

## 1. The canonical model — does it hold up?

The model in `docs/architecture.md` §0.2 ("Two truths: DNS never enforces;
agent is a dumb applier") is a strong frame and the doc work in #350 /
#367 canonicalized it well. The frame holds. The problem is not the model;
the problem is that **the wire format and the agent code haven't migrated
to it.** Both deviations are called out inline in the doc and tracked, but
the migration has been pending across several iterations:

- `extraBlocked` is rendered as `address=/host/#` (DNS-layer block) in
  `render.lua:64`. The canonical model rejects DNS-layer enforcement.
- `blocklistIds` / categories: render.lua doesn't fetch RPZ files at all;
  the agent has no category-enforcement code path. `PolicyService.decide`
  uses categories only on the legacy `/api/router/decision` endpoint.
- `blockIpOnly` does not exist.

Concrete failure scenario: a parent toggles a new blocklist on; the API
serves the updated snapshot; the agent ignores `blocklistIds`; the parent
sees no enforcement; they assume the product is broken. This has been
"known deviation" status for >6 months.

**Finding:** "canonical model" + "known deviations" is a sustainable
posture only when the deviations close. They aren't closing. Either:
(a) execute the migration, or (b) downgrade the deviations from "tracked
follow-up" to "alternate enforcement plane" and update the model to match
reality. The current half-way state — model says nft, code does dnsmasq,
docs admit it but call it a follow-up — is the worst of both.

**Suggested response:** Close the gap one field at a time. Each migration
is small (rewrite render.lua's emission for one field, add nft rules,
update render_spec). The discipline is "no new deviations" — if a new
field lands in the snapshot, it lands on the canonical plane (nft) or
it doesn't land. Sub-issue filed.

---

## 2. API / router split — is "dumb applier" actually achievable?

The split as written is sound: render → nft, no per-flow round-trip,
worst-case staleness one poll interval. The forces that push logic to
the router are not currently dominant: latency budget is generous
(60s poll), scale is small (one router per household), offline ops have
a clear failure-mode story (§4 of resilience.md, modulo #321).

The bigger structural concern is the **opposite direction**: the
PolicyService is *more* than a dumb projection of DB rows. It evaluates
schedules against wall-clock-now, projects daily-limit exhaustion
against accumulated usage, runs blocklist membership lookups. This is
correct — it has to live somewhere — but it means every snapshot fetch
is a heavyweight computation that walks the full device/profile/usage
graph. `PolicyService.snapshot` ([api/src/policy/PolicyService.scala:29](api/src/policy/PolicyService.scala:29))
runs `ZIO.foreach(profiles)` three times sequentially over the profile
list. At household scale (≤10 profiles) this is fine; the architecture
should record that this is the assumed scale ([§7](#7-scaling-assumptions)).

**Finding:** the "dumb applier" frame describes the agent side well but
under-specifies the API side. `PolicyService.snapshot` is the system's
hottest path — every router GET /api/router/policy fires it — and it has
no caching layer. At one router per household with 60s polls, raw cost
is trivial; with 100 households on one API host, every minute starts with
100 serial DB walks. **Suggested response:** an ETag-keyed in-memory
snapshot cache (compute once per content-hash; serve 304s and 200s from
the cache; invalidate on any profile / device / time_usage write).
Sub-issue filed.

**Counter-pressure noted:** the router DOES still hold decision logic
that contradicts "dumb applier" — `failover_transition` in
[policy.lua:379](openwrt/files/usr/lib/lua/familydns/policy.lua:379)
decides when to enter / leave failover, `usage.lua` decides
`activeSeconds` granularity at flush time. These are not policy
decisions in the parental-controls sense, but they ARE state-machine
decisions that mean the agent is not purely declarative. This is
acceptable, but the architecture doc should name them so future
contributors don't mistake them for "logic that should move to the API."

---

## 3. Snapshot contract (post-#354)

**This is the highest-payoff finding.** The canonical shape from
`docs/architecture.md` §0.2 is `Map[MacAddress, DevicePolicy]` with
`BlockRules` carrying only resolved booleans. The actual wire shape
([shared/src/Models.scala:488](shared/src/Models.scala:488)) is:

```scala
case class PolicySnapshot(
  etag: String, generatedAt: String,
  defaultProfileId: Option[Long],
  devices: List[PolicyDevice],
  profiles: List[PolicyProfile],     // ← carries full decision inputs
  blockedMacs: List[BlockedMac],     // ← carries the OUTPUT of evaluating those inputs
  blocklists: Map[String, PolicyBlocklist],
)

case class PolicyProfile(
  ..., paused: Boolean,
  blockedCategories: List[String],   // raw inputs the agent should not consult
  extraBlocked: List[String],
  extraAllowed: List[String],
  schedules: List[PolicySchedule],   // ← wall-clock raw schedule windows
  dailyMinutes: Option[Int],          // ← raw daily limit
  siteLimits: List[PolicySiteLimit],  // ← raw site limits
  timeUsedToday: PolicyTimeUsedToday, // ← raw accumulated usage
  extensionsTodayMinutes: Int,        // ← raw extension grants
  failureMode: FailureMode,
)
```

The agent reads `blockedMacs` and (for site-limit ipset wiring)
`siteLimits`. Everything else in `PolicyProfile` is dead from the
agent's perspective. Concretely:

- `paused`, `dailyMinutes`, `extensionsTodayMinutes`, `timeUsedToday`,
  `schedules` exist only so that, if `blockedMacs` is wrong, the
  agent has the raw materials to compute it. That's a fragile design:
  it presumes the agent SHOULDN'T trust the API while shipping it
  everything it would need to disagree.

- The current code doesn't disagree. But the agent's `policy_spec` has
  to fake-populate all those fields to test rendering, the render output
  has to thread them through the JSON consume, and any future "let's add
  schedule prediction in the UI" feature has to figure out which side
  evaluates.

The structural property that would have prevented #305 (schedule active
but agent doesn't see it), #302 (decision-input fields with no consumer),
and #354 (whole shape mismatched the model): **the wire type is a
computed shape, not a field-copy of DB rows.** Today the wire type is a
near-direct projection of the DB rows; `blockedMacs` was bolted on as a
correction.

**Suggested response:** migrate the snapshot to the canonical
`Map[MacAddress, DevicePolicy]` shape from architecture.md §0.2 in one
go, deleting the redundant fields. Risk is manageable: the agent only
reads `blockedMacs`, `devices`, `siteLimits` (for ipset names), and
`extraBlocked` (for `address=/`). The first two collapse cleanly; the
last two are part of the canonical model and stay.

**Concrete savings:** ~80 lines of dead fields in
`PolicyService.snapshot`, ~120 lines of fixture-stuffing in
`policy_spec.lua`, ~40 lines of dead JSON parse code in `render.lua`.
And the snapshot becomes self-describing: an outside reader can
understand what enforcement decisions were made by reading the
snapshot alone. Sub-issue filed.

---

## 4. State management — seams between Postgres, flash, tmpfs

The current seams are largely correct but **implicit**, which is the
recurring failure mode.

**Postgres ↔ snapshot:** `PolicyService.snapshot` is the documented
boundary. The hybrid shape (§3) blurs the line — DB rows leak into
the wire payload — but the conceptual boundary is sound.

**Snapshot ↔ flash cache:** `/etc/familydns/policy.json` is written
after every successful apply ([policy.lua:293](openwrt/files/usr/lib/lua/familydns/policy.lua:293)).
The cache is read at boot before any network call ([familydns-agent:222](openwrt/files/usr/sbin/familydns-agent:222)).
This is the right shape. One concern: the cached JSON has no version
field. If the API ever changes the wire shape (and it will — see §3),
a cached old-shape snapshot will be applied at boot by a new agent
that can't parse it correctly. **Suggested response:** add a
`snapshotVersion` field to the wire shape; agents refuse to apply a
cached snapshot whose version they don't recognize, falling through
to the boot default-deny + first poll. Sub-issue filed.

**Tmpfs buckets:** the design call in resilience.md §1 ("usage is
best-effort; sudden power loss forfeits the current bucket window") is
sound. Don't second-guess it.

**Device lifecycle state:** this is the genuine structural problem.
A device row can be in one of:
- nonexistent
- created by `first_seen_mac` event with placeholder name
- created by `dhcp_lease` event with hostname
- renamed by admin
- both first_seen and dhcp arrive in unpredictable order

The transitions are scattered across `applyDhcpOrFirstSeen`
([RouterIngestRoutes.scala:235](api/src/routes/RouterIngestRoutes.scala:235)),
`renameIfAutoGenerated`, `upsertUnknown`, and `touchLastSeen` — four
DeviceRepo methods, each handling a corner. #249 fixed *one* race
(first_seen-before-dhcp) by adding `renameIfAutoGenerated`. The pattern
of "another race, another rename method" is not bounded.

**Suggested response:** consolidate device ingestion to a single repo
method `ingestDeviceObservation(mac, ip?, hostname?, ts, source)` that
applies the merge rule once, with the precedence ordering explicit
(real DHCP hostname beats auto-generated placeholder beats nothing).
Sub-issue filed.

**Failover state on the agent:** `in_failover_render` is a single
boolean ([familydns-agent:206](openwrt/files/usr/sbin/familydns-agent:206)),
`last_snapshot` is a global, `M.last_successful_poll_ts` lives in the
policy module. Three pieces of state that move together. Acceptable
for now (the bug-prone interactions are mostly worked through after
#331), but worth a refactor when the next bug surfaces. Don't file
yet.

---

## 5. Failure modes and rendering pipeline

`docs/resilience.md` covers 5 scenarios well. The gaps that matter are
**not in the scenarios it lists** but in scenarios it didn't:

- **Agent crash mid-render.** Between writing
  `/tmp/dnsmasq.d/familydns.conf` and writing
  `/tmp/nftables.d/familydns.nft` ([policy.lua:240](openwrt/files/usr/lib/lua/familydns/policy.lua:240)),
  what happens if the agent SIGKILLs? Half the config is updated;
  the next start picks up cached snapshot and re-renders, which
  resolves it — but during the window between crash and restart,
  dnsmasq sees new directives and nft sees old. **Severity: low**
  (window is <100ms, render is idempotent, restart re-applies). No
  action; documented gap.

- **Two simultaneous admin writes to the same profile.** The API
  has no optimistic locking on `profiles`; two admins in two browser
  tabs hitting "save" produce last-write-wins. For parental controls
  this is mostly fine, but a "I just unblocked YouTube" / "I just
  blocked YouTube" race produces a confusing final state. **Severity:
  low; cost: low (`If-Match: ETag` on profile updates).** Sub-issue
  worth filing if cheap; otherwise documented gap. File it.

- **Generated ruleset is invalid for the target nft version.** This
  is the #297 / #287 pattern. The rendered file is parsed by nft at
  apply time; if it fails to parse, the OLD ruleset stays in effect
  (good) but no surface signals the silent failure. The #328 smoke
  probe addressed one variant of this (verify dnsmasq actually
  reloaded a blocked-domain) but only for the dnsmasq side; nft has
  no equivalent. **Severity: high; cost: low.** Two complementary
  fixes:
  - **CI:** add `nft -c -f <rendered>` against an OpenWRT-pinned
    nftables version, fed by the fixture suite.
  - **Runtime:** capture `nft -f` stderr; if non-empty, log loudly
    and (optionally) leave a flag file for the agent to surface on
    next poll. Today the call is fire-and-forget via `os.execute`.
  Sub-issue filed.

- **Two policy fetches racing across reload.** If a poll fires at
  T=0 with etag E1 and the apply takes 500ms, and at T=60 the next
  poll fires while apply-of-E2 is in progress — what happens? The
  loop is co-operatively scheduled inside `on_tick`, so this can't
  actually happen. Documented gap, no action.

The recurring pattern across these: **external-tool calls are fire-
and-forget.** dnsmasq restart, nft apply, conntrack POST. The #328
smoke probe is the first instance of "verify after acting." That
pattern should generalize: any call out to an external system should
have a post-condition check. Sub-issue filed.

---

## 6. Concurrency — admin actions, schedule edges, multi-router

Most admin-action races are benign (parental controls product, not
financial). Three are worth naming:

- **Profile edit ↔ schedule tick.** An admin edits a schedule at
  18:00:00 right as the API's `computeBlockedMacs` is firing for
  the 18:00 schedule window. The transaction is single-row, so the
  outcome is deterministic per-call; the only oddity is the etag
  changes twice. Documented gap, no action.

- **Multi-router per household (#136).** The schema allows multiple
  routers; the snapshot is identical per-router; if two routers
  enforce the same policy on overlapping LANs, there's no coordination
  needed because each only sees its own MACs. But: usage POSTs from
  two routers can both attribute the same MAC's traffic for the same
  bucket window (the MAC roams between LAN segments). The current
  idempotency key is `(routerId, periodStart, mac, hostname)` — both
  POSTs succeed because routerId differs, and the API double-counts.
  **Severity: medium (only matters if #136 actually ships).** Sub-
  issue documented as a #136 follow-up; don't block now.

- **Daily-limit-exhaustion edge.** `PolicyService.snapshot` reads
  `time_usage` at the moment of the call; the usage POST that
  crossed the threshold may have committed 100ms ago or 100ms from
  now. The agent's next poll picks up the new `blockedMacs`. Worst-
  case overshoot: ~5 minutes (usage POST cadence) + 60s (policy
  poll). Documented in architecture.md §7.5. No action.

---

## 7. Scaling assumptions

**The architecture has no explicit scale targets.** This is fine while
the product is for one household, but the rename + going-live
discussion implies multi-household deployments. Numbers worth pinning
down NOW so future contributors don't trip them:

| Dimension | Assumed value | Where it breaks |
|---|---|---|
| Profiles per household | ≤10 | Snapshot ETag computation walks all profiles serially; #305 blockedMacs evaluation similar. |
| Devices per router | ≤50 | nft `set blocked_macs` is fine; `dhcp-host=` directives bloat dnsmasq config linearly; UCI lookups in agent startup. |
| Site limits per profile | ≤20 | Each emits one nft `set` and one `ipset=` directive; agent does linear scans in `update_shared`. |
| Blocklists per profile | ≤5 | Agent doesn't fetch them today; once #blocklist-application lands, each is a per-poll cached fetch. |
| Households per API host | UNDEFINED | Every router poll triggers a full snapshot recomputation. |
| Usage POST rate | 1 per router per 5min | DB write is per-record; ON CONFLICT DO NOTHING is cheap. |
| Event POST rate | up to 50 events per 10s flush per router | Single linear insert batch. |

**Suggested response:** add a "Scaling assumptions" section to
architecture.md naming the numbers. The point is not to prove the
system can scale further, but to make the assumption visible so the
next architectural pressure (per-conn websocket push, multi-tenant
deployment) gets evaluated against an explicit baseline. Sub-issue
filed.

---

## 8. External dependencies

The hard couplings:

- **dnsmasq:** used for DHCP, DNS, query log, and (today) NXDOMAIN
  enforcement of `extraBlocked`. The canonical model wants the
  NXDOMAIN role removed; if that migration completes, dnsmasq is
  reduced to "DHCP + DNS forwarder + query log." At that point the
  question is: **could we drop dnsmasq and use Unbound + odhcpd**, with
  the bonus of not having to deal with conf-dir-vs-SIGHUP semantics
  (#328)? Probably yes, but: dnsmasq + OpenWRT is the path that every
  OpenWRT user is already on. **No action.** The future ipv6 / per-MAC
  resolver decisions might revisit.

- **nftables:** the enforcement plane. Tight coupling intentional. The
  pain points (DNAT-in-filter #297, atomic load) are nftables-specific
  but well-understood. No change recommended; just ensure CI catches
  them ([§5](#5-failure-modes-and-rendering-pipeline)).

- **conntrack:** the agent's per-flow event source. Couples to the
  kernel module + `conntrack -E` CLI. No structural concern.

- **OpenWRT procd:** packaged with assumptions about init scripts,
  UCI, and OpenWRT-specific tooling (`uci`, `logger`). Acceptable.

- **opkg:** auto-update pulls from GitHub releases. Security audit
  surface (#369). No structural concern.

- **Postgres:** the only persistence layer. The API isn't trying to
  be portable; tight coupling intentional. Migration tooling (Flyway)
  is appropriate.

- **Docker / docker-compose:** the deploy unit. No structural concern.

**No findings; documented for traceability.**

---

## 9. Threat model (light — overlap with #369 noted)

One pass, not a security audit. The question is whether the
architecture has structural defenses or relies on layers not yet built.

The kid-on-LAN threat is the load-bearing one. The architecture's
answer is: "DNS doesn't enforce; nft forward-drop is the only thing
that matters; therefore DoH and DNS bypass are irrelevant."

That answer holds **only if every connection from a kid device is
matched by a per-MAC drop rule that fires.** Three structural concerns:

1. **MAC randomization bypass** (already in the executive summary).
   iOS Private Address, Android randomized MAC, Linux `macchanger`.
   Today: a fresh MAC has no profile → no entry in `blocked_macs` →
   no entry in `profile_macs` → forward chain doesn't drop it.
   The boot default-deny skeleton handles only the boot window. After
   that, unmanaged MACs flow freely. **Sub-issue filed.** Coordinate
   with #369 — this finding belongs structurally here (the architecture
   doesn't *have* a "default policy for unmanaged MAC"), security-wise
   there ([#369]).

2. **Compromised managed device pivots to API.** If malware on a kid's
   laptop discovers `router_token` from the agent's `/etc/config/familydns`,
   it can spoof router calls to the API. The API trusts a router token
   completely. **Severity: medium for a parental-controls product;
   relies on filesystem permissions on the router.** Owned by #369.

3. **DNS-tail cache poisoning.** `familydns-dns-tail` reads
   `/tmp/familydns-dnsmasq.log` and trusts every reply line to update
   the IP→hostname cache. A device that runs its own resolver (DoH /
   direct IP / DNS-over-TCP to 1.1.1.1) doesn't show up in this log,
   so its hostname attribution falls back to IP literal. That's
   correct behavior. But a device that *can write to that log file*
   (impossible if the agent runs as root with normal FS perms; defense
   in depth nonetheless) could poison the cache. Owned by #369.

The architecture's structural defenses are: nft as the only
enforcement plane, per-MAC rules, snapshot-derived rather than
agent-evaluated. These are sound. The MAC-randomization gap is a
**structural** miss: the design does not name "unmanaged MAC" as a
state and so the renderer has no rule for it.

---

## 10. Maintenance + evolution — adding a new feature

Walk-through: imagine adding "block YouTube Shorts specifically (not
the rest of YouTube)" — a hypothetical new feature with no incumbent
implementation.

Steps required today:

1. Add a column to `profiles` or a new table (migration).
2. Wire it into `Profile` / `UpsertProfileRequest` in `shared/Models.scala`.
3. Wire it into `ProfileRepo.update` and `findById`.
4. Wire it into `PolicyService.snapshot` to add a field to `PolicyProfile`
   OR (better) compute its effect into `blockedMacs` / `extraBlocked`.
5. Update `policy_spec.lua` fixtures.
6. Possibly update `render.lua` if the field needs rendering.
7. Update `render_spec.lua` fixtures.
8. Update admin UI form.
9. Document in architecture.md if it's a new concept.

Steps 5 and 7 are the load-bearing pain. The fixture files are hand-
maintained; they go stale silently. The #302 / #305 patterns are
both "field landed in API, render side didn't notice."

**Suggested response:** generate the agent-side fixture from the API.
Concretely: a CI step that runs the API in test mode, POSTs a known
admin payload, GETs the snapshot, and saves it to `openwrt/test/fixtures/`.
The render tests load that. Mismatches between API and agent now
break CI deterministically. Sub-issue filed.

**Pattern observation:** every recent feature has touched
`shared/Models.scala`, `PolicyService.scala`, `policy.lua`, `render.lua`,
and `render_spec.lua`. The Scala side is one strongly-typed source of
truth; the Lua side is a hand-maintained shadow. Either generate Lua
parsing/types from Scala (overkill) or generate fixtures (sufficient).

---

## Systemic patterns

The findings above cluster around four root patterns:

### Pattern A: shape-driven instead of value-driven wire types

The snapshot wire format is a near-direct projection of database rows.
Computed shapes (BlockRules from §0.2) are bolted on as corrections
when the projection isn't enough (`blockedMacs` is the most recent
example). The fix is to invert: design the wire shape from the
**agent's enforcement needs**, then make the API project DB rows down
into that shape. Bugs caused by this pattern: #305, #302, #354.

### Pattern B: external-tool reload without verification

dnsmasq SIGHUP doesn't reload conf-dir (#328). nft `-f` silently keeps
the old ruleset on parse error (#297). Conntrack POSTs are best-effort
(#330, partly fixed). The system has no convention for "call out,
verify the call had its intended effect." #328 added the first such
verification (smoke probe for sinkholed domain). It should generalize.

### Pattern C: implicit state machines

Device lifecycle (#249). Agent failover state (#321, #331).
Snapshot-vs-cached-vs-default-deny (#308, #309). Each was solved by
adding one more flag or one more repo method. The transitions are
correct but unrostered; the next race (DHCP-rename-after-admin-rename,
say) requires another flag.

### Pattern D: tests assert structure, not behavior

`render_spec` validates the string of the generated nft file; nothing
runs nft on it. `policy_spec` validates the snapshot parse; nothing
applies it. `RouterApiSpec` validates the JSON wire; nothing on the
other end consumes it. Bugs caused: #297 (DNAT in inet), #287
(duplicate keyword), #321 (render branch never reached), #305
(schedule field unused). The fix is a small end-to-end suite that
posts → polls → renders → validates with `nft -c`, not necessarily
full e2e with traffic.

---

## What this audit visited and found OK

Audit areas from issue #368 with **no concern worth filing**, recorded
so coverage is reviewable:

- **§2 force-pressures on the API/router split:** the split holds
  for the assumed scale; no forces currently pushing back on it.
- **§4 tmpfs buckets / power-loss correctness:** design call is sound;
  do not revisit.
- **§5 NTP / clock skew handling:** the post-#312 design (skew
  reported, banner shown, enforcement continues) is balanced.
- **§5 DB blip (503 vs 500):** resolved in resilience.md §3 work.
- **§6 admin-action races on independent fields:** parental-controls
  product, last-write-wins acceptable.
- **§6 race between policy edits and policy polls:** etag flips
  naturally; no structural concern.
- **§7 usage POST scale:** current cadence (every 5 min per router)
  fits the assumed scale.
- **§8 each external dependency considered:** no swap-out savings
  judged worth the migration cost today.
- **§8 procd / OpenWRT init coupling:** acceptable; intentional.
- **§10 schema migration tooling (Flyway):** appropriate.

---

## Sub-issues filed

| # | Title | Section |
|---|-------|---------|
| [#370](https://github.com/sameerparekh/familydns/issues/370) | snapshot: collapse decision-inputs server-side; ship canonical BlockRules shape | §3 |
| [#371](https://github.com/sameerparekh/familydns/issues/371) | render: validate generated nft + dnsmasq config against target tool in CI | §5 |
| [#372](https://github.com/sameerparekh/familydns/issues/372) | device-ingest: consolidate first_seen / dhcp / rename into single repo method | §4 |
| [#373](https://github.com/sameerparekh/familydns/issues/373) | e2e: end-to-end policy round-trip from admin POST to nft rule | §10 |
| [#374](https://github.com/sameerparekh/familydns/issues/374) | enforcement: default-drop rule for unmanaged-MAC bypass | §9 |
| [#375](https://github.com/sameerparekh/familydns/issues/375) | API: ETag-keyed in-memory snapshot cache to avoid per-poll recompute | §2 |
| [#376](https://github.com/sameerparekh/familydns/issues/376) | snapshot: add snapshotVersion field; agents refuse unknown versions | §4 |
| [#377](https://github.com/sameerparekh/familydns/issues/377) | render: capture nft / dnsmasq stderr; surface apply failures | §5 |
| [#378](https://github.com/sameerparekh/familydns/issues/378) | API: optimistic locking (If-Match: ETag) on profile updates | §5 |
| [#379](https://github.com/sameerparekh/familydns/issues/379) | docs: pin scaling assumptions in architecture.md | §7 |
| [#380](https://github.com/sameerparekh/familydns/issues/380) | tests: generate Lua render fixtures from a live API run | §10 |
