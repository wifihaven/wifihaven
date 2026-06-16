# Worktree isolation for spawned work

This was originally in AGENTS.md §"Always isolate spawned work in a worktree"; see AGENTS.md for the TOC.

## Always isolate spawned work in a worktree

This repo is actively developed across many parallel sessions, so the main
checkout at `/Users/sameer/workspace/wifihaven` is usually on some in-flight
branch. **Spawning a session or agent that edits files without an isolated
worktree pollutes that working tree and causes branch conflicts.**

Rules:

- When delegating with the `Agent` tool and the agent will edit files, pass
  `isolation: "worktree"`. Read-only research agents (Explore, plain lookups)
  don't need it.
- When spinning off background work with `spawn_task`, write the prompt so
  the spawned session creates its own worktree before doing anything else
  (e.g. `git worktree add .claude/worktrees/<slug> -b <branch>` off the
  latest `main`). State this explicitly in the prompt — the spawned session
  starts with no context.
- Never push to or check out a new branch in the top-level
  `/Users/sameer/workspace/wifihaven` checkout from a spawned session. Treat
  it as someone else's working tree.
- Worktrees live under `.claude/worktrees/<slug>` and use branch names
  `claude/<slug>` by convention (see `git worktree list`).
