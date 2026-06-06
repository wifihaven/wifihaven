"""Fixture-based tests for the presence replay harness (#1467).

These pin the harness *computation* against tiny synthetic datasets so the gate
doesn't silently bit-rot — they mirror the behaviours the shipped
``api/src/presence/Presence.scala`` (and the design §2b/§2d) guarantee:

  * the session model credits a sparse continuous session its full span, not the
    sampled ``max(activeSeconds)`` floor;
  * two concurrent apps in one minute count once (within-device union);
  * the re-bucketing invariance holds at N≥R and collapses to 0 at N<R;
  * the heartbeat filter (bytes floor + FQDN allowlist) strips keepalives, and a
    keepalive window between two real sessions is bridged while a long keepalive
    run is not;
  * the ``2 × R`` collapse-guard clamp.

Run:  python3 -m pytest scripts/analysis/test_presence_replay.py -v
No network / DB — pure synthetic fixtures.
"""

from __future__ import annotations

import argparse

import presence_replay as pr

MIN = 60


def _traffic(mac, host, start, *, dur=60, active=10, bytes=50_000, host_type="fqdn", date="2026-06-02"):
    return pr.TrafficRow(
        mac=mac, date=date, period_start=start, period_end=start + dur,
        host=host, host_type=host_type, bytes=bytes, active_seconds=active,
    )


# ── pattern matching (mirror HostMatch.matchesPattern) ────────────────────────


def test_matches_pattern():
    assert pr.matches_pattern("www.youtube.com", "youtube.com")
    assert pr.matches_pattern("youtube.com", "youtube.com")
    assert pr.matches_pattern("a.b.push.apple.com", "*.push.apple.com")
    assert pr.matches_pattern("push.apple.com", "*.push.apple.com")
    assert not pr.matches_pattern("notyoutube.com", "youtube.com")
    assert not pr.matches_pattern("youtube.com.evil.com", "youtube.com")


# ── heartbeat classification ──────────────────────────────────────────────────


def test_heartbeat_bytes_and_pattern():
    f = pr.HeartbeatFilter(enabled=True, bytes_threshold=10_000, host_patterns=("*.push.apple.com",))
    assert pr.is_heartbeat(_traffic("m", "x.com", 0, bytes=500), f)  # low bytes
    assert pr.is_heartbeat(_traffic("m", "1.push.apple.com", 0, bytes=99_999), f)  # allowlist
    assert not pr.is_heartbeat(_traffic("m", "mathacademy.com", 0, bytes=99_999), f)
    # disabled filter keeps everything
    off = pr.HeartbeatFilter(enabled=False, bytes_threshold=10_000)
    assert not pr.is_heartbeat(_traffic("m", "x.com", 0, bytes=1), off)
    # IP-literal hosts never match FQDN patterns
    assert not pr.is_heartbeat(
        _traffic("m", "1.2.3.4", 0, bytes=99_999, host_type="ipv4"), f
    )


# ── session model recovers the full span, not the sampled floor (design §2b) ──


def test_session_beats_legacy_max_for_sparse_session():
    # 10 contiguous 60s buckets of one host, each sampling only the 10s floor.
    rows = [_traffic("m", "mathacademy.com", i * MIN, active=10) for i in range(10)]
    cur = pr.legacy_max_seconds(rows)
    sess = pr.session_total_seconds(rows, gap=120)
    assert cur == 10 * 10  # 100s — the floor, ~10x low
    assert sess == 10 * MIN  # 600s — the full continuous span
    assert sess > cur * 5


def test_two_concurrent_apps_one_minute_counts_once():
    # same minute, two different hosts → union is one minute, not two.
    rows = [
        _traffic("m", "mathacademy.com", 0),
        _traffic("m", "khanacademy.org", 0),
    ]
    assert pr.session_total_seconds(rows, gap=120) == MIN


def test_idle_gap_bridges_within_n_not_beyond():
    # two buckets 90s apart (gap 30s) bridge at N=120; a 600s gap does not.
    near = [_traffic("m", "h.com", 0), _traffic("m", "h.com", 90)]
    far = [_traffic("m", "h.com", 0), _traffic("m", "h.com", 600)]
    assert pr.session_total_seconds(near, gap=120) == 150  # [0, 150]
    # far: two separate [t, t+60] sessions → 120s, not bridged
    assert pr.session_total_seconds(far, gap=120) == 120


# ── effective-gap 2×R collapse guard (mirror Presence.effectiveGap) ───────────


def test_effective_gap_clamp():
    assert pr.effective_gap([60, 60], 120) == 120  # N already >= 2R
    assert pr.effective_gap([60], 60) == 120  # N < 2R → clamped up
    assert pr.effective_gap([300], 120) == 600  # coarse reporting raises the floor
    assert pr.effective_gap([], 120) == 120


# ── §2d re-bucketing invariance + collapse ────────────────────────────────────


def _events(mac, host, *times, host_type="fqdn"):
    return [pr.ConnEvent(mac=mac, host=host, host_type=host_type, ts=t) for t in times]


def test_rebucket_invariant_when_n_ge_r():
    # a dense 1-hour run of requests; union at N=300 should be ~stable across R
    # (the trailing-edge truncation of point-anchoring is ≤ R, a small fraction of
    # an hour — exactly why the real-prod week comes out at ~10% spread).
    ev = _events("m", "mathacademy.com", *range(0, 3600, 7))  # every 7s for 1 h
    u10 = pr.rebucket_union_seconds(ev, 10, 300, ())
    u60 = pr.rebucket_union_seconds(ev, 60, 300, ())
    u300 = pr.rebucket_union_seconds(ev, 300, 300, ())
    vals = [u10, u60, u300]
    spread = (max(vals) - min(vals)) / max(vals)
    assert spread <= 0.25  # rate-independent at N >= R
    assert u10 >= 55 * MIN  # recovers ~the full hour


def test_rebucket_collapses_when_n_lt_r():
    ev = _events("m", "mathacademy.com", *range(0, 3600, 7))
    # N=60 < R=300 → consecutive points 300s apart, nothing merges, union 0.
    assert pr.rebucket_union_seconds(ev, 300, 60, ()) == 0


def test_rebucket_drops_fqdn_allowlist_heartbeats():
    ev = _events("m", "1.push.apple.com", *range(0, 600, 7))
    assert pr.rebucket_union_seconds(ev, 60, 300, ("*.push.apple.com",)) == 0
    # but kept when not on the allowlist
    assert pr.rebucket_union_seconds(ev, 60, 300, ()) > 0


# ── end-to-end on a synthetic dataset (gate wiring) ───────────────────────────


def _args():
    return argparse.Namespace(
        ratio_min=2.0, ratio_max=6.0, bridge_max=1.25,
        invariance_tol=0.25, collapse_max=0.10, hb_delta_tol=0.35,
    )


def test_end_to_end_gate_go_on_healthy_fixture():
    # one device, one day: a sparse-but-continuous edu session that the session
    # model recovers, plus a dense request stream for §2d, plus keepalive noise.
    # alternate the sampled floor (10s) and a busier bucket (30s) so the legacy
    # `cur` is realistic and the recovery ratio lands in the design's [2,6] band
    # rather than the degenerate ~6.7x of an all-floor stream.
    traffic = [_traffic("m", "mathacademy.com", i * MIN, active=10 if i % 2 else 30) for i in range(20)]
    traffic += [_traffic("m", "1.push.apple.com", i * MIN, bytes=200) for i in range(20)]
    conn = _events("m", "mathacademy.com", *range(0, 3600, 5))
    ds = pr.Dataset(
        settings={
            "heartbeat_filter_enabled": True,
            "heartbeat_bytes_threshold": 10_000,
            "heartbeat_host_patterns": ["*.push.apple.com"],
            "presence_continuation_seconds": 120,
        },
        devices={"m": "Kid Mac"},
        traffic=traffic,
        conn=conn,
    )
    res = pr.build_result(ds, _args())
    # §2b: session recovers ~the full 20-minute span vs the 200s floor.
    assert res.section_2b["total"]["ratio_default_vs_cur"] >= 2.0
    # §2d: invariant + collapses.
    assert res.section_2d["spread"] <= 0.25
    assert res.section_2d["collapse"]["fraction_of_ref"] <= 0.10
    # gate is GO and the renderer runs.
    assert res.gate["go"] is True
    assert "GO — tuned defaults validated" in pr.render(ds, res)


def test_gate_no_go_when_undercount_band_violated():
    # legacy max already == session (no sparse undercount) → ratio ~1 → out of band.
    traffic = [_traffic("m", "h.com", i * MIN, active=60) for i in range(10)]
    conn = _events("m", "h.com", *range(0, 600, 5))
    ds = pr.Dataset(
        settings={
            "heartbeat_filter_enabled": True,
            "heartbeat_bytes_threshold": 10_000,
            "heartbeat_host_patterns": [],
            "presence_continuation_seconds": 120,
        },
        devices={"m": "m"},
        traffic=traffic,
        conn=conn,
    )
    res = pr.build_result(ds, _args())
    assert res.section_2b["total"]["ratio_default_vs_cur"] < 2.0
    assert res.gate["go"] is False
