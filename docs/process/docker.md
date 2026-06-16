# Docker inside Claude Code agents (worktrees)

This was originally in AGENTS.md §"Docker inside Claude Code agents (worktrees)"; see AGENTS.md for the TOC.

## Docker inside Claude Code agents (worktrees)

Docker commands (`docker info`, `docker compose`, etc.) will **hang
indefinitely** if Docker Desktop is not running or is in a degraded state.
The Claude Code bash sandbox does not block Unix socket connections — Docker
simply must be healthy.

**Before running any docker command**, verify the daemon responds:

```bash
docker info 2>&1 | grep "Server Version"
```

This should return within 2 seconds. If it hangs, restart Docker Desktop
(quit from the menu bar, then reopen) and wait for the whale icon to become
steady before retrying.

Common symptom: Docker Desktop appears "running" (process exists, socket
files exist) but the daemon inside the VM has crashed or frozen — this happens
after long uptimes. Restarting Docker Desktop is the fix.
