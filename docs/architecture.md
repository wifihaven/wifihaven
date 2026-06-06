# Router-based enforcement architecture

Status: **partially implemented.**
- §5.1–5.5 and §9 schema in production (issues #67, #68/#98, #69/#97).
- §5.6 pending (#70).
- OpenWRT agent (#72) and OpnSense agent (#94) pending.

> **Read this first.** The "Enforcement model" section below is the canonical
> architecture. Some current code deviates from it (called out inline and in
> follow-up issues); the model is the target. If you are writing a new
> feature, follow the model.

## 0. Enforcement model

Two truths govern everything that follows.

### 0.1 DNS is never the enforcement plane

DNS always resolves. Blocking happens at the **connection layer** via
nftables forward-drop on the gateway router. The block page is reached via
HTTP DNAT on port 80, **not** via DNS sinkhole, NXDOMAIN, or RPZ.

dnsmasq is still used on the router, but only for **hostname attribution**:
it populates per-host nftables ipsets via `--ipset=` callbacks at resolution
time, so nftables can match destination IPs back to the hostname the device
asked for. dnsmasq does not return a fake answer for any blocked host — the
host resolves normally, the device opens a TCP connection, and nftables
either drops the SYN (blocked) or DNATs HTTP/80 to the local block page.

> **Corollary — "a hostname resolved" tells you nothing about reachability.**
> The resolved IP is precisely what nftables drops, so never infer "DNS
> resolved ⇒ not blocked," and never describe a fix as "allow the domain's
> DNS." An allowlist entry carves the host's *resolved IPs* out of the
> forward-drop via the per-`(mac, host)` `ea_` ipset that dnsmasq's nftset
> callback populates at resolve time (§0.2 `extraAllowed`) — it does not
> change any DNS answer. This connection-layer-vs-DNS confusion recurs in
> agent work (e.g. [#1307](https://github.com/wifihaven/wifihaven/issues/1307));
> see [#1313](https://github.com/wifihaven/wifihaven/issues/1313).

We rejected DNS-based blocking (sinkhole / RPZ / NXDOMAIN) because:

- DoH / DoT bypasses any DNS-layer block trivially.
- Hard-coded-IP traffic is invisible to a DNS-layer block.
- DNS-layer blocks are usually global, not per-MAC, so a single profile's
  blocklist leaks to other devices.
- The block-page UX is poor when the device gets NXDOMAIN instead of a
  real HTTP response.

The `blockIpOnly` flag (see §0.2) is what closes the DoH / hard-coded-IP
hole: forwarded traffic to an IP we did not resolve for this MAC is
dropped.

### 0.2 The router agent is a dumb applier

The API server's `PolicyService` evaluates every policy concept — schedules,
daily and per-site time limits, pause state, category membership, manual
blocks, role-based defaults, failover behaviour — and bakes the result into
a small fixed snapshot. The agent resolves device → effective rules once at
apply time and then enforces purely per-MAC. **Profiles never appear in the
enforcement pipeline.**

Target snapshot shape (Scala 3, sealed-trait ADTs per [#114](https://github.com/wifihaven/wifihaven/issues/114);
typed names used here even though the current code is stringly):

```scala
case class PolicySnapshot(
    etag: ETag,
    generatedAt: Instant,
    global: BlockRules,                        // fleet-wide; applied to every MAC (§0.3).
                                               //   Same shape as ProfilePolicy.rules:
                                               //   extraAllowed = always-reachable hosts
                                               //   (UI, block page, connectivity, PKI),
                                               //   extraBlocked/blocklistIds = global
                                               //   blocks, blocked = network lockdown.
    devices: Map[MacAddress, DevicePolicy],
    profiles: Map[ProfileId, ProfilePolicy],   // wire-dedup only; not consulted at enforcement
    blocklists: Map[BlocklistId, Blocklist],
)

case class DevicePolicy(
    profileId: Option[ProfileId],
    name: String,
    rules: Option[BlockRules],                 // if Some, the API-resolved per-MAC rules
                                               //   (profile+device merged server-side);
                                               //   replaces the profile lookup at the router
)

case class ProfilePolicy(
    name: String,
    rules: BlockRules,
    failureMode: FailureMode,                  // per-profile: what the router does
                                               //   for THIS profile's devices the moment
                                               //   a policy poll fails (#422 — failover
                                               //   trips on the first failed poll; no
                                               //   time-based cushion). See
                                               //   docs/resilience.md §4 for the
                                               //   per-mode behaviour and the
                                               //   defaulting policy.
)

case class BlockRules(
    blocked: Boolean,                          // drop all forwarded traffic from this MAC
    blockReason: Option[MacBlockReason],       // for block-page text only
    extraBlocked: List[Hostname],
    extraAllowed: List[Hostname],              // carves out blocks, incl. when blocked = true
    blocklistIds: List[BlocklistId],
    blockIpOnly: Boolean,                      // drop literal-IP traffic not resolved for this MAC
)

case class Blocklist(version: BlocklistVersion, url: Url)

// BlockReason is the unified reason emitted for any drop, both in connection
// events and on the block-page query string. It is split into two layers:
//
//  - MacBlockReason covers reasons a whole MAC is blocked. PolicyService can
//    only emit these in BlockRules.blockReason — schedule / time-limit / etc.
//    are server-side computations.
//  - The remaining BlockReason variants describe per-flow drops decided by
//    the router at packet time and are never present in the snapshot.
//
// The split is enforced by the type system: BlockRules.blockReason is typed
// as Option[MacBlockReason], so a router-only reason cannot be assigned to
// a snapshot field.
sealed trait BlockReason

sealed trait MacBlockReason extends BlockReason
object MacBlockReason:
  case object Paused      extends MacBlockReason
  case object Schedule    extends MacBlockReason
  case object TimeLimit   extends MacBlockReason
  case object Manual      extends MacBlockReason
  case object DefaultDeny extends MacBlockReason   // profile is default-deny baseline (§0.3)

object BlockReason:
  // Per-flow drop reasons — emitted by the router at connection-drop time
  // for the events log and the /blocked query string.
  case class Host(host: Hostname)                          extends BlockReason
  case class Category(host: Hostname, list: BlocklistId)   extends BlockReason
  case class IpOnly(dstIp: IpAddress)                      extends BlockReason

sealed trait FailureMode
object FailureMode:
  case object BlockAll      extends FailureMode
  case object AllowAll      extends FailureMode
  case object LastKnownGood extends FailureMode
```

Resolution step (the only place profiles touch enforcement):

```scala
def effective(d: DevicePolicy, profiles: Map[ProfileId, ProfilePolicy]): BlockRules =
  d.rules
    .orElse(d.profileId.flatMap(profiles.get).map(_.rules))
    .getOrElse(BlockRules.allowAll)
```

After this single deref, the agent works with a `Map[MacAddress, BlockRules]`
and profiles are unreachable.

> **Scope decision (2026-06): authored policy has exactly two tiers — global
> and profile. There is NO per-device override authoring surface.** The wire
> shape (`DevicePolicy.rules: Option[BlockRules]`) *can* carry a device-specific
> override, and `effective` above honours it — but the only thing that populates
> `rules` today is the server-side **unmanaged-MAC block** path (a device with
> no `profileId` under a `block` household policy). A managed device always
> takes its rules from its assigned profile (`rules = None` on the wire).
>
> This is deliberate, not a gap: we compose policy from **global ∘ profile**
> and assign devices to profiles. There is no DB column, repo, route, or SPA
> editor for a per-device override, and we are **not** adding one right now.
> Do not build per-device rule authoring, and do not describe the `rules` field
> as a user-facing "device override" — it is an internal mechanism reserved for
> the unmanaged-block case. (The `rules`-override **wire capability** stays
> because it is additive and already used by that path; keeping it costs nothing
> and removing it would be a breaking wire change.) Tracked/closed:
> [#1452](https://github.com/wifihaven/wifihaven/issues/1452).

Enforcement plane per field:

| Field | Enforcement |
|-------|-------------|
| `blocked` | nftables: `ether saddr ∈ blocked_macs` → drop forwarded traffic; DNAT HTTP/80 to local block page |
| `extraBlocked` | nftables: `(ether saddr == mac, ip daddr ∈ ipset(host))` → drop. Per-host ipset populated by dnsmasq `--ipset=` at resolution time. **No `address=/host/#` NXDOMAIN.** |
| `extraAllowed` | nftables: explicit accept above the drop rules for this MAC |
| `blocklistIds` | Agent fetches blocklist by URL (cached by version), resolves member hosts periodically, populates per-blocklist ipset. nftables: `(mac, ip daddr ∈ ipset(blocklist))` → drop. **No RPZ.** |
| `blockIpOnly` | nftables: `(mac, ip daddr ∉ ⋃(per-host ipsets resolved for this MAC))` → drop. Strict — no allowlist carve-out, since we cannot attribute the IP to a hostname. |

dnsmasq's role is exclusively: forward DNS upstream, populate per-host
nftables ipsets via `--ipset=`, and write a query log that `dns-tail` reads
for usage attribution (§7.2). It is **never** the enforcement plane.

### 0.3 The global policy layer (#1308)

Fleet-wide policy is carried **once** in `snapshot.global`, not copied into
every profile/MAC. **It is a `BlockRules` — the same shape `ProfilePolicy.rules`
carries** — applied to every MAC, **not a third tier** in the
device-replaces-profile resolution above. Each field keeps its usual router
behaviour, just fleet-wide: `global.extraAllowed` = hosts always reachable from
every MAC (the WifiHaven UI / block page, connectivity check, PKI — the router
does not care *why*); `global.extraBlocked` / `global.blocklistIds` = blocks a
profile may not un-block; `global.blocked` = whole-network lockdown;
`global.blockIpOnly` = network-wide strict mode.

Composition has a fixed precedence. Let `G` = `snapshot.global` and `R` = the
MAC's resolved per-MAC `BlockRules`; for a forwarded packet from MAC `m` to
destination `d`:

```
ga(d)       ⇔ d ∈ G.extraAllowed
gblock(d)   ⇔ G.blocked ∨ d ∈ G.extraBlocked ∨ d ∈ ⋃ ipset(G.blocklistIds)
rblock(m,d) ⇔ R.blocked ∨ d ∈ R.extraBlocked ∨ d ∈ ⋃ ipset(R.blocklistIds)

drop(m, d) ⇔
      ¬ga(d) ∧ ( gblock(d) ∨ ( d ∉ R.extraAllowed ∧ rblock(m,d) ) )
   ∨  (G.blockIpOnly ∨ R.blockIpOnly) ∧ d ∉ resolved_<m>
```

The ladder, top wins: `global.extraAllowed` → global block → per-MAC
`extraAllowed` → per-MAC block → `blockIpOnly` (orthogonal). Per-MAC
`extraAllowed` suppresses per-MAC blocks but **not** a global block; only
`global.extraAllowed` does. That makes the override directions precise:
**`global.extraAllowed` always wins**, a **global block a profile may not
un-block**, and a **global *default* a profile may override** (loosenable
defaults are resolved server-side into per-MAC `BlockRules` and never reach the
wire as "global"). On OpenWRT this is two extra fleet-wide ipsets
(`@global_allow`, `@global_block`) layered on the per-MAC rules; the router
still never sees a profile, schedule, or tier. `global.extraAllowed` is a
security-sensitive bypass surface — curated and auditable server-side (the
*why* stays in the DB; the wire carries only the hostname list). See
[`docs/design/global-policy-layer.md`](design/global-policy-layer.md) for the
full composition model, precedence table, and per-profile **default-deny**
mode (`blocked = true` baseline + `MacBlockReason.DefaultDeny`, with
`extraAllowed` and `global.extraAllowed` carving out).

> **Status (#1308).** Server-side assembly has landed (#1318):
> `PolicyService` now emits `snapshot.global` (a `BlockRules`) from the V48
> global-policy tables + the per-deployment UI hosts, evaluates per-profile
> `default_deny` into `blocked = true` + `MacBlockReason.DefaultDeny`, and the
> snapshot ETag covers the global section. The router composes the global
> section (#1319, `@global_allow` / `@global_block`). Both fleet-wide
> always-reachable sets — the **deployment UI / block-page hosts** and the
> curated **#1307 infra allowlist** (connectivity-check / OCSP / PKI /
> captive-portal / gvt2) — now live in `global.extraAllowed` and are emitted
> **once**; the per-profile copy in `PolicyService.computeBlockRules` has been
> retired (#1321). They beat every block path for every MAC via the router's
> global carve-out, exactly as the old per-(MAC) `ea_` copies did, with no
> per-profile duplication and a single ETag-moving source.

> **Known deviations from this model as of May 2026** (tracked follow-ups,
> not the canonical design):
>
> - `extraBlocked` is IPv4-only — dnsmasq populates the `eb_<host>` set on
>   A records, but AAAA replies are not captured into a parallel `eb6_<host>`
>   v6 set, so a blocked host that resolves over v6 escapes the drop (#392).
> - `blocklistIds` / category blocking is not applied on the router at all.
>   `render.lua` and `wifihaven-agent` do not fetch RPZ files or render
>   category rules. `PolicyService.decide` uses categories only on the
>   fallback `POST /api/router/decision` endpoint (#352).
> - `blockIpOnly` is carried in the snapshot but not yet enforced (#353).
> - `failureMode` lives on each `ProfilePolicy` rather than at the snapshot
>   top level — the DB column is per-profile and we keep it that way until
>   there's a reason to consolidate.

### 0.3 The snapshot is a minimal functional shape, not a policy model

`BlockRules` is the complete wire vocabulary for enforcement, and keeping it
that small is a deliberate design constraint, not an accident waiting to be
"fixed" by adding fields. The snapshot carries only the **functional** data the
router must act on; it is not a serialization of the server's richer policy
model. When you reach for a new field, stop and check whether an existing
functional field already expresses the behaviour.

**1. The snapshot is the minimal functional shape needed to enforce.** Every
field exists because the router has to act on it: drop all traffic
(`blocked`), drop a host (`extraBlocked`), allow a host (`extraAllowed`), drop a
category (`blocklistIds`), drop literal-IP traffic (`blockIpOnly`). If a new
policy concept can be expressed through one of these, it **MUST** be — adding a
new field for the concept itself is forbidden.

The motivating example: while shipping the global infra allowlist
([#1307](https://github.com/wifihaven/wifihaven/issues/1307)) — a curated set of
infrastructure hosts that must stay reachable so allowed-mode apps keep working
even when a whole MAC is blocked — an initial attempt added a top-level
`PolicySnapshot.infraAllow` set (and even carried the *reason* each host was
allowed) straight to the router. That was wrong: an always-allowed host is
functionally indistinguishable from any other `extraAllowed` entry, and the
router has no need for a separate "infra" channel or for *why* the host is
allowed. The correct fix resolves the infra hosts server-side and emits them as
ordinary always-reachable hosts, relying on the enforcement that already exists
for `extraAllowed`. The router learns nothing new. (They first shipped copied
into every profile's `extraAllowed`; #1321 then consolidated them — and the UI
hosts — into `global.extraAllowed` so the fleet-wide set is carried once. The
"no new field / no reason on the wire" lesson is unchanged either way.)

**2. No policy *reasons* on the wire except where functionally required.**
`blockReason: Option[MacBlockReason]` is the single intentional exception, and
it exists only to choose block-page copy — it is **never** read for
enforcement (see §0.2 and the type split that keeps router-only reasons out of
the snapshot). Do not add analogous "why" metadata for allow/deny decisions. The
server collapses the reasoning into functional data; the router applies that
data blind. A field whose only consumer is human-readable explanation does not
belong in the enforcement snapshot.

**3. Policy lives server-side, in `PolicyService`.** All composition — global
defaults, profile rules, per-device overrides, schedules, time limits, category
membership, app modes, and the infra allowlist — collapses server-side into the
per-MAC `BlockRules` described above. A new policy concept lands in
`PolicyService` and presents to the router as one of the existing functional
fields. The router never sees the tiers, the precedence order, or the inputs
that produced the result.

**4. Redundancy and wire-shape are separate concerns.** Copying a shared set
(like the infra allowlist) into every profile's `extraAllowed` duplicates data
across the snapshot, and that is an acceptable wire shape. The duplication is a
distinct optimization, tracked by the global-policy-layer work in
[#1308](https://github.com/wifihaven/wifihaven/issues/1308) (carry the global
set once, with an override model), and it must be solved **without** teaching the
router about policy tiers. Reducing bytes on the wire is never a reason to push
a policy concept onto the dumb applier.

This guardrail is recorded in
[#1311](https://github.com/wifihaven/wifihaven/issues/1311); the same principle
is summarized in the top-level [`AGENTS.md`](../AGENTS.md) architectural-model
section.

## 1. Why this exists

The original architecture ran a DNS server and a pcap-based traffic monitor on
the same Linux host as the API. That works only if the host sees every DNS
query and every flow — which it doesn't on a normal home LAN. Clients can:

- hard-code an upstream resolver (`1.1.1.1`, `8.8.8.8`) and bypass the local
  DNS server entirely;
- use DNS-over-HTTPS / DNS-over-TLS, which looks like ordinary HTTPS to the
  pcap monitor;
- talk peer-to-peer or to services that don't show up on the host's NIC.

Per-profile time accounting and per-device blocking are unenforceable from a
host that isn't on the data path. Moving enforcement to the **gateway** — a
router every device must traverse to reach the internet — fixes this.

## 2. Topology

```
                                                 +----------------------+
LAN devices ───┐                                 |  api server (Scala)  |
  (phones,     │   all egress traffic            |  ┌────────────────┐  |
   laptops,    │  ───────────────▶               |  │  Postgres      │  |
   TVs)        │                                 |  └────────────────┘  |
               ▼                                 |   • policy API       |
        +-------------------+   HTTP             |   • usage ingest API |
        |  gateway router   |◀──bearer token────▶|   • block-page UI    |
        |  (OpenWRT or      |                    |   • web admin UI     |
        |   OpnSense)       |                    +----------------------+
        |  • DNS server     |
        |  • packet filter  |
        |  • router agent   |
        +-------------------+
```

- **API server** is a single instance. All endpoints (admin, web, router) live
  under one HTTP port, distinguished by path prefix.
- **HTTP, not HTTPS**, for the router endpoints in the initial rollout. Switch
  to HTTPS / mTLS once the API server moves into the cloud.
- **Authentication** between router and API is a per-router bearer token
  obtained via a one-shot enrollment flow (§5.1).
- **The gateway can be any supported platform** — OpenWRT or OpnSense. Both
  speak the same wire protocol to the same API endpoints (§3).

## 3. Abstraction boundary

### 3.1 API contract (router-agnostic)

The HTTP contract is platform-neutral. Every agent speaks the same REST
protocol, regardless of router OS:

| Endpoint | Purpose | Status |
|----------|---------|--------|
| `POST /api/router/register` | Exchange enrollment token for bearer token | implemented |
| `GET /api/router/policy` | Pull enforcement snapshot | implemented |
| `GET /api/blocklists/<cat>.rpz` | Fetch RPZ blocklist | implemented |
| `POST /api/router/usage` | Push per-(mac, hostname) traffic records | implemented (#97) |
| `POST /api/router/events` | Push DHCP lease + DNS query events | implemented (#97) |
| `POST /api/router/decision` | Per-hostname fallback decision | pending #70 |
| `GET /blocked` | Public block page | pending #70 |

The policy snapshot JSON shape (§5.2), usage record shape (§5.4), and event
shape (§5.5) are identical across all agent platforms. An agent author only
needs to implement the HTTP calls and the platform-specific rendering logic.

### 3.2 Agent responsibilities (all platforms)

Every router agent must:

1. **Enroll** once: exchange a one-time enrollment token for a long-lived bearer token.
2. **Poll policy** every ~60 s, render the snapshot into the platform's native
   enforcement config, and reload atomically.
3. **Report usage** every ~60 s: scrape platform-native traffic counters,
   attribute bytes to `(mac, hostname)` pairs, POST to `/api/router/usage`.
4. **Stream events**: forward DHCP lease events and DNS query events to
   `/api/router/events`.

### 3.3 Platform divergence

| Concern | OpenWRT | OpnSense |
|---------|---------|----------|
| DNS server | dnsmasq | Unbound |
| Packet filter | nftables | pf / pfctl |
| Traffic accounting | nftables counters | pflog / pf tables |
| Per-MAC hostname attribution | dnsmasq `dhcp-host` tag + `--ipset=` callback | Unbound query log correlated to DHCP lease |
| Event sourcing | dnsmasq query log + `--dhcp-script` | Unbound query log + ISC dhcpd / Kea lease file |
| Package system | opkg / OpenWRT SDK | FreeBSD pkg / OPNsense plugin system |
| Config format | UCI | OPNsense XML API or conf files |
| Daemon supervisor | procd init script | rc.d service |
| Agent language | Lua | Python (TBD in #94) |

### 3.4 What is identical across platforms

- All HTTP calls target the same endpoints with the same JSON shapes.
- The policy snapshot (§5.2) is parsed identically; only the *rendering* step
  (snapshot → local config) is platform-specific.
- Usage POST body (§5.4): `active_seconds`, `bytes_in`, `bytes_out` per `(mac, hostname)` — same on all platforms.
- Events POST body (§5.5): `dhcp_lease` and `dns_query` types — same on all platforms.
- Bearer-token auth, ETag polling, and idempotency semantics are platform-neutral.

## 4. Component responsibilities

### Gateway router (any platform)

- DHCP + DNS for the LAN.
- Enforces filtering: drops or redirects blocked flows using the platform's packet filter.
- Accounts traffic per `(mac, hostname)`.
- Periodically pulls policy and pushes usage to the API.
- Serves a local block page that 302s to the API's `/blocked` URL for any
  blocked HTTP request.

### API server (this repo, `api/` module)

- Stores profiles, schedules, time limits, blocklists, devices, users,
  routers, traffic reports, block events.
- Exposes admin/web endpoints (existing) **and** router endpoints (§5).
- Renders the public `/blocked` page.
- Owns the policy decision logic (`PolicyService`).

### Components being deleted (issue #71)

- `dns/` — replaced by dnsmasq / Unbound on the router.
- `traffic/` — replaced by the agent's platform-native traffic accounting.

## 5. Decision model: policy snapshot, not per-flow round-trips

The router does **not** round-trip to the API on every connection, and it
does **not** make policy decisions locally. The split is:

1. Every ~60 s the agent pulls a **policy snapshot** in which `PolicyService`
   on the API server has already evaluated every schedule, time limit, pause
   state, and category for every device and **collapsed the result into the
   per-MAC `BlockRules` shape from §0.2**. The snapshot contains target
   state, not raw policy inputs.
2. The agent resolves device → `BlockRules` (a single profile-or-override
   lookup per device) and renders into nftables rules + a dnsmasq fragment
   used only for hostname attribution / ipset population. Both reload
   atomically.
3. Per-flow decisions happen in the kernel against nftables sets — no API
   call per request, no policy logic in the agent.
4. `POST /api/router/decision` exists as a **fallback** for the legacy
   server-side decision path, and is optional for v1.

Worst-case staleness is one poll interval (~60 s). That is acceptable for
parental-control use cases; an instant-block requirement can be met later by
a server-push channel (websocket, SSE) if needed.

The architectural invariant: **every new policy concept lands in
`PolicyService` and presents to the agent as one of the existing fields in
`BlockRules`**. Adding decision logic to the agent (schedule evaluation,
time accounting, category lookup, role-based defaults) is a bug.

**Per-app schedules ([#1376](https://github.com/wifihaven/wifihaven/issues/1376))
are an instance of this invariant, not an exception to it.** An app can carry
its own time window meaning "allowed during W" or "blocked during W" on top of
the profile's general schedule. This is a `PolicyService`-only feature with
**no wire or router change**: at snapshot-compute time the window is evaluated
(reusing `scheduleActiveAt`, household-local zone via the injected `Clock`) and
collapsed into the *existing* fields — `allowed_during` while active → the app's
hosts in `extraAllowed` (which beats Schedule/Paused/Manual whole-MAC blocks per
#421; whether it also beats the *daily time limit* is a server-side choice keyed
on the app's `exemptFromDaily` flag, not a router behaviour), `blocked_during`
while active → `extraBlocked`. The agent never learns that per-app schedules
exist; it still sees only the per-MAC `BlockRules`. Freshness rides the same pull-based path as profile schedules —
the next poll after a window edge recomputes against the current instant
(worst-case one poll interval). A precomputed boundary scheduler is needed only
if the future server-push channel above is built. Full design:
[`docs/design/per-app-schedules.md`](design/per-app-schedules.md).

**Hard pause ([#1418](https://github.com/wifihaven/wifihaven/issues/1418))
is the same pattern in reverse — a functional override of the carve-out, not
a new field.** Pause has two modes, stored per-profile in
`profiles.pause_mode` (`'soft'` | `'hard'`, default `'soft'`). *Soft* pause is
today's behaviour: the MAC drops except its per-profile `extraAllowed` hosts (an
allowed app survives, per #421/#1413). *Hard* pause is a true off-switch — when a
profile is paused **and** `pause_mode = 'hard'`,
`PolicyService.computeBlockRules` ships `blocked = true` with the per-profile
`extraAllowed` emptied of the app/exempt hosts. With an empty `ea_` set the
router drops the MAC unconditionally — no `ip daddr != @ea_…` exception — so it
never learns "hard vs soft"; `blockReason` stays `Paused` (block-page copy
only). No wire/router change. The fleet-wide always-reachable set
(`global.extraAllowed` — deployment UI / block-page hosts **and** the curated
#1307 infra allowlist, consolidated there in #1321) is the top of the router's
precedence ladder and survives a hard pause, so the block page and admin SPA
still load on the household LAN and pure infra probes stay reachable; a hard
pause remains a true off-switch for every *per-profile* carve-out.

## 6. Router HTTP API

All endpoints below are served by the existing API process under
`/api/router/*`. Bearer-token auth on every request except `/register`
(one-time enrollment token) and the public `/blocked` page.

JSON field names use camelCase (ZIO JSON default — no snake_case transform).

### 6.1 `POST /api/router/register`

Boot-time enrollment. The user creates a router record in the admin UI; the
API returns a one-time enrollment token they paste into the agent's config.
The agent calls this endpoint once to exchange the enrollment token for a
long-lived router token.

**Request**

```json
{
  "enrollmentToken": "et_5f3c9b...",
  "routerName": "home-gw",
  "platformVersion": "23.05.3",
  "agentVersion": "0.1.0"
}
```

`platformVersion` carries the router OS version (e.g. OpenWRT `23.05.3`,
OPNsense `24.1`). `routerName` overrides the name set in the admin UI (optional).

**Response 200**

```json
{
  "routerId": "9c1f2e8a-...",
  "routerToken": "rt_a7d12b..."
}
```

**Errors**: `401` on invalid/used enrollment token.

The enrollment token is single-use; the API marks it consumed on success.

### 6.2 `GET /api/router/policy?since=<etag>`

Polled every ~60 s. Returns the full enforcement snapshot, or `304 Not
Modified` if the client's ETag still matches.

**Response 200** (matches §0.2, as of #354):

```json
{
  "etag": "sha256:abc123...",
  "generatedAt": "2026-05-02T14:00:00Z",
  "global": {
    "blocked": false,
    "blockReason": null,
    "extraBlocked": [],
    "extraAllowed": ["connectivitycheck.gstatic.com", "ocsp.digicert.com", "wifihaven.local"],
    "blocklistIds": [],
    "blockIpOnly": false
  },
  "devices": {
    "aa:bb:cc:11:22:33": {
      "profileId": 3,
      "name": "kid-ipad",
      "rules": null
    },
    "aa:bb:cc:44:55:66": {
      "profileId": 3,
      "name": "kid-phone",
      "rules": {
        "blocked": true,
        "blockReason": "TimeLimit",
        "extraBlocked": [],
        "extraAllowed": ["khanacademy.org"],
        "blocklistIds": ["ads", "adult"],
        "blockIpOnly": true
      }
    }
  },
  "profiles": {
    "3": {
      "name": "kids",
      "rules": {
        "blocked": false,
        "blockReason": null,
        "extraBlocked": ["tiktok.com"],
        "extraAllowed": ["khanacademy.org"],
        "blocklistIds": ["ads", "adult"],
        "blockIpOnly": true
      },
      "failureMode": "block-all"
    }
  },
  "blocklists": {
    "ads":   { "version": "2026-04-29", "url": "/api/blocklists/ads.rpz" },
    "adult": { "version": "2026-04-29", "url": "/api/blocklists/adult.rpz" }
  }
}
```

**Response 304** when `If-None-Match: <etag>` (or `?since=<etag>`) matches
the current snapshot. Body is empty.

ETag is computed deterministically over snapshot content.

Notes:

- The `global` section is a `BlockRules` carried once and applied to every MAC
  (§0.3). Its `extraAllowed` holds the always-reachable hosts (UI, block page,
  connectivity, PKI) — **not** copied into each profile's `extraAllowed`;
  changing them rewrites only `global` and the snapshot `etag`.
- The first device above takes its rules from the `"kids"` profile.
- The second device carries an inline `rules` that the router uses verbatim
  (replace, not merge). **This is the wire mechanism only** — today the sole
  populator of an inline `rules` is the server-side unmanaged-MAC block path.
  There is no per-device override authoring surface, and we are not adding one
  (see the "Scope decision (2026-06)" callout in §0.2 above). Read this example
  as "the router can apply a pre-resolved per-MAC rule," not "operators author
  per-device overrides."
- The router resolves each device into a single `BlockRules` once and then
  enforces purely per-MAC. Profiles are not consulted further.
- All schedule / time-limit / pause / category evaluation has already
  happened server-side in `PolicyService` and is reflected in
  `blocked` / `blockReason` / `extraBlocked` / `blocklistIds`.

> **Implementation status (post-#354).** The snapshot wire format now
> matches the target shape above. Server-side evaluation of pause /
> schedule / time-limit collapses into per-profile `BlockRules.blocked`;
> the agent never sees raw schedules or daily-minute counters. Three
> consumer fixes are still in flight to fully realise §0.2 enforcement:
> #351 (per-MAC nft drop for `extraBlocked` replacing the legacy
> `address=/host/#` NXDOMAIN), #352 (per-blocklist ipset drop for
> `blocklistIds`), and #353 (`blockIpOnly` enforcement). Until they land
> the agent still uses dnsmasq NXDOMAIN for `extraBlocked` and ignores
> `blocklistIds` / `blockIpOnly` — but the snapshot ships them, so each
> consumer fix can light up cleanly.

### 6.3 `GET /api/blocklists/<category>.rpz`

Returns an RPZ-formatted blocklist for the named category. Versioned and
ETagged. The agent caches by `version` from the policy snapshot and only
refetches when the version changes.

### 6.4 `POST /api/router/usage`

Sent every 60 s (configurable via `usage_report_interval`). Idempotent on
`(routerId, periodStart, mac, host.type, host.value)` so retries are safe.

**Request**

```json
{
  "routerId": "9c1f2e8a-...",
  "periodStart": "2026-05-02T14:00:00Z",
  "periodEnd":   "2026-05-02T14:01:00Z",
  "records": [
    {
      "mac": "aa:bb:cc:11:22:33",
      "ip":  "192.168.1.42",
      "host": { "type": "fqdn", "value": "youtube.com" },
      "activeSeconds": 50,
      "bytesIn":  38123412,
      "bytesOut": 921000
    },
    {
      "mac": "aa:bb:cc:11:22:33",
      "ip":  "192.168.1.42",
      "host": { "type": "ipv4", "value": "1.2.3.4" },
      "activeSeconds": 30,
      "bytesIn":  120000,
      "bytesOut": 4000
    }
  ]
}
```

`host` is a tagged union (`HostId`, #391): `{ "type": "fqdn", ... }` when
the agent has a forward-DNS attribution for the flow,
`{ "type": "ipv4" | "ipv6", ... }` when it doesn't (direct-IP traffic, DoH-
resolved domain, Apple Private Relay, or any other case where the dnsmasq
attribution sidecar lost the race). IP-typed rows are stored alongside FQDN
rows but are systematically excluded from FQDN pattern matching (site-limit
patterns like `*.example.com`) — an IP literal can never match a hostname
pattern, so counting it against one would be a silent correctness bug. See
§7.2 and §8.2 for the platform-specific attribution path, and
[docs/ops/hostname-attribution.md](ops/hostname-attribution.md) for the
known buckets of bare-IP events operators encounter in the field.

**Response 200**: empty body.

Server actions: insert into `traffic_reports`, increment `time_usage`, update
`devices.last_seen_ip` / `last_seen_at`.

### 6.5 `POST /api/router/events`

Out-of-band events (DHCP leases, DNS query log lines). Used to populate the
unknown-device list and feed device autodetection.

**Request**

```json
{
  "routerId": "9c1f2e8a-...",
  "events": [
    { "type": "dhcp_lease",
      "mac": "aa:bb:cc:11:22:33", "ip": "192.168.1.42",
      "hostname": "kid-ipad", "ts": "2026-05-02T14:01:13Z" },
    { "type": "connection_attempt",
      "mac": "aa:bb:cc:11:22:33",
      "host":    { "type": "fqdn", "value": "youtube.com" },
      "destIp":  "142.250.65.78",
      "allowed": true, "reason": "allow",
      "ts": "2026-05-02T14:01:14Z" }
  ]
}
```

`type` values (`dhcp_lease`, `dns_query`) are the same regardless of platform.

**Response 200**: empty body.

### 6.6 `POST /api/router/decision`  *(optional fallback, pending #70)*

For hostnames not in the most recent snapshot. This endpoint is FQDN-only
by design: the request carries `hostname: Hostname` (a resolved name from
the agent's dns-tail cache, §7.2), never an IP literal. Direct-IP flows
are handled by nftables enforcement, not by a per-flow API call.

**Request**: `{ "mac": "aa:bb:...", "hostname": "..." }`

**Response 200**: `{ "allow": false, "reason": "category:adult", "expiresAt": "..." }`

### 6.7 `GET /blocked`  *(public, no auth, pending #70)*

Query params: `mac`, `host`, `reason`. Renders a page showing why the request
was blocked and offering a "request extension" button gated on parent login.
This is the URL the router's local block page redirects to.

## 7. OpenWRT agent design

This section describes the OpenWRT-specific rendering layer. It is implementation
guidance, not part of the wire contract.

### 7.1 Capabilities used

- **Per-MAC nftables sets** — `ether saddr @blocked_macs` matches packets
  from any MAC in the named set. Used to express `blocked = true` from the
  resolved `BlockRules` (§0.2) and to scope per-MAC drop / accept chains.
- **Per-(MAC, host) IP sets via dnsmasq `--ipset=`** — dnsmasq populates
  named nftables sets with the IPs a hostname resolves to *for a given
  client*. nftables matches destination IP against the set, scoped by
  source MAC. This is how `extraBlocked` / `extraAllowed` / `blocklistIds`
  are enforced without DNS-layer blocking: the host resolves normally, but
  the forward rule for that MAC drops the resulting flow.
- **Per-MAC dnsmasq tagging** — `dhcp-host=...,set:mac<N>` applies a
  per-MAC tag so `--ipset=` callbacks can populate the right per-MAC ipset
  for hostname attribution. Dnsmasq tags are **not** used for `address=` /
  `server=` differentiation — there is no per-tag NXDOMAIN. dnsmasq always
  resolves normally; enforcement happens in nftables.
- **nftables counter objects** keyed on `ether saddr . ip daddr` for per-MAC,
  per-IP byte counts.
- **dnsmasq query log** (`--log-queries=extra`, written to a private file at
  `/tmp/wifihaven-dnsmasq.log`) is the primary source for `dst_ip → hostname`
  attribution on every connection event. A sidecar process
  (`wifihaven-dns-tail`) tails the file, parses `query[A] <name>` and
  `reply <name> is <ip>` lines into a TTL-bounded cache, and writes a snapshot
  to `/tmp/wifihaven-dns-cache.txt`. The main agent reads the snapshot when a
  new conntrack flow arrives.
- **uhttpd** on a loopback port serves the local block page; nftables `dnat`
  redirects blocked HTTP/80 to it.

### 7.2 Forward-lookup hostnames, not reverse DNS

Reverse DNS of a destination IP often returns generic CDN PTRs
(`lb-13.akamai.net`) unrelated to the user's intent. Instead, dns-tail
captures dnsmasq's forward-lookup answers *at resolution time* and tracks
the original queried name across CNAME chains via the dnsmasq query id —
so a flow to `142.250.x.x` shortly after `query[A] youtube.com` is logged as
`youtube.com`, not `youtube-ui.l.google.com` (the last CNAME hop) and not
the IP literal.

Some clients (notably Apple devices) don't stop at one query: after resolving
the branded host they **re-query the final CNAME target directly** as a
separate lookup. On that second query the qname on the wire *is* the CDN
target (e.g. `query[A] prod.khan.map.fastly.net`), so the query-id correlation
— which is working correctly — still attributes the flow to the CDN target,
which then fails to suffix-match the app's branded `extraAllowed` entry
(`kastatic.org`) and is mislabelled / mis-classified as blocked. To recover the
brand, `dns_log` remembers a bounded **CNAME-target → chain-head** map built
from the chains it already parses (every non-head owner name in a chain maps to
that chain's queried head). A later direct query for a known target is then
attributed back to the branded ancestor the target was first observed under.
This is best-effort: the very first direct-target query seen before any branded
chain falls back to the target name (never worse than the IP-literal default),
and stale alias edges expire on the same TTL as cache entries (#1344).

The per-domain `--ipset=` mechanism is still used for the `site_limits`
enforcement chains (nftables matches `ip daddr @profileN_<domain>`), but it
is no longer load-bearing for hostname attribution in the query log — that
moved to dns-tail in #259, which fixed the regression where every log entry
displayed a raw IP because the ipset table was only ever populated for the
handful of site-limit domains.

Connection attempts whose destination IP isn't in the dns-tail cache (e.g.
direct-IP traffic, DoH-resolved domains, agent restart racing a flow) fall
back to the IP literal as an `ipv4`-tagged or `ipv6`-tagged `HostId`
(§6.4, #391). The agent emits `{ "type": "ipv4", "value": "<dst_ip>" }`
rather than smuggling an IP into a hostname field, so the type system
prevents accidental pattern matches against `*.example.com` and the admin
UI can render direct-IP rows distinguishably from named hosts.

### 7.3 Package layout

```
openwrt/
├── Makefile                            # opkg metadata, builds via OpenWRT SDK
├── files/
│   ├── etc/init.d/wifihaven            # procd init script
│   ├── etc/config/wifihaven            # UCI: api_url, router_token, poll_interval
│   ├── usr/sbin/wifihaven-agent        # main daemon (Lua)
│   ├── usr/sbin/wifihaven-dns-tail     # dnsmasq query-log tailer (sidecar)
│   ├── usr/lib/lua/wifihaven/policy.lua    # snapshot fetcher, atomic apply
│   ├── usr/lib/lua/wifihaven/usage.lua     # nftables counter scraper, reporter
│   ├── usr/lib/lua/wifihaven/dns_log.lua   # forward-lookup hostname cache (#259)
│   ├── usr/lib/lua/wifihaven/render.lua    # writes dnsmasq + nft fragments
│   └── www/wifihaven/block.html        # local block page → 302 to api /blocked
└── README.md
```

### 7.4 Daemon loop (three timers)

- **Policy timer (60 s)** — fetch snapshot, atomically rewrite
  `/tmp/dnsmasq.d/wifihaven.conf` and `/tmp/nftables.d/wifihaven.nft`,
  then `nft -f` the new ruleset. The dnsmasq full restart only fires when
  the rendered `dnsmasq.conf` actually differs byte-for-byte from the
  on-disk copy (#414) — most policy applies flip nft-side state only
  (blocked_macs membership from schedule / pause / time-limit transitions)
  and don't require a DNS service blip. When the dnsmasq fragment does
  change (new extraBlocked host, device profile reassignment, blocklist
  membership), a full `/etc/init.d/dnsmasq restart` is required: SIGHUP
  does not re-read `conf-dir` files, so `reload` would leave new
  `dhcp-host=` / `nftset=` directives silently inactive (#328). On `304`:
  do nothing.
- **Usage timer (60 s)** — scrape nftables counters, correlate with dnsmasq
  query log + DHCP leases, POST to `/api/router/usage`. On 200, reset counters;
  on failure, retain and retry (endpoint is idempotent).
- **Event watcher** — dnsmasq `--dhcp-script` hook for DHCP events; log tail
  for query events; batched to `/api/router/events`.

### 7.5 Time-limit enforcement

`PolicyService` on the API server tracks `time_used_today`, daily limits,
extensions, and per-site limits. When a daily limit is exhausted, the API
emits the affected MAC with `blocked = true, blockReason = TimeLimit` in
the next snapshot. When a per-site limit is exhausted, the relevant host
is added to that MAC's `extraBlocked`. **The agent does no time arithmetic.**

Per-MAC usage is reported to the API every 60 s via
`POST /api/router/usage` (§6.4); the API accumulates and decides. Worst-case
overshoot is one usage-report interval (~60 s) plus one policy-poll interval
(~60 s).

### 7.6 Block-page redirect

nftables `dnat` on TCP/80 to `127.0.0.1:8081` (uhttpd). A tiny CGI script
reads the original destination from conntrack and 302s to
`http://<api>/blocked?mac=…&host=…&reason=…`. Blocked HTTPS times out (no
MITM without a CA install — same behavior as commercial parental-control boxes).

## 8. OpnSense agent design  *(pending #94)*

This section describes the OpnSense-specific rendering layer. The wire protocol
(§6) is unchanged; only the platform primitives differ.

### 8.1 Capabilities used

- **Unbound DNS** for per-client DNS overrides via `local-data` directives or
  Unbound's access-control + RPZ support.
- **pf firewall** for per-MAC blocking via `<table>` entries and anchor rules
  (`pass/block from <table>`).
- **pflog / pf counters** for traffic accounting — pflog captures firewall
  decisions with source MAC and destination; pf per-rule byte counters provide
  aggregate stats.
- **DHCP server** (ISC dhcpd or Kea, depending on OPNsense version) for
  MAC→IP mapping; lease file or commit script provides events.
- **OPNsense plugin system** for packaging and rc.d integration.

### 8.2 Forward-lookup hostnames on OpnSense

Unbound's query log provides `(client IP, qname)`. The agent correlates the
client IP to a MAC via the DHCP lease table to get `(mac, hostname)` — the
same attribution the OpenWRT agent derives from dnsmasq. Connections that
bypass Unbound get attributed to `unknown`.

### 8.3 Package layout  *(TBD in #94)*

```
opnsense/
├── pkg-descr
├── Makefile
├── files/
│   ├── usr/local/etc/rc.d/wifihaven     # rc.d service
│   ├── usr/local/etc/wifihaven.conf     # api_url, router_token, poll_interval
│   └── usr/local/sbin/wifihaven-agent   # main daemon (Python)
└── README.md
```

### 8.4 Daemon loop

Same three-timer structure as the OpenWRT agent (§7.4), with platform-specific
rendering:
- **Policy timer**: renders Unbound `local-data` overrides and pf table entries,
  reloads both atomically via `unbound-control reload` and `pfctl -T replace`.
- **Usage timer**: reads pf per-rule counters or parses pflog, attributes bytes
  via Unbound query log, POSTs to `/api/router/usage`.
- **Event watcher**: tails dhcpd lease file and Unbound query log, batches to
  `/api/router/events`.

### 8.5 Block-page redirect

pf `rdr` rule redirects TCP/80 for blocked MACs to a local web server (nginx or
lighttpd). The block page 302s to the API's `/blocked` URL. Blocked HTTPS times
out (same limitation as OpenWRT, same rationale).

## 9. Schema

New tables (landed in #67):

- `routers` — registered gateways and their tokens.
- `traffic_reports` — raw audit log of usage POSTs.
- `block_events` — record of decisions returned by `/decision` and redirects
  served by `/blocked`.

New columns (landed in #67):

- `time_usage.bytes_in bigint default 0`
- `time_usage.bytes_out bigint default 0`

No schema changes are needed to support OpnSense — the tables are platform-neutral.
The `query_logs` table is fed by `/api/router/events` (replacing the deleted
DNS server as the source).

## 10. Rollout sequence

| Issue | Description | Status |
|-------|-------------|--------|
| #67 | V2 migration + repos | done |
| #68 | `GET /api/router/policy`, blocklists, enrollment, admin UI | done (#98) |
| #69 | `POST /api/router/usage` + `POST /api/router/events` | done (#97) |
| #70 | `POST /api/router/decision` + public `/blocked` page | pending |
| #71 | Delete `dns/` and `traffic/` modules | done (#125) |
| #72 | OpenWRT agent (Lua, opkg) | pending |
| #73 | e2e fake-router in staging compose | pending |
| #88 | OpenWRT deploy plan (`openwrt/README.md`, `openwrt/build-ipk.sh`, CI `.ipk` build) | pending |
| #89 | Cloud deploy plan | pending |
| #93 | (see issue) | pending |
| #94 | OpnSense agent (Python, OPNsense plugin) | pending |

Steps #67–#70 land before #71: the old enforcement stack is not deleted until
the new API surface is addressable.
