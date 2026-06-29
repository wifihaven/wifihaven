"""Load the contract goldens used as initial snapshot/response fixtures.

The fake reads `contract/api-to-router/policy_snapshot.json` verbatim
so the CI drift guard against the live Scala codec keeps it honest. See
`contract/README.md`.
"""

from __future__ import annotations

import json
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[4]
CONTRACT_DIR = REPO_ROOT / "contract"
POLICY_SNAPSHOT_PATH = CONTRACT_DIR / "api-to-router" / "policy_snapshot.json"


def load_initial_snapshot() -> dict:
    with POLICY_SNAPSHOT_PATH.open() as f:
        snap = json.load(f)
    _localize_blocklist_urls(snap)
    return snap


def _localize_blocklist_urls(snap: dict) -> None:
    """Rewrite any *absolute* blocklist URL in the loaded contract golden to a
    fake-served relative path (#2034, Mode 2 of #2033).

    The contract golden seeds the ``adult`` category blocklist with an EXTERNAL
    url (``https://example.org/lists/adult.txt``). In Gate-2 fake mode the router
    VM's blocklists.lua would fetch that over the flaky shared-host egress —
    ``example.org/lists/...`` 404s, and on an egress blip it times out — which
    pollutes the agent logs with ``blocklist fetch: fetch failed for adult ...``
    and couples the suite to external egress (the #1935/#2034 flake).

    We CANNOT edit ``contract/api-to-router/policy_snapshot.json`` itself: it is
    read verbatim and held byte-identical to the live Scala codec by the CI drift
    guard (see ``contract/README.md``). So we localize here, after load, leaving
    the file untouched. The id is preserved, so the rewritten url
    ``/api/blocklists/<id>`` is served by the fake from the in-memory stub content
    seeded in ``State.fresh`` — making the base-session blocklist fetch
    deterministic and egress-free. Relative urls (already fake-served) pass
    through unchanged.
    """
    for bl_id, bl in (snap.get("blocklists") or {}).items():
        if isinstance(bl, dict) and isinstance(bl.get("url"), str) and "://" in bl["url"]:
            bl["url"] = f"/api/blocklists/{bl_id}"
