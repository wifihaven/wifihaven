"""Typed snapshot builder for fake-mode scenarios (#684).

Produces dicts that match `contract/api-to-router/policy_snapshot.json`
verbatim. The fake's `POST /test/snapshot` accepts any dict and serves it back
to the agent as-is, so all the shape rules live here, not in the fake.

Design: dicts + a couple of asserts. No pydantic / no jsonschema — the contract
golden is already enforced upstream by `contract-tests` against the Scala codec,
so this builder only has to faithfully reproduce that shape.
"""
from __future__ import annotations

from typing import Any


_DEFAULT_GENERATED_AT = "2026-05-19T00:00:00Z"


def profile_rules(
    *,
    blocked: bool = False,
    block_reason: str | None = None,
    extra_blocked: list[str] | None = None,
    extra_allowed: list[str] | None = None,
    blocklist_ids: list[str] | None = None,
    block_ip_only: bool = False,
) -> dict[str, Any]:
    """Build the BlockRules wire shape.

    `block_reason` mirrors the `MacBlockReason` enum the Scala API emits
    (`"Paused"`, `"Schedule"`, `"TimeLimit"`, `"Manual"`). Required when
    `blocked=True` so the router agent tags `blocked_reason[mac]` with a
    meaningful value — both the block-page renderer and the connection-event
    `reason` field key off this string.
    """
    rules: dict[str, Any] = {
        "blocked": blocked,
        "extraBlocked": list(extra_blocked or []),
        "extraAllowed": list(extra_allowed or []),
        "blocklistIds": list(blocklist_ids or []),
        "blockIpOnly": block_ip_only,
    }
    if block_reason is not None:
        rules["blockReason"] = block_reason
    return rules


class SnapshotBuilder:
    """Accumulator that produces a policy-snapshot dict ready for /test/snapshot.

    Usage:
        b = SnapshotBuilder()
        b.add_profile(id=3, name="kids", extra_blocked=["tiktok.com"])
        b.add_device(mac="aa:bb:cc:11:22:33", profile_id=3, name="kid-ipad")
        b.add_blocklist(id="ads")
        snap = b.build(etag='"sha256:abc"')
    """

    def __init__(self, *, generated_at: str = _DEFAULT_GENERATED_AT) -> None:
        self._generated_at = generated_at
        self._profiles: dict[str, dict[str, Any]] = {}
        self._devices: dict[str, dict[str, Any]] = {}
        self._blocklists: dict[str, dict[str, Any]] = {}
        # The fleet-wide `global` BlockRules (#1316/#1318). None until
        # `set_global()` is called, so snapshots that don't exercise the global
        # layer omit the key entirely and render byte-identically to pre-#1319
        # (render.lua treats an absent `global` as BlockRules.allowAll).
        self._global: dict[str, Any] | None = None
        # #1911: the additive top-level `blockEncryptedDns` bool. None until
        # `set_block_encrypted_dns()` is called, so by default the key is omitted
        # entirely and the agent renders byte-identically to today (absent →
        # false). Mirrors the API: a sibling of global/devices/profiles/blocklists.
        self._block_encrypted_dns: bool | None = None

    def add_profile(
        self,
        *,
        id: int,
        name: str,
        blocked: bool = False,
        block_reason: str | None = None,
        extra_blocked: list[str] | None = None,
        extra_allowed: list[str] | None = None,
        blocklist_ids: list[str] | None = None,
        block_ip_only: bool = False,
        failure_mode: str = "block-all",
    ) -> "SnapshotBuilder":
        if blocked and block_reason is None:
            # Mirrors render.lua's fallback: without a reason the block-page +
            # event-reason both degrade to the literal "blocked". For tests
            # that want enforcement (`blocked=True`) we require an explicit
            # MacBlockReason so the agent-emitted reason is matchable.
            raise AssertionError(
                "add_profile(blocked=True) requires block_reason "
                "('Paused' | 'Schedule' | 'TimeLimit' | 'Manual')"
            )
        self._profiles[str(id)] = {
            "name": name,
            "rules": profile_rules(
                blocked=blocked,
                block_reason=block_reason,
                extra_blocked=extra_blocked,
                extra_allowed=extra_allowed,
                blocklist_ids=blocklist_ids,
                block_ip_only=block_ip_only,
            ),
            "failureMode": failure_mode,
        }
        return self

    def add_device(
        self,
        *,
        mac: str,
        name: str = "e2e-dev",
        profile_id: int | None = None,
        rules: dict[str, Any] | None = None,
    ) -> "SnapshotBuilder":
        """Add a device entry.

        Exactly one of `profile_id` (assigned device, inherits profile rules)
        or `rules` (per-MAC override, e.g. unknown-device branch in the
        contract golden's `12:34:56:78:9a:bc` row) must be set.
        """
        if (profile_id is None) == (rules is None):
            raise AssertionError(
                "add_device requires exactly one of profile_id= or rules="
            )
        entry: dict[str, Any] = {"name": name}
        if profile_id is not None:
            entry["profileId"] = profile_id
        else:
            entry["rules"] = dict(rules)  # type: ignore[arg-type]
        self._devices[mac] = entry
        return self

    def set_global(
        self,
        *,
        blocked: bool = False,
        block_reason: str | None = None,
        extra_blocked: list[str] | None = None,
        extra_allowed: list[str] | None = None,
        blocklist_ids: list[str] | None = None,
        block_ip_only: bool = False,
    ) -> "SnapshotBuilder":
        """Populate the snapshot's fleet-wide `global` BlockRules (#1318/#1319).

        Applied to *every* MAC by the agent alongside that MAC's resolved
        per-MAC rules, with the precedence ladder from
        `docs/design/global-policy-layer.md` §5.2:

          - `extra_allowed` → `@global_allow` — carves out **every** drop
            (per-MAC or global), all MACs. Top of the ladder.
          - `extra_blocked` / `blocklist_ids` → `@global_block` — blocks
            fleet-wide; a per-profile `extraAllowed` may **not** un-block them,
            only `global.extraAllowed` can.
          - `blocked` → whole-network lockdown; only `global.extraAllowed`
            survives it.

        Unlike `add_profile(blocked=True)` this does **not** require a
        `block_reason`: a global lockdown's reason is cosmetic (block-page copy
        only) and has no `MacBlockReason` enum case. Pass one if a test asserts
        on the rendered block-page text.
        """
        self._global = profile_rules(
            blocked=blocked,
            block_reason=block_reason,
            extra_blocked=extra_blocked,
            extra_allowed=extra_allowed,
            blocklist_ids=blocklist_ids,
            block_ip_only=block_ip_only,
        )
        return self

    def set_block_encrypted_dns(self, value: bool = True) -> "SnapshotBuilder":
        """Set the network-wide `blockEncryptedDns` toggle (#1911 / #1909).

        When true the agent enforces the "block encrypted DNS & relays"
        behaviour: NODATA for the curated relay/DoH hostnames (dnsmasq) + a
        forward-chain drop of DoT/853 and DNS:53-to-curated-resolver-IPs (nft).
        Additive top-level field; absent → false (no-op).
        """
        self._block_encrypted_dns = value
        return self

    def add_blocklist(
        self,
        *,
        id: str,
        version: str = "2026.05.01",
        url: str | None = None,
    ) -> "SnapshotBuilder":
        self._blocklists[id] = {
            "version": version,
            "url": url if url is not None else f"/api/blocklists/{id}",
        }
        return self

    def build(self, *, etag: str) -> dict[str, Any]:
        # Self-consistency: every device's profileId must reference a profile
        # we declared. The agent tolerates dangling IDs (treats them as
        # default-allow), but the tests don't — a typo here means hours
        # chasing a phantom failure.
        for mac, dev in self._devices.items():
            pid = dev.get("profileId")
            if pid is not None and str(pid) not in self._profiles:
                raise AssertionError(
                    f"device {mac} references unknown profileId={pid}; "
                    f"declared profiles: {sorted(self._profiles)}"
                )
        snap: dict[str, Any] = {
            "etag": etag,
            "generatedAt": self._generated_at,
            "devices": dict(self._devices),
            "profiles": dict(self._profiles),
            "blocklists": dict(self._blocklists),
        }
        # Emit `global` only when explicitly set, so the default top-level key
        # set stays {etag, generatedAt, devices, profiles, blocklists} for the
        # scenarios (and test_snapshot_builder) that don't touch it.
        if self._global is not None:
            snap["global"] = dict(self._global)
        # #1911: emit `blockEncryptedDns` only when explicitly set, so the
        # default top-level key set is unchanged for every other scenario.
        if self._block_encrypted_dns is not None:
            snap["blockEncryptedDns"] = self._block_encrypted_dns
        return snap


# ── Convenience helpers kept for the #683 canary (test_pause) ───────────────


def _default_blocklists() -> dict[str, Any]:
    return {
        "ads": {"version": "2026.05.01", "url": "/api/blocklists/ads"},
    }


def snapshot_with_assigned_device(
    *,
    etag: str,
    mac: str,
    device_name: str = "e2e-dev",
    profile_id: int = 100,
    profile_name: str = "e2e-profile",
    paused: bool = False,
    generated_at: str = _DEFAULT_GENERATED_AT,
) -> dict[str, Any]:
    """Single-device snapshot used by the #683 pause canary."""
    b = SnapshotBuilder(generated_at=generated_at)
    b.add_profile(
        id=profile_id,
        name=profile_name,
        blocked=paused,
        block_reason="Paused" if paused else None,
    )
    b.add_device(mac=mac, name=device_name, profile_id=profile_id)
    b.add_blocklist(id="ads")
    return b.build(etag=etag)
