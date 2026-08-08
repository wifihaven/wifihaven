# Design — persistent websocket router↔API transport

Status: **proposed** (design only — no code in this PR).
Relates to [#1023](https://github.com/wifihaven/wifihaven/issues/1023) (transport
migration), [#376](https://github.com/wifihaven/wifihaven/issues/376) (capability
negotiation, the gate this re-examines), [#1512](https://github.com/wifihaven/wifihaven/issues/1512)
(snapshot-build cost — push-on-change removes the per-poll recompute),
[#1017](https://github.com/wifihaven/wifihaven/issues/1017) (the 100 KiB body cap
that triggered this).

> **Scope of this doc.** This is the umbrella design for the EPIC. It does NOT
> implement the transport. It (a) fixes the wire protocol, ~~(b) specifies the
> *minimal* capability-negotiation needed to ship a non-additive transport
> change safely (the part of #376 that actually blocks #1023),~~ and (c) lays out
> a phased, independently-shippable, back-compat rollout. The implementation
> sub-issues filed against this doc are listed in §9. **(b) was dropped:** the ws
> transport carries the REST payloads verbatim, so the existing additive +
> ignore-unknown wire contract already covers it — no capability handshake (§2
> status, #1847 closed won't-do 2026-06-23).

---

## 0. Why, and the one hard constraint

Today the router↔API link is three independent HTTP exchanges
(`docs/architecture.md` §6.2–6.5):

| Exchange | Direction | Cadence | Pain |
| --- | --- | --- | --- |
| `GET /api/router/policy` | API → agent (pull) | ~5 s ETag poll | up-to-5 s policy latency; **~500 ms recompute per poll** (#1512) |
| `POST /api/router/usage` | agent → API | ~60 s batch | **blew the 100 KiB body cap** (#1017); artificial 60 s bucketing |
| `POST /api/router/events` | agent → API | ~10 s batch | same body cap is now biting it too |
| `POST /api/router/metrics` | agent → API | ~60 s | independent failure surface; no single "link healthy?" signal |

The goal: **one persistent websocket per router** carrying all four flows, with
policy pushed on change (sub-second instead of ≤5 s) and usage/events pushed
when the agent has data (no body cap, no forced bucketing). This collapses four
independent retry/health stories into one connection whose liveness *is* the
"router connected" signal the #866/#913 cascade lacked.

### 0.1 The one hard constraint — the agent has no event loop

This is the dominant design force and the single biggest risk, so it is stated
up front. The OpenWRT agent (`openwrt/files/usr/sbin/wifihaven-agent`) is **a
single foreground Lua process with no async I/O**. Its structure
(`conntrack.watch{…, on_tick=…}`) is:

```
conntrack -E  ──blocking read──▶  on_tick()  ─▶ policy poll / usage / metrics timers
   (one line at a time)             (cooperative, fires per conntrack line)
```

Every network call is a **`curl` shell-out** (`http_get` / `http_post` in the
agent; see `policy.lua`, `usage.lua`). There is no select loop, no coroutine
scheduler, no persistent socket. A long-lived bidirectional websocket — which
must read server-pushed frames *at any time* while also sending — does not fit
the `curl`-per-call, conntrack-driven cooperative model.

Two consequences drive the whole agent-side design:

1. **The websocket lives in its own sidecar process** (`wifihaven-ws`), a new
   procd instance alongside the existing `dns-tail` / `sni-tail` / `nflog-tail`
   sidecars (`openwrt/files/etc/init.d/wifihaven`). It owns the socket's event
   loop; it does not share the main agent's conntrack loop. It bridges to the
   main agent over the **filesystem queues that already exist on tmpfs** (the
   usage retry queue, the events batcher spool), so the main agent's data-path
   code is unchanged — it writes frames to a spool, the sidecar drains the spool
   to the socket; the sidecar writes received `policy` frames to the snapshot
   file the main agent already reads.
2. **A Lua websocket library on OpenWRT must be proven before any agent code is
   committed.** This is gated behind an explicit *spike* sub-issue (§9, sub-issue
   D0) — see §3.4.

> **Server side is the easy half.** `zio-http` 3.0.1 (`build.mill`) has
> first-class websocket support (`Handler.webSocket`, `WebSocketApp`, `channel`
> send/receive). The server work is well-understood; the agent work carries the
> risk. We therefore sequence **server endpoint first** (§9, sub-issue A) so the
> wire can be exercised by a test client long before the Lua client exists.

---

## 1. Protocol & messages

### 1.1 Endpoint and lifecycle

```
wss://api.wifihaven.net/api/router/ws
```

```
agent                                   API
  │   HTTP/1.1 Upgrade: websocket          │
  │   Authorization: Bearer rt_…           │
  │   X-WifiHaven-Agent-Version: 0.3.1     │
  │ ─────────────────────────────────────▶ │  RouterAuth.authenticate(upgradeReq)
  │                                         │  → resolve Router, register channel
  │   101 Switching Protocols              │
  │ ◀───────────────────────────────────── │
  │                                         │
  │   {"op":"hello", payload:{caps,ver}}    │  ── agent advertises first (§2)
  │ ─────────────────────────────────────▶ │
  │   {"op":"ready", payload:{caps,ver,     │  ── server replies negotiated set
  │                  snapshotVersion}}      │
  │ ◀───────────────────────────────────── │
  │   {"op":"policy", payload:<snapshot>}   │  ── server pushes current snapshot
  │ ◀───────────────────────────────────── │     (replaces the first GET poll)
  │                                         │
  │   …steady state…                        │
  │   {"op":"usage", payload:<UsageReport>} │ ─▶  (agent → API, any time)
  │   {"op":"ack", payload:{op,seq,status}} │ ◀─  (API → agent, per data frame; drives spool drain §5.3)
  │   {"op":"events", payload:<…>}          │ ─▶
  │   {"op":"metrics", payload:<…>}         │ ─▶
  │   {"op":"policy", payload:<snapshot>}   │ ◀─  (API → agent, on policy change)
  │   {"op":"ping"}  / {"op":"pong"}        │ ◀▶  (heartbeat, both directions)
  │                                         │
```

The connection is **long-lived**: opened once at agent boot, kept warm by
heartbeats, re-established on drop (§5).

> **Note (2026-06-23):** the `hello`/`ready` exchange shown above is **not built**
> — the capability handshake was closed won't-do (§2 status, #1847). In the
> implemented flow the connection upgrades, the server registers the channel, and
> (under #1849) pushes the first `policy` frame directly — no handshake step.

### 1.2 Frame envelope

Every frame is a single JSON **text** websocket message:

```json
{ "op": "<string>", "payload": <existing REST JSON, verbatim>, "seq": <int?> }
```

- `op` — a discriminator naming the logical channel. Server demuxes on it and
  dispatches into **the existing handler code** (`handleUsage`, `handleEvents`,
  the metrics ingest path, and the snapshot builder). **No payload shapes
  change** — `UsageReport`, `RouterEventsRequest`, the metrics body, and
  `PolicySnapshot` (`shared/src/Models.scala`) stay the single source of truth.
- `payload` — the exact JSON body the corresponding REST endpoint accepts/emits
  today. A `usage` frame's payload is byte-for-byte a current
  `POST /api/router/usage` body.
- `seq` — optional monotonic per-sender counter, present on agent→API data
  frames only, used for at-least-once dedup logging and gap detection (§5.4).
  Ignored if absent (forward-compat).

**Framing rule:** one JSON object per websocket message; never split a frame
across messages, never batch two frames into one message. Frame size is bounded
by the agent never emitting a payload larger than `ws_max_frame_bytes` (§5.3) —
this is the structural fix for #1017: the agent splits a large `UsageReport`
into multiple `usage` frames instead of one over-cap POST.

### 1.3 Message types

| `op` | Dir | Payload | Maps to today's |
| --- | --- | --- | --- |
| ~~`hello`~~ | A→S | ~~`{agentCapabilities:[…], snapshotVersion:int, agentVersion:str}`~~ | **NOT BUILT** — handshake won't-do, see §2 status (#1847) |
| ~~`ready`~~ | S→A | ~~`{serverCapabilities:[…], snapshotVersion:int}`~~ | **NOT BUILT** — handshake won't-do, see §2 status (#1847) |
| `policy` | S→A | `PolicySnapshot` JSON | `GET /api/router/policy` 200 body |
| `usage` | A→S | `UsageReport` JSON | `POST /api/router/usage` body |
| `events` | A→S | `RouterEventsRequest` JSON | `POST /api/router/events` body |
| `metrics` | A→S | metrics-push JSON | `POST /api/router/metrics` body |
| `ack` | S→A | `{op:str, seq:int, status:"ok"\|"reject", reason:str?}` | the 200/4xx status the POST returned |
| `ping` | both | `{}` | (new — heartbeat) |
| `pong` | both | `{}` | (new — heartbeat) |

**Unknown `op` → ignore + meter** (forward-compat, §2.3). This is what lets a
future `policy_diff` op (out of scope here, per #1023) ship without a flag day.

### 1.4 How `policy` push replaces the poll (ETag / version semantics)

The ETag stays the snapshot's identity, but the *transfer* inverts:

- **On connect** (right after the upgrade — there is no `ready` step; the
  handshake is won't-do, §2 status), the server sends exactly one `policy` frame
  with the current snapshot. This replaces the agent's first `GET` poll. The
  agent applies it before sending any data frame (avoids a first-connect policy
  race — same guarantee #1023 calls out).
- **On change**, the server pushes a fresh `policy` frame to every connected
  agent (§6.2). The agent compares the pushed snapshot's `etag` to the
  currently-applied one and **applies only if it differs** (the agent keeps its
  existing "apply iff etag changed" guard from `policy.lua`, so a redundant push
  is a cheap no-op, not a re-render).
- **No 304 over the socket.** 304 existed only to make polling cheap; push makes
  it moot. The server simply does not push when nothing changed. The agent never
  *requests* a snapshot in steady state.
- **Reconnect re-syncs unconditionally**: on every (re)connect the server pushes
  the current full snapshot, so an agent that missed a change while disconnected
  converges immediately (§5.1). v1 always ships the **full** snapshot; diffs are
  out of scope (#1023).

The `etag` field and `PolicyService.computeEtag` are unchanged — they remain the
change-detection key the server uses to decide *whether* to push (§6.2).

> **Implementation note (#1945).** "The agent applies it" is split across the two
> agent processes to keep a *single enforcement owner*. The `wifihaven-ws` sidecar
> (the only ws process) merely **persists** a pushed `policy` frame to
> `/etc/wifihaven/policy.json` via the atomic `policy.save_snapshot` (tmp→rename);
> it never touches nft/dnsmasq. The **main agent** owns enforcement: an
> apply-on-push tick in its `on_tick` loop runs the pushed snapshot through the
> *same* apply pipeline the HTTP poll uses (`refresh_blocklists` → `policy.apply`
> → `render.update_shared`) **iff the on-disk `etag` differs** from the
> currently-applied one. The `etag` is the single dedup key shared by the poll
> and the push, so even while the poll still runs (it is deprecated separately in
> [#1850](https://github.com/wifihaven/wifihaven/issues/1850)) a snapshot is
> applied exactly once and there is no two-writer race on the enforcement plane.
> Before #1945 the sidecar saved the file but nothing re-applied it mid-run, so a
> push was a no-op for live enforcement until the next poll/restart.
>
> **Event-driven wake (#2229).** The apply tick no longer waits up to
> `wifihaven.ws.apply_interval` to *notice* a push. When the sidecar persists a
> snapshot it also writes a tiny trigger — `"<etag>\t<uptime>"` at
> `paths.ws_pending` (the `ws_pending` module; `<uptime>` is a `/proc/uptime`
> stamp both processes read) — and the agent reads that few-byte file on **every**
> `on_tick` (driven by the #2024 heartbeat, so ~1 s even fully idle). A pushed
> pause therefore applies within one tick, not one `apply_interval`; the 2 s
> poll-of-disk gate is kept only as a backstop for a missed/torn trigger. The
> agent observes `ws_push_apply_latency_seconds` (persist→apply) from the stamp.
>
> **Enforcement-first apply ordering (#2229).** Inside `policy.apply` the
> `nft -f` load (which *is* the enforcement change — the whole-MAC drop,
> per-host drops, `blocked_macs`) now runs **before** the `/etc/init.d/dnsmasq
> restart`, not after. The restart only propagates changed
> `dhcp-host=`/`nftset=` directives (ipsets populate on the next resolve) and,
> because a pause toggles the profile's `extraAllowed` carve, it fires on nearly
> every pause/unpause and can take seconds (much longer on a prod-sized config).
> When it ran first it *gated* the block behind itself — the dominant part of the
> #2229 push→apply latency. Loading nft first lands the block in ~0.1 s and lets
> the (unavoidable, #414-conditional) restart run without gating enforcement.

---

## 2. Capability negotiation (re-examining #376)

> **Status (2026-06-23): NOT BUILDING THIS — sub-issue B
> ([#1847](https://github.com/wifihaven/wifihaven/issues/1847)) closed won't-do.**
> The premise below — that replacing the poll "forces the question" of capability
> negotiation — was reconsidered and rejected. The ws transport **carries the REST
> payloads verbatim** (`PolicySnapshot`, `UsageReport`, events, metrics — §1.2/§1.3
> are explicit that no payload shapes change), so the **existing REST wire contract
> (additive-only + ignore-unknown fields, `docs/process/wire-contract.md`) already
> makes ws evolution safe, identically to REST.** A `hello`/`ready` handshake with
> `snapshotVersion` negotiation gated **zero behavior** today: version negotiation /
> a `4003`-style refusal only buys anything for a *non-additive* snapshot change,
> which the wire contract forbids without a deprecation window **regardless of
> transport** — so the handshake was pure insurance for a hypothetical future
> breaking change. The one genuinely-needed forward-compat rule — **ignore + meter
> an unknown `op`** (§1.3) — already shipped in #1846, and unknown payload fields
> are already ignored by both decoders. If an actual breaking snapshot-shape change
> ever needs the `snapshotVersion ≥ 2` translation machinery, that is the deferred
> #376 follow-up (sub-issue **F**) — built then, against a concrete need. **Do not
> build the `hello`/`ready`/`snapshotVersion` handshake.** The rest of this section
> is retained as the rejected design's rationale; the `hello`/`ready` rows in §1.1
> and §1.3 are likewise not implemented.

[#376](https://github.com/wifihaven/wifihaven/issues/376) was deferred on the
grounds that a single prod router can tandem-deploy. **This transport change
forces the question**, because per `docs/process/wire-contract.md` a
non-additive wire change (replacing the poll) is explicitly *gated on* #376. But
the full #376 ask (a `serverCapabilities`/`agentCapabilities` matrix gating
every optional snapshot field) is more than #1023 needs. This section specifies
the **minimal** slice of #376 that unblocks the websocket safely, and defers the
rest.

### 2.1 What "minimal" means here

The only capability that *must* be negotiated for #1023 is **"can this pair
speak the websocket transport at all?"** Everything else (per-field gating of
snapshot shape) is a property of the *payload*, and the payload is unchanged by
this epic — so the existing additive-only + ignore-unknown-fields rules already
cover it. We therefore add:

1. **A handshake** (`hello` → `ready`) carrying capability sets both directions.
2. **`snapshotVersion`** — an integer the agent advertises (its max-understood
   `PolicySnapshot` shape) and the server echoes (the version it will emit).
   Starts at **1** (today's shape). Bumped **only** on a breaking snapshot-shape
   change. This is additively introduced — see §2.4.
3. **A documented forward-compat rule** (ignore-unknown), already implied by the
   wire contract, now made explicit for both the envelope (`op`) and the
   payload (fields).

We **defer** (still #376, not this epic):

- Per-field capability gating of the snapshot (`"unmanaged-mac-policy"`,
  `"https-block-page"`, …). Not needed while the payload is frozen.
- `snapshotVersion ≥ 2` and the v1↔v2 shape-translation machinery. Filed as a
  follow-up that *uses* the handshake this design lands (§9, sub-issue F).

### 2.2 Handshake

```jsonc
// agent → server, immediately after upgrade
{ "op": "hello", "payload": {
    "agentCapabilities": ["ws-transport-v1", "usage-frame-split"],
    "snapshotVersion": 1,
    "agentVersion": "0.3.1"
} }

// server → agent, reply
{ "op": "ready", "payload": {
    "serverCapabilities": ["ws-transport-v1", "policy-push", "ack-frames"],
    "snapshotVersion": 1               // = min(agent.max, server.max)
} }
```

- **Capability sets are string sets**, intersected to decide optional behavior.
  v1 the only meaningful capability is `ws-transport-v1` (presence of which is
  implied by reaching the handshake at all) plus `policy-push` and `ack-frames`
  (server features the agent may rely on). The set is the **extension point**:
  future features (diffs, compression) are announced here, never inferred.
- **`snapshotVersion` = `min(agent.maxKnown, server.maxKnown)`.** The server
  emits snapshots at the negotiated version. v1 both ends are at 1, so this is a
  no-op today — but the field and the `min` rule ship now so the *next* shape
  change is a version bump, not a flag day. This is the durable half of #376.
- If the agent omits `hello` (an older agent that somehow reached the ws
  endpoint — shouldn't happen, since ws is new, but defensively): server waits
  `hello_timeout` (5 s) then closes with code `4002 hello-required`. The agent
  falls back to HTTP polling (§3).

### 2.3 Forward-compat (unknown-field policy) — now explicit in the contract

Added to `docs/process/wire-contract.md` and `docs/architecture.md` as the
"Evolution policy" subsection #376 asks for:

- **Envelope:** a receiver MUST ignore (and meter) a frame whose `op` it does
  not recognize. New ops are additive.
- **Payload:** both sides MUST ignore unknown JSON fields (already the case —
  zio-json `derives JsonCodec` ignores extras; the Lua `jsonc` decoder ignores
  extras). New payload fields are additive.
- **Version ceiling:** an agent MUST refuse a `policy` frame whose negotiated
  `snapshotVersion` exceeds its max-known (this *cannot* happen given the `min`
  rule, but is stated so a server bug can't silently push a v2 shape to a v1
  agent — it would close `4003 version-exceeded` and fall back to its last-good
  cached snapshot per `docs/resilience.md §1`).

### 2.4 Why this is additive and safe to ship now

`snapshotVersion`, `serverCapabilities`, `agentCapabilities` are introduced
**only inside the new `hello`/`ready` frames on the new ws endpoint**. They do
**not** touch the existing `PolicySnapshot` JSON or any REST body, so no
already-deployed agent parses anything new (the REST poll path is byte-identical).
A v1 ws-capable agent and a v1 ws-capable server negotiate `snapshotVersion: 1`
and behave exactly as #1023 describes. The machinery sits dormant until the
first real shape change needs it — which is exactly the property #376 wanted.

---

## 3. Fallback — agents/proxies that can't do websocket

Auto-update is not instant; mismatched versions run in the field for hours-to-
days. The REST endpoints therefore **stay fully live** for the entire epic and
the deprecation window after it (§9, sub-issue G). No agent is ever forced onto
the websocket.

### 3.1 The agent decides per-boot; the server supports both forever (until deprecation)

> **Default (as of [#2608](https://github.com/wifihaven/wifihaven/issues/2608)):
> ws.** The UCI flag `wifihaven.ws.enabled` defaults to **1 when unset**, so a
> fresh install and an upgraded router both land on the websocket with no manual
> step. Only an explicit `enabled=0` selects the HTTP path up front — and the
> shipped `/etc/config/wifihaven` deliberately writes no value, so "unset" stays
> observable. The one-shot `/etc/uci-defaults/97-wifihaven-ws-default-on`
> migration moves pre-#2608 routers over and records a
> `wifihaven.ws.default_on_migrated` marker; once that marker exists the
> migration never rewrites `enabled` again, which is what makes an operator's
> opt-out durable. **Nothing else in this section changes** — every fallback
> below is exactly as it was, and it is what keeps the default flip safe.

```
agent boot
  │
  ├─ ws-transport enabled in UCI?  ── explicit 0 ──▶  HTTP poll/POST path (today's code, untouched)
  │            │ yes
  ├─ open wss://…/api/router/ws
  │     ├─ 101 + first policy frame within connect_timeout?  ── no ──▶  log, mark ws-unhealthy, HTTP path  (no `ready` — handshake won't-do, §2)
  │     │            │ yes
  │     └─ run ws sidecar; main agent's HTTP poll timer goes dormant
  │
  └─ ws drops & won't re-establish for `ws_fallback_after` (e.g. 5 min)?
        └─▶ main agent resumes HTTP polling until ws re-establishes (belt-and-suspenders)
```

- **The server runs both transports in parallel.** `RouterRoutes` (REST) and the
  new `RouterWsRoutes` are both mounted. A router may use either; nothing on the
  server assumes which. The two paths share the **same** ingest handlers and the
  **same** snapshot builder, so there is exactly one implementation of each
  behavior (no `single-source-of-truth` violation — see
  `docs/process/single-source-of-truth.md`).
- **`routers.last_seen_at`** is touched by *both* paths (the ws heartbeat and the
  REST poll both call `routerRepo.touch`), so liveness is uniform regardless of
  transport.
- **`routers.last_etag`** is written by *both* paths too, as of
  [#2619](https://github.com/wifihaven/wifihaven/issues/2619): the REST poll
  stamps the etag it serves, and `RouterWsRegistry` stamps the etag a `policy`
  frame delivered. Before that only the poll wrote it, which froze the column
  for any router on a healthy ws link (the poll goes dormant, #2037).
  The column means **the newest policy version the server has SENT this
  router** — a send-time fact on either transport, never "the router has applied
  it". The applied etag lives in the router's own on-disk snapshot.
  Note that unlike `last_seen_at`, the ws stamp writes `last_etag` ALONE
  (`RouterRepo.touchEtag`, not `touch`). `last_seen_at` means "we heard from
  this router", and every writer of it is triggered by something the router did;
  a server-initiated push must not be able to hold that gauge green for a router
  whose socket has gone half-open.

  One case where the ws path deliberately does NOT write: the push-on-change
  fan-out is not household-scoped yet
  ([#2626](https://github.com/wifihaven/wifihaven/issues/2626)), so the stamp is
  skipped (and metered `router_ws_etag_stamp_total{outcome="household_mismatch"}`)
  when the pushed snapshot's household differs from the router's. A stale etag
  is recoverable; another tenant's etag written into this column is not.
  **Consequence, stated plainly: until #2626 lands, a router in a NON-default
  household does not get an advancing `last_etag` at all** beyond its
  connect-time push, which is household-scoped and therefore does stamp. That
  also makes `household_mismatch` a steady-state series rather than an anomaly —
  read it as a ratio against `ok`, not against zero.

### 3.2 Detection: how the server knows which a router uses

It doesn't need to *choose*, but it should *observe* for the rollout dashboard:
a `router_transport` bounded label (`ws` | `http`) on the connection metric
(§7) lets the operator watch the fleet migrate and confirm the ws path is
healthy before arming any deprecation.

### 3.3 Proxy / network reality

- **Render** terminates websockets (confirmed supported on the Render web-service
  plan); the spike (D0) confirms per-plan idle-timeout and max-frame limits and
  pins the heartbeat cadence (§5.5) under them.
- A **captive-portal / transparent-proxy** between a self-hosted router and a
  cloud API that mangles `Upgrade` → the 101 never arrives → the agent falls
  back to HTTP (§3.1) automatically. Self-hosted installs (SPA+API on the same
  LAN host, no proxy) are the common case and upgrade cleanly.

### 3.4 The Lua-websocket spike gates everything agent-side

Before any agent ws code is committed, sub-issue **D0** must answer:

- Which Lua ws library is in the OpenWRT package feed and small enough for the
  router image? Candidates: `lua-websockets` (pure-Lua + LuaSocket; check feed),
  or a `libwebsockets` C-binding, or — fallback — `curl`'s experimental ws
  support / a hand-rolled framing layer over the `lua-openssl` TLS socket the
  agent already depends on (`openwrt/Makefile` DEPENDS lists `lua-openssl`,
  `libustream-mbedtls`).
- Can it hold a TLS ws connection open for hours under OpenWRT's memory budget
  without leaking? (long-soak test on real hardware / qemu).
- Does it cleanly surface "connection dropped" so the sidecar can reconnect?

**If D0 fails** (no viable library), the epic stops at the server endpoint (A;
B the handshake is won't-do, §2) — independently valuable (a future agent or an
OPNsense agent can use the endpoint) — and the agent stays on HTTP. This is why
A is sequenced first and sized to ship without C.

---

## 4. Auth

### 4.1 Upgrade-time auth (reuse `RouterAuth`, unchanged)

The agent sends the existing per-router bearer token in the **`Authorization`
header of the HTTP upgrade request** (`policy.lua` already builds this header;
zio-http exposes upgrade-request headers to the `webSocket` handler). The server
calls the **existing** `RouterAuth.authenticate(upgradeReq)` →
`repo.findByTokenHash(sha256Hex(token))` before completing the upgrade. A bad
token → **reject the upgrade with 401** (no 101), identical semantics to today's
REST 401. The agent treats a ws-401 exactly like a REST-401 (drop, log, do not
hammer-retry) and may fall back to HTTP (which will also 401 — surfacing the same
operator-visible "router needs re-enrollment" state).

Reuse means there is **one** token-validation implementation; the ws path is not
a second auth surface (`single-source-of-truth`).

### 4.2 Re-auth / token expiry mid-connection

Router tokens today do not expire (they are revoked by re-enrollment, which
rotates `token_hash`). For a long-lived connection:

- **Revocation closes the socket.** If a token is rotated (re-enrollment) while a
  connection is open, the server closes that connection with `4401
  token-revoked` on the next heartbeat tick (the registry re-checks the
  token_hash against the row, or simply drops connections for a router whose
  row's `token_hash` changed). The agent, now holding a stale token, will fail
  to re-upgrade (401) and surface the re-enroll-needed state.
- **No silent privilege carry-over.** Because every ingest is still dispatched
  through the same `router`-scoped handlers (the channel carries the resolved
  `Router`, set once at upgrade), a revoked router cannot keep ingesting after
  its row changes — the close is the enforcement point, bounded by the heartbeat
  interval.

### 4.3 Enrollment interplay (unchanged)

Enrollment (`POST /api/router/register`, one-time enrollment token → router
token) stays a plain REST call. **The websocket is a post-enrollment transport
only**; a router with no `router_token` cannot open it (no bearer → 401). This
keeps the single-use enrollment-token security property (`docs/process/security.md`)
entirely on the existing REST path — the ws design adds no new enrollment
surface.

---

## 5. Reliability

### 5.1 Reconnect / backoff

- The ws sidecar reconnects with **exponential backoff + jitter** (e.g. 1 s →
  2 s → 4 s → … cap 60 s), reset on a successful connect (the `101` upgrade; not
  a `ready` — handshake won't-do, §2). Reconnect *is* the throttle — there is no
  per-frame retry (#1023).
- On every (re)connect the server pushes the current full snapshot, so the agent
  re-syncs policy unconditionally — a missed change during a disconnect window
  converges on reconnect, no diff bookkeeping needed.
- While disconnected longer than `ws_fallback_after`, the main agent resumes
  HTTP polling (§3.1) so enforcement never goes stale waiting on a flapping
  socket. Failover/boot-deny semantics (`docs/resilience.md §1/§4`) are
  **unchanged** — a stale policy still ages into the per-profile failure mode
  exactly as it does under HTTP polling, because that logic lives in the main
  agent, not the transport.

### 5.2 Backpressure & buffering

- **Outbound (agent→API):** the sidecar drains the **existing tmpfs spools** —
  the usage retry queue (`usage_queue`, already bounded by
  `usage.MAX_QUEUE_BUCKETS`) and the events batcher spool. These are already
  size-capped and rotation-bounded per `docs/process/router-agent-bounded-writes.md`;
  the ws sidecar inherits those caps unchanged — **no new unbounded /tmp
  growth**. If the socket is slow/blocked, frames stay in the spool (oldest-first
  drop at the existing high-water cap, logged), exactly as a failed POST does
  today. This satisfies the bounded-tmp-writes rule with **zero new spool**.
- **Inbound (API→agent):** the only server-pushed payload is `policy` (small,
  one at a time) plus heartbeats. No inbound backpressure concern; the agent
  applies the latest and discards superseded ones (last-write-wins by etag).
- **Server outbound:** the connection registry (§6) pushes policy to each
  channel with a bounded per-channel mailbox; if a channel's send buffer is full
  (a wedged client), the server drops the oldest policy push for that channel
  (the newest snapshot is all that matters) and meters it. A persistently-wedged
  channel is closed and left to reconnect.

### 5.3 At-least-once + dedup (usage/events)

The websocket gives **at-least-once**, same as the POST-with-retry path. Exactly-
once is neither needed nor attempted. Dedup is **already** handled server-side by
the persistence-layer unique keys and is unchanged:

- `traffic_reports` is idempotent on `(routerId, periodStart, mac, host.type,
  host.value)` (`docs/architecture.md §6.4`).
- `connection_events` dedups on `event_id`.

So a frame redelivered after a reconnect (the agent re-sends an un-acked spooled
frame) lands as a no-op insert — identical to a retried POST today. The optional
`seq` (§1.2) is for **observability only** (gap/duplicate logging), never for
correctness.

`ws_max_frame_bytes` (§5.5) caps a single frame; the agent splits an oversized
`UsageReport` across multiple `usage` frames, each independently idempotent.

### 5.4 Ordering

- **Usage/events:** order does not matter for correctness (idempotent upserts,
  each row self-describing with its own period/timestamp). The sidecar drains
  the spool FIFO for tidiness, but a reorder is harmless.
- **Policy:** last-write-wins by `etag` — the agent applies whichever snapshot it
  most recently received and ignores an out-of-order older one (it can tell via
  `generatedAt` / by simply only re-rendering when the etag differs from the
  applied one; a re-pushed identical etag is a no-op).

### 5.5 Heartbeat / liveness — and the "is the router connected?" signal

This is the payoff for #1023's #866/#913 motivation.

- **Heartbeat:** `ping`/`pong` every `ws_heartbeat_interval` (default **30 s**,
  pinned under Render's idle timeout by the D0 spike). Server sends `ping`, agent
  replies `pong` (and vice-versa is allowed). A missed `pong` for
  `2 × interval` → server closes the channel and deregisters it.
- **Loop tick ≠ heartbeat (#2620):** the sidecar's connected loop drains the
  outbound spool once per iteration and then blocks in the inbound `recv`. That
  `recv` timeout is `ws_poll_interval` (UCI key `wifihaven.ws.poll_interval`;
  default **1 s**, and the shipped config deliberately omits the key so
  `ws_loop.DEFAULT_POLL_INTERVAL` stays the one definition), NOT the heartbeat —
  originally they were the same value, so an event spooled just after a drain
  waited out the full 30 s heartbeat before leaving the router (the dominant hop
  in a measured ~26 s drop→SPA latency). The heartbeat keeps its own `last_ping`
  cadence, so decoupling them changes liveness not at all. The value is clamped
  into `[ws_loop.MIN_POLL_INTERVAL, ws_heartbeat_interval]`: the floor stops a
  `0` from busy-spinning the cqueues fiber, the ceiling makes "poll no slower
  than the heartbeat" the worst case (i.e. the pre-#2620 shape).
- **`last_seen_at`:** each heartbeat (and each data frame) touches
  `routers.last_seen_at` — same column the REST poll touches, so the existing
  liveness UI/alerts work for ws routers with no change.
- **The new signal:** the connection registry (§6) exposes a per-router
  **connected/not-connected** boolean (is there a live channel right now?). This
  is the single "router↔API link healthy" signal that did not exist when each
  POST/poll could fail independently. It backs a `router_connected` gauge (§7)
  and is the foundation for a future "your router went offline" alert (out of
  scope; noted for the alerting epic).

---

## 6. Server side

### 6.1 zio-http websocket endpoint + connection registry

`RouterWsRoutes.scala` (new), mounted in `Main` alongside `RouterRoutes`:

```scala
// sketch — not final code
Method.GET / "api" / "router" / "ws" ->
  handler { (req: Request) =>
    routerAuth.authenticate(req).foldZIO(            // 401 → no upgrade
      err => ZIO.succeed(ErrorMapper.errorToResponse(err)),
      router =>
        Handler.webSocket { channel =>
          for {
            _ <- registry.register(router.id, channel)      // §6.2
            _ <- pushCurrentSnapshot(channel, router)        // first policy on connect (#1849); no hello/ready handshake — §2 won't-do
            _ <- channel.receiveAll {
                   case Read(WebSocketFrame.Text(json)) => dispatch(router, json)  // demux on op
                   case Read(WebSocketFrame.Close(_,_)) => registry.deregister(router.id, channel)
                   case _                               => ZIO.unit
                 }
          } yield ()
        }.toResponse
    )
  }
```

- **`dispatch`** decodes the envelope and routes by `op` into the **existing**
  `handleUsage` / `handleEvents` / metrics-ingest / (no-op for client-side ops)
  code. There is **no second copy** of ingest logic — `RouterIngestRoutes`'s
  handlers are refactored into a transport-agnostic service both routes call
  (small extraction, sized into sub-issue A). This is the `single-source-of-
  truth` discipline applied to the transport split.
- **Connection registry** (`RouterWsRegistry`): a `Ref[Map[RouterId, Set[Channel]]]`
  (or a `Hub`/`Queue` per router for push fan-out). Keyed by `RouterId`.
  Single-process API is fine for the household model; multi-tenant pub/sub
  fan-out is explicitly out of scope (#1023).

### 6.2 Push-on-change + removing the per-poll recompute (ties to #1512)

Today every poll calls `policy.snapshot` (~500 ms recompute, #1512). Under push:

1. **A computed-snapshot cache** in `PolicyService`: `Ref[Option[(ETag,
   PolicySnapshot)]]`, populated on first build, **invalidated on any policy
   mutation** (profile/device/schedule/blocklist edit, and the time-dependent
   transitions — schedule boundary, time-limit exhaustion — which a small ticker
   re-evaluates; see #1512's note on time-dependent fields). This is filed as
   sub-issue **E** and is the concrete realization of #1512's "computed-snapshot
   cache with correct invalidation."
2. **On invalidation**, `PolicyService` rebuilds once and **publishes** the new
   snapshot to the registry, which pushes one `policy` frame to every connected
   channel. So the snapshot is computed **once per change**, not once per poll
   per router — for the single-household fleet that is the difference between
   ~500 ms every 5 s and ~500 ms only when policy actually changes.
3. **The REST poll path also reads the cache** (returns the cached snapshot /
   304), so even un-migrated HTTP agents stop paying the per-poll recompute. #1512
   is thus *partly* solved for everyone the moment E lands, independent of the
   transport. (E can ship before or after the ws endpoint — it is back-compat and
   independently valuable; sequenced after A so the registry exists to push to,
   but it improves the REST path regardless.)

> **Ticker cost note (for sub-issue E):** the time-boundary re-evaluation
> ticker re-checks only whether a schedule/time-limit transition moved the
> *single* household snapshot's etag; it must not become a per-router per-tick
> full recompute. For the single-household model the cache holds one global
> snapshot, so a tick is one cheap re-eval + (only on an etag move) one rebuild
> + fan-out — not O(routers). Spec the ticker cadence and the invalidation-vs-
> recompute boundary explicitly in E so this stays true if the model grows.

**What triggers a push:** exactly the events that move the etag today (the
`PolicyService.computeEtag` inputs) — profile/device/blocklist/schedule changes,
pause/unpause, manual block/allow, time-extension grants, and the time-boundary
ticker. The trigger is "etag changed," computed in the **one** existing place;
the registry just fans the result out. No new notion of "change" is introduced.

---

## 7. Metrics

All via `Metrics` (the `AppMetrics`/`MetricGuard` facade,
`api/src/metrics/Metrics.scala`), **bounded labels only** — never per-mac /
per-host (`docs/process/instrumentation.md`). New series (each ships with its
Grafana panel per the dashboard rule — sub-issue tasks include the panel):

**Server:**

| Metric | Type | Labels (bounded) | Meaning |
| --- | --- | --- | --- |
| `router_ws_connections_active` | gauge | — | currently-open channels. Recomputed from the registry map on every mutation (never separately incremented), and bounded to at most one channel per router since #2561 — a re-connect for an already-present router supersedes the stale channel |
| `router_ws_connections_superseded_total` | counter | — | #2561: a reconnect arrived while the server still held a channel for that router (the previous socket went half-open, so its teardown never ran). The stale channel is evicted + shut down; this counter keeps the underlying half-open rate visible rather than silently absorbed by the fix |
| `router_connected` | gauge | `router_id` (fleet-bounded) | 1/0 link-up per router (§5.5) — emitted only for currently-connected routers and **aged out on deregister** so the `router_id` series doesn't accumulate stale values (impl: sub-issue A) |
| `router_ws_frames_total` | counter | `op`, `dir` (`in`/`out`), `result` (`ok`/`reject`/`unknown_op`) | frame throughput + the unknown-op forward-compat counter |
| ~~`router_ws_handshake_total`~~ | — | — | **NOT BUILT** — handshake won't-do (§2 status, #1847) |
| `router_ws_policy_push_total` | counter | `result` (`ok`/`dropped_full`/`channel_closed`) | push fan-out health |
| `router_transport` | counter/gauge | `transport` (`ws`/`http`) | rollout-progress dashboard (§3.2) |
| `policy_snapshot_build_total` | counter | `result` (`computed`/`cache_hit`) | proves the #1512 cache is working |

**Agent** (folded into the existing `/metrics` push registry, `metrics.lua`):

| Metric | Labels | Meaning |
| --- | --- | --- |
| `ws_connect_total` | `result` (`ok`/`upgrade_fail`/`auth_fail`/`timeout`) | reconnect health |
| `ws_state` | — (gauge) | 1 connected / 0 disconnected |
| `ws_fallback_total` | `result` (`to_http`/`back_to_ws`) | how often the agent fell back (§3.1) |
| `ws_frames_sent_total` / `ws_frames_recv_total` | `op` | agent-side throughput |

The `op`, `dir`, `result`, `transport` label spaces are small fixed enums —
matching the existing bounded-label discipline in `Metrics.scala`.

---

## 8. Open questions / risks (carried into the spike)

1. **Lua ws library viability (D0)** — the gating risk; see §3.4. If unresolved,
   ship A+B only and keep the agent on HTTP.
2. **Render ws idle-timeout / max-frame** — confirmed-supported, exact limits
   pinned by D0; heartbeat cadence (§5.5) and `ws_max_frame_bytes` (§5.3) depend
   on the answer.
3. **Observability parity** — per-POST error logging is replaced by per-frame
   structured logging (`op`/`router_id`/`result` on the MDC, mirroring the
   existing `LogContext` annotations) so the debuggability #1017/#1334 relied on
   is retained. Built into sub-issue A.
4. **Single-process push fan-out** — fine for one household; explicitly not
   multi-tenant (would need a pub/sub layer). Documented limit, not a TODO.

---

## 9. Phased rollout & implementation sub-issues

Each sub-issue is sized to **ship independently and back-compat** (REST stays
live throughout; the ws endpoint is purely additive until the final deprecation
step). Ordering is server-first (low risk, exercisable by a test client) →
agent (gated on the spike) → optimization → deprecation.

Filed: D0 → [#1845](https://github.com/wifihaven/wifihaven/issues/1845),
A → [#1846](https://github.com/wifihaven/wifihaven/issues/1846),
B → [#1847](https://github.com/wifihaven/wifihaven/issues/1847) **(closed
won't-do 2026-06-23 — see §2 status)**,
C → [#1848](https://github.com/wifihaven/wifihaven/issues/1848),
E → [#1849](https://github.com/wifihaven/wifihaven/issues/1849),
G → [#1850](https://github.com/wifihaven/wifihaven/issues/1850).
F (deferred #376) is not filed yet — it is created when the first breaking
snapshot-shape change needs the version machinery. (Originally this doc said F
"uses B's handshake"; with B dropped, F would land both the version machinery
and whatever minimal handshake it actually needs at that point.)

| # | Sub-issue | Independently shippable? | Back-compat |
| --- | --- | --- | --- |
| **D0** | **Spike: Lua websocket library viability on OpenWRT** — pick/prove a library, long-soak a TLS ws on real/qemu hardware, confirm Render limits. Gate for C. | yes (spike, no prod code) | n/a |
| **A** | **API `/api/router/ws` endpoint + envelope demux + connection registry** — `RouterWsRoutes`, extract transport-agnostic ingest service from `RouterIngestRoutes` (no behavior change), per-frame structured logging, server metrics (§7). REST untouched. | yes | additive — new route only |
| **B** | ~~**Capability handshake + `snapshotVersion` + Evolution-policy doc** — `hello`/`ready` frames, `min`-version rule, ignore-unknown rules.~~ **WON'T-DO ([#1847](https://github.com/wifihaven/wifihaven/issues/1847) closed 2026-06-23, see §2 status).** ws inherits the REST additive + ignore-unknown contract verbatim; the handshake gated zero behavior. Unknown-`op` forward-compat shipped in #1846. Version machinery deferred to F against a concrete need. | n/a (not built) | n/a |
| **C** | **Agent websocket sidecar (`wifihaven-ws`)** — new procd instance, ws client over the proven library, drains existing tmpfs spools out / writes pushed `policy` in, HTTP-fallback wiring (§3.1), agent metrics. Gated on D0. **SHIPPED ([#1848](https://github.com/wifihaven/wifihaven/issues/1848)):** `wifihaven-ws` is a default-off (`wifihaven.ws.enabled=0`) procd instance built on the spike-proven `ws_client.lua` driven by `ws_loop.lua`; it reconnects with exp backoff+jitter (§5.1), heartbeats (§5.5), drains the agent's outbound usage/events bodies over a bounded tmpfs spool (the agent tees them when ws is healthy, else POSTs as before), splits an oversized `UsageReport` across `usage` frames (§5.3/#1017), writes a pushed `policy` to the snapshot file (dormant until the server push lands in #1849), and folds `ws_connect_total`/`ws_state`/`ws_fallback_total`/`ws_frames_{sent,recv}_total` into the `/metrics` push (§7). The main agent's HTTP poll/POST path is byte-for-byte unchanged with the flag off. `cqueues` + `luaossl` became hard package DEPENDS in [#2036](https://github.com/wifihaven/wifihaven/issues/2036), and [#2608](https://github.com/wifihaven/wifihaven/issues/2608) made ws the **default** transport (unset ⇒ on) with a marker-guarded upgrade migration, so a fresh install comes up on ws with no `apk add` and no UCI step. | yes (behind UCI flag; default off until proven, default ON as of #2608) | additive — main agent HTTP path unchanged |
| **E** | **Push-on-change + computed-snapshot cache in `PolicyService`** (#1512) — cache + invalidation + time-boundary ticker; registry push on change; REST poll also reads the cache. | yes (improves REST path even with zero ws agents) | behavior-preserving (same snapshot bytes) |
| **F** | **(deferred #376) per-field snapshot capability gating + `snapshotVersion ≥ 2` machinery** — only when the first breaking shape change needs it. (B is won't-do, so F lands both the version machinery **and** whatever minimal handshake it actually needs at that point — it no longer builds on a B handshake.) | yes, later | additive |
| **G** | **Deprecate the REST poll** — after the operator confirms the ws path healthy across the fleet (the `router_transport` dashboard shows 100% ws), add a one-release deprecation log to the REST poll, then remove in a later release per the wire-contract deprecation window. | yes, last | the *only* non-additive step, gated on fleet rollover |

**Critical path:** D0 → C (agent). **Parallel track:** A (server endpoint; B the
handshake is won't-do, §2) and E (perf) can land while D0 runs, since they are
valuable even if the agent never migrates. **G is last and operator-gated** —
never armed automatically
(`docs/pr-review-checklist.md#monitor-to-merged` discipline: the cutover is the
operator's call).
