# Spike findings — Lua websocket client viability on OpenWrt (#1845 / D0)

Status: **complete**. Gates the agent-side websocket work
([#1848](https://github.com/wifihaven/wifihaven/issues/1848), sub-issue C) of
epic [#1023](https://github.com/wifihaven/wifihaven/issues/1023). See
[`docs/design/websocket-transport.md`](websocket-transport.md) §3.4 for the
question this answers. POC code: [`openwrt/spike/ws-1845/`](../../openwrt/spike/ws-1845/).

---

## Verdict: **GO**

Build the agent ws client (#1848) on **`cqueues` (event loop + TLS socket) +
`luaossl` (TLS context / SHA-1 / CSPRNG) + a hand-rolled RFC 6455 client framing
layer**. Do **not** adopt `lua-http`/`lua-websockets` — they are not in the
OpenWrt feed and pull a heavy dependency tree we don't need.

One caveat carried into #1848's first task: the **reactive receive loop** still
needs a green run on a real OpenWrt/Linux target (the macOS dev build has a
poll/timeout I/O quirk — §5). Everything else is proven.

---

## 1. Why this combination

The dominant constraint (`websocket-transport.md` §0.1) is that the agent **has
no event loop** — it is a single foreground `conntrack -E` reader with
`curl`-per-call I/O. A persistent bidirectional socket needs an async scheduler
in a sidecar process.

`cqueues` *is* that scheduler: "embeddable asynchronous networking, threading,
and notification framework for Lua." It bundles a non-blocking sockets layer
with DNS, buffering, and TLS, plus a coroutine controller (`cqueues.poll`). So
it solves the event-loop problem **and** the socket problem in one packaged
dependency — which is exactly the sidecar shape §3.1 describes.

The missing piece is websocket *framing*. There is **no** packaged Lua
websocket library (§2), but RFC 6455 *client* framing is small and stable
(masking, a handful of opcodes, one SHA-1 handshake). Hand-rolling it
([`ws_frame.lua`](../../openwrt/spike/ws-1845/ws_frame.lua), ~180 lines, pure,
unit-tested) is far cheaper than vendoring `lua-http`'s tree (`luaossl`,
`basexx`, `lpeg`, `lpeg_patterns`, `binaryheap`, `fifo`), most of which is not
packaged for OpenWrt. This also matches the repo's existing pattern — `quic.lua`
hand-rolls QUIC/TLS parsing over injected crypto for the same reasons.

## 2. Package availability per target

Checked against the live OpenWrt feeds.

| Package | 23.05 (`.ipk`, x86_64 release feed) | snapshot / 25.12 (`.apk`, aarch64) | Lua 5.1 | Notes |
| --- | --- | --- | --- | --- |
| `cqueues` | ✅ `cqueues_20200726-1` | ✅ `cqueues-20200726-r2` (+ `-lua5.3`, `-lua5.4` variants) | ✅ | event loop + non-blocking sockets + TLS glue; ~131 KiB installed; 29 archs |
| `luaossl` | ✅ `luaossl_20220711-1` | ⚠️ not seen in the aarch64 snapshot index probed | ✅ | provides `openssl.ssl.context` (TLS), `openssl.digest` (SHA-1), `openssl.rand` (CSPRNG) — cqueues' TLS dependency |
| `lua-openssl` (zhaozg) | ✅ `lua-openssl_0.8.2-1-1` | — | ✅ | **already in the agent `DEPENDS`**; different API (`require("openssl")`). Usable for handshake crypto, but the TLS *context* still needs luaossl |
| `lua-http` / `lua-websockets` | ❌ | ❌ | — | not in either feed; would need vendoring + its dep tree |

**Action for #1848:** confirm `luaossl` (and the right `cqueues` variant) for the
**router's actual arch** in **both** the ipk and apk feeds before adding to
`openwrt/Makefile` `DEPENDS`. luaossl was present in the 23.05 x86_64 release
feed but not spotted in the aarch64 *snapshot* index probed here — verify per
target arch. If luaossl is missing for an arch, the fallbacks are (a) the
agent's existing `lua-openssl` for the digest/rand half + cqueues' own TLS, or
(b) building luaossl into the feed.

## 3. TLS (wss) story

Confirmed working API (`ws_client.lua`):

```lua
local socket  = require("cqueues.socket")
local context = require("openssl.ssl.context")   -- from luaossl
local sock = socket.connect{ host = host, port = 443 }
local ctx  = context.new("TLS", false)           -- client context
ctx:setVerify(context.VERIFY_PEER)                -- verify server chain
sock:starttls(ctx)                                -- TLS handshake
```

- TLS is terminated by cqueues+luaossl over the platform OpenSSL/mbedTLS the
  image already ships (`libustream-mbedtls` / `openssl-util` are in `DEPENDS`).
- `starttls` succeeded against a live external host in testing (before sandbox
  DNS blocked further external calls).
- **Cert verification:** v1 should `VERIFY_PEER` against the system CA bundle.
  Note `wss://api.wifihaven.net` is a public Let's Encrypt cert, so the default
  trust store validates it — no pinning needed for v1.

## 4. Footprint

- `cqueues` ~131 KiB installed + `luaossl` (similar order) — both link the
  platform OpenSSL already present for the block-page TLS listener, so the
  marginal cost is the two Lua C-extension `.so`s, not a second TLS stack.
- `ws_frame.lua` / `ws_crypto.lua` are pure Lua, negligible.
- Acceptable for the router image; comparable to the `lua-openssl` (~464 KiB,
  per the Makefile note) the agent already carries for QUIC SNI.

## 5. What was proven, and the one open item

**Proven locally:**
1. **Framing wire-correctness** — 17/17 busted tests against RFC 6455 §1.3
   (handshake accept-key) and §5.7 (masked/unmasked frame byte vectors), plus
   extended-length (16/64-bit) and partial-buffer/multi-frame decode.
   (`openwrt/test/ws_frame_spec.lua`, runs in CI via `run_tests.sh`.)
2. **TLS connect + handshake** via cqueues + luaossl (§3).
3. **Interop against an independent implementation (`websocat`, Rust), both
   directions:**
   - websocat *as server* accepted our `Sec-WebSocket-Key` (replied 101) and
     decoded our masked text frame ("incoming text") → our client→server path.
   - websocat *as client* fully round-tripped through our `echo_server.lua`
     (our handshake reply + masked-frame `decode` + unmasked-frame `encode` all
     interoperate) → our full duplex framing.
4. **Clean drop detection** — `recv` returns a distinct reason
   (`closed`/`eof`/`timeout`/error) so the sidecar's backoff loop (§5.1) can act.
5. **Ping/pong + reconnect backoff** scaffolding (`poc_echo.lua`).

**Open item (must validate on hardware — folds into #1848's first task):**
- The **Lua client's reactive receive** (waiting for bytes that arrive *after*
  the read is issued). Under the macOS *luarocks*-built cqueues + brew-openssl
  in the dev sandbox, such a read can return an immediate `ETIMEDOUT` even after
  `cqueues.poll` reports readability. The underlying socket I/O is fine (the
  websocat↔echo_server round-trip and raw reactive socket tests both pass), so
  this is a kqueue/timeout-integration artifact of that specific build, **not**
  a protocol or library-availability problem. Re-run `poc_echo.lua` against the
  **packaged** cqueues (epoll) on the router or a Linux box — expected to pass.

## 6. Render limits (heartbeat / max-frame inputs)

- Render **does not impose a fixed ws idle timeout**, but connections drop on
  instance replacement (deploys) and community reports note ~5-min idle drops
  through the proxy; Render's own guidance is to send periodic keepalives.
- **No documented max-frame-size limit** beyond standard proxy behavior.

**Pins for the design:** the §5.5 default **30 s `ping`/`pong` heartbeat is
comfortably under any observed idle window** — keep it. Set a conservative
`ws_max_frame_bytes` (e.g. 64 KiB) and rely on the §5.3 usage-frame splitting;
no Render hard cap forces a specific value. Confirm both empirically against
`wss://api.wifihaven.net` during the hardware soak.

## 7. Recommendation for #1848 (agent ws sidecar)

1. Add `cqueues` + `luaossl` to `openwrt/Makefile` `DEPENDS` (after the per-arch
   feed confirmation in §2).
2. Promote `ws_frame.lua` / `ws_crypto.lua` from the spike into
   `files/usr/lib/lua/wifihaven/` essentially as-is (pure, tested).
3. Build the `wifihaven-ws` sidecar as a new procd instance
   (`websocket-transport.md` §0.1/§3.1) whose cqueues controller runs the
   `ws_client.lua` loop, drains the existing tmpfs spools out, and writes pushed
   `policy` frames to the snapshot file the main agent already reads.
4. **First task of #1848: the hardware-validation step** — green
   `poc_echo.lua` against packaged cqueues, a multi-hour TLS soak (watch RSS/fds),
   and a real `wss://api.wifihaven.net` connection to pin Render limits (§6).
5. UCI flag, default off until the soak passes (§3.1).

**If the hardware receive validation unexpectedly fails**, the epic still ships
the server endpoint + handshake (#1846/#1847) and the agent stays on HTTP
polling — exactly the A+B-only fallback §3.4 describes.
