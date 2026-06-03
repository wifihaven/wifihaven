# WifiHaven project board

The open issues in `wifihaven/wifihaven` are organized on a **GitHub Projects v2**
board so that "give me an update on the big threads" and "what's in progress" are
answerable from a board view instead of a manual label sweep.

The board is **managed directly in the GitHub UI**. This doc records its
conventions — the Epic taxonomy, what each Status means, and the
umbrella = sub-issue-parent convention — so they stay consistent as issues are
triaged.

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

Convention: keep `Status` in agreement with the issue labels — when you move an
issue to `In Progress` or `Blocked` on the board, set the matching
`in-progress` / `blocked` label (and vice versa) so the two read surfaces don't
drift.

### `Epic` (single-select)

Each open issue is assigned to exactly one epic — the long-running thread it
belongs to. Set it on the item in the board UI.

| Epic | What it covers |
|------|----------------|
| `Observability/Metrics` | `/metrics` endpoint, zio-metrics, Grafana dashboards, agent counters, structured logging. Planning threads #471 / #1240. |
| `Global Policy & Default-Deny` | Household-wide always-on rules, the global allow/block layer, default-deny, unmanaged-MAC policy, infra-allow. Issues #1315–#1322, #1337, #937, #335, #374, #1047. |
| `Dashboard & UX` | Web SPA — the /dashboard redesign (umbrella #1148), profile/device UX, logs, autosave, dark mode, LuCI status surfaces. |
| `Blocklists & Enforcement` | The nftables/dnsmasq enforcement plane, block page, blocklist fetch/refresh, DoH/SNI handling, the OPNsense agent, CNAME/IP-only edges. |
| `CI/CD & Ops` | CI pipelines, release/rollout (canary, rollback), install scripts, deploy safety, infra/ops, Render. |
| `Rollups & Data` | Traffic/connection-event data, weekly partitions, retention, EXPLAIN/query-perf, screen-time rollups, data-quality filters, PSL apex grouping. |
| `Mobile App` | The mobile app thread — architecture, auth/pairing, push, distribution, beta. Issues #981–#987. |
| `Launch & Marketing` | Marketing site, branding/logo system, public-launch readiness. |
| `E2E Test Coverage` | VM/e2e gate coverage, fake-router/fake-API scenarios, test backfill, install-script tests. |
| `Schedules` | Named/reusable schedules, schedule-driven blocklists, schedule enforcement verification. |
| `Other` | Cross-cutting refactors, docs, type-safety, wire-versioning, notifications, and anything that doesn't fit a thread above. |

## Umbrellas are native sub-issue parents

Umbrella issues are wired as real **parents** via GitHub's sub-issue feature (not
just prose lists in the body), so the child issues nest under them in the board
and on the issue page. Current parents:

- **#1240** — Observability planning thread. Parent of the metrics/Grafana/agent-
  counter children (#471 #962 #1208 #1210 #1279 #1280 #1281 #1301 #1302 #1304
  #1305 #1325).
- **#1148** — `/dashboard` holistic redesign. Parent of the dashboard-piece
  issues it supersedes (#849 #856 #859 #860 #862 #892 #951 #995 #1062 #1064
  #1065 #1066 #1078 #1080 #1098).
- **#844** — Connection Events + Traffic Usage w/ rollups. No open issue
  currently references it; add children as they're filed.

The **global-policy** set (#1316–#1322, #1315, #1337) has **no open parent** — its
design issue #1308 is closed. Those issues are grouped via the
`Global Policy & Default-Deny` epic field only, until an open parent exists.

To add a child to an umbrella, use the sub-issue control on the issue page (or the
GraphQL `addSubIssue` mutation).

## Maintaining the board

- **New issues:** add them to the board and set `Epic` + `Status` in the UI.
  (A built-in Projects auto-add workflow can pull every new open issue in
  automatically; set `Status` → `Todo` and pick the `Epic` on triage.)
- **Recategorize:** change the item's `Epic` field.
- **Status:** keep it aligned with the `in-progress` / `blocked` labels.
