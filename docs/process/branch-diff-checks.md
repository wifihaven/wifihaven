# Branch-diff checks (CI + pre-push)

This was originally in AGENTS.md §"Branch-diff checks (CI + pre-push)"; see AGENTS.md for the TOC.

## Branch-diff checks (CI + pre-push)

CI checks and pre-push checks that compare a branch against `main` MUST diff against the **merge base** with `origin/main`, not `origin/main` directly. Use three-dot syntax (`origin/main...HEAD`) or an explicit `git merge-base origin/main HEAD`. Two-dot (`origin/main..HEAD`) over-reports when `main` has advanced since the branch diverged, producing spurious failures and noise.

Pre-commit checks are different: they operate on staged files (`git diff --cached`), not against `origin/main`.
