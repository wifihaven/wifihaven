# Merge `origin/main` into a pushed branch, never force-push

To catch a pushed branch up with `main`, merge: `git fetch origin`, `git merge origin/main`, resolve conflicts, run the checks, `git push origin HEAD:<branch>`. A normal push is a fast-forward, and nothing a reviewer, a CI run or another session has seen gets rewritten.

Merge commits on a PR branch do not reach `main`: the `Main Protection` ruleset allows squash only and runs a merge queue (see "How to merge" in `docs/pr-review-checklist.md`). That is why merging costs nothing in history.

## The rule

Force-pushing is not allowed: `git push --force`, `-f`, `--force-with-lease`, and a `+refspec` all count. A force-push can destroy commits a reviewer, a CI run or another session has already seen, and an APPROVE is bound to a SHA that a rewrite makes unreachable. If a push is rejected as non-fast-forward, merge instead of overriding, and do not offer a force-push to the operator as an option.

## What this means for plans

- A plan, brief or spawn prompt never says "rebase onto `origin/main`" for a branch that has been pushed. Say "merge `origin/main` into the branch" instead.
- Rebasing a branch that has not been pushed yet is fine. Once it is pushed, merge.
- If you already rebased a pushed branch locally, do not push it. Branch from `origin/<branch>`, merge `origin/main`, cherry-pick the commits that are not on `origin/<branch>` onto it (the pre-rebase SHAs are in `git reflog`), and push that.
