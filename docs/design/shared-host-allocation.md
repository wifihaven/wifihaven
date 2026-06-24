# Shared-host allocation — credit a multi-app host to the app that's demonstrably in use

Status: **Design** (Relates to [#1888](https://github.com/wifihaven/wifihaven/issues/1888);
first consumer [#1889](https://github.com/wifihaven/wifihaven/issues/1889) "Feeling Great")

## Problem

Some hosts are backends/CDNs shared by **many** apps — `api.elevenlabs.io`
(TTS), `app.launchdarkly.com` / `events.launchdarkly.com` (feature flags),
and similar vendor APIs. Today an app template's hosts attribute to that app
**unconditionally**: a host is matched to "the lowest `appId` that owns it"
([`UsageRoutes.buildUsageByApp`](../../api/src/routes/UsageRoutes.scala):498-514)
and counts toward that app's engaged-minutes via the host-set stitch
([`Presence.appSpansForProfileWithDropCount`](../../api/src/presence/Presence.scala):645-699).

So if `api.elevenlabs.io` were listed on both "Feeling Great" and some future
"Speechify" app, its traffic would be mis-credited — counted for whichever app
won the `minBy(appId)` tiebreak, or (worse, if the tiebreak changed) smeared
ambiguously. We need a host that can appear on multiple apps **without**
double-counting and **without** being credited to an app that isn't actually
being used.

## Desired behavior (operator)

> A host can be marked **shared** and listed on multiple apps, but its traffic
> is allocated to a given app **only when that app's DISTINCTIVE (non-shared)
> hosts also have traffic** for the same device/window. A shared host is
> credited to the app that's demonstrably in use (its distinctive hosts are
> active), and otherwise not credited to that app.

The motivating case: Feeling Great's distinctive host is `app.feelinggreat.com`;
its `elevenlabs` + `launchdarkly` traffic should be credited to Feeling Great
**only while `app.feelinggreat.com` is active on the same device**, not whenever
some unrelated app happens to hit elevenlabs.

## Non-goal — this is NOT the rejected byte-share / argmax-foreground heuristic

[#842](https://github.com/wifihaven/wifihaven/issues/842) /
[#715](https://github.com/wifihaven/wifihaven/issues/715) Path 1 — "pick the
single foreground app per bucket by `argmax(bytes)`" — was **rejected**
(won't-do): argmax-bytes loses background flows and credits the wrong host when
a download dominates. **This design does not resurrect it.**

The distinction is sharp and load-bearing:

| Rejected (argmax) | This design (co-presence) |
|---|---|
| Picks **one** app per bucket | Any number of apps can qualify |
| Decides by **byte magnitude** ("biggest wins") | Decides by **presence** ("is this app's own distinctive session live?") |
| A shared host is always credited *somewhere* | A shared host with no live distinctive session is credited to **none** |

A shared host attributes to app A **iff A's own distinctive hosts have a live
session overlapping the shared traffic** on the same device. There is no
magnitude comparison anywhere — we never ask "which app sent the most bytes."
Distinctive-host activity is a **presence predicate**, computed by the exact
same session-stitch primitive ([`Presence`](../../api/src/presence/Presence.scala))
that already powers [#715](https://github.com/wifihaven/wifihaven/issues/715).

---

## Template syntax — `shared_hosts:`

Today a template is a flat `hosts:` list
([`AppTemplates.parseTemplate`](../../api/src/AppTemplates.scala):128-141). We
add an **optional, parallel** `shared_hosts:` list. `hosts:` remains the app's
**distinctive** host-set; `shared_hosts:` carries the shared ones.

```yaml
slug: feeling-great
name: Feeling Great
icon: "🧠"
hosts:                       # DISTINCTIVE — uniquely identify this app's use
  - feelinggreat.com
shared_hosts:                # SHARED — backends used by many apps; credited
  - elevenlabs.io            #   to this app only while a distinctive host is active
  - launchdarkly.com
```

Rules (enforced by the loader / validation test, see sub-issue **S1b**):

1. **`shared_hosts:` is optional** and defaults to empty. Every existing
   template (which has none) is unchanged — fully backward compatible.
2. **An app must have at least one distinctive host** (`hosts:` non-empty —
   already required). A template whose host-set is *entirely* shared is
   rejected: with no distinctive host it could never be "demonstrably in use,"
   so it would receive zero attribution forever — almost certainly an
   authoring error.
3. **Global consistency:** a host that is `shared` on *any* template must be
   `shared` on *every* template that lists it. If `elevenlabs.io` is shared on
   Feeling Great but listed under plain `hosts:` on another app, that other
   app would re-introduce unconditional mis-credit. The directory-wide
   validation test fails on this inconsistency. (Distinctiveness is thus an
   emergent property of a host across the whole catalog, even though the flag
   is stored per-`(app, host)` for enforcement flexibility — see below.)
4. **No wildcards** (unchanged); the router matches subdomains inherently via
   [`HostMatch`](../../shared/src/types/HostMatch.scala).

### "Distinctive" vs "shared" — the authoring definition

- **Distinctive:** a host whose traffic, on its own, is strong evidence *this
  specific app* is in use (`app.feelinggreat.com`, `youtube.com`,
  `googlevideo.com`). If you'd be comfortable saying "traffic here = this app,"
  it's distinctive.
- **Shared:** a multi-tenant vendor API / backend that several unrelated apps
  legitimately depend on (`elevenlabs.io`, `launchdarkly.com`, generic auth/CDN
  edges). Traffic here, in isolation, tells you nothing about which app is
  running.

This is the *attribution* sibling of the existing **shared-pool collateral**
rule in [`_README.yml`](../../api/resources/app_templates/_README.yml) (don't
*block* a host whose IPs you don't own). The two reinforce each other: a host
that's shared for attribution is exactly the kind of host that's dangerous to
block. The enforcement section below makes that link explicit.

---

## Allocation rule (canonical)

All allocation is **server-side**, in the [`Presence`](../../api/src/presence/Presence.scala)
layer — the same place [#715](https://github.com/wifihaven/wifihaven/issues/715)
attribution already lives. The router/agent is unchanged and remains app-blind
(it emits `(mac, host, bytes, activeSeconds)` and knows nothing of apps).

Define, per profile-day, per device `D`:

- **`distinctiveSpans(A, D)`** — the session-stitched spans of app A computed
  over **A's distinctive hosts only** (the existing stitch +
  `continuationSeconds` gap-bridge + #1666 anchor-row guard, restricted to
  `hosts:` and excluding `shared_hosts:`).

A shared-host presence row `R = (D, host=S, [start,end])` where `S ∈
shared_hosts(A)` is **allocated to A iff** `[start,end]` **overlaps** some span
in `distinctiveSpans(A, D)`.

- "Overlaps" = temporal intersection of the row's bucket with a distinctive
  span on the **same device**. We use strict overlap, **not** the
  `continuationSeconds` gap-bridge, for the shared row: a shared host may
  *refine within* an established distinctive session but must **never extend or
  create** one. This is what guarantees "credited only while distinctive hosts
  are active." (Same-device is required — a shared row on the kid's iPad does
  not get credited because the *parent's* phone had Feeling Great open.)

### Effect on each surface

The metric of record (#715) is **engaged minutes** (session presence), not
bytes. That makes the rule clean and collapses most "double-count" worries:

1. **Engaged-minutes (per-app cap) + daily-cap exemption — shared hosts are
   excluded from the stitch entirely.** An app's engaged minutes =
   `distinctiveSpans(A, D)` summed. Because an allocated shared row overlaps a
   distinctive span by construction, the wall-clock seconds it represents are
   **already counted** in that distinctive span — including it would change the
   number by zero. So we simply do not feed `shared_hosts` into
   `appSecondsForProfile`. Consequences:
   - A shared host **never inflates** an app's time or burns/credits its cap.
   - A shared host **never extends** a session past distinctive activity.
   - No tie-break is needed on this surface: a shared row sitting inside the
     distinctive spans of two apps adds zero minutes to either.

2. **Per-host byte / proportional reporting (`buildUsageByApp`,
   [UsageRoutes](../../api/src/routes/UsageRoutes.scala):473-599) — this is
   where allocation visibly matters.** Today `appOfHost` maps each host to one
   app via `minBy(appId)`. That 1:1 assumption breaks for shared hosts. Replace
   it for shared hosts with the co-presence rule:
   - A shared host's per-row bytes / `proportionalSeconds` attribute to the
     app(s) whose `distinctiveSpans` overlap that row.
   - **Tie-break (multiple apps qualify): split equally** — each of the `N`
     co-qualifying apps gets `1/N` of the row's bytes/proportional-seconds.
     Equal split is deterministic, symmetric, introduces no double-count, and —
     critically — picks **no winner by magnitude** (not argmax). It is the only
     tie-break consistent with the non-goal above.
   - A shared row that overlaps **no** app's distinctive spans is
     **unattributed** → it surfaces in the existing `orphanHosts` / "Other"
     bucket ([UsageRoutes](../../api/src/routes/UsageRoutes.scala):569-588),
     exactly as the operator asked.

Distinctive hosts keep their current unconditional `minBy(appId)` mapping;
only `shared_hosts` rows go through the co-presence path.

### Worked example

Device D, one day. `app.feelinggreat.com` active 09:00–09:20 and 14:00–14:05.
`api.elevenlabs.io` rows at 09:05 (within span 1), 11:30 (no FG span), and
14:02 (within span 2). A "Speechify" app (distinctive `speechify.com`) is
active 11:25–11:40.

- FG engaged minutes = 25 (the two distinctive spans). elevenlabs adds 0.
- Reporting: elevenlabs 09:05 → FG; elevenlabs 14:02 → FG; elevenlabs 11:30 →
  Speechify (its distinctive span covers it).
- If both FG and Speechify were active at 11:30, the 11:30 elevenlabs bytes
  split 50/50.
- If elevenlabs fired at 03:00 with neither app active → "Other".

---

## Enforcement — the most-permissive rule for shared hosts

`shared` governs **two independent decisions**, and they are decided separately:

1. **Attribution** (usage reporting / cap) — the co-presence rule above.
2. **Enforcement** (whether a packet is allowed or dropped) — the
   **most-permissive** rule defined here.

These are not the same computation and must not be conflated. The enforcement
rule is deliberately *simpler* than attribution: it does **not** depend on
co-presence at all. It is a single principle —

> **A shared host follows the most permissive disposition of any app that lists
> it. If the app is in ALLOW, the shared host goes into `extraAllowed`. If the
> app is in BLOCK, the shared host is NOT put into `extraBlocked`.**

Concretely, for each shared host `S` and the profile's app dispositions:

- **App in ALLOW → `S` IS allowed.** Add `S` to `extraAllowed`,
  **unconditionally** (no co-presence gate). It is added whenever *any* app that
  lists `S` resolves to `Allowed` enforcement for the profile. This keeps the
  shared backend reachable so the allowed app actually works — e.g. Feeling
  Great carved around a Schedule block needs `elevenlabs.io` reachable for TTS.
- **App in BLOCK (manual block, or TimeLimited cap exhausted) → `S` is NOT
  blocked.** A shared host is **never** added to a drop set: not to a
  `Blocked`-mode app's `extraBlocked`, and not to cap-exhaustion's
  `appCapExhaustedHosts` ([PolicyService](../../api/src/policy/PolicyService.scala):878-885).
  Only the app's **distinctive** hosts are block-eligible. Blocking
  `elevenlabs.io` because Feeling Great's cap is hit would drop TTS for *every
  other app on the device* — the precise
  [#1636](https://github.com/wifihaven/wifihaven/issues/1636) collateral failure.
  The cap is still enforced — through the app's distinctive hosts — while the
  shared backend stays reachable, which is correct because it belongs to other
  apps too.

This is "most permissive" in the literal sense: allow wins, block is withheld.
Two reasons it's the right rule, not a compromise:

- **It's safe.** `extraAllowed` already **beats every block path** at the router
  (an architectural invariant — admin allow overrides `@blocked_macs` too), and
  over-allowing a shared backend breaks nothing. Over-*blocking* one is the
  collateral hazard. So permissiveness costs nothing and avoids the failure mode.
- **The router can't do better.** `extraAllowed` / `extraBlocked` are
  **unconditional per-`(mac, host)`** nftables sets (see
  [AGENTS.md](../../AGENTS.md) / [architecture §0.2](../architecture.md)). There
  is no way to express "allow `S` only while the app's distinctive hosts are
  active" — that's a stateful temporal predicate the dumb-applier router does not
  have, and the minimal-functional-shape rule forbids pushing one onto it. So the
  enforcement decision is necessarily host-level and unconditional; the most-
  permissive choice is the only safe unconditional one. The co-presence subtlety
  lives entirely in attribution, where it's a pure server-side computation.

This is the same asymmetry as the
[`_README.yml`](../../api/resources/app_templates/_README.yml) shared-pool rule
("over-allow is safe, over-block is collateral"), restated for the snapshot path.
The `allowed_during_schedule_block` flag
([#1679](https://github.com/wifihaven/wifihaven/issues/1679),
[Models.scala](../../shared/src/Models.scala):293-296) governs shared hosts
identically to distinctive ones on the allow path.

### Enforcement decision table

| App disposition (for a profile) | Distinctive hosts | Shared hosts |
|---|---|---|
| **Allowed** | `extraAllowed` | **`extraAllowed`** (unconditional) |
| **Blocked** (manual) | `extraBlocked` | **omitted** (never dropped) |
| **TimeLimited**, cap exhausted | `extraBlocked` | **omitted** (never dropped) |
| **TimeLimited**, under cap (exempt) | `extraAllowed` carve | **`extraAllowed`** if exempt-carve applies |
| not assigned / no policy | — | — |

### No wire change

`shared` is a server-side authoring + attribution property. The snapshot keeps
its existing vocabulary (`extraAllowed` reused for the allow case; nothing new
for block/report). The agent stays app-blind. This satisfies the
"snapshot is a minimal functional shape" invariant — we are not naming a new
concept on the wire, we are refining a server-side computation.

---

## Where it computes — reconciliation with #715

| Quantity | Computed in | Shared-host treatment |
|---|---|---|
| Per-app **engaged minutes** (cap, daily exemption) | `Presence.appSecondsForProfile` ([Presence](../../api/src/presence/Presence.scala):708-737) via `appSpansForProfileWithDropCount`:645-699 | shared hosts **excluded** from the group's host-set; minutes = distinctive stitch only |
| Per-host **proportionalMins / bytes** (UI usage-by-app) | `buildUsageByApp` ([UsageRoutes](../../api/src/routes/UsageRoutes.scala):473-599); `Presence.proportionalHostSeconds`:803-833 | shared host row → co-presence overlap with `distinctiveSpans` → split among qualifiers → else "Other" |
| `extraBlocked` / `extraAllowed` snapshot | `PolicyService.computeBlockRules` ([PolicyService](../../api/src/policy/PolicyService.scala):771-855) | **most-permissive**: shared hosts never enter `extraBlocked`; added to `extraAllowed` (unconditional) when the app is in ALLOW |

The single-source-of-truth contract ([AGENTS.md §single-source-of-truth](../process/single-source-of-truth.md))
is preserved: `distinctiveSpans` and the span→seconds projection still live in
exactly one place in `Presence`; the shared-host allocation is a new consumer of
the **same** stitch primitive, not a second time-accounting implementation. We
do **not** re-derive engaged time anywhere — the co-presence test reuses the
spans the cap surface already produced.

The groups fed to `Presence` come from `AppTimeLimitRepoLive.listForProfile`
([Repos.scala](../../api/src/db/Repos.scala):1028-1042). That join must start
returning the per-`(app,host)` `shared` flag so `Presence` can partition each
app's host-set into distinctive vs shared. This is the plumbing in sub-issue
**S2**.

---

## Edge cases

| Case | Behavior |
|---|---|
| Shared host active, **no** app's distinctive hosts active | Credited to **none**; surfaces in `orphanHosts`/"Other". Zero minutes anywhere. |
| Two apps' distinctive hosts both active, both list the shared host | Minutes: no-op for both (already inside each distinctive span). Reporting bytes: **split equally** among qualifiers. |
| App whose host-set is **entirely** shared (no distinctive host) | **Rejected at load** (validation S1b) — could never be demonstrably-in-use. |
| Same host `shared` on app A, distinctive on app B | **Rejected at load** (global-consistency check S1b) — would re-introduce unconditional mis-credit on B. |
| Shared host on a `Blocked`-mode app | Not blocked (collateral protection). Reporting still credits it to A while A's distinctive hosts are active (rare, since A's distinctive hosts are themselves dropped when blocked). |
| Shared host on an `Allowed`-mode app under a Schedule block | Allowed unconditionally so the app works (subject to `allowed_during_schedule_block`). |
| Shared host shares IPs with collateral (DNS-cache miss → IP-only attribution) | Same collateral guidance as `_README.yml`; shared hosts are never blocked, so the [#1636](https://github.com/wifihaven/wifihaven/issues/1636) failure mode cannot occur via the shared path. |

---

## Phased implementation (dependency-ordered sub-issues)

Ordered foundation-first; each ships with tests per [TDD](../process/tdd.md) and
runs `/pr-review` before merge.

- **S1a — Migration: `app_hosts.shared` column** ✅ shipped
  ([#1895](https://github.com/wifihaven/wifihaven/issues/1895),
  `V60__app_hosts_shared.sql`) — schema-only PR per
  [migrations](../process/migrations.md#migrations-back-compat).
  `ALTER TABLE app_hosts ADD COLUMN shared BOOLEAN NOT NULL DEFAULT FALSE;`
  Existing rows default distinctive. No source/test in this PR — the existing
  suite is the back-compat gate. The flag is stored and ignored until S1b
  adopts it.

- **S1b — Template syntax + parser + seeder + validation** (depends on S1a).
  Add `sharedHosts: List[Hostname]` to `AppTemplate`
  ([AppTemplates.scala](../../api/src/AppTemplates.scala):46-52); parse
  `shared_hosts:`; seeder writes the `shared` flag through
  `AppRepo.setHosts` / `getHosts`; `_README.yml` documents the field;
  directory-wide validation test enforces (a) ≥1 distinctive host, (b)
  global shared/distinctive consistency. **No behavior change yet** — flag is
  stored and ignored by attribution.

- **S2 — Engaged-minutes excludes shared hosts** (depends on S1b). Plumb the
  `shared` flag from `AppTimeLimitRepoLive.listForProfile`
  ([Repos.scala](../../api/src/db/Repos.scala):1028-1042) into the group
  construction so `Presence.appSecondsForProfile` stitches **distinctive hosts
  only**. **Acceptance: expose `distinctiveSpans(A, D)` as a reusable `Presence`
  primitive** (the per-app, per-device distinctive-host span list) — S3 consumes
  the *same* spans for the co-presence overlap test. S3 must NOT re-derive them
  (that would be a single-source-of-truth violation per
  [AGENTS.md §single-source-of-truth](../process/single-source-of-truth.md)).
  Tests pin: shared host inside a distinctive span → 0 added minutes; shared
  host with no distinctive activity → not credited.

- **S3 — Per-host reporting allocation** ✅ shipped
  ([#1898](https://github.com/wifihaven/wifihaven/issues/1898)). In
  `buildUsageByApp` ([UsageRoutes](../../api/src/routes/UsageRoutes.scala)), shared
  hosts route through `Presence.allocateSharedHostSeconds` — co-presence overlap
  with each candidate app's distinctive spans (the S2
  `TimeStatusService.distinctiveSpansByApp` seam, now mode-agnostic so an Allowed
  app earns its shared backends too) + equal split among qualifiers + "Other"
  fallback — replacing `minBy(appId)` for shared hosts only; distinctive hosts keep
  the unconditional `minBy`. The by-app axis builder (`loadAppLookup`) excludes
  shared hosts from both `appOf` and `patternsBySlug` so the per-app *time-series*
  span stays distinctive-only (reconciling with the S2 cap); a shared host surfaces
  there as a standalone host entry. No argmax / byte-share anywhere. Ships with the
  bounded-label `usage_shared_host_attribution_total{outcome}` counter
  (`attributed` / `split` / `other`) and its Grafana panels on the
  `data-quality-ingest` dashboard.

- **S4 — Enforcement guardrails** (depends on S1b; parallel to S2/S3).
  Exclude shared hosts from `appCapExhaustedHosts` and `Blocked`-mode
  `extraBlocked` in `PolicyService.computeBlockRules`
  ([PolicyService](../../api/src/policy/PolicyService.scala):771-885); keep the
  unconditional allow path for shared hosts on `Allowed`-mode apps. Tests pin:
  cap exhaustion never drops a shared host; allow-mode shared host carves.

- **S5 — Feeling Great app, first consumer**
  ([#1889](https://github.com/wifihaven/wifihaven/issues/1889), depends on
  S1b). Author `feeling-great.yml`: distinctive `feelinggreat.com` (+ any
  Feeling-Great-specific apexes discovered from prod
  `connection_events`), `shared_hosts: [elevenlabs.io, launchdarkly.com]`
  (apex form — covers `api.elevenlabs.io` / `events.launchdarkly.com` etc.
  inherently via [`HostMatch`](../../shared/src/types/HostMatch.scala), per
  `_README.yml`; do NOT list the API subdomains separately);
  register in `_index.yml`; run the template validation tests. This is the
  acceptance demonstration that elevenlabs/launchdarkly coexist on the catalog
  without mis-credit.

S1a → S1b is the only hard serialization at the head; S2, S3, S4 fan out after
S1b (S3 depends on S2 for the shared distinctive-span helper); S5 needs only
S1b to ship the template and S2/S3 to demonstrate correct attribution.
