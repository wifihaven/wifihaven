# Design — SPA-side websocket (live browser updates)

Status: **proposed** (design only — no code in this PR).

Relates to [#1023](https://github.com/wifihaven/wifihaven/issues/1023) (the
router↔API transport epic this is the browser-facing sibling of),
[#1846](https://github.com/wifihaven/wifihaven/issues/1846) (the server
connection registry + `{op,payload}` envelope this **consumes the pattern of**),
[#1849](https://github.com/wifihaven/wifihaven/issues/1849) (push-on-change —
the change-event source this **consumes**, not rebuilds), and
[#1860](https://github.com/wifihaven/wifihaven/issues/1860) (this issue).

> **Scope of this doc.** This is the umbrella design for the SPA-websocket work.
> It does NOT implement anything. It (a) fixes the browser-facing wire protocol,
> (b) decides the contentious choices — auth handshake, registry reuse-vs-fork,
> patch-vs-invalidate — with justification rather than a menu, and (c) lays out a
> phased, independently-shippable rollout in which **polling stays the fallback
> throughout (no flag day)**. The implementation sub-issues this doc files are
> listed in §8.

---

## 0. Why, and the one shaping constraint

### 0.1 What the SPA does today

The SPA live-updates by **TanStack Query `refetchInterval` polling**
([`web/src/api/queries.ts`](../../web/src/api/queries.ts)):

| Hook | Endpoint | Cadence | Pain |
| --- | --- | --- | --- |
| `useDashboardNow` | `GET /api/dashboard/now` | 10 s | up-to-10 s lag; a kid gets blocked, the parent's screen trails |
| `useRecentBlocked` | `GET /api/logs?blocked=true` | 10 s | same lag on the "what just got dropped" feed |
| `useAlerts` | `GET /api/alerts` | 30 s | a freshly-raised access request waits up to 30 s |
| `useTimeStatus*` | `GET /api/time/status*` | adaptive 10 s–5 m ([`TIME_STATUS_REFETCH_LADDER`](../../web/src/api/queries.ts)) | the whole adaptive ladder exists *only because* there is no push |

Three structural problems, all of which a push connection removes:

- **Staleness** — bounded only by the poll cadence (≤10 s on the hot views).
- **A fixed poll tax per open tab**, paid whether or not anything changed, that
  scales with concurrent admins/tabs rather than with the actual change rate.
- **No "is my data live?" signal.** A silently-dead poll (laptop asleep, API
  unreachable, token expired) looks *identical* to "nothing changed." The
  [`ApiUnreachableBanner`](../../web/src/components/ApiUnreachableBanner.tsx) only
  fires on an *active* request failure ([`apiHealth`](../../web/src/api/apiHealth.ts)
  is driven by `client.req()`); a poll that never fires because the tab is
  backgrounded reports nothing.

### 0.2 The one shaping constraint — the browser `WebSocket` is impoverished

The dominant design force, stated up front so the rest follows from it. A browser
`WebSocket` is **not** `curl` and **not** a server socket:

1. **It cannot set request headers** on the upgrade. There is no way to send
   `Authorization: Bearer <jwt>`. This is *the* reason the SPA auth handshake
   (§3) differs from the router's (which rides a bearer header,
   [`docs/design/websocket-transport.md` §4.1](websocket-transport.md)). Every
   browser-ws auth scheme is a workaround for this single fact.
2. **It cannot send ping/pong control frames** from JS. The heartbeat (§5.2)
   must be an application-level `{op:"ping"}` text frame, or rely on the
   server's control-frame ping + the browser's automatic pong + `onclose`.
3. **It is per-tab and dies on background/sleep.** Reconnect, backoff, and
   multi-tab behavior (§5) are SPA concerns the router never had.

The server side, by contrast, is the *easy* half — `zio-http`'s
`Handler.webSocket` already backs `GET /api/router/ws`
([`RouterWsRoutes.scala`](../../api/src/routes/RouterWsRoutes.scala)); the SPA
endpoint is the same machinery with a different auth gate and a different fan-out
payload. **We therefore reuse the router transport's *patterns* (envelope, demux,
registry shape, metric discipline) and explicitly do not reuse its *code* where
the two genuinely differ (auth, key, payload vocabulary).**

### 0.3 The non-negotiable: no new read-model shapes

Per the same single-source-of-truth discipline as #1023
([`AGENTS.md#single-source-of-truth`](../../AGENTS.md), wire-shape carve-out),
**every server→SPA payload is an existing REST response body, verbatim.** A
push that carries data carries the *exact* JSON the matching `GET` already emits
(`DashboardNow`, the `/api/logs` page, etc.). The socket introduces **zero** new
serializers, DTOs, or `web/src/types/api.ts` shapes. This is what lets §2 keep
React Query as the one client-side cache and the REST endpoints as the schema
authority — the socket is a *transport for change*, not a parallel data model.

---

## 1. Endpoint & protocol

### 1.1 Endpoint

```
wss://<api-host>/api/ws
```

One endpoint for the operator SPA (admin / adult / child — role scoping is
applied per-frame from the authenticated claims, §3.4; there is no separate
`/api/admin/ws`). `<api-host>` is the **same host the REST client already
targets** — `VITE_API_BASE_URL` ([`web/src/api/client.ts`](../../web/src/api/client.ts)):
empty (same-origin) for the JVM-bundled self-hosted build, and the absolute
`https://api.wifihaven.net` / `https://api-staging.wifihaven.net` for the
Cloudflare-Pages cloud build. The SPA derives the ws URL from the same base:

```ts
const wsBase = (VITE_API_BASE_URL || location.origin).replace(/^http/, 'ws')
const url = `${wsBase}/api/ws?ticket=${ticket}`   // ticket: §3.2
```

The Cloudflare-Pages-vs-Render hosting split is a real deployment consideration
and is covered in §7.

### 1.2 Frame envelope — mirror the router transport's framing discipline

Identical envelope discipline to
[`docs/design/websocket-transport.md` §1.2](websocket-transport.md), so the two
share patterns (and a future shared `WsFrame` codec) without sharing semantics:

```json
{ "op": "<string>", "payload": <existing REST JSON | small control object>, "seq": <int?> }
```

- **One JSON text message per frame.** Never split a frame across messages,
  never batch two frames into one. Same rule as the router path.
- **`op`** — the discriminator the receiver demuxes on. Server→SPA ops name a
  *topic that changed* (or, for a thick push, the topic whose body rides in
  `payload`); SPA→server ops are control (`hello`, `ping`, `reauth`).
- **`payload`** — for a thin "stale" frame, a tiny control object (the topic
  key, optional hints). For a thick data frame (§2, the measured exception), the
  **exact** REST body — byte-for-byte what the matching `GET` returns.
- **`seq`** — optional monotonic per-sender counter, observability only
  (gap/dup logging); ignored if absent (forward-compat).

**Unknown `op` → ignore + meter** on both ends (forward-compat), exactly as the
router demux does ([`RouterWsRoutes.dispatch`](../../api/src/routes/RouterWsRoutes.scala)).
This is what lets a future topic ship without a flag day.

### 1.3 Message types

| `op` | Dir | Payload | Meaning / maps to |
| --- | --- | --- | --- |
| `hello` | SPA→S | `{clientVersion, topics?:[…]}` | first frame after connect; optional subscription filter (§1.4) |
| `ready` | S→SPA | `{role, serverTime}` | server confirms auth + role; SPA flips the indicator to "live" |
| `stale` | S→SPA | `{topic:"dashboard-now"\|"recent-blocked"\|"alerts"\|"time-status"\|"routers", scope?}` | **the canonical push** (§2): "this React Query key is stale, refetch it" |
| `data` | S→SPA | `{topic, body:<exact REST body>}` | the **opt-in thick push** (§2.3), used only where measured to matter (dashboard-now) |
| `reauth` | SPA→S | `{ticket}` | present a fresh ticket to extend the connection past JWT expiry without a reconnect (§3.3) |
| `ping` / `pong` | both | `{}` | app-level heartbeat (§5.2) |

`topic` is a **bounded enum** — it doubles as the metric label space (§6) and
maps 1:1 to a React Query key prefix (§2.2). It is never a free-form string and
never carries a per-entity id as a label (a `scope` field inside the payload may
narrow *which* rows changed for a future targeted invalidation, but it is data,
not a metric label).

### 1.4 Subscribe / hello

On connect the SPA sends `hello` once. `topics` is an **optional** filter — if
present, the server only pushes those topics to this connection (a child-role tab
that renders only its own time-status need not receive `routers` frames). If
omitted, the server pushes every topic the connection's role is authorized for
(§3.4). v1 ships the **omit-everything-authorized** default; the `topics` filter
is the forward-compat seam for per-view subscriptions and is honored if sent.
The server replies `ready` with the resolved role; the SPA flips its
live-indicator to "live" only after `ready` (a socket that upgrades but never
`ready`s — e.g. a wedged server — must not read as live).

---

## 2. React Query integration — **invalidate by default, thick-push by exception**

This is the central client-side decision. The two candidates:

- **(A) Thin "stale" push → `queryClient.invalidateQueries`.** The frame carries
  only the topic key; React Query refetches the canonical `GET`.
- **(B) Thick "data" push → `queryClient.setQueryData`.** The frame carries the
  new body; the cache is patched directly, no refetch.

### 2.1 Decision: (A) invalidate is the canonical pattern; (B) is an opt-in optimization

**Recommend (A) as the default for every topic.** Justification:

1. **Single source of truth (the decisive reason).** With (A), the `GET`
   endpoint stays the *only* place a read-model body is produced, and the socket
   carries no data shape at all — it cannot drift from the REST body because it
   contains no body. (B) creates a second producer of the same bytes; even though
   the architecture's wire-shape carve-out *permits* reusing an existing body
   verbatim, every thick topic is a place where a server-side filter/derivation
   (e.g. `DashboardNow`'s per-role device visibility,
   [`DashboardNowRoutes`](../../api/src/routes/DashboardNowRoutes.scala)) must be
   re-applied identically on the push path or the pushed body silently differs
   from the polled one. (A) has no such path to keep in sync.
2. **Authz correctness for free.** A refetch goes through the normal
   `requireAuth` + per-role visibility filter on the `GET`. A pushed body must
   re-implement that filtering *before* fan-out (you cannot push one household's
   `DashboardNow` to a child whose visible device set is a subset). (A) sidesteps
   the entire "did we filter the push correctly per recipient?" risk: the server
   pushes a *contentless* "stale" signal to every authorized connection, and each
   client's refetch is filtered by its own token.
3. **Coalescing is trivial.** Many rapid changes collapse to one refetch:
   React Query dedups concurrent invalidations of the same key, and the server
   can drop-oldest-coalesce `stale{topic}` frames in a wedged connection's
   mailbox (§5.3) because they are identical and idempotent. Thick frames cannot
   be coalesced as cheaply (each carries distinct bytes).
4. **It reuses the existing, battle-tested fetch path** — `cache:'no-store'`,
   the 10 s timeout, `apiHealth` reporting, the 401→/login redirect
   ([`client.ts`](../../web/src/api/client.ts)) — none of which a thick push
   would exercise.

The cost of (A) is **one refetch round-trip of latency** after the signal. For
the household model (LAN or a nearby cloud region, sub-100 ms RTT) this is
negligible against the 10 s poll lag it replaces.

### 2.2 The canonical handler

A single `useWsInvalidation()` hook, mounted once in the app shell, owns the
socket and maps `stale{topic}` → the right invalidation. The topic→key map is the
**one** place the mapping lives (single-source-of-truth), reusing the existing
`qk` key factory and `useInvalidators` helpers in
[`queries.ts`](../../web/src/api/queries.ts):

```ts
// topic (bounded enum, §1.3) → React Query key prefix (reuses qk/useInvalidators)
const TOPIC_INVALIDATORS: Record<WsTopic, (qc: QueryClient) => Promise<unknown>> = {
  'dashboard-now':  qc => qc.invalidateQueries({ queryKey: qk.dashboardNow() }),
  'recent-blocked': qc => qc.invalidateQueries({ queryKey: qk.recentBlocked() }),
  'alerts':         qc => qc.invalidateQueries({ queryKey: ['alerts'] }),
  'time-status':    qc => qc.invalidateQueries({ queryKey: ['time', 'status'] }),
  'routers':        qc => qc.invalidateQueries({ queryKey: ['admin', 'routers'] }),
}
```

`invalidateQueries` only refetches **mounted** queries (and marks unmounted ones
stale for their next mount), so a `stale{time-status}` while the dashboard isn't
open costs nothing — the exact "poll only what's visible" property the adaptive
ladder hand-rolls, now for free.

> Four of the five topics map to an existing polled hook in
> [`queries.ts`](../../web/src/api/queries.ts) (`useDashboardNow`,
> `useRecentBlocked`, `useAlerts`, `useTimeStatus*`). **`routers` is the
> exception:** the admin router list is a plain `api.routers.list()` call today,
> not a TanStack-cached poll, so the `['admin','routers']` key above is
> *forward-looking* — the `routers` topic (router connect/disconnect, fed by
> `RouterWsRegistry` register/deregister, §4.2) implies first wrapping the router
> list/status in a query, not pausing an existing interval. Noted for S5 so the
> implementer doesn't assume a poll to retire.

### 2.3 The thick-push exception (deferred, measured)

`data{topic, body}` exists in the protocol (§1.3) for **one** future case:
`dashboard-now`, the hottest view, where the push body *is already* the exact
`GET /api/dashboard/now` output and the refetch RTT is the visible lag. It is
**not built in v1.** It is enumerated as a later, independently-shippable
optimization (§8, sub-issue **S5**) gated on a measurement showing the refetch
RTT actually matters, and even then it writes through `setQueryData` for the
**same** query key so a subsequent refetch reconciles — the `GET` stays the
schema authority. Shipping (A) first and (B) only-if-measured is the cautious
order; we do not pay (B)'s per-recipient-filtering complexity speculatively.

### 2.4 Polling stays as the paused fallback

`refetchInterval` is **not removed** — it is made conditional on socket health.
A small `useWsLive()` signal (§5.2) gates the interval:

```ts
useDashboardNow({ refetchInterval: wsLive ? false : 10_000 })
```

When the socket is healthy, the interval is `false` (paused) and pushes drive
updates; when the socket is down/reconnecting, the interval resumes at its
current cadence so the view keeps updating exactly as today. This is
belt-and-suspenders: an un-migrated view, an offline window, or a server that
stops pushing all degrade to polling with no user-visible cliff. The redundant
intervals are retired only at the *end* of the rollout (§8, **S6**), per-view,
after the push path is proven for that view.

---

## 3. Auth

The SPA authenticates with the **operator JWT** (`AuthService` /
[`useAuth`](../../web/src/hooks/useAuth.tsx) — `localStorage.token`, HS256,
`{sub, role, iat, exp}`,
[`AuthService.scala`](../../api/src/auth/AuthService.scala)). The browser
`WebSocket` cannot send the `Authorization` header that carries it (§0.2.1), so
the upgrade needs a different mechanism.

### 3.1 The three options

| Mechanism | How | Why rejected / chosen |
| --- | --- | --- |
| **Cookie** | Set an auth cookie; browser sends it on the ws upgrade automatically | **Rejected.** The SPA's whole auth model is a `localStorage` bearer token, not cookies ([`client.ts`](../../web/src/api/client.ts)). Introducing an auth cookie means CSRF defenses, a `Set-Cookie`/SameSite story, and a second credential to keep in sync with the bearer — a large surface for one endpoint. |
| **Subprotocol** | Smuggle the JWT in `Sec-WebSocket-Protocol` (the one header `new WebSocket(url, protocols)` can set) | **Rejected.** Abuses a negotiation header to carry a secret; the full JWT lands in server access logs and proxy logs; awkward to echo back the selected subprotocol correctly. Works, but it's a hack with a long-lived-secret-in-logs footgun. |
| **Short-lived single-use ticket** | Authenticated `POST /api/ws/ticket` (normal bearer) → opaque short-TTL ticket → `wss://…/api/ws?ticket=…` | **Chosen.** ✓ |

### 3.2 Decision: short-lived, single-use ticket

```
SPA                                              API
 │  POST /api/ws/ticket   Authorization: Bearer <jwt>   │  requireAuth(req)  → JwtClaims
 │ ───────────────────────────────────────────────────▶ │  mint ticket: random 256-bit,
 │                                                       │  store {ticket → (sub, role, jwtExp)} TTL=30s, single-use
 │  { "ticket": "<opaque>", "expiresInSec": 30 }         │
 │ ◀─────────────────────────────────────────────────── │
 │                                                       │
 │  GET /api/ws?ticket=<opaque>   Upgrade: websocket     │  consume ticket (atomic delete);
 │ ───────────────────────────────────────────────────▶ │  invalid/expired/used → 401, no 101
 │  101 Switching Protocols                              │  → resolve (sub, role) → register channel (§4)
 │ ◀─────────────────────────────────────────────────── │
```

- **The ticket is not the JWT.** It is an opaque random token, single-use, TTL
  ~30 s, stored server-side (a `Ref[Map[Ticket, TicketEntry]]` in the SPA
  registry — single-process is fine for the household model, §4) bound to the
  authenticated `(sub, role)` and the originating JWT's `exp`. The query-string
  exposure that makes "JWT in the URL" unacceptable is bounded to a 30 s,
  one-shot, non-replayable, non-JWT token. Minting reuses the existing
  `requireAuth` on the `POST`, so there is **one** JWT-validation implementation
  (single-source-of-truth) — the ws path is not a second auth surface.
- **Consume-at-upgrade is atomic** (delete-returns-old) so a ticket cannot be
  used twice (a replay attacker who sniffs the URL loses the race with the
  legitimate upgrade, and after either consumes it the ticket is gone).
- **A bad/expired/used ticket → reject the upgrade with 401** (no 101), exactly
  the router-path semantics ([`RouterWsRoutes`](../../api/src/routes/RouterWsRoutes.scala)).
  Metered `spa_ws_auth_total{result=ticket_invalid|ticket_expired}` (§6).

### 3.3 Token expiry mid-connection + re-auth

The ticket gates only the *upgrade*; the **connection's authz deadline is the
JWT's `exp`**, captured at upgrade (`TicketEntry.jwtExp`). Two cases:

- **Expiry while connected.** The server tracks `jwtExp` per channel; on the
  heartbeat tick where `now ≥ jwtExp`, it closes the channel with a
  `4401 token-expired` close code and metered `spa_ws_auth_total{result=jwt_expired}`.
  The bound on stale-authz carry-over is one heartbeat interval — same property
  the router path gives for token revocation
  ([`docs/design/websocket-transport.md` §4.2](websocket-transport.md)).
- **Re-auth.** Today the SPA has **no silent token refresh** — an expired JWT
  means a 401 → `localStorage` clear → `/login`
  ([`client.ts`](../../web/src/api/client.ts)). The ws design mirrors this exactly
  and adds a seam for when refresh lands:
  - **v1 (no refresh):** on `4401 token-expired`, the SPA stops reconnecting and
    falls back to polling (§2.4); the next REST call 401s and triggers the
    existing `/login` redirect. The user re-logs in; on the new session the SPA
    mints a fresh ticket and reconnects. No new logout path is invented.
  - **Forward-compat (`reauth` op):** when a future silent-refresh mechanism
    exists, the SPA mints a new ticket from the refreshed JWT and sends
    `{op:"reauth", payload:{ticket}}` on the **open** socket; the server
    validates+consumes it and advances the channel's `jwtExp` — extending the
    connection without a reconnect. The op is in the protocol now (§1.3) so the
    server can accept it the moment refresh exists; v1 server may `ack`-reject it.

### 3.4 Role scoping per frame

The resolved `role` (from the ticket → claims) is captured on the channel at
upgrade and is the authz key for fan-out: the server only pushes a topic to a
connection whose role may see it (e.g. `routers` → admin only, matching the
`requireAdmin` on `GET /api/admin/routers`). Because the canonical push is the
*contentless* `stale` signal (§2.1), the worst case of a fan-out bug is a
needless refetch that the `GET`'s own `requireAuth`/role-filter then rejects or
scopes — defense in depth, not a leak. Thick pushes (§2.3), if ever built, must
filter the body per recipient role *before* sending; this asymmetry is a further
reason (A) is the default.

---

## 4. Reuse vs. separate registry — **separate `SpaWsRegistry`, shared event source**

### 4.1 Decision

**Fork a parallel `SpaWsRegistry`; do not generalize
[`RouterWsRegistry`](../../api/src/routes/RouterWsRegistry.scala) to hold both.**
Justification — the two registries differ on the three axes a registry *is*:

| Axis | `RouterWsRegistry` (#1846) | `SpaWsRegistry` (this design) |
| --- | --- | --- |
| **Key** | `RouterId` | a session/connection id, with `role` for authz fan-out (keyed per-connection, not per-user — a user may have N tabs, §5.4) |
| **Fan-out payload** | full `PolicySnapshot` as a `policy` frame | contentless `stale{topic}` frames (§2.1), role-filtered (§3.4) |
| **Event vocabulary** | one event: "policy snapshot changed" | several: policy change, new connection event, usage tick, router connected/disconnected |

Generalizing one registry to two key types, two payload vocabularies, and two
fan-out filters would couple two things that share only the *shape* of "a `Ref`
of channels with a fan-out method." The `RouterWsRegistry` is a clean, small,
single-purpose class
([`RouterWsRegistry.scala`](../../api/src/routes/RouterWsRegistry.scala)); the
right reuse is **the pattern, not the instance** — `SpaWsRegistry` is the same
`Ref[Map[K, Set[WebSocketChannel]]]` shape with SPA-appropriate K, payload, and
metrics. (Same call the router doc makes: "share patterns, not code.")

### 4.2 What IS shared — the change-event source (consume #1849, don't rebuild)

The reuse point is the **event source**, not the registry. #1849's
push-on-change ([`PolicyService.reevaluate`](../../api/src/policy/PolicyService.scala)
→ [`PolicySnapshotPublisher`](../../api/src/policy/PolicySnapshotPublisher.scala))
already fires exactly when policy changes. The SPA needs that signal **plus**
three others the router never cared about (new connection events, usage ticks,
router connect/disconnect). So:

1. **Generalize the single-sink `PolicySnapshotPublisher` into a small
   multi-subscriber hub.** Today it is an `AtomicReference[PolicySnapshotPublisher]`
   with one sink ([`PolicyService.setPublisher`](../../api/src/policy/PolicyService.scala)).
   Replace the single sink with a ZIO `Hub` (or a subscriber list) so **both**
   the `RouterWsRegistry` (which wants the full snapshot) **and** the
   `SpaWsRegistry` (which wants a `stale{topic:"time-status"|"dashboard-now"}`
   derived from "policy changed") subscribe. This is a behavior-preserving
   widening of an existing seam — the router subscriber is unchanged.
2. **Introduce a server-side `SpaEventHub`** (a `Hub[SpaTopic]`) that the SPA
   registry subscribes to, fed by the existing write paths — each is a one-line
   publish at a point that *already* runs on the relevant change:
   - policy reevaluate (#1849) → `time-status`, `dashboard-now`
   - connection-events ingest (`RouterIngestService`, the same handler the REST
     and router-ws paths share) → `recent-blocked`, `dashboard-now`
   - usage ingest → `dashboard-now`
   - alert raised (access-request created) → `alerts`
   - `RouterWsRegistry` register/deregister → `routers`
   The hub is the **one** place "a thing the SPA renders changed" is named;
   `SpaWsRegistry` translates a `SpaTopic` into role-filtered `stale` frames.
   This keeps the change-detection logic at the existing write sites (no new
   polling/diffing loop) — it consumes #1849's discipline and extends it to the
   non-policy topics.

> **Single-process fan-out, like the router path.** Both registries and the hub
> are in-memory, single-instance. This is correct for the single-household model
> (one API process); cross-instance pub/sub is explicitly out of scope, same as
> [`docs/design/websocket-transport.md` §6.1](websocket-transport.md).

---

## 5. Reliability

### 5.1 Reconnect / backoff (browser)

The SPA ws client reconnects with **exponential backoff + jitter** (1 s → 2 s →
4 s → … cap 30 s), reset on a successful `ready` (not merely the socket
`open` — a socket that opens but never `ready`s, e.g. ticket consumed but server
wedged, must not reset the backoff). Reconnect *is* the throttle; there is no
per-frame retry. On `4401 token-expired` (§3.3) the client stops reconnecting and
hands off to polling + the existing `/login` path; on any other close it backs
off and retries. Each reconnect mints a **fresh ticket** (§3.2) — tickets are
single-use, so a reconnect cannot replay the old one.

### 5.2 Heartbeat / liveness → the real "live / reconnecting" indicator

This is the payoff that the silent poll cannot give (§0.1).

- **Heartbeat.** App-level `{op:"ping"}`/`{op:"pong"}` every ~30 s (the browser
  can't send WS control-frame pings, §0.2.2). The server also sends control-frame
  pings; the browser auto-pongs and surfaces a drop via `onclose`. A missed
  app-level `pong` for `2×interval` → the client treats the socket as dead and
  reconnects (§5.1); server-side, a missed pong closes + deregisters the channel.
- **The indicator.** A `useWsLive()` hook exposes `'live' | 'reconnecting' |
  'offline'`, driven by the socket state machine (`ready` → live; backoff →
  reconnecting; gave-up/expired → offline). This **replaces the silent-poll
  ambiguity**: the UI shows a small "Live"/"Reconnecting…" badge, and the
  signal also gates the polling fallback (§2.4) and feeds
  [`ApiUnreachableBanner`](../../web/src/components/ApiUnreachableBanner.tsx):
  - socket reconnecting **and** REST polls failing (`apiHealth.unreachable`) →
    the existing red "can't reach the API" banner (unchanged).
  - socket reconnecting **but** REST polls succeeding → a softer "Reconnecting
    live updates… (still refreshing every 10 s)" state — *degraded, not down*,
    a distinction the current banner cannot draw. (Implementation folds a
    `wsLive` input into `apiHealth`/the banner; the banner's existing
    `unreachable` path is untouched.)

### 5.3 Backpressure

- **Server outbound.** Each channel has a **bounded mailbox**; because the
  canonical frame is the idempotent contentless `stale{topic}` (§2.1), a slow
  client's mailbox **coalesces by topic** — N pending `stale{dashboard-now}`
  collapse to one (the newest is all that matters). A persistently-wedged channel
  (mailbox full of *distinct* topics and not draining) is closed and left to
  reconnect, metered `spa_ws_policy_push_total{result=channel_closed}`-style
  (§6). This is strictly simpler than the router path's snapshot mailbox because
  the payloads are coalescible signals, not distinct bytes.
- **Inbound (SPA→server).** Only tiny control frames (`hello`/`ping`/`reauth`);
  no inbound backpressure concern.

### 5.4 Multi-tab — **socket per tab (v1); SharedWorker deferred**

**Recommend one socket per tab for v1.** Justification:

- Each tab has its own React Query cache (`QueryClient`); a per-tab socket maps
  cleanly to "invalidate *this* tab's cache." A SharedWorker would hold one
  socket for N tabs and then must broadcast each `stale` to every tab's client —
  more moving parts (worker lifecycle, `BroadcastChannel`, message routing) for a
  workload that is tiny: a household has a handful of admin tabs, and the
  per-connection cost is one idle socket + a ~30 s heartbeat + coalesced
  contentless signals. The poll tax this removes scaled per-tab too, so per-tab
  sockets are already strictly better than today.
- **SharedWorker is the documented future optimization** (§8, **S7**), worth it
  only if a deployment shows many tabs per browser; its tradeoff (one socket,
  fewer server connections, but broadcast complexity + no clean per-tab role
  story when tabs differ) is recorded, not a TODO.

### 5.5 Failure independence

A dead socket never breaks the app: it degrades to the polling that runs today
(§2.4). There is no enforcement or correctness dependency on the socket — it is a
pure latency/UX optimization over a REST baseline that stays fully live.

---

## 6. Metrics

All via the `AppMetrics`/`MetricGuard` facade
([`api/src/metrics/Metrics.scala`](../../api/src/metrics/Metrics.scala)),
**bounded labels only — never per-user / per-session / per-mac**
([`docs/process/instrumentation.md`](../process/instrumentation.md)). Mirrors the
router-ws metric families ([`websocket-transport.md` §7](websocket-transport.md))
so the two dashboards read alike.

| Metric | Type | Labels (bounded) | Meaning |
| --- | --- | --- | --- |
| `spa_ws_connections_active` | gauge | `role` (`admin`\|`adult`\|`child`) | currently-open SPA channels, refreshed on every register/deregister so it ages out on disconnect (same discipline as `router_ws_connections_active`) |
| `spa_ws_frames_total` | counter | `op` (bounded enum §1.3), `direction` (`in`\|`out`), `result` (`ok`\|`reject`\|`unknown_op`) | frame throughput + the unknown-op forward-compat counter |
| `spa_ws_auth_total` | counter | `result` (`ticket_ok`\|`ticket_invalid`\|`ticket_expired`\|`ticket_reused`\|`jwt_expired`) | the ticket-handshake + mid-connection-expiry outcomes (§3) |
| `spa_ws_push_total` | counter | `topic` (bounded enum), `result` (`ok`\|`coalesced`\|`channel_closed`) | per-topic fan-out health + backpressure coalescing (§5.3) |

`role`, `op`, `direction`, `result`, `topic` are all small fixed enums — the same
cardinality-firewall discipline `Metrics.scala` already enforces (an
attacker-supplied `op` is collapsed to the literal `unknown` for the label, real
value to the log only, exactly as `RouterWsRoutes` does). **No `router_id`-style
per-entity label** — the SPA has no fleet-bounded entity, so unlike
`router_connected` there is no per-id gauge here.

> **Registration (S1/S6 implementer note):** each new `spa_ws_*` series needs its
> `(name -> allowed keys)` entry added to `MetricGuard.Allowed`
> ([`api/src/metrics/Metrics.scala`](../../api/src/metrics/Metrics.scala), the
> `Allowed` map) or `MetricGuard` rejects the emit — exactly as the `router_ws_*`
> families were registered. Do not rely on the discipline being implicit.

### 6.1 Grafana panel

Add a **`spa-ws.json`** dashboard under
[`deploy/grafana/dashboards/`](../../deploy/grafana/dashboards/) (sibling to the
existing `router-ws-transport.json`), authored against the series above —
**grepped from `api/src`, not a design-doc catalog**
([`docs/process/instrumentation.md#metrics-need-a-dashboard`](../process/instrumentation.md)).
Panels:

1. **SPA connections active** — `sum(spa_ws_connections_active)` stat + a
   `by (role)` timeseries (the "how many browser tabs are live right now" signal,
   the SPA analogue of router connections active).
2. **Frame throughput** — `sum by (op,direction) (rate(spa_ws_frames_total[5m]))`
   timeseries; a separate stat on
   `rate(spa_ws_frames_total{result="unknown_op"}[5m])` (forward-compat tripwire).
3. **Auth outcomes** — `sum by (result) (rate(spa_ws_auth_total[5m]))`; alert-worthy
   if `ticket_invalid`/`ticket_reused` spikes (replay attempt) — noted for the
   alerting epic, not built here.
4. **Push health** — `sum by (topic,result) (rate(spa_ws_push_total[5m]))`; a
   rising `coalesced`/`channel_closed` rate flags slow/wedged clients (§5.3).

The dashboard ships in the **same PR** as the metric-emitting code for each
phase, targeting only series that phase actually emits (no no-data panels), per
the dashboard rule. CI gate: `grafana-terraform`. As a *new* dashboard,
`spa-ws.json` must also be added to the `local.dashboards` list in
[`infra/grafana/main.tf`](../../infra/grafana/main.tf) (sibling to the existing
`router-ws-transport` entry) — that list is what the `grafana-terraform` gate
provisions from.

---

## 7. Deployment consideration — Cloudflare-Pages-vs-Render hosting split

The SPA hosting split ([`AGENTS.md`](../../AGENTS.md) "SPA hosting split")
directly shapes where the ws endpoint lives:

- **Self-hosted (JVM-bundled SPA).** The SPA is served by the API process at the
  same origin (`VITE_API_BASE_URL` empty). `wss://<same-origin>/api/ws` is
  same-origin; no CORS, no cross-host concern. The common case, upgrades cleanly.
- **Cloud (SPA on Cloudflare Pages, API on Render).** The SPA is static assets on
  `app.wifihaven.net` (Cloudflare Pages); the API — and therefore the ws
  endpoint — is on `api.wifihaven.net` (Render). Consequences:
  1. **The ws connection goes browser → Render directly**, *not* through
     Cloudflare Pages (Pages serves static assets only; it does not proxy the
     `/api/*` origin). So Cloudflare Pages config is irrelevant to the socket —
     it is purely a Render concern.
  2. **Render terminates websockets** — confirmed for the router transport on the
     Render web-service plan ([`websocket-transport.md` §3.3](websocket-transport.md));
     the SPA endpoint inherits that, including the same idle-timeout/heartbeat
     pinning (§5.2). The 30 s app-level heartbeat must stay under Render's idle
     timeout, the same constant the router path pins.
  3. **Cross-origin upgrade → an `Origin` allowlist.** Unlike the router (a
     non-browser client that sends no `Origin`), the browser **does** send an
     `Origin` header on the ws upgrade. The server should check it against an
     allowlist (`app.wifihaven.net`, `staging.*`, `localhost` for dev) and reject
     a mismatched origin at upgrade — a CSWSH (cross-site websocket hijacking)
     defense the same-origin self-hosted case gets for free. The ticket (§3.2)
     is the primary credential, but the Origin check is cheap defense-in-depth
     and is the one server-side ws config that differs by hosting mode.
  4. **The SPA derives the ws host from `VITE_API_BASE_URL`** (§1.1) — the same
     env var that already points the REST client at the right API host per
     environment ([`client.ts`](../../web/src/api/client.ts)), so no new
     deployment config is introduced; the ws URL falls out of the existing one.

---

## 8. Phased, independently-shippable rollout

Each phase **ships independently and back-compat**: polling stays live
throughout (§2.4), the ws path is purely additive until the final per-view
retirement, and **no phase is a flag day**. Server-first (exercisable by a test
ws client before any SPA code), then one pilot view, then broaden, then retire
redundant intervals last. Sub-issues to file against this doc:

| # | Sub-issue | Independently shippable? | Back-compat |
| --- | --- | --- | --- |
| **S1** | **API `/api/ws` endpoint + `{op,payload}` demux + `SpaWsRegistry`** — the server half: ticket-less skeleton refused for now, envelope/demux (`hello`→`ready`, `ping`/`pong`, unknown-op ignore+meter), per-connection registry keyed by session, server metrics §6, `spa-ws.json` panels for the series it emits. Reuses the `RouterWsRoutes` patterns. REST untouched. | yes (test ws client) | additive — new route only |
| **S2** | **Ticket handshake + auth** — `POST /api/ws/ticket` (reuses `requireAuth`), single-use short-TTL ticket store, consume-at-upgrade, `Origin` allowlist (§7), mid-connection `jwtExp` close (§3.3), `reauth` op accepted (or ack-rejected) as the forward-compat seam, auth metrics. | yes (completes server auth; still no SPA client) | additive |
| **S3** | **Generalize the change-event source** — widen `PolicySnapshotPublisher`'s single sink to a multi-subscriber hub so both registries consume #1849; add `SpaEventHub` (`Hub[SpaTopic]`) fed by the existing write sites (policy reevaluate, events/usage ingest, alert raise, router connect/disconnect); `SpaWsRegistry` translates topics → role-filtered `stale` frames (§4.2). | yes (the hub + fan-out are exercisable by a test client; behavior-preserving for the router subscriber) | behavior-preserving |
| **S4** | **SPA ws client + pilot view (dashboard "now") off polling** — the `useWsInvalidation()` hook (§2.2), ticket-mint-then-connect, reconnect/backoff (§5.1), heartbeat + `useWsLive()` indicator (§5.2), polling-as-paused-fallback wiring for **dashboard-now only** (§2.4). One view migrated; everything else still polls. | yes (one view) | additive — other views unchanged |
| **S5** | **Broaden to recent-blocked, alerts, time-status** — add their topic→invalidator entries (§2.2) and pause their intervals when `wsLive`; optionally the **thick-push** measurement + `data{topic}` for dashboard-now *iff* the refetch RTT is shown to matter (§2.3). | yes (per-view) | additive |
| **S6** | **Retire redundant `refetchInterval`s** — per migrated view, after its push path is proven on the fleet, drop the now-paused interval (keep the `wsLive ? false : …` fallback only where a view still has no push). The only "removal" step; per-view, operator-gated, never a flag day. | yes, last | the only subtractive step, gated per-view |
| **S7** | **(optional) SharedWorker single-socket multi-tab** — only if a deployment shows many tabs/browser (§5.4). Records the broadcast-complexity tradeoff; not a TODO. | yes, later | additive |

**Critical path:** S1 → S2 → S4 (the first user-visible win). **Parallel:** S3
(the event source) can land alongside S2 since it improves nothing user-facing
until S4 consumes it, but is independently testable. **S6 is last and
per-view-gated** — the cutover off polling for each view is a deliberate step
once that view's push path is proven, never armed automatically
([`docs/pr-review-checklist.md#monitor-to-merged`](../pr-review-checklist.md)
discipline).

---

## 9. Open questions / risks

1. **Ticket store durability.** v1's single-process in-memory ticket store
   (§3.2) is correct for one API instance; a multi-instance API would need the
   ticket validated on the instance that terminates the upgrade (sticky routing
   or a shared store). Same single-process assumption as the registries (§4) —
   documented limit, not a TODO, and consistent with the router path.
2. **Thick-push filtering (if S5 ever builds it).** Per-recipient role filtering
   of a thick `data{dashboard-now}` body is the one place (A)'s
   contentless-signal safety is lost; the measurement gate (§2.3) and the
   "writes through the same key, GET reconciles" rule bound the risk, but it is
   the riskiest optional piece and stays deferred until measured.
3. **Render idle-timeout vs. heartbeat.** The 30 s app-level heartbeat (§5.2)
   must stay under Render's ws idle timeout — pinned to the same constant the
   router transport's D0 spike established, re-confirmed for the SPA endpoint.
4. **Observability parity.** Per-frame structured logging
   (`op`/`role`/`result` on the MDC, mirroring `RouterWsRoutes`' `LogContext`
   annotations) so a transport fault is as debuggable as the REST path. Built
   into S1.
