---
description: Independent, read-only adversarial review of a PR using the standard WifiHaven checklist
---

Run the standard WifiHaven **independent PR review** pass. Load the full
checklist and follow it verbatim:

@docs/pr-review-checklist.md

Review the PR referenced in `$ARGUMENTS` (a PR number, or the current branch if
empty). Read the PR body and the diff (`gh pr diff <n>`, or local
`git diff origin/main...HEAD` — three-dot merge-base, never two-dot). Review
ONLY the changes, cite `file:line`, classify every finding BLOCKER / SHOULD-FIX
/ NIT, do not modify files, and end with VERDICT: APPROVE or REQUEST-CHANGES
(never APPROVE with an open BLOCKER) plus a 3-line summary.
