# WifiHaven project board

The open issues in `wifihaven/wifihaven` are organized on a **GitHub Projects v2**
board so that "give me an update on the big threads" and "what's in progress" are
answerable from a board view instead of a manual label sweep.

The board's **Epic** and **Status** fields are reproducible from the repo: the
issue→epic assignment lives in [`scripts/project-epics.tsv`](../scripts/project-epics.tsv)
and is applied by [`scripts/project-board-sync.sh`](../scripts/project-board-sync.sh).
This doc records the conventions — the Epic taxonomy, what each Status means, and
the umbrella = sub-issue-parent convention — so they stay consistent as issues are
triaged.

## The board

- **Title:** `WifiHaven Roadmap`
- **Owner:** `wifihaven` (org-level Project v2)
- **Number:** 2
- **URL:** https://github.com/orgs/wifihaven/projects/2
- **Project node id:** `PVT_kwDOEQNiBs4BZwFe`

## Reproducing the board

The board is populated from the repo, not by hand:

```bash
# Re-applies Epic (from the TSV) and Status (from each issue's labels)
# to every open issue on Project #2. Idempotent — safe to re-run.
scripts/project-board-sync.sh
```

`scripts/project-board-sync.sh` is idempotent: it adds any open issue that isn't
yet an item, reads its Epic from `scripts/project-epics.tsv`, and derives its
Status from the issue's labels. Re-running it reconciles the board after issues
are filed, closed, or relabeled. When you (re)categorize an issue, edit the TSV
and re-run the script rather than only clicking in the UI, so the repo stays the
source of truth.

The script resolves the Epic/Status field ids and every single-select **option
id by name** from the live project definition at runtime. This is deliberate:
Projects v2 regenerates all of a field's option ids whenever the field is edited
(e.g. to add an option), so hardcoded ids silently rot. Resolving by name means
adding a new Epic option never breaks the sync — only the taxonomy table below
and the TSV need updating.

## Fields

### `Status` (single-select)

| Status | Meaning |
|--------|---------|
| `Todo` | Not started. The default for any issue without an `in-progress` signal. Blocked issues (`blocked` / `blocked-on-#NNN` labels) also rest here until the board grows a dedicated Blocked column. |
| `In Progress` | Actively being worked. Mirrors the `in-progress` label. |
| `Done` | Closed/shipped. |

`Status` is **derived from labels** by the sync script: an issue carrying the
`in-progress` label maps to `In Progress`; everything else maps to `Todo`.
Keep the label and the board column in agreement — set `in-progress` on the
issue when you start work and the next sync moves it.

### `Epic` (single-select)

Each open issue is assigned to exactly one epic — the long-running thread it
belongs to. The assignment is stored in `scripts/project-epics.tsv` and applied
by the sync script. There is intentionally **no `Other` bucket**: every issue
lands in the closest real thread.

| Epic | What it covers |
|------|----------------|
| `Observability & Metrics` | `/metrics` endpoint, zio-metrics, Grafana dashboards, agent counters (dns/blocklist/nftables), cardinality/retention review, structured logging. Planning threads #471 / #1240. |
| `Global Policy & Default-Deny` | Household-wide always-on rules, the global allow/block layer, default-deny, infra-allow, the global-profile thread. Issues #1315–#1322, #937. |
| `Dashboard & UX` | Web SPA — the `/dashboard` redesign (umbrella #1148), profile/device UX, logs, autosave, dark mode, the per-section freshness/collapse work. |
| `Blocklists & Enforcement` | Blocklist sourcing/fetch/refresh, category enforcement, schedule-driven blocklist activation, unmanaged-MAC enforcement, render_spec fixtures. |
| `DNS-Bypass & Attribution` | DoH/DoT egress blocking, SNI-based attribution, `blockIpOnly` carve-outs, port-aware traffic, flow-offload compatibility. |
| `CI/CD & Ops` | CI pipelines, caching, release/deploy safety + auto-rollback, install scripts, Render/infra cost, smoke tests, build matrices. |
| `E2E Test Coverage` | VM/e2e gate coverage, fake-router/fake-API scenarios, resilience suites, install-script tests, test backfill. |
| `Router Agent & Release` | OpenWRT/OPNsense agent behavior, LuCI, staged rollout/canary/rollback, agent health/retry-queue, websocket transport, agent-side renames. |
| `Block Page` | Block-page architecture — captive-portal approach, HTTPS variant, scheduled-downtime reachability, extension-request flow. |
| `Usage & Screen-Time` | Traffic stats, per-site/FQDN breakdowns, screen-time rollups, heartbeat-filter replay, foreground-host heuristics, dest_ip enrichment. |
| `Database & Data Lifecycle` | Schema, weekly partitions, partition-drop retention, future-partition jobs, EXPLAIN/query-perf audits, index cleanup. |
| `API Contract & Type Safety` | Wire-schema versioning, PATCH/symmetric shapes, typed dates/MAC/Hostname/BlockReason, OpenAPI, code structure refactors. |
| `Notifications & Alerting` | New-device alerts (web push / webhook / email), unmanaged-MAC alerting, the alerting strategy/rules/routing design. |
| `Security & Access Control` | Pre-launch security audit, multi-tenant, non-admin/role-scoped views. |
| `Schedules` | Named/reusable household schedules, per-app schedules (models/repo/eval/UI/migration). |
| `Mobile App` | The mobile app thread — architecture, auth/pairing, push, distribution, beta. Issues #981–#987. |
| `Launch Branding & Naming` | Marketing site, branding/naming, leftover familydns→wifihaven rename sweeps. |
| `Cost Reduction` | Cutting recurring infra spend — Render plan sizing (API + Postgres), retention/footprint work that unlocks a downsize, vendor/region sanity checks. Orchestrator #1386, children #1388–#1392, #1258. |

## Umbrellas are native sub-issue parents

Umbrella issues are wired as real **parents** via GitHub's sub-issue feature (not
just prose lists in the body), so the child issues nest under them in the board
and on the issue page. Current parents:

- **#1240** — Observability planning thread. Parent of the metrics/Grafana/agent-
  counter children.
- **#471** — Metrics & observability architecture plan. Sibling planning thread
  under the same epic.
- **#1148** — `/dashboard` holistic redesign. Parent of the dashboard-piece
  issues it supersedes.
- **#1069** — Reusable named schedules. Parent of the per-app-schedule
  implementation issues (#1378 #1379 #1380).

The **global-policy** set (#1315–#1322, #937) is grouped via the
`Global Policy & Default-Deny` epic field only; its design issue #1308 is closed,
so there is no open parent to nest them under.

To add a child to an umbrella, use the sub-issue control on the issue page (or the
GraphQL `addSubIssue` mutation).

## Maintaining the board

- **New issues:** every new repo issue is **auto-added** to the board by the
  built-in *Auto-add to project* workflow (Status defaults to `Todo`). The `Epic`
  is not set automatically — add a row to `scripts/project-epics.tsv` and re-run
  the sync script on triage.
- **Recategorize:** edit the issue's row in `scripts/project-epics.tsv`, then
  re-run `scripts/project-board-sync.sh`.
- **Status:** keep the `in-progress` label aligned; the sync script reads it.
- **New epic:** when a genuinely large new thread starts that no existing epic
  covers, add an option to the `Epic` field (Epic field → add option) and add a
  row to the taxonomy table above. The sync script resolves options by name, so
  it needs no change. Reserve new epics for threads worth their own swimlane.
