---
description: Independent, read-only adversarial review of a PR — dispatches a separate reviewer agent, which posts a marked comment and re-runs incrementally on each push
---

Target PR: `$ARGUMENTS` (a PR number, or the current branch's PR if empty).

## If you were not spawned as the reviewer: dispatch, do not review

Do **not** apply the checklist in this context. The review has to come from an
agent that did not write the PR and has not seen the conversation that produced
it. A review run in the author's context reads the diff the way the author
meant it. On #2912 the authoring session reviewed its own PR inline and posted
APPROVE over a real blocker, which a separately spawned reviewer then caught.

Push everything first: the reviewer reviews the PR head, and stops if this
worktree differs from it. Then your only action is to spawn the reviewer with
the Agent tool:

```
Agent(
  subagent_type: "general-purpose",
  model: "opus",
  description: "Independent review of PR <n>",
  prompt: "You are the independent reviewer for wifihaven/wifihaven PR <n>.
    Checkout: <absolute path of this worktree>. Read
    .claude/commands/pr-review.md and follow its 'Reviewer instructions'
    section for PR <n>. You did not write this PR. Report back the verdict,
    the findings, and the URL of the comment you posted.")
```

Give the reviewer nothing else: no summary of the PR, no reasoning about it, no
guess at the findings. It must start cold.

- **Re-run after a push:** send the new head to the **same** reviewer agent with
  `SendMessage`, so it can status its own prior findings. If it is gone, spawn a
  fresh one with the prompt above; the re-run flow finds the prior marked
  comment either way.
- **Never** post a review comment, a `VERDICT:` line, or a `reviewed-sha`
  marker from the authoring context, including after fixing the reviewer's
  findings. Fix, push, and re-dispatch.
- A spawned subagent is not a new session, so this is compatible with
  "don't spawn sessions" instructions.

## Reviewer instructions

Follow this section only if your prompt says you are the independent reviewer.

Run the standard WifiHaven **independent PR review** pass, **post it to the PR**,
and on a re-run **status the prior findings + review only the new delta**. Read
`docs/pr-review-checklist.md` in full with the Read tool and follow it verbatim,
including its **Posting & re-runs** section, which is authoritative. (It is not
inlined here, so the dispatching author never loads it.)

Apply the checklist's 9 dimensions to the diff (the 9th, **Unsourced facts &
magic constants**, catches the #2018 class — behavior-driving magic numbers and
"how it works" comments asserted without tracing to an authoritative source),
cite `file:line`, classify every
finding BLOCKER / SHOULD-FIX / NIT, do **not** modify files, and end with the
SHA-bound verdict line plus a 3-line summary. Then post per the algorithm below.

The verdict line is machine-read by the [merge rule](../../docs/pr-review-checklist.md#monitor-to-merged),
so its shape is exact (checklist *Output format*): one line, at column 0,

    VERDICT: APPROVE @ <HEAD_SHA>            or   VERDICT: REQUEST-CHANGES @ <HEAD_SHA>

with `<HEAD_SHA>` the full 40-char SHA from step 1, the same SHA as the marker.
Never APPROVE with an open BLOCKER. A verdict for any other SHA is void, so a
review of an older head never authorizes a merge.

### Flow

1. **Resolve the PR number `<n>` and head SHA.**

   ```bash
   gh pr view <n> --repo wifihaven/wifihaven --json headRefOid -q .headRefOid
   ```

   Then confirm the checkout is exactly that commit, since the verdict is
   stamped with it:

   ```bash
   git fetch origin
   test "$(git rev-parse HEAD)" = "<HEAD_SHA>" && test -z "$(git status --porcelain)"
   ```

   If either test fails, stop and report it instead of reviewing. Do not check
   out or reset anything; the worktree belongs to the author.

2. **Look for a prior marked review comment** (the marker is
   `<!-- wifihaven-pr-review reviewed-sha=<sha> -->`):

   ```bash
   gh api "repos/wifihaven/wifihaven/issues/<n>/comments" --paginate \
     --jq '[.[] | select(.body | contains("<!-- wifihaven-pr-review reviewed-sha="))] | last'
   ```

   - **Empty / null → first run.** Review the full merge-base diff
     `git diff origin/main...HEAD` (three-dot, never two-dot) against the 9
     dimensions.
   - **Found → re-run.** Extract its prior SHA
     (`grep -oE 'reviewed-sha=[0-9a-f]+' | head -1`) and its findings, then:
     a. **Status each prior finding** ADDRESSED / NOT-ADDRESSED / PARTIAL by
        re-checking the `file:line` it cited in current code.
     b. **Review the incremental delta** `git diff <reviewed-sha>...HEAD`
        (three-dot) — the latest push(es) plus context the fix touched — for NEW
        findings.

3. **Post one marked comment** as a plain PR comment (never
   `gh pr review --approve/--request-changes`: GitHub forbids self-approval on
   these operator-authored PRs, and the verdict lives in the comment). Write the
   body to a temp file, leading with the marker for the **current** HEAD SHA and
   ending with the verdict line for the **same** SHA:

   ```bash
   printf '<!-- wifihaven-pr-review reviewed-sha=%s -->\n' "$HEAD_SHA" > /tmp/pr-review-body.md
   # ...append the review body (re-run: prior-findings status table → new findings)...
   printf '\nVERDICT: %s @ %s\n' "$VERDICT" "$HEAD_SHA" >> /tmp/pr-review-body.md   # APPROVE | REQUEST-CHANGES
   # ...append the 3-line summary...
   gh pr comment <n> --repo wifihaven/wifihaven --body-file /tmp/pr-review-body.md
   ```

4. **Merge gate.** A prior BLOCKER clears only when ADDRESSED *and* the latest
   push introduced no new BLOCKER. Any open BLOCKER (still NOT-ADDRESSED /
   PARTIAL, or newly introduced) keeps the verdict at REQUEST-CHANGES and stays
   merge-gating. Post exactly one updated comment per run — don't duplicate a
   comment for the same SHA. Re-read `headRefOid` just before posting; if the
   head moved while you reviewed, review the new delta first rather than posting
   a verdict for a SHA that is already stale.
