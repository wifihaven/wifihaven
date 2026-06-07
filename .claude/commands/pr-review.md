---
description: Independent, read-only adversarial review of a PR — posts a marked comment and re-runs incrementally on each push
---

Run the standard WifiHaven **independent PR review** pass, **post it to the PR**,
and on a re-run **status the prior findings + review only the new delta**. Load
the full checklist and follow it verbatim — including its **Posting & re-runs**
section, which is authoritative:

@docs/pr-review-checklist.md

Target PR: `$ARGUMENTS` (a PR number, or the current branch's PR if empty).

Apply the checklist's 8 dimensions to the diff, cite `file:line`, classify every
finding BLOCKER / SHOULD-FIX / NIT, do **not** modify files, and end with
VERDICT: APPROVE or REQUEST-CHANGES (never APPROVE with an open BLOCKER) plus a
3-line summary. Then post per the algorithm below.

## Flow

1. **Resolve the PR number `<n>` and head SHA.**

   ```bash
   gh pr view <n> --repo wifihaven/wifihaven --json headRefOid -q .headRefOid
   ```

2. **Look for a prior marked review comment** (the marker is
   `<!-- wifihaven-pr-review reviewed-sha=<sha> -->`):

   ```bash
   gh api "repos/wifihaven/wifihaven/issues/<n>/comments" --paginate \
     --jq '[.[] | select(.body | contains("<!-- wifihaven-pr-review reviewed-sha="))] | last'
   ```

   - **Empty / null → first run.** Review the full merge-base diff
     `git diff origin/main...HEAD` (three-dot, never two-dot) against the 8
     dimensions.
   - **Found → re-run.** Extract its prior SHA
     (`grep -oE 'reviewed-sha=[0-9a-f]+' | head -1`) and its findings, then:
     a. **Status each prior finding** ADDRESSED / NOT-ADDRESSED / PARTIAL by
        re-checking the `file:line` it cited in current code.
     b. **Review the incremental delta** `git diff <reviewed-sha>...HEAD`
        (three-dot) — the latest push(es) plus context the fix touched — for NEW
        findings.

3. **Post one marked comment** as a **non-approving** PR comment (never
   `gh pr review --approve/--request-changes` — it can interfere with required
   human reviews / the merge queue). Write the body to a temp file, leading with
   the marker for the **current** HEAD SHA:

   ```bash
   printf '<!-- wifihaven-pr-review reviewed-sha=%s -->\n' "$HEAD_SHA" > /tmp/pr-review-body.md
   # ...append the review body (re-run: prior-findings status table → new findings → VERDICT)...
   gh pr comment <n> --repo wifihaven/wifihaven --body-file /tmp/pr-review-body.md
   ```

4. **Merge gate.** A prior BLOCKER clears only when ADDRESSED *and* the latest
   push introduced no new BLOCKER. Any open BLOCKER (still NOT-ADDRESSED /
   PARTIAL, or newly introduced) keeps the verdict at REQUEST-CHANGES and stays
   merge-gating. Post exactly one updated comment per run — don't duplicate a
   comment for the same SHA.
