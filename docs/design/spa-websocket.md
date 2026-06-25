# Design — SPA-side websocket (live browser updates)

Status: **proposed** (design only — no code in this PR).

Relates to [#1023](https://github.com/wifihaven/wifihaven/issues/1023) (the
router↔API transport epic — its push-on-change core and **realtime usage stream**
are the data sources this consumes),
[#1846](https://github.com/wifihaven/wifihaven/issues/1846) (the server
connection-registry pattern this reuses),
[#1849](https://github.com/wifihaven/wifihaven/issues/1849) (push-on-change — one
of the event sources this consumes),
[#747](https://github.com/wifihaven/wifihaven/issues/747) (live bytes in/out — the
realtime throughput element this finally makes shippable),
[#1148](https://github.com/wifihaven/wifihaven/issues/1148) /
[`docs/design/dashboard-redesign.md`](dashboard-redesign.md) (the dashboard whose
live sections **consume** this stream; its §8 data-contract is the input to §1
here), and [#1860](https://github.com/wifihaven/wifihaven/issues/1860) (this issue).

> **Scope of this doc.** Design only, no implementation. It (a) **specifies what
> data flows over the socket and why each item is on it** (§1 — the heart of this
> design), (b) decides the contentious choices — push-vs-invalidate per data
> class, auth handshake, registry reuse-vs-fork — with justification, and (c)
> lays out a phased rollout in which **polling stays the fallback throughout (no
> flag day)**. Implementation sub-issues are in §9.

---

## 0. Why, and what the socket is *for*

### 0.1 The socket exists to stream the live surface — not to "poll faster"

The crucial framing, because the first draft of this doc got it wrong. The SPA
today live-updates by **TanStack Query `refetchInterval` polling**
([`web/src/api/queries.ts`](../../web/src/api/queries.ts)): dashboard "now" every
10 s, recent-blocked every 10 s, alerts every 30 s, an adaptive time-status
ladder. The websocket is **not** a generic "invalidate every query faster"
transport bolted under all of that. It exists to **stream the handful of facts
that must move in realtime** — and most of those are *not* things the SPA can
poll well, or at all:

- **Live in/out throughput** (overall + per-profile bandwidth, B/s) — the headline
  the dashboard redesign asks for ([#747](https://github.com/wifihaven/wifihaven/issues/747),
  [`dashboard-redesign.md` §8.1](dashboard-redesign.md)). **This is a rate, not a
  resource** — there is no body you can `GET` and cache; it only exists as a
  stream. It has *no good polling implementation at all*. It is the single
  clearest reason the websocket needs to exist.
- **"NOW" — who's online and what they're watching** (`DashboardNow`), the heart
  of the dashboard, today polled every 10 s with visible lag.
- **"Most Recently Blocked"** — the live "what just got dropped" feed
  ([#1338](https://github.com/wifihaven/wifihaven/issues/1338)), today polled
  every 10 s.
- **Live time-usage — per-profile and per-app minutes** (used / remaining,
  counting up as time accrues). Today this is the one surface with a hand-rolled
  *adaptive* refetch ladder (`TIME_STATUS_REFETCH_LADDER`,
  [`queries.ts`](../../web/src/api/queries.ts)) — polling at 10 s when a profile
  is near its cap, slowing to 5 m otherwise — which exists *only* because there is
  no push. The whole ladder collapses into a push: the server already computes the
  live, continuation-adjusted minutes (`TimeStatusService.dayStateAllLive`,
  per-app via `Presence.appSecondsForProfile`), so it can emit them when they move
  instead of the SPA guessing a poll rate.

Everything else the SPA shows — profiles, devices, schedules, blocklists, the 24 h
rollup panels, time-status caps — is a **cacheable resource that changes
occasionally**. Those stay on request/response (with at most an invalidate nudge
on the rare change). **The websocket carries the live surface; it does not
replace queries that should stay queries.** This split is the spine of the whole
design (§1).

The three structural wins, restated against that framing:

- **Realtime data that polling cannot serve** (throughput) becomes possible.
- **Sub-second freshness** on NOW / blocked, vs. the 10 s poll lag.
- **A liveness signal** — the connection's health *is* the "is my dashboard
  live?" indicator, replacing the silent-poll ambiguity (a dead 10 s poll looks
  identical to "nothing changed"; [`ApiUnreachableBanner`](../../web/src/components/ApiUnreachableBanner.tsx)
  only fires on an *active* request failure, not a poll that never ran).

### 0.2 The one shaping constraint — the browser `WebSocket` is impoverished

A browser `WebSocket` is not `curl` and not a server socket:

1. **It cannot set request headers** on the upgrade — no `Authorization: Bearer`.
   This is *the* reason the SPA auth handshake (§4) differs from the router's
   bearer-on-upgrade ([`websocket-transport.md` §4.1](websocket-transport.md)).
2. **It cannot send ping/pong control frames** from JS — the heartbeat (§6.2) is
   an app-level text frame.
3. **It is per-tab and dies on background/sleep** — reconnect, backoff, and
   multi-tab behavior (§6) are SPA concerns the router never had.

The server side is the easy half — `zio-http`'s `Handler.webSocket` already backs
`GET /api/router/ws` ([`RouterWsRoutes.scala`](../../api/src/routes/RouterWsRoutes.scala)).
We **reuse the router transport's patterns** (envelope, demux, registry shape,
metric discipline) and **fork where the two genuinely differ** (auth, key,
payload vocabulary) — "share patterns, not code," same call #1023 makes.

### 0.3 Read-model discipline — reuse bodies for resources, ONE new shape for the stream

Per the single-source-of-truth discipline
([`AGENTS.md#single-source-of-truth`](../../AGENTS.md), wire-shape carve-out):

- **Pushed snapshots of existing resources carry the existing REST body,
  verbatim.** A NOW push is the exact `GET /api/dashboard/now` body
  (`DashboardNow`, [`shared/src/Models.scala`](../../shared/src/Models.scala)); a
  blocked-event push is the exact `/api/logs?blocked=true` row shape. **Zero new
  serializers** for these — the `GET` stays the schema authority and a refetch
  reconciles.
- **The one genuinely-new shape is the throughput tick** (§1.3), and it is new
  *because the data is new*: no REST endpoint produces a current byte-rate today
  (`DashboardNow*` carries no bytes — confirmed in `Models.scala`). A push-native
  telemetry stream has no existing body to reuse; inventing a minimal one is
  correct, not a violation. It is deliberately tiny and is **not** mirrored into a
  REST resource (you would never poll it).

---

## 1. What flows over the socket — the message catalog

This is the section the design turns on. Every server→SPA payload maps to a
dashboard live element ([`dashboard-redesign.md` §8.2](dashboard-redesign.md) is
the input contract); every item is classified by **why** it is on the socket.

### 1.1 Three data classes

| Class | What | Examples | How it rides the socket |
|---|---|---|---|
| **(1) Push-native realtime stream** | a *rate/stream* with no cacheable REST form | **live throughput** (overall + per-profile in/out B/s) | server pushes a `throughput` tick on a fixed cadence; client renders a live gauge — **not** in the React Query resource cache |
| **(2) Pushed live snapshot/append** | a real REST resource that changes fast enough to push instead of poll | **NOW** (`DashboardNow`), **Most Recently Blocked** feed, **live time-usage** (per-profile used/remaining + per-app minutes) | server pushes the (changed) body / the new row; client patches the existing React Query cache for that key (§3) |
| **(3) Occasionally-changing resource** | a cacheable resource that changes rarely | profiles, devices, schedules, blocklists, 24 h rollup panels | **stays request/response.** At most a `stale` nudge on a relevant mutation; many need nothing (their mutation already invalidates locally) |

The websocket is for **(1) and (2)**. **(3) stays on queries** — the explicit
correction to the "invalidate everything" framing. The dashboard's "Blocking
activity (24 h)" panel and the "Events (1h)/Blocked (1h)" KPIs are class (3): they
ride the rollup tables on request/response and are *not* streamed
([`dashboard-redesign.md` §7.5/§8.2](dashboard-redesign.md)).

### 1.2 Server→SPA message catalog

| `op` | Class | Payload | Drives (dashboard element) | Source |
|---|---|---|---|---|
| `throughput` | (1) | `ThroughputTick` (§1.3) | overall in/out gauge **+** per-profile ▲▼ on NOW card headers (#747) | API aggregator over the #1023 usage stream (§5.3) |
| `now` | (2) | `DashboardNow` (existing body, verbatim) | NOW active cards; derived "Online now / Blocked now" KPIs | recompute on change (§5.2), reusing the `/api/dashboard/now` builder |
| `blocked` | (2) | one blocked `QueryLog` row (existing `/api/logs` row shape) | Most Recently Blocked feed (prepend) | connection-events ingest (§5.2) |
| `timeStatus` | (2) | `ProfileTimeStatus[]` (existing `/api/time/status` body) | per-profile **used / remaining** bars (live), "profiles over limit" KPI | usage credit + #1849 ticker + extension grant (§5.2), via `TimeStatusService.dayStateAllLive` |
| `appUsage` | (2) | per-app engaged-minutes (existing `/api/profiles/{id}/usage-by-app` body) | per-app **minutes-used** rows (live) on the expanded profile / screen-time view | same triggers, via `Presence.appSecondsForProfile` |
| `stale` | (3) | `{ topic, scope? }` (bounded `topic` enum) | invalidate a class-(3) query (profiles/devices/schedules/blocklists) | the relevant write site (§5.2) |
| `ready` | — | `{ role, serverTime }` | flips the live indicator to "live" | upgrade (§4) |
| `ping`/`pong` | — | `{}` | heartbeat (§6.2) | — |

SPA→server: `hello` (`{clientVersion, subscribe?:[…]}`), `reauth` (`{ticket}`,
§4.3), `ping`/`pong`. **Unknown `op` → ignore + meter**, both directions
(forward-compat, mirrors [`RouterWsRoutes.dispatch`](../../api/src/routes/RouterWsRoutes.scala)).

### 1.3 The one new shape — `ThroughputTick`

```jsonc
// op:"throughput" payload — bytes-per-second rates as of `asOf`
{
  "asOf": "2026-06-24T14:31:02Z",
  "overall":     { "inBps": 1840000, "outBps": 240000 },
  "perProfile": [
    { "profileId": 3, "inBps": 1510000, "outBps": 90000 },
    { "profileId": 7, "inBps":  330000, "outBps": 150000 }
  ]
}
```

- **Rates, not cumulative bytes** — the SPA renders a gauge/sparkline, not a
  counter; the server does the rate math once (§5.3) so every tab doesn't
  re-derive it (single-source-of-truth for the rate).
- **Overall + per-profile only.** Per-device / per-host throughput is deliberately
  **off** the dashboard ([`dashboard-redesign.md` §8.1](dashboard-redesign.md));
  the same tick *shape* could carry a `perDevice` array for a future `/devices`
  live view, but v1 emits only overall + per-profile.
- **Bounded size** — one entry per active profile (households have ≤ ~10), well
  under any frame cap.
- **A profile absent from `perProfile`** is idle (0 B/s) — the client zeroes it;
  the tick never grows with history.

This is the only addition to `web/src/types/api.ts`; `now`/`blocked`/`stale`
payloads reuse shapes that already exist there.

### 1.4 hello / subscribe

On connect the SPA sends `hello` once. `subscribe` is an **optional** topic
filter (a child-role tab rendering only its own time-status need not receive
`throughput`); omitted → the server pushes everything the role is authorized for
(§4.4). v1 ships omit-everything-authorized; the filter is the forward-compat seam
and is honored if sent. The server replies `ready`; the client flips its
indicator to "live" only after `ready` (a socket that upgrades but never `ready`s
must not read as live).

---

## 2. Endpoint & protocol

### 2.1 Endpoint

```
wss://<api-host>/api/ws
```

One endpoint for the operator SPA (role scoping applied per-frame from the
authenticated claims, §4.4 — no separate `/api/admin/ws`). `<api-host>` is the
**same host the REST client already targets** — `VITE_API_BASE_URL`
([`client.ts`](../../web/src/api/client.ts)): empty (same-origin) for the
JVM-bundled self-hosted build, absolute `https://api.wifihaven.net` for the
Cloudflare-Pages cloud build. The SPA derives the ws URL from the same base:

```ts
const wsBase = (VITE_API_BASE_URL || location.origin).replace(/^http/, 'ws')
const url = `${wsBase}/api/ws?ticket=${ticket}`   // ticket: §4.2
```

The Cloudflare-Pages-vs-Render hosting split is covered in §8.

### 2.2 Frame envelope — mirror the router transport

Identical envelope discipline to
[`websocket-transport.md` §1.2](websocket-transport.md), so the two share patterns
(and a future shared `WsFrame` codec) without sharing semantics:

```json
{ "op": "<string>", "payload": <existing REST JSON | small control/tick object>, "seq": <int?> }
```

- **One JSON text message per frame.** Never split a frame across messages, never
  batch two frames into one.
- **`op`** — the discriminator the receiver demuxes on (§1.2). `seq` is optional,
  observability only (gap/dup logging), ignored if absent.
- **Unknown `op` → ignore + meter**, both ends — the forward-compat rule that lets
  a future op ship without a flag day.

---

## 3. Client integration — push-data for the live surface, invalidate for the rest

The central client-side decision, now made **per data class** (§1.1) rather than
one-size-fits-all:

### 3.1 Class (1) throughput — direct to a live store, not the resource cache

A `throughput` tick is a stream sample, not a cache entry. The `useWsThroughput()`
hook holds the latest tick (a small `useSyncExternalStore` or a dedicated query
key updated via `setQueryData`) and feeds the overall gauge + per-profile card
headers. There is nothing to invalidate and no `GET` to refetch — when the socket
is down the gauge shows "—" (its only fallback; the 24 h volume on `/usage`
answers the historical question). Latest-wins: a new tick replaces the prior one
(§6.3).

### 3.2 Class (2) NOW + blocked — push carries data, patch the cache

For the live snapshots we **push the data and patch the React Query cache**
directly — the *opposite* of the default for class (3), and correct here because
the entire point is to remove the refetch RTT on the live surface:

```ts
// on `now`  → replace the dashboard-now cache with the pushed body
queryClient.setQueryData(qk.dashboardNow(), tick.payload)
// on `blocked` → prepend the new row to the recent-blocked cache (bounded, dedup by id)
queryClient.setQueryData(qk.recentBlocked(), prev => prependCapped(prev, row))
// on `timeStatus` → replace the per-profile used/remaining cache with the pushed body
queryClient.setQueryData(qk.timeStatusToday(), rows)
// on `appUsage` → patch only the LIVE (today) per-app cache entry; past windows
// are immutable, so the push targets today's [from,to] key, not every cached window
queryClient.setQueryData(qk.profileUsageByApp(profileId, todayFrom, todayTo), appRows)
```

Justification this is safe despite (3)'s SSOT argument: the pushed body **is** the
exact `GET` body (§0.3), written through the **same** query key, so a later
refetch (on reconnect, §6.1, or the paused-poll fallback, §3.4) reconciles against
the authority. The server applies the *same* per-role visibility filter the `GET`
uses before fan-out (§4.4 / §5.2) — for the household's small fan-out this is one
filtered build, not a per-recipient risk surface. The derived KPIs ("Online now /
Blocked now") are computed client-side off the pushed `DashboardNow`, so they
update with the same push — no separate stream.

### 3.3 Class (3) everything else — `stale` → invalidate (or nothing)

For occasionally-changing resources, a `stale{topic}` frame triggers
`queryClient.invalidateQueries` on the mapped key, reusing the existing `qk`
factory / `useInvalidators` ([`queries.ts`](../../web/src/api/queries.ts)). The
topic→key map is the one place the mapping lives. `invalidateQueries` only
refetches **mounted** queries, so a `stale{time-status}` while that view is closed
costs nothing. Many class-(3) changes already invalidate locally via a mutation's
`onSuccess` and need no socket frame at all — the `stale` op is for the case where
*another* operator/tab made the change. **No thick push for class (3):** it keeps
the `GET` as the sole producer and gets per-recipient authz for free.

### 3.4 Polling stays the paused fallback

`refetchInterval` is **not removed** — it is gated on socket health via a
`useWsLive()` signal (§6.2):

```ts
useDashboardNow({ refetchInterval: wsLive ? false : 10_000 })
```

Socket healthy → interval paused, pushes drive updates; socket down/reconnecting →
interval resumes at today's cadence. The **time-usage adaptive ladder**
(`TIME_STATUS_REFETCH_LADDER`) is the clearest case: it stays exactly as-is as the
disconnected fallback, but goes dormant while the `timeStatus` push is live —
and is the prime candidate for full retirement (§9, **S7**) since its entire
reason for existing is the absence of a push. The redundant intervals are retired
only at the *end* of the rollout, per-view, after each view's push path is proven.
Live throughput (class 1) has no poll fallback — it simply shows "—" while
disconnected.

---

## 4. Auth

The SPA authenticates with the **operator JWT** (`AuthService` /
[`useAuth`](../../web/src/hooks/useAuth.tsx) — `localStorage.token`, HS256,
`{sub, role, iat, exp}`, [`AuthService.scala`](../../api/src/auth/AuthService.scala)).
The browser `WebSocket` can't send the `Authorization` header that carries it
(§0.2.1), so the upgrade needs a different mechanism.

### 4.1 The three options

| Mechanism | Why rejected / chosen |
|---|---|
| **Cookie** | **Rejected.** The SPA's auth model is a `localStorage` bearer, not cookies; an auth cookie means CSRF defenses + a second credential to sync. |
| **Subprotocol** (JWT in `Sec-WebSocket-Protocol`) | **Rejected.** Abuses a negotiation header to carry a secret; the full JWT lands in access/proxy logs. |
| **Short-lived single-use ticket** | **Chosen.** ✓ |

### 4.2 Decision: short-lived, single-use ticket

```
SPA                                              API
 │  POST /api/ws/ticket   Authorization: Bearer <jwt>   │  requireAuth(req) → JwtClaims
 │ ───────────────────────────────────────────────────▶ │  mint random 256-bit ticket,
 │  { "ticket": "<opaque>", "expiresInSec": 30 }         │  store {ticket → (sub, role, jwtExp)} TTL=30s, single-use
 │  GET /api/ws?ticket=<opaque>   Upgrade: websocket     │  consume ticket (atomic delete);
 │ ───────────────────────────────────────────────────▶ │  invalid/expired/used → 401, no 101
 │  101 Switching Protocols  → register channel (§5)     │  → resolve (sub, role)
```

- **The ticket is not the JWT** — opaque, single-use, TTL ~30 s, stored
  server-side (a `Ref[Map[Ticket, TicketEntry]]` in the SPA registry §5.1) bound
  to `(sub, role, jwtExp)`. The query-string exposure that makes "JWT in the URL"
  unacceptable is bounded to a 30 s one-shot non-JWT token. Minting reuses
  `requireAuth` on the `POST`, so there is **one** JWT-validation implementation —
  the ws path is not a second auth surface.
- **Consume-at-upgrade is atomic** (delete-returns-old) → a ticket can't be used
  twice; a URL-sniffing replayer loses the race and finds it already gone.
- **Bad/expired/used ticket → reject the upgrade with 401** (no 101), the
  router-path semantics, metered `spa_ws_auth_total{result=…}` (§7).

### 4.3 Token expiry mid-connection + re-auth

The ticket gates only the *upgrade*; the connection's authz deadline is the JWT's
`exp`, captured at upgrade.

- **Expiry while connected.** The server tracks `jwtExp` per channel; on the
  heartbeat tick where `now ≥ jwtExp` it closes with `4401 token-expired`
  (metered `jwt_expired`). Stale-authz carry-over is bounded by one heartbeat
  interval — same property the router path gives for revocation.
- **Re-auth.** The SPA has **no silent token refresh** today — an expired JWT
  means 401 → `localStorage` clear → `/login` ([`client.ts`](../../web/src/api/client.ts)).
  v1 mirrors this: on `4401` the SPA stops reconnecting, falls back to polling
  (§3.4); the next REST call 401s → the existing `/login` redirect; after
  re-login it mints a fresh ticket and reconnects. The **`reauth` op** (§1.2) is
  the forward-compat seam: when silent refresh lands, the SPA mints a ticket from
  the refreshed JWT and sends `reauth` on the open socket; the server
  validates+consumes it and advances `jwtExp` — no reconnect. v1 server may
  `ack`-reject `reauth`.

### 4.4 Role scoping per frame

The resolved `role` is captured on the channel at upgrade and is the fan-out authz
key: the server only pushes a topic to a connection whose role may see it
(`stale{...}` for an admin-only resource → admin connections only; per-profile
throughput → filtered to the profiles the role may view). For class-(2) thick
pushes the body is filtered per-role *before* send, exactly as the matching `GET`
filters (§3.2 / §5.2). For class-(3) `stale` signals the worst case of a fan-out
bug is a needless refetch the `GET`'s own `requireAuth` then scopes — defense in
depth.

---

## 5. Server side — registries, change sources, the throughput aggregator

### 5.1 Decision: fork a parallel `SpaWsRegistry`; share the change sources

**Fork `SpaWsRegistry`; do not generalize
[`RouterWsRegistry`](../../api/src/routes/RouterWsRegistry.scala) to hold both.**
The two differ on the three axes a registry *is*:

| Axis | `RouterWsRegistry` (#1846) | `SpaWsRegistry` (this design) |
|---|---|---|
| **Key** | `RouterId` | per-connection id + `role` (a user may have N tabs, §6.4) |
| **Fan-out payload** | full `PolicySnapshot` | the §1.2 op vocabulary (`throughput`/`now`/`blocked`/`stale`), role-filtered |
| **Event vocabulary** | one event (policy changed) | several (throughput tick, NOW change, new blocked event, class-3 mutations) |

Generalizing one registry across two key types + two payload vocabularies couples
things that share only the *shape* "a `Ref` of channels with a fan-out method."
Reuse the **pattern**, not the instance — `SpaWsRegistry` is the same
`Ref[Map[K, Set[WebSocketChannel]]]` with SPA-appropriate K, payloads, and
metrics. (Also holds the ticket store, §4.2.)

### 5.2 The change sources — consume existing write sites (don't rebuild)

The reuse point is the **event source**, not the registry. The SPA registry
subscribes to changes published at write sites that already run on the relevant
change — no new polling/diffing loop:

1. **Generalize the single-sink `PolicySnapshotPublisher` to a multi-subscriber
   hub.** Today it's an `AtomicReference[PolicySnapshotPublisher]` with one sink
   ([`PolicyService.setPublisher`](../../api/src/policy/PolicyService.scala)).
   Widen to a `Hub` so both `RouterWsRegistry` (wants the full snapshot) and
   `SpaWsRegistry` (wants `stale{topic:"time-status"|...}` derived from "policy
   changed") subscribe. Behavior-preserving for the router subscriber.
2. **A `SpaEventHub`** (`Hub[SpaEvent]`) fed by existing write sites, each a
   one-line publish:
   - connection-events ingest (`RouterIngestService`, the shared handler both REST
     and router-ws ingest call) → a `blocked` push (the new row) + a `now`
     recompute trigger.
   - usage ingest → feeds the throughput aggregator (§5.3); a `now` trigger; and a
     `timeStatus` + `appUsage` recompute (newly-credited minutes move used/
     remaining) via `TimeStatusService.dayStateAllLive` / `Presence.appSecondsForProfile`.
   - policy reevaluate (#1849) → `now` recompute. The #1849 time-boundary **ticker**
     (schedule boundary, cap exhaustion) → a `timeStatus` push, since those
     transitions change remaining-minutes / over-limit without new usage.
   - time-extension grant (`POST /api/time/extend`) → a `timeStatus` push (the
     grant immediately changes remaining-minutes).
   - alert raised → `stale{alerts}`; profile/device/schedule mutations →
     `stale{profiles|devices|schedules}`.
   `SpaWsRegistry` translates each `SpaEvent` into role-filtered frames. **`now`
   pushes reuse the existing `DashboardNowRoutes` builder** (one implementation of
   the NOW snapshot — SSOT), recomputed on change rather than per-poll.

### 5.3 The throughput aggregator — derive the rate from the #1023 usage stream

Live throughput is the one element needing a genuinely new server path, and it
**depends on [#1023](https://github.com/wifihaven/wifihaven/issues/1023)** — the
router→API leg that pushes usage as the agent has it, rather than the ~60 s REST
batch. Design:

- **Source.** The router already accounts directional bytes per `(mac, host)` —
  `UsageRecord{bytesIn,bytesOut}` ([`Models.scala`](../../shared/src/Models.scala)).
  The ~60 s `usage` batch is too coarse for a live gauge. **Recommend a dedicated
  lightweight `throughput` sample on the router→API ws** (additive to #1023):
  per-`mac` bytes-since-last-sample, every ~2 s, sourced from the conntrack byte
  counters the agent already reads (`conntrack.lua`). This separates concerns —
  the `usage` records stay low-frequency/high-fidelity (per-host, period-accurate,
  idempotent for rollups); the `throughput` sample is high-frequency/low-fidelity
  (per-mac totals, display-only, lossy-OK). Conflating them would make `usage` too
  chatty or throughput too coarse. (Filed as a #1023 sub-issue, §9 **S0**.)
- **Aggregate.** The API maps `mac → profile` (it already does) and maintains a
  rolling overall + per-profile B/s (last-sample rate or short EWMA). This is the
  **one** place the rate is computed (SSOT) — every tab consumes the same tick.
- **Push.** Emit a `throughput` tick (§1.3) to SPA connections on a fixed cadence
  (~2 s), latest-wins (§6.3). If #1023's realtime usage isn't available yet, the
  aggregator simply has no input and emits nothing (the gauge shows "—") — so the
  SPA endpoint + the rest of the catalog ship independently of S0.

> **Single-process fan-out**, like the router path — registries, hubs, and the
> aggregator are in-memory, single-instance (correct for the one-API-process
> household model; cross-instance pub/sub is out of scope, same as
> [`websocket-transport.md` §6.1](websocket-transport.md)).

---

## 6. Reliability

### 6.1 Reconnect / backoff (browser)

Exponential backoff + jitter (1 s → 2 s → 4 s → … cap 30 s), reset on `ready`
(not merely socket `open`). Reconnect *is* the throttle; no per-frame retry. Each
reconnect mints a **fresh ticket** (§4.2; single-use, so a reconnect can't replay
the old one). On reconnect the client refetches the class-(2) queries once
(`now`/`blocked`) so a change missed while disconnected converges; the throughput
gauge resumes on the next tick. On `4401 token-expired` it stops reconnecting and
hands off to polling + `/login` (§4.3).

### 6.2 Heartbeat / liveness → the real "live / reconnecting" indicator

The payoff the silent poll can't give (§0.1).

- **Heartbeat.** App-level `{op:"ping"}`/`{op:"pong"}` ~30 s (browsers can't send
  WS control pings, §0.2.2); the server also control-pings and the browser
  auto-pongs, surfacing drops via `onclose`. A missed app-pong for `2×interval` →
  client treats the socket as dead and reconnects; server-side a missed pong
  closes + deregisters.
- **The indicator.** `useWsLive()` exposes `'live' | 'reconnecting' | 'offline'`
  from the socket state machine. It backs a small "Live / Reconnecting…" badge on
  the dashboard, gates the polling fallback (§3.4), and feeds
  [`ApiUnreachableBanner`](../../web/src/components/ApiUnreachableBanner.tsx):
  - socket reconnecting **and** REST polls failing → the existing red banner.
  - socket reconnecting **but** polls succeeding → a softer "Reconnecting live
    updates…" state — *degraded, not down*, a distinction the current banner
    can't draw.

### 6.3 Backpressure

- **`throughput`** — latest-wins: a slow client's mailbox keeps only the newest
  tick (older rates are worthless). Never queues.
- **`now`** — coalesce to the latest snapshot (same: only the freshest matters).
- **`blocked`** — small append frames; a bounded per-channel mailbox, drop-oldest
  past the cap (the feed is "recent" anyway), metered. A persistently-wedged
  channel is closed and left to reconnect.
- **`stale`** — idempotent; coalesce by topic (N `stale{X}` → one).
- **Inbound (SPA→server)** — tiny control frames only; no concern.

### 6.4 Multi-tab — socket per tab (v1); SharedWorker deferred

**One socket per tab for v1.** Each tab has its own `QueryClient`, so a per-tab
socket maps cleanly to "patch *this* tab's cache." A SharedWorker (one socket for
N tabs + broadcast) is more machinery (worker lifecycle, `BroadcastChannel`,
per-tab role routing) for a tiny workload — a household has a handful of admin
tabs, and per-tab cost is one idle socket + a 30 s heartbeat + coalesced
latest-wins ticks. **SharedWorker is the documented future optimization** (§9,
**S8**), recorded with its tradeoff, not a TODO.

### 6.5 Failure independence

A dead socket never breaks the app: class (2)/(3) degrade to today's polling
(§3.4); class (1) throughput shows "—". No enforcement or correctness depends on
the socket — it is a latency/UX layer over a fully-live REST baseline.

---

## 7. Metrics

Via `AppMetrics`/`MetricGuard` ([`Metrics.scala`](../../api/src/metrics/Metrics.scala)),
**bounded labels only — never per-user/session/mac/profile**
([`docs/process/instrumentation.md`](../process/instrumentation.md)). Mirrors the
router-ws families ([`websocket-transport.md` §7](websocket-transport.md)).

| Metric | Type | Labels (bounded) | Meaning |
|---|---|---|---|
| `spa_ws_connections_active` | gauge | `role` (`admin`\|`adult`\|`child`) | open SPA channels, refreshed on register/deregister (ages out on disconnect) |
| `spa_ws_frames_total` | counter | `op` (enum §1.2), `direction` (`in`\|`out`), `result` (`ok`\|`reject`\|`unknown_op`) | frame throughput + unknown-op tripwire |
| `spa_ws_auth_total` | counter | `result` (`ticket_ok`\|`ticket_invalid`\|`ticket_expired`\|`ticket_reused`\|`jwt_expired`) | ticket handshake + mid-connection expiry (§4) |
| `spa_ws_push_total` | counter | `op` (`throughput`\|`now`\|`blocked`\|`stale`), `result` (`ok`\|`coalesced`\|`dropped`\|`channel_closed`) | per-class fan-out health + backpressure (§6.3) |

`role`/`op`/`direction`/`result` are small fixed enums — the same
cardinality-firewall discipline `Metrics.scala` enforces (an attacker-supplied
`op` collapses to the literal `unknown` for the label, real value to the log
only). **No per-entity label.**

> **Registration (implementer note):** each new `spa_ws_*` series needs its
> `(name -> allowed keys)` entry in `MetricGuard.Allowed`
> ([`Metrics.scala`](../../api/src/metrics/Metrics.scala)) or the emit is rejected
> — exactly as the `router_ws_*` families were registered.

### 7.1 Grafana panel

Add **`spa-ws.json`** under [`deploy/grafana/dashboards/`](../../deploy/grafana/dashboards/)
(sibling to `router-ws-transport.json`), authored against the series above —
grepped from `api/src`, not a catalog
([`instrumentation.md#metrics-need-a-dashboard`](../process/instrumentation.md)).
Panels: connections-active `by (role)`; frame throughput `by (op,direction)` + an
`unknown_op` rate stat; auth outcomes `by (result)` (alert-worthy on
`ticket_reused`/`ticket_invalid`); push health `by (op,result)` (rising
`coalesced`/`dropped`/`channel_closed` ⇒ slow clients). Each phase ships its
panels in the **same PR**; a *new* dashboard must also join the `local.dashboards`
list in [`infra/grafana/main.tf`](../../infra/grafana/main.tf) (what the
`grafana-terraform` gate provisions from).

---

## 8. Deployment — Cloudflare-Pages-vs-Render hosting split

The SPA hosting split ([`AGENTS.md`](../../AGENTS.md) "SPA hosting split") shapes
where the ws endpoint lives:

- **Self-hosted (JVM-bundled SPA).** Served by the API at the same origin
  (`VITE_API_BASE_URL` empty). `wss://<same-origin>/api/ws` is same-origin — no
  CORS, no cross-host concern. The common case; upgrades cleanly.
- **Cloud (SPA on Cloudflare Pages, API on Render).** SPA static assets on
  `app.wifihaven.net`; the API — and the ws endpoint — on `api.wifihaven.net`:
  1. **The ws goes browser → Render directly**, *not* through Cloudflare Pages
     (Pages serves static assets, doesn't proxy `/api/*`). So CF Pages config is
     irrelevant to the socket — purely a Render concern.
  2. **Render terminates websockets** — confirmed for the router transport
     ([`websocket-transport.md` §3.3](websocket-transport.md)); the SPA endpoint
     inherits the same idle-timeout/heartbeat pinning (§6.2 — the 30 s heartbeat
     stays under Render's idle timeout).
  3. **Cross-origin upgrade → an `Origin` allowlist.** Unlike the router (no
     `Origin`), the browser sends `Origin` on the upgrade. The server checks it
     against an allowlist (`app.wifihaven.net`, `staging.*`, `localhost` for dev)
     and rejects a mismatch — a CSWSH defense the same-origin self-hosted case
     gets free. The ticket (§4.2) is the primary credential; the Origin check is
     cheap defense-in-depth and the one server-side ws config that differs by
     hosting mode.
  4. **The SPA derives the ws host from `VITE_API_BASE_URL`** (§2.1) — the same
     env var that already points the REST client per environment, so no new
     deployment config.

---

## 9. Phased, independently-shippable rollout

Each phase **ships independently and back-compat**: polling stays live throughout
(§3.4), the ws path is additive until the final per-view retirement, **no flag
day**. The pilot is the **realtime dashboard surface** (the actual goal), not an
arbitrary view. Sub-issues to file against this doc (+ the #1023 dependency for
realtime throughput):

| # | Sub-issue | Independently shippable? | Back-compat |
|---|---|---|---|
| **S0** | **(in #1023) router→API lightweight `throughput` sample** — additive per-mac bytes-since-last-sample frame every ~2 s from the conntrack counters (§5.3). The realtime source for live bandwidth. | yes (additive frame; usage path unchanged) | additive |
| **S1** | **API `/api/ws` endpoint + `{op,payload}` demux + `SpaWsRegistry`** — server skeleton, envelope/demux (`hello`→`ready`, `ping`/`pong`, unknown-op ignore+meter), per-connection registry, server metrics §7 + `spa-ws.json`. Reuses `RouterWsRoutes` patterns. REST untouched. | yes (test ws client) | additive — new route |
| **S2** | **Ticket handshake + auth** — `POST /api/ws/ticket` (reuses `requireAuth`), single-use short-TTL store, consume-at-upgrade, `Origin` allowlist (§8), `jwtExp` close (§4.3), `reauth` seam, auth metrics. | yes | additive |
| **S3** | **Change sources** — widen `PolicySnapshotPublisher` to a hub; add `SpaEventHub` fed by existing write sites; translate to role-filtered `stale`/`now`/`blocked` frames (§5.2). | yes (behavior-preserving for the router subscriber; testable via a probe client) | behavior-preserving |
| **S4** | **Throughput aggregator + `throughput` push** — consume S0's samples, derive overall + per-profile B/s, push the tick (§5.3/§1.3). Emits nothing (gauge "—") until S0 lands. | yes | additive |
| **S5** | **SPA ws client + realtime dashboard pilot** — `useWsThroughput()` (overall + per-profile gauges, #747), `now`/`blocked` cache-patching (§3.1/§3.2), `useWsLive()` indicator (§6.2), ticket-mint-then-connect, backoff (§6.1), polling-as-paused-fallback for the dashboard live sections. **This lights up the redesigned NOW + throughput + Most-Recently-Blocked** ([`dashboard-redesign.md` §8](dashboard-redesign.md), unblocks #1834/#1835). | yes (dashboard only) | additive — other views poll |
| **S6a** | **Live time-usage push** — `timeStatus` (per-profile used/remaining) + `appUsage` (per-app minutes) class-(2) pushes (§1.2) wired to usage-credit / #1849-ticker / extension-grant (§5.2); SPA patches the time-status + per-app caches (§3.2) and pauses the adaptive ladder when `wsLive`. The `appUsage` push targets only the **live (today) window** key — past windows are immutable. Lights up the **live screen-time surface** (per-profile + per-app, /profiles). | yes | additive |
| **S6b** | **Broaden class-(3) `stale` to alerts / profiles / devices / schedules** — add topic→invalidator entries (§3.3); pause their intervals when `wsLive`. | yes (per-view) | additive |
| **S7** | **Retire redundant `refetchInterval`s** — per migrated view, after its push path is proven; keep the `wsLive ? false : …` fallback only where a view still has no push. The only subtractive step, per-view, operator-gated. | yes, last | per-view subtractive |
| **S8** | **(optional) SharedWorker single-socket multi-tab** — only if a deployment shows many tabs/browser (§6.4). | yes, later | additive |

**Critical path to the operator-visible win:** S1 → S2 → S5 (dashboard NOW +
blocked live), with **S0 → S4 → S5** adding live throughput. **Parallel:** S3 can
land alongside S2. **S7 is last and per-view-gated** — each cutover off polling is
deliberate once that view's push path is proven, never armed automatically
([`pr-review-checklist.md#monitor-to-merged`](../pr-review-checklist.md)).

---

## 10. Open questions / risks

1. **Throughput cadence vs. cost.** ~2 s tick is the proposed default; the exact
   cadence is pinned with S0 against the router's conntrack-read cost and Render's
   frame budget. Faster than ~1 s buys little for a human-watched gauge.
2. **#1023 dependency for live bandwidth.** Live throughput (S4/S5's gauge) is the
   one element gated on #1023's realtime usage source (S0). Everything else (NOW,
   blocked, the endpoint, auth) ships without it; the gauge shows "—" until S0
   lands. Sequence S0 with the #1023 epic, not as a blocker for S1–S3.
3. **Ticket store durability.** v1's single-process in-memory ticket store (§4.2)
   is correct for one API instance; multi-instance would need sticky routing or a
   shared store — same single-process assumption as the registries/aggregator
   (§5), documented limit, not a TODO.
4. **Thick-push filtering (class 2).** Per-role filtering of a pushed `now` body is
   the one place class-(1)/(3)'s contentless safety is absent; it reuses the
   `DashboardNowRoutes` filter (§3.2/§5.2) so there is one filter implementation,
   bounding the risk.
5. **Observability parity.** Per-frame structured logging (`op`/`role`/`result` on
   the MDC, mirroring `RouterWsRoutes`' `LogContext`) so a transport fault is as
   debuggable as REST. Built into S1.
