"""Unit cover for the gate3 template-app selection (#1810).

Pure — no staging API, no KVM VM. Run standalone:

    python3 -m pytest scripts/e2e/lib/test_app_seed.py

Pins the contract the gate3 `scratch_profile_and_device` fixture relies on
post-#1798: seed the shipped templates, then pick two distinct *seeded* apps
(one for the block leg, one for the allow leg) off `GET /api/apps` output —
since `POST /api/apps` (arbitrary-host create) is gone.
"""
from __future__ import annotations

import pytest

from app_seed import ALLOW_PREF, BLOCK_PREF, pick_block_allow_apps


def _app(app_id: int, template_id: str | None, hosts: list[str]) -> dict:
    """An AppDetail-shaped dict as `GET /api/apps` returns it."""
    return {
        "app": {"id": app_id, "slug": template_id or f"app-{app_id}", "templateId": template_id},
        "hosts": hosts,
        "assignments": [],
    }


def _catalog() -> list[dict]:
    # A representative seeded catalog: preferred block/allow picks present,
    # plus an unhosted app (must be skipped) and a CDN-ish extra.
    return [
        _app(1, "youtube", ["youtube.com", "youtu.be"]),
        _app(2, "duolingo", ["duolingo.com"]),
        _app(3, "khan-academy", ["khanacademy.org"]),
        _app(4, "empty-app", []),  # no hosts → never selected
        _app(5, "1password", ["1password.com"]),
    ]


def test_prefers_template_ids_for_both_legs():
    blocked, allowed = pick_block_allow_apps(_catalog())
    assert blocked["app"]["templateId"] == "youtube"      # first BLOCK_PREF present
    assert allowed["app"]["templateId"] == "duolingo"     # first ALLOW_PREF present
    assert blocked["hosts"][0] == "youtube.com"
    assert allowed["hosts"][0] == "duolingo.com"


def test_block_and_allow_are_always_distinct():
    blocked, allowed = pick_block_allow_apps(_catalog())
    assert blocked["app"]["id"] != allowed["app"]["id"]


def test_skips_apps_with_no_hosts():
    blocked, allowed = pick_block_allow_apps(_catalog())
    for picked in (blocked, allowed):
        assert picked["app"]["templateId"] != "empty-app"
        assert picked["hosts"], "selected app must carry at least one host"


def test_falls_back_to_any_hosted_app_when_prefs_absent():
    # A catalog of two arbitrary hosted apps, none in the preference lists.
    catalog = [
        _app(10, "obscure-a", ["a.example"]),
        _app(11, "obscure-b", ["b.example"]),
    ]
    blocked, allowed = pick_block_allow_apps(catalog)
    assert {blocked["app"]["id"], allowed["app"]["id"]} == {10, 11}


def test_allow_falls_back_when_only_pref_collides_with_block():
    # Only one preferred app exists and it's a block pick; the allow leg must
    # fall back to a different hosted app rather than reuse it.
    catalog = [
        _app(20, "youtube", ["youtube.com"]),   # block pick
        _app(21, "obscure", ["obscure.example"]),
    ]
    blocked, allowed = pick_block_allow_apps(catalog)
    assert blocked["app"]["templateId"] == "youtube"
    assert allowed["app"]["id"] == 21


def test_raises_when_fewer_than_two_hosted_apps():
    with pytest.raises(RuntimeError):
        pick_block_allow_apps([_app(1, "youtube", ["youtube.com"]), _app(2, "x", [])])


def test_preference_lists_are_disjoint():
    # Guards against a future edit that puts the same slug in both legs,
    # which would make the distinct-picks guarantee rely purely on fallback.
    assert not (set(BLOCK_PREF) & set(ALLOW_PREF))
