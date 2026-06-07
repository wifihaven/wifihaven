---
name: epic-prioritization
description: Decide what WifiHaven work to pull next — the orchestration session's repeatable "what should we work on next / next epic / prioritize the backlog / what to spawn" process. Pulls live board + issue + PR state, reconciles stale items first, applies the standing priority stack, orders foundation-first by dependency, and emits a ranked next-1-3 recommendation with rationale. Invoke whenever choosing the next issue(s) to spawn, confirming whether to stay in the current epic, or asking "what's next."
---

# Epic / task prioritization

This skill encodes the **"what do we pull next"** decision the WifiHaven
orchestration session runs. The point is consistency: the decision is derived
the same way every time, from **live** state, not re-improvised or read off a
stale snapshot.

This file is the **process**. The **data** lives elsewhere and is read live —
do not duplicate it here:

- Epic taxonomy, Status meanings, umbrella/sub-issue convention, board IDs →
  [`docs/project-board.md`](../../../docs/project-board.md)
- Operating guardrails (worktree isolation, TDD, migration-isolation,
  back-compat wire contract, single-source-of-truth, metrics-with-dashboards) →
  [`AGENTS.md`](../../../AGENTS.md)
- The merge-gating independent review →
  [`docs/pr-review-checklist.md`](../../../docs/pr-review-checklist.md)

When this skill and those docs disagree, **those docs win** — they are the
source of truth; update this skill if the process itself changed.

---

## Step 0 — Pull live state. Never trust a frozen snapshot.

Everything downstream is wrong if the inputs are stale. At invocation, gather:

```bash
# Open issues (with labels) and open PRs
gh issue list  --repo wifihaven/wifihaven --state open  --limit 200 \
  --json number,title,labels,createdAt
gh pr list     --repo wifihaven/wifihaven --state open \
  --json number,title,headRefName,mergeStateStatus,statusCheckRollup
# Recent merges — what just landed (unblocks gated work, exposes merged-but-open)
gh pr list     --repo wifihaven/wifihaven --state merged --limit 40 \
  --json number,title,mergedAt
```

The org Project #1 board ("WifiHaven", id `PVT_kwDOEQNiBs4BZodj`) carries the
`Epic` and `Status` fields. **Fetch the field + option IDs live** — never paste
IDs from memory, they change:

```bash
gh project field-list 1 --owner wifihaven --format json   # Epic/Status field IDs + option IDs
gh project item-list  1 --owner wifihaven --format json --limit 300  # items + current field values
```

Cross-check the three surfaces against each other — board, issues, PRs. Where
they disagree, the disagreement *is* the first work item (Step 1).

---

## Step 1 — Reconcile FIRST, before recommending anything

Stale board/issue state silently corrupts the recommendation (you'll re-suggest
done work, or skip unblocked work). Fix it before reasoning about priority:

1. **Merged-but-still-open issues.** Our PR titles use `fix(#n)` / `feat(#n)`,
   **not** `Fixes #n`, so merging the PR does **not** auto-close the issue.
   For each recent merge, find the referenced issue, confirm it's truly done,
   then `gh issue close` it and set board **Status = Done**.
2. **In-flight work.** Anything currently spawned / has an open PR / carries the
   `in-progress` label → board **Status = In Progress** (and the matching label).
3. **Blocked work.** `blocked` / `blocked-on-#NNN` label → **Status = Blocked**.
4. **New issues without an Epic.** Triage onto the right epic from the taxonomy
   in `docs/project-board.md`; don't leave real threads in `Other`. Add to the
   board if the auto-add somehow missed it.

> **Serialize board mutations.** Do **not** run two board-mutating operations
> (`gh project item-edit`, `gh issue close`, label changes) concurrently —
> the Projects API races and you get lost updates. One at a time.

Record what you reconciled — it goes in the output so the operator sees what
moved.

---

## Step 2 — Apply the standing priority stack

**Read the current epic order live** from `docs/project-board.md` (epic
taxonomy + umbrellas) and `AGENTS.md`, plus what recent merges show is actually
closing out — do not hardcode it here, it shifts as threads finish.

As of this writing the order is: **App-Centric Model → Schedules (remainder) →
Tuning → …**, with **Observability already done**. We drive **one epic to
closure at a time**, foundation-first, rather than spreading thin across epics —
this avoids write-before-read ordering traps.

Two things jump the queue regardless of the stack:

- **Prod-incident bugs** — triage and fix inline; they preempt feature work.
- **Anti-divergence / de-risking infrastructure** — when duplicated logic is
  *actively* causing bugs, collapsing it preempts feature work because it
  de-risks everything downstream (the #1532 lesson — see worked example).

Default is to **stay in the current epic until it's closed out**. Only switch
when the current epic is done or genuinely blocked on every remaining item.

---

## Step 3 — Within the epic, order foundation-first by dependency

Within the chosen epic, classify every candidate issue:

| Class | Meaning | Recommendable? |
|-------|---------|----------------|
| **merged** | already landed | no — reconcile to Done |
| **unblocked-now** | all prerequisites merged | **yes** — these are the spawn candidates |
| **gated-on-unmerged-prereq** | depends on an open/unmerged PR or issue | no — name the prereq |
| **blocked** | label/soak-gated (e.g. a `blocked-on-#NNN`, a bake/soak window) | no — name the gate |

**Never recommend work whose prerequisite isn't merged.** Read → write
ordering: build the read/primitive/schema before the thing that consumes it.
Schema-only migration PRs land and deploy *before* the code that adopts them
(migration-isolation, `AGENTS.md`). The naming/churn pass goes **last** to avoid
re-touching files mid-arc.

---

## Step 4 — Respect the guardrails when recommending

A recommendation that violates an operating guardrail is not a valid
recommendation. Before naming the next spawn, check it against `AGENTS.md` and
`docs/pr-review-checklist.md`:

- **Single source of truth.** Don't pull work that adds a parallel
  computation/decision path. If a candidate would duplicate an existing path,
  recommend the **collapse first** (#1532 lesson). See
  `feedback_no_duplicate_logic_paths` in memory.
- **Schema-only migrations split from code.** A migration PR carries only the
  `V<n>__….sql` (+ docs); the adopting code is a separate follow-up PR.
- **Spawned work isolates in a worktree off `origin/main`**, fetched fresh —
  never branch off a stale HEAD.
- **TDD red→green** — failing test committed before the implementation.
- **No AI/Claude/Anthropic mentions** in commits/PR titles/bodies (the
  `Co-Authored-By` trailer is fine). **Push over SSH.**
- **Every PR gets the independent review** (`docs/pr-review-checklist.md`)
  before it's authorized to merge — a separate agent, not the author.
- **Monitor merge-queue PRs to MERGED** — queued ≠ done; watch for queue CI
  failures and conflicts.

---

## Step 5 — Output a glanceable decision

Emit a short ranked recommendation the operator can approve at a glance:

1. **Next epic** — the chosen epic, or "stay in `<current>`" with one line why.
2. **Next 1–3 spawnable issues** in dependency order, each with a one-line
   rationale (why it's next, what it unblocks).
3. **Gated / blocked** — what's not recommendable yet and the exact prerequisite
   or gate it's waiting on.
4. **Reconciliation actions taken** — issues closed, statuses moved, epics set
   (from Step 1).

Keep it to a decision, not a report. Example skeleton:

```
EPIC: stay in App-Centric Model (host-sets + presence done; aggregation next)
NEXT:
  1. #1510 aggregation builder — unblocked: schema #1516 merged, presence #1514 merged
  2. #1517 graph — gated on #1510 (consumes the aggregation)
  3. #1515 enforcement cap — unblocked-now, independent of the graph chain
GATED/BLOCKED:
  #1518 naming pass — deferred to LAST (avoid mid-arc churn)
  #1485 — blocked on soak window
RECONCILED:
  closed #1505 (merged as fix(#1505), board→Done); #1514→In Progress
```

---

## Worked example — how we've operated

Concrete reasoning so the process isn't abstract:

**App-Centric Model arc (foundation-first, one epic to closure).** We drove it
in dependency order so nothing consumed an unbuilt primitive:

```
host-sets (#1505)
  → presence primitive (#1514) + rollup schema (#1516, schema-only migration)
    → aggregation builder (#1510)
      → graph (#1517) + enforcement cap (#1515)
        → rename (#1526)
          → presentation (#1519 / #1507)
            → naming pass (#1518)  ← LAST, to avoid mid-arc file churn
```

The schema (#1516) shipped **before** the aggregation that reads it; the naming
pass (#1518) was held to the **end** so it didn't re-touch files still in flight.

**Anti-divergence preempts features (#1532).** A divergence audit ran
mid-stream and its collapses (#1544 / #1545 / #1546) were pulled **first** —
ahead of queued feature work — because the divergence was *actively* producing
logic-change bugs. De-risking infrastructure can jump the queue when it's
making the rest of the work safer.

**Prod bugs jump the queue (#1531 / #1539 / #1513).** Triaged and fixed inline,
ahead of the epic backlog, regardless of which epic they touched.

The throughline: **finish one epic foundation-first; let prod incidents and
active-divergence de-risking preempt; everything else waits its dependency
turn.**
