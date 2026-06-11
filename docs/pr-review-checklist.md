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
   (three-dot — never two-dot) against the 8 dimensions above.
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
