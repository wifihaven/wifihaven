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
before using it. Until #376 lands, treat breaking wire changes as off the
table.

Surfaces that are **not** part of the cross-process wire contract — UCI keys
written and read by the same agent build, CLI flags, and DB schema (guarded
separately by Flyway migrations) — can still change without a deprecation
window, but coordinate them within their own component.

(The flip was driven by the actual prod deploy, not by the permanent-name
decision [#38](https://github.com/wifihaven/wifihaven/issues/38).)
