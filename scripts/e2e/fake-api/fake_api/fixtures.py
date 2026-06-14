"""Load the contract goldens used as initial snapshot/response fixtures.

The fake reads `contract/api-to-router/policy_snapshot.json` verbatim
so the CI drift guard against the live Scala codec keeps it honest. See
`contract/README.md`.
"""

from __future__ import annotations

import json
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[4]
CONTRACT_DIR = REPO_ROOT / "shared" / "contract"
POLICY_SNAPSHOT_PATH = CONTRACT_DIR / "api-to-router" / "policy_snapshot.json"


def load_initial_snapshot() -> dict:
    with POLICY_SNAPSHOT_PATH.open() as f:
        return json.load(f)
