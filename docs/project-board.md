# WifiHaven project board

The open issues in `wifihaven/wifihaven` are organized on a **GitHub Projects v2**
board so that "give me an update on the big threads" and "what's in progress" are
answerable from a board view instead of a manual label sweep.

The board's taxonomy and per-issue assignments are reconciled from this repo by
[`scripts/project-board-sync.sh`](../scripts/project-board-sync.sh) — **that
script is the source of truth, not hand-edits in the UI.** Two concurrent
hand-edit sessions once raced and corrupted the Epic options and item
assignments; running the sync script converges the board back to what the repo
says. This doc records the conventions (Epic taxonomy, Status meanings,
umbrella = sub-issue-parent) so they stay consistent.

> **One board.** There is exactly one board. A second, parallel board
> (`WifiHaven Roadmap`, org Project #2) was created during the corruption episode
> as a curated subset; it has been consolidated away. Do not create a second
> project — extend this one.

## The board

- **Title:** `WifiHaven`
- **Owner:** `wifihaven` (org-level Project v2)
- **Number:** 1
- **URL:** https://github.com/orgs/wifihaven/projects/1

## Fields

### `Status` (single-select)

| Status | Meaning |
|--------|---------|
| `Todo` | Not started. The default for any issue without an `in-progress`/`blocked` signal. |
| `In Progress` | Actively being worked. Mirrors the `in-progress` label. |
| `Blocked` | Waiting on something else. Mirrors the `blocked` label or any `blocked-on-#NNN` label. |
| `Done` | Closed/shipped. |

The sync script sets `Status` from issue labels: `in-progress` → `In Progress`;
`blocked` / `blocked-on-*` → `Blocked` (falling back to `Todo` if the board has
no `Blocked` option); otherwise `Todo`. Keep `Status` in agreement with the
labels — when you move an issue on the board, set the matching label (and vice
versa) so the two read surfaces don't drift.

### `Epic` (single-select)

Each **open** issue is assigned to exactly one epic — the long-running thread it
belongs to. The assignment lives in
[`scripts/project-epics.tsv`](../scripts/project-epics.tsv) (`issue<TAB>epic`);
edit that file and re-run the sync script rather than setting the field by hand.
There is intentionally **no `Other` catch-all** — every open issue gets a real
thread (use best judgment for a new issue and add a row to the TSV).

| Epic | What it covers |
|------|----------------|
| `Observability & Metrics` | `/metrics` endpoint, zio-metrics, Grafana dashboards, agent counters, structured logging, cardinality/retention. Planning threads #471 / #1240. |
| `Global Policy & Default-Deny` | Household-wide always-on rules, the global allow/block layer, default-deny, unmanaged-MAC policy, infra-allow. Issues #1315–#1322, #937. |
| `Dashboard & UX` | Web SPA — the `/dashboard` redesign (umbrella #1148), profile/device UX, logs, autosave, dark mode, profile photos/clone, LuCI status surfaces. |
| `Blocklists & Enforcement` | The nftables/dnsmasq enforcement plane, blocklist fetch/refresh, category lists, CNAME/IP-only enforcement edges. |
| `DNS-Bypass & Attribution` | DoH/DoT/hard-coded-IP bypass, `blockIpOnly`, hostname↔IP attribution, unknown-device discovery from DNS. |
| `CI/CD & Ops` | CI pipelines, release/rollout, install scripts, deploy safety, infra/ops, Render, docker image, internal refactors. |
| `E2E Test Coverage` | VM/e2e gate coverage, fake-router/fake-API scenarios, test backfill, install-script tests. |
| `Router Agent & Release` | The OpenWRT/OPNsense agents, agent packaging/self-update, LuCI app, router config/docs. |
| `Block Page` | The HTTP/80 DNAT block page — content, theming, per-reason copy, the served endpoint. |
| `Usage & Screen-Time` | Per-device usage tracking, time limits/extensions, site-time limits, screen-time surfacing. |
| `Database & Data Lifecycle` | Schema/migrations, partitions, retention, rollup tables, dead-index cleanup, data lifecycle. |
| `API Contract & Type Safety` | Wire request/response shapes, PATCH/symmetric resources, wire versioning (#376), typed domain errors. |
| `Notifications & Alerting` | Alert rules, contact points / notification policy, paging, and operator-facing notifications. |
| `Security & Access Control` | Auth, JWT claims, admin vs read-only, router-token handling, access-control hardening. |
| `Schedules` | Named/reusable schedules, schedule-driven blocklists, schedule enforcement verification. |
| `Mobile App` | The mobile app thread — architecture, auth/pairing, push, distribution, beta. Issues #981–#987. |
| `Launch Branding & Naming` | Marketing site, branding/logo system, naming, public-launch readiness. |
| `Cost Reduction` | Cutting recurring infra cost — Render plan sizing (API + Postgres), retention/footprint downsizes, service consolidation. Orchestrator #1386. |

## Umbrellas are native sub-issue parents

Umbrella issues are wired as real **parents** via GitHub's sub-issue feature (not
just prose lists in the body), so the child issues nest under them in the board
and on the issue page. Current parents:

- **#1240** — Observability planning thread. Parent of the open
  metrics/Grafana/agent-counter children (#471 #1210 #1280 #1301 #1302 #1325).
- **#1148** — `/dashboard` holistic redesign. Parent of the dashboard-piece
  issues it supersedes (#849 #856 #859 #860 #862 #892 #951 #995 #1062 #1064
  #1065 #1066 #1078 #1080 #1098).
- **#844** — Connection Events + Traffic Usage w/ rollups. No open issue
  currently references it; add children as they're filed.

The **global-policy** set (#1315–#1322, #937) has **no open parent** — its design
issue #1308 is closed. Those issues are grouped via the
`Global Policy & Default-Deny` epic field only, until an open parent exists.

To add a child to an umbrella, use the sub-issue control on the issue page (or the
GraphQL `addSubIssue` mutation).

## Maintaining the board

- **New issues:** every new repo issue is **auto-added** to the board by the
  built-in *Auto-add to project* workflow (Status defaults to `Todo`). The `Epic`
  is not set automatically — add an `issue<TAB>epic` row to
  `scripts/project-epics.tsv` on triage and re-run the sync script.
- **Recategorize:** edit the issue's row in `scripts/project-epics.tsv`, re-run
  the sync script.
- **Status:** keep it aligned with the `in-progress` / `blocked` labels; the sync
  script derives it from them.
- **New epic:** when a genuinely large new thread starts that no existing epic
  covers, add it to `CANONICAL_EPICS` in the sync script **and** a row to the
  taxonomy table above, then re-run. Reserve this for threads worth their own
  swimlane (an umbrella / multi-issue body of work).
- **Reconcile anytime:** `scripts/project-board-sync.sh` is idempotent. Run it
  (or `DRY_RUN=1 scripts/project-board-sync.sh` to preview) to prune closed
  items, add missing open issues, and re-assert every Epic/Status from the repo.
