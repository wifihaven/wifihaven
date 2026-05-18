# API wire-schema versioning

Tracks: [#597](https://github.com/wifihaven/wifihaven/issues/597). Related: [#498](https://github.com/wifihaven/wifihaven/issues/498) (manual deploy gate), [#376](https://github.com/wifihaven/wifihaven/issues/376) (full capability negotiation), [#251](https://github.com/wifihaven/wifihaven/issues/251) (cloud-deploy umbrella).

## What `wireSchema` is

`wireSchema` is a monotonically-increasing integer that identifies the JSON shape of the cloud API ↔ router-agent wire contract. It is **one** number that covers every router-facing endpoint as a single unit:

- `GET /api/router/policy`
- `POST /api/router/events`
- `POST /api/router/usage`

When a router agent boots, it hits `GET /api/version` and compares the API's `wireSchema` against its own baked-in `WIRE_SCHEMA`. A mismatch logs a loud warning but does **not** stop the agent — the safety net for breaking changes is the manual deploy gate in #498, not a runtime refusal. Full capability negotiation (per-feature flags, graceful degradation) is the long-term path in #376; this issue is the minimum viable bridge until then.

The `/api/version` endpoint itself is intentionally not versioned (chicken-and-egg). Treat it as v0-forever — additions are allowed; removals or rename of `apiBuild` / `wireSchema` are not.

## What counts as a breaking change

**Bump required:**

- Removing or renaming a field on any router-facing endpoint.
- Changing the semantic meaning or type of an existing field (e.g. seconds → milliseconds, string → int).
- Changing required-vs-optional on a field the agent reads.
- Restructuring response/request envelopes.

**No bump required:**

- Adding a new optional field that older agents/APIs can ignore.
- Internal refactors that don't change JSON wire shape.
- Changes to non-router endpoints (dashboard, admin, auth) — those aren't covered by `wireSchema`.

When in doubt, bump. The cost of a spurious bump is one warning log line during a transitional deploy; the cost of a missed bump is silent breakage that takes a customer-side outage to detect.

## How to bump

There are two constants that must move in lockstep:

1. `shared/src/WireSchema.scala` — `object WireSchema { val Current: Int = N }`
2. `openwrt/files/usr/lib/lua/wifihaven/version.lua` — `M.WIRE_SCHEMA = N`

**Mechanism for keeping them in sync: manual, with a runtime safety net.** We chose manual over auto-generation because:

- The two constants live in different build systems (Mill / OpenWRT-feed). Auto-generation would require either Mill to drive the OpenWRT package build (it doesn't today) or a shared text file that both build systems read at build time (extra moving parts).
- Wire-shape changes already require coordinated edits on both sides (request/response handlers in Scala, parsers in Lua). Bumping a constant next to those edits is a one-line addition, not a workflow change.
- If someone forgets to bump one side, the agent's startup mismatch warning surfaces the drift on the next deploy. That warning is the load-bearing piece — the dual constant is just a way to detect drift visibly.

Steps for a bump:

1. Edit `WireSchema.scala` and `version.lua` in the same PR. Increment both by 1.
2. Add a new row to the compatibility matrix below.
3. Coordinate the API and agent deploys atomically through the manual gate in #498 — don't ship one without the other. The gate is where this coordination lives; this doc is just the matrix it consults.

## `apiBuild`

The `apiBuild` field carries the short git sha of the API jar at build time. It is informational only — the agent does not gate behavior on it. Sources, in priority order:

1. The `WIFIHAVEN_BUILD_SHA` env var (set by CI / Docker build).
2. `git rev-parse --short HEAD` in the Mill workspace.
3. Literal `"dev"` for builds with no git context (tarball release, local clone without `.git`).

Injection is at jar-build time via a Mill `generatedSources` task (`build.mill` → `buildInfoSource`), so the value is baked into the jar — no runtime env coordination required.

## Compatibility matrix

| `wireSchema` | API versions | Compatible agent versions | Notes |
|---|---|---|---|
| 1 | initial (post-#597) | initial (post-#597) | starting state — single wire shape spanning policy / events / usage |

When you add a row, include the API release tag / commit it shipped in and the corresponding agent .ipk / .apk version. Old rows are kept for the lifetime of the oldest deployed agent — once every router has rolled past a schema, that row may be deleted.

## See also

- [#498](https://github.com/wifihaven/wifihaven/issues/498) — manual deploy gate; this is where the API and agent bumps get coordinated atomically.
- [#376](https://github.com/wifihaven/wifihaven/issues/376) — full capability-negotiation framework, deferred until pre-v1.0. Replaces this single-integer scheme with per-feature negotiation.
- [#251](https://github.com/wifihaven/wifihaven/issues/251) — cloud-deploy umbrella.
