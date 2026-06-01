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
   weaken this, and the global infra allowlist must carve out *every* drop —
   including a global block.

## 3. Wire-shape changes

### 3.1 New `GlobalPolicy` section

`PolicySnapshot` gains one field carrying fleet-wide policy once:

```scala
case class PolicySnapshot(
    etag:        ETag,
    generatedAt: String,
    global:      GlobalPolicy,                     // NEW — fleet-wide, applied to every MAC
    devices:     Map[MacAddress, DevicePolicy],
    profiles:    Map[ProfileId, ProfilePolicy],    // wire-dedup only; never seen by enforcement
    blocklists:  Map[BlocklistId, Blocklist],
)

case class GlobalPolicy(
    version:       GlobalPolicyVersion,            // content hash — audit + ETag input
    infraAllow:    List[Hostname],                 // @global_allow — beats ALL drops, all MACs
    alwaysBlocked: List[Hostname],                 // @global_block — beats profile/device allow
    blocklistIds:  List[BlocklistId],              // global category lists, applied to all MACs
)
```

Only **router-relevant, fleet-wide** policy lives here. `infraAllow` is the
#1307 set, relocated out of per-MAC `extraAllowed`. `alwaysBlocked` and the
global `blocklistIds` are room for the "global hard block" requirement; they
ship empty until their follow-up lands, but the shape is fixed now.

**`GlobalPolicy` carries its own `version`** so the SPA/audit tooling and the
snapshot ETag can both reference it, and so a change to the curated infra
allowlist is individually auditable (see [§7](#7-curation-versioning-and-audit)).

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
them** (see [§5](#5-composition--override-model)) and that `extraAllowed` no
longer carries the infra/UI hosts (those moved to `global.infraAllow`).

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
`extraAllowed` (plus `global.infraAllow`) is reachable.

**Representation requires no new router field.** Default-deny *is* exactly
`blocked = true` with `extraAllowed` carving out — the router already enforces
that (#421). So `PolicyService` collapses a default-deny profile to:

```
blocked      = true
blockReason  = DefaultDeny      (unless a stronger reason already applies)
extraAllowed = <profile/device allow list>     // global.infraAllow is NOT copied here
extraBlocked = []               // pointless under block-all — omitted to slim the wire
blocklistIds = []               // pointless under block-all — omitted
blockIpOnly  = <as configured>
```

Interactions:

- **Global infra allowlist** still carves out, because `global.infraAllow`
  is a separate flat layer applied to every MAC at the router — independent of
  whether the MAC's `blocked` flag came from default-deny, a schedule, or a
  pause. OCSP/PKI/captive-portal hosts stay reachable.
- **Per-device overrides** compose per [§5](#5-composition--override-model): a
  device override fully specifies the per-MAC rules, so a device may run
  allow-by-default under a default-deny profile, or vice versa.
- **`blockIpOnly`** — default-deny + `blockIpOnly` is the **strictest**
  combination. `blocked = true` drops everything except `extraAllowed ∪
  global.infraAllow`; `blockIpOnly` independently drops any destination IP not
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
**relocating the shared concern (infra/UI allow) to the global layer** — which
was the thing actually being copied. Profile/device `extraAllowed` now carries
only profile/device-specific allows, so replace no longer forces copying the
fleet-wide set.

(A future structured-patch override — `inherit | replace | extend` — is a
possible enhancement if operators need "add one allow on top of the profile"
without restating it. It is out of scope here; replace is the baseline.)

### 5.2 Stage 2 — global envelope (flat, fleet-wide)

The global section is **not** a third tier in the stage-1 merge. It is an
orthogonal envelope the router applies uniformly to every MAC:

- `global.infraAllow` → a single `@global_allow` ipset that suppresses **every**
  drop path for **every** MAC. It is the fleet-wide analogue of `extraAllowed`,
  and it ranks **above** the global block.
- `global.alwaysBlocked` / `global.blocklistIds` → `@global_block`-class ipsets
  that drop for every MAC and are suppressed **only** by `@global_allow` — a
  profile or device's own `extraAllowed` does **not** un-block them.

The router drop predicate (extending the one documented in
`openwrt/.../render.lua`) becomes, for a forwarded packet from MAC `m` to
destination `d`:

```
drop(m, d) ⇔
      ( global_block_hit(d)        ∧ ¬global_allow_hit(d) )
   ∨  ( m ∈ blocked_macs            ∧ ¬ea_hit(m,d) ∧ ¬global_allow_hit(d) )
   ∨  ( eb_hit(m,d)                 ∧ ¬ea_hit(m,d) ∧ ¬global_allow_hit(d) )
   ∨  ( bl_hit(m,d)                 ∧ ¬ea_hit(m,d) ∧ ¬global_allow_hit(d) )
   ∨  ( blockIpOnly(m)              ∧ d ∉ resolved_<m> )
```

Key asymmetry: per-MAC `ea_hit` suppresses the per-MAC drops
(`blocked_macs` / `eb_` / `bl_`) but **not** `global_block`. Only
`global_allow` suppresses `global_block`. This is what makes "a profile may
not un-block a global block" true while "infra allow always wins" remains
true. `global_allow` and `@ea_` hosts are resolved by dnsmasq into
`resolved_<m>`, so both compose with `blockIpOnly` exactly as `extraAllowed`
does today (no carve-out needed because they are already resolved).

This is two flat fleet-wide ipsets, not tier logic — the router still never
sees a profile or a schedule.

### 5.3 Precedence table

For each policy dimension, who wins across global / profile / device, and why.
"Wins" = determines the enforced outcome after server-side resolution.

| Dimension | Global | Profile | Device | Resolution | Why |
|-----------|--------|---------|--------|------------|-----|
| **Infra allowlist** (`global.infraAllow`) | ✅ always carves out, beats every block incl. global block | — | — | Global wins absolutely | Security/connectivity survival surface; must never be un-reachable |
| **Global hard block** (`global.alwaysBlocked`, `global.blocklistIds`) | ✅ beats profile/device allow | cannot un-block | cannot un-block | Global wins over per-MAC allow; only infra-allow saves | Fleet-wide mandatory block (e.g. malware) a profile must not loosen |
| **Manual / pause / schedule / time-limit** (`blocked`) | infra-allow carves out | sets it | device override sets it (replace) | Device replaces profile; `extraAllowed` + infra-allow carve out | #421 — admin allow beats the MAC block |
| **`extraBlocked`** (per-MAC host block) | infra-allow carves out | profile list | device list (replace) | `extraAllowed` + infra-allow carve out; device replaces profile | #421 |
| **`extraAllowed`** (per-MAC allow) | — | profile list | device list (replace) | Beats per-MAC blocks; loses to `global_block`; device replaces profile | Allow-wins within the MAC, but cannot override a fleet mandate |
| **`blocklistIds`** (per-MAC category) | global set adds (mandatory) | profile set | device set (replace) | Per-MAC: `extraAllowed`/infra-allow carve out. Global set: only infra-allow | Profile categories are loosenable; global ones are not |
| **`blockIpOnly`** | default (loosenable) | profile value overrides default | device value overrides (replace) | Strictest applicable per-MAC value after replace; global is only a default | A profile may opt in/out of strict IP mode; global just seeds the default |
| **Default-deny baseline** | — | per-profile flag → `blocked=true` | device override may flip | Device replaces profile | Per-profile inversion of the allow-by-default model |
| **Schedules / time limits** | — | profile-evaluated → `blocked` | follows the MAC | Orthogonal `OR` into `blocked`; `extraAllowed` carves out | Time-based blocks compose with everything else |
| **`failureMode`** | — | per-profile | follows the MAC | Per-profile, unchanged | Router behaviour on poll failure (#422) |

Reading the override directions the issue asked for explicitly:

- **Infra allowlist = global always wins / always carves out.** Top of the
  ladder.
- **Global block = profile may NOT un-block.** Profile/device `extraAllowed`
  does not suppress `@global_block`; only `@global_allow` does.
- **Global default (e.g. `blockIpOnly` default) = profile may override.**
  Resolved server-side; never reaches the wire as "global."

## 6. JSON wire examples

### 6.1 Global section carried once; profiles reference it by *not* copying

```json
{
  "etag": "sha256:abc123...",
  "generatedAt": "2026-06-01T14:00:00Z",
  "global": {
    "version": "gp-7f3a91c0",
    "infraAllow": [
      "connectivitycheck.gstatic.com",
      "captive.apple.com",
      "ocsp.digicert.com",
      "ocsp.pki.goog"
    ],
    "alwaysBlocked": [],
    "blocklistIds": []
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

Note: the infra hosts appear **once** under `global`, not duplicated into the
`kids` profile's `extraAllowed` or into every device. Changing the infra
allowlist rewrites only `global` (and its `version`), not every profile entry.

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
`khanacademykids.org`, and the `global.infraAllow` hosts — and, because
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
override **replaces** the profile's rules entirely. It still inherits the
fleet-wide `global` envelope (infra allow carves out; any future global block
applies).

## 7. Curation, versioning, and audit

The global infra allowlist is a **security-sensitive bypass surface**: a host
on it is reachable from every device regardless of any block. It must be
curated, versioned, and auditable.

- **Dedicated table, not the operator's general settings.** A
  `global_infra_allow` table with audit columns (`added_by`, `added_at`,
  `reason`, soft-delete `removed_by`/`removed_at`). Edits append/soft-delete;
  history is retained.
- **Versioned.** `GlobalPolicy.version` is a content hash over the resolved
  global section; it feeds the snapshot ETag and lets the SPA show "infra
  allowlist last changed …".
- **Separate from operator-editable global blocks.** `global.alwaysBlocked` /
  global `blocklistIds` are operator policy; `infraAllow` is a tightly-curated
  connectivity-survival list. They share the wire section but have distinct
  authoring surfaces and permissions.
- **Auditable in the UI.** A read-only audit view of who changed the infra
  allowlist and why (see SPA follow-up).

## 8. Follow-up implementation issues

To be filed by the operator / orchestration session (not by this design PR):

1. **`shared` models.** Add `GlobalPolicy`, `GlobalPolicyVersion`,
   `PolicySnapshot.global`, and `MacBlockReason.DefaultDeny`; codecs; ETag
   input. Update `BlockRules.allowAll` usages as needed. (No router/SPA logic.)
2. **DB migration (schema-only PR, per the migration-isolation rule).**
   `global_infra_allow` table (audit columns + soft-delete), `global_blocks`
   table, `profiles.default_deny` column, and a household-level global
   `blocklistIds` association. Touches only small/lookup tables — not the
   unbounded-growth event tables — so it is metadata-only and safe on the
   startup path. Ships in its own PR with only `*.sql` + docs.
3. **`PolicyService` changes.** Emit the `global` section from the new tables;
   **stop** copying `uiAllowedHosts` into per-MAC `extraAllowed`
   (`PolicyService.scala:549`) and out of the unmanaged-block path
   (`:169`) — relocate to `global.infraAllow`; evaluate per-profile
   default-deny → `blocked=true` + `DefaultDeny` reason; resolve global
   defaults (e.g. `blockIpOnly`) server-side; produce merged device overrides.
   Instrument: a counter for global-section size / version changes and a gauge
   for default-deny profile count (per the "new functionality ships with
   metrics" rule) + a Grafana panel.
4. **Router carve-out (openwrt `render.lua` + agent; opnsense parity).** Add
   `@global_allow` (suppresses all drops, all MACs) and `@global_block`
   (suppressed only by `@global_allow`) ipsets; update the drop predicate per
   [§5.2](#52-stage-2--global-envelope-flat-fleet-wide); populate the global
   ipsets via dnsmasq `nftset=` callbacks; lua tests in `openwrt/test/`.
5. **SPA settings.** Global infra-allow management page with the audit view;
   global-blocks management; per-profile default-deny toggle; per-device
   override editor that reflects replace semantics (and warns that overriding a
   default-deny profile fully restates it).
6. **Retire the #1307 copy.** Once `global.infraAllow` ships, remove the
   per-profile copy introduced by
   [#1307](https://github.com/wifihaven/wifihaven/issues/1307).
7. **Tests (feature + router).** Global infra-allow carves out a `blocked=true`
   MAC; `global_block` beats profile `extraAllowed` but `global.infraAllow`
   beats `global_block`; default-deny + `blockIpOnly` reaches only
   allowed-and-locally-resolved hosts; default-deny profile collapses to
   `blocked=true` + `DefaultDeny`.

## 9. Rollout

Pre-v1.0, agent and API tandem-deploy (no compat shims). Order: ship the
`shared` shape and `PolicyService` emission first (router ignores the new
`global` section until it knows the ipsets — additive, harmless), then the
router carve-out, then retire the #1307 copy. The migration PR precedes the
`PolicyService` PR per the schema-only isolation rule.
