# AGENTS.md — WifiHaven

This file provides context for AI coding agents (Claude, Copilot, Cursor, etc.) working on this codebase.

## Architectural model — read this before working on anything

These two truths are the canonical architecture. Code or docs that contradict
them are bugs — fix or flag, do not propagate.

**1. DNS is never the enforcement plane.** DNS always resolves. Blocking
happens at the connection layer via nftables forward-drop on the gateway
router. The block page is reached via HTTP DNAT for port 80, **not** via
DNS sinkhole / NXDOMAIN / RPZ. dnsmasq is still used on the router for
hostname attribution (forward-lookup ipset population so nftables can match
on `(mac, dst ip ∈ resolved-hosts-for-this-mac)`), but it is not the
enforcer.

> **ANTI-PATTERN — never reason "resolved ⇒ reachable / not blocked."**
> A successful DNS lookup proves nothing about whether traffic is allowed:
> DNS *always* resolves, and the resolved IP is exactly what nftables drops.
> Reachability is a connection-layer property, not a DNS one. So:
> - Do NOT conclude "the app's domain resolved fine, so it wasn't blocked."
> - Do NOT describe a fix as "allow the domain's DNS." An allow entry works
>   by carving the host's *resolved IPs* out of the forward-drop — the
>   per-`(mac, host)` `ea_` ipset that dnsmasq's nftset callback populates at
>   resolve time — it does not touch DNS at all.
>
> This slip recurs (e.g. [#1307](https://github.com/wifihaven/wifihaven/issues/1307));
> see [#1313](https://github.com/wifihaven/wifihaven/issues/1313).

**2. The router agent is a dumb applier.** The API server ships a policy
snapshot in which every decision is already pre-computed. After a one-time
resolution step, the enforcement pipeline sees a `Map[MacAddress, BlockRules]`
where `BlockRules` is:

- `blocked: Boolean` — drop all forwarded traffic from this MAC. Covers
  paused profile, out-of-schedule, daily-limit exceeded, manual block, and
  any other "block right now" reason — **all evaluated server-side and
  collapsed into this flag**. The router does not look at schedules, daily
  minutes, or time-used-today.
- `blockReason: Option[MacBlockReason]` — for block-page text only. Not
  used for enforcement. `MacBlockReason` is a `sealed trait extends
  BlockReason` whose cases are `Paused`, `Schedule`, `TimeLimit`, `Manual`
  — the only reasons a whole-MAC block can come from `PolicyService`. Per-
  flow drop reasons (host-block, category-block, ip-only-block) are emitted
  by the router at drop time and never appear in the snapshot; they live
  on `BlockReason` but not `MacBlockReason`, so the type system prevents
  them from being assigned here.
- `extraBlocked: List[Hostname]` — hosts blocked for this MAC. Enforced
  via nftables drop on `(mac, dst ip ∈ ipset(host))`, where the ipset is
  populated by dnsmasq's `--ipset=` callback at resolution time.
- `extraAllowed: List[Hostname]` — hosts allowed for this MAC, including
  as a carve-out when `blocked = true`.
- `blocklistIds: List[BlocklistId]` — category lists that apply to this
  MAC. Enforced via nftables ipset drop, **not** via RPZ or any DNS-layer
  mechanism. The agent fetches each blocklist by URL (cached by version)
  and resolves member hosts into a per-blocklist ipset on a periodic
  cadence.
- `blockIpOnly: Boolean` — drop forwarded traffic whose destination IP
  was not resolved via our local DNS for this MAC. No allowlist carve-out:
  if we cannot attribute the destination IP to a hostname, we cannot
  evaluate the allowlist against it. Used to prevent DoH / hard-coded-IP
  bypass.

Profiles exist in the snapshot only as a wire-level dedup aid:
`profiles: Map[ProfileId, BlockRules]`. Each device carries an optional
`profileId` and an optional per-device `rules` override; the override, if
present, **replaces** the profile rules entirely (it is not a merge). The
agent resolves device → effective rules once at apply time, then enforcement
is purely per-MAC. **The enforcement pipeline never sees profiles.**

New policy concepts (a new schedule type, a new failover behaviour, a new
category model) land in the API server's `PolicyService` and present to
the router as one of the fields above. **Do not add decision logic — schedule
evaluation, time accounting, category lookup, role-based defaults — to the
router agent.**

### The snapshot is a minimal functional shape, not a policy model

The fields above are the *whole* wire vocabulary, and that is deliberate. The
snapshot carries only the **functional** data the router must act on to
enforce — it is not a mirror of the server's policy model.

- **Express new policy via existing functional fields — never add a field for
  the concept itself.** If a new policy idea can be carried by a field that
  already exists, it MUST be. An "always-allow this host" rule is functionally
  just another `extraAllowed` entry; a "block this app" rule is just
  `extraBlocked` / `blocklistIds`. Adding a new top-level channel (e.g. an
  `infraAllow` set) to name the *concept* pushes a policy tier onto the router
  and is wrong. (This was the original misstep in
  [#1307](https://github.com/wifihaven/wifihaven/issues/1307) — the global
  infra allowlist now ships by copying its hosts into every profile's
  `extraAllowed`, not via a new field.)
- **No policy *reasons* on the wire except where functionally required.**
  `blockReason` is the one intentional exception, and it exists only to pick
  block-page copy — it is never read for enforcement. Do not add "why"
  metadata for allow/deny decisions; the server resolves policy into functional
  data and the router applies it blind.
- **Redundancy and wire-shape are separate concerns.** Copying a shared set
  (like the infra allowlist) into every profile's `extraAllowed` is the correct
  wire shape even though it duplicates data. The duplication is a separate
  optimization, tracked by the global-policy-layer work in
  [#1308](https://github.com/wifihaven/wifihaven/issues/1308) — and reducing it
  must NOT come at the cost of teaching the router about policy tiers. See
  [#1311](https://github.com/wifihaven/wifihaven/issues/1311) for the full
  rationale.

See [`docs/architecture.md`](docs/architecture.md) for the full snapshot
shape, wire JSON examples, and the enforcement model.

> **Implementation status as of June 2026.** The model above is what the
> code does. `extraBlocked` enforces via per-`(MAC, host)` nftables drops on
> the resolved IPs (`eb_`/`eb6_` sets), not a DNS sinkhole. Category
> blocklists enforce via per-`(MAC, blocklistId)` nftables drops on the
> `bl_`/`bl6_` sets, which the agent populates by fetching each blocklist URL
> and resolving its members at DNS time (`blocklists.lua` →
> `render.lua`). `blockIpOnly` is implemented (per-MAC `resolved_` sets). The
> snapshot ships the collapsed per-MAC `BlockRules` shape only — `blocked`,
> `blockReason`, `extraBlocked`, `extraAllowed`, `blocklistIds`,
> `blockIpOnly` — plus per-profile `failureMode`; schedules, daily minutes,
> time-used, and site limits are all evaluated server-side and never reach
> the wire. (The category-enforcement path was a silent no-op on prod until
> [#1334](https://github.com/wifihaven/wifihaven/issues/1334) fixed the
> agent's blocklist-fetch call.)

## What this project is

WifiHaven is a self-hosted, network-level parental-control system with per-device filtering, time limits, and a web dashboard. The API runs on a Linux home server (Ubuntu) and replaces commercial products like Gryphon or TP-Link HomeShield; the enforcement agent runs on the gateway router (OpenWRT or OPNsense).

## Architecture

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
- **Staging and production cloud (`staging.wifihaven.net`,
  `api.wifihaven.net`)**: the SPA deploys to **Cloudflare Pages**,
  independent of the API. The API JVM serves only `/api/*`; the SPA is a
  static bundle that talks to the API over the network like any other
  client. Cloudflare config lives in-repo:
  - [`infra/cloudflare/`](infra/cloudflare/) — Terraform (`main.tf`,
    `variables.tf`) for the Cloudflare account-level resources.
  - [`web/wrangler.toml`](web/wrangler.toml) (prod) and
    `web/wrangler.staging.toml` (staging) — Wrangler config for
    `wrangler pages deploy`. Deploys are driven from
    `.github/workflows/deploy-spa.yml`.

**In the cloud environments, API and SPA roll back independently.**
Rolling back the API on Render does **not** roll back the SPA on
Cloudflare Pages, and vice versa. A coordinated rollback must touch
both sides. This does not apply to the self-hosted install, where the
SPA ships inside the API image.

Connection-level enforcement and per-device usage tracking run on the gateway router, not on the API host (see the "Architectural model" callout at the top of this file for why):
- **OpenWRT** — the `openwrt/` Lua agent polls `/api/router/policy` and rewrites nftables rules + a dnsmasq fragment used only for hostname attribution / ipset population; reports usage via `/api/router/events` and `/api/router/usage`
- **OPNsense** — the `opnsense/` Python agent tails pflog and posts connection events

Key API surface (under `/api/router/*` and `/api/blocklists/*`):
- `POST /api/router/register` — one-time enrollment
- `GET  /api/router/policy`   — ETag-polled enforcement snapshot
- `GET  /api/blocklists/<cat>.rpz` — RPZ blocklist per category
- `POST /api/router/usage`    — per-(mac, hostname) traffic records
- `POST /api/router/events`   — DHCP lease + connection attempt events

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

## Policy decision pipeline (server-side, in `PolicyService`)

These steps happen **on the API server** when computing the snapshot — not on
the router. They collapse into the per-MAC `BlockRules` fields described in
the "Architectural model" callout above.

Order matters because earlier conditions short-circuit:

1. Profile paused → `blocked = true`, reason `Paused`
2. Schedule active for current time → `blocked = true`, reason `Schedule`
3. Daily time limit reached (`time_used_today >= daily_minutes + extensions_today`) → `blocked = true`, reason `TimeLimit`
4. Per-site time limit reached for some domain → that domain added to `extraBlocked` for this MAC
5. Manual admin block → `blocked = true`, reason `Manual`
6. Profile / device `extraBlocked` hostnames → `extraBlocked` for this MAC
7. Profile / device `extraAllowed` hostnames → `extraAllowed` for this MAC (carves out blocks above)
8. Profile / device assigned categories → `blocklistIds` for this MAC
9. `blockIpOnly` flag for the profile / device → set as-is

The router never re-evaluates any of this. It receives the resolved
`BlockRules` and applies them mechanically.

(DNS resolution itself is never blocked by WifiHaven. dnsmasq forwards
upstream as normal; the enforcement plane is nftables on the resolved IPs.)

## Tech stack decisions

| Choice | Reason |
|--------|--------|
| Scala 3 + ZIO 2 | Type-safe effects, great for concurrent servers |
| ZIO HTTP | Native ZIO integration, good middleware support |
| Doobie | Typesafe SQL, no magic ORM |
| Flyway | Versioned DB migrations, easy to reason about schema |
| Lua (OpenWRT) | Native on OpenWRT, zero-dep enforcement agent |
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

## Prefer declarative config over dashboard toggles

Anywhere a piece of infrastructure can be configured declaratively in-repo
(Render Blueprint `render.yaml`, GitHub Actions workflows, repo settings via
`gh` API, DNS via a checked-in zone file, etc.), do it there instead of
clicking around in a vendor dashboard.

- The repo is the source of truth; dashboard state should be reproducible
  from `render blueprint apply` (or equivalent). When the two disagree,
  the in-repo file wins on the next sync.
- Render specifics: `autoDeploy`, image URLs, env vars, health check
  paths, domains — all expressible in `render.yaml`. Set them there.
  Note that some toggles exist in the dashboard UI for Static Sites but
  are hidden (or missing entirely) for image-runtime services — declarative
  config is sometimes the *only* reliable way to set them.
- GitHub Actions secrets and repo settings: `gh secret set`, `gh repo edit`.
  Branch protection: `gh api -X PUT repos/.../branches/main/protection`.
- When a user reports doing something in a dashboard, take it as a signal
  to encode that change declaratively in the same PR.

## Always isolate spawned work in a worktree

This repo is actively developed across many parallel sessions, so the main
checkout at `/Users/sameer/workspace/wifihaven` is usually on some in-flight
branch. **Spawning a session or agent that edits files without an isolated
worktree pollutes that working tree and causes branch conflicts.**

Rules:

- When delegating with the `Agent` tool and the agent will edit files, pass
  `isolation: "worktree"`. Read-only research agents (Explore, plain lookups)
  don't need it.
- When spinning off background work with `spawn_task`, write the prompt so
  the spawned session creates its own worktree before doing anything else
  (e.g. `git worktree add .claude/worktrees/<slug> -b <branch>` off the
  latest `main`). State this explicitly in the prompt — the spawned session
  starts with no context.
- Never push to or check out a new branch in the top-level
  `/Users/sameer/workspace/wifihaven` checkout from a spawned session. Treat
  it as someone else's working tree.
- Worktrees live under `.claude/worktrees/<slug>` and use branch names
  `claude/<slug>` by convention (see `git worktree list`).

## Backwards compatibility

**WifiHaven is deployed to prod, so this policy is now IN EFFECT.** The
API and the agents deploy **independently** — there is no longer a tandem
deploy that lets you change both sides of the wire at once. A snapshot the
API emits today may be parsed by an older already-deployed agent, and an
event an older agent posts must still be accepted by a newer API. So the
router↔API request/response shapes (and the policy snapshot in particular)
are a **public contract**.

Rules for any change to a wire-visible shape (API request/response bodies,
the policy snapshot, the usage/event ingest payloads):

- **Additive only.** New fields are fine; renaming, removing, retyping, or
  changing the meaning of an existing field is not.
- **Ignore unknown fields on input.** Both sides must tolerate fields they
  don't recognize, so a newer peer can add fields without breaking an older
  one.
- **Deprecation windows for removals.** To drop a field, stop relying on it,
  ship that, wait for the fleet to roll forward, then remove it in a later
  change — never in the same step.
- **The API may still change freely** as long as it stays backwards
  compatible with already-deployed agents under the rules above.

Non-additive / breaking wire changes are gated on **wire versioning and
capability negotiation** ([#376](https://github.com/wifihaven/wifihaven/issues/376)):
that mechanism is what will eventually let the two sides agree on a shape
before using it. Until #376 lands, treat breaking wire changes as off the
table.

Surfaces that are **not** part of the cross-process wire contract — UCI keys
written and read by the same agent build, CLI flags, and DB schema (guarded
separately by Flyway migrations) — can still change without a deprecation
window, but coordinate them within their own component.

(The flip was driven by the actual prod deploy, not by the permanent-name
decision [#38](https://github.com/wifihaven/wifihaven/issues/38).)

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
docker run -d --name wifihaven-pg \
  -e POSTGRES_USER=wifihaven \
  -e POSTGRES_PASSWORD=secret \
  -e POSTGRES_DB=wifihaven \
  -p 5432:5432 postgres:16

# Copy and edit config
cp config/application.conf.example config/application.conf

# Run API
mill api.run

# Run frontend dev server (Vite — talks to the local API at :8080).
# Self-hosted/install.sh deploys bundle the SPA into the API image; the
# cloud staging/prod environments serve it from Cloudflare Pages instead.
cd web && npm run dev
```

## Testing

```bash
# All Scala tests
mill __.test

# Single module
mill api.test
mill shared.test

# Format check
mill __.checkFormatting

# Fix imports
mill __.fix

# OpenWRT agent tests (requires lua5.1 + busted + lua-cjson)
cd openwrt && LUA_PATH="./files/usr/lib/lua/wifihaven/?.lua;$(lua -e 'print(package.path)')" busted test/

# OPNsense agent tests (requires Python 3 + pytest)
cd opnsense && python -m pytest test/ -v
```

## Database migrations

Migrations live in `api/resources/db/migration/` as `V{n}__{description}.sql`. They run automatically on API startup via Flyway. Never edit existing migrations — always add a new one.

Tests run the same Flyway migrations from `classpath:db/migration` against the embedded Postgres in `TestDatabase`, so there is one source of truth — never maintain a parallel test schema. To change the schema, add a new `V{n}__...sql` migration; do not edit `V1__init.sql` (or any other already-applied migration).

### Schema changes land in their own PR {#migrations-back-compat}

**A PR that adds a Flyway migration may contain only the migration and
documentation (`*.md`) updates.** No source code. **No test changes.**
No CI tweaks. No fixtures, no scripts, no build files. The follow-up
PR — the one that adopts the new schema — carries all of that.

This is the durable defense against the
[#1176](https://github.com/wifihaven/wifihaven/issues/1176) class of
bug (rollback blocked because the applied schema is incompatible with
the previously deployed image). The gate is the existing feature-test
suite:

- `api/test/**` runs embedded Postgres via `TestDatabase`, which
  applies **every** Flyway migration on the classpath, **including the
  new one in this PR**, before any test runs.
- The test fixtures exercise the **existing** production code paths in
  `api/src/**` — image-(N-1)'s code.
- If image-(N-1)'s queries, inserts, or wire shapes can't survive
  DB-at-V<N>, those existing tests fail. That is the gate.

**The gate only works when nothing else in the PR can move.** Test
edits silently bypass it: a fixture author can update the assertions
to the new shape, and the back-compat regression goes undetected. CI
changes can disable the suite entirely. Source changes shift the test
subject. So all of those are forbidden in a migration PR — not just
production source.

Docs are the one exception because they cannot alter execution.

Allowed alongside a new migration:

- `api/resources/db/migration/**.sql` — additional migration files
- `**/*.md` — documentation, including `AGENTS.md`, `CLAUDE.md`,
  READMEs, and anything under `docs/`

Anything else is rejected by
[`check-migration-isolation.sh`](.github/scripts/check-migration-isolation.sh).
That includes `api/test/**` and `shared/test/**` — new tests probing
the new schema shape belong in the follow-up PR, not here.

The workflow is two PRs:

1. **PR 1 — schema only.** Just `V<N>__….sql` (plus any doc updates
   that describe the new shape). The existing `api.test` suite is the
   gate: if it passes, the migration is backward-compatible with
   image-(N-1) — modulo coverage gaps, so close those *before* you
   open the migration PR by adding tests in a prior PR that exercises
   the lines the migration will touch.
2. **PR 2 — code adopts the new schema.** Lands after PR 1 is merged
   and deployed. This is where new repo methods, route handlers, wire
   shapes, and the tests that cover them go.

**Escape hatch:** for genuinely atomic changes (rare), apply the
`migration-coupled-justified` label on the PR and explain in the body
why splitting isn't possible. The check skips when the label is set.

### Migrations that are fast on dev/staging can be minutes-long on prod {#migrations-prod-data-volume}

**A migration's runtime is dominated by prod data volume, which is
orders of magnitude larger than dev/staging.** The embedded Postgres in
`TestDatabase` is empty; staging carries days of data; prod carries the
full accumulated history. A migration that completes in milliseconds
under test can take **many minutes** on prod — and the API runs Flyway
on startup, on the critical path, so a slow migration blocks the
container from binding its port and **Render fails the deploy at the
15-minute port-scan timeout**.

This actually happened: the V41/V42 partition migrations
([#805](https://github.com/wifihaven/wifihaven/issues/805),
[#806](https://github.com/wifihaven/wifihaven/issues/806)) rewrite and
re-index the two highest-growth tables (`traffic_reports`,
`connection_events`). Their own comments assert *"at current volume
this is seconds"* — true against test/embedded volume, false at prod
scale, where the `ATTACH PARTITION` full-scan validation plus index
rebuilds ran past 15 minutes and the forward-roll deploy timed out
(post-mortem: [#1197](https://github.com/wifihaven/wifihaven/issues/1197)).

**Before writing or reviewing any migration, ask: does this touch a
table whose row count grows unbounded in prod?** The unbounded-growth
tables are the event/usage surfaces — `traffic_reports`,
`connection_events`, `block_events`, and the rollup tables that derive
from them. A schema-metadata-only change (add a column, add an
index on a *small* table, add a lookup table) is safe. A change that
**scans, rewrites, copies, re-indexes, or `ATTACH`/`VALIDATE`s** one of
the growth tables is not — assume minutes, not seconds.

For such a migration:

- **Never trust a "this is fast" comment that was measured on
  dev/staging.** State the assumption explicitly and flag that it is
  unverified at prod scale.
- **Prefer index builds that don't hold the critical path.**
  `CREATE INDEX CONCURRENTLY` avoids the long exclusive lock — but it
  **cannot run inside a transaction**, and Flyway wraps each migration
  in one by default. Splitting it out needs a non-transactional Flyway
  migration (`-- flyway:transactional=false` is not our current setup),
  so treat it as a design decision, not a one-liner.
- **Have an offline plan.** If the migration genuinely must rewrite a
  growth table, coordinate with the operator: it may need to run
  out-of-band (operator-applied, deploy paused) rather than on the
  startup critical path, or the table may need to be truncated/archived
  first if the historical data is expendable. The #1197 unblock was a
  one-time `TRUNCATE traffic_reports, connection_events;` — acceptable
  only because losing ~1 week of history was acceptable; do not assume
  that's always on the table.
- **Estimate against prod row counts, not test fixtures.** Ask the
  operator for the live row count of the affected table before deciding
  the migration is safe to run on the startup path.

## Validate query performance before merge {#query-explain-before-merge}

**Any PR that introduces or materially changes a SQL query must prove the
query's plan at prod scale before it merges.** This applies to new
Doobie/SQL in `*RepoLive` implementations and to any rollup/analytics
query. It does *not* apply to trivially-bounded lookups by primary key.

This rule exists because the 2026-05-31 prod incident was a missing index.
The [#809](https://github.com/wifihaven/wifihaven/issues/809) byte-rollup
attribution shipped a `LEFT JOIN LATERAL` over `connection_events` with no
`(mac, dest_ip, ts)` index, so each `traffic_reports` row triggered a
full-day sequential scan. It only manifested in prod under real row counts
— pegging managed-Postgres CPU at 100%, exhausting the HikariCP pool, and
crash-looping the API. A single `EXPLAIN (ANALYZE)` against prod-shaped
data at PR time would have caught it; instead it was diagnosed by hand
mid-incident (root-caused in
[#1254](https://github.com/wifihaven/wifihaven/issues/1254)/[#1256](https://github.com/wifihaven/wifihaven/issues/1256),
[#1240](https://github.com/wifihaven/wifihaven/issues/1240)).

Before the PR merges:

1. **Identify the access pattern.** State the new/changed query, the
   tables and columns it filters and joins on, and the expected prod
   row-count growth of those tables. Call out any table on the
   unbounded-growth list (`traffic_reports`, `connection_events`,
   `block_events`, and the rollups derived from them).
2. **Observe real performance (read-only).** Run
   `EXPLAIN (ANALYZE, BUFFERS)` against **prod or a prod-shaped dataset**.
   Watch for sequential scans on large tables, nested-loop lateral joins
   without a supporting index, and runtime that scales with **table size
   rather than result size**.
3. **Add the needed indexes in the SAME PR.** If the plan shows a missing
   index, add the migration that creates it (partial/covering as
   appropriate) and re-run `EXPLAIN (ANALYZE, BUFFERS)` to confirm the
   plan flips to an index scan. (Mind the migration-isolation rule above:
   if the index migration must ship without code, split per the two-PR
   workflow.) Capture the before/after plan summary in the PR description.
4. **Document the expectation in the PR.** Note which tables the query
   touches and why the chosen indexes cover it, so reviewers can
   sanity-check at scale.

### Safety rules for running EXPLAIN against prod

- **Read-only ONLY.** Only `SELECT` / `EXPLAIN` may be run against prod.
  **Never `EXPLAIN ANALYZE` a writing statement against prod** — it
  executes the write. Wrap-in-transaction-and-rollback is **not** an
  acceptable substitute on prod for write paths; use a prod-shaped scratch
  DB for those.
- **Never echo credentials.** Use the existing prod-DSN handling, capture
  the DSN into a shell var, and mask passwords in any printed output
  (matches the live-debug conventions).
- **Bound the work.** `SET statement_timeout='5s';` before a heavy
  `EXPLAIN (ANALYZE, BUFFERS)` so an accidental expensive plan can't itself
  stress prod.
- **Prefer a prod-shaped dataset or replica where one exists.** Run there
  first; fall back to a prod read-only `EXPLAIN` only when the real prod
  data shape is what's being validated.

> Out of scope here: automated query-plan regression testing in CI — a
> heavier, separate idea. This rule is just the agent-workflow guardrail.

## New functionality ships with metrics {#instrument-new-functionality}

**When you add a meaningful new code path — a route, a background job, a
periodic poller, an external call, an ingest/enforcement step — instrument it
with a metric in the same PR.** The architectural model puts all decision and
aggregation logic server-side in the API (the router is a dumb applier), so the
API process is the one place an operator can see what the system is doing. A
feature that emits no metric is invisible until it breaks.

What "meaningful" means — instrument it when at least one is true:

- It can **fail or be rejected** in a way an operator would want to rate-alert
  on (auth/validation failures, dropped/filtered records, ret-exhausted calls).
  Emit a `*_total{reason}` counter with a **bounded** reason enum.
- It does **work whose latency or volume matters** (a DB query on a
  growth table, a rollup, an external fetch, a request handler). Emit a
  `*_duration_seconds` histogram and/or a throughput counter.
- It reflects **fleet/system state** an operator would check first during an
  incident (connected routers, queue depth, last-success timestamp). Emit a
  gauge.

Rules of thumb:

- **Route the emission through `AppMetrics` / `MetricGuard`** (see
  `api/src/metrics/Metrics.scala`), not a bare `Metric.*` at the call site, so
  the §4 cardinality firewall and the name/label allowlist apply. Add the new
  `(name -> allowed keys)` entry to `MetricGuard.Allowed`.
- **Labels are a small, known enum** — `route` (templated), `op`, `reason`,
  `status`, `job`. **Never** a per-mac / per-domain / per-device / per-ip /
  per-user value; those are forbidden keys and will be rejected.
- **Don't over-instrument.** A pure helper, a trivial getter, or a path already
  covered by the HTTP/DB middleware doesn't need its own series. One good
  counter or histogram beats five redundant ones, and every series costs
  cardinality.
- A new metric then **ships with its dashboard panel** — see the next rule.

## A new metric ships with its dashboard {#metrics-need-a-dashboard}

**A PR that adds or changes an emitted metric series must also add or update
a Grafana panel that consumes it, in the same PR.** A metric nobody can see
is dead weight: it costs cardinality and registry space but never reaches an
operator's eyes until an incident, which is exactly when you don't want to be
authoring PromQL from scratch.

"Emitted metric series" means any new `Metric.counter` / `histogram` / `gauge`
(or `AppMetrics`/`MetricGuard` helper) whose name reaches the `/metrics`
exposition. Adding a label to an existing series counts too if it changes what
an operator would want to slice by.

In the same PR:

1. **Add the panel where it belongs.** Dashboards are checked-in JSON under
   [`deploy/grafana/dashboards/`](deploy/grafana/dashboards/), deployed by
   [`master-grafana.yml`](.github/workflows/master-grafana.yml) via the
   [`infra/grafana`](infra/grafana/) Terraform. Extend an existing dashboard
   when the metric fits its theme (process health vs. application
   self-metrics vs. rollup health); add a new `*.json` and register it in
   `infra/grafana/main.tf`'s `dashboards` list when it's a new concern.
2. **Target the series you actually emit — never a design-doc catalog.** Grep
   `api/src` for the exact metric name and labels and write the PromQL against
   that. Histograms render as `<name>_bucket{le=…}` / `_sum` / `_count` (use
   `histogram_quantile`); the zio-prometheus connector does **not** append
   `_total` to counters, so the name in code is the name in the query. Do not
   ship no-data panels for metrics that aren't emitted yet — defer those to
   the follow-up PR that instruments them.
3. **Keep labels low-cardinality in the query, too.** Slice only by bounded
   label keys (templated `route`, `op`, `reason`, `status`); never by a
   per-mac / per-domain / per-device / per-ip value. If the firewall would
   reject the label, the panel shouldn't group by it.
4. **The CI gate is `grafana-terraform`** ([`ci.yml`](.github/workflows/ci.yml)):
   `terraform fmt -check`, `terraform validate`, and `python3 -m json.tool`
   on every dashboard. Run all three locally before pushing.

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
- Router tokens are single-use enrollment tokens; after enrollment a separate bearer token is issued
- Passwords are bcrypt hashed (cost factor 12)
- Admin vs ReadOnly enforced via JWT claims + middleware
- SQL injection impossible via Doobie parameterized queries
- Config file contains DB credentials — never commit it (in .gitignore)

## TDD workflow (required for new features and bug fixes)

For any new feature or bug fix, follow test-driven development:

1. **Write the test first.** Before implementing, write the unit and/or feature test(s) that describe the desired behavior. For bugs, the test should fail in the way the bug manifests; for features, it should describe the new behavior.
2. **Validate the test logic before implementing.**
   - **Interactive sessions:** show the test to the user and ask them to confirm it correctly describes the intended behavior.
   - **Autonomous / spawned sessions:** commit the failing test as its own commit before any implementation commit. The red-green progression must be visible in the PR's commit history. The reviewer of the PR is the validator.
3. **Only after the test exists, implement the code** to make it pass.

This applies to both unit tests and feature tests — pick whichever level fits the change (see "Testing philosophy" below).

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
