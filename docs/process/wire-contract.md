# Wire contract — backwards compatibility

This was originally in AGENTS.md §"Backwards compatibility"; see AGENTS.md for the TOC.

## Backwards compatibility

**WifiHaven is deployed to prod, so this policy is now IN EFFECT.** The
API and the agents deploy **independently** — there is no longer a tandem
deploy that lets you change both sides of the wire at once. A snapshot the
API emits today may be parsed by an older already-deployed agent, and an
event an older agent posts must still be accepted by a newer API. So the
router↔API request/response shapes (and the policy snapshot in particular)
are a **public contract**.

Rules for any change to a wire-visible shape (API request/response bodies,
the policy snapshot, the usage/event ingest payloads):

- **Additive only.** New fields are fine; renaming, removing, retyping, or
  changing the meaning of an existing field is not.
- **Ignore unknown fields on input.** Both sides must tolerate fields they
  don't recognize, so a newer peer can add fields without breaking an older
  one.
- **Deprecation windows for removals.** To drop a field, stop relying on it,
  ship that, wait for the fleet to roll forward, then remove it in a later
  change — never in the same step.
- **The API may still change freely** as long as it stays backwards
  compatible with already-deployed agents under the rules above.

Non-additive / breaking wire changes are gated on **wire versioning and
capability negotiation** ([#376](https://github.com/wifihaven/wifihaven/issues/376)):
that mechanism is what will eventually let the two sides agree on a shape
before using it. The durable, minimal slice of that mechanism shipped with the
websocket transport handshake (#1847) — see the Evolution policy below. Until a
breaking change actually needs the `snapshotVersion ≥ 2` translation machinery
(filed as the deferred #376 follow-up), treat breaking wire changes as off the
table.

## Evolution policy (#376 / #1847)

The websocket transport ([`docs/design/websocket-transport.md`](../design/websocket-transport.md) §2)
adds a capability handshake on the `GET /api/router/ws` connection. This is the
durable half of #376 — the rules below are now part of the wire contract.

**The handshake.** Immediately after the ws upgrade, the agent sends a `hello`
frame and the server replies `ready`:

```jsonc
// agent → server
{ "op": "hello", "payload": {
    "agentCapabilities": ["ws-transport-v1"],   // string set; the extension point
    "snapshotVersion": 1,                        // agent's max-known PolicySnapshot shape
    "agentVersion": "0.3.1"                       // observability only
} }
// server → agent
{ "op": "ready", "payload": {
    "serverCapabilities": ["ws-transport-v1", "ack-frames"],  // the server's own set
    "snapshotVersion": 1                                       // = min(agent.maxKnown, server.maxKnown)
} }
```

The rules a conforming peer MUST follow:

- **Ignore-unknown — envelope.** A receiver MUST ignore (and meter) a frame
  whose `op` it does not recognize. New ops are additive; this is what lets a
  future op (e.g. `policy_diff`) ship without a flag day.
- **Ignore-unknown — payload.** Both sides MUST ignore unknown JSON fields in a
  payload. New payload fields are additive. (Already the case — zio-json's
  derived decoders drop extras and the Lua `jsonc` decoder ignores them.)
- **`min`-version rule.** The negotiated `snapshotVersion` is
  `min(agent.maxKnown, server.maxKnown)`. The server emits snapshots at that
  version. So a newer agent that knows a higher version polling an older server
  is handed the older shape, and the next breaking shape change is a version
  bump rather than a flag day. `snapshotVersion` starts at **1** (today's shape)
  and bumps **only** on a breaking snapshot-shape change.
- **Version-ceiling refusal.** An agent MUST refuse a snapshot whose negotiated
  `snapshotVersion` exceeds its max-known (it falls back to its last-good cached
  snapshot per [`docs/resilience.md`](../resilience.md) §1). The `min` rule means
  this cannot happen in normal operation, but it is stated so a server bug can't
  silently push a newer shape to an older agent. Symmetrically, the **server**
  refuses a `hello` whose advertised `snapshotVersion` is below its floor (no
  shape both ends understand) by closing the socket with code `4003
  version-exceeded`.
- **Handshake-required.** If the agent never sends `hello`, the server closes
  the socket with code `4002 hello-required` after a timeout and the agent falls
  back to HTTP polling.

**Why this is additive and safe.** `snapshotVersion`, `serverCapabilities`, and
`agentCapabilities` live **only** inside the new `hello`/`ready` frames on the
new ws endpoint. They do not touch the existing `PolicySnapshot` JSON or any REST
body, so no already-deployed agent parses anything new — the REST poll path is
byte-identical. The machinery sits dormant until the first real shape change
needs it.

**Scope of #1847 (this slice).** Only "can this pair speak the ws transport, and
at what snapshot version?" is negotiated. **Per-field** capability gating of the
snapshot (e.g. `"unmanaged-mac-policy"`, `"https-block-page"`) and the
`snapshotVersion ≥ 2` shape-translation machinery are deferred to the #376
follow-up that *uses* this handshake — they are not needed while the payload is
frozen.

Surfaces that are **not** part of the cross-process wire contract — UCI keys
written and read by the same agent build, CLI flags, and DB schema (guarded
separately by Flyway migrations) — can still change without a deprecation
window, but coordinate them within their own component.

(The flip was driven by the actual prod deploy, not by the permanent-name
decision [#38](https://github.com/wifihaven/wifihaven/issues/38).)
