# Standard PR review checklist (read-only, adversarial)

This is the prompt for the **independent review pass** every WifiHaven PR gets
before it is authorized to merge (see the *Independent PR review* rule in
[`AGENTS.md`](../AGENTS.md)). Hand it to a review subagent verbatim, or run the
equivalent review command (`/code-review`). The reviewer is a **separate
agent** — not the author self-reviewing. The author self-runs this checklist
before opening the PR, but the independent pass is the gate.

Our prod incidents cluster around a few recurring failure modes — duplicated
logic paths that drift ([#1531](https://github.com/wifihaven/wifihaven/issues/1531) /
[#1539](https://github.com/wifihaven/wifihaven/issues/1539), audit
[#1532](https://github.com/wifihaven/wifihaven/issues/1532)) and test changes
that silently hide regressions (the migration-isolation lesson). A consistent,
checked-in review gate catches them before merge instead of depending on
whoever happens to look.

> Keep this file in sync with the orchestrator-side prompt in
> `reference_pr_review_prompt.md` — they are the same review and must not drift.

---

## How to run this review

You are an **independent reviewer** for a PR on the wifihaven repo. Read the PR
body and the diff:

- GitHub PR: `gh pr diff <n>`.
- Local branch: diff the **merge base** with three-dot syntax
  (`git diff origin/main...HEAD`), **never** two-dot — two-dot over-reports when
  `main` has advanced since the branch diverged.

Review **only** the changes in the diff. Be specific — **cite `file:line`** for
every finding. **Do NOT modify files** — this is a read-only pass.

Classify every finding:

- **BLOCKER** — must be fixed before merge. Merge-gating.
- **SHOULD-FIX** — fix now unless there's a good reason not to; reviewer's call.
- **NIT** — minor / stylistic; non-blocking.

End with **VERDICT: APPROVE** or **VERDICT: REQUEST-CHANGES**. **Never APPROVE
while an open BLOCKER exists.**

---

## 1. Duplicated logic / single source of truth — HIGHEST priority

Shipped real prod bugs ([#1531](https://github.com/wifihaven/wifihaven/issues/1531) /
[#1539](https://github.com/wifihaven/wifihaven/issues/1539)); this is the first
thing to check. Cross-reference the
[single-source-of-truth convention](../AGENTS.md#single-source-of-truth) added
in [#1561](https://github.com/wifihaven/wifihaven/issues/1561).

- **Re-derivation = BLOCKER.** Does new code compute a quantity or decision that
  already exists — minutes-used, is-blocked, block-reason, app engaged-seconds,
  a wire string? If so it MUST call the existing primitive rather than
  recomputing it. The named primitives:
  - `TimeStatusService` day-state / `usedSecondsForProfile` / `usedSecondsByMac`
    — time-used and daily-limit state.
  - `Presence.appSecondsForProfile` — per-app engaged seconds.
  - `BlockReason.asWire` / `BlockReason.fromWire` — the block-reason wire string.
- **"Keep in sync" comments are a smell.** Any `// must mirror`, `// same branch
  as`, `// keep in sync`, hand-copied precedence, or a list duplicated across
  files signals divergence risk. Recommend COLLAPSE (one source) or
  TYPE-ENFORCE (make the compiler prevent drift) — never "keep in sync by hand."
- **Display vs. enforcement source mismatch** (the
  [#1539](https://github.com/wifihaven/wifihaven/issues/1539) trap): does a
  display / UI path read a DIFFERENT source than the enforcement path for the
  same fact? They will drift. Flag it.
- **OK — not a finding:** the intentional wire-shape redundancy the architecture
  mandates. Copying the infra allowlist into every profile's `extraAllowed`
  ([#1311](https://github.com/wifihaven/wifihaven/issues/1311)) and the
  `profiles` map dedup are deliberate; do not flag them as duplication.

## 2. Testing integrity — critical

- **TDD red→green visible in history.** For a new feature or bug fix, is there a
  failing-test commit before the implementation commit (autonomous sessions), or
  a test the user validated (interactive)?
- **BLOCK any change to existing tests that could hide a regression.** Weakened
  or deleted assertions, fixtures retrofitted to match new output, tolerance
  widened without justification, a test skipped / disabled / `ignore`d — any
  such change is a **BLOCKER** unless the PR explains why the OLD assertion was
  actually wrong. (The migration-isolation lesson: test edits silently bypass
  gates, so the gate only holds if test weakening is caught here.)
- **Is the new behavior actually asserted** — not merely compiled or exercised
  without a check? Are negative / edge / boundary cases covered?
- **Right level.** Feature tests through the full stack
  (HTTP → route → service → repo → embedded Postgres) are preferred; unit tests
  are reserved for pure edge cases (policy logic, schedule boundaries, pattern
  matching, time arithmetic).
- **No forbidden mocks.** Never mock repositories, `AuthService`, or `Clock`.
  `Clock` comes via `TestClock`; the DB layer uses real embedded Postgres.
- **No wall-clock waits / timing-sensitive tests.** A test that `ZIO.sleep`s
  (or otherwise blocks on real wall-time) to *wait for* a background fiber,
  poller, cache refresh, or scheduled effect to land — typically under
  `@@ TestAspect.withLiveClock` / a `Clock.live` layer — is **flaky by
  construction** and a **finding** (SHOULD-FIX, or BLOCKER if it gates an
  enforcement / metric path). The clock is injected: drive async work to
  completion **synchronously** (call the single-tick function directly, not
  `.fork` + sleep) and advance time **deterministically** with
  `TestClock.adjust`. A bare `ZIO.sleep` in a test body is the tell. Worked
  example: [#2042](https://github.com/wifihaven/wifihaven/issues/2042) —
  `MetricsExportSpec` forked the rollup loop, `ZIO.sleep(600.millis)`,
  interrupted, slept again, then asserted; it passed in isolation but flaked
  an unrelated PR's CI under 14-worker contention. The standing rule lives in
  [`docs/process/testing.md`](process/testing.md) ("Clock is always injected —
  use `Clock.TestClock`"); this dimension enforces it at review. Legitimate
  live-clock uses (measuring *real* elapsed duration) are exempt but must say
  why inline.
- **Bug fix → regression test that FAILS without the fix.** Confirm the test
  actually pins the bug, not just adjacent behavior.

## 3. Architectural invariants

- **DNS never enforces.** Blocking is connection-layer (nftables forward-drop);
  the block page is HTTP DNAT, not a DNS sinkhole / NXDOMAIN / RPZ. Reject any
  "resolved ⇒ reachable / allowed" reasoning, and any fix described as "allow
  the domain's DNS."
- **Router is a dumb applier.** No schedule evaluation, time accounting,
  category lookup, or role-based defaults added to the openwrt / opnsense agent.
  All decision logic lives server-side in `PolicyService`.
- **Snapshot stays the minimal wire vocabulary.** No NEW top-level field that
  names a *concept* an existing field can carry
  (`extraAllowed` / `extraBlocked` / `blocklistIds` / `blockIpOnly` /
  `blocked` / `blockReason`). Express new policy through the existing functional
  fields.
- **`extraAllowed` beats every block path** — it must override `blocked` and
  every other drop.
- **`Clock` injected** — no direct `java.time` `now`. **ZIO effects** — no
  `throw`; typed sealed errors, not strings. **Config via `zio-config`** — no
  `sys.env` or hardcoded values.
- **Every new router-agent write under `/tmp` is bounded.** `/tmp` is `tmpfs`
  (RAM) on OpenWRT, so an unbounded append-writer is an OOM / router-wedge bug.
  Any new log / spool / journal that grows with traffic, time, or events must
  ship rotation in the SAME PR — joined to the existing
  `wifihaven-rotate-dnsmasq-log` cron, using **copytruncate** (not rename) when
  a long-lived process or `tail -F` follower holds the fd. Fixed-size snapshots
  (atomic whole-file rewrite, e.g. `paths.dns_cache`) are already bounded and
  exempt. BLOCKER if a grow-writer ships with no cap. See AGENTS.md
  [§bounded-tmp-writes](../AGENTS.md#bounded-tmp-writes).

## 4. Backwards compatibility / wire contract

The router↔API request/response shapes (and the policy snapshot in particular)
are a public contract — API and agents deploy independently.

- **Additive only** on any wire-visible shape (snapshot, usage/event ingest,
  API request/response bodies). No renaming, removing, retyping, or
  meaning-changing of an existing field.
- **Tolerate unknown fields on input** — both sides ignore fields they don't
  recognize.
- **Breaking changes are gated on
  [#376](https://github.com/wifihaven/wifihaven/issues/376)** (wire versioning +
  capability negotiation). Until that lands, treat breaking wire changes as off
  the table.

## 5. Migrations

- **Migration PRs are isolated.** A PR that adds a Flyway migration may contain
  ONLY the `*.sql` migration(s) and `*.md` docs — no source, no test, no CI, no
  fixtures, no build files. (`check-migration-isolation.sh` enforces this; verify
  it would pass, or that `migration-coupled-justified` is set with a reason.)
- **Never edit an applied migration.** New schema = new `V{n}__….sql` with a
  unique sequential number.
- **Prod-volume safety.** Does the migration scan, rewrite, copy, re-index, or
  `ATTACH`/`VALIDATE` a growth table (`traffic_reports`, `connection_events`,
  `block_events`, or a derived rollup)? If so, assume minutes not seconds at
  prod scale — flag startup-critical-path risk. `CREATE INDEX CONCURRENTLY`
  avoids the long lock but cannot run inside a transaction (Flyway wraps each
  migration in one). Distrust any "this is fast" comment measured on dev/staging.
- **EXPLAIN-validated index for new SQL.** New or materially changed SQL on a
  growth table must ship the supporting index in the same PR, with an
  `EXPLAIN (ANALYZE, BUFFERS)` plan (prod or prod-shaped) showing an index scan,
  not a sequential scan whose runtime tracks table size.

## 6. Metrics + dashboard

- **New meaningful path emits a metric.** A new route, background job, poller,
  external call, or ingest/enforcement step should emit a metric in the SAME PR
  — a `*_total{reason}` counter for failures/rejections, a
  `*_duration_seconds` histogram for latency/volume, or a gauge for system
  state.
- **Routed through `AppMetrics` / `MetricGuard`** (not a bare `Metric.*`), with
  the new `(name -> allowed keys)` entry added to `MetricGuard.Allowed`.
- **Bounded labels only** — `route` (templated), `op`, `reason`, `status`,
  `job`. **Never** a per-mac / per-domain / per-device / per-ip / per-user
  value; those are forbidden and will be rejected by the cardinality firewall.
- **Ships with a Grafana panel** — checked-in JSON under
  `deploy/grafana/dashboards/`, registered in `infra/grafana/main.tf` when it's
  a new dashboard. Query targets the series actually emitted, slices only by
  bounded labels.

## 7. Scope / hygiene

- **Focused diff.** Limited to the intended files; no unrelated drive-by edits.
- **No dead code or leftover debug logging.** TODOs reference an issue:
  `TODO(#n)`.
- **NO Claude / Anthropic / AI mentions** in commit messages or the PR title /
  body. A `Co-Authored-By` trailer is fine.
- **Branch-diff checks use merge-base (three-dot)** vs `origin/main`, never
  two-dot.

## 8. Security

- **No secrets / credentials committed** (config files with DB creds stay out of
  the repo).
- **Parameterized SQL only** — no string interpolation into SQL; Doobie
  parameters throughout.
- **New routes enforce auth + role.** JWT-authenticated, with the correct
  Admin / ReadOnly check via the middleware.

## 9. Unsourced facts & magic constants

The reviewer that APPROVED the wrong 5-minute `raw` window
([#2018](https://github.com/wifihaven/wifihaven/issues/2018)) missed a
behavior-driving magic number and several "how it works" comments that were
**asserted without being traced to their authoritative source**. Treat every
comment, docstring, and PR-body claim as a **claim to verify, not a fact** —
and, where cheap, spend a tool call to actually check the asserted source (grep
the config / constant) before deciding. Cross-references the
[verify-and-cite convention](../AGENTS.md#verify-and-cite) and
[single-source-of-truth](../AGENTS.md#single-source-of-truth).

- **Magic constants not traced to source.** A new literal encoding a cadence /
  interval / window / size / limit, where the diff doesn't show it derived from
  or cited to an authoritative source (a config option, the data, an existing
  named constant). Durations are the highest-risk class — `% 300`,
  `ofMinutes(5)`, bare `300`, `60`. SHOULD-FIX by default; **BLOCKER when the
  constant drives behavior** (as the `raw` window did).
- **Re-hardcoding a single-sourced value = BLOCKER when it can drift.** A value
  that already lives in one authoritative place (the agent
  `usage_report_interval`, a config option, a shared constant) copied as a
  literal elsewhere. The router↔API usage period is the worked example: it is
  single-sourced at the agent (`usage_report_interval`, default 60s) and rides
  every row as `period_start`/`period_end`; an API-side `300` / `ofMinutes(5)`
  copy is exactly the #2018 bug.
- **Comment / doc asserts behavior the diff doesn't substantiate.** A comment,
  docstring, or PR-body claim about a cadence, granularity, invariant, or "how
  subsystem X works" that the code in the diff doesn't back up (or contradicts).
  Flag it and ask for the citation — don't take the comment's word for it.
- **Comment contradicts the code it annotates** — e.g. a "5-min boundaries"
  comment next to logic that no longer matches. The false comment *"source rows
  are at UTC 5-min boundaries already"* is what cemented the #2018 wrong model.

Worked example: the #2018 `% 300` raw window — a stale agent default
([#101](https://github.com/wifihaven/wifihaven/issues/101)'s `300`) re-hardcoded
into the API in [#846](https://github.com/wifihaven/wifihaven/issues/846)
*after* [#529](https://github.com/wifihaven/wifihaven/issues/529) had moved the
agent to 60s, cemented by a false comment. A behavior-driving, unsourced,
re-hardcoded constant — a BLOCKER under this dimension.

---

## Output format

Report findings in this order, then the verdict:

```
BLOCKERS
1. <file:line> — <what's wrong and why it's merge-gating>
2. ...
(none — if there are no blockers)

SHOULD-FIX
1. <file:line> — <issue and suggested fix>
...

NITS
1. <file:line> — <minor note>
...

VERDICT: APPROVE | REQUEST-CHANGES
<3-line summary of the change and the basis for the verdict>
```

Never emit **VERDICT: APPROVE** while any BLOCKER is listed.

---

## Posting & re-runs

The review does not just get *returned* — it is **posted to the PR** as a
comment, and **re-run on every subsequent push**, statusing the prior findings
and reviewing only the incremental delta. This keeps a single living review on
the PR whose verdict tracks the latest commit.

### The marker

Every posted review comment **leads with a stable, machine-findable marker**
that records the exact commit reviewed:

```
<!-- wifihaven-pr-review reviewed-sha=<HEAD-sha> -->
```

followed by the standard body (BLOCKERS / SHOULD-FIX / NITS / VERDICT +
summary). The marker is what the next run greps for to find the prior review
and learn which commit it last covered. `<HEAD-sha>` is the full 40-char SHA of
the commit the review covers — the PR head at review time.

### Post as a comment, never as a GitHub review

Post with **`gh pr comment <n>`** — a plain, **non-approving** PR comment. Do
**NOT** use `gh pr review --approve` / `--request-changes`: an automated GitHub
*review* can satisfy or conflict with required-human-review rules and interfere
with the merge queue. The APPROVE / REQUEST-CHANGES verdict lives in the comment
body as text, not as a GitHub review state.

Use `--body-file` (write the body to a temp file) rather than `--body` for the
long, multi-line body, so markdown and special characters survive shell quoting:

```bash
gh pr comment <n> --repo wifihaven/wifihaven --body-file /tmp/pr-review-body.md
```

### First run (no prior marked comment)

1. Resolve the PR head SHA: `gh pr view <n> --json headRefOid -q .headRefOid`
   (or `git rev-parse HEAD` when reviewing the checked-out branch).
2. Review the **full merge-base diff** `git diff origin/main...HEAD`
   (three-dot — never two-dot) against the 9 dimensions above.
3. Post the marked comment for that SHA.

### Re-run (a prior marked comment exists)

1. **Find the latest prior review comment** by the marker and extract its
   `reviewed-sha` and its findings:

   ```bash
   # latest comment carrying the marker (null if none → this is a first run)
   gh api "repos/wifihaven/wifihaven/issues/<n>/comments" --paginate \
     --jq '[.[] | select(.body | contains("<!-- wifihaven-pr-review reviewed-sha="))] | last'
   ```

   Pull the prior SHA out of that comment's body:
   `grep -oE 'reviewed-sha=[0-9a-f]+' | head -1`. (PR-conversation comments live
   on the `issues/<n>/comments` endpoint — PR review comments are a different
   endpoint we deliberately don't use.)

2. **Status each prior finding** against the *current* code by re-checking the
   `file:line` it cited:
   - **ADDRESSED** — the cited problem is gone / fixed in current code.
   - **NOT-ADDRESSED** — still present, unchanged.
   - **PARTIAL** — partly fixed; state what remains.

3. **Review the incremental delta** `git diff <reviewed-sha>...HEAD` (three-dot)
   — the latest push(es) plus the context the fix newly touched — against the 8
   dimensions, for **NEW** findings. This is what catches a fix that introduces
   a fresh problem.

4. **Post one updated marked comment** carrying the **new** HEAD `reviewed-sha`,
   containing, in order:
   - a **prior-findings status table** (each prior finding →
     ADDRESSED / NOT-ADDRESSED / PARTIAL),
   - the **new** findings from the delta (classified BLOCKER / SHOULD-FIX / NIT),
   - an **updated VERDICT** + summary.

### Merge gate across re-runs

- A prior **BLOCKER clears only when it is ADDRESSED *and* the latest push
  introduced no new BLOCKER.**
- **Any open BLOCKER** — a prior one still NOT-ADDRESSED / PARTIAL, *or* a newly
  introduced one — keeps the verdict at **REQUEST-CHANGES** and stays
  merge-gating.

### Idempotent, non-spammy

Post **one** updated comment per run. Appending a fresh marked comment (with the
new `reviewed-sha`) is fine and preserves the review history — the marker's SHA
distinguishes runs, and the re-run logic always reads the **latest** marked
comment, so old comments don't cause double-review. Do not post duplicate
comments for the same SHA.

---

## Imported rules (originally in AGENTS.md)

These rules used to live in AGENTS.md; the TOC there now points here.

### Independent PR review (required before merge) {#independent-pr-review}

**Every PR gets an independent review pass before merge using this checklist.**
Spawn a review subagent against the diff (or run `/code-review` / the equivalent
review command); treat **BLOCKERS as merge-gating**. The author should self-run
the checklist before opening the PR, but the **independent pass — a separate
agent, not the author self-reviewing — is the gate.** It is read-only and
adversarial: the reviewer cites `file:line`, classifies findings BLOCKER /
SHOULD-FIX / NIT, and ends with APPROVE or REQUEST-CHANGES, never approving with
an open BLOCKER. The checklist leads with duplicated-logic / single-source-of-truth
(see [Single source of truth](process/single-source-of-truth.md#single-source-of-truth))
and test-integrity, the two failure modes behind our recurring prod incidents.

**The review is POSTED to the PR and RE-RUN on each push.** The reviewer posts
its findings as a marked, **non-approving** PR comment (`gh pr comment` — never a
GitHub `--approve` / `--request-changes` review, so it can't interfere with
required human reviews or the merge queue), leading with a machine-findable
marker that records the reviewed commit
(`<!-- wifihaven-pr-review reviewed-sha=<sha> -->`). On a subsequent push the
review re-runs incrementally: it finds the prior marked comment, **statuses each
prior finding** ADDRESSED / NOT-ADDRESSED / PARTIAL against current code, reviews
only the **incremental delta** (`git diff <reviewed-sha>...HEAD`) for new
findings, and posts an updated marked comment. A BLOCKER clears only when
ADDRESSED *and* no new BLOCKER was introduced; any open BLOCKER stays
merge-gating. See the *Posting & re-runs* section above for the full algorithm.

### Monitor PRs through to MERGED, not just queued {#monitor-to-merged}

**Spawned chips monitor PRs through to MERGED, not just to queued.** The
independent review pass is the gate for ENTERING the merge queue, but the
author chip's job isn't done until the PR's state is `MERGED`. Once queued,
the chip watches the merge queue and iterates without waiting for an operator
prompt:

- **Queue CI fails** (Gate 2 port collision from a sibling chip,
  infrastructure flake, etc.) → diagnose, push a fix, iterate.
- **Conflict appears** with another PR that landed first → rebase on
  `origin/main`, resolve, push.
- **Re-review needed** because new commits got pushed → re-run `/pr-review`,
  address BLOCKERs, push. (This pairs with the re-run-on-push behavior in the
  *Posting & re-runs* section above, which covers the REVIEWER side; this rule
  covers the AUTHOR side of the same lifecycle.)

**The chip NEVER queues a PR for merge itself, and NEVER re-arms
merge-when-ready unless the operator had already armed it.** The
merge-when-ready click is the operator's explicit approval to ship — it is
**required** so the operator approves every change, and a chip running
`gh pr merge … --auto` (or any equivalent) bypasses that approval and is
forbidden. Concretely:

- **First time the PR is ready** → the chip reports "review APPROVE, ready
  for merge-when-ready," and stops. The operator clicks merge-when-ready.
- **After a rebase or queue-CI fix push**, the chip may re-arm
  merge-when-ready (`gh pr merge <n> --auto …`) **only if** the operator had
  already armed it before the push — i.e. `gh pr view <n> --json
  autoMergeRequest` returned a non-null `autoMergeRequest` immediately
  before the push knocked it off. Check that field; if it is null, do
  nothing and report state.
- **If the operator explicitly unqueued the PR** (the chip should treat
  any transition from armed → null as "operator unqueued" unless the chip
  itself just force-pushed), the chip does NOT re-arm. Report and stop.

A chip replies "done" only when `gh pr view <n> --json state` returns
`MERGED`. Polling cadence is ~5–10 minutes — use `ScheduleWakeup` for long
waits, don't busy-poll.
