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
- **Timezone comes from household settings at read time, never `java.time`
  directly.** Profile schedules already get their `tz: ZoneId` injected when
  the `Schedule` is constructed from the row (the `schedules` table stores no
  zone column); per-app windows follow the same path. All "is W active now?"
  evaluation goes through the injected `wifihaven.shared.Clock` (`Clock.instant`)
  projected into that zone, exactly as profile schedules do today. DST is
  handled transparently by `ZonedDateTime` (see PolicyService §"#334
  timezone-aware time math").

Because we reuse `scheduleActiveAt`, per-app windows inherit overnight-wrap,
DOW, and DST correctness for free, and the schedule-boundary unit tests can
reuse the existing fixtures.

### 3.2 Inline windows *and* named-schedule references (#1069)

The feature must support **both**:

- **Inline window** — `days` + `from` + `until` stored directly on the per-app
  schedule row. Available now; #1069 is not yet built.
- **Named-schedule reference** ([#1069](https://github.com/wifihaven/wifihaven/issues/1069)) —
  a foreign key to a household-scoped reusable schedule ("Bedtime", "School
  hours"). When #1069 lands, an operator can attach a named schedule to an app
  window instead of re-typing the times. This is forward-compatible: the column
  is added nullable now, and the FK constraint to the `named_schedules` table is
  added in the follow-up that ships #1069 (the target table does not exist yet).

Exactly one of `{schedule_id}` or `{days, from, until}` is populated per row,
enforced by a table `CHECK`.

This composes with **#1067** (schedule-driven blocklist activation): both are
"a schedule drives a policy effect." They should converge on the same named-
schedule references once #1069 exists, but neither blocks the other.

### 3.3 DB migration shape

The window is a **child of the per-app-per-profile assignment**
(`app_policy_assignments`), not of the app itself — the same app can have
different windows under different profiles, exactly as it already has different
`mode`s. One-to-many (an assignment may carry several windows), mirroring how
`schedules` is one-to-many under `profiles`.

New migration (next free version, currently `V48__app_policy_schedules.sql`):

```sql
CREATE TABLE app_policy_schedules (
  id             BIGSERIAL PRIMARY KEY,
  assignment_id  BIGINT NOT NULL
                   REFERENCES app_policy_assignments(id) ON DELETE CASCADE,
  mode           TEXT NOT NULL
                   CHECK (mode IN ('allowed_during','blocked_during')),
  -- inline window (mirrors the schedules table); used when schedule_id IS NULL
  days           TEXT[],
  window_from    TEXT,            -- 'HH:MM', household-local
  window_until   TEXT,            -- 'HH:MM', household-local
  -- #1069 named-schedule reference; FK added in the follow-up that ships #1069
  schedule_id    BIGINT,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CHECK (
    (schedule_id IS NOT NULL
       AND days IS NULL AND window_from IS NULL AND window_until IS NULL)
    OR
    (schedule_id IS NULL
       AND days IS NOT NULL AND window_from IS NOT NULL AND window_until IS NOT NULL)
  )
);
CREATE INDEX idx_app_policy_schedules_assignment
  ON app_policy_schedules(assignment_id);
```

**Migration-isolation (AGENTS.md "Schema changes land in their own PR").** This
is a brand-new table referencing only existing tables, so:

- It is trivially backward-compatible with image-(N-1): the old image's queries
  never touch `app_policy_schedules`, so the existing `api.test` suite (which
  applies every migration including this one against image-(N-1) code) passes
  unchanged — that is the gate.
- The migration PR contains **only** `V48__….sql` plus doc updates. No source,
  no tests, no fixtures. The PolicyService eval, repo methods, and tests land in
  the follow-up PR (§"Sub-issues").
- **Not a growth table** ("Migrations that are fast on dev … minutes-long on
  prod"): `app_policy_schedules` is bounded by `apps × profiles × windows`
  (tens of rows), not by event volume. The migration is metadata-only — safe on
  the startup critical path.

### 3.4 Domain model (`shared/src/Models.scala`, follow-up PR — API-internal)

These are API-internal models consumed by `PolicyService`; **none of them is a
`PolicySnapshot` field** — they never reach the wire.

```scala
enum AppScheduleMode { case AllowedDuring, BlockedDuring }

case class AppScheduleWindow(
    id: AppScheduleWindowId,
    assignmentId: AppPolicyAssignmentId,
    mode: AppScheduleMode,
    // inline window (None when referencing a named schedule)
    days: Option[List[String]],
    startLocal: Option[LocalTime],
    endLocal: Option[LocalTime],
    // #1069 reference (None when inline)
    scheduleId: Option[ScheduleId],
)
```

At evaluation time each `AppScheduleWindow` is resolved into a `Schedule`
(inline fields or the referenced named schedule), the household `tz` is
injected, and `PolicyService.scheduleActiveAt` decides activity. The
`UpsertAppAssignmentRequest` gains an additive `schedules: List[…] = Nil`
field (back-compatible default).

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

```
effectiveDisposition(assignment, windows, now):
  if any allowed_during window active at now  -> AllowedDuring  // carve-out CANDIDATE (see gate)
  else if any blocked_during window active     -> Blocked
  else                                         -> base mode      // Allowed / Blocked / TimeLimited
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
fallback agrees with the snapshot. Note its current precedence chain — "paused >
schedule > allowed-app > blocked-app > site_time_limit > time_limit > category"
— places allowed-app *above* time_limit unconditionally; that must change so a
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

- An **add-window** control producing one or more window rows (one-to-many,
  matching the data model).
- Per window: a **mode** toggle — *Allowed during* / *Blocked during* — and
  either
  - a **named-schedule picker** (when #1069 ships), or
  - an **inline window editor** reusing the existing profile-schedule editor
    component (day-of-week multiselect + `from`/`until` time pickers). Times are
    household-local; the editor shows the household zone.
- **Autosave** ([#995](https://github.com/wifihaven/wifihaven/issues/995) /
  [#423](https://github.com/wifihaven/wifihaven/issues/423)): debounced PATCH of
  the assignment, no explicit Save button, consistent with the autosave-default
  preference. The request shape extends `UpsertAppAssignmentRequest` additively
  with `schedules: List[AppScheduleWindow]` (default `Nil`, so existing clients
  keep working).
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

## 9. Sub-issues to file (do not file yet — operator/orchestration)

In dependency order. The first three honour the migration-isolation two-PR
split.

1. **DB migration (schema-only PR).** `V48__app_policy_schedules.sql` per §3.3,
   plus doc updates only. No source, no tests. Gate: existing `api.test` passes
   against image-(N-1) code with the new table present.
2. **PolicyService eval + repo + models (follow-up PR).** Add
   `AppScheduleWindow` / `AppScheduleMode` to `shared/Models.scala`; repo method
   to load windows per assignment; the §4.1 effective-disposition resolution in
   both `PolicyService.snapshot` and `PolicyService.decide`; extend
   `UpsertAppAssignmentRequest` additively. Confirm **no** `PolicySnapshot`
   field is added.
3. **Schedule-boundary unit tests.** Pure tests over the effective-disposition
   resolver + `scheduleActiveAt` reuse: exact on/off edges, overnight wrap, DOW
   membership, DST transition, `allowed_during`-beats-`blocked_during` tiebreak,
   the cap gate (`carveIntoExtraAllowed` true for exempt-over-cap, false for
   non-exempt-over-cap, true for either when under cap), and inline vs
   named-schedule resolution. Use `Clock.TestClock` fixtures; never
   `java.time` directly.
4. **Feature tests.** End-to-end snapshot assertions proving the app's hosts
   land in `extraAllowed` during an `allowed_during` window while the profile is
   in scheduled downtime (row 1); that an **exempt** app *is* carved out when the
   profile is over its daily cap (row 9a) while a **non-exempt** one is *not*
   (row 9b) — the cap-gate split; the coincident schedule+cap case for a
   non-exempt app (row 9c); and `extraBlocked` during a `blocked_during` window
   (row 3). Drive time with `TestClock`; assert the ETag flips across a window
   edge.
5. **Web UI.** The §7 assignment-editor Schedules section with autosave;
   inline editor now, named-schedule picker gated on #1069.
6. **(Optional, compose-with) Named-schedule FK.** Once #1069 lands, add the
   `app_policy_schedules.schedule_id` FK to `named_schedules` and the picker
   wiring. Tracked against #1069, not a blocker for inline windows.

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
