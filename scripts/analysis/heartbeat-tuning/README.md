# Heartbeat-filter tuning replay (#779)

Replays the #714 server-side heartbeat filter against a prod snapshot of
`/api/time/heartbeat-explain/{mac}?date=` rows and produces a comparison table
across candidate `(bytesThreshold, activeFractionPct)` parameter sets.

Run:

```sh
# 1. Pull data (writes data/per-mac/<mac>.json — one heartbeat-explain payload each)
TOKEN=$(curl -sS -X POST https://api.wifihaven.net/api/auth/login \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$ADMIN_PW\"}" | jq -r '.token')
DATE=2026-05-21
for mac in $(jq -r '.[].mac' data/devices.json); do
  curl -sS -H "Authorization: Bearer $TOKEN" \
    "https://api.wifihaven.net/api/time/heartbeat-explain/$mac?date=$DATE" \
    > data/per-mac/$mac.json
done

# 2. Replay
python3 replay.py > report.md
```

Read-only. The replay logic mirrors `wifihaven.api.presence.Presence`:

- `isHeartbeat`: drop row if `bytes < bytesThreshold` OR
  (`periodSeconds > 0 && activeSeconds*100 < activeFractionPct*periodSeconds`).
- Per-mac total = sum over buckets of `max(activeSeconds)` per `(mac, period)`.
- Profile minutes = sum of per-mac totals for that profile.
- Host minutes = bucket `max(activeSeconds)` attributed once to each distinct
  host in the bucket (matches `Presence.hostMinutes`).

See [#779](https://github.com/wifihaven/wifihaven/issues/779) for the
recommendation produced from this analysis.
