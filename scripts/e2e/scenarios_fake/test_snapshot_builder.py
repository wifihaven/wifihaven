"""Unit tests for the snapshot builder (#684).

Pure-dict tests; do not request any VM fixtures. Cross-checks the builder's
output structure against `contract/api-to-router/policy_snapshot.json`,
the contract golden the fake itself loads on startup.
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from .snapshot_builder import SnapshotBuilder, snapshot_with_assigned_device


REPO_ROOT = Path(__file__).resolve().parents[3]
GOLDEN_PATH = REPO_ROOT / "contract" / "api-to-router" / "policy_snapshot.json"


def _golden() -> dict:
    return json.loads(GOLDEN_PATH.read_text())


# ── Shape ─────────────────────────────────────────────────────────────────────


def test_empty_build_has_top_level_contract_keys():
    snap = SnapshotBuilder().build(etag='"sha256:empty"')
    assert set(snap) == {"etag", "generatedAt", "devices", "profiles", "blocklists"}
    assert snap["devices"] == {}
    assert snap["profiles"] == {}
    assert snap["blocklists"] == {}


def test_builder_matches_golden_shape():
    """Reproduce the golden snapshot's structure via the builder and verify
    its key set + per-row sub-shapes line up.

    Not a byte-for-byte equality check — the golden uses string `etag` quoting
    we shouldn't pin in the builder — but the structural surface must match
    so the fake serves bytes the agent can decode.
    """
    g = _golden()
    b = SnapshotBuilder(generated_at=g["generatedAt"])
    # Profiles
    for pid_str, prof in g["profiles"].items():
        r = prof["rules"]
        b.add_profile(
            id=int(pid_str),
            name=prof["name"],
            blocked=r["blocked"],
            block_reason=r.get("blockReason"),
            extra_blocked=r.get("extraBlocked"),
            extra_allowed=r.get("extraAllowed"),
            blocklist_ids=r.get("blocklistIds"),
            block_ip_only=r.get("blockIpOnly", False),
            failure_mode=prof.get("failureMode", "block-all"),
        )
    # Devices — golden has both branches (profileId-assigned + per-MAC rules).
    for mac, dev in g["devices"].items():
        if "profileId" in dev:
            b.add_device(mac=mac, name=dev["name"], profile_id=dev["profileId"])
        else:
            b.add_device(mac=mac, name=dev["name"], rules=dev["rules"])
    # Blocklists
    for bid, bl in g["blocklists"].items():
        b.add_blocklist(id=bid, version=bl["version"], url=bl["url"])

    built = b.build(etag=g["etag"])

    assert built["etag"] == g["etag"]
    assert built["generatedAt"] == g["generatedAt"]
    assert set(built["profiles"]) == set(g["profiles"])
    assert set(built["devices"]) == set(g["devices"])
    assert set(built["blocklists"]) == set(g["blocklists"])

    # Each profile's rules block must carry the contract fields. Order
    # doesn't matter, but the agent reads these by key name.
    for pid_str, prof in built["profiles"].items():
        assert set(prof) == {"name", "rules", "failureMode"}
        r = prof["rules"]
        contract_keys = {"blocked", "extraBlocked", "extraAllowed", "blocklistIds", "blockIpOnly"}
        assert contract_keys.issubset(set(r))

    # The per-MAC override device in the golden must round-trip as a `rules`
    # device, NOT as a profileId device.
    override_mac = "12:34:56:78:9a:bc"
    assert override_mac in built["devices"]
    assert "rules" in built["devices"][override_mac]
    assert "profileId" not in built["devices"][override_mac]


# ── Per-MAC override ──────────────────────────────────────────────────────────


def test_add_device_per_mac_override():
    snap = (
        SnapshotBuilder()
        .add_device(
            mac="aa:bb:cc:dd:ee:ff",
            name="unknown",
            rules={
                "blocked": True,
                "blockReason": "Manual",
                "extraBlocked": [],
                "extraAllowed": [],
                "blocklistIds": [],
                "blockIpOnly": False,
            },
        )
        .build(etag='"sha256:override"')
    )
    d = snap["devices"]["aa:bb:cc:dd:ee:ff"]
    assert d["rules"]["blocked"] is True
    assert "profileId" not in d


def test_add_device_rejects_both_profile_and_rules():
    with pytest.raises(AssertionError):
        SnapshotBuilder().add_device(
            mac="aa:bb:cc:dd:ee:ff", profile_id=3, rules={"blocked": False},
        )


def test_add_device_rejects_neither():
    with pytest.raises(AssertionError):
        SnapshotBuilder().add_device(mac="aa:bb:cc:dd:ee:ff")


# ── Profile guard ─────────────────────────────────────────────────────────────


def test_add_profile_requires_block_reason_when_blocked():
    """`blocked=True` without a reason → render.lua falls back to "blocked".
    Tests that match on `reason=Paused`/etc. would mysteriously fail; force
    callers to be explicit."""
    with pytest.raises(AssertionError):
        SnapshotBuilder().add_profile(id=1, name="p", blocked=True)


def test_build_rejects_device_referencing_unknown_profile():
    b = SnapshotBuilder()
    b.add_profile(id=1, name="p")
    b.add_device(mac="aa:bb:cc:dd:ee:ff", profile_id=999)
    with pytest.raises(AssertionError):
        b.build(etag='"sha256:bad"')


# ── Convenience helper (canary uses this) ─────────────────────────────────────


def test_snapshot_with_assigned_device_canary_shape():
    snap = snapshot_with_assigned_device(
        etag='"sha256:c"', mac="aa:bb:cc:dd:ee:ff", paused=True,
    )
    assert snap["devices"]["aa:bb:cc:dd:ee:ff"]["profileId"] == 100
    rules = snap["profiles"]["100"]["rules"]
    assert rules["blocked"] is True
    assert rules["blockReason"] == "Paused"


# ── Global section (#1460) ────────────────────────────────────────────────────


def test_global_omitted_unless_set():
    """Without set_global(), the snapshot carries no `global` key — keeping the
    default top-level shape (test_empty_build_has_top_level_contract_keys) and
    rendering byte-identically to pre-#1319 (render.lua treats absent global as
    allowAll)."""
    snap = SnapshotBuilder().build(etag='"sha256:no-global"')
    assert "global" not in snap


def test_set_global_emits_blockrules_shape():
    """set_global() produces a BlockRules dict under `global` with the contract
    field set the agent reads (matches the golden's `global` row)."""
    snap = (
        SnapshotBuilder()
        .set_global(
            extra_allowed=["connectivitycheck.gstatic.com"],
            extra_blocked=["malware.example"],
        )
        .build(etag='"sha256:with-global"')
    )
    g = snap["global"]
    assert set(g) == {
        "blocked", "extraBlocked", "extraAllowed", "blocklistIds", "blockIpOnly",
    }
    assert g["extraAllowed"] == ["connectivitycheck.gstatic.com"]
    assert g["extraBlocked"] == ["malware.example"]
    assert g["blocked"] is False
    assert g["blockIpOnly"] is False


def test_set_global_matches_golden_global_keys():
    """The builder's `global` key set lines up with the contract golden's, so
    the fake serves bytes the agent decodes."""
    g = _golden()["global"]
    built = SnapshotBuilder().set_global(
        extra_allowed=g["extraAllowed"],
    ).build(etag='"sha256:golden-global"')["global"]
    assert set(built) == set(g)


def test_set_global_lockdown_allows_optional_reason():
    """Unlike add_profile(blocked=True), set_global(blocked=True) does NOT
    require a block_reason — a global lockdown's reason is cosmetic."""
    snap = SnapshotBuilder().set_global(blocked=True).build(etag='"sha256:lockdown"')
    assert snap["global"]["blocked"] is True
    assert "blockReason" not in snap["global"]
    # ...but it may be set when a test asserts on block-page copy.
    snap2 = (
        SnapshotBuilder()
        .set_global(blocked=True, block_reason="Manual")
        .build(etag='"sha256:lockdown2"')
    )
    assert snap2["global"]["blockReason"] == "Manual"
