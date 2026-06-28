# Testing philosophy

This was originally in AGENTS.md §"Testing philosophy"; see AGENTS.md for the TOC.

## Testing philosophy

### Feature tests first, unit tests for edge cases only

The primary test style is **feature/functional tests** that exercise the full call stack:

```
HTTP request → Route handler → Service → Repository → Embedded Postgres → Response
```

Unit tests are reserved for:
- Pure functions with complex edge cases (e.g. policy decision logic in `openwrt/policy.lua`)
- Schedule boundary conditions (exact on/off times, overnight wrapping, day-of-week)
- Domain pattern matching edge cases
- Time limit arithmetic (extensions, site-specific exemptions)

If you can test something via a feature test, do that instead of a unit test.

### Embedded Postgres — no mocks for the DB layer

All tests that touch data use a real embedded Postgres via `zonkyio/embedded-postgres`.
Never mock `*Repo` traits. The point is to test the actual SQL.

Test infrastructure lives in `api/test/src/TestDatabase.scala`:
- `TestDatabase.layer` — spins up embedded PG, runs Flyway migrations, provides all repos
- `TestDatabase.cleanAndMigrate` — call in `beforeEach` equivalent to reset state between tests
- `TestLayers.seedKidsProfile`, `seedAdultsProfile`, `seedDevice` — common seed helpers

### Clock is always injected — never call java.time directly

`wifihaven.shared.Clock` is the only way to get the current time anywhere in the codebase.

```scala
// WRONG
val now = LocalDateTime.now()
val today = LocalDate.now()

// RIGHT
for
  now   <- Clock.now
  today <- Clock.today
yield ...
```

In tests, use `Clock.TestClock.make(dt)` to control time:
```scala
// Standard fixtures in Clock.TestClock:
Clock.TestClock.schoolDayAfternoon  // Monday 14:00
Clock.TestClock.bedtime             // Monday 21:30
Clock.TestClock.earlyMorning        // Monday 06:00
Clock.TestClock.weekendAfternoon    // Saturday 15:00

// Advance time in a test:
for
  ref <- Ref.make(LocalDateTime.of(2025, 1, 6, 20, 55, 0))
  tc   = new Clock.TestClock(ref)
  _   <- tc.advance(Duration.ofMinutes(10)) // now 21:05 — past bedtime
  d   <- checkBlocking(tc)
yield ...
```

**Never wait on wall-clock time for async work.** A test must not `ZIO.sleep`
(or otherwise block on real wall-time) to *wait for* a background fiber, poller,
cache refresh, or scheduled effect to land — typically under
`@@ TestAspect.withLiveClock` or a `Clock.live` layer. That shape is flaky by
construction: it passed `MetricsExportSpec` in isolation (it forked the rollup
loop, `ZIO.sleep(600.millis)`, interrupted, slept again, then asserted) but
flaked an unrelated PR's CI under 14-worker contention
([#2042](https://github.com/wifihaven/wifihaven/issues/2042)). Drive async work
to completion **synchronously** (call the single-tick function directly, not
`.fork` + sleep) and advance time **deterministically** with `TestClock.adjust`.
Measuring *real* elapsed duration is the only legitimate live-clock use, and it
must say why inline.

### ZIO primitives for mutable state

Use ZIO primitives everywhere except tight inner loops:

| Use case | Type |
|----------|------|
| Single mutable value | `Ref[A]` |
| Atomic read-modify-write across effects | `Ref.Synchronized[A]` |
| Producer/consumer queue | `Queue[A]` |
| Broadcast | `Hub[A]` |
| Tight inner loop | Scala `mutable.HashMap` inside single fiber — document why |

Avoid mutable Scala collections unless there is a strong, documented reason.

### Mocks — external I/O only

Only mock things that can't run in CI:
- Network I/O in the router agents — use injected function parameters (see `policy.lua` `get_fn`, `write_fn`, `exec_fn` patterns)

Never mock:
- Repository traits
- `AuthService`
- `Clock` (use `TestClock`)

### Test structure

```
api/test/src/
  TestDatabase.scala          ← shared test infrastructure
  feature/
    AuthApiSpec.scala         ← login, token validation, password change
    ProfileApiSpec.scala      ← CRUD, schedules, time limits
    DeviceApiSpec.scala       ← upsert, MAC normalisation, delete
    TimeApiSpec.scala         ← usage tracking, extensions, site limits
    LogApiSpec.scala          ← query filtering, stats aggregation
    RouterApiSpec.scala       ← enrollment, policy snapshot, ETag
    RouterIngestSpec.scala    ← usage + event ingest endpoints
    RouterDecisionSpec.scala  ← /api/router/decision blocking logic

shared/test/src/
  ClockSpec.scala             ← TestClock advance/set behaviour

openwrt/test/
  policy_spec.lua             ← policy fetch + apply (blocking decisions)
  conntrack_spec.lua          ← conntrack event parsing
  render_spec.lua             ← RPZ/dnsmasq render
  usage_spec.lua              ← usage accumulation

opnsense/test/
  test_pflog.py               ← pflog line parsing
  test_unbound.py             ← Unbound log parsing
  test_agent.py               ← agent event loop
```
