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
| `Alerting & Paging` | Alert rules, contact points / notification policy, on-call routing, paging strategy, and the declarative alerting Terraform. Turning failure-mode metrics into pages, not just graphable series. Design thread #1381 (split out of #1368 / #1373). |
| `Cost & Infra Spend` | Cutting recurring infra cost — Render plan sizing (API + Postgres), retention/footprint work that unlocks a downsize, vendor sanity checks. Orchestrator #1386. |
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

- **New issues:** every new repo issue is **auto-added** to the board by the
  built-in *Auto-add to project* workflow (Status defaults to `Todo`). The `Epic`
  is not set automatically — pick it on triage.
- **Recategorize:** change the item's `Epic` field.
- **Status:** keep it aligned with the `in-progress` / `blocked` labels.
- **New epic:** when a genuinely large new thread starts that no existing epic
  covers, add a new option to the `Epic` field (Epic field → add option) rather
  than overloading `Other` — then add a row to the taxonomy table above. Reserve
  this for threads worth their own swimlane (an umbrella / multi-issue body of
  work); one-offs stay in `Other`.

## Deciding what to pull next

The repeatable "what do we work on next / what to spawn" process — reconcile
the board, apply the standing priority stack, order foundation-first by
dependency — is encoded as a skill:
[`.claude/skills/epic-prioritization/SKILL.md`](../.claude/skills/epic-prioritization/SKILL.md).

---

## Imported rules (originally in AGENTS.md)

This section used to live in AGENTS.md; the TOC there now points here.

### Project board — every new issue gets an Epic

Open issues are tracked on the **WifiHaven GitHub Project** (org-level Project
**#1**, <https://github.com/orgs/wifihaven/projects/1>). It is managed in the
GitHub UI; the conventions (Epic taxonomy, Status meanings, umbrella =
sub-issue-parent) are documented above in this file.

New repo issues are **auto-added** to the board (Status defaults to `Todo`) by the
project's built-in *Auto-add to project* workflow, so they land on the board even
when no agent is in the loop. The `Epic` is **not** set automatically — set it on
triage.

**When you file or triage a new issue, make sure it has the right `Epic`.**
Don't leave a new issue sitting in the `Other` epic when a real thread fits (and
if it somehow isn't on the board, add it). Steps:

1. Add it if missing: `gh project item-add 1 --owner wifihaven --url <issue-url>`.
2. Set `Epic` to the matching thread from the taxonomy above (Observability/Metrics,
   Global Policy & Default-Deny, Dashboard & UX, Blocklists & Enforcement, CI/CD & Ops,
   Rollups & Data, Mobile App, Launch & Marketing, E2E Test Coverage, Schedules, or
   Other) — judge from the title, labels, and body.
3. Set `Status`: `In Progress` if it carries the `in-progress` label, `Blocked`
   if `blocked` / `blocked-on-#NNN`, otherwise `Todo`.

**Starting a large new thread? Create a new `Epic` option for it** instead of
forcing it into `Other`. Only do this for a thread big enough to deserve its own
swimlane — a new umbrella, or a body of work that will span several issues; a
one-off still goes in `Other`. Add the option in the Project UI (Epic field →
add option) or via GraphQL `updateProjectV2Field` (pass the full option list,
each `{name,color,description}`), then record the new epic in this file so the
taxonomy stays the source of truth.

Field IDs and option IDs are discoverable with
`gh project field-list 1 --owner wifihaven --format json`; set a field with
`gh project item-edit --id <itemId> --project-id <projectId> --field-id <fid>
--single-select-option-id <oid>`.
