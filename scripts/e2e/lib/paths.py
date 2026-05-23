"""Repo-relative path resolution. Single source of truth for where things live."""
from __future__ import annotations

import os
from pathlib import Path

# scripts/e2e/lib/paths.py → repo root is three parents up.
REPO_ROOT = Path(__file__).resolve().parents[3]
SCRIPTS_DIR = REPO_ROOT / "scripts"
VM_DIR = SCRIPTS_DIR / "vm"
# When WH_RUN_ID is set, runtime state lives in a per-run subdir so two
# concurrent pairs don't share overlay/pid/socket paths (#891). Keep the
# default identical to the pre-#891 layout.
_WH_RUN_ID = os.environ.get("WH_RUN_ID", "")
VM_RUN_DIR = (VM_DIR / ".run" / _WH_RUN_ID) if _WH_RUN_ID else (VM_DIR / ".run")
VM_CACHE_DIR = VM_DIR / ".cache"
CLIENT_SSH_KEY = VM_DIR / "keys" / "client_test_ed25519"

# Path of a per-run scratch dir for orchestrator artifacts (logs, captured tokens).
RUN_ARTIFACTS_DIR = Path(
    os.environ.get("E2E_VM_ARTIFACTS_DIR", str(REPO_ROOT / ".e2e-vm-artifacts"))
)
