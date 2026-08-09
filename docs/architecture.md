# Router-based enforcement architecture

Status: **implemented for OpenWRT; OPNsense partial.** (Refreshed 2026-07 — #2088.)
- §5.1–§5.6 and §9 schema in production (issues #67, #68/#98, #69/#97, #70).
- OpenWRT agent (#72) has been in production for over a month.
- OPNsense agent event-emission (#94) is done, but its deploy plan (#89) is
  still open — OPNsense is not yet a supported production target.

> **Read this first.** The "Enforcement model" section below is the canonical
> architecture. Some current code deviates from it (called out inline and in
> follow-up issues); the model is the target. If you are writing a new
> feature, follow the model.

## 0. Enforcement model

Two truths govern everything that follows.

> For the user-facing consequences of this model — why a freshly-enabled block
> warms up rather than dropping instantly, what HTTPS blocks look like, and
> which device settings bypass filtering — see
> [`docs/enforcement-expectations.md`](enforcement-expectations.md).

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

#### The one sanctioned exception: `blockEncryptedDns` NXDOMAIN signaling (#1911)

There is exactly **one** place where the agent returns a negative DNS answer,
and it is a deliberate, narrow exception — not a softening of the rule above.
The additive top-level snapshot flag **`blockEncryptedDns`** (a network-wide
household toggle; feature [#1909](https://github.com/wifihaven/wifihaven/issues/1909),
epic [#1903](https://github.com/wifihaven/wifihaven/issues/1903)) enables the
"block encrypted DNS & relays" behaviour. When it is `true` the agent enforces
**two separable halves**:

1. **NXDOMAIN half (dnsmasq).** A *negative* DNS answer (`local=/<host>/` →
   **NXDOMAIN**) for a small curated list of relay / DoH hostnames baked into the
   agent (`mask.icloud.com`, `mask-h2.icloud.com`, `cloudflare-dns.com`,
   `dns.google`, `dns.quad9.net`, NextDNS, AdGuard, OpenDNS). This is the
   exception. It exists **only because Apple's documented way to disable iCloud
   Private Relay network-wide is a negative answer** for the relay hostnames:
   iOS then turns Private Relay *off* and falls back to direct, filterable
   connections. A connection-layer drop of the relay ingress does **not**
   disable it — Apple explicitly warns that silently dropping Private Relay
   packets causes client hangs (the [#1891](https://github.com/wifihaven/wifihaven/issues/1891)
   symptom), and a `0.0.0.0` sinkhole leaves it "on but failing" rather than
   cleanly off. So this DNS answer is **bypass-disable *signaling*, not
   enforcement** — its purpose is to make the device stop tunneling so that the
   real (connection-layer) enforcement can see and filter the traffic. It is
   network-wide because stock dnsmasq cannot answer DNS per-client.

2. **Connection-layer half (nftables).** The actual enforcement, in the
   standard plane: a dedicated `wifihaven_encrypted_dns` forward chain that
   drops **DoT (TCP/853 to any IP)** and **DNS port 53 (UDP+TCP) to a curated
   set of public-resolver IPs** (`1.1.1.1`, `8.8.8.8`, `9.9.9.9`, … +v6).
   Deliberately **port-scoped to :53** — `:443` and ICMP to those IPs are left
   intact because `1.1.1.1`/`8.8.8.8` double as ubiquitous "am I online?"
   connectivity-check targets, and a blanket drop makes such devices believe
   they are permanently offline (the [#1909](https://github.com/wifihaven/wifihaven/issues/1909)
   port-scope refinement). DoH pinned to a raw resolver IP (`:443`) is the only
   bypass this leaves open; it is rare and handled per-device if it appears.

Both halves are gated on the single `blockEncryptedDns` flag. **Absent/false on
the wire renders byte-identically to a snapshot without the feature, and an
un-updated agent ignores the unknown field entirely** — that back-compat
property is why `PolicySnapshot.blockEncryptedDns` decodes to `false` and must
keep doing so.

Since [#2643](https://github.com/wifihaven/wifihaven/issues/2643) a **NEW**
household starts with the toggle **ON**: a device that tunnels around the LAN
resolver bypasses all hostname attribution and everything that depends on it —
site and category blocking, and per-app limits (though *not* a daily time limit,
which is a whole-MAC forward-drop that never consults DNS) — and it does so
silently, since the dashboard still renders and just shows raw IPs. Existing
households were **not** backfilled (flipping a live network's DNS behaviour can
break devices that depend on DoH, so it is the operator's call, per household).
The new-household default lives in exactly one place,
`HouseholdSettings.DefaultBlockEncryptedDns`, and every creation path
(`HouseholdSeed.insertHousehold`, `HouseholdSettingsRepoLive.ensureDefault`)
names the column explicitly from it. V61's `block_encrypted_dns … DEFAULT FALSE`
column default is unchanged and means something different: the value a row gets
when written by code that does not name the column, which since #2643 means
image-(N-1) back-compat and nothing else. The boot backfill that repairs
pre-existing households used to rely on it too, and now stamps `FALSE`
explicitly, so a future change to the column default cannot reach an existing
household.

The curated lists live baked into the agent
(`openwrt/files/usr/lib/lua/wifihaven/encrypted_dns.lua`), keeping the wire to a
single boolean. This is the *only* DNS-negative-answer path in the system;
every other block remains a connection-layer drop.

> **Interaction note.** The NXDOMAIN half wins over any allow carve-out for the
> *same* curated hostname: `local=/<host>/` short-circuits resolution, so if a
> profile's `extraAllowed` (or `global.extraAllowed`) happens to name one of the
> curated DoH/relay hosts, its `ea_`/`@global_allow` ipset never populates (no
> resolved IP to add) and the host stays unreachable. This is intended — the
> toggle is a deliberately heavy-handed *network-wide* control, not a per-profile
> one — but it means an allow entry for a curated host is silently inert while
> the toggle is on.

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
    blockEncryptedDns: Boolean,                // #1912/#1909: network-wide "block encrypted
                                               //   DNS & relays" toggle, resolved from
                                               //   household_settings.block_encrypted_dns.
                                               //   When true the agent (#1911) forces devices
                                               //   onto the LAN resolver: NXDOMAIN for the
                                               //   curated relay/DoH hostnames (iCloud Private
                                               //   Relay, public DoH) + nftables drops for
                                               //   hardcoded resolver IPs and DoT/853. Carries
                                               //   ONLY the boolean — the curated host/IP
                                               //   lists are baked in the agent, never shipped
                                               //   on the wire. Deliberate, narrow exception to
                                               //   Architectural Truth #1 ("DNS always
                                               //   resolves") — bypass-disablement signaling is
                                               //   itself enforcement-enabling. Default false.
                                               //   Enforcement detail in §0.1.
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
  case object Manual      extends MacBlockReason   // reserved — no producer; see #2087
  case object Unmanaged   extends MacBlockReason   // no profile assignment under `block` policy (#1122)
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

> **Shared-pool collateral — `extraBlocked` host-sets must own their IPs.**
> Because `extraBlocked` drops on the resolved `dst_ip`, a blocked host whose
> IPs are *shared* with traffic we never meant to block drags that other
> traffic into the drop. This was the root cause of
> [#1636](https://github.com/wifihaven/wifihaven/issues/1636): kids' iPads
> couldn't sign into Google Drive when YouTube was blocked because
> `youtubei.googleapis.com` resolves to the same Google GFE anycast pool as
> Drive / OAuth / Calendar, so the YouTube `eb_` rule dropped every Google
> product API on that pool. The fix is an **authoring** rule, not a new
> enforcement mechanism: prefer the app's **dedicated-bytes** host
> (`googlevideo.com` for YouTube, `nflxvideo.net` for Netflix) — that's where
> the attention-heavy traffic lives and it carries no collateral — and keep
> shared vendor-API / multi-tenant-CDN hosts out of block host-sets. The
> [#1648](https://github.com/wifihaven/wifihaven/issues/1648) audit split
> shared-pool hosts into **Class 1** (vendor-API anycast pools like Google GFE
> `*.googleapis.com` — collision near-certain, strip; only
> `youtubei.googleapis.com` qualified, being removed in Phase 0
> [#1660](https://github.com/wifihaven/wifihaven/issues/1660)) and **Class 2**
> (branded per-app CDN edges like `discordapp.net`, `rbxcdn.com`,
> `tiktokcdn.com`, `sc-cdn.net`, `jtvnw.net` / `ttvnw.net`, `kastatic.org` —
> latent risk only, **do not strip**, watch-list for the deferred Phase 3
> evidence loop [#1663](https://github.com/wifihaven/wifihaven/issues/1663)).
> The full authoring rule and worked example (`imessage.yml`) live next to the
> templates in
> [`api/resources/app_templates/_README.yml`](../api/resources/app_templates/_README.yml).

> **Cache-warmth gap on `blocklistIds` — bounded by client TTL.** The
> `bl_<id>` / `bl6_<id>` ipsets are populated at **client-DNS-resolution
> time** via dnsmasq's `nftset=` callback (rendered in
> `openwrt/files/usr/lib/lua/wifihaven/blocklists.lua`; see also the
> comment at `openwrt/files/usr/lib/lua/wifihaven/render.lua:64`) — the
> snapshot ships category *membership*, not pre-resolved IPs, and the
> agent does not pre-seed the sets at boot. The consequence: when a
> category list is newly applied to a `(mac, host)` pair AND the client
> already holds a fresh OS / browser DNS cache entry for `host`,
> enforcement for that flow lags until the client's TTL expires and it
> re-queries — the router has no way to invalidate the *client's* DNS
> cache. The window is bounded by that stored TTL: typically ≤ 5 min for
> ad / tracker workloads (median TTL on programmatic-ad apexes sits in
> the 60–300 s range), with outliers on high-TTL consumer-brand apexes
> (social / gambling) where the client cache is hot regardless of
> policy. Scope: this applies to a category becoming newly active for a
> `(mac, host)` pair; brand-new hosts the client has never resolved
> through this router, and blocklist-URL refreshes that ship new members
> the client had no prior reason to cache, see a ≈ zero gap because the
> first lookup populates `bl_<id>` before the connect. Accepted as a
> bounded property of the resolve-time-population mechanism per
> [#1786](https://github.com/wifihaven/wifihaven/issues/1786).

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

> **May-2026 deviations from this model — all resolved as of this refresh
> (2026-07, #2088).** At the time this doc was first written, three gaps were
> tracked as follow-ups; all three have since landed and are retired here
> (see the "Implementation status" callout in §3.4 for the fuller picture):
>
> - `extraBlocked` was IPv4-only; the v6 sibling ipset (`eb6_<host>`) now
>   exists alongside `eb_<host>` (`render.lua:213-220`, #392).
> - Category blocklist enforcement now renders `bl_`/`bl6_` nftables sets on
>   the router (`render.lua`, #352) — no longer fallback-only via
>   `PolicyService.decide`.
> - `blockIpOnly` is enforced via per-MAC `resolved_`/`resolved6_` nftables
>   sets (`render.lua:241`, #353).
>
> `failureMode` still lives on each `ProfilePolicy` rather than at the
> snapshot top level — the DB column is per-profile and we keep it that way
> until there's a reason to consolidate. This one remains current, not a
> deviation.

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
| `GET /api/blocklists/<id>` | Fetch plain hostname-list blocklist (ETag-cached) — **not RPZ** | implemented |
| `POST /api/router/usage` | Push per-(mac, hostname) traffic records | implemented (#97) |
| `POST /api/router/events` | Push DHCP lease + DNS query events | implemented (#97) |
| `POST /api/router/decision` | Per-hostname fallback decision | implemented (#70) |
| `GET /blocked` | Public block page | implemented (#70) |

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
hosts in `extraAllowed` (which beats Schedule/Paused/TimeLimit whole-MAC blocks per
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
    "ads":   { "version": "2026-04-29", "url": "/api/blocklists/ads" },
    "adult": { "version": "2026-04-29", "url": "/api/blocklists/adult" }
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

> **Implementation status (post-#354, refreshed 2026-07 #2088).** The
> snapshot wire format matches the target shape above. Server-side
> evaluation of pause / schedule / time-limit collapses into per-profile
> `BlockRules.blocked`; the agent never sees raw schedules or daily-minute
> counters. The three consumer fixes originally tracked here have all
> landed: #351 (per-MAC nft drop for `extraBlocked`, replacing the legacy
> `address=/host/#` NXDOMAIN), #352 (per-blocklist ipset drop for
> `blocklistIds`), and #353 (`blockIpOnly` enforcement). §0.2 enforcement is
> fully realised — the agent no longer uses dnsmasq NXDOMAIN for
> `extraBlocked` and no longer ignores `blocklistIds` / `blockIpOnly`.

### 6.3 `GET /api/blocklists/<id>`

Returns a plain-text, newline-separated hostname list for the named
blocklist id — **not RPZ format**; RPZ (a DNS-enforcement mechanism) never
shipped, per Truth #1 (`PolicyService.renderBlocklist`,
`api/src/policy/PolicyService.scala:551-565`). Versioned and ETagged. The
agent caches by `version` from the policy snapshot and only refetches when
the version changes, resolving member hosts into the `bl_`/`bl6_` nftables
ipsets at DNS time.

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

### 6.6 `POST /api/router/decision`  *(optional fallback, implemented — #70)*

For hostnames not in the most recent snapshot. This endpoint is FQDN-only
by design: the request carries `hostname: Hostname` (a resolved name from
the agent's dns-tail cache, §7.2), never an IP literal. Direct-IP flows
are handled by nftables enforcement, not by a per-flow API call.

**Request**: `{ "mac": "aa:bb:...", "hostname": "..." }`

**Response 200**: `{ "allow": false, "reason": "category:adult", "expiresAt": "..." }`

### 6.7 `GET /blocked`  *(public, no auth, implemented — #70)*

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

#### Static IP-range labels (#1655 / #1708)

Before falling through to the IP literal, the agent consults a small,
in-repo **static IP-range → label map**
(`openwrt/files/usr/lib/lua/wifihaven/static_ip_labels.lua`) as a
last-resort attribution source. Some traffic never presents an SNI *and*
never resolves via dnsmasq — notably Apple push (APNs) on `17.0.0.0/8`,
and apps that bypass the local resolver and hit public resolvers
(`8.8.8.8`, `1.1.1.1`) directly. With the map, those flows show up in
connection_events as `apple-push` / `google-dns` / `cloudflare-dns`
instead of a bare IP.

**Precedence in `handle_flow`:**

1. `attribute_hostname` — dnsmasq query-log cache (the SNI/QUIC sidecars
   also feed this primitive via `cache.insert_sni`, so SNI- and
   QUIC-derived hostnames flow through the same path — #573, #1651).
2. `ipset_lookup_hostname` — legacy `nft_sets` fallback for site_limits
   domains.
3. `static_ip_labels.lookup` — last-resort static map (this section).

A real attribution always beats a static guess.

**LABELS ONLY — never an enforcement input.** The static map participates
in zero drop or carve-out predicates: enforcement is the per-MAC
`BlockRules` / nftables pipeline (see §0.1 / §0.2). Promoting an entry to
an enforcement input requires explicit operator approval and a tracking
issue. The header comment in `static_ip_labels.lua` repeats this.

**Wire shape.** Static-map attributions ride the wire as the
`HostId.Label` variant (#1708): `{ "type": "label", "value": "<label>",
"source": "static-ip-range" }`. The `source` string is threaded through
`conntrack.build_event` from `static_ip_labels.lookup` so the wire shape
is assembled in exactly one place. Downstream `HostMatch.matchesAny`
returns `false` for label-typed hosts, so a synthetic label can never
pattern-match against a real apex — an operator who configures an app
whose host-set happens to include the literal `apple-push` will *not*
see APNs flows attribute into that app. Distinguishing labels from real
FQDNs at the wire level was the whole point of #1708; this section
replaces the earlier "currently emitted as fqdn" caveat.

**Extending the map.** Add a `{ cidr, label }` row to `M._ranges` with a
citation in the comment beside it, and a test in
`openwrt/test/static_ip_labels_spec.lua`. Keep growth operator-curated:
entries should cover ranges with an unambiguous, well-known owner that
prod evidence shows dominating the unattributed-IP tail.

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
extensions, and **per-app limits**. When a daily limit is exhausted, the API
emits the affected MAC with `blocked = true, blockReason = TimeLimit` in
the next snapshot. When a per-app limit is exhausted, the relevant hosts
are added to that MAC's `extraBlocked`. **The agent does no time arithmetic.**

> **The model is app-focused, not site-focused.** Usage and time limits are
> framed around **apps**, not individual sites/domains. The limit is scoped to
> an **app's whole host-set, aggregated as one budget** (#1505), not to each
> host independently. A time-limited app's usage is the union of presence
> across **all** its `app_hosts` — apex plus any off-domain asset/CDN hosts —
> counted once per presence bucket per app. So traffic to an off-domain asset
> host ticks the same limit as the apex (and is exempt from the daily cap when
> the app is `exemptFromDaily`), and when the app's aggregate hits its limit,
> **every** host in the set goes to `extraBlocked` together.
>
> **A host that belongs to no configured app is its own single-host app** — not
> a member of some catch-all "Other" app. There is no semantic "Other" bucket:
> it only ever appears as a **display rollup** of the long tail (e.g. a usage
> view shows the top-5 apps and folds the remainder into "Other"). A host
> landing in that rollup means "low on the list," **not** "part of an Other
> app." This rework landed in #1526 (closed): unmatched hosts are now their own
> single-host `AppMembership` (`AppMembership.forUnmatchedHost`,
> `api/src/usage/UsageTraffic.scala:27`), keyed by the host itself so two
> unmatched hosts produce two distinct memberships rather than one shared
> `__other__` bucket.
>
> The per-app count is computed with the #1464 session-stitch primitive (#1504,
> via `Presence.patternGroupMinutesForProfile`) — engaged wall-clock time across
> the whole host-set, combined across the profile's devices per its overlap
> mode, not the legacy bucket-max floor. App host-sets are the curated per-app
> dependency domains and are distinct from the device-level infra-allow list
> (#1337/#1411): app assets *attribute and count*, device infra is *allowed and
> suppressed* — see the AGENTS.md "host-set" seam.

Per-MAC usage is reported to the API every 60 s via
`POST /api/router/usage` (§6.4); the API accumulates and decides. Worst-case
overshoot is one usage-report interval (~60 s) plus one policy-poll interval
(~60 s).

### 7.6 Block-page redirect

nftables `dnat` on TCP/80 to `127.0.0.1:8081` (uhttpd) AND on TCP/443 to
`127.0.0.1:8443` (uhttpd with a self-signed cert). The lua handler at
`/www/wifihaven/handler.lua` reads the requested host from the
`HTTP_HOST` env and the client MAC from the kernel ARP table keyed by
`REMOTE_ADDR`. It renders the block page directly with `?mac=&host=&bpt=` —
no external 302, and (post-#679/#1617/#1618) no per-MAC reason lookup at
all here: the SPA derives the canonical block reason separately via
`GET /api/blocked` (which re-runs `PolicyService.decide`).
This still works when the API is unreachable because the page itself
renders locally; only the reason text depends on the API being reachable.

**`bpt` — the block-page household token (#2566/#2569/#2322).** `mac` and
`host` say *which device asked for what*; they cannot say *which household*,
because a MAC may exist in several households (V74/V75). Without a household
the two unauthenticated block-page endpoints (`GET /api/blocked` and
`POST /api/access-requests`) resolved everything against household 1 — a
cross-household disclosure of that household's profile names and live
screen-time, and the wrong answer for every other household.

`bpt` is an opaque `<routerId>.<HMAC-SHA256(jwtSecret, "wifihaven-block-page-v1:" + routerId)>`
minted by the router-authenticated `GET /api/router/block-page-token`. The
agent fetches it at startup (retrying on its policy tick), publishes it to
`/var/run/wifihaven/block_page_token`, and the handler stamps it onto the
redirect. The API verifies the HMAC, then reads the household **live** from
the `routers` row — so moving a router between households re-points its token
and deleting the router revokes it. No expiry, no new secret, no schema change.

Two properties worth stating plainly:

- **It is not authentication.** It authorizes exactly what any client already
  on that household's LAN can do: read a block-page reason and today's
  minutes for a MAC, and file an access request. `POST /api/access-requests`
  keeps its per-source-IP limiter (#2081); `GET /api/blocked` has two buckets of
  its own — per source IP, which is the MAC-enumeration bound, and per
  (source IP, MAC), so one device cannot exhaust a NATed household's budget.
- **Its absence is not an error, and the two endpoints fall back differently.**
  The router↔API wire is additive and the two deploy independently, so an agent
  that predates the token still redirects without one. `GET /api/blocked` then
  answers from household 1 — exactly what it did before, and the residual
  #2569 disclosure, which is what the per-source-IP bucket above bounds.
  `POST /api/access-requests` instead falls back to the device row's own
  household (`DeviceRepo.findOwningHousehold`), because *that* is what it did
  before; defaulting it to household 1 would reject every other household's
  intake, since their MACs are absent from household 1. Both fallbacks are
  metered (`block_page_household_total{outcome}`), not silent — `absent` should
  trend to zero as the fleet rolls forward.

Per-household block-page hosting from the edge (#2109) remains the longer-term
answer for custom domains; it is not a prerequisite for correct household
derivation, which this token already provides.

#### HTTPS variant (#383)

Up through Gate 2, blocked HTTPS sites timed out silently — the kid hit
"this site can't be reached" and had no clue WiFi was filtering. As the
web HTTPSifies, that gap degrades the UX by year and teaches the wrong
lesson ("the wifi is broken; find a workaround") instead of the right
one ("this is blocked; ask a parent").

We now DNAT TCP/443 too, to a sibling uhttpd listener on
`127.0.0.1:8443` (and `[::1]:8443`) that terminates TLS with a
self-signed cert. Trade we accept:

- The browser shows a cert warning. The user clicks through (in modern
  Chrome / Safari / Firefox: "Advanced" → "Proceed anyway") and lands
  on the same block page as the HTTP path.
- The cert warning **is** the design. It is honest: the block page IS
  a non-standard interception of the requested host.

What we explicitly do NOT do:

- **No CA installed on managed devices.** That would be invasive (per-
  device setup), would escalate the trust model (we'd be sitting in
  every TLS handshake the device makes), and is not necessary for the
  block-page UX.

Cert generation lives in
`/usr/lib/wifihaven/generate-block-page-cert.sh` and is invoked by
`setup-uhttpd-block-page.sh` before the TLS listener binds. CN is
`block.wifihaven.local` (a hint — not a real DNS name); RSA-2048; valid
for 10 years; **idempotent**: subsequent agent restarts reuse the
existing cert and never rotate it. Keys live at
`/etc/wifihaven/block_page.crt` and `/etc/wifihaven/block_page.key`.

The DNAT pairing is emitted by a single `dnat4`/`dnat6` helper in
`render.lua` — every block predicate (whole-MAC, per-(MAC, host),
per-(MAC, blocklistId), `blockIpOnly`, global block, global lockdown,
v4 + v6) lands BOTH a TCP/80 → 8081 rule and a TCP/443 → 8443 rule. No
predicate can drift between the two ports.

Still traffic-layer enforcement — DNS resolves normally; the redirect
happens at nftables prerouting, exactly the same as HTTP/80.

## 8. OpnSense agent design  *(agent code done — #94; deploy plan #89 still open)*

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
| #70 | `POST /api/router/decision` + public `/blocked` page | done |
| #71 | Delete `dns/` and `traffic/` modules | done (#125) |
| #72 | OpenWRT agent (Lua, opkg) | done — in production |
| #73 | e2e fake-router in staging compose | done |
| #88 | OpenWRT deploy plan (`openwrt/README.md`, `openwrt/build-ipk.sh`, CI `.ipk` build) | done |
| #89 | OPNsense deploy plan | open — OPNsense not yet a supported production target |
| #93 | OpenWRT agent: emit `connection_attempt` events per new outbound flow | done |
| #94 | OpnSense agent (Python, OPNsense plugin) — event emission | done |

Steps #67–#70 land before #71: the old enforcement stack is not deleted until
the new API surface is addressable.

---

## Imported rules (originally in AGENTS.md)

These sections used to live in AGENTS.md; the TOC there now points here.

### What this project is

WifiHaven is a self-hosted, network-level parental-control system with per-device filtering, time limits, and a web dashboard. The API runs on a Linux home server (Ubuntu) and replaces commercial products like Gryphon or TP-Link HomeShield; the enforcement agent runs on the gateway router (OpenWRT or OPNsense).

### Architecture

```
wifihaven/
├── shared/        # Domain models shared across all modules (Scala 3, ZIO JSON)
├── api/           # REST API (ZIO HTTP, Doobie, PostgreSQL)
├── openwrt/       # Lua agent for OpenWRT (dnsmasq + nftables policy enforcement)
├── opnsense/      # Python agent for OPNsense (Unbound + pflog usage events)
└── web/           # React TypeScript dashboard (Vite, Tailwind)
```

One JVM process runs in production:
1. `api` — REST API on :8080, handles auth (JWT), owns the DB, runs `PolicyService` (the only place decision logic lives).

SPA hosting differs by environment:

- **Self-hosted (local / dev / `deploy/install.sh`)**: the SPA is bundled
  with the API — `web/dist` is baked into the API container and served by
  the JVM on :8080. One deploy, one rollback.
- **Staging and production cloud (`app-staging.wifihaven.net`,
  `app.wifihaven.net`; API at `api.wifihaven.net`)**: the SPA deploys to **Cloudflare Pages**,
  independent of the API. The API JVM serves only `/api/*`; the SPA is a
  static bundle that talks to the API over the network like any other
  client. Cloudflare config lives in-repo:
  - [`infra/cloudflare/`](../infra/cloudflare/) — Terraform (`main.tf`,
    `variables.tf`) for the Cloudflare account-level resources.
  - [`web/wrangler.toml`](../web/wrangler.toml) (prod) and
    `web/wrangler.staging.toml` (staging) — Wrangler config for
    `wrangler pages deploy`. Deploys are driven from
    `.github/workflows/deploy-spa.yml`.

**In the cloud environments, API and SPA roll back independently.**
Rolling back the API on Render does **not** roll back the SPA on
Cloudflare Pages, and vice versa. A coordinated rollback must touch
both sides. This does not apply to the self-hosted install, where the
SPA ships inside the API image.

Connection-level enforcement and per-device usage tracking run on the gateway router, not on the API host (see the "Architectural model" callout in AGENTS.md for why):
- **OpenWRT** — the `openwrt/` Lua agent polls `/api/router/policy` and rewrites nftables rules + a dnsmasq fragment used only for hostname attribution / ipset population; reports usage via `/api/router/events` and `/api/router/usage`
- **OPNsense** — the `opnsense/` Python agent tails pflog and posts connection events

Key API surface (under `/api/router/*` and `/api/blocklists/*`):
- `POST /api/router/register` — one-time enrollment
- `GET  /api/router/policy`   — ETag-polled enforcement snapshot
- `GET  /api/blocklists/<id>` — plain hostname-list blocklist per category (not RPZ)
- `POST /api/router/usage`    — per-(mac, hostname) traffic records
- `POST /api/router/events`   — DHCP lease + connection attempt events

### Key domain concepts

- **User / role** — a login account scoped to one household (`users.household_id`, V65), with role `admin`, `adult`, or `child` (`shared/src/Models.scala:42`; the value `CHECK` is in `V1__init.sql:8`). **A household has exactly ONE admin** — enforced in the schema by the partial unique index `uq_users_household_single_admin ON users(household_id) WHERE role = 'admin'` (`V86__one_admin_per_household.sql`, [#2512](https://github.com/wifihaven/wifihaven/issues/2512)); the index is the backstop, and the readable 4xx comes from the guard on `POST /api/users` / `PATCH /api/users/{id}`. `adult` and `child` are many-per-household by design. The one-admin rule is what makes "one Plain support customer per household" (keyed on `externalId` = household id) *correct* rather than an aliasing hazard — a second admin's address would otherwise overwrite the household customer's email and wedge on Plain's workspace-wide email uniqueness ([#2435](https://github.com/wifihaven/wifihaven/issues/2435), [#2505](https://github.com/wifihaven/wifihaven/issues/2505)). The admin owns the **account** (who exists, who pays, what hardware is enrolled, the household kill-switch); [#2522](https://github.com/wifihaven/wifihaven/issues/2522) is the paired change that makes `adult` the **editing** role for everything else (profiles, schedules, blocklists, apps, settings), which is what makes a single admin livable.
- **Profile** — a set of filtering rules (blocked categories, schedules, time limits). Devices are assigned to profiles.
- **Device** — identified by MAC address (not IP, which changes with DHCP). Matched to a profile.
- **Schedule** — time windows when internet is blocked entirely for a profile (e.g. bedtime 21:00–07:00).
- **TimeLimit** — daily total minutes allowed per profile (e.g. 120 min/day total screen time).
- **App** — a named bundle of host patterns (a **host-set**: apex + off-domain asset/CDN domains) with a per-profile policy (allowed / blocked / time-limited). Apps are the unit usage and time limits are framed around — **the model is app-focused, not site-focused.** App *definitions* (name, slug, icon, host-set) are **authored only via the built-in `AppTemplates` in code** — there is no operator-facing create/edit/delete of definitions. To add or change an app, update `AppTemplates` and let the seed/reconcile pass apply it; `POST /api/apps/seed-from-templates`, `POST /api/apps/:id/reset-to-template`, and `POST /api/admin/apps/reconcile-templates` are the editing path — all three **operator-only** (`requireOperator`: admin AND household 1) since [#2567](https://github.com/wifihaven/wifihaven/issues/2567), because `apps` / `app_hosts` are a single install-wide catalog with no `household_id`, so a caller from any household running them rewrites state every other household's enforcement reads. Authoring definitions in the SPA was removed in [#1798](https://github.com/wifihaven/wifihaven/issues/1798) (it was the root of the duplicate-app slug-collision churn, #1777/#1794); the SPA Apps page is now a read-only directory. **Policy assignment** (assigning an app to a profile, with mode + schedule/daily flags) stays operator-driven via `PUT/DELETE /api/apps/:id/policy/:profileId` on the Profiles page. `DELETE /api/apps/:id` survives as an **operator-only** route (stray-row cleanup — same `requireOperator` gate, same reason, [#2535](https://github.com/wifihaven/wifihaven/issues/2535)) but is no longer surfaced in the SPA.
- **App time limit** — daily minutes for an *app*, counted against its **whole host-set aggregated as one budget** (#1505), tracked *separately* from the main daily limit (e.g. 30 min YouTube across `youtube.com` + `ytimg.com` + `googlevideo.com`, not counted in the 120 min total). A host belonging to no configured app **is its own single-host app**; there is no semantic catch-all "Other" app. "Other" only ever appears as a **display rollup** of the long tail (top-N + remainder) — a host there is *low on the list*, not *part of an Other app*.
  > Naming debt: a few residual `SiteUsage` / `SiteDayState` / `perSite` spellings remain in code. The `__other__` synthetic-membership sentinel itself is gone (#1526, closed) — unmatched hosts are now per-host `AppMembership` rows (`UsageTraffic.scala:27,257`); only comments and tests referencing the old name (and asserting its absence) remain. The reason token and BlockReason JSON kind were renamed to `app_time_limit:` / `appTimeLimit` in #1518 — both are SPA-API surfaces (the router treats `BlockReason` wire strings as opaque pass-through, so renaming was safe even pre-#376). `BlockReason.fromWire` only parses the new `app_time_limit:` text — routers echo back whatever they receive, and the dual-written `reason_text` column is consumed by older-image rollback only, never re-parsed here, so no live caller needs the legacy text alias. `JsonDecoder[BlockReason]` does still accept the legacy `siteTimeLimit` kind because V40-migrated `block_events.reason` / `connection_events.reason` JSONB rows persist that kind; the encoder canonicalizes them to `appTimeLimit` on read so the SPA never sees the legacy form on the wire.
- **TimeUsage** — per-(device, domain, date) minutes accumulated, reset at midnight. Updated by traffic monitor.
- **TimeExtension** — admin-granted extra minutes for a device on a specific day, with audit trail.
- **BlocklistDomain** — domain → category mapping. Loaded into memory cache, refreshed every 15 min.
- **QueryLog** — every DNS query logged with device, profile, blocked status, reason.
- **Location** — `home` or `vacation`. Stored on devices and logs. Both locations share profiles/devices but query logs are tagged so you can filter by house.

### Policy decision pipeline (server-side, in `PolicyService`)

These steps happen **on the API server** when computing the snapshot — not on
the router. They collapse into the per-MAC `BlockRules` fields described in
the "Architectural model" callout in AGENTS.md.

Order matters because earlier conditions short-circuit:

1. Profile paused → `blocked = true`, reason `Paused`
2. Schedule active for current time → `blocked = true`, reason `Schedule`
3. Daily time limit reached (`time_used_today >= daily_minutes + extensions_today`) → `blocked = true`, reason `TimeLimit`
4. Per-app time limit reached (an app's usage, aggregated across its whole host-set, hits its limit) → **every** host in that app's host-set added to `extraBlocked` for this MAC
5. Profile / device `extraBlocked` hostnames → `extraBlocked` for this MAC
6. Profile / device `extraAllowed` hostnames → `extraAllowed` for this MAC (carves out blocks above)
7. Profile / device assigned categories → `blocklistIds` for this MAC
8. `blockIpOnly` flag for the profile / device → set as-is

Two `MacBlockReason` cases sit outside this per-request short-circuit chain
rather than being a numbered step: `Unmanaged` (#1122) is the baseline for a
device with no profile assignment under a `block` household policy, and
`DefaultDeny` (§0.3) is the lowest-precedence baseline for a profile in
default-deny mode — either can still be overridden by a higher-precedence
step above reporting a stronger reason. `Manual` is reserved vocabulary with
no producer today — no step in `PolicyService` ever emits it (see
[#2087](https://github.com/wifihaven/wifihaven/issues/2087)); AGENTS.md's
Architectural model section and §0.2 above list the full six-case ADT.

The router never re-evaluates any of this. It receives the resolved
`BlockRules` and applies them mechanically.

(DNS resolution itself is never blocked by WifiHaven. dnsmasq forwards
upstream as normal; the enforcement plane is nftables on the resolved IPs.)
