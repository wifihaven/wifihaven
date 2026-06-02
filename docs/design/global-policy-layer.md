# Design: global policy layer + per-profile default-deny

Status: **proposal** (issue
[#1308](https://github.com/wifihaven/wifihaven/issues/1308)). This document
specifies the wire-shape changes, the global→profile→device composition model,
and per-profile default-deny. Implementation lands in the follow-up issues
listed in [§8](#8-follow-up-implementation-issues). Pre-v1.0: agent and API
tandem-deploy, no compatibility shims.

## 1. Problem

Every policy decision is collapsed per-MAC into `BlockRules` server-side. That
is the right model — the router stays a dumb applier (see `AGENTS.md`
"Architectural model" and `docs/architecture.md` §0). But two things are
currently expressed by **copying a shared concept into every profile/MAC**:

- `uiAllowedHosts` is unioned into *every* profile's `extraAllowed`
  (`PolicyService.computeBlockRules`, `api/src/policy/PolicyService.scala:549`)
  and into the unmanaged-device block path
  (`api/src/policy/PolicyService.scala:169`).
- The near-term fix [#1307](https://github.com/wifihaven/wifihaven/issues/1307)
  ships the **global infra allowlist** (connectivity-check, OCSP, PKI,
  captive-portal hosts that must survive any block) the same way — copied into
  every profile.

Copying a fleet-wide set into N profiles bloats the snapshot, and a single
change to that set rewrites every entry (and the ETag), forcing a full
re-poll across the fleet. We want the snapshot to carry global policy **once**,
plus a precise model for how global / profile / device settings compose.

Separately, we want a per-profile **default-deny** mode ("block everything not
explicitly allowed") — the inverse of today's allow-by-default + blocklists
model.

## 2. Invariants this design must not break

From `AGENTS.md` and `docs/architecture.md` §0 — these are load-bearing:

1. **DNS is never the enforcement plane.** All blocking is nftables
   forward-drop on resolved IPs; the block page is HTTP/80 DNAT. The global
   layer adds no DNS-layer mechanism.
2. **The router is a dumb applier.** It never learns about tiers, profiles,
   schedules, or time. All composition resolves **server-side** into a per-MAC
   `BlockRules` map plus a small flat global section.
3. **`extraAllowed` beats every per-MAC block path** (#421,
   `memory/feedback_extraallowed_beats_blocked.md`). The global layer must not
   weaken this, and `global.extraAllowed` must carve out *every* drop —
   including a global block.

## 3. Wire-shape changes

### 3.1 The global section is a `BlockRules`

`PolicySnapshot` gains one field carrying fleet-wide policy once. **Its shape
is `BlockRules` — the exact shape `ProfilePolicy.rules` already carries.** No
new struct, and no field describes *why* a host is allowed or blocked, only
*what the router does*:

```scala
case class PolicySnapshot(
    etag:        ETag,
    generatedAt: String,
    global:      BlockRules,                        // NEW — applied to every MAC (see §5.2)
    devices:     Map[MacAddress, DevicePolicy],
    profiles:    Map[ProfileId, ProfilePolicy],     // wire-dedup only; never seen by enforcement
    blocklists:  Map[BlocklistId, Blocklist],
)
```

Reusing `BlockRules` means each field has the same router behaviour it has on a
profile, just applied to every MAC:

| `global` field | Router behaviour (all MACs) |
|----------------|-----------------------------|
| `extraAllowed` | Hosts reachable from every MAC. **Carves out every block**, per-MAC or global. This is where the connectivity-check / PKI / captive-portal hosts **and** the WifiHaven UI / block-page hosts go — the router does not care *why* a host is on this list, only that it is always reachable. Replaces both the #1307 per-profile copy and the `uiAllowedHosts` copy (`PolicyService.scala:549`). |
| `extraBlocked` | Hosts blocked for every MAC. A profile/device may **not** un-block them (only `global.extraAllowed` can). |
| `blocklistIds` | Category lists applied to every MAC. Same un-blockable semantics as `global.extraBlocked`. |
| `blocked` | Whole-network lockdown: drop all forwarded traffic for every MAC except `global.extraAllowed`. A global kill switch. |
| `blockReason` | Block-page text when `global.blocked` is set. |
| `blockIpOnly` | Strict-IP mode for every MAC (drop destinations not resolved locally for that MAC). |

So we get the "global allowlist" the issue asked for as `global.extraAllowed`,
plus a global hard-block, a network lockdown, and a network-wide strict mode —
all from one shape we already ship and the router already understands. Most
deployments set only `global.extraAllowed`; the rest default to the
allow-all/empty values of `BlockRules.allowAll`.

**No `version` field on the wire.** The snapshot `etag` already changes when
`global` changes, so the router needs nothing extra. Curation and audit of
*which* hosts are on `global.extraAllowed` (and why) is a server-side / DB
concern, not wire shape — see [§7](#7-curation-and-audit).

**Is there any reason `global` should differ from a profile's `BlockRules`?**
No field needs to. The only thing that is global-specific is the
*composition precedence* (`global` outranks per-MAC; see
[§5.2](#52-stage-2--global-composition)) — that is router behaviour, not shape.

### 3.2 What is *not* in the global section

Global **defaults** (e.g. a household default for `blockIpOnly`, or a default
category set for new profiles) are **not** on the wire. They are an authoring
convenience: when a profile leaves a dimension unset, `PolicyService` fills it
from the household default while computing that profile's `BlockRules`. By the
time the snapshot is built, the value is already baked into per-MAC
`BlockRules` and is indistinguishable from a profile-set value. A default a
profile may override is therefore a server-side concern only — exposing it on
the wire would invite the router to "merge," which violates invariant #2.

### 3.3 `BlockRules`, `DevicePolicy`, `ProfilePolicy` — mostly unchanged

`BlockRules` is unchanged. `DevicePolicy.rules: Option[BlockRules]` and
`ProfilePolicy` are unchanged in shape. What changes is **what the API puts in
them** (see [§5](#5-composition--override-model)) and that per-MAC
`extraAllowed` no longer carries the always-reachable hosts (those moved to
`global.extraAllowed`).

`MacBlockReason` gains one case for block-page text:

```scala
sealed trait MacBlockReason extends BlockReason
object MacBlockReason:
  case object Paused      extends MacBlockReason
  case object Schedule    extends MacBlockReason
  case object TimeLimit   extends MacBlockReason
  case object Manual      extends MacBlockReason
  case object Unmanaged   extends MacBlockReason
  case object DefaultDeny extends MacBlockReason   // NEW — profile is default-deny baseline
```

`DefaultDeny` is the **lowest-precedence** reason (it is the steady-state
baseline; a concurrent Paused/Schedule/TimeLimit reports the stronger reason).

## 4. Default-deny per profile

A profile in default-deny mode has baseline **block-all**; only its
`extraAllowed` (plus `global.extraAllowed`) is reachable.

**Representation requires no new router field.** Default-deny *is* exactly
`blocked = true` with `extraAllowed` carving out — the router already enforces
that (#421). So `PolicyService` collapses a default-deny profile to:

```
blocked      = true
blockReason  = DefaultDeny      (unless a stronger reason already applies)
extraAllowed = <profile/device allow list>     // global.extraAllowed is NOT copied here
extraBlocked = []               // pointless under block-all — omitted to slim the wire
blocklistIds = []               // pointless under block-all — omitted
blockIpOnly  = <as configured>
```

Interactions:

- **`global.extraAllowed`** still carves out, because it is a separate layer
  applied to every MAC at the router — independent of whether the MAC's
  `blocked` flag came from default-deny, a schedule, or a pause. The
  always-reachable hosts (UI, block page, connectivity check, PKI) stay
  reachable.
- **Per-device overrides** compose per [§5](#5-composition--override-model): a
  device override fully specifies the per-MAC rules, so a device may run
  allow-by-default under a default-deny profile, or vice versa.
- **`blockIpOnly`** — default-deny + `blockIpOnly` is the **strictest**
  combination. `blocked = true` drops everything except `extraAllowed ∪
  global.extraAllowed`; `blockIpOnly` independently drops any destination IP not
  in `resolved_<m>` (the set dnsmasq populated from *this MAC's* lookups). A
  packet survives **both** only if its destination is (a) explicitly allowed
  **and** (b) was resolved through our local DNS for this MAC. Allowed hosts
  are resolved by dnsmasq and land in `resolved_<m>` naturally, so they pass;
  hard-coded-IP and DoH destinations to anything (allowed or not) are dropped.
  Net: only explicitly-allowed, locally-resolved hosts are reachable.
- **Schedules / time limits** are **orthogonal**. They also produce
  `blocked = true`; default-deny and a schedule simply `OR` into the same flag.
  When both apply, enforcement is identical and the reported `blockReason`
  follows the precedence ladder (Paused > Schedule > TimeLimit > DefaultDeny).
  `extraAllowed` carves out of all of them, consistent with #421.

DB: a `profiles.default_deny BOOLEAN NOT NULL DEFAULT false` column. Devices
inherit it via the profile unless they carry a `rules` override.

## 5. Composition / override model

Resolution is **two stages**, both server-side. The router sees only the
output of stage 2.

### 5.1 Stage 1 — per-MAC base rules (device REPLACES profile)

```scala
// unchanged router-side resolution
def effective(d: DevicePolicy, profiles: Map[ProfileId, ProfilePolicy]): BlockRules =
  d.rules
    .orElse(d.profileId.flatMap(profiles.get).map(_.rules))
    .getOrElse(BlockRules.allowAll)
```

We **keep replace semantics**, not a layered union-merge. A device with a
`rules` override fully specifies its per-MAC policy; a device without one
(`rules = None`) inherits its profile's resolved `BlockRules` verbatim. The
wire dedup is preserved: only devices that actually differ from their profile
carry an inline `rules`.

What changes is the *meaning* on the API side: when a device has an override,
`PolicyService` now produces the **merged** profile+device result and inlines
it as the device's `BlockRules`. The router contract is byte-identical to
today; only the server computation changes.

**Why replace, not union:** a union of `extraAllowed` across tiers is unsafe
under default-deny. If a profile is allow-by-default with
`extraAllowed = [a, b, c]` and a device flips to default-deny but should reach
only `x`, a union would carry `a, b, c` through the device's block-all and
defeat the intent. Replace avoids this footgun and keeps the model
predictable. The #1308 redundancy is removed not by switching to merge but by
**relocating the shared always-reachable hosts to `global.extraAllowed`** —
which was the thing actually being copied. Profile/device `extraAllowed` now
carries only profile/device-specific allows, so replace no longer forces
copying the fleet-wide set.

(A future structured-patch override — `inherit | replace | extend` — is a
possible enhancement if operators need "add one allow on top of the profile"
without restating it. It is out of scope here; replace is the baseline.)

### 5.2 Stage 2 — global composition

The global `BlockRules` is **not** a third tier in the stage-1 merge. The
router applies it to every MAC alongside that MAC's resolved `BlockRules`, with
a fixed precedence: **`global.extraAllowed` outranks everything; global blocks
outrank per-MAC allow; per-MAC allow outranks per-MAC blocks.**

Let `G` = the global `BlockRules`, `R` = the MAC's resolved per-MAC
`BlockRules` (stage 1), and for a forwarded packet from MAC `m` to destination
`d`:

```
ga(d)        ⇔  d ∈ G.extraAllowed
ra(m,d)      ⇔  d ∈ R.extraAllowed
gblock(d)    ⇔  G.blocked ∨ d ∈ G.extraBlocked ∨ d ∈ ⋃ ipset(G.blocklistIds)
rblock(m,d)  ⇔  R.blocked ∨ d ∈ R.extraBlocked ∨ d ∈ ⋃ ipset(R.blocklistIds)

drop(m,d) ⇔
      ¬ga(d) ∧ ( gblock(d) ∨ ( ¬ra(m,d) ∧ rblock(m,d) ) )
   ∨  (G.blockIpOnly ∨ R.blockIpOnly) ∧ d ∉ resolved_<m>
```

Read it as a precedence ladder, top wins:

1. `ga(d)` — `global.extraAllowed`. Suppresses every hostname/MAC drop, global
   or per-MAC. Nothing overrides it.
2. `gblock(d)` — a global block. Beaten only by `ga`; a per-MAC `ra` does
   **not** suppress it ("a profile may not un-block a global block").
3. `ra(m,d)` — per-MAC `extraAllowed`. Suppresses the per-MAC blocks only
   (#421).
4. `rblock(m,d)` — per-MAC blocks.
5. `blockIpOnly` (global OR per-MAC) — orthogonal; no allowlist carve-out
   because allowed hosts are resolved into `resolved_<m>` by dnsmasq and so
   pass the IP-only test naturally.

On OpenWRT this is two extra fleet-wide ipsets — `@global_allow` (`ga`) and
`@global_block` (`gblock` hosts/categories; plus a global blocked flag) —
layered onto the existing per-MAC `@ea_` / `@blocked_macs` / `@eb_` / `@bl_`
rules. It is **not** tier logic: the router still never sees a profile, a
schedule, or "global vs profile" — it sees one more `BlockRules` whose drops
rank above the per-MAC ones and whose `extraAllowed` ranks above everything.

### 5.3 Precedence table

For each policy dimension, who wins across global / profile / device, and why.
"Wins" = determines the enforced outcome after server-side resolution.

| Dimension | Global | Profile | Device | Resolution | Why |
|-----------|--------|---------|--------|------------|-----|
| **`extraAllowed`** | ✅ always carves out — beats every block, global or per-MAC | per-MAC list | per-MAC list (replace) | `global.extraAllowed` is the top of the ladder; per-MAC `extraAllowed` beats per-MAC blocks only (#421) | Always-reachable hosts (UI, block page, connectivity, PKI) must never be un-reachable |
| **`extraBlocked`** | beats profile/device allow; only `global.extraAllowed` saves | per-MAC list | per-MAC list (replace) | Global block beaten only by `global.extraAllowed`; per-MAC block carved out by per-MAC `extraAllowed` | A global host block is mandatory; a profile host block is loosenable by the MAC |
| **`blocklistIds`** | beats profile/device allow; only `global.extraAllowed` saves | per-MAC set | per-MAC set (replace) | Same as `extraBlocked` | Global categories are mandatory; profile categories are loosenable |
| **`blocked`** | whole-network lockdown; only `global.extraAllowed` saves | per-MAC (pause/schedule/time-limit/manual) | per-MAC (replace) | Global lockdown beaten only by `global.extraAllowed`; per-MAC block carved out by per-MAC `extraAllowed` | Network kill switch vs. per-MAC block |
| **`blockIpOnly`** | applies to all MACs if set | per-MAC value | per-MAC value (replace) | `global.blockIpOnly ∨ R.blockIpOnly`; strictest wins; no allow carve-out | Strict-IP is a floor the network can raise; a profile may also raise it |
| **Default-deny baseline** | — | per-profile flag → `blocked=true` | device override may flip (replace) | Collapses into per-MAC `blocked`; composes as the `blocked` row | Per-profile inversion of allow-by-default |
| **Schedules / time limits** | — | profile-evaluated → `blocked` | follows the MAC | Orthogonal `OR` into per-MAC `blocked`; `extraAllowed` carves out | Time-based blocks compose with everything else |
| **`failureMode`** | — | per-profile | follows the MAC | Per-profile, unchanged | Router behaviour on poll failure (#422) |

Reading the override directions the issue asked for explicitly:

- **`global.extraAllowed` = global always wins / always carves out.** Top of
  the ladder — beats global blocks and per-MAC blocks alike.
- **Global block (`global.extraBlocked` / `global.blocklistIds` /
  `global.blocked`) = profile may NOT un-block.** A per-MAC `extraAllowed` does
  not suppress a global block; only `global.extraAllowed` does.
- **A global *default* (e.g. a household default `blockIpOnly`) = profile may
  override.** This is resolved server-side into the per-MAC `BlockRules` and
  never reaches the wire as "global" (see [§3.2](#32-what-is-not-in-the-global-section));
  it is distinct from `global.blockIpOnly`, which is an enforced network-wide
  floor.

## 6. JSON wire examples

### 6.1 Global section carried once; profiles reference it by *not* copying

```json
{
  "etag": "sha256:abc123...",
  "generatedAt": "2026-06-01T14:00:00Z",
  "global": {
    "blocked": false,
    "blockReason": null,
    "extraBlocked": [],
    "extraAllowed": [
      "connectivitycheck.gstatic.com",
      "captive.apple.com",
      "ocsp.digicert.com",
      "ocsp.pki.goog",
      "wifihaven.local"
    ],
    "blocklistIds": [],
    "blockIpOnly": false
  },
  "devices": {
    "aa:bb:cc:11:22:33": { "profileId": 3, "name": "kid-ipad", "rules": null },
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

Note: the always-reachable hosts (connectivity check, PKI, **and** the
WifiHaven UI/block-page host `wifihaven.local`) appear **once** under
`global.extraAllowed`, not duplicated into the `kids` profile's `extraAllowed`
or into every device. The router does not distinguish "infra" from "UI" — both
are just always-reachable hosts. Changing the list rewrites only `global`
(and the snapshot `etag`), not every profile entry. `global` is a plain
`BlockRules`: the empty/false fields mean "no global block, no global lockdown,
no global strict-IP."

### 6.2 A default-deny profile

```json
{
  "profiles": {
    "7": {
      "name": "toddler",
      "rules": {
        "blocked": true,
        "blockReason": "DefaultDeny",
        "extraBlocked": [],
        "extraAllowed": ["pbskids.org", "khanacademykids.org"],
        "blocklistIds": [],
        "blockIpOnly": true
      },
      "failureMode": "block-all"
    }
  }
}
```

A device on profile `7` (`rules: null`) is reachable only at `pbskids.org`,
`khanacademykids.org`, and the `global.extraAllowed` hosts — and, because
`blockIpOnly` is on, only when those are reached via locally-resolved IPs.
`extraBlocked` / `blocklistIds` are omitted (empty) because they are redundant
under block-all.

### 6.3 A device override under a default-deny profile (replace semantics)

```json
{
  "devices": {
    "aa:bb:cc:99:88:77": {
      "profileId": 7,
      "name": "parent-loaner",
      "rules": {
        "blocked": false,
        "blockReason": null,
        "extraBlocked": [],
        "extraAllowed": [],
        "blocklistIds": ["ads"],
        "blockIpOnly": false
      }
    }
  }
}
```

This device runs allow-by-default even though its profile is default-deny — the
override **replaces** the profile's rules entirely. It still composes with the
fleet-wide `global` `BlockRules` (`global.extraAllowed` carves out; any global
block applies).

## 7. Curation and audit

`global.extraAllowed` is a **security-sensitive bypass surface**: a host on it
is reachable from every device regardless of any block. It must be curated and
auditable. This is a **server-side / DB concern, not wire shape** — the wire
carries only the flat hostname list the router needs; the *why* and *who*
stay in the database.

- **Dedicated table.** A `global_allow` table with audit columns (`added_by`,
  `added_at`, `reason`, soft-delete `removed_by`/`removed_at`) feeds
  `global.extraAllowed`. Edits append/soft-delete; history is retained. The
  *reason* (connectivity survival, PKI, the WifiHaven UI/block page, an
  operator carve-out) lives here as metadata — the router never sees it.
- **No wire version needed.** The snapshot `etag` already changes when
  `global` changes; the SPA can show "global allowlist last changed …" from
  the audit table's timestamps.
- **Distinct authoring surfaces, one wire shape.** Global *blocks*
  (`global.extraBlocked` / `global.blocklistIds` / `global.blocked`) are
  operator policy with their own UI and permissions; the curated always-allow
  list is tightly controlled. They collapse into one `global` `BlockRules` on
  the wire but are edited separately server-side.
- **Auditable in the UI.** A read-only audit view of who changed the global
  allow list and why (see SPA follow-up).

## 8. Follow-up implementation issues

Filed against this design. Dependency order is shown; the migration PR (item 2)
precedes the PolicyService adoption PR (item 3) per the schema-only
isolation rule.

| # | Issue | Depends on |
|---|-------|------------|
| 1 | [#1316 — shared: add `PolicySnapshot.global` (BlockRules) + `MacBlockReason.DefaultDeny`](https://github.com/wifihaven/wifihaven/issues/1316) | — |
| 2 | [#1317 — db: migration for global policy tables + `profiles.default_deny` (schema-only PR)](https://github.com/wifihaven/wifihaven/issues/1317) | — |
| 3 | [#1318 — PolicyService: assemble global section, relocate `uiAllowedHosts`, default-deny eval](https://github.com/wifihaven/wifihaven/issues/1318) | #1316, #1317 |
| 4 | [#1319 — router: global composition (`@global_allow` / `@global_block`) on OpenWRT + OPNsense](https://github.com/wifihaven/wifihaven/issues/1319) | #1316, #1318 |
| 5 | [#1320 — web: global allow/block management + audit view, default-deny toggle, device override editor](https://github.com/wifihaven/wifihaven/issues/1320) | #1317, #1318 |
| 6 | [#1321 — policy: retire the #1307 per-profile infra-allow copy](https://github.com/wifihaven/wifihaven/issues/1321) | #1318, #1319 |
| 7 | [#1322 — test: global policy layer + default-deny coverage (feature + router)](https://github.com/wifihaven/wifihaven/issues/1322) | #1318, #1319 |

1. **[#1316](https://github.com/wifihaven/wifihaven/issues/1316) — `shared`
   models.** Add `PolicySnapshot.global: BlockRules` and
   `MacBlockReason.DefaultDeny`; codecs; ETag input. No new struct — `global`
   reuses `BlockRules`. (No router/SPA logic.)
2. **[#1317](https://github.com/wifihaven/wifihaven/issues/1317) — DB migration
   (schema-only PR, per the migration-isolation rule).** `global_allow` table
   (audit columns + soft-delete), a `global_blocks` table (hosts) +
   household-level global `blocklistIds` association + global
   `blocked`/`blockIpOnly` flags, and a `profiles.default_deny` column. Touches
   only small/lookup tables — not the unbounded-growth event tables — so it is
   metadata-only and safe on the startup path. Ships in its own PR with only
   `*.sql` + docs.
3. **[#1318](https://github.com/wifihaven/wifihaven/issues/1318) —
   `PolicyService` changes.** Assemble the `global` `BlockRules` from the new
   tables; **stop** copying `uiAllowedHosts` into per-MAC `extraAllowed`
   (`PolicyService.scala:549`) and out of the unmanaged-block path (`:169`) —
   relocate to `global.extraAllowed`; evaluate per-profile default-deny →
   `blocked=true` + `DefaultDeny` reason; resolve global *defaults* (the
   loosenable kind) server-side; produce merged device overrides. Instrument: a
   counter for global-section size / change events and a gauge for default-deny
   profile count (per the "new functionality ships with metrics" rule) + a
   Grafana panel.
4. **[#1319](https://github.com/wifihaven/wifihaven/issues/1319) — Router global
   composition (openwrt `render.lua` + agent; opnsense parity).** Add
   `@global_allow` (suppresses every drop, all MACs) and `@global_block`
   (suppressed only by `@global_allow`) ipsets + a global blocked/blockIpOnly
   flag; implement the drop predicate per
   [§5.2](#52-stage-2--global-composition); populate the global ipsets via
   dnsmasq `nftset=` callbacks; lua tests in `openwrt/test/`.
5. **[#1320](https://github.com/wifihaven/wifihaven/issues/1320) — SPA
   settings.** Global always-allow management page with the audit view;
   global-blocks management; per-profile default-deny toggle; per-device
   override editor that reflects replace semantics (and warns that overriding a
   default-deny profile fully restates it).
6. **[#1321](https://github.com/wifihaven/wifihaven/issues/1321) — Retire the
   #1307 copy.** Once `global.extraAllowed` ships, remove the per-profile copy
   introduced by [#1307](https://github.com/wifihaven/wifihaven/issues/1307).
7. **[#1322](https://github.com/wifihaven/wifihaven/issues/1322) — Tests
   (feature + router).** `global.extraAllowed` carves out a `blocked=true` MAC;
   a global block beats a per-MAC `extraAllowed` but `global.extraAllowed` beats
   the global block; default-deny + `blockIpOnly` reaches only
   allowed-and-locally-resolved hosts; default-deny profile collapses to
   `blocked=true` + `DefaultDeny`.

## 9. Rollout

Prod is deployed, so the router↔API snapshot is a backwards-compatible
contract: API and agent deploy independently, and `global` is an **additive**
field. A current agent ignores keys it doesn't recognize — OpenWRT parses the
body with `luci.jsonc.parse` into a plain Lua table, reads only the keys it
knows, and round-trips the rest untouched via `jsonc.stringify`; OPNsense does
not parse the snapshot at all yet (TODO #112). So shipping `global` does not
require a coordinated agent release — an old agent keeps enforcing exactly as
before until a new agent learns to compose it.

Order: ship the `shared` shape and `PolicyService` emission first (old agents
ignore `global`; new behavior is inert until the router learns the ipsets),
then the router composition, then retire the #1307 copy. The migration PR
precedes the `PolicyService` PR per the schema-only isolation rule. This stays
backwards-compatible only by addition (no field removals/renames on the wire)
until capability negotiation / wire versioning lands (#376).
