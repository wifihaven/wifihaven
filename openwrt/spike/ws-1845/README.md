# Spike #1845 — Lua websocket client viability on OpenWrt

Gates the agent-side websocket work in epic
[#1023](https://github.com/wifihaven/wifihaven/issues/1023) (see
`docs/design/websocket-transport.md` §3.4, sub-issue **D0**). This is a
**spike** — throwaway proof code, not shipped to the router image. The verdict
and full evidence live in
[`docs/design/ws-lua-spike-findings.md`](../../../docs/design/ws-lua-spike-findings.md).

## Verdict: **GO** — `cqueues` + `luaossl` + a hand-rolled RFC 6455 client.

`cqueues` (the async event loop the agent currently lacks) and `luaossl` (TLS)
are both in the official OpenWrt feeds for Lua 5.1, on both the 23.05 `.ipk`
generation and the snapshot/25.12 `.apk` generation. No packaged Lua websocket
*library* exists, so we hand-roll the (small, stable) RFC 6455 client framing
over the cqueues TLS socket rather than vendoring `lua-http`'s six-package
dependency tree.

## Files

| File | Role | Runs on |
| --- | --- | --- |
| `ws_frame.lua` | Pure RFC 6455 framing (encode/decode/mask/handshake-key). No I/O, no deps. | anywhere (Lua 5.1 / 5.4 / 5.5) |
| `ws_crypto.lua` | SHA-1 / Base64 / random — `pure()`, `luaossl()`, `openssl()` backends, dependency-injected into `ws_frame`. | anywhere |
| `ws_client.lua` | cqueues-backed wss client: connect → TLS → handshake → send/recv → ping/pong → drop detection. | Linux / router (needs cqueues + luaossl) |
| `echo_server.lua` + `run_echo_server.lua` | Minimal cqueues RFC 6455 echo *server* — a controlled loopback peer for the harness. | Linux / router |
| `poc_echo.lua` | Runnable end-to-end demo (connect, hello, echo, heartbeat, reconnect-backoff). | Linux / router |
| `../../test/ws_frame_spec.lua` | busted unit tests for `ws_frame` against RFC 6455 §1.3/§5.7 vectors. Wired into `openwrt/test/run_tests.sh`. | dev host (no native deps) |

## Run the framing unit tests (no native deps — this is what CI runs)

```sh
cd openwrt
LUA_PATH="./files/usr/lib/lua/?.lua;./files/usr/lib/lua/wifihaven/?.lua;./spike/ws-1845/?.lua;./test/shim/?.lua;$(lua -e 'print(package.path)')" \
  busted test/ws_frame_spec.lua
# 17 successes / 0 failures
```

## Run the live POC (needs cqueues + luaossl)

```sh
# OpenWrt target:
#   23.05 (ipk):   opkg update && opkg install cqueues luaossl
#   snapshot(apk): apk add cqueues luaossl
# Linux dev box (Lua 5.1–5.4; NOT 5.4-only cqueues won't build on 5.5):
#   luarocks install cqueues luaossl

# against a public echo (needs working DNS — cqueues uses its own resolver):
lua poc_echo.lua wss://echo.websocket.events

# fully offline against the bundled echo server (two terminals):
lua run_echo_server.lua 8870           # terminal 1
lua poc_echo.lua ws://127.0.0.1:8870   # terminal 2
```

## What was validated where

**Validated locally (this spike):**
- Framing is wire-correct against RFC 6455 vectors (17/17 busted tests).
- Package availability + TLS API on both feed generations (see findings doc).
- **Handshake + client→server frames against an independent impl:** `websocat`
  (Rust) accepted our `Sec-WebSocket-Key` (replied 101) and decoded our masked
  text frame.
- **Full duplex against an independent impl:** a `websocat` *client* round-trips
  through our `echo_server.lua` — our handshake reply, masked-frame `decode`,
  and unmasked-frame `encode` all interoperate.

**MUST be validated on a real OpenWrt/Linux target (the D0 hardware step):**
- The Lua **client's reactive receive loop**. Under the macOS *luarocks*-built
  cqueues + brew-openssl in the sandbox, a read waiting for not-yet-arrived
  bytes can return an immediate `ETIMEDOUT` even after `cqueues.poll` signals
  readability — a kqueue/timeout-integration artifact of that build (the raw
  socket I/O works, proven by the websocat round-trip). Re-run `poc_echo.lua`
  against the *packaged* cqueues (epoll) on the router / a Linux box.
- **Multi-hour TLS soak** for memory growth / fd leaks (RSS over a wss kept warm
  by 30 s heartbeats for hours).
- **`wss://api.wifihaven.net` against Render** to pin the idle-timeout and
  max-frame limits empirically.
