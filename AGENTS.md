# AGENTS.md — FamilyDNS

This file provides context for AI coding agents (Claude, Copilot, Cursor, etc.) working on this codebase.

## What this project is

FamilyDNS is a self-hosted parental control DNS server with per-device filtering, time limits, and a web dashboard. It runs on a Linux home server (Ubuntu) and replaces commercial products like Gryphon or TP-Link HomeShield.

## Architecture

```
familydns/
├── shared/        # Domain models shared across all modules (Scala 3, ZIO JSON)
├── api/           # REST API + web server (ZIO HTTP, Doobie, PostgreSQL)
├── dns/           # DNS filtering server (UDP :53, ZIO, pure Scala DNS parsing)
├── traffic/       # Passive traffic monitor (pcap4j, session tracking)
└── web/           # React TypeScript dashboard (Vite, Tailwind)
```

Three JVM processes run at runtime:
1. `api` — REST API on :8080, serves the React SPA, handles auth (JWT)
2. `dns` — DNS server on :53, reads cache from Postgres, blocks by profile/schedule/time
3. `traffic` — Packet capture via libpcap, tracks time-on-site per device per domain

All three share a single PostgreSQL database.

## Key domain concepts

- **Profile** — a set of filtering rules (blocked categories, schedules, time limits). Devices are assigned to profiles.
- **Device** — identified by MAC address (not IP, which changes with DHCP). Matched to a profile.
- **Schedule** — time windows when internet is blocked entirely for a profile (e.g. bedtime 21:00–07:00).
- **TimeLimit** — daily total minutes allowed per profile (e.g. 120 min/day total screen time).
- **SiteTimeLimit** — daily minutes for a specific domain pattern, tracked *separately* from the main limit (e.g. 30 min YouTube, not counted in the 120 min total).
- **TimeUsage** — per-(device, domain, date) minutes accumulated, reset at midnight. Updated by traffic monitor.
- **TimeExtension** — admin-granted extra minutes for a device on a specific day, with audit trail.
- **BlocklistDomain** — domain → category mapping. Loaded into memory cache, refreshed every 15 min.
- **QueryLog** — every DNS query logged with device, profile, blocked status, reason.
- **Location** — `home` or `vacation`. Stored on devices and logs. Both locations share profiles/devices but query logs are tagged so you can filter by house.

## DNS blocking priority order

1. Profile paused → block all
2. Schedule active (bedtime etc.) → block all
3. Domain in extra_allowed → allow (overrides everything below)
4. Domain in extra_blocked → block
5. Daily time limit reached (from TimeUsage) → block with reason `time_limit`
6. Site-specific time limit reached for this domain → block with reason `site_time_limit`
7. Domain in blocklist category → block with reason `category:X`
8. Default → allow, forward to upstream CleanBrowsing DNS

## Tech stack decisions

| Choice | Reason |
|--------|--------|
| Scala 3 + ZIO 2 | Type-safe effects, great for concurrent servers |
| ZIO HTTP | Native ZIO integration, good middleware support |
| Doobie | Typesafe SQL, no magic ORM |
| Flyway | Versioned DB migrations, easy to reason about schema |
| pcap4j | JVM bindings for libpcap, works on Ubuntu |
| JWT (jwt-scala) | Stateless auth, easy to verify in DNS process too |
| Mill | Faster than sbt, simpler build files |
| React + Vite + TypeScript | Fast builds, good DX, type safety |
| Tailwind CSS | Utility-first, mobile-friendly without component library lock-in |

## Coding conventions

- **Effects**: always `ZIO[R, E, A]`, never throw exceptions. Use `ZIO.attempt` to wrap unsafe code.
- **Errors**: domain errors as sealed traits, not strings. Use `ZIO.fail` with typed errors.
- **Config**: always via `zio-config` + HOCON. Never hardcode values or use `sys.env` directly.
- **DB**: all queries in repository classes. No SQL outside of `*RepoLive` implementations.
- **Layers**: wire dependencies via `ZLayer`. No global mutable state.
- **Tests**: use `ZIO Test` spec style. Integration tests use Testcontainers PostgreSQL.
- **Formatting**: `scalafmt` enforced in CI. Run `mill __.reformat` before committing.
- **Imports**: managed by `scalafix OrganizeImports`. Run `mill __.fix` before committing.

## Docker inside Claude Code agents (worktrees)

Docker commands (`docker info`, `docker compose`, etc.) will **hang
indefinitely** if Docker Desktop is not running or is in a degraded state.
The Claude Code bash sandbox does not block Unix socket connections — Docker
simply must be healthy.

**Before running any docker command**, verify the daemon responds:

```bash
docker info 2>&1 | grep "Server Version"
```

This should return within 2 seconds. If it hangs, restart Docker Desktop
(quit from the menu bar, then reopen) and wait for the whale icon to become
steady before retrying.

Common symptom: Docker Desktop appears "running" (process exists, socket
files exist) but the daemon inside the VM has crashed or frozen — this happens
after long uptimes. Restarting Docker Desktop is the fix.

## Running locally

```bash
# Start Postgres
docker run -d --name familydns-pg \
  -e POSTGRES_USER=familydns \
  -e POSTGRES_PASSWORD=secret \
  -e POSTGRES_DB=familydns \
  -p 5432:5432 postgres:16

# Copy and edit config
cp config/application.conf.example config/application.conf

# Run API
mill api.run

# Run DNS server (needs root for port 53)
sudo mill dns.run

# Run traffic monitor (needs root for pcap)
sudo mill traffic.run

# Run frontend dev server
cd web && npm run dev
```

## Testing

```bash
# All tests
mill __.test

# Single module
mill api.test
mill dns.test

# Format check
mill __.checkFormatting

# Fix imports
mill __.fix
```

## Database migrations

Migrations live in `api/resources/db/migration/` as `V{n}__{description}.sql`. They run automatically on API startup via Flyway. Never edit existing migrations — always add a new one.

Tests run the same Flyway migrations from `classpath:db/migration` against the embedded Postgres in `TestDatabase`, so there is one source of truth — never maintain a parallel test schema. To change the schema, add a new `V{n}__...sql` migration; do not edit `V1__init.sql` (or any other already-applied migration).

## Branch-diff checks (CI + pre-push)

CI checks and pre-push checks that compare a branch against `main` MUST diff against the **merge base** with `origin/main`, not `origin/main` directly. Use three-dot syntax (`origin/main...HEAD`) or an explicit `git merge-base origin/main HEAD`. Two-dot (`origin/main..HEAD`) over-reports when `main` has advanced since the branch diverged, producing spurious failures and noise.

Pre-commit checks are different: they operate on staged files (`git diff --cached`), not against `origin/main`.

## Adding a new API route

1. Add request/response types to `shared/src/Models.scala`
2. Add repo method to the trait in `api/src/db/Database.scala`
3. Implement in `api/src/db/Repos.scala`
4. Add route in the appropriate file under `api/src/routes/`
5. Register route in `api/src/Main.scala`
6. Add tests in `api/test/src/`
7. Add TypeScript API call in `web/src/api/`

## Security notes

- JWT secret must be at least 32 chars, set in config
- DNS server does not expose HTTP — no auth surface
- Traffic monitor does not expose HTTP — no auth surface
- Passwords are bcrypt hashed (cost factor 12)
- Admin vs ReadOnly enforced via JWT claims + middleware
- SQL injection impossible via Doobie parameterized queries
- Config file contains DB credentials — never commit it (in .gitignore)

## TDD workflow (required for new features and bug fixes)

For any new feature or bug fix, follow test-driven development:

1. **Write the test first.** Before implementing, write the unit and/or feature test(s) that describe the desired behavior. For bugs, the test should fail in the way the bug manifests; for features, it should describe the new behavior.
2. **Validate the test logic with the user before writing implementation code.** Show the test to the user and ask them to confirm the test correctly describes the intended behavior. Do not skip this step — the test is the spec.
3. **Only after the user confirms, implement the code** to make the test pass.

This applies to both unit tests and feature tests — pick whichever level fits the change (see "Testing philosophy" below).

## Testing philosophy

### Feature tests first, unit tests for edge cases only

The primary test style is **feature/functional tests** that exercise the full call stack:

```
HTTP request → Route handler → Service → Repository → Embedded Postgres → Response
```

Unit tests are reserved for:
- Pure functions with complex edge cases (`BlockingEngine`, `DnsPacket`, `SessionTracker`)
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

`familydns.shared.Clock` is the only way to get the current time anywhere in the codebase.

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

### ZIO primitives for mutable state

Use ZIO primitives everywhere except tight inner loops:

| Use case | Type |
|----------|------|
| Single mutable value | `Ref[A]` |
| Atomic read-modify-write across effects | `Ref.Synchronized[A]` |
| Producer/consumer queue | `Queue[A]` |
| Broadcast | `Hub[A]` |
| Tight inner loop (packet capture) | Scala `mutable.HashMap` inside single fiber — document why |

The `SessionTracker` class is the only place Scala mutable collections are intentionally used.
This is documented in the class — do not add more without strong justification.

### Mocks — external I/O only

Only mock things that can't run in CI:
- `pcap4j` packet capture (no network interface in CI) — `SessionTracker` is unit-tested directly
- Upstream DNS socket (port 53 forwarding) — `DnsServer.forwardUpstream` is the boundary

Never mock:
- Repository traits
- `AuthService`
- `BlockingEngine` (pure functions, test directly)
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

dns/test/src/
  unit/
    BlockingEngineSpec.scala  ← all decision paths, edge cases
    DnsPacketSpec.scala       ← packet parsing edge cases
  feature/
    DnsBlockingSpec.scala     ← full stack with real DB + TestClock

traffic/test/src/
  unit/
    SessionTrackerSpec.scala  ← session accumulation, expiry, drain

shared/test/src/
  ClockSpec.scala             ← TestClock advance/set behaviour
```
