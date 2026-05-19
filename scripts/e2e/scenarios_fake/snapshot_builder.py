"""Minimal snapshot dict builders for the fake-API canary scenario (#683).

Just enough to construct the two snapshots `test_pause` needs. The full
typed builder lives in #684.

The wire shape matches `shared/contract/api-to-router/policy_snapshot.json`
verbatim — the same fixture the fake loads on startup.
"""
from __future__ import annotations

from typing import Any


def _default_blocklists() -> dict[str, Any]:
    return {
        "ads": {"version": "2026.05.01", "url": "/api/blocklists/ads"},
    }


def profile_rules(
    *,
    blocked: bool = False,
    block_reason: str | None = None,
    extra_blocked: list[str] | None = None,
    extra_allowed: list[str] | None = None,
    blocklist_ids: list[str] | None = None,
    block_ip_only: bool = False,
) -> dict[str, Any]:
    """Build the per-profile BlockRules wire shape.

    `block_reason` mirrors the `MacBlockReason` enum the Scala API emits —
    `"Paused"`, `"Schedule"`, `"TimeLimit"`, `"Manual"`. Required when
    `blocked=true` so the router agent (`render.lua::update_shared`) tags
    `blocked_reason[mac]` with a meaningful value rather than the generic
    fallback `"blocked"` — both the block-page renderer and the event
    `reason` field key off this string.
    """
    rules: dict[str, Any] = {
        "blocked": blocked,
        "extraBlocked": extra_blocked or [],
        "extraAllowed": extra_allowed or [],
        "blocklistIds": blocklist_ids or [],
        "blockIpOnly": block_ip_only,
    }
    if block_reason is not None:
        rules["blockReason"] = block_reason
    return rules


def snapshot_with_assigned_device(
    *,
    etag: str,
    mac: str,
    device_name: str = "e2e-dev",
    profile_id: int = 100,
    profile_name: str = "e2e-profile",
    paused: bool = False,
    generated_at: str = "2026-05-19T00:00:00Z",
) -> dict[str, Any]:
    """Snapshot with one device assigned to one profile.

    `paused=True` flips the profile's `rules.blocked` to true, mirroring how
    the live admin pause toggle propagates at wire level (per the answer to
    the design checkpoint in #683).
    """
    return {
        "etag": etag,
        "generatedAt": generated_at,
        "devices": {
            mac: {"profileId": profile_id, "name": device_name},
        },
        "profiles": {
            str(profile_id): {
                "name": profile_name,
                "rules": profile_rules(
                    blocked=paused,
                    block_reason="Paused" if paused else None,
                ),
                "failureMode": "block-all",
            },
        },
        "blocklists": _default_blocklists(),
    }
