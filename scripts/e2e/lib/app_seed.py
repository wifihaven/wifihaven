"""Gate 3 app selection over the template-authored Apps surface (#1810).

Post-#1798 the operator-facing app-definition mutators were retired — there
is no `POST /api/apps`, so gate3 can no longer mint ad-hoc `example.com` /
`example.org` apps with arbitrary hosts (apps are authored only via the
built-in `AppTemplates`). Instead the gate3 fixture seeds the shipped
templates (`POST /api/apps/seed-from-templates`, idempotent + admin-gated)
and drives its host-agnostic two-leg block/allow assertion off two distinct
*seeded* apps' real hosts.

This module holds the pure selection — pick a block-leg app and an
allow-leg app from `GET /api/apps` output — so it is unit-testable without a
staging API or a KVM VM (see `test_app_seed.py`).
"""
from __future__ import annotations

from collections.abc import Sequence

# Ordered preference of seeded template ids (== `app.templateId`) whose apex
# resolves and is reachable over plain HTTP/80 through qemu SLIRP v4 NAT —
# the same property `example.com` / `example.org` had, which is why those
# were the original probe hosts. The block leg only needs the host to
# RESOLVE (dnsmasq populates the per-host nft set from the upstream answer,
# then the HTTP/80 DNAT to the block page is host-agnostic); the allow leg
# needs a clean 2xx/3xx with no block-page markers. Distinct lists so the
# two legs probe different hosts. Falls back to any hosted app (below) so a
# template-catalog change never breaks the gate.
BLOCK_PREF: tuple[str, ...] = ("youtube", "netflix", "roblox", "tiktok", "discord")
ALLOW_PREF: tuple[str, ...] = ("duolingo", "khan-academy", "wifihaven", "brave", "1password")


def _hosted(apps: Sequence[dict]) -> list[dict]:
    """AppDetail dicts that have at least one host and a real app id."""
    out = []
    for a in apps:
        app = a.get("app") or {}
        if (a.get("hosts") or []) and app.get("id") is not None:
            out.append(a)
    return out


def _find_by_template(hosted: Sequence[dict], template_id: str) -> dict | None:
    return next(
        (a for a in hosted if (a.get("app") or {}).get("templateId") == template_id),
        None,
    )


def pick_block_allow_apps(
    apps: Sequence[dict],
    *,
    block_pref: Sequence[str] = BLOCK_PREF,
    allow_pref: Sequence[str] = ALLOW_PREF,
) -> tuple[dict, dict]:
    """Choose two DISTINCT seeded apps (each with >=1 host) for the gate3
    block / allow legs from `GET /api/apps` output (a list of AppDetail
    dicts: ``{"app": {id, slug, templateId, ...}, "hosts": [...], ...}``).

    Returns ``(blocked_app, allowed_app)``. Prefers the stable
    `templateId`-keyed picks above; falls back to the first / next hosted
    app so the gate survives a catalog change. Raises if fewer than two
    hosted apps exist (a broken/empty seed — surface it loudly rather than
    silently degrade).
    """
    hosted = _hosted(apps)
    if len(hosted) < 2:
        raise RuntimeError(
            f"gate3 needs >=2 seeded apps with hosts; saw {len(hosted)} "
            "(is the template catalog seeded?)",
        )

    blocked = next(
        (a for a in (_find_by_template(hosted, s) for s in block_pref) if a is not None),
        hosted[0],
    )
    block_id = blocked["app"]["id"]

    allowed = next(
        (
            a
            for a in (_find_by_template(hosted, s) for s in allow_pref)
            if a is not None and a["app"]["id"] != block_id
        ),
        None,
    )
    if allowed is None:
        # No distinct preferred allow pick — take any other hosted app.
        allowed = next((a for a in hosted if a["app"]["id"] != block_id), None)
    if allowed is None:
        raise RuntimeError("gate3 could not pick two distinct hosted apps")
    return blocked, allowed
