# Architecture critique — pre-rename, pre-go-live

Status: **review draft.** Tracks [#368](https://github.com/sameerparekh/familydns/issues/368).

This is a hostile-critic review of the **design record** — `docs/architecture.md`,
`docs/resilience.md`, `AGENTS.md`'s two truths, and the wire contracts in
`shared/src/Models.scala`. The earlier draft of this document critiqued the
*code state* (snapshot shape, render output, CI gaps); on review those were
migration debt being tracked under #354 and the canonical-model rollout. They
are not architectural findings.

What survives are eleven concerns with the **design record itself** — claims
the architecture asserts but doesn't defend, tradeoffs it accepts without
naming the alternative, lifecycle edges it doesn't draw, and internal drift
between the docs and the wire types.

The deliverable is this document plus eleven sub-issues. Each finding has a
concrete design response — agreed in the #368 thread.

Out of scope: security has its own audit ([#369](https://github.com/sameerparekh/familydns/issues/369)).
Where a finding has both structural and security framing, this doc owns the
structural side and cross-references #369.

---

## Executive summary — top 5 architectural findings

1. **MAC-as-identity is load-bearing but undefended.** Every per-device
   decision keys on MAC. MAC randomization (iOS Private Address, etc.) is
   not named anywhere in the architecture. The model has no "unmanaged
   MAC" state and no design for what happens when a fresh MAC appears.
   **Response:** explicit `unmanagedMacPolicy` in the snapshot, an
   "unmanaged" admin-UI page with per-MAC enroll/block/allow actions,
   and push notification when a new MAC appears (web now, mobile later).
   Sub-issue: [#374](https://github.com/sameerparekh/familydns/issues/374) (rewritten).

2. **The wire contract has no schema-evolution policy.** §0.2 and §6.2
   define field names; nothing says how versions evolve, no `snapshotVersion`,
   no capability negotiation. As written, the architecture reads as "one
   version forever." This blocks every future migration that has to ride
   alongside older agents/APIs in the field (auto-update is not instant).
   **Response:** define a capability set on both ends; snapshot carries
   `snapshotVersion` + `serverCapabilities`; agent advertises
   `agentCapabilities` on enrollment and policy GET; API serves the highest
   shape both ends understand. Sub-issue: [#376](https://github.com/sameerparekh/familydns/issues/376) (rewritten).

3. **`FailureMode` is internally inconsistent across the design record.**
   `architecture.md` §0.2 specifies `BlockAll | AllowAll | LastKnownGood`.
   `resilience.md` §4 and `Models.scala` use `Open | Closed`. The
   canonical record contradicts itself. **Response:** align on three
   modes — `BlockAll`, `AllowAll`, `LastKnownGood` — semantically
   distinct, named consistently across architecture, resilience, and
   the wire type. `Open`/`Closed` is a two-bit collapse of three states
   and loses the LastKnownGood case. Sub-issue filed (see table).

4. **Cross-platform portability is asserted, not proven.** §3 commits
   to OpenWRT + OpnSense both implementing the same wire contract,
   each rendering the snapshot to platform primitives. nft `ether saddr
   @set` is a clean primitive on Linux; pf's MAC matching in the
   forward path on FreeBSD is not the same shape. The architecture
   maps "Packet filter | nftables | pf / pfctl" in a table without
   demonstrating that `BlockRules` semantics translate. This will
   surprise the OpnSense agent author at design time; better to surface
   the constraint now and either prove portability or scope the model
   to "Linux-class platforms." Sub-issue filed (see table).

5. **Agent responsibilities are under-specified in §3.2.** Architecture
   says "implement HTTP calls + platform-specific rendering." Real
   contract collected from resilience.md: flash-cache snapshot for boot
   recovery, retry queue with exponential backoff for usage, retry
   queue with cap-and-drop for events, monotonic-clock scheduler,
   smoke-probe post-reload, failover state machine, boot default-deny
   skeleton, atomic apply, version handshake. Every OpnSense-agent
   contributor will hit each as a surprise. Sub-issue filed (see table).

The other six findings (HTTPS block UX, `blockIpOnly` binary, one-etag
two-rate-classes, failover threshold ownership, router-deletion
lifecycle, hostname as unconstrained string) follow.

---

## 1. MAC-as-identity is undefended

§0.2 specifies per-MAC enforcement. Every nft rule keys on `ether saddr`.
Every device row is a MAC. Every snapshot decision is per-MAC. This is the
load-bearing identity axiom of the entire enforcement model.

The architecture is silent on:

- **MAC randomization.** iOS Private Address generates a fresh MAC per SSID.
  Android does the same. Linux `macchanger` is trivial. A fresh MAC has no
  profile, no entry in `blocked_macs`, no rule that touches it.
- **The "unmanaged MAC" state.** The design model has two device states
  (in-profile, in-blocked-set). There is no third state for "MAC seen,
  not assigned." The boot default-deny skeleton (#308) covers only the
  boot window; after the first apply, the new ruleset has no fallthrough.

### Design response

- Snapshot carries `unmanagedMacPolicy: { policy: "block" | "allow", blockPage: bool }`,
  default `block`.
- Render emits a fallthrough chain matching any forwarded MAC not in any
  per-profile set, applying the unmanaged policy.
- API tracks "first seen, not assigned" MACs as a first-class entity,
  exposes them at `/api/admin/unmanaged-macs`.
- Admin UI grows a "Unmanaged" page listing them with per-MAC actions
  (assign to profile, block permanently, allow-passthrough).
- Web admin UI push notification (websocket / SSE) fires when a new
  unmanaged MAC appears. Mobile app push notification later when the
  app exists.

This is the right answer to MAC randomization in a parental-controls
product: you can't prevent it, but you CAN make the resulting unmanaged
MAC a loud, surfaced event rather than a silent bypass.

Sub-issue: [#374](https://github.com/sameerparekh/familydns/issues/374).

---

## 2. HTTPS block-page UX has no architectural answer

§7.6 commits to nft DNAT on TCP/80 to the local block page. HTTPS times out
silently. The architecture accepts this with one sentence: "blocked HTTPS
times out (no MITM without a CA install — same behavior as commercial
parental-control boxes)."

As the web HTTPSifies, "your block is invisible" degrades by year. A child
who hits a blocked site over HTTPS sees the browser's generic "this site
can't be reached" — they have no idea their parent's filter is what blocked
it. That UX gap encourages exactly the wrong learning ("the wifi is broken,
let me find a way around it") rather than the right one ("this is blocked,
ask a parent").

### Design response

DNAT HTTPS/443 from blocked MACs to the local block-page server presenting
its own self-signed cert. The browser shows a cert warning; the user clicks
through; they land on the block page. The trade is:

- **What we gain:** the block reason is reachable on HTTPS. The user gets
  an explanation, not a hang.
- **What we accept:** users see a cert warning before reaching the block
  page. This is *worse* than the HTTP block-page UX, but *better* than the
  HTTPS timeout UX. The TLS warning is acceptable because the block page
  is intentionally a non-standard interception; the warning is the truth.
- **What we do NOT do:** install a CA on managed devices (rejected:
  invasive, not commodity, requires per-device setup, escalates the trust
  model). The cert warning is the design.

Sub-issue filed.

---

## 3. `blockIpOnly` is a binary defense

§0.2 specifies `blockIpOnly`: drop forwarded traffic to any IP we did not
resolve for this MAC. The strict form has no allowlist carve-out. As
written:

- **On:** drops legitimate CDN / push-notification / hardcoded-IP traffic
  that bypassed the local resolver. Many apps break.
- **Off:** DoH / hardcoded-IP bypasses the entire model.

There's no middle setting designed.

### Design response

`blockIpOnly` should drop IPs *unless* the destination IP is in the union
of `(ipset(host) for host in extraAllowed)`. The `extraAllowed` carve-out
already exists at the per-host level; extending it to apply to IPs
resolved-for-that-host gives the parent a tool: "allow `firebase.com` and
also any IP that resolved to a `firebase.com` for any MAC."

This narrows the binary into a designed third state: "drop IPs that aren't
allowlisted or weren't resolved-for-this-MAC." The defense against DoH is
preserved (DoH-resolved IPs to non-allowlisted hosts are still dropped)
while sparing common-case CDN traffic.

Sub-issue filed.

---

## 4. Wire contract has no schema-evolution policy

The architecture defines field names but not how they evolve. There is no:

- `snapshotVersion` field.
- API capability set advertised to the agent.
- Agent capability set advertised to the API.
- Documented policy for "API ships field X, agent doesn't know it" — does
  the agent ignore it (forward-compat), reject the snapshot (strict), or
  apply a partial enforcement (degraded)?

Auto-update is not instant; even after #279 lands fully, agents and APIs
will run mismatched versions in the field for hours-to-days during rollouts.

### Design response

Add capability negotiation to the wire contract:

- Snapshot carries `snapshotVersion: Int` (start at 1; bump on
  breaking-shape change).
- Snapshot carries `serverCapabilities: Set[String]` listing optional
  features the server can produce (e.g. `"failover-threshold-in-snapshot"`,
  `"unmanaged-mac-policy"`).
- Agent advertises `agentCapabilities: Set[String]` on enrollment AND on
  every `GET /api/router/policy` (header or query param).
- API serves the snapshot shape negotiated by the intersection.
- Documented policy: agent must ignore unknown top-level fields
  (forward-compat) but must refuse a snapshot whose `snapshotVersion`
  exceeds its known version (no silent partial enforcement).

The capability set lets new features land server-side without breaking
older agents — the canonical example is shipping #370 (canonical-shape
collapse) safely while old agents still poll.

Sub-issue: [#376](https://github.com/sameerparekh/familydns/issues/376) (rewritten).

---

## 5. `FailureMode` mismatch across the design record

| Source | Variants |
|---|---|
| `architecture.md` §0.2 | `BlockAll`, `AllowAll`, `LastKnownGood` |
| `resilience.md` §4 | `closed`, `open` (~= BlockAll, AllowAll w/ cached snapshot riding) |
| `Models.scala` `FailureMode` | `Open`, `Closed` |
| Wire format (JSON) | `"open"`, `"closed"` |

This is the design record contradicting itself. `Open`/`Closed` is a
collapse of three semantic modes into two; `LastKnownGood` is meaningfully
distinct from `AllowAll` (one keeps the cached snapshot enforcing exactly
as last seen; the other drops all restrictions). The current Lua agent
treats `Open` as roughly `LastKnownGood` ("keep enforcing the cached
snapshot exactly as-is") — i.e. the code chose one interpretation while
the doc shipped both.

### Design response

Align on three modes, named consistently across architecture.md,
resilience.md, Models.scala, and the wire format:

- `BlockAll` — drop all forwarded traffic for this profile's devices when
  failed-poll-age > threshold. Wire form: `"block-all"`.
- `AllowAll` — pass forwarded traffic with no enforcement when failed-poll
  threshold exceeded. Wire form: `"allow-all"`.
- `LastKnownGood` — keep enforcing the cached snapshot exactly as-is when
  failed-poll threshold exceeded. Wire form: `"last-known-good"`.

Default for child profiles: `BlockAll`. Default for adult/admin: `LastKnownGood`.
(The current `Open` collapses the adult use case into `AllowAll`, losing
the cached-snapshot defense — `LastKnownGood` is a better default for
adults.)

Sub-issue filed.

---

## 6. Failover threshold is architecturally unowned

`resilience.md` §4 specifies a 5-minute threshold for the API-unreachable
failover transition. It is a magic number with no design home:

- Is it API-side configuration?
- Is it agent-side configuration?
- Is it per-profile?
- Is it negotiated?

Currently it's a hard-coded constant in `policy.lua`'s `failover_transition`
([policy.lua:386](openwrt/files/usr/lib/lua/familydns/policy.lua:386)) AND
in `render.lua`'s `poll_age > 300` check
([render.lua:230](openwrt/files/usr/lib/lua/familydns/render.lua:230)). To
change the threshold, the agent has to be re-released and re-installed —
which is exactly when you'd want to change it (incident response).

### Design response

The failover threshold is managed by the API server, shipped in the
snapshot:

- `PolicySnapshot.failoverThresholdSeconds: Int` (default 300).
- Optionally per-profile if there's a use case; start with one
  household-wide value.
- Agent reads from snapshot; falls back to a built-in default (300s) if
  absent (forward-compat with older API).

Operational benefit: in an incident, the operator can raise the threshold
to 30 minutes without re-flashing routers.

Sub-issue filed.

---

## 7. Cross-platform portability is asserted, not proven

§3 commits to OpenWRT + OpnSense both implementing the same wire contract.
The "Platform divergence" table maps primitives:

| Concern | OpenWRT | OpnSense |
|---|---|---|
| Packet filter | nftables | pf / pfctl |
| Per-MAC | `ether saddr @set` | (pf MAC matching) |
| Traffic accounting | nftables counters | pflog / pf tables |
| Hostname attribution | dnsmasq `--ipset=` | Unbound query log |

What the architecture **doesn't** show:

- pf on FreeBSD is primarily L3+ in the forward path. `ether saddr`
  matching exists but is constrained and not as ergonomic as nft's
  `@set`-of-MACs. Whether `BlockRules` semantics translate cleanly to pf
  is not demonstrated.
- Unbound's query log gives `(client IP, qname)`. Correlating client IP →
  MAC via DHCP lease is documented in §8.2, but the canonical-model
  promise of "per-MAC hostname attribution" is one indirection further on
  OpnSense than on OpenWRT. That indirection's failure modes (DHCP lease
  race vs. DNS query) aren't covered in resilience.md.
- nft can flush+load atomically with one `nft -f`; pf has `pfctl -T
  replace` per-table but no equivalent atomic multi-table swap. Atomic
  apply is a load-bearing invariant in the OpenWRT design; the OpnSense
  story is unspecified.

### Design response

Before #94 (OpnSense agent) starts, produce a focused design doc that:

- Sketches the pf ruleset shape rendered from `BlockRules`.
- Proves (with a small bench config) that per-MAC matching at scale (~50
  devices) is performant on pf.
- Documents how Unbound + DHCP lease correlation produces the same
  `(mac, hostname)` attribution shape as dnsmasq.
- Names the atomic-apply story on pf.
- Either confirms the wire contract is portable as-is, or identifies the
  subset of `BlockRules` that doesn't translate (and proposes either
  shrinking the wire contract OR scoping the architecture to "Linux-class
  router platforms").

Sub-issue filed.

---

## 8. §3.2 understates agent responsibilities

Architecture says an agent implements "HTTP calls + platform-specific
rendering." Real list (collected from resilience.md, the OpenWRT agent
code, and recent fix PRs):

- One-shot enrollment + bearer-token persistence
- Periodic policy poll with ETag
- Atomic render → apply (dnsmasq + nft)
- Flash-cache snapshot for boot recovery (#309)
- Boot-time default-deny skeleton (#308)
- Smoke-probe post-reload to verify external tool actually applied (#328)
- Usage POST every 5min, with exponential-backoff retry queue (#309)
- Events POST with cap-and-drop retry queue (#330)
- Monotonic-clock scheduler immune to wall-clock jumps (#336)
- Failover state machine with poll-age tracking (#311 / #321 / #331)
- Hostname attribution sidecar (dnsmasq query-log tail; #259)
- DHCP-script hook for lease events
- Conntrack watcher for per-flow events
- Capability advertisement on enrollment + each poll (per §4 above)

Every item above surfaced as a separate fix; an OpnSense agent contributor
will rediscover each as a separate surprise.

### Design response

Add a new section to `docs/architecture.md` ("Agent responsibilities,
fully specified") listing all of these as the contract every agent must
implement, with one paragraph each pointing at the originating concern.
The "agent author only needs to implement HTTP calls" framing in §3.2 is
misleading and should be removed.

Sub-issue filed.

---

## 9. One ETag covers two rate-classes of state

The snapshot conflates two kinds of change:

- **Slow:** profile config, device→profile assignment, blocklist URLs,
  failover policy. Changes on admin action; can be hours-to-days between
  changes.
- **Fast:** schedule-window edges, time-limit exhaustion, pause. Changes
  continuously against wall-clock; every minute past 18:00 invalidates
  the etag of every household with an 18:00 schedule.

Both flow through one snapshot, one etag, one 60s poll. Consequences:

- Schedule-edge thundering herd: at 18:00 every router with a 6PM
  bedtime polls and gets a new snapshot containing identical
  device/profile data plus a flipped `blocked` flag for the affected
  MAC. The slow-state half is re-shipped on every fast-state change.
- A user's "block X now" admin action propagates at poll-interval
  latency (worst-case 60s). For an angry-parent UX this is borderline.
- Liveness signal (admin UI showing router status) is poll-bound.

### Design response

**Accept and document the tradeoff.** The simplicity benefit of one
snapshot / one etag / one cadence is real, and at household scale the
thundering-herd cost is irrelevant. Add a "Design tradeoffs accepted"
section to `docs/architecture.md` naming this explicitly:

> The wire contract conflates slow-changing config state and
> fast-changing wall-clock-derived state into one snapshot. Worst-case
> admin-action propagation latency is one poll interval (60s). Schedule
> edges cause etag churn at the edge time across all affected routers.
> We accept this in exchange for protocol simplicity. If
> sub-poll-interval blocking ever becomes a product requirement, a
> server-push channel (websocket/SSE) will be added as an additive,
> non-breaking change.

This isn't a sub-issue against the design — it's a doc-edit recording
the deliberate tradeoff so future contributors don't waste time
re-deriving it. Sub-issue filed for the doc edit.

---

## 10. No design for router-record deletion

Architecture covers enrollment + ongoing poll. It doesn't cover deletion:

- An admin deletes a router record via `DELETE /api/admin/routers/:id`.
- The agent is still running on the router with a valid (now-orphaned)
  bearer token.
- What does the API return on the agent's next poll? 401? 410? 404?
- What does the agent do? Keep enforcing the cached snapshot? Wipe and
  default-deny? Stop and allow-all?

Currently undefined.

### Design response

On router-record deletion:

- API marks the token revoked.
- Subsequent `/api/router/*` calls from that token return **410 Gone**
  (semantically: "you used to exist, you do not anymore").
- Agent on 410: log loudly, stop enforcing, flush the nft ruleset back
  to allow-all (no default-deny — the user explicitly disowned this
  router; the LAN should keep working).
- Agent stops the daemon (exit non-zero so procd doesn't auto-restart).
- A re-enrollment requires a fresh enrollment token from a new admin
  action.

Rationale for allow-all (not default-deny on disownment): the admin's
action of deleting the router signals "this router is no longer mine";
keeping the LAN locked down after that is hostile. The LAN reverts to
"no familydns enforcement" — same as if the package were uninstalled.

Sub-issue filed.

---

## 11. `hostname` is an unconstrained string in the wire contract

Architecture treats `hostname` as text. `usage.records[*].hostname`,
`events.dns_query.qname`, `time_usage.domain`, the block-reason
`category:<host>` — all are typed `string` in the wire and in the schema.

But §7.2 establishes a fallback: when dns-tail can't attribute an IP to
a hostname (direct-IP traffic, DoH-resolved domain, sidecar race), the
agent falls back to **the IP literal in the hostname field**. So
`time_usage` rows can have `hostname = "192.0.2.1"`. Site-limits assume
domain-pattern matching; matching `*.example.com` against `192.0.2.1`
is just a miss. Presence reports show "192.0.2.1" as a top-host.

The architecture never names "what's the type of hostname, really?"

### Design response

Promote `hostname` to a union type at the wire contract level:

```scala
sealed trait HostId
object HostId:
  case class FQDN(name: String) extends HostId      // "youtube.com"
  case class IPv4(addr: String) extends HostId      // "192.0.2.1"
  case class IPv6(addr: String) extends HostId      // "2001:db8::1"
```

Wire form: `{"type": "fqdn", "value": "youtube.com"}` or shorthand string
+ heuristic parse on the receive side (TBD which).

Downstream consequences (each is a small follow-up, not architectural):

- `time_usage` schema gains a `host_type` column.
- Site-limit pattern matching skips non-FQDN rows.
- Presence reports group IP-typed rows under a "direct-IP traffic" bucket.
- Admin UI renders IPv4/IPv6 hosts distinguishably from named hosts.

Sub-issue filed.

---

## Systemic patterns

The eleven findings cluster into four design-record-level patterns:

### Pattern A: load-bearing axioms named but not defended

The architecture asserts a property (MAC-is-identity, hostname-is-text,
nft-is-the-only-enforcement-plane) and builds on it without surfacing
what happens when the assumption breaks. Findings: §1 (MAC randomization),
§11 (hostname-as-IP fallback).

### Pattern B: tradeoffs accepted without naming the alternative

The design has chosen something (HTTPS-hangs over HTTPS-warning-page,
binary blockIpOnly, one snapshot for two rate-classes) but the design
record doesn't show the alternative was considered. Findings: §2 (HTTPS
block), §3 (blockIpOnly), §9 (one ETag).

### Pattern C: lifecycle edges undesigned

Enrollment and ongoing poll are covered; the edges around them are not.
Findings: §4 (schema evolution), §6 (failover threshold ownership), §7
(cross-platform translation), §10 (router-record deletion).

### Pattern D: internal drift within the design record

The canonical record contradicts itself, or under-specifies what an
implementer must produce. Findings: §5 (FailureMode mismatch), §8 (agent
responsibilities understated).

---

## What this audit visited and found OK at the architecture level

Recorded for traceability:

- **§0.1 "DNS is never the enforcement plane."** Sound axiom; well-defended
  with concrete rejection reasons (DoH, hardcoded-IP, per-MAC granularity,
  block-page UX). No concern.
- **§0.2 split between `MacBlockReason` and per-flow `BlockReason`.** The
  type-system enforcement (router can't emit a Mac-level reason in a
  per-flow event) is a clean design.
- **§2 topology assumptions** (one API, one router per household, HTTP for
  router endpoints, bearer tokens). All sound for the current scope.
- **§5 worst-case staleness = one poll interval.** Acceptable for the
  product; documented in §5.
- **`resilience.md` §1–3, §5** (power loss boot-deny, API restart with
  cached snapshot, DB blip 503, router-time-independence). All sound;
  the fragile parts were the implementation gaps, now mostly closed.
- **§6 wire contract endpoints** (`register`, `policy`, `usage`,
  `events`, `decision`, `/blocked`). Endpoint set is sound; the only
  evolution concern is captured under §4 above.
- **§9 schema.** Platform-neutral; appropriate.
- **§10 rollout sequence.** Sound. The deletion of `dns/` and `traffic/`
  modules in #71/#125 was the right call.

---

## Sub-issues filed

| # | Title | Finding |
|---|-------|---------|
| [#374](https://github.com/sameerparekh/familydns/issues/374) | unmanaged-mac: alerting, default-block flag, admin UI page, push notification | §1 |
| [#383](https://github.com/sameerparekh/familydns/issues/383) | block-page: serve HTTPS variant with self-signed cert | §2 |
| [#384](https://github.com/sameerparekh/familydns/issues/384) | blockIpOnly: allow IPs that resolved-for-this-MAC to extraAllowed hosts | §3 |
| [#376](https://github.com/sameerparekh/familydns/issues/376) | wire-contract: schema versioning + capability negotiation | §4 |
| [#385](https://github.com/sameerparekh/familydns/issues/385) | FailureMode: align design record on three modes (BlockAll / AllowAll / LastKnownGood) | §5 |
| [#386](https://github.com/sameerparekh/familydns/issues/386) | snapshot: failover threshold owned by API, shipped in snapshot | §6 |
| [#387](https://github.com/sameerparekh/familydns/issues/387) | OpnSense agent: design doc proving wire-contract portability before #94 | §7 |
| [#388](https://github.com/sameerparekh/familydns/issues/388) | docs: fully specify agent responsibilities in architecture.md §3.2 | §8 |
| [#389](https://github.com/sameerparekh/familydns/issues/389) | docs: document the one-ETag two-rate-classes tradeoff | §9 |
| [#390](https://github.com/sameerparekh/familydns/issues/390) | router-deletion: 410 Gone + agent stops + allow-all | §10 |
| [#391](https://github.com/sameerparekh/familydns/issues/391) | wire-contract: hostname becomes union of FQDN \| IPv4 \| IPv6 | §11 |
