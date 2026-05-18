-- wifihaven.version — wire-protocol schema version baked into the agent (#597).
--
-- WIRE_SCHEMA is an integer that must match the value returned by the cloud
-- API's GET /api/version endpoint. Bump it whenever the JSON shape of any
-- router-facing endpoint (/api/router/policy, /api/router/events,
-- /api/router/usage, ...) changes in a way that an older API cannot serve
-- safely. Additive, optional fields do not require a bump.
--
-- This constant is MANUALLY kept in sync with `WireSchema.Current` in
-- shared/src/WireSchema.scala. See docs/api-versioning.md for the bump
-- procedure and the compatibility matrix. The agent's startup mismatch
-- warning (see wifihaven-agent) is the safety net but not a substitute for
-- coordinated bumps — see #498 (manual gate) and #376 (full capability
-- negotiation).

local M = {}

M.WIRE_SCHEMA = 1

-- Compare a remote wire-schema value against the agent's baked-in WIRE_SCHEMA
-- and emit an appropriate log line. Returns true on match, false on mismatch.
-- Pulled out so it can be unit-tested without spinning up the full agent.
function M.compare_wire_schema(remote, log)
  if remote == M.WIRE_SCHEMA then
    log.info("wireSchema match: %d", M.WIRE_SCHEMA)
    return true
  else
    log.warn("wireSchema MISMATCH: agent=%d, api=%s — incompatible behavior possible",
             M.WIRE_SCHEMA, tostring(remote))
    return false
  end
end

return M
