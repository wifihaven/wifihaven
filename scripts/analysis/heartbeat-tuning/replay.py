#!/usr/bin/env python3
"""
#779 heartbeat-filter tuning replay.

Reads prod heartbeat-explain dumps under data/per-mac/<mac>.json and sweeps
candidate (bytesThreshold, activeFractionPct) parameter sets. Emits markdown
tables to stdout. Read-only — touches no API.
"""

import json
import pathlib
from collections import defaultdict

DATA = pathlib.Path(__file__).parent / "data"
DEVICES = json.loads((DATA / "devices.json").read_text())
MAC_TO_DEVICE = {d["mac"]: d for d in DEVICES}


def load_rows():
    """Yield (mac, profile, name, periodStart, host_str, host_type, activeSeconds, periodSeconds, bytes)."""
    for path in sorted((DATA / "per-mac").glob("*.json")):
        payload = json.loads(path.read_text())
        for r in payload["rows"]:
            mac = r["mac"]
            dev = MAC_TO_DEVICE.get(mac, {"profileName": "?", "name": mac})
            host = r["host"]
            host_str = host["value"] if host["type"] == "fqdn" else f"ip:{host['value']}"
            yield {
                "mac": mac,
                "profile": dev.get("profileName", "?"),
                "device": dev.get("name", mac),
                "period": r["periodStart"],
                "host": host_str,
                "host_type": host["type"],
                "active": r["activeSeconds"],
                "period_s": r["periodSeconds"],
                "bytes": r["bytes"],
            }


ROWS = list(load_rows())
print(f"# rows loaded: {len(ROWS)}", flush=True)


def is_heartbeat(row, bytes_thr, fraction_pct):
    if bytes_thr == 0 and fraction_pct == 0:
        return False
    if row["bytes"] < bytes_thr:
        return True
    if row["period_s"] > 0 and row["active"] * 100 < fraction_pct * row["period_s"]:
        return True
    return False


def replay(bytes_thr, fraction_pct):
    """Returns (profile_minutes, host_minutes, host_minutes_removed)."""
    kept = []
    removed = []
    for r in ROWS:
        if is_heartbeat(r, bytes_thr, fraction_pct):
            removed.append(r)
        else:
            kept.append(r)

    # Per-mac total minutes = sum over buckets (max activeSeconds per (mac, period))
    bucket_secs_kept = defaultdict(int)  # (mac, period) -> max active
    for r in kept:
        k = (r["mac"], r["period"])
        if r["active"] > bucket_secs_kept[k]:
            bucket_secs_kept[k] = r["active"]
    mac_secs = defaultdict(int)
    for (mac, _), secs in bucket_secs_kept.items():
        mac_secs[mac] += secs

    # Aggregate by profile
    profile_secs = defaultdict(int)
    for mac, secs in mac_secs.items():
        prof = MAC_TO_DEVICE.get(mac, {}).get("profileName", "?")
        profile_secs[prof] += secs
    profile_minutes = {p: s // 60 for p, s in profile_secs.items()}

    # Per-(profile, host) host minutes (KEPT)
    # hostMinutes attributes each bucket's secs to every distinct host in the bucket
    bucket_hosts_kept = defaultdict(set)  # (mac, period) -> set(host)
    bucket_max_secs_kept = defaultdict(int)
    for r in kept:
        k = (r["mac"], r["period"])
        bucket_hosts_kept[k].add(r["host"])
        if r["active"] > bucket_max_secs_kept[k]:
            bucket_max_secs_kept[k] = r["active"]
    host_secs_kept = defaultdict(lambda: defaultdict(int))  # profile -> host -> secs
    for k, hosts in bucket_hosts_kept.items():
        mac = k[0]
        prof = MAC_TO_DEVICE.get(mac, {}).get("profileName", "?")
        secs = bucket_max_secs_kept[k]
        for h in hosts:
            host_secs_kept[prof][h] += secs

    # Per-(profile, host) host minutes UNFILTERED minus KEPT  =  removed
    bucket_hosts_all = defaultdict(set)
    bucket_max_secs_all = defaultdict(int)
    for r in ROWS:
        k = (r["mac"], r["period"])
        bucket_hosts_all[k].add(r["host"])
        if r["active"] > bucket_max_secs_all[k]:
            bucket_max_secs_all[k] = r["active"]
    host_secs_all = defaultdict(lambda: defaultdict(int))
    for k, hosts in bucket_hosts_all.items():
        mac = k[0]
        prof = MAC_TO_DEVICE.get(mac, {}).get("profileName", "?")
        secs = bucket_max_secs_all[k]
        for h in hosts:
            host_secs_all[prof][h] += secs

    host_minutes_kept = {p: {h: s // 60 for h, s in d.items()} for p, d in host_secs_kept.items()}
    host_minutes_all = {p: {h: s // 60 for h, s in d.items()} for p, d in host_secs_all.items()}
    return profile_minutes, host_minutes_kept, host_minutes_all


# Candidates: (label, bytes_thr, fraction_pct)
CANDIDATES = [
    ("OFF (raw)", 0, 0),
    ("Current prod (2KB, 20%)", 2048, 20),
    ("C1: 10KB, 20%", 10240, 20),
    ("C2: 10KB, 25%", 10240, 25),
    ("C3: 100KB, 25%", 102400, 25),
    ("C4: 10KB, 50%", 10240, 50),
    ("C5: 100KB, 50%", 102400, 50),
]

# Profiles of interest — pick non-trivial ones
INTERESTING_PROFILES = ["Sameer", "Rachel", "Kids", "Family"]

results = []
for label, bt, fp in CANDIDATES:
    pm, hk, ha = replay(bt, fp)
    results.append((label, bt, fp, pm, hk, ha))
    print(f"\n## Candidate: {label}  (bytesThreshold={bt}, activeFractionPct={fp})")
    print(f"\n| Profile | Minutes |")
    print(f"|---|---:|")
    for p in INTERESTING_PROFILES:
        print(f"| {p} | {pm.get(p, 0)} |")

# Summary delta table per profile (all candidates side by side)
print("\n\n## Per-profile active minutes (all candidates)\n")
header = ["Profile"] + [c[0] for c in CANDIDATES]
print("| " + " | ".join(header) + " |")
print("|" + "|".join(["---"] + ["---:"] * len(CANDIDATES)) + "|")
for p in INTERESTING_PROFILES:
    row = [p] + [str(r[3].get(p, 0)) for r in results]
    print("| " + " | ".join(row) + " |")

# Hosts removed (vs OFF baseline) — top 10 per (candidate, profile)
print("\n\n## Top-10 hosts removed by each candidate (host-minutes delta vs OFF)\n")
off_hk = results[0][4]  # host_minutes_kept under OFF == all
for label, bt, fp, pm, hk, _ha in results[1:]:
    print(f"\n### {label}")
    for p in INTERESTING_PROFILES:
        all_h = off_hk.get(p, {})
        kept_h = hk.get(p, {})
        removed = [(h, all_h.get(h, 0) - kept_h.get(h, 0)) for h in all_h]
        removed.sort(key=lambda x: -x[1])
        removed = [r for r in removed if r[1] > 0][:10]
        if not removed:
            continue
        print(f"\n**{p}** — top-10 hosts removed:")
        print("\n| Host | Minutes removed | Minutes kept |")
        print("|---|---:|---:|")
        for h, delta in removed:
            print(f"| `{h}` | {delta} | {kept_h.get(h, 0)} |")

# Hosts kept — top 10 per profile under recommended candidate
RECOMMENDED = "C3: 100KB, 25%"
rec = next(r for r in results if r[0] == RECOMMENDED)
print(f"\n\n## Top-10 hosts KEPT under recommended {RECOMMENDED}\n")
for p in INTERESTING_PROFILES:
    kept_h = rec[4].get(p, {})
    top = sorted(kept_h.items(), key=lambda x: -x[1])[:10]
    if not top:
        continue
    print(f"\n**{p}**:")
    print("\n| Host | Minutes kept |")
    print("|---|---:|")
    for h, m in top:
        print(f"| `{h}` | {m} |")
