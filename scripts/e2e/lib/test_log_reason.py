"""Unit cover for the #1153 fix: /api/logs `reason` is a kind-tagged object
(#1147/#962), so the router-e2e read-back must key on `reason.kind`, not treat
`reason` as a hashable string. Run standalone:

    python3 -m pytest scripts/e2e/lib/test_log_reason.py
"""
from __future__ import annotations

from log_reason import blocked_reason_kinds

# The five MacBlockReason kinds the #1122 round-trip posts, as encoded by the
# shared BlockReason zio-json codec (camelCase `kind`, mirror of the SPA's
# web/src/types/blockReason.ts).
_KIND_TAGGED_ROWS = [
    {"blocked": True, "host": {"value": "198.51.100.10"}, "reason": {"kind": "paused"}},
    {"blocked": True, "host": {"value": "198.51.100.11"}, "reason": {"kind": "schedule"}},
    {"blocked": True, "host": {"value": "198.51.100.12"}, "reason": {"kind": "timeLimit"}},
    {"blocked": True, "host": {"value": "198.51.100.13"}, "reason": {"kind": "manual"}},
    {"blocked": True, "host": {"value": "198.51.100.14"}, "reason": {"kind": "unmanaged"}},
    # An allowed row must not contribute a kind.
    {"blocked": False, "host": {"value": "8.8.8.8"}, "reason": {"kind": "allow"}},
]


def test_blocked_reason_kinds_keys_on_kind():
    assert blocked_reason_kinds(_KIND_TAGGED_ROWS) == [
        "manual", "paused", "schedule", "timeLimit", "unmanaged",
    ]


def test_parametrized_kind_with_slug_uses_discriminator():
    # category(+slug) etc. still reduce to the bare `kind` discriminator.
    rows = [{"blocked": True, "reason": {"kind": "category", "slug": "ads"}}]
    assert blocked_reason_kinds(rows) == ["category"]
