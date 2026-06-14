# Per-app schedules — design

Tracking: [#1376](https://github.com/wifihaven/wifihaven/issues/1376).
Status: **design only** — this document specifies the feature; no code ships
with it.

## 1. Goal

Let an individual **app** (a household-scoped bundle of host patterns; see
[#761](https://github.com/wifihaven/wifihaven/issues/761)) carry its own time
window, layered on top of the profile's general schedule. The window means one
of two things:

- **Allowed during W** — the app stays reachable while W is active, even when
  the profile is otherwise in scheduled downtime (the headline case: an
  educational app reachable during bedtime).
- **Blocked during W** — the app is dropped while W is active, even when the
  profile is otherwise unrestricted (e.g. a game blocked during homework
  hours).

This extends the existing schedule abstraction — today a profile-wide downtime
window — down to the per-app layer.

## 2. The hard constraint, and why it holds

**API-logic only. No snapshot/wire change. No router change.** Per-app
schedules are a `PolicyService` feature that collapses, at snapshot-compute
time, into the *existing* per-MAC `BlockRules` fields. The router stays a dumb
applier (AGENTS.md "Architectural model" §0.2): it never learns that schedules
exist, per-profile or per-app.

The mapping is exact and uses only fields the wire already carries:

| App window state at instant `now` | Effect on the per-MAC `BlockRules` |
|---|---|
| `allowed_during W`, W active | app's hosts → **`extraAllowed`** (beats every whole-MAC block per [#421](https://github.com/wifihaven/wifihaven/issues/421)) |
| `allowed_during W`, W inactive | app contributes nothing from the window; falls back to its base mode |
| `blocked_during W`, W active | app's hosts → **`extraBlocked`** |
| `blocked_during W`, W inactive | app contributes nothing from the window; falls back to its base mode |

`extraAllowed` / `extraBlocked` already enforce per-`(MAC, host)` at the router
(`ea_` / `eb_` ipsets populated from resolved IPs). The snapshot's wire
vocabulary is untouched: `blocked`, `blockReason`, `extraAllowed`,
`extraBlocked`, `blocklistIds`, `blockIpOnly`. **No new field, no new reason on
the wire.** This is the same discipline #763 used to expand `AppMode` into the
existing buckets — per-app schedules are just another input to that
server-side collapse.

> **Confirmed: every case is expressible in the current fields.** There is no
> case in this feature that forces a wire change — including the subtle one,
> "reachable during downtime **but still** subject to the daily time-limit cap."
> That case *is* expressible, because the server decides whether to emit the
> `extraAllowed` carve at all: it withholds the carve for a non-exempt app whose
> daily cap is exhausted, so the cap still bites without the router ever knowing
> why. See §5 (rows 9a–9c) for the full decision.

## 3. Data model

### 3.1 Reuse the existing `Schedule` representation

A per-app window reuses the exact shape of the profile `schedules` table and
the `Schedule` domain model in `shared/src/Models.scala`:

- `days: List[String]` — day-of-week set (`["Mon","Tue",...]`).
- `startLocal` / `endLocal` — wall-clock `HH:MM` in **household-local time**.
- Overnight wrap (`startLocal > endLocal`), DOW membership, and the tail day
  are all handled by the *already-existing* `PolicyService.scheduleActiveAt`
  and `scheduleEndInstantAfter`. We do not write new time math.
- **Each window carries its own IANA `tz`, never `java.time` directly.** The V1
  `schedules` table stores a per-row `tz` (added in V16), and the #1069
  `schedule_windows` child table mirrors it per window, so the `Schedule.tz:
  ZoneId` is read straight off the row. All "is W active now?" evaluation goes
  through the injected `wifihaven.shared.Clock` (`Clock.instant`) projected into
  that zone, exactly as profile schedules do today. DST is handled transparently
  by `ZonedDateTime` (see PolicyService §"#334 timezone-aware time math").

Because we reuse `scheduleActiveAt`, per-app windows inherit overnight-wrap,
DOW, and DST correctness for free, and the schedule-boundary unit tests can
reuse the existing fixtures.

### 3.2 Named schedules land first — references only, no inline windows

**Sequencing decision: [#1069](https://github.com/wifihaven/wifihaven/issues/1069)
(household-scoped reusable named schedules) lands before per-app schedules, and
per-app schedules reference a named schedule exclusively.** No inline
`{days, from, until}` table is introduced.

Rationale:

- #1069 already proposes the household named-schedule table (named, with a
  typed `schedule_windows` child table supporting compound time blocks) **and**
  an `app_policy_assignments.schedule_id` reference. Building per-app schedules
  on that foundation avoids inventing a second, parallel inline-window
  representation that would later have to be migrated into the named model
  anyway.
- A one-off window is just a named schedule the operator creates ad hoc
  ("Math homework block"). The reusable-named primitive subsumes the inline
  case, so "reference a named schedule, not only inline windows" (the #1376
  ask) is satisfied by *named-only* once #1069 exists — cleaner than carrying
  both, with no `CHECK`-enforced either/or column pair.
- A named schedule's `schedule_windows` rows already cover the compound case
  (e.g. "school hours: weekdays 8–15, Fri 8–12"), so per-app schedules inherit
  multi-window support for free without their own one-to-many window rows.

This also lines up with **#1067** (schedule-driven blocklist activation), which
references the same named schedules — all three (profile schedule, per-app
schedule, blocklist activation) converge on the one #1069 primitive.

### 3.3 What #1069 gives us, and what #1376 adds

An app needs two things from a schedule reference:

1. a **mode** — *allowed during* vs *blocked during*; and
2. the ability to attach **more than one** (schedule, mode) pair to a single app
   (e.g. *allowed during* Bedtime **and** *blocked during* Homework).

So #1376 adds a small child table of `(assignment, named-schedule, mode)`
rules. Because #1069 lands first, the FK target already exists and the FK is
created directly — no deferred-constraint dance.

> **#1069 landed (`V50__named_schedules.sql`).** The new household-scoped
> primitive is the **`named_schedules`** table (parent: id, name, description,
> timestamps) plus a typed **`schedule_windows`** child table (`schedule_id`,
> `days TEXT[]`, `start_local TIME`, `end_local TIME`, `tz TEXT`) — mirroring
> the V1 `schedules` time columns so the existing `Schedule` model and
> `scheduleActiveAt` apply unchanged. The name is `named_schedules`, NOT
> `schedules` — the spec's chosen name collided with the V1 profile-scoped
> `schedules` table, which deployed enforcement code still read at the time, so
> it could not be dropped/renamed in an additive migration.
>
> **#1482 made `named_schedules` the single enforcement source.** The boot-time
> `ScheduleSeeder` migrates each profile's legacy `schedules` rows into the named
> model, and `PolicyService` / `TimeStatusService` now read schedule downtime
> **exclusively** from `named_schedules` / `profile_schedule_rules` (the legacy
> union is gone). The V1 `schedules` table still exists — it backs the legacy
> profile-CRUD/display surface and keeps the pre-migration image enforcing on a
> rollback — but it is no longer an enforcement source. Retiring it is staged as
> **two** further PRs, honouring the migration-isolation rule: first a code/test
> PR (#1709) that removed the now-dead legacy `ScheduleRepo` (its repo, the
> profile-upsert `schedules` write/read, the `@unused` injection, and the
> fixtures that seeded it), then a migration-only PR (#1485) that drops
> the table.
>
> **Profiles reference schedules through a `(profile, schedule, mode)` join
> table — `profile_schedule_rules` — NOT a single `profiles.schedule_id`
> column.** #1069 deliberately dropped the single-reference columns its spec
> sketched (`profiles.schedule_id`, `app_policy_assignments.schedule_id`):
> they can't carry a mode and cap a profile at one schedule. The
> `app_policy_schedule_rules` table below is the **exact same shape** for apps,
> FKing **`named_schedules(id)`**, and is the **next** free version after V50
> (e.g. `V51__app_policy_schedule_rules.sql`). The two tables are intentionally
> identical — profiles and apps share one (entity, schedule, mode) model.

New migration (next free version after #1069's `V50`, e.g.
`V51__app_policy_schedule_rules.sql`, sequenced *after* #1069's migration):

> **Landed (`V51__app_policy_schedule_rules.sql`, #1378).** The table below
> ships as the schema-only foundation PR, FKing `app_policy_assignments(id)`
> (V28) and the #1069 `named_schedules(id)` (V50). It mirrors V50's
> `profile_schedule_rules` exactly — same `(entity, schedule, mode)` shape, same
> two indexes (by assignment for the per-app fold, by schedule for the
> cascade-delete probe). Inert until #1379 adds the PolicyService evaluation.

```sql
CREATE TABLE app_policy_schedule_rules (
  id             BIGSERIAL PRIMARY KEY,
  assignment_id  BIGINT NOT NULL
                   REFERENCES app_policy_assignments(id) ON DELETE CASCADE,
  schedule_id    BIGINT NOT NULL
                   REFERENCES named_schedules(id) ON DELETE CASCADE,  -- #1069 named schedule
  mode           TEXT NOT NULL
                   CHECK (mode IN ('allowed_during','blocked_during')),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (assignment_id, schedule_id, mode)
);
CREATE INDEX idx_app_policy_schedule_rules_assignment
  ON app_policy_schedule_rules(assignment_id);
```

**Migration-isolation (AGENTS.md "Schema changes land in their own PR").** This
is a brand-new table referencing only tables that already exist once #1069 has
landed, so:

- It is trivially backward-compatible with image-(N-1): the old image's queries
  never touch `app_policy_schedule_rules`, so the existing `api.test` suite
  (which applies every migration including this one against image-(N-1) code)
  passes unchanged — that is the gate.
- The migration PR contains **only** the `V51__….sql` plus doc updates. No
  source, no tests, no fixtures. The PolicyService eval, repo methods, and tests
  land in the follow-up PR (§"Sub-issues").
- **Not a growth table** ("Migrations that are fast on dev … minutes-long on
  prod"): `app_policy_schedule_rules` is bounded by `apps × profiles × rules`
  (tens of rows), not by event volume. The migration is metadata-only — safe on
  the startup critical path.

### 3.4 Domain model (`shared/src/Models.scala`, follow-up PR — API-internal)

These are API-internal models consumed by `PolicyService`; **none of them is a
`PolicySnapshot` field** — they never reach the wire.

```scala
enum AppScheduleMode { case AllowedDuring, BlockedDuring }

case class AppScheduleRule(
    id: AppScheduleRuleId,
    assignmentId: AppPolicyAssignmentId,
    scheduleId: ScheduleId,        // #1069 named schedule
    mode: AppScheduleMode,
)
```

At evaluation time each `AppScheduleRule` resolves its referenced named
schedule to that schedule's window list (#1069 `windows`), the household `tz` is
injected, and `PolicyService.scheduleActiveAt` is applied **per window** — the
rule is "active at `now`" iff any of the named schedule's windows is active. The
`UpsertAppAssignmentRequest` gains an additive
`scheduleRules: List[AppScheduleRule] = Nil` field (back-compatible default).

## 4. PolicyService evaluation

### 4.1 Per-app effective disposition (the key step)

Today the snapshot builder (`PolicyService.snapshot`, ~L116–130) buckets each
assignment by its static `mode` into `appAllowedHosts` / `appBlockedHosts`,
then `computeBlockRules` folds those into `extraAllowed` / `extraBlocked`.

Per-app schedules insert one step **before** bucketing: resolve each
assignment to a single **effective disposition at `now`**, so a window
*overrides* the base mode for that app rather than blindly unioning into both
buckets. This matters because a naive union would let the router's
allow-beats-block rule defeat a `blocked_during` window on a base-`Allowed`
app.

A rule is "active at `now`" iff its referenced named schedule (#1069) has any
window active at `now` (§3.4).

```
effectiveDisposition(assignment, scheduleRules, now):
  if any allowed_during rule active at now   -> AllowedDuring  // carve-out CANDIDATE (see gate)
  else if any blocked_during rule active      -> Blocked
  else                                        -> base mode      // Allowed / Blocked / TimeLimited
```

- **`allowed_during` wins over `blocked_during`** when an app has overlapping
  windows of both kinds active simultaneously. This is consistent with the
  system-wide "allow beats block" invariant (#421) and with the headline
  intent ("keep this reachable"). Overlapping same-app allowed+blocked windows
  are a config oddity the SPA should discourage; the tiebreak is defined so the
  outcome is never ambiguous.
- The base mode still applies whenever no window is active — outside every
  window, the app behaves exactly as it does today.

**The `AllowedDuring` carve is gated by the daily-cap / exempt check (§5).**
Placing the app's hosts in `extraAllowed` beats *every* whole-MAC block at the
router (#421), so whether the app survives the daily time limit is decided
*here*, server-side, by whether we carve at all — not by the router:

```
carveIntoExtraAllowed(assignment) =
  effectiveDisposition == AllowedDuring
  AND NOT (dailyCapExhausted(state) AND NOT assignment.exemptFromDaily)

dailyCapExhausted(state) =
  state.dailyLimitMinutes.exists(lim => state.usedMinutes >= lim + state.extensionMinutes)
  // equivalently state.remainingMinutes.contains(0)
```

`dailyCapExhausted` is computed **directly from `ProfileDayState`**, independent
of the collapsed `blockReason` — important because when schedule downtime and
cap exhaustion coincide, `blockReason` reports `Schedule` (higher precedence)
yet the budget is still exhausted. The window must still beat the *schedule*
block while *yielding* to the *budget* block for a non-exempt app, so the carve
gate keys off the raw cap condition, not the reason.

After this gate, the existing bucketing is unchanged:
`AllowedDuring (carved) / base Allowed → appAllowedHosts (→ extraAllowed)`,
`Blocked → appBlockedHosts (→ extraBlocked)`,
`TimeLimited → site-limit path (unchanged, #764)`.
An `AllowedDuring` candidate that fails the gate is *not* added to either
bucket — it simply remains subject to the profile's whole-MAC block (it is
blocked because the cap is exhausted, which is the intent).

Cross-app host collisions continue to resolve allow-wins at the router exactly
as #763 documents — that behaviour is untouched.

### 4.2 `decide` fallback must mirror it

The per-host fallback (`PolicyService.decide`, ~L216) independently re-derives
`appAllowed` / `appBlocked` from the assignments. It must apply the same
per-app effective-disposition resolution **and the same cap gate** so the
fallback agrees with the snapshot. Note its current precedence chain — "allowed-app >
paused > schedule > blocked-app > site_time_limit > time_limit > category"
(allowed-app moved to the head of the chain in #1413 so `extraAllowed` beats the
Paused/Schedule whole-MAC blocks too, matching the router carve-out) — places
allowed-app *above* time_limit unconditionally; that must change so a
*non-exempt* `allowed_during` app falls **below** time_limit (cap bites), while
an *exempt* one stays above it. Concretely: an active `allowed_during` window
short-circuits to Allow only when `exemptFromDaily` OR the cap is not yet
exhausted; otherwise it does not, and the time_limit branch decides. This is the
per-host mirror of the §4.1 `carveIntoExtraAllowed` gate.

### 4.3 Interaction with the #1105 exempt-app carve-out

The existing `appExemptAllowedHosts` path (time_limited apps with
`exemptFromDaily=true` and remaining budget → `extraAllowed`) is orthogonal and
unchanged. An `allowed_during` window reuses the **same `exemptFromDaily` flag**
to decide cap-immunity (§4.1 gate, §5): the window governs the *schedule* axis
(which apps are reachable during a downtime window), while `exemptFromDaily`
governs the *budget* axis (whether the daily cap binds the app). They are
deliberately the same knob #1105 already exposes — the window does not invent a
second, implicit form of cap-immunity. Both paths feed `extraAllowed`; the
union is harmless.

## 5. Precedence / interaction table

Evaluated server-side, per `(MAC, app, host)` at instant `now`. "Whole-MAC
block" = `blocked=true` (one of Paused / profile-Schedule / TimeLimit / Manual
— all collapse to `@blocked_macs` at the router). Outcome is what the router
enforces after #421 allow-beats-block.

"Exempt?" is the assignment's `exemptFromDaily` flag. The `AllowedDuring`
carve gate (§4.1) is `windowActive AND NOT (dailyCapExhausted AND NOT exempt)`.

| # | Profile whole-MAC block? | App base mode | App window @ now | Exempt? | App host bucket | **Router outcome for the app** |
|---|---|---|---|---|---|---|
| 1 | Schedule downtime active | any | `allowed_during` active | n/a | `extraAllowed` | **Reachable** (carve-out beats downtime) — the headline case |
| 2 | none | any | `allowed_during` active | n/a | `extraAllowed` | Reachable (no-op carve-out; already allowed) |
| 3 | none | Allowed | `blocked_during` active | n/a | `extraBlocked` | **Blocked** (window overrides base Allowed, §4.1) |
| 4 | none | Blocked | `blocked_during` active | n/a | `extraBlocked` | Blocked (consistent) |
| 5 | Schedule downtime active | any | `blocked_during` active | n/a | `extraBlocked` | Blocked (already blocked; window redundant but valid) |
| 6 | none | Allowed | no active window | n/a | `extraAllowed` | Reachable (base mode) |
| 7 | none | Blocked | no active window | n/a | `extraBlocked` | Blocked (base mode) |
| 8 | Schedule downtime active | Allowed | no active window | n/a | `extraAllowed` | Reachable — note base `Allowed` *already* carves out of downtime today |
| 9a | **TimeLimit reached** | any | `allowed_during` active | **yes** | `extraAllowed` | **Reachable** — exempt app survives the cap (see decision) |
| 9b | **TimeLimit reached** | any | `allowed_during` active | **no** | *(not carved)* | **Blocked** — non-exempt app: the daily cap still bites (see decision) |
| 9c | Schedule downtime **and** TimeLimit reached | any | `allowed_during` active | no | *(not carved)* | **Blocked** — cap binds even though `blockReason` reports `Schedule`; gate keys off raw cap, not reason (§4.1) |
| 10 | Manual block | any | `allowed_during` active | n/a | `extraAllowed` | Reachable (Manual is a whole-MAC block, not a budget; carve-out beats it) |
| 11 | Paused | any | `allowed_during` active | n/a | `extraAllowed` | Reachable (Paused is a whole-MAC block, not a budget; carve-out beats it) |

### Decision: "allowed during W" yields to the daily time limit unless the app is exempt (rows 9a–9c)

**An app with an active `allowed_during` window stays reachable past the
profile's daily time limit *only if* its assignment is `exemptFromDaily`. A
non-exempt app, once the daily cap is exhausted, is blocked — window or no
window.**

Justification:

1. **It is a server-side choice, not a wire constraint.** Yes, `extraAllowed`
   beats `@blocked_macs` unconditionally at the router (#421) regardless of the
   block reason. But *whether the host is in `extraAllowed`* is decided by
   `PolicyService`, which has `ProfileDayState` (used / limit / extensions) and
   the assignment's `exemptFromDaily`. So the server simply withholds the carve
   when the cap is exhausted and the app is not exempt. No new wire field, no
   router change — the cap can still bite. (My earlier framing of this as
   "wire-forced" was wrong: the router can't tell reasons apart, but the server
   never has to put the host on the wire in the first place.)
2. **It keeps two axes separate.** A window is a *schedule* layer — which apps
   are reachable during a downtime window. The daily time limit is a *budget*
   layer — total screen time. "Educational app reachable during bedtime" should
   not silently also mean "reachable after you've burned your whole daily
   allowance." Conflating them would let any `allowed_during` window become an
   accidental, unlimited cap bypass.
3. **It reuses the existing knob.** `exemptFromDaily` already means exactly
   "this app isn't bound by the daily budget" (#1105, for time_limited apps).
   An `allowed_during` window that needs cap-immunity sets the same flag; one
   that should respect the cap leaves it `false`. No second, implicit form of
   cap-immunity is invented. (Manual / Paused / Schedule blocks are *not*
   budgets, so the window beats them irrespective of `exemptFromDaily` — rows 1,
   10, 11.)

This is the **one place** the issue asked to resolve explicitly, and it is
resolved *within* the existing fields — not as a wire-change exception.

### Default-deny (#1316–#1322)

Per-app schedules compose cleanly with default-deny because they only ever
produce `extraAllowed` / `extraBlocked` — the exact vocabulary default-deny
also speaks:

- Under a default-deny profile, an `allowed_during` window adds the app's hosts
  to `extraAllowed` while W is active → reachable during W; outside W the
  default-deny baseline drops them (unless another allow covers). This is the
  natural and desirable behaviour.
- A `blocked_during` window under default-deny is usually redundant (the
  baseline already blocks) but remains valid for an app that some *other* allow
  would otherwise permit.
- Per-app schedules are **per-profile** and feed the profile's `extraAllowed`/
  `extraBlocked`. The global policy layer's `global.extraAllowed`/`extraBlocked`
  (#1316) is a separate, higher tier evaluated independently; per-app schedules
  do **not** write the `global` section. Global-allow still beats everything,
  per that layer's own precedence — unchanged here.

### Category blocklists

`blocklistIds` drop is a per-`(MAC, blocklist)` ipset drop. `extraAllowed`
beats it (#421), so an `allowed_during` window also carves a category-blocked
host out while W is active — same mechanism as a static allowed-mode app today.
A `blocked_during` window adds to `extraBlocked`, independent of categories.

## 6. Snapshot-freshness implications

**The snapshot is computed on-demand on every poll using `Clock.instant`;
there is no precomputed boundary scheduler.** Each `GET /api/router/policy`
re-evaluates `scheduleActiveAt(window, now)` for the current instant, rebuilds
`extraAllowed` / `extraBlocked`, recomputes the deterministic `etag`
(`blockRulesSig` already folds `ea`/`eb` into the ETag — see PolicyService
`computeEtag`/`blockRulesSig`), and returns `200` with the fresh body or `304`
if nothing changed.

Consequences for per-app schedules:

- **Correctness is automatic.** The first poll after a window edge (≤ one poll
  interval, ~60 s — architecture.md §5) recomputes activity and flips the
  affected MAC's `extraAllowed`/`extraBlocked` and ETag. Worst-case staleness is
  one poll interval, identical to profile schedules today. No new mechanism is
  needed — per-app schedules ride the *same* pull-based freshness path profile
  schedules already rely on.
- **No per-poll compute increase.** Every poll already recomputes the whole
  snapshot; adding window evaluation is a handful of `scheduleActiveAt` calls
  per assignment. Negligible at single-household scale.
- **What does grow: the number of distinct ETag-flip instants per day.** Today
  a MAC's ETag flips at profile-schedule edges, midnight reset, and time-limit
  exhaustion. Per-app windows add up to **2 edges per window per profile**. The
  only effect is that the router receives a few more `200`s (vs `304`s) across
  the day — more full-snapshot transfers, not more work per poll. Fine for the
  single-household deployment.
- **Flag for the future push channel.** Architecture.md §5 notes a possible
  server-push (websocket/SSE) channel for instant blocks. *That* design would
  need an explicit "next boundary" scheduler to know when to push; per-app
  windows enlarge that wakeup set (every app-window edge becomes a wake
  instant). The existing `scheduleEndInstantAfter` is the building block for
  computing those edges. This is **out of scope for #1376** — pull-based
  freshness needs nothing — but should be called out when the push channel is
  designed.

## 7. SPA configuration surface (specify, don't build)

In the app/profile assignment editor, each app's assignment row gains a
**Schedules** section:

- An **add-rule** control producing one or more rule rows (one-to-many,
  matching the data model).
- Per rule: a **mode** toggle — *Allowed during* / *Blocked during* — plus a
  **named-schedule picker** (the #1069 picker; "Bedtime", "School hours", or
  create-new inline from the same control). No bespoke time editor here — the
  named-schedule primitive owns day/time editing, and a one-off is just a new
  named schedule. Times are household-local; the picker shows the household
  zone.
- **Autosave** ([#995](https://github.com/wifihaven/wifihaven/issues/995) /
  [#423](https://github.com/wifihaven/wifihaven/issues/423)): debounced PATCH of
  the assignment, no explicit Save button, consistent with the autosave-default
  preference. The request shape extends `UpsertAppAssignmentRequest` additively
  with `scheduleRules: List[AppScheduleRule]` (default `Nil`, so existing
  clients keep working).
- The editor should surface the §5 semantic plainly. A hint on `allowed_during`
  should read like "this app stays reachable during this window even during
  bedtime" — and, because cap-immunity is governed by the assignment's
  `exemptFromDaily` flag (rows 9a–9c), the editor should show that flag
  alongside the window with copy such as "still counts against / is blocked by
  the daily time limit when off" vs "exempt from the daily time limit." This
  makes the exempt-vs-non-exempt distinction explicit at config time so the
  cap-bites-non-exempt behaviour is not a surprise.

This is specified for a web sub-issue to pick up; it is not built here.

## 8. Architecture.md note

A short note is added to `docs/architecture.md` under the "Architectural model"
/ snapshot-freshness discussion, reaffirming that per-app schedules are a
server-side `PolicyService` collapse into the existing `extraAllowed` /
`extraBlocked` fields with **no wire or router change**, and that the router
remains a dumb applier. (Added in the same PR as this design doc.)

## 9. Sub-issues (filed under this epic — Schedules)

**Prerequisite: [#1069](https://github.com/wifihaven/wifihaven/issues/1069)
(named schedules) lands first.** Per-app schedules reference the #1069
`schedules` table directly (§3.2–3.4), so every issue below is **blocked on
#1069**. Filed as native sub-issues of the #1376 epic, Epic = `Schedules`.

In dependency order, honouring the migration-isolation two-PR split:

1. **DB migration (schema-only PR).** `app_policy_schedule_rules` per §3.3
   (FK to the #1069 `schedules` table + `mode`), plus doc updates only. No
   source, no tests. Gate: existing `api.test` passes against image-(N-1) code
   with the new table present. Blocked on #1069's migration.
2. **PolicyService eval + repo + models + tests (follow-up PR, TDD).** Add
   `AppScheduleRule` / `AppScheduleMode` to `shared/Models.scala`; repo method
   to load rules per assignment; the §4.1 effective-disposition resolution +
   cap gate in **both** `PolicyService.snapshot` and `PolicyService.decide`;
   extend `UpsertAppAssignmentRequest` additively. Confirm **no**
   `PolicySnapshot` field is added. Ships with its tests per TDD:
   - *Schedule-boundary unit tests* — exact on/off edges, overnight wrap, DOW
     membership, DST transition, `allowed_during`-beats-`blocked_during`
     tiebreak, multi-window named-schedule resolution, and the cap gate
     (`carveIntoExtraAllowed` true for exempt-over-cap, false for
     non-exempt-over-cap, true for either under cap). `Clock.TestClock`
     fixtures; never `java.time` directly.
   - *Feature tests* — `extraAllowed` carve during downtime (row 1); the
     cap-gate split exempt (9a) vs non-exempt (9b); coincident schedule+cap
     non-exempt (9c); `extraBlocked` during a `blocked_during` rule (row 3);
     ETag flips across a window edge.
   Blocked on issue 1.
3. **Web UI.** The §7 assignment-editor Schedules section: per-rule mode toggle
   (*Allowed during* / *Blocked during*) + named-schedule picker (the #1069
   picker), the `exemptFromDaily` flag surfaced alongside, autosave (#995/#423).
   Additive `scheduleRules` on the assignment payload. Blocked on issue 2.

## 10. Cross-references

- [#1376](https://github.com/wifihaven/wifihaven/issues/1376) — this epic.
- [#421](https://github.com/wifihaven/wifihaven/issues/421) — `extraAllowed`
  beats every block (the mechanism the whole mapping rests on).
- [#763](https://github.com/wifihaven/wifihaven/issues/763) /
  [#764](https://github.com/wifihaven/wifihaven/issues/764) — app → per-MAC
  bucket expansion; the path this feature extends.
- [#1105](https://github.com/wifihaven/wifihaven/issues/1105) — exempt-app
  carve-out; the precedent for "allowed beats the cap."
- [#1069](https://github.com/wifihaven/wifihaven/issues/1069) — reusable named
  schedules (referenced, not required).
- [#1067](https://github.com/wifihaven/wifihaven/issues/1067) —
  schedule-driven blocklist activation (sibling "schedule drives an effect").
- [#937](https://github.com/wifihaven/wifihaven/issues/937),
  [#1316](https://github.com/wifihaven/wifihaven/issues/1316)–[#1322](https://github.com/wifihaven/wifihaven/issues/1322)
  — global policy + default-deny; per-app schedules compose underneath.
- [#995](https://github.com/wifihaven/wifihaven/issues/995) /
  [#423](https://github.com/wifihaven/wifihaven/issues/423) — autosave + PATCH.
- [#376](https://github.com/wifihaven/wifihaven/issues/376) — wire-versioning
  gate (why no wire change is on the table).
