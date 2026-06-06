# `scripts/analysis/` — presence replay / validation harness

Read-only analysis tooling that replays a week of real prod data to **gate the
tuned presence defaults** — the go/no-go check on the session-stitch model,
`presence_continuation_seconds`, and the heartbeat-filter thresholds
([#1467](https://github.com/wifihaven/wifihaven/issues/1467), which subsumes the
heartbeat-replay re-run [#790](https://github.com/wifihaven/wifihaven/issues/790)).

Spec: [`docs/design/presence-tuning.md`](../../docs/design/presence-tuning.md) §5
item 5; it reproduces §2b/§2d and the §Appendix recipe.

## What it checks

| Section | Question | Pass condition |
|--------|----------|----------------|
| **§2b** | Does the session model recover the within-minute undercount that the legacy `max(activeSeconds)` model loses? Is `N = 120 s` at the knee? | `session(N=120)/cur` in the design's band; `session(N=120)/full` small (loss is *within* the window, not across) |
| **§2d** | Is the session-stitch union rate-independent — stable when the same day is re-bucketed at `R = 10/60/300 s`, and does it collapse when `N < R`? | spread across `R` small at `N ≥ R`; union ≈ 0 at `N < R` |
| **HB** (#790) | Over a real week, is the heartbeat bytes floor neither over-suppressing real screen time (lowering it recovers little) nor sitting on the edge of real interactive traffic (raising it drops little)? | no cliff in either direction across a 2× threshold sweep |

The computation **mirrors `api/src/presence/Presence.scala`** (`spanOf` / `stitch`
/ `unionSeconds` / `effectiveGap` / `isHeartbeat`) so the gate reflects the
*shipped* behaviour. It is a validation tool, not the production path; the
fixture-based test pins it so it can't bit-rot.

## Layout

- [`presence_replay.py`](presence_replay.py) — the harness: pure computation +
  CLI. Reads a directory of exported JSON, prints the comparison tables and a
  pass/fail gate, exits non-zero on **NO-GO**.
- [`fetch_prod_data.sh`](fetch_prod_data.sh) — read-only prod extraction into that
  directory. Touches prod (SELECT-only, under a `statement_timeout`); the harness
  never does.
- [`test_presence_replay.py`](test_presence_replay.py) — fixture-based unit tests
  of the computation (no network/DB).

## Running it

### 1. Export a week of prod data (out of band, read-only)

Get a **read-only** prod DSN out of band and export it (never echo it). One way —
via the Render Postgres connection-info API (the operator's `RENDER_API_KEY`,
kept in local memory, never committed):

```sh
RENDER_API_KEY=$(awk '/^rnd_/{print; exit}' \
  ~/.claude/projects/-Users-sameer-workspace-wifihaven/memory/render_api_token.md)
export PROD_DSN=$(curl -fsS -H "Authorization: Bearer $RENDER_API_KEY" \
  "https://api.render.com/v1/postgres/<prod-pg-id>/connection-info" \
  | jq -r '.externalConnectionString')

# Pull the kid devices (the undercount-prone profiles) for a date range:
./fetch_prod_data.sh ./data 2026-05-30 2026-06-05
```

`fetch_prod_data.sh` writes `settings.json`, `devices.json`,
`traffic_reports.json`, `connection_events.json` into `./data` (gitignored —
**never commit prod data**). Override the devices with
`KID_MACS='aa:bb:..,cc:dd:..'`; omit the date args to default to the last 7 days
present.

> Rotate the Render API key after the session, per the standing key-handling rule.

### 2. Run the gate

```sh
python3 presence_replay.py ./data            # text report + gate verdict; exits 1 on NO-GO
python3 presence_replay.py ./data --json     # machine-readable
```

Gate tolerances are CLI flags (`--ratio-min/-max`, `--bridge-max`,
`--invariance-tol`, `--collapse-max`, `--hb-delta-tol`); the defaults encode the
design's conclusions.

### 3. Tests

```sh
python3 -m pytest test_presence_replay.py -v
```

## Result (week of 2026-05-30 → 06-05, kid devices)

Run against live prod (continuation `N=120`, heartbeat floor `10000`, 16 FQDN
patterns):

- **§2b** — `session(N=120)/cur = 3.42×` (design measured ~3.46×); the dominant
  loss is within the window (`session/full = 1.04×`).
- **§2d** — union stable across `R`: 864 / 889 / 965 min at `R = 10/60/300`
  (spread 0.10, matching the design's ~7% drift; e.g. Kid Mac 06-02 reads
  117 / 119 / 125, the design's exact figures). `N < R` collapses to 0.
- **HB** — floor sensitivity is modest and monotone (1751 → 1523 → 1196 kept min
  at 5 KB / 10 KB / 20 KB; ±15–22%), no cliff. The FQDN allowlist is doing real
  work (top dropped: `gdmf.apple.com`, `*.courier.push.apple.com`, `*.akadns.net`).

**Verdict: GO — the tuned defaults validate.**
