package wifihaven.shared

/**
 * Single source of truth for the API ↔ router-agent wire-protocol schema version.
 *
 * Bump [[Current]] whenever the JSON shape of any router-facing endpoint
 * (`/api/router/policy`, `/api/router/events`, `/api/router/usage`, …) changes in a way that an
 * older agent cannot parse safely. Additive, optional fields do not require a bump.
 *
 * When you bump this constant you MUST also bump the matching Lua constant in
 * `openwrt/files/usr/lib/lua/wifihaven/version.lua` and add a new row to the compatibility matrix
 * in `docs/api-versioning.md`. The agent's startup mismatch warning is the safety net but is not
 * a substitute for coordinated bumps — see #498 (manual gate) and #376 (full capability
 * negotiation).
 */
object WireSchema {
  val Current: Int = 1
}
