"""#2034 (Mode 2 of #2033): the fake serves every blocklist the initial
snapshot references, with no external-egress dependency.

The contract golden (`contract/api-to-router/policy_snapshot.json`, read
verbatim for the Scala-codec drift guard) seeds category blocklists — at the
time of writing `ads` (relative, fake-served) and `adult` (originally an
EXTERNAL `https://example.org/lists/adult.txt`). In Gate-2 fake mode the router
VM's blocklists.lua fetches each one; an external url couples the suite to the
shared CD host's flaky egress and an unregistered id 404s — both surface as
`blocklist fetch: fetch failed` log noise and intermittent reddening.

These tests pin the two halves of the fix:
  * `fixtures._localize_blocklist_urls` rewrites any absolute blocklist url to a
    fake-served relative path.
  * `State._seed_initial_blocklists` registers (empty) content for every
    referenced id so `GET /api/blocklists/<id>` is 200, not 404.
"""
from __future__ import annotations

from fake_api.fixtures import load_initial_snapshot


def test_initial_snapshot_has_no_external_blocklist_urls() -> None:
    """No blocklist in the loaded initial snapshot points at external egress —
    every url is a fake-served relative path."""
    snap = load_initial_snapshot()
    blocklists = snap.get("blocklists") or {}
    assert blocklists, "expected the contract golden to carry category blocklists"
    for bl_id, bl in blocklists.items():
        url = bl.get("url")
        assert isinstance(url, str) and "://" not in url, (
            f"blocklist {bl_id!r} still has an absolute/external url {url!r} — "
            f"_localize_blocklist_urls (#2034) should have rewritten it to "
            f"/api/blocklists/{bl_id}"
        )
        assert url == f"/api/blocklists/{bl_id}", (
            f"blocklist {bl_id!r} localized to {url!r}; expected "
            f"/api/blocklists/{bl_id}"
        )


async def test_initial_snapshot_blocklists_are_served_not_404(client, auth_headers) -> None:
    """Every blocklist id the initial snapshot references resolves to a 200 from
    the fake (seeded empty), so the agent's base-session fetch never 404s."""
    snap = load_initial_snapshot()
    ids = list((snap.get("blocklists") or {}).keys())
    assert ids, "expected the contract golden to carry category blocklists"
    for bl_id in ids:
        resp = await client.get(f"/api/blocklists/{bl_id}", headers=auth_headers)
        assert resp.status == 200, (
            f"GET /api/blocklists/{bl_id} returned {resp.status}, expected 200 — "
            f"_seed_initial_blocklists (#2034) should have registered it"
        )


async def test_seeding_does_not_clobber_registered_content(client, auth_headers) -> None:
    """A scenario that registers real members for a referenced id keeps them —
    the empty seed uses setdefault, so registration wins."""
    snap = load_initial_snapshot()
    ids = list((snap.get("blocklists") or {}).keys())
    assert ids, "expected at least one referenced blocklist id"
    bl_id = ids[0]
    await client.post("/test/blocklist", json={"id": bl_id, "hosts": ["real.example"]})
    resp = await client.get(f"/api/blocklists/{bl_id}", headers=auth_headers)
    assert resp.status == 200
    body = await resp.text()
    assert "real.example" in body, (
        f"registered content for {bl_id!r} was clobbered by the empty seed"
    )
