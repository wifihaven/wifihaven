# Time handling — schedules, daily reset, DST

This document explains how WifiHaven handles wall-clock time across timezones
and DST transitions. The rules apply to schedule windows and daily-usage
reset; both are evaluated by `PolicyService` on the API server.

See issue [#334](https://github.com/wifihaven/wifihaven/issues/334) for
the design discussion.

## The rule: timezone lives with the data

Every time-anchored value carries its own IANA timezone. There is **no**
server-wide or household-wide "current timezone" that everything inherits.

| What                | Stored as                              | In the model                                |
|---------------------|----------------------------------------|---------------------------------------------|
| Schedule window     | `start_local TIME, end_local TIME, tz` | `Schedule.startLocal/endLocal/tz`           |
| Daily-usage reset   | `daily_reset_time TIME, daily_reset_tz`| `HouseholdSettings.dailyResetTime/Tz`       |

The API server's own clock is `Instant`-based (UTC). Per-row timezone math
projects `Instant.now()` into the row's `tz` and compares wall-clock components
there. The host's local timezone is **not** consulted at decision time.

## Why store tz with the data?

Two reasons:

1. **Wall-clock stability across DST.** A schedule of "9 PM to 7 AM
   America/Los_Angeles" stays "9 PM to 7 AM" forever. On the night clocks
   spring forward, the window's UTC duration is 9 hours instead of 10; on the
   night they fall back, 11 hours instead of 10 — but to the user, it's the
   same 9 PM to 7 AM every night, which matches user intent ("bedtime is
   9 PM"). If we stored the window in UTC instead, the user-visible time
   would shift by an hour twice a year.

2. **A household can have schedules in multiple zones.** A travelling family
   member's profile can have schedules in their travel zone, distinct from
   the household-wide reset zone, without any per-profile or per-row
   "override the default" plumbing.

## DST mechanics

The math reduces to:

```scala
val zdt = instant.atZone(schedule.tz)
val now = zdt.toLocalTime
val today = zdt.toLocalDate
```

`ZonedDateTime` handles both DST transitions correctly:

- **Spring forward** (e.g. 2026-03-08 02:00 PST → 03:00 PDT in LA): no
  `Instant` maps to a wall-clock time in `[02:00, 03:00)` PDT. A schedule
  that spans this gap (e.g. `21:00 → 07:00 LA`) simply has fewer in-window
  Instants that night — about 9 hours instead of 10. Edge times like
  `06:30` and `07:01` evaluate as expected.
- **Fall back** (e.g. 2026-11-01 02:00 PDT → 01:00 PST in LA): wall-clock
  times in `[01:00, 02:00)` happen *twice*. Both of those Instants project
  to the same `LocalTime` and are evaluated against the schedule
  identically — the in-window state is continuous; there is no "double
  block" or "off then on again."

## Schedule windows

A schedule has `startLocal: LocalTime`, `endLocal: LocalTime`, `tz: ZoneId`,
`days: List[String]` (`"mon"` … `"sun"`).

- **Same-day** (`startLocal < endLocal`): in-window iff today's local date is
  in `days` and `startLocal <= now < endLocal`.
- **Cross-midnight overnight** (`startLocal > endLocal`, e.g. 21:00 → 07:00):
  in-window iff
  - today is in `days` and `now >= startLocal`, *or*
  - yesterday is in `days` and `now < endLocal` (the "tail" of yesterday's
    window).
- **Empty** (`startLocal == endLocal`): never in-window.

`endLocal` is *exclusive*: a `09:00 → 17:00` window is in-window at 16:59 and
out-of-window at 17:00.

## Daily reset

The household has one configurable reset time + tz. The next reset Instant is
computed strictly-after `now`:

```scala
def nextDailyResetAfter(settings, now): Instant =
  val zdt = now.atZone(settings.dailyResetTz)
  val candidate = zdt.toLocalDate.atTime(settings.dailyResetTime)
                       .atZone(settings.dailyResetTz).toInstant
  if candidate.isAfter(now) then candidate
  else /* tomorrow's reset */
```

This Instant populates the `expiresAt` field on `time_limit` and
`site_time_limit` block decisions sent to the router. The router then
expires the block at exactly that wall-clock moment in the configured zone,
regardless of DST.

The household-local "today" used for usage-bucket lookup is
`now.atZone(settings.dailyResetTz).toLocalDate`. (For non-midnight reset
times this slightly diverges from the strict "the bucket starts at the last
reset Instant" interpretation; we'll revisit if anyone configures a
non-midnight reset.)

## Validation

- IANA zone names are validated at the wire boundary by `JsonCodec[ZoneId]`
  in `shared/src/Models.scala`. Unknown zones (`"Foo/Bar"`) → 400 with a
  clear error.
- `LocalTime` is `"HH:mm"` 24-hour, validated by `JsonCodec[LocalTime]`.

## Backfill (V14 migration)

Pre-#334 schedules used `block_from TEXT, block_until TEXT` (HH:mm strings)
with no timezone field; the server interpreted them as server-local time
(effectively UTC, since the API container runs UTC). The V14 migration:

1. Adds `start_local TIME, end_local TIME, tz TEXT NOT NULL` to `schedules`.
2. Backfills `start_local`/`end_local` from the existing strings; sets
   `tz = 'UTC'` (preserves prior behavior bit-for-bit).
3. Drops `block_from`, `block_until`.
4. Creates `household_settings` (single row, `id = 1`) with default
   `daily_reset_time = '00:00'` and `daily_reset_tz = 'UTC'`.

On first boot, `Main.scala` calls `HouseholdSettingsRepo.ensureDefault(...)`,
which inserts the row using `ZoneId.systemDefault()` from the JVM (so a fresh
install picks up the API server's host tz). On subsequent boots this is a
no-op (`ON CONFLICT DO NOTHING`).

After upgrading, the operator can change `tz` per schedule (and the household
reset tz) via the UI. There is no automatic conversion — UTC is preserved
until someone touches the row.

## API server host clock

The API server should run with an NTP-synced UTC system clock. Per-row
timezone math means the server's local timezone setting does not affect
decisions; only `Instant.now()` (a UTC-based monotonic point) matters. The
host tz only affects the install-time default for `household_settings.tz`.

## What we did *not* do

- **No agent-side time decisions.** The router agent does not evaluate
  schedules or daily limits — `PolicyService` collapses everything into the
  per-MAC `BlockRules` in the snapshot, plus the `expiresAt` Instant on
  per-host decisions. See [#350](https://github.com/wifihaven/wifihaven/issues/350)
  (Truth 2) and [#415](https://github.com/wifihaven/wifihaven/issues/415)
  (agent skew code removal).
- **No per-user viewer-local time display.** The UI shows a schedule's time
  in the schedule's stored tz so all viewers see the same string ("9 PM
  America/Los_Angeles"). Showing each viewer their own local-time
  re-projection would create confusion when two parents in different zones
  edit the same schedule.
