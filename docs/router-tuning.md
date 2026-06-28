# Router cadence tuning

The OpenWRT agent's behaviour is shaped by a handful of cadence knobs that
control how often it polls the API, scrapes counters, samples activity, and
flushes events. This page documents each knob, the trade-offs in raising or
lowering it, and the couplings between them.

All settings live in `/etc/config/wifihaven` (`config wifihaven 'wifihaven'`)
and are read once at agent startup by `wifihaven-agent`. After editing the
file, restart the service so the new values take effect:

```sh
/etc/init.d/wifihaven restart
```

You can edit these in the LuCI web UI under **Administration → Services →
WifiHaven → Settings**, or by hand in `/etc/config/wifihaven`.

## Cadence knobs

### `policy_poll_interval` (default `5`)

How often (seconds) the agent calls `GET /api/router/policy` to fetch the
policy snapshot.

- **Lower** → site-limit changes, pauses, and `extraAllowed`/`extraBlocked`
  edits propagate to the router faster; more API requests/minute (one per
  router).
- **Raise** → less API load; admin-UI changes feel laggy (a 30 s interval
  means a parent's "pause" tap could take up to 30 s to bite).
- **Suggested range:** `3`–`30`. Below `3` the `curl` spawn overhead
  dominates; above `30` the UX gets noticeably sluggish.

### `usage_report_interval` (default `60`)

How often (seconds) the agent scrapes nftables counters and POSTs
`/api/router/usage`.

- **Lower** → more granular daily usage timeline (#721, #716); more API
  writes; bigger DB churn.
- **Raise** → coarser timeline buckets; reduced API load. The server treats
  each POST as a delta, so raising this does **not** lose bytes — only
  timeline resolution.
- **Couples with** `activity_sample_int`: must divide
  `usage_report_interval` evenly. With defaults (10 / 60) every report
  carries 6 samples.

### `activity_sample_int` (default `10`)

How often (seconds) the agent samples per-MAC conntrack activity to compute
`activeSeconds` (#295, #516).

- **Lower** → finer `activeSeconds` accuracy (a 5 s sample can attribute
  use down to 5 s buckets); more CPU on the router (nft + conntrack scrape
  per tick).
- **Raise** → less CPU; coarser accuracy, and the upper bound on
  under-counting grows (a device that pings the router once every
  `activity_sample_int - 1` seconds would still register as active).
- **Couples with** `usage_report_interval`: must evenly divide it to avoid
  partial buckets at report boundaries. The agent emits a `warn` log line
  at startup if the values are inconsistent and continues with degraded
  accuracy.

### `conntrack_tick_interval` (default `1`)

How often (seconds) the agent's idle heartbeat drives `on_tick` — the
cooperative dispatcher that runs the usage flush, the `activity_sample_int`
sampler, the policy poll, the nflog drain, and the metrics push.

Before #2024 those timers fired only when a `conntrack -E -e NEW` line
arrived. On a quiet LAN — a single device on a long-lived connection (a kid
alone on a websocket game, one device streaming) emits almost no NEW events —
`on_tick` stalled for minutes: the usage window ballooned to span the whole
un-monitored gap (the server's session-stitch then credited it all as
presence → the #2016 over-count) and the activity sampler starved. The
watcher now multiplexes a heartbeat line into the conntrack popen stream every
`conntrack_tick_interval` seconds, so `on_tick` fires on a wall-clock cadence
regardless of traffic.

- **Lower** → tighter worst-case latency on every cooperative timer when the
  LAN is idle; marginally more CPU (one extra `on_tick` per second is a few
  microseconds of monotonic-diff comparisons that early-return — the agent is
  niced below dnsmasq, #1864).
- **Raise** → fewer idle wakes; the activity sampler and usage flush can run
  up to `conntrack_tick_interval` seconds late, so keep this **well below**
  `activity_sample_int` (a value ≥ `activity_sample_int` reintroduces sampling
  starvation, the exact failure this knob fixes).
- **Leading indicator**: the `usage_window_stall_total` metric increments
  whenever a reported window still exceeds 2× `usage_report_interval` — i.e.
  the heartbeat failed to keep `on_tick` alive. A healthy fleet holds it flat
  at 0 (see the "Usage window stalls per router" panel on the router-fleet
  Grafana dashboard).

### `event_batch_size` (default `50`)

How many `connection_attempt` events the agent buffers before flushing to
`/api/router/events`.

- **Lower** → events appear in the UI sooner; more requests/minute.
- **Raise** → fewer requests; events feel laggy and a router crash loses
  more in-flight events.

### `event_flush_interval` (default `10`)

Force-flush the event buffer after this many seconds even if
`event_batch_size` is not reached.

- **Lower** → bounded staleness even on quiet networks; more empty/small
  POSTs.
- **Raise** → fewer POSTs on quiet networks; events from rare devices can
  sit buffered for a long time.

## Non-cadence but cadence-adjacent

- `debug` (default `0`) — does not change cadence but increases log volume
  per tick. Bump to `1` only when actively diagnosing; leave at `0` in
  steady state.

## Safe-range / coupling summary

| Knob | Default | Suggested range | Couples with |
|---|---|---|---|
| `policy_poll_interval` | 5 | 3–30 | — |
| `usage_report_interval` | 60 | 30–300 | `activity_sample_int` (must divide evenly) |
| `activity_sample_int` | 10 | 5–30 | `usage_report_interval`, `conntrack_tick_interval` |
| `conntrack_tick_interval` | 1 | 1–5 | `activity_sample_int` (must stay well below) |
| `event_batch_size` | 50 | 10–200 | `event_flush_interval` |
| `event_flush_interval` | 10 | 5–60 | `event_batch_size` |

## Worked examples

**Low-traffic home (default-ish but lighter):**

```
option policy_poll_interval '10'
option usage_report_interval '120'
option activity_sample_int '15'   # 120 / 15 = 8 samples per report
option event_batch_size '25'
option event_flush_interval '15'
```

**Lab / development (fast feedback in the UI):**

```
option policy_poll_interval '3'
option usage_report_interval '30'
option activity_sample_int '5'    # 30 / 5 = 6 samples per report
option event_batch_size '10'
option event_flush_interval '5'
```

**Battery-constrained or weak CPU router:**

```
option policy_poll_interval '20'
option usage_report_interval '300'
option activity_sample_int '30'   # 300 / 30 = 10 samples per report
option event_batch_size '100'
option event_flush_interval '30'
```
