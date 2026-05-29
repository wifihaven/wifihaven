"""Reason extraction for /api/logs (and /api/connection-events/*) rows.

Single source of truth shared by the bash router e2e (`scripts/e2e-router.sh`
#1122 read-back) and its unit test, so the wire-shape contract can't drift
between them.
"""
from __future__ import annotations

from typing import Any


def blocked_reason_kinds(rows: list[dict[str, Any]]) -> list[str]:
    """Sorted, de-duplicated reason discriminators for the blocked rows.

    `/api/logs` rows carry `reason` as a kind-tagged object (#1147/#962),
    e.g. {"kind": "paused"} or {"kind": "category", "slug": "ads"}. The
    low-cardinality `kind` is the discriminator (mirror of the SPA's
    `web/src/types/blockReason.ts`).
    """
    return sorted({r.get("reason") for r in rows if r.get("blocked")})
