-- ws_outbound.lua — agent-side outbound tee for the ws transport (#1848).
--
-- The main agent's loop is unchanged; this is the one additive, DEFAULT-OFF seam
-- it gains. It wraps the agent's `http_post` so that, ONLY when the ws sidecar is
-- enabled AND the link is healthy, an outbound usage/events body is handed to the
-- sidecar via the bounded spool (the sidecar frames + sends it) instead of being
-- POSTed here. In every other case it is a pure pass-through to the real
-- http_post, so with ws off (the shipped default) the agent's HTTP path is
-- byte-for-byte unchanged (back-compat, design §3.1).
--
-- "Healthy" = the sidecar's ws-health sentinel (paths.ws_health) carries a
-- timestamp within fallback_after of now. The sidecar writes os.time() into the
-- sentinel on every successful send/recv and removes it on disconnect; reading
-- the integer avoids `stat -c %Y`, which stock busybox lacks (the agent already
-- avoids it elsewhere). A stale/absent sentinel → the link is down past the
-- fallback window → resume HTTP so enforcement/telemetry never stalls on a
-- flapping socket.
--
-- Only `usage` and `events` are teed. The metrics push stays on HTTP always, so
-- the ws_* observability series reach the server even while the socket is down.

local M = {}

-- make(opts) → post_fn(url, body, headers) → status, resp_body, err
--
-- opts:
--   enabled        bool                  — wifihaven.ws.enabled
--   http_post      fn(url,body,hdrs)     — the real curl post (the fall-through)
--   spool_append   fn(line) → ok         — append one bounded frame line
--   health_read    fn() → number|nil     — the sentinel timestamp (or nil)
--   now            fn() → number         — os.time
--   fallback_after number                — seconds
--   metrics_inc    fn(result)?           — optional tee outcome meter
function M.make(opts)
  local http_post     = opts.http_post
  local spool_append  = opts.spool_append
  local health_read   = opts.health_read
  local now           = opts.now
  local fallback_after = opts.fallback_after
  local metrics_inc   = opts.metrics_inc or function() end

  return function(url, body, headers)
    if not opts.enabled then
      return http_post(url, body, headers)
    end
    -- Only usage/events are bridged; the trailing path segment is the op.
    local op = url:match("/api/router/(usage)$") or url:match("/api/router/(events)$")
    if not op then
      return http_post(url, body, headers)
    end
    -- Fall back to HTTP if the link has been down past the fallback window.
    local h = health_read()
    if not h or (now() - h) > fallback_after then
      metrics_inc("http_fallback")
      return http_post(url, body, headers)
    end
    -- Healthy: hand the body to the sidecar as one frame. The body is already a
    -- JSON object string, so wrapping it as the frame payload is a concat — no
    -- re-encode. A synthetic 200 tells the caller's retry queue the body is now
    -- owned by the sidecar (at-least-once; the sidecar re-sends on reconnect and
    -- the server dedups). If the spool write itself fails, fall back to HTTP so
    -- the datum is never silently dropped.
    local line = '{"op":"' .. op .. '","payload":' .. body .. '}'
    if spool_append(line) then
      metrics_inc("spooled")
      return 200, "", nil
    end
    metrics_inc("spool_failed")
    return http_post(url, body, headers)
  end
end

return M
