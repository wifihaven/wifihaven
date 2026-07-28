# AGENTS.md — WifiHaven

This file provides context for AI coding agents (Claude, Copilot, Cursor, etc.) working on this codebase.

**How to use this file.** Read the *Architectural model* section in full — it is
high-rep and must always be in context. Then use the *Where to look* TOC to load
the detail file(s) for the topic(s) relevant to your task. Each TOC entry
includes a one-paragraph summary so you can decide whether to read the detail
file. Anchor IDs are preserved on TOC entries so existing
`AGENTS.md#<anchor>` cross-references continue to resolve.

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
- `blockReason: Option[MacBlockReason]` — carries the per-MAC reason from
  `PolicyService` to the router so `conntrack.lua` can label per-MAC drops
  on `POST /api/router/events` with `Paused` / `Schedule` / `TimeLimit` /
  `Manual`. **Not used for enforcement**: the router decides whether to
  drop based on `blocked`, never on `blockReason`. **Not used for block-page
  text** either — the block page derives the canonical reason from
  `GET /api/blocked` (which re-runs `PolicyService.decide(mac, host)`); the
  router's HTTP/80 redirect carries only `?mac=&host=` (see
  [#679](https://github.com/wifihaven/wifihaven/issues/679) /
  [#1615](https://github.com/wifihaven/wifihaven/issues/1615) /
  [#1617](https://github.com/wifihaven/wifihaven/issues/1617) /
  [#1618](https://github.com/wifihaven/wifihaven/issues/1618)).
  `MacBlockReason` is a `sealed trait extends BlockReason` with six cases:
  `Paused`, `Schedule`, `TimeLimit`, `Manual`, `Unmanaged` (#1122 — device has
  no profile assignment under a `block` household policy), `DefaultDeny`
  (#1316/#1308 — profile is in default-deny mode; lowest precedence) — the
  only reasons a whole-MAC block can come from `PolicyService`.
  **`Manual` has no producer today** — nothing in `api/src` ever sets it, only
  defensive consumer-side handling exists (`BlockedRoutes.scala`,
  `nft_drops.lua`); it is reserved vocabulary, not a shipped feature
  ([#2087](https://github.com/wifihaven/wifihaven/issues/2087)). Per-flow drop
  reasons (host-block, category-block, ip-only-block) are emitted by the
  router at drop time and never appear in the snapshot; they live on
  `BlockReason` but not `MacBlockReason`, so the type system prevents them
  from being assigned here. **The router cannot derive this locally**, and
  re-deriving it API-side at ingest is wrong: the schedule / time-used /
  paused-flag / no-profile-assignment / default-deny-mode state behind these
  cases lives in `PolicyService` and is deliberately NOT shipped on the
  snapshot per the minimal-functional-shape rule below — so the router only
  sees `blocked=true` and can't tell the cases apart; and `PolicyService.decide`
  run at ingest time evaluates *current* policy, which can change between drop
  and ingest (e.g. a profile unpaused in the interim would mislabel the
  historical event). The drop-time reason has to ride the snapshot.
- `extraBlocked: List[Hostname]` — hosts blocked for this MAC. Enforced
  via nftables drop on `(mac, dst ip ∈ ipset(host))`, where the ipset is
  populated by dnsmasq's `--ipset=` callback at resolution time.
- `extraAllowed: List[Hostname]` — hosts allowed for this MAC, including
  as a carve-out when `blocked = true`. An Allowed-mode app's host-set
  normally lands here; when the app's `allowedDuringScheduleBlock = false`
  (`app_policy_assignments.allowed_during_schedule_block`, V56, #1679) and
  the profile's block reason is `Schedule`, `PolicyService` omits those
  hosts so the schedule drop applies. Paused/TimeLimit/Manual blocks are
  unaffected (Schedule scope only per #1679).
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

> **Authored policy has two tiers only: global and profile. There is NO
> per-device override authoring surface, and we are not adding one (decided
> 2026-06; [#1452](https://github.com/wifihaven/wifihaven/issues/1452) closed
> as won't-do).** The `DevicePolicy.rules` override above is a *wire mechanism*,
> not a feature: the only thing that populates an inline `rules` today is the
> server-side **unmanaged-MAC block** path (a device with no `profileId` under
> a `block` household policy). A managed device always inherits its profile's
> resolved `BlockRules` (`rules = None` on the wire). Operators express policy
> by editing **global** rules and **profile** rules and assigning devices to
> profiles — full stop. So: do **not** add a `devices.rules` column, repo
> method, route, `Device.rules` field, or SPA editor for per-device overrides;
> do **not** call the `rules` field a user-facing "device override" in docs or
> UI. The override **wire capability** stays (it is additive, already used by
> the unmanaged path, and removing it would be a breaking wire change) — but it
> is off-limits as an authoring concept. See `docs/architecture.md` §0.2
> "Scope decision (2026-06)".

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
  `blockReason` is the one intentional exception, and it exists only to
  label per-MAC drop events the router emits to `POST /api/router/events`
  (the router can't distinguish `Paused` / `Schedule` / `TimeLimit` /
  `Manual` locally, and re-deriving at ingest time would mislabel events
  when policy changed between drop and ingest) — it is never read for
  enforcement, and it is not what the block page renders. Do not add "why"
  metadata for allow/deny decisions; the server resolves policy into
  functional data and the router applies it blind.
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
> the wire. Post-[#679](https://github.com/wifihaven/wifihaven/issues/679)
> `blockReason` exists for the `POST /api/router/events` connection_event
> labeling path only (`render.update_shared` → `blocked_reason[mac]` →
> `conntrack.lua`); the block-page handler does not read it. (The
> category-enforcement path was a silent no-op on prod until
> [#1334](https://github.com/wifihaven/wifihaven/issues/1334) fixed the
> agent's blocklist-fetch call.)

### Stating facts about the system — verify and cite, never confabulate {#verify-and-cite}

Any statement of a constant's value, a cadence / interval / window, a schema
fact, or "how subsystem X works" must be traced to and cited from its
authoritative source in the repo (config, the code that sets it, the migration)
— **not** from a comment (a comment can be the bug), from memory, or from
inference. A single-sourced value is read/derived, never re-hardcoded; when you
can't verify, say "unknown / unverified" and stop rather than confabulating a
plausible explanation. → see [`docs/process/verify-and-cite.md`](docs/process/verify-and-cite.md)

## Where to look — topic TOC

Each entry below summarizes one previously-inline AGENTS.md section. Anchors
are preserved so existing `AGENTS.md#<anchor>` cross-references resolve. The
detail file holds the full rule text.

### Architecture, domain concepts, policy decision pipeline
What WifiHaven is; repo layout; SPA hosting split (self-hosted bundles SPA into API, cloud uses Cloudflare Pages); key API surface (`/api/router/*`, `/api/blocklists/*`); domain vocabulary (Profile, Device, Schedule, TimeLimit, App, App time limit, TimeUsage, TimeExtension, BlocklistDomain, QueryLog, Location); server-side policy decision pipeline (paused → schedule → daily limit → app limit → manual → extraBlocked → extraAllowed → blocklists → blockIpOnly). → see [`docs/architecture.md`](docs/architecture.md)

### Project board — every new issue gets an Epic
Org-level Project #1; new issues auto-add (Status defaults `Todo`) but `Epic` is set on triage; start a new `Epic` option only for threads big enough to deserve their own swimlane. The live `Epic` taxonomy is **not** mirrored in-repo — fetch via `gh project field-list 1 --owner wifihaven --format json` (board at https://github.com/orgs/wifihaven/projects/1). → see [`docs/process/project-board.md`](docs/process/project-board.md)

### Tech stack decisions
Scala 3 + ZIO 2, ZIO HTTP, Doobie, Flyway, Lua on OpenWRT, jwt-scala, Mill, React + Vite + TypeScript, Tailwind. → see [`docs/process/tech-stack.md`](docs/process/tech-stack.md)

### Coding conventions
ZIO effects (no exceptions), typed errors as sealed traits, config via `zio-config` HOCON, SQL only in `*RepoLive`, ZLayer wiring, ZIO Test, scalafmt + scalafix enforced in CI. → see [`docs/process/coding-conventions.md`](docs/process/coding-conventions.md)

### No dark-by-default — required config fails loud {#no-dark-by-default}
A feature that requires config must fail loud when it's missing/invalid — a typed `zio-config` startup error that crashes boot, or a loud alerting runtime error — never a silent no-op; absence of a secret is a bug, not a disable switch. Set the secret in every target environment before (or atomically with) the dependent code, which assumes it's present and does not defensively degrade to off. Genuinely-optional-off must be an explicit named flag (logged at startup, in a health/config endpoint), never an unlabeled silent branch. Startup validation reports ALL missing required keys at once. The same bar covers runtime best-effort / enrichment side-writes, split by failure cause: a broken/under-scoped credential, missing permission, or unprovisioned dependency (a 401/403) is a misconfiguration — the integration is broken, so fail (the request, or better at provisioning/startup); metering a 403 and continuing is the same silent-degradation bug. Only genuinely-transient / optional failures (flakes, timeouts, non-critical enrichment) may be skipped so the request survives — and even then are logged + metered with an attributable `{outcome}`. The Plain `upsertTenantEntitlement` path (#2410) is the worked example. Grep-able anti-patterns: `sys.env.get(...).getOrElse(<disabled>)`, `Option[...]` config that gates whether a feature runs, `enabled` flags derived from secret presence, feature flags defaulting off on absence, best-effort side-writes that log-and-continue with no metric. → see [`docs/process/no-dark-by-default.md`](docs/process/no-dark-by-default.md)

### Single source of truth — never duplicate a decision or computation {#single-source-of-truth}
The same logical quantity or decision (e.g. minutes used today, the block reason, engaged seconds for an app) must be computed in exactly ONE place; every other consumer calls it. Resolve unavoidable proximity by COLLAPSE or TYPE-ENFORCE; otherwise ACCEPT + TEST-PIN. Wire-shape duplication mandated by the architecture is a carve-out, not a violation. → see [`docs/process/single-source-of-truth.md`](docs/process/single-source-of-truth.md)

### Prefer declarative config over dashboard toggles
Configure infra declaratively in-repo (Render `render.yaml`, GitHub Actions, `gh` API, DNS zone file) instead of clicking around vendor dashboards. → see [`docs/process/declarative-config.md`](docs/process/declarative-config.md)

### Always isolate spawned work in a worktree
Spawned sessions/agents that edit files must use an isolated git worktree under `.claude/worktrees/<slug>` — the top-level checkout is usually on some other in-flight branch. → see [`docs/process/worktree-isolation.md`](docs/process/worktree-isolation.md)

### Backwards compatibility — the router↔API wire is a public contract
API and agents deploy independently. Wire-visible changes are additive only, both sides ignore unknown fields, removals need a deprecation window. Non-additive changes are gated on capability negotiation (#376). UCI keys, CLI flags, and DB schema are NOT part of the wire contract. → see [`docs/process/wire-contract.md`](docs/process/wire-contract.md)

### Docker inside Claude Code agents (worktrees)
Docker commands hang indefinitely if Docker Desktop is degraded; verify `docker info` responds in <2s before any docker command. → see [`docs/process/docker.md`](docs/process/docker.md)

### Running locally + testing commands
Postgres in Docker, `mill api.run`, `cd web && npm run dev`, `mill __.test`, plus OpenWRT (`busted`) and OPNsense (`pytest`) command incantations. → see [`docs/process/local-dev.md`](docs/process/local-dev.md)

### Database migrations — schema-only PR {#migrations-back-compat}
A migration PR contains only the migration SQL and `*.md` docs — no source, tests, CI, fixtures, build files — so the existing feature-test suite can act as the back-compat gate. The gate is unconditional (no label opt-out; #2098). → see [`docs/process/migrations.md`](docs/process/migrations.md#migrations-back-compat)

### Migrations that are fast on dev/staging can be minutes-long on prod {#migrations-prod-data-volume}
Migrations that scan/rewrite the unbounded-growth tables (`traffic_reports`, `connection_events`, `block_events`, rollups) can take minutes on prod and time out the 15-minute Render port-scan; estimate against prod row counts, not test fixtures. → see [`docs/process/migrations.md`](docs/process/migrations.md#migrations-prod-data-volume)

### One-shot migration/backfill/seeder code is deleted after it deploys {#delete-deployed-one-shots}
Scala-level one-shot migrations/backfills/seeders get a follow-up issue under #1608 to delete them after the deploy propagates; leaving them in risks re-runs (e.g. #1602). → see [`docs/process/migrations.md`](docs/process/migrations.md#delete-deployed-one-shots)

### Validate query performance before merge {#query-explain-before-merge}
Any PR introducing or materially changing a SQL query must prove its plan at prod scale: identify the access pattern, run `EXPLAIN (ANALYZE, BUFFERS)` against prod-shaped data (READ-ONLY; never `EXPLAIN ANALYZE` a write on prod), add needed indexes in the same PR. → see [`docs/process/query-perf.md`](docs/process/query-perf.md)

### New functionality ships with metrics {#instrument-new-functionality}
A meaningful new code path (route, background job, poller, external call, ingest/enforcement step) ships with a metric in the same PR, routed through `AppMetrics`/`MetricGuard` with bounded label enums — never per-mac / per-domain / per-device labels. → see [`docs/process/instrumentation.md`](docs/process/instrumentation.md#instrument-new-functionality)

### A new metric ships with its dashboard {#metrics-need-a-dashboard}
The same PR that emits a new metric series adds or updates a consuming Grafana panel under `deploy/grafana/dashboards/`, targeting the series actually emitted (grep `api/src` — don't author from a design-doc catalog). CI gate is `grafana-terraform`. → see [`docs/process/instrumentation.md`](docs/process/instrumentation.md#metrics-need-a-dashboard)

### Grafana Cloud stack
`https://wifihaven.grafana.net` hosts app metrics (Alloy), Render infra OTLP, deploy annotations. Repo secrets `GRAFANA_CLOUD_URL` + `GRAFANA_CLOUD_ANNOTATION_TOKEN`. The API also ships app logs to Grafana Cloud **Loki** — to **query** them for debugging/incidents (LogQL via the Explore UI, or scriptably via `logcli` / the query_range HTTP API using the read-only access-policy token in the operator's local memory), prefer Loki over `render logs` (a fallback). → see [`docs/ops/grafana-cloud.md`](docs/ops/grafana-cloud.md) (§ *Querying logs from Loki* `{#querying-logs}`)

### Never render real-looking data before real data has loaded {#loading-states}
A pending/loading query MUST show a loading state (spinner/skeleton), never a placeholder that looks like a real value — `0m`, `0`, empty counts, "no usage", an empty chart. A loading `0` is indistinguishable from a genuine zero and has masked real bugs (a slow per-profile query made `/profiles` show `0m` everywhere — a perf problem read as data loss). Distinguish three states in every data view: **loading** (spinner/skeleton), **error** (error affordance, not zero), **loaded** (real value, may legitimately be 0). In react-query terms: gate on `isPending`/`isError` before reading `data`; never coerce `undefined`/loading to `0`. → see [`docs/process/loading-states.md`](docs/process/loading-states.md#loading-states)

### Every router-agent write to `/tmp` must be bounded {#bounded-tmp-writes}
On OpenWRT `/tmp` is `tmpfs` — RAM. Any agent file under `/tmp` that grows with traffic/time/event volume must ship a rotation/truncation path in the SAME change; add it to the existing `wifihaven-rotate-dnsmasq-log` cron (copytruncate, never rename). → see [`docs/process/router-agent-bounded-writes.md`](docs/process/router-agent-bounded-writes.md)

### Branch-diff checks (CI + pre-push)
Branch-vs-main diffs MUST use the merge base (three-dot `origin/main...HEAD` or `git merge-base`), not two-dot `origin/main..HEAD`. Pre-commit checks operate on staged files. → see [`docs/process/branch-diff-checks.md`](docs/process/branch-diff-checks.md)

### Adding a new API route
Models → repo trait → repo impl → route → register in Main → tests → TS client. → see [`docs/process/api-route-checklist.md`](docs/process/api-route-checklist.md)

### Security notes
JWT secret ≥32 chars, router enrollment tokens single-use, bcrypt cost 12, Admin/ReadOnly via JWT claims + middleware, Doobie parameterized queries, config file not in repo. → see [`docs/process/security.md`](docs/process/security.md)

### TDD workflow (required for new features and bug fixes)
Write the test first; for autonomous/spawned sessions, commit the failing test as its own commit so the red→green progression is visible in PR history. → see [`docs/process/tdd.md`](docs/process/tdd.md)

### Independent PR review (required before merge) {#independent-pr-review}
Every PR gets an independent review pass by a separate agent (not the author) before merge using `docs/pr-review-checklist.md`; BLOCKERs are merge-gating. Review is POSTED as a marked, non-approving PR comment and RE-RUN on each push, statusing prior findings ADDRESSED / NOT-ADDRESSED / PARTIAL and reviewing only the incremental delta. → see [`docs/pr-review-checklist.md#independent-pr-review`](docs/pr-review-checklist.md#independent-pr-review)

### Monitor PRs through to MERGED, not just queued {#monitor-to-merged}
The author-side chip monitors the PR through to MERGED: iterate on queue CI failures / conflicts / re-reviews without an operator prompt, but **never** call `gh pr merge` or arm `--auto` (merge-when-ready is the operator's explicit approval); only re-arm `--auto` if the operator had already armed it before the iteration push. Reply done only when `gh pr view <n> --json state` returns `MERGED`. → see [`docs/pr-review-checklist.md#monitor-to-merged`](docs/pr-review-checklist.md#monitor-to-merged)

### Testing philosophy
Feature/functional tests through the full call stack on embedded Postgres — never mock `*Repo`. Unit tests reserved for pure-function edge cases. Clock is always injected (`wifihaven.shared.Clock`) — use `Clock.TestClock` in tests. ZIO primitives for mutable state; mocks only for external I/O. → see [`docs/process/testing.md`](docs/process/testing.md)
