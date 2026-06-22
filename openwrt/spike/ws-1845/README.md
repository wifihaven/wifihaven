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

# offline wss:// (real TLS) with a self-signed cert:
openssl req -x509 -newkey rsa:2048 -keyout key.pem -out cert.pem -days 2 -nodes -subj /CN=localhost
lua run_echo_server.lua 8970 tls cert.pem key.pem        # terminal 1
WS_INSECURE=1 lua poc_echo.lua wss://localhost:8970      # terminal 2 (insecure = self-signed)
```

> **No-root install recipe** (how this was validated on Ubuntu without sudo):
> `apt-get download lua5.1 liblua5.1-0 lua-cqueues lua-luaossl` then
> `dpkg-deb -x <deb> <prefix>` each, and point `LUA_PATH`/`LUA_CPATH`/`LD_LIBRARY_PATH`
> at `<prefix>`. The `.so`s link the system OpenSSL.

## What was validated

**On a real Lua 5.1 + distro-packaged cqueues/luaossl target** (Ubuntu 24.04,
epoll — the package versions match the OpenWrt feed):
- `poc_echo.lua` PASS over **ws://** loopback (connect → handshake → hello →
  echo received → ping/pong → clean close + drop detection).
- `poc_echo.lua` PASS over **wss://** loopback with real TLS (self-signed cert) —
  the same full cycle over an encrypted channel.

This run **found and fixed three real Lua-5.1/cqueues bugs** the macOS dev build
had masked (see findings doc §5.1): the HTTP-upgrade CR-before-blank header parse
(poisoned the read buffer), cqueues' unchecked-error-limit abort on idle
heartbeats (needs `clearerr`), and `pcall`-across-yield breaking TLS `starttls`
on Lua 5.1 (needs a returning `onerror` + no pcall around yielding I/O).

**Also validated:**
- Framing wire-correct against RFC 6455 vectors (17/17 busted tests, in CI).
- Package availability + TLS API on both feed generations (see findings doc).
- Interop with `websocat` (independent Rust impl), both directions.

**Still pending (needs the deployed server endpoint, not this spike):**
- **Multi-hour TLS soak** for memory growth / fd leaks (RSS over a wss kept warm
  by 30 s heartbeats for hours).
- **`wss://api.wifihaven.net` against Render** to pin the idle-timeout and
  max-frame limits empirically.
