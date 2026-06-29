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
- **"Recently Blocked"** — the live "what just got dropped" feed
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
   This is specific to the `WebSocket` **constructor API**: `fetch`/`XMLHttpRequest`
   *can* set arbitrary headers, which is exactly how REST auth works today
   (`client.ts:41` sets `Authorization: Bearer <jwt>` on every `fetch`). The
   `WebSocket(url, protocols)` constructor simply exposes no header argument — so
   the JWT that rides a header on REST has to ride something else on the ws
   upgrade. This is *the* reason the SPA auth (§4) differs from the router's
   bearer-on-upgrade ([`websocket-transport.md` §4.1](websocket-transport.md)) —
   the router is not a browser and sets the header directly.
2. **It cannot send ping/pong control frames** from JS — the heartbeat (§6.2) is
   an app-level text frame.
3. **It is per-tab and dies on background/sleep** — reconnect, backoff, and
   multi-tab behavior (§6) are SPA concerns the router never had.

The server side is the easy half — `zio-http`'s `Handler.webSocket` already backs
`GET /api/router/ws` ([`RouterWsRoutes.scala`](../../api/src/routes/RouterWsRoutes.scala)).
We **reuse the router transport's patterns** (envelope, demux, registry shape,
metric discipline) and **fork where the two genuinely differ** (auth, key,
payload vocabulary) — "share patterns, not code," same call #1023 makes.

### 0.3 Read-model discipline — ZERO new read-model shapes; every topic reuses a REST body + its filters

Per the single-source-of-truth discipline
([`AGENTS.md#single-source-of-truth`](../../AGENTS.md), wire-shape carve-out):
**every server→SPA payload is an existing REST body, verbatim, and every
subscription's parameters are that endpoint's existing query params.** The socket
is a *transport for change over the existing read models*, not a new data model:

- **Bandwidth is not new data** — `traffic_reports` already carries directional
  bytes per `(mac, host, period)`, surfaced by `GET /api/usage/traffic` as
  `TrafficUsageResponse` ([`shared/src/Models.scala`](../../shared/src/Models.scala):
  `totalBytesIn`/`totalBytesOut` per group). Overall / per-profile / per-device
  bandwidth is just that endpoint with `groupBy ∈ {∅, profile, device}`; the
  averaging window is its `bucket`. So the **`trafficUsage` topic streams
  `TrafficUsageResponse` verbatim** — no `ThroughputTick`, no new shape. (An
  earlier draft invented a `ThroughputTick`; that was wrong — the read model
  already exists.)
- **NOW / blocked / connection-events / time-usage** likewise reuse
  `DashboardNow`, the `/api/logs` row (`QueryLog`), and the `/api/time/status` /
  `/api/profiles/{id}/usage-by-app` bodies verbatim.
- **The win:** because each topic *is* an existing endpoint-plus-filters, the same
  subscription powers the dashboard **and** the page it came from — `trafficUsage`
  drives the dashboard bandwidth gauge *and* live-updates the Traffic Usage page;
  `connectionEvents` drives the dashboard "Recently Blocked" *and* the Connection
  Events page. One stream, many consumers, one schema authority (the `GET`).

---

## 1. What flows over the socket — the message catalog

This is the section the design turns on. Every server→SPA payload maps to a
dashboard live element ([`dashboard-redesign.md` §8.2](dashboard-redesign.md) is
the input contract); every item is classified by **why** it is on the socket.

### 1.1 Three data classes

| Class | What | Examples | How it rides the socket |
|---|---|---|---|
| **(1) Live-edge stream over a paged read model** | an existing read endpoint whose **newest** data changes fast; history stays on the `GET` | **live bandwidth** (`trafficUsage` — overall/per-profile/per-device bytes), **live connection events** (`connectionEvents`) | the page/gauge loads its window via the existing `GET` (incl. cursor paging); the socket pushes **only the live edge** (the current bucket advancing / new head rows), which the client merges into the cached query result (§3) |
| **(2) Pushed live snapshot/append** | a real REST resource that changes fast enough to push instead of poll | **NOW** (`DashboardNow`), **live time-usage** (per-profile used/remaining + per-app minutes) | server pushes the (changed) body / the new row; client patches the existing React Query cache for that key (§3) |
| **(3) Occasionally-changing resource** | a cacheable resource that changes rarely | profiles, devices, schedules, blocklists, 24 h rollup panels | **stays request/response.** At most a `stale` nudge on a relevant mutation; many need nothing (their mutation already invalidates locally) |

(Classes 1 and 2 are both "push the data, patch the cache." Class 1 is a
*live-edge delta* over a paged/filtered query the client still loads from the
`GET` — the socket only keeps the newest slice fresh, it does **not** replace the
historical query. Class 2 is a singular resource pushed whole. The split that
matters is **vs. class 3**, which stays on queries.) The websocket is for **(1) and (2)**; **(3) stays on
queries** — the correction to the "invalidate everything" framing. The dashboard's
"Blocking activity (24 h)" panel and the "Events (1h)/Blocked (1h)" KPIs are class
(3): rollup-backed request/response, *not* streamed
([`dashboard-redesign.md` §7.5/§8.2](dashboard-redesign.md)).

### 1.2 Server→SPA message catalog

Every server→SPA push is **gated on a subscription** (§1.4) — the client receives
only the topics it asked for, with the parameters it asked for. The `Params`
column is what the client sends in `subscribe`.

| `op` | Class | Params (subscription) = the endpoint's query params | Payload (existing body) | Drives | Source |
|---|---|---|---|---|---|
| `trafficUsage` | (1) | `{ groupBy ∈ {∅,profile,device,…}, bucket (window), macs?, profileIds? }` (= `GET /api/usage/traffic` params) | `TrafficUsageResponse` containing **only the current/most-recent bucket** for those params (live edge) | dashboard **overall + per-profile bandwidth** gauges (#747) **and** the live tail of the **Traffic Usage page** (history via the `GET`) | recompute the current bucket on usage ingest (§5.3) |
| `connectionEvents` | (1) | `{ blocked?, macs?, profileIds?, domain? }` (= `GET /api/logs` params) | **new head** `QueryLog` rows (append) | dashboard **Recently Blocked** (`blocked:true`) **and** the live head of the **Connection Events page** (history/paging via the `GET`) | connection-events ingest (§5.2) |
| `now` | (2) | — | `DashboardNow` | NOW active cards; derived "Online/Blocked now" KPIs | recompute on change (§5.2), reusing the `/api/dashboard/now` builder |
| `timeStatus` | (2) | — (all authorized profiles in v1; `profileId` filter is an additive future param) | `ProfileTimeStatus[]` (`/api/time/status`) | per-profile **used/remaining** bars; "over limit" KPI | usage credit + #1849 ticker + extension grant (§5.2), via `TimeStatusService.dayStateAllLive` |
| `appUsage` | (2) | `profileId` (the expanded card) | per-app minutes (`/api/profiles/{id}/usage-by-app`) | per-app **minutes-used** rows (live) | same triggers, via `Presence.appSecondsForProfile` |
| `stale` | (3) | — | `{ topic, scope? }` (bounded `topic` enum) | invalidate a class-(3) query | the relevant write site (§5.2) |
| `ready` | — | — | `{ role, serverTime }` | flips the live indicator to "live" | upgrade (§4) |
| `ping`/`pong` | — | — | `{}` | heartbeat (§6.2) | — |

SPA→server: `hello` (`{clientVersion}`), **`subscribe` (`{topic, params?}`)**,
**`unsubscribe` (`{topic}`)**, `reauth` (`{jwt}` on the open socket, §4.3),
`ping`/`pong`.
**Unknown `op` → ignore + meter**, both directions (forward-compat, mirrors
[`RouterWsRoutes.dispatch`](../../api/src/routes/RouterWsRoutes.scala)).

### 1.3 No new shapes — `trafficUsage` is the existing `TrafficUsageResponse`

There is **no `ThroughputTick` and no new read-model**. The "live bandwidth"
display the dashboard wants is `GET /api/usage/traffic` streamed:

- **`groupBy` selects the breakdown** — empty = overall household; `["profile"]` =
  per-profile; `["device"]` = per-device. `TrafficUsageAggregateRow` already
  carries `totalBytesIn`/`totalBytesOut`/`totalSeconds` per group
  ([`Models.scala`](../../shared/src/Models.scala)). The dashboard subscribes
  overall + per-profile; the Traffic Usage page subscribes with whatever groupBy
  the user has selected — **same topic, same body, different params**.
- **`bucket` is the window** — the choosable averaging window is the existing
  `TrafficUsageBucket` enum (`raw | 1m | 10m | 1h | 12h | 1d | 1w`,
  [`web/src/types/api.ts`](../../web/src/types/api.ts)). The client converts
  bytes-over-bucket → a B/s rate for the gauge; the server ships the same row the
  page already renders.
- **Bandwidth is a derived view, not a counter we invent** — the client divides
  `totalBytes / bucketSeconds` (the page already does this kind of math), so the
  server stays the single source of the *bytes* and there's no second "rate"
  serializer.
- **History is the `GET`; the socket is the live edge.** The page (and the
  dashboard gauge) loads its window — and pages older data — via
  `GET /api/usage/traffic` exactly as today. The `trafficUsage` push carries
  **only the current/most-recent bucket** for the subscribed params (a
  `TrafficUsageResponse` whose window is just that bucket); the client **merges**
  it into the cached series — replace the head bucket while it's still
  accumulating, append a new head when the bucket rolls over. The socket never
  re-streams historical buckets, so a 24 h × 1 h chart isn't re-sent every minute —
  just its newest bar advances.

> **Granularity (decided with the operator, §10 Q1).** Aggregated floor is **`1m`**
> (the `traffic_reports` minimum), so `{1m, 10m, 1h, …}` come free. For
> **fully-realtime**, the existing **`raw` bucket** streams the live edge **at
> whatever cadence the router sends usage data** (today ~60 s batches; sub-minute
> and toward real-time as #1023 streams usage as the agent has it) — realtime-ness
> is bounded by the usage-send rate, with **no bespoke high-frequency sample and no
> router change**. (Note: a `raw` subscription **WITH a `groupBy`** (e.g. the gauge's
> `groupBy:profile`) is **aggregated per that `groupBy`** at the ingest-period
> granularity — one overall/per-profile point per arriving usage period. A `raw`
> subscription **WITHOUT a `groupBy`** — the Traffic Usage page's per-host inspector,
> its default view — instead pushes the **new per-host `rawRows`** for the ingest
> period, which the page **prepends** like the `connectionEvents` feed (#2048). This
> split mirrors the `GET /api/usage/traffic` exactly: `raw`+groupBy → `aggregateRows`,
> `raw`+no-groupBy → `rawRows`.) (`5m`/`10m` display buckets are trivially addable later.)

`connectionEvents` likewise reuses the `/api/logs` `QueryLog` row shape and its
filter params — it is the `blocked`-feed generalized so the **Connection Events
page** can stream too (subscribe with the page's current filters), and the
dashboard "Recently Blocked" is just `connectionEvents{blocked:true}`. **Zero
additions to `web/src/types/api.ts`.**

### 1.4 Subscriptions — first-class, so a tab gets only what it shows

**Subscriptions are a v1 requirement, not a deferred seam.** A connection receives
a topic **only if it has subscribed to it** (and is authorized, §4.4). This is how
"only get the data we need over the ws" is achieved: the dashboard subscribes
`trafficUsage{groupBy:profile}` + `now` + `connectionEvents{blocked:true}` +
`timeStatus`; the **Traffic Usage page** subscribes `trafficUsage` with *its*
current groupBy/bucket/filters; the **Connection Events page** subscribes
`connectionEvents` with its filters; a `/profiles` tab subscribes `timeStatus` +
`appUsage{profileId}`. Nothing is pushed to a connection that didn't ask.

**Protocol.**

```jsonc
// after `ready`, the client declares what it wants — and updates it as the UI changes
{ "op": "subscribe",   "payload": { "topic": "trafficUsage", "params": { "groupBy": ["profile"], "bucket": "1m" } } }
{ "op": "subscribe",   "payload": { "topic": "appUsage",     "params": { "profileId": 3 } } }
{ "op": "unsubscribe", "payload": { "topic": "trafficUsage" } }
```

- **Lifecycle = the mounted UI.** A hook subscribes on mount, unsubscribes on
  unmount (e.g. the bandwidth gauge subscribes `trafficUsage{groupBy,bucket}` and
  **re-subscribes with the new `bucket` when the user picks the window from the
  selector**, or a new `groupBy` when the Traffic page changes breakdown — the
  server replaces that connection's prior `trafficUsage` subscription). Expanding a
  profile card subscribes `appUsage{profileId}`; collapsing it unsubscribes. So
  per-connection traffic tracks exactly what's on screen.
- **Server holds subscriptions per-connection** in the channel's registry state
  (§5.1) — `{topic → params}`. Fan-out checks the set (and the params) before
  sending; the `trafficUsage` aggregator (§5.3) maintains/pushes a given
  `(groupBy, bucket, filter)` result only while ≥1 connection subscribes it, at a
  bucket-appropriate cadence.
- **Authorization still gates** on top of subscription: subscribing a topic the
  role can't see is rejected (`ack` reject); per-profile rows are filtered to the
  profiles the role may view (§4.4). Subscription narrows; authz constrains.
- **`ack` per subscribe** (`{op:"ack", topic, status:"ok"|"reject", reason?}`) so
  the client knows the subscription took (and isn't silently waiting on a topic
  the server refused). Note this SPA `ack` is keyed by **`topic`**, not by `seq`
  like the router path's data-frame `ack` ([`RouterWsRoutes`](../../api/src/routes/RouterWsRoutes.scala))
  — same `op` name, different (separate-endpoint) payload.
- **Reconnect re-subscribes.** Subscriptions are connection-scoped and **not**
  durable across a reconnect; on reconnect the client replays its current
  subscription set (§6.1) — the server starts each connection subscribed to
  nothing.

**Defaults.** There is no "subscribe to everything" default — the explicit
opt-in *is* the data-minimization. A view with no subscriptions receives only
`ready` + heartbeats. (`stale` class-(3) topics may be bundled into a single cheap
`subscribe{topic:"stale"}` since they are contentless nudges, or subscribed
individually; either is fine — they are tiny.)

The server replies `ready` right after upgrade; the client flips its indicator to
"live" only after `ready` (a socket that upgrades but never `ready`s must not read
as live), then sends its `subscribe` frames.

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
// auth rides a tightly-scoped cookie set just before connect (§4.2), not the URL
setWsAuthCookie(getToken())
const url = `${wsBase}/api/ws`
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

## 3. Client integration — patch the cache for the live surface, invalidate for the rest

The central client-side decision, made **per data class** (§1.1): classes (1) and
(2) push the data and **patch the React Query cache** (no refetch RTT on the live
surface); class (3) invalidates.

### 3.1 Classes (1) + (2) — push carries the (existing) body, patch the matching key

Because every payload is an existing REST body (§0.3), a push patches the **same
React Query key the matching `GET` populates** — so the dashboard and the source
page share one cache entry, and a later refetch (reconnect §6.1, or paused-poll
fallback §3.3) reconciles against the `GET` authority. (`trafficKey`/`logsKey`
below are illustrative — they are the Traffic Usage / Connection Events pages'
existing `useQuery` keys, to be lifted into the shared `qk` factory so the push and
the page key are produced in one place.)

```ts
// trafficUsage → MERGE the live-edge bucket into the GET-loaded series (history stays put);
// join on windowStart (TrafficUsageAggregateRow.windowStart) — replace the row with the same
// windowStart while it accumulates, prepend when a new windowStart rolls over. Never refetch history.
queryClient.setQueryData(trafficKey(params), prev => mergeHeadBucket(prev, liveBucket)) // by windowStart
// connectionEvents → prepend new head rows (bounded, dedup by id); cursor-paged history untouched
queryClient.setQueryData(logsKey(filter), prev => prependHead(prev, rows))
// now → replace the dashboard-now cache (singular resource, pushed whole)
queryClient.setQueryData(qk.dashboardNow(), body)
// timeStatus → replace the per-profile used/remaining cache
queryClient.setQueryData(qk.timeStatusToday(), rows)
// on `appUsage` → patch only the LIVE (today) per-app cache entry; past windows
// are immutable, so the push targets today's [from,to] key, not every cached window
queryClient.setQueryData(qk.profileUsageByApp(profileId, todayFrom, todayTo), appRows)
```

Justification this is safe despite (3)'s SSOT argument: the pushed body **is** the
exact `GET` body (§0.3), written through the **same** query key, so a later
refetch (on reconnect, §6.1, or the paused-poll fallback, §3.3) reconciles against
the authority. The server applies the *same* per-role visibility filter the `GET`
uses before fan-out (§4.4 / §5.2) — for the household's small fan-out this is one
filtered build, not a per-recipient risk surface. The derived KPIs ("Online now /
Blocked now") are computed client-side off the pushed `DashboardNow`, so they
update with the same push — no separate stream.

### 3.2 Class (3) everything else — `stale` → invalidate (or nothing)

For occasionally-changing resources, a `stale{topic}` frame triggers
`queryClient.invalidateQueries` on the mapped key, reusing the existing `qk`
factory / `useInvalidators` ([`queries.ts`](../../web/src/api/queries.ts)). The
topic→key map is the one place the mapping lives. `invalidateQueries` only
refetches **mounted** queries, so a `stale{time-status}` while that view is closed
costs nothing. Many class-(3) changes already invalidate locally via a mutation's
`onSuccess` and need no socket frame at all — the `stale` op is for the case where
*another* operator/tab made the change. **No thick push for class (3):** it keeps
the `GET` as the sole producer and gets per-recipient authz for free.

### 3.3 Polling stays the paused fallback

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
We **authorize the upgrade request itself, pre-upgrade** (exactly like the router
path authorizes its bearer before the `101`). The only wrinkle is *how the
credential rides that request*: the browser `WebSocket` API can't set an
`Authorization` header (§0.2.1), so the JWT must travel in either the URL or a
cookie — those are the only two things a browser controls on a ws upgrade.

### 4.1 The options

| Mechanism | Verdict |
|---|---|
| **`Authorization` header** | **Impossible *for the `WebSocket` API*** — unlike `fetch` (which carries the bearer on every REST call, `client.ts:41`), the `WebSocket` constructor exposes no way to set headers (§0.2.1). This is the whole reason the others exist. |
| **JWT in the query string** | **Rejected.** It *does* authorize pre-upgrade, but the full JWT lands in access/proxy logs, browser history, and `Referer` — valid for its multi-hour life if leaked. |
| **Subprotocol** (`Sec-WebSocket-Protocol`) | **Rejected.** Abuses a negotiation header to smuggle a secret; same log-exposure problem. |
| **Short-lived single-use ticket** (query string) | Viable — keeps the JWT out of the URL via a 30 s one-shot token, but needs an extra round-trip + a server-side ticket store. **Not chosen** (see below). |
| **Cookie carrying the JWT** | **Chosen.** ✓ Rides the upgrade automatically → direct pre-upgrade authorization, no extra round-trip, no ticket store, JWT never in a URL. |

> **Why a cookie works for both deployments (the call I initially got wrong).**
> The concern with cookies is third-party-cookie blocking. It does **not** apply
> here: `app.wifihaven.net` and `api.wifihaven.net` are subdomains of the **same
> registrable domain** (`wifihaven.net`), so a cookie scoped to `wifihaven.net` is
> **first-party / same-site** for both the cloud split *and* the self-hosted
> same-origin case. No third-party-cookie problem, so no need for the ticket's
> URL-indirection.

### 4.2 Decision: a tightly-scoped JWT cookie, set just before connect

```
SPA                                                    API
 │  document.cookie = "wh_ws=<jwt>; Domain=wifihaven.net;                 │
 │                     Path=/api/ws; SameSite=Strict; Secure"             │
 │  new WebSocket("wss://api.wifihaven.net/api/ws")  (cookie rides it)    │
 │ ─────────────────────────────────────────────────────────────────────▶ │  authenticate(upgradeReq):
 │                                                                         │  read wh_ws cookie → AuthService.verify
 │  101 Switching Protocols  → register channel (§5)                       │  + Origin allowlist (§8);
 │ ◀───────────────────────────────────────────────────────────────────── │  bad/missing/expired → 401, no 101
 │  (SPA clears the cookie immediately after the socket opens)            │
```

- **The credential is the existing JWT, validated by the existing `AuthService.verify`**
  — one JWT-validation implementation; the ws path is not a second auth surface
  (`AGENTS.md#single-source-of-truth`). A bad/missing/expired cookie → reject the
  upgrade with **401, no 101**, identical to the router path and to a REST 401.
- **Cookie scoping is the security boundary, not secrecy:**
  - `Path=/api/ws` — the cookie is sent **only** on the ws upgrade, not on every
    REST call (REST keeps using the `localStorage` bearer, unchanged — no broad
    cookie, so **no CSRF surface added to the REST API**).
  - `SameSite=Strict` — the browser won't attach it to a cross-site-initiated
    request, which (with the `Origin` allowlist, §8) closes cross-site websocket
    hijacking (CSWSH).
  - `Secure` — HTTPS only. `http://localhost` is a browser "secure context" so
    `Secure` cookies are accepted there for dev; the self-hosted deploy is HTTPS.
  - `Max-Age` short (e.g. 60 s) — the cookie is meant to live only across the
    upgrade. The SPA clears it right after the socket opens, but the short
    `Max-Age` is the belt-and-suspenders bound: if the tab is killed between
    set and open, the lingering cookie self-expires in seconds rather than
    persisting.
  - `Domain=wifihaven.net` in cloud so it reaches `api.` from `app.`; omitted
    (host-only) in the self-hosted same-origin case.
- **No new persistent exposure.** The JWT already lives in `localStorage`
  (XSS-readable); a JS-set, path-scoped cookie set transiently around connect is
  no worse, and the SPA clears it right after the socket opens so it isn't sitting
  around. (`HttpOnly` isn't usable here because JS sets it; that's acceptable
  given the existing `localStorage` exposure.)
- **No ticket, no `/api/ws/ticket` endpoint, no ticket store, no extra
  round-trip** — the simplification the cookie buys over the ticket.

### 4.3 Token expiry mid-connection + re-auth

The cookie authorizes only the *upgrade*; the connection's authz deadline is the
JWT's `exp`, captured at upgrade.

- **Expiry while connected.** The server tracks `jwtExp` per channel; on the
  heartbeat tick where `now ≥ jwtExp` it closes with `4401 token-expired`
  (metered `jwt_expired`). Stale-authz carry-over is bounded by one heartbeat
  interval — same property the router path gives for revocation.
- **Re-auth.** The SPA has **no silent token refresh** today — an expired JWT
  means 401 → `localStorage` clear → `/login` ([`client.ts`](../../web/src/api/client.ts)).
  v1 mirrors this: on `4401` the SPA stops reconnecting, falls back to polling
  (§3.3); the next REST call 401s → the existing `/login` redirect; after
  re-login it re-sets the cookie and reconnects. The **`reauth` op** (§1.2) is the
  forward-compat seam: when silent refresh lands, the SPA can present the refreshed
  JWT on the open socket and the server advances `jwtExp` — no reconnect. v1 server
  may `ack`-reject `reauth`.

### 4.4 Role scoping per frame

The resolved `role` is captured on the channel at upgrade and is the fan-out authz
key: the server only pushes a topic to a connection whose role may see it
(`stale{...}` for an admin-only resource → admin connections only; per-profile
throughput → filtered to the profiles the role may view). For class-(2) thick
pushes the body is filtered per-role *before* send, exactly as the matching `GET`
filters (§3.1 / §5.2). For class-(3) `stale` signals the worst case of a fan-out
bug is a needless refetch the `GET`'s own `requireAuth` then scopes — defense in
depth.

---

## 5. Server side — registries, change sources, the `trafficUsage` aggregator

### 5.1 Decision: fork a parallel `SpaWsRegistry`; share the change sources

**Fork `SpaWsRegistry`; do not generalize
[`RouterWsRegistry`](../../api/src/routes/RouterWsRegistry.scala) to hold both.**
The two differ on the three axes a registry *is*:

| Axis | `RouterWsRegistry` (#1846) | `SpaWsRegistry` (this design) |
|---|---|---|
| **Key** | `RouterId` | per-connection id + `role` (a user may have N tabs, §6.4) |
| **Per-channel state** | just the channel | channel + **subscription set `{topic → params}`** (§1.4) + `jwtExp` |
| **Fan-out payload** | full `PolicySnapshot` | the §1.2 op vocabulary, role-filtered **and** subscription-gated |
| **Event vocabulary** | one event (policy changed) | several (new traffic-usage data, NOW change, new connection event, time-usage change, class-3 mutations) |

Generalizing one registry across two key types + two payload vocabularies couples
things that share only the *shape* "a `Ref` of channels with a fan-out method."
Reuse the **pattern**, not the instance — `SpaWsRegistry` is the same
`Ref[Map[K, Channel+subscriptions]]` shape with SPA-appropriate K, payloads, and
metrics. (Auth is stateless cookie-verified at upgrade, §4.2 — no ticket store to
hold.) **Fan-out is two gates: a topic is
sent to a channel only if the channel both *subscribed* to it (§1.4) and is
*authorized* for it (§4.4).** The subscription set is the data-minimization
mechanism; the registry is where it lives.

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
     and router-ws ingest call) → a `connectionEvents` push (the new row(s), to
     subscribers whose filter matches) + a `now` recompute trigger.
   - usage ingest → feeds the `trafficUsage` aggregator (§5.3); a `now` trigger; and a
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

### 5.3 The `trafficUsage` aggregator — re-run the existing query on new usage

Live bandwidth is **not** a new data path — it is the existing
`GET /api/usage/traffic` query, re-run when new usage lands and pushed to
matching subscribers. Design:

- **Source = the same `traffic_reports` the page reads — but only the live edge.**
  The page/gauge has already loaded its history via `GET /api/usage/traffic`
  (incl. paging). When usage ingest writes new `traffic_reports` rows (the shared
  ingest handler, fed by the REST poll or the #1023 router-ws), the aggregator
  recomputes **only the current/most-recent bucket** for each distinct
  `(groupBy, filter)` that has ≥1 subscriber (§1.4) and pushes that one bucket as a
  `TrafficUsageResponse`. It does **not** re-run the full historical window — the
  client merges the head bucket (§3.1). Still the **one** existing query
  implementation, scoped to the head — so the stream and the page's `GET` can't
  disagree (SSOT), and the per-push cost is one bucket, not the whole chart.
- **Cadence = bucket-appropriate**, coalesced. The current `1m` bucket is re-pushed
  at most ~once per usage-ingest cycle (~every minute, as it accumulates); coarser
  buckets advance less often. Latest-wins per `(groupBy,bucket,filter)` (§6.3) — a
  slow client gets the freshest head bucket, never a backlog. Recompute only for
  subscribed param-sets — no subscriber, no query.
- **Granularity floor = the data; `raw` = ingest-rate realtime (§10 Q1).** The
  aggregated floor is `1m` (`traffic_reports` minimum), giving `{1m, 10m, 1h, …}`
  for free. A **`raw`-bucket** subscription instead pushes the live edge **as each
  usage report lands** — so "fully-realtime" is just the usage-send cadence (today
  ~60 s; sub-minute and toward real-time as #1023 streams usage). **No bespoke
  conntrack sample and no router change** — realtime-ness rides the existing usage
  path. (`5m`/`10m` display buckets are additive re-aggregations later.)
- **Degrades cleanly.** With usage arriving only at the REST cadence the live edge
  simply advances that often; it gets faster automatically as #1023 streams usage.
  Nothing here is gated on #1023.

> **Single-process fan-out**, like the router path — registries, hubs, and the
> aggregator are in-memory, single-instance (correct for the one-API-process
> household model; cross-instance pub/sub is out of scope, same as
> [`websocket-transport.md` §6.1](websocket-transport.md)).

---

## 6. Reliability

### 6.1 Reconnect / backoff (browser)

Exponential backoff + jitter (1 s → 2 s → 4 s → … cap 30 s), reset on `ready`
(not merely socket `open`). Reconnect *is* the throttle; no per-frame retry. Each
reconnect **re-sets the auth cookie** (§4.2) from the current JWT before opening,
and **replays its current subscription set** (§1.4 — subscriptions
are connection-scoped, not durable; the server starts the new connection
subscribed to nothing). On reconnect the client also refetches its live queries
once (`now` / `timeStatus` / the subscribed `trafficUsage` + `connectionEvents`
keys) so a change missed while disconnected converges; the streams then resume on
their next push. On `4401 token-expired` it stops reconnecting and hands off to
polling + `/login` (§4.3).

### 6.2 Heartbeat / liveness → the real "live / reconnecting" indicator

The payoff the silent poll can't give (§0.1).

- **Heartbeat.** App-level `{op:"ping"}`/`{op:"pong"}` ~30 s (browsers can't send
  WS control pings, §0.2.2); the server also control-pings and the browser
  auto-pongs, surfacing drops via `onclose`. A missed app-pong for `2×interval` →
  client treats the socket as dead and reconnects; server-side a missed pong
  closes + deregisters.
- **The indicator.** `useWsLive()` exposes `'live' | 'reconnecting' | 'offline'`
  from the socket state machine. It backs a small "Live / Reconnecting…" badge on
  the dashboard, gates the polling fallback (§3.3), and feeds
  [`ApiUnreachableBanner`](../../web/src/components/ApiUnreachableBanner.tsx):
  - socket reconnecting **and** REST polls failing → the existing red banner.
  - socket reconnecting **but** polls succeeding → a softer "Reconnecting live
    updates…" state — *degraded, not down*, a distinction the current banner
    can't draw.

### 6.3 Backpressure

- **`trafficUsage` / `now` / `timeStatus`** — latest-wins per
  `(topic, params)`: a slow client's mailbox keeps only the newest result (older
  snapshots/rates are worthless). Never queues.
- **`connectionEvents`** — small append frames; a bounded per-channel mailbox,
  drop-oldest past the cap (the feed is "recent" anyway), metered. A
  persistently-wedged channel is closed and left to reconnect.
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
(§3.3); class (1) throughput shows "—". No enforcement or correctness depends on
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
| `spa_ws_frames_total` | counter | `op` (enum §1.2, incl. `subscribe`/`unsubscribe`), `direction` (`in`\|`out`), `result` (`ok`\|`reject`\|`unknown_op`) | frame throughput + unknown-op tripwire |
| `spa_ws_subscriptions_active` | gauge | `topic` (`trafficUsage`\|`now`\|`connectionEvents`\|`timeStatus`\|`appUsage`\|`stale`) | live subscriptions per topic — shows what the fleet is actually watching (and that `trafficUsage` query work is bounded to demand). **No `bucket`/`groupBy`/`profileId` label** — params (esp. profileId) are unbounded; keep them out of labels |
| `spa_ws_auth_total` | counter | `result` (`ok`\|`no_cookie`\|`invalid_jwt`\|`expired_jwt`\|`bad_origin`\|`jwt_expired_midconn`) | upgrade-auth outcomes (cookie verify + Origin check) + mid-connection expiry (§4) |
| `spa_ws_push_total` | counter | `op` (`trafficUsage`\|`now`\|`connectionEvents`\|`timeStatus`\|`appUsage`\|`stale`), `result` (`ok`\|`coalesced`\|`dropped`\|`channel_closed`) | per-topic fan-out health + backpressure (§6.3) |

`role`/`op`/`direction`/`result`/`topic` are small fixed enums — the same
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
`bad_origin`/`invalid_jwt` spikes — a CSWSH/forgery probe); push health `by (op,result)` (rising
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
  3. **Cross-origin upgrade → `Origin` allowlist + `SameSite=Strict` cookie.**
     Unlike the router (no `Origin`), the browser sends `Origin` on the upgrade.
     The server checks it against an allowlist (`app.wifihaven.net`, `staging.*`,
     `localhost` for dev) and rejects a mismatch. This pairs with the
     `SameSite=Strict` auth cookie (§4.2) as the CSWSH defense: `SameSite=Strict`
     stops the cookie riding a cross-site-initiated upgrade, and the `Origin` check
     is the server-side backstop. The cookie is scoped `Domain=wifihaven.net` so it
     reaches `api.` from `app.` (same registrable domain — first-party, §4.1); the
     self-hosted same-origin case uses a host-only cookie. The Origin allowlist is
     the one server-side ws config that differs by hosting mode.
  4. **The SPA derives the ws host from `VITE_API_BASE_URL`** (§2.1) — the same
     env var that already points the REST client per environment, so no new
     deployment config.

---

## 9. Phased, independently-shippable rollout

Each phase **ships independently and back-compat**: polling stays live throughout
(§3.3), the ws path is additive until the final per-view retirement, **no flag
day**. The pilot is the **realtime dashboard surface** (the actual goal), not an
arbitrary view. Sub-issues to file against this doc (+ the #1023 dependency for
realtime throughput):

| # | Sub-issue | Independently shippable? | Back-compat |
|---|---|---|---|
| **S1** | **API `/api/ws` endpoint + `{op,payload}` demux + `SpaWsRegistry` + subscription model** — server skeleton, envelope/demux (`hello`→`ready`, `subscribe`/`unsubscribe` with per-connection subscription state + `ack`, `ping`/`pong`, unknown-op ignore+meter), per-connection registry, server metrics §7 + `spa-ws.json`. Reuses `RouterWsRoutes` patterns. REST untouched. | yes (test ws client) | additive — new route |
| **S2** | **Upgrade auth** — verify the `wh_ws` JWT cookie at upgrade via the existing `AuthService.verify` (no new auth surface), `Origin` allowlist (§8), `SameSite=Strict; Path=/api/ws; Secure` cookie scoping, `jwtExp` mid-connection close (§4.3), `reauth` seam, auth metrics. SPA-side: set-cookie-before-connect + clear-after helper. | yes | additive |
| **S3** | **Change sources** — widen `PolicySnapshotPublisher` to a hub; add `SpaEventHub` fed by existing write sites; translate to subscription-gated `now`/`connectionEvents`/`stale` frames (§5.2). | yes (behavior-preserving for the router subscriber; testable via a probe client) | behavior-preserving |
| **S4** | **`trafficUsage` aggregator + live-edge push** — on usage ingest, recompute only the **current bucket** for subscribed `(groupBy,filter)` and push it (merge head, §3.1), latest-wins per param-set (§5.3); a `raw`-bucket subscription pushes at the usage-ingest cadence (fully-realtime, §10 Q1). Reuses the existing query — no new shape, no router change. | yes | additive |
| **S5** | **SPA ws client + realtime dashboard pilot** — `useWsTrafficUsage(params)` driving the overall + per-profile bandwidth gauges with the **window (bucket) selector** (re-subscribes on change, #747), `now` + `connectionEvents{blocked:true}` cache-patching (§3.1), subscription wiring (subscribe-on-mount/unsubscribe-on-unmount), `useWsLive()` indicator (§6.2), set-cookie-then-connect (§4.2), backoff + re-subscribe (§6.1), polling-as-paused-fallback. **Lights up the redesigned NOW + bandwidth + Recently Blocked** ([`dashboard-redesign.md` §8](dashboard-redesign.md), unblocks #1834/#1835). | yes (dashboard only) | additive — other views poll |
| **S6a** | **Live time-usage push** — `timeStatus` (per-profile used/remaining) + `appUsage` (per-app minutes) class-(2) pushes (§1.2) wired to usage-credit / #1849-ticker / extension-grant (§5.2); SPA patches the time-status + per-app caches (§3.1) and pauses the adaptive ladder when `wsLive`. The `appUsage` push targets only the **live (today) window** key — past windows are immutable. Lights up the **live screen-time surface** (per-profile + per-app, /profiles). | yes | additive |
| **S6b** | **Stream the Traffic Usage + Connection Events pages** — those pages keep loading **history via their existing `GET`** (initial window + cursor paging, #862); they additionally subscribe `trafficUsage` / `connectionEvents` with *their* current filters (same topics as the dashboard, different params) for the **live edge only** — `trafficUsage` merges the head bucket, `connectionEvents` prepends new head rows; older/paged data is never mutated by the stream. Pause the page's poll when `wsLive`. Plus class-(3) `stale` for alerts / profiles / devices / schedules (§3.2). | yes (per-page) | additive |
| **S7** | **Retire redundant `refetchInterval`s** — per migrated view, after its push path is proven; keep the `wsLive ? false : …` fallback only where a view still has no push. The only subtractive step, per-view, operator-gated. | yes, last | per-view subtractive |
| **S8** | **(optional) SharedWorker single-socket multi-tab** — only if a deployment shows many tabs/browser (§6.4). | yes, later | additive |

**Critical path to the operator-visible win:** S1 → S2 → S3 → S5 (dashboard NOW +
blocked live), with **S4 → S5** adding live bandwidth from the existing
`traffic_reports` (no router change; realtime-ness scales with the usage-send rate
and approaches real-time as #1023 streams usage). **Parallel:** S3/S4 can land
alongside S2. **S7 is last and per-view-gated** — each cutover off polling is
deliberate once that view's push path is proven, never armed automatically
([`pr-review-checklist.md#monitor-to-merged`](../pr-review-checklist.md)).

---

## 10. Open questions / risks

1. **Bandwidth windows — RESOLVED (operator, 2026-06-25).** Floor is **`1m`** (the
   `traffic_reports` aggregated minimum), giving `{1m, 10m, 1h, …}` from the
   existing read model with no new data path. **Plus a "raw" / ingest-rate mode for
   fully-realtime:** the live edge is pushed **at whatever cadence the router sends
   usage data** (today ~60 s batches; sub-minute and approaching real-time as
   #1023 streams usage as the agent has it), surfaced via the existing `raw`
   `TrafficUsageBucket` value. So realtime-ness is bounded by the usage-send rate,
   **not** by a bespoke high-frequency sample — the conntrack-sample idea (former
   S0) is **dropped** (no router-side throughput frame, nothing new on the agent).
   `5m`/`10m` display buckets remain trivially addable later if wanted.
2. **No hard #1023 dependency for bandwidth — confirmed.** The `1m`-and-coarser
   stream comes from the existing `traffic_reports` (S4) and refreshes at the
   usage-ingest cadence; it simply gets *faster* (toward the `raw` ingest-rate
   mode) as #1023 streams usage. Nothing here blocks on #1023.
3. **Multi-instance — FILED as [#1952](https://github.com/wifihaven/wifihaven/issues/1952).**
   This is the **first feature whose correctness requires cross-instance
   engineering**, so it gets its own issue rather than a buried caveat. Auth is
   already multi-instance-clean (stateless cookie/bearer verify — no ticket store).
   The single-process state that needs a story before a 2nd API instance: the
   `SpaWsRegistry` + per-connection **subscription set**, the `RouterWsRegistry`,
   the `SpaEventHub`/publisher hub, and the `trafficUsage` aggregator (§5) — a push
   computed on instance A can't reach a client on instance B. Options (sticky
   routing vs cross-instance pub/sub vs hybrid), and the fact that subscription
   state rebuilds on reconnect (§6.1) so it need not be replicated, are documented
   in #1952. Correct as-is for the single-instance deployment; #1952 is the home
   for the scaling work.
4. **Thick-push filtering (class 2) — accepted.** Per-role filtering of a pushed
   `now` body is the one place class-(1)/(3)'s contentless safety is absent; it
   reuses the `DashboardNowRoutes` filter (§3.1/§5.2) so there is one filter
   implementation, bounding the risk.
5. **Observability parity — accepted.** Per-frame structured logging
   (`op`/`role`/`result` on the MDC, mirroring `RouterWsRoutes`' `LogContext`) so a
   transport fault is as debuggable as REST. Built into S1.
