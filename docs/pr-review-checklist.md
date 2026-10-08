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

End with **`VERDICT: APPROVE @ <sha>`** or **`VERDICT: REQUEST-CHANGES @ <sha>`**,
where `<sha>` is the full 40-char SHA of the PR head you reviewed (see *Output
format*). **Never APPROVE while an open BLOCKER exists.**

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
- **Sibling-path drift-by-omission = BLOCKER.** This is the other face of SSOT:
  not a value computed twice, but a value *written* by several parallel paths
  where the touched one silently skips a step its siblings perform. When the PR
  adds or edits ONE of several code paths that construct, provision, or mutate
  the same entity — a creation path, an enforcement path, a teardown path — diff
  it against the canonical / sibling path(s): does it perform the SAME set of
  required seeds / invariant writes / cleanups? A path that omits a step a
  sibling performs is a drift-by-omission BLOCKER. The resolution is the SSOT
  resolution — prefer **COLLAPSE** (one shared primitive both paths call) or
  **TYPE-ENFORCE** over hand-maintained parallel paths; a review is not
  addressed by adding the missing step to the fork and leaving the fork. Worked
  invariant for this repo: **every household-creation path must seed
  `households` + `household_billing` + the global-sentinel profile together**
  ([#2355](https://github.com/wifihaven/wifihaven/issues/2355): a second
  creation path seeded the household + sentinel profile but not the billing row,
  and shipped through review as a prod/staging "no billing record" error) —
  there should be exactly one creation primitive. See
  [single-source-of-truth](process/single-source-of-truth.md#single-source-of-truth).
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
- **Tests conform to [`docs/process/testing.md`](process/testing.md)** — that
  file is the authoritative source for how tests are written; **read it and
  check the diff against it, don't re-derive its rules here.** The ones that
  most often surface as findings: **right level** (feature test through the full
  stack vs unit only for the edge cases it enumerates), **mocks — external I/O
  only** (never a repo / `AuthService` / `Clock`; CI-runnable code uses the real
  thing), **`Clock` injected via `TestClock`** — including **no wall-clock
  `ZIO.sleep` waits** for a background fiber / poller / cache to catch up (the
  [#2042](https://github.com/wifihaven/wifihaven/issues/2042) flaky class) — and
  **ZIO primitives for mutable state**. A test that breaks a rule there is a
  finding — **BLOCKER** when it hides a regression (mocked SQL/auth, weakened
  assertion) or gates an enforcement / metric path (a flaky wall-clock wait),
  otherwise SHOULD-FIX.
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
  every other drop. This is the *router enforcement* invariant (#421): whatever
  lands in `extraAllowed` wins. It is **not** a claim that the server always
  puts an app's hosts there — `PolicyService` withholds the carve for a
  **non**-exempt app once the profile's daily cap is exhausted
  (`ProfileAppDispositions.scala:147`), so "the app is allowed, therefore it
  works past the daily limit" is wrong unless the assignment is
  `exemptFromDaily` (architecture.md → "App exempt-from-daily").
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
  fixtures, no build files. (`check-migration-isolation.sh` enforces this
  unconditionally — there is no label opt-out; verify it would pass.)
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

VERDICT: APPROVE @ <full 40-char HEAD sha>        (or: VERDICT: REQUEST-CHANGES @ <sha>)
<3-line summary of the change and the basis for the verdict>
```

Never emit **APPROVE** while any BLOCKER is listed.

**The verdict line is machine-read** by `scripts/pr-merge-gate.sh` to decide
whether the author may merge ([merge rule](#monitor-to-merged)), so its shape
is exact:

- exactly one line in the comment, starting at column 0:
  `VERDICT: APPROVE @ <sha>` or `VERDICT: REQUEST-CHANGES @ <sha>`;
- `<sha>` is the full 40-char lowercase SHA of the commit you reviewed, the
  same SHA as the comment's `reviewed-sha=` marker;
- if you quote an example verdict anywhere else in the comment, indent it or
  put it in a code block so it does not start at column 0.

A verdict naming any SHA other than the current PR head is **void**: the gate
treats it as no review at all.

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

Post with **`gh pr comment <n>`** — a plain PR comment. Do **NOT** use
`gh pr review --approve` / `--request-changes`. Every PR is authored from the
operator's GitHub account and GitHub does not let an author approve their own
PR, so a GitHub review state cannot carry the verdict; it would also interact
with review rules and the merge queue. The APPROVE / REQUEST-CHANGES verdict
lives in the comment body as the SHA-bound `VERDICT:` line (see *Output
format*). That line is the approve signal the [merge rule](#monitor-to-merged)
reads.

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
- **An APPROVE covers one SHA.** Any push after it, including a one-line fix
  for a review finding, voids it until the review is re-run on the new head.

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
its findings as a marked PR comment (`gh pr comment`, never a GitHub
`--approve` / `--request-changes` review; see *Post as a comment* above),
leading with a machine-findable marker that records the reviewed commit
(`<!-- wifihaven-pr-review reviewed-sha=<sha> -->`) and ending with a verdict
line bound to the same SHA (`VERDICT: APPROVE @ <sha>`). On a subsequent push the
review re-runs incrementally: it finds the prior marked comment, **statuses each
prior finding** ADDRESSED / NOT-ADDRESSED / PARTIAL against current code, reviews
only the **incremental delta** (`git diff <reviewed-sha>...HEAD`) for new
findings, and posts an updated marked comment. A BLOCKER clears only when
ADDRESSED *and* no new BLOCKER was introduced; any open BLOCKER stays
merge-gating. See the *Posting & re-runs* section above for the full algorithm.

### Monitor PRs through to MERGED, not just queued {#monitor-to-merged}

**This section is the one definition of the PR merge rule** (decided by the
operator 2026-10-08, [#2869](https://github.com/wifihaven/wifihaven/issues/2869)).
AGENTS.md, skills, and commands link here and do not restate it;
`.github/scripts/check-merge-rule-single-source.sh` fails CI if one does. Its
mechanical check is [`scripts/pr-merge-gate.sh`](../scripts/pr-merge-gate.sh).
If the script and this section disagree, this section is right and the script
is the bug.

The author-side session (the chip that opened the PR) owns it from open to
`MERGED`. The operator can always merge or hold any PR themselves.

#### When the session may merge

The session merges the PR itself when **all** of these hold. Run
`scripts/pr-merge-gate.sh check <n>`; it prints `MERGE <sha>` (exit 0) only
when they do.

1. **APPROVE on the current head.** The latest marked `/pr-review` comment ends
   with `VERDICT: APPROVE @ <sha>`, and `<sha>` (and the comment's
   `reviewed-sha=` marker) equals the PR's current `headRefOid`. An APPROVE for
   any other SHA is void. **Every push after a review, however small, needs a
   re-review before merging.** This is the
   [#2829](https://github.com/wifihaven/wifihaven/issues/2829) failure: a fix
   was pushed after review, the session reported done, and an unreviewed SHA
   was merged. The reviewer emits APPROVE only with no open BLOCKER
   ([output format](#output-format)).
2. **Required checks green on that SHA.** Every required status check on
   `main` succeeded on that exact commit. The script reads the required set from
   branch protection (today it is the umbrella `CI` job) rather than hardcoding
   it.
3. **Open, not a draft, mergeable** (no conflicts).
4. **Not an excluded class** (below).

#### Excluded classes: the operator merges

These still get the full review and must reach APPROVE on HEAD with green CI,
but the session does **not** merge them. Their failure mode is prod-wide and CI
cannot see it (for example a migration that runs for minutes against prod row
counts, [#migrations-prod-data-volume](process/migrations.md#migrations-prod-data-volume)).
Detection is by changed path, including the old path of a rename (`classify`
in the script):

| Class | Changed paths |
|---|---|
| Schema migration | `api/resources/db/migration/**`, or any `V<n>__*.sql` |
| Prod deploy config | `render.yaml`; `infra/cloudflare/**` (DNS, Pages, Workers routes); `**/wrangler*.toml`; `docker/entrypoint.sh` (env and secrets into app config); `.github/workflows/master-*.yml` (the prod CD pipelines and their secrets) |
| Merge-policy machinery | `scripts/pr-merge-gate.sh`, `.claude/commands/pr-review.md`, this file, `.github/scripts/check-merge-rule-single-source.sh`, so a PR cannot loosen the gate it then merges through |

Router agent changes are **eligible**, even though a merge cuts a release the
family router self-installs.

#### How to merge: enqueue with a head guard

"Merge" means **enqueue into the merge queue**. The `Main Protection` ruleset
on `main` allows squash only, requires linear history, and runs a merge queue
(squash, `ALLGREEN`, one entry built and merged at a time) that re-runs CI on
the combined result before landing. Enqueueing a PR authored from the same
account works; the ruleset requires 0 approvals.

```bash
scripts/pr-merge-gate.sh enqueue <n>
```

That runs `check`, then:

1. re-reads `headRefOid` immediately before enqueueing and aborts if it moved;
2. runs `gh pr merge <n> --squash --match-head-commit <approved-sha>`. With a
   merge queue required, `gh` enqueues through `enablePullRequestAutoMerge` and
   sends `--match-head-commit` as its `expectedHeadOid` (gh v2.96.0,
   `pkg/cmd/pr/merge/http.go`);
3. re-reads `headRefOid` afterwards and, if it moved, disarms with
   `gh pr merge <n> --disable-auto` and reports.

Server-side enforcement of `expectedHeadOid` in merge-queue mode has not been
exercised live, so steps 1 and 3 do not rely on it. Never pass `--admin`, and
never arm `--auto` on a head that lacks an APPROVE.

If you push to a PR that is queued or armed, the old APPROVE is void. Check
`gh pr view <n> --json autoMergeRequest,isInMergeQueue`; if either is still set,
disarm with `gh pr merge <n> --disable-auto` first, then re-review and
`enqueue` again.

#### Iterating until done

Once the PR is open the session iterates without waiting for an operator
prompt:

- **Queue CI fails** (Gate 2 port collision from a sibling chip,
  infrastructure flake, etc.) → diagnose, push a fix, re-review, enqueue again.
- **Conflict appears** with another PR that landed first → rebase on
  `origin/main`, resolve, push, re-review, enqueue again.
- **Re-review needed** because new commits got pushed → re-run `/pr-review`,
  address BLOCKERs, push. (This is the AUTHOR side of the re-run-on-push
  behavior in *Posting & re-runs* above.)
- **The operator dequeued or disarmed the PR** (it left the queue with no push
  and no queue-CI failure) → treat it as a hold. Do not enqueue again; report
  and stop.

#### What "done" means

- **Eligible PR:** done only when `gh pr view <n> --json state` returns
  `MERGED`. Queued is not merged.
- **Excluded class:** done when the PR is `OPEN`, mergeable, has an APPROVE on
  HEAD and green CI (`check` prints `OPERATOR <sha>`, exit 3). Say plainly that
  it is **waiting for the operator to merge**, and name the excluded class and
  path the script reported. Do not enqueue it.

Polling cadence is ~5–10 minutes. Use `ScheduleWakeup` for long waits; don't
busy-poll.
