---
name: cd-watchdog
description: Daily health check of WifiHaven Master Router CD + Master API/UI CD. Detects a RED deploy pipeline on main, triages infra flakes (re-run, don't patch), and ONLY for a genuine unaddressed code failure opens a fix PR. Invoke as the daily CD watchdog, or whenever asked "is CD green", "check the deploy pipelines", "why is Master Router CD / Master API-UI CD red", or "watchdog the CD".
---

# CD health watchdog

This skill is the WifiHaven **CD health watchdog** — an autonomous check of the
two deploy pipelines. It runs with a **fresh context and no memory of prior
runs**, so everything it needs is here.

The governing instinct is **conservatism**: detect a RED deploy pipeline and,
ONLY if it is a genuine code failure that nobody is already fixing, open a fix
PR. **Infra flakes get re-run, not patched.** Under-acting beats opening a
duplicate or a wrong PR.

This file is the **process**. Operating guardrails live elsewhere and win on
any disagreement — update this skill if the process itself changed:

- Worktree isolation, TDD red→green, push-over-SSH, single-source-of-truth,
  the back-compat wire contract → [`AGENTS.md`](../../../AGENTS.md).
- The merge-gating independent review →
  [`docs/pr-review-checklist.md`](../../../docs/pr-review-checklist.md).

## Repo

- Local checkout: `/Users/sameer/workspace/wifihaven` (macOS). All git work
  happens in an **isolated worktree** under `.claude/worktrees/<slug>`, branched
  off `origin/main` (never off the current HEAD).
- GitHub: `github.com/wifihaven/wifihaven`. Use the `gh` CLI (already
  authenticated). Push with **SSH** (`git@github.com:wifihaven/wifihaven.git`).

---

## Step 1 — Detect red

```bash
cd /Users/sameer/workspace/wifihaven && git fetch origin
```

For EACH workflow, get the most recent **COMPLETED** run on `main`:

```bash
# Master Router CD
gh run list --workflow=master-router.yml --branch main --limit 5 \
  --json conclusion,status,displayTitle,headSha,url,createdAt,databaseId
# Master API/UI CD
gh run list --workflow=master-api-ui.yml --branch main --limit 5 \
  --json conclusion,status,displayTitle,headSha,url,createdAt,databaseId
```

A workflow is **RED** if its most recent *completed* run on main has
`conclusion == "failure"` — ignore in-progress runs; look at the latest
completed one. If **NEITHER** workflow is red, print **"both green"** and STOP.

---

## Step 2 — Find the ACTUAL failing step

For each RED workflow, drill to the real error before classifying anything:

```bash
gh run view <runId> --json jobs       # which job/step failed
gh run view <runId> --log-failed      # grep this for the real error
```

Master Router CD's **first gate is a shared CI job** (frontend Vitest + Scala
tests). A router-CD failure is frequently **not** Gate 3a (the KVM/router e2e)
but a test in that shared gate — identify which it actually is.

---

## Step 3 — INFRA-FLAKE TRIAGE (do this BEFORE treating anything as a code bug)

### 3.0 — FIRST check the run history, not just the latest commit

**Do NOT call a red a flake just because the commit that triggered the failing
run is unrelated to the failing test.** The triggering commit only tells you
what *ran*, not what *introduced* the break. A pure-content commit (e.g. an
app-template YAML add) can be the run that first surfaces a REAL regression from
an EARLIER merge whose own CD failed-but-merged-anyway or was cancelled before
the failing gate ran.

Before classifying, pull the history and find the **last GREEN** run:

```bash
gh run list --workflow=<file>.yml --branch main --limit 12 \
  --json conclusion,headSha,displayTitle,databaseId,createdAt
```

- **Sustained red** — the SAME gate/test has failed across **≥2 consecutive
  runs on different commits** since some merge → almost certainly a **real
  regression**, regardless of what the latest commit touched. Identify the
  FIRST red and what IT merged (check whether its own run failed/was cancelled
  before this gate ran) — that commit is the suspect, not the latest one.
  Go to Step 4/5; do **not** re-run it as a flake.
- **Endpoint/test-specific** — if earlier probes in the same test pass and only
  one endpoint/test times out, the service is healthy and it's a real code bug,
  not environmental.
- **Isolated + varied** — a single isolated red, or a *different* test/arm
  failing each run with a known infra signature (below) → genuine flake; proceed
  with re-run triage.

> Caught this way 2026-06-27: Gate 3b was red on the unrelated A-Z Animals YAML
> commit (#2010). History showed Gate 3b red for 3 consecutive runs since #1974
> (a `/api/time/status` rewrite that merged despite its own CD failing) — a real
> regression (#2012), not a flake. Re-running would have looped forever.

### 3.1 — Infra-flake signals

Once 3.0 says the red looks isolated, classify it. It is an **INFRASTRUCTURE /
ENVIRONMENT flake** (NOT a code bug) if the failing step shows things like:

- **"docker daemon not responding"** / "restart Docker Desktop" / a Docker stall
  on the self-hosted (plex) runner.
- runner offline/lost, "The runner has received a shutdown signal", job
  cancelled by infra.
- network/registry/timeout errors: artifact download 5xx, `actions/*-artifact`
  failures, registry 429/5xx, DNS/connection resets, transient `git fetch`/clone
  errors.
- **"No space left on device"**, disk/inode exhaustion.
- **QEMU/VM boot or SSH-to-VM timeouts** in the image-build/boot phase (not an
  assertion in our own test code).

If it IS an infra flake:

1. Do **NOT** open a code PR and do **NOT** write any code.
2. Re-run the failed jobs: `gh run rerun <runId> --failed`. Re-run **at most
   twice**, waiting for each to complete.
3. If it then goes **GREEN** → done; print a one-line note that it was a
   transient infra flake auto-recovered by re-run.
4. If it **KEEPS failing on the SAME infra cause** after re-runs → **STOP**.
   Leave a comment on a tracking issue (file one with label `ci`/`ops` if none
   exists) describing the infra failure, the runner, and that it needs operator
   attention (e.g. restart Docker on the plex runner). Do **NOT** open a code PR.

> This class is real and recurring. Master Router CD run `27920333460` failed on
> `docker daemon not responding` during the QEMU VM image build; the fix was a
> re-run after Docker recovered on plex — not a code change.

Only continue to Step 4 when the failure is a **genuine CODE/TEST assertion**.

---

## Step 4 — Don't duplicate work in flight (genuine code failures only)

**SKIP** (open no fix) if ANY of these holds:

- an **OPEN PR** references the failing area/test/issue
  (`gh pr list --state open --json number,title,headRefName,createdAt`);
- a **more recent run** of the SAME workflow on main is already green or
  in-progress;
- an **open issue labeled `in-progress`** tracks this exact failure.

When in doubt, **SKIP and leave a brief comment** — never open a competing PR.
Under-acting beats a duplicate.

---

## Step 5 — Fix it (genuine code failure, unaddressed; do the work yourself)

```bash
git worktree add .claude/worktrees/cd-fix-<slug> -b claude/cd-fix-<slug> origin/main
# cd in; verify `git log --oneline origin/main..HEAD` is empty. ALL paths rooted in the worktree.
```

- Reproduce, write a **failing test first** when applicable, then fix. Run the
  relevant suite:
  - **Scala** → `mill __.test` for affected modules + `mill scalafmtCheckAll` if
    Scala changed.
  - **Frontend** → `cd web && nvm use 22 && npm run test` (vitest needs node 22).
  - **Router Lua** → `busted` in `openwrt/`.
- You **CANNOT run KVM / router-e2e Gate 3a on macOS**. If that's the failure,
  fix by reasoning + Lua unit tests and rely on the PR's CI; **say so in the PR**.
- File a tracking issue if none exists (assignee `sameerparekh`, `in-progress`
  label). Open a PR with **`Fixes #<issue>`**, title
  `fix(cd): <what failed> — <fix>`.

---

## Step 6 — Review + monitor, do NOT merge

- Run the **`/pr-review`** skill; address BLOCKERs + cheap SHOULD-FIX; push;
  re-run until clean.
- Monitor the PR through CI: if checks fail, read, fix, push. If a check fails on
  an **INFRA flake** (Step 3 signals), **re-run it** rather than editing code.
- **NEVER `gh pr merge`; NEVER enable auto-merge** — that is the operator's call.
- Finish when the PR is **green + mergeable**, or when you've left the skip/infra
  notice.

---

## Output

A concise summary:

- each workflow **green/red**;
- for each red: whether it was an **infra flake** (and the re-run outcome) or a
  **code failure** (PR number + URL);
- anything you **skipped** and why.
