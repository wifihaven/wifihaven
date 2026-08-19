-- ws_outbound.lua — agent-side outbound tee for the ws transport (#1848).
--
-- The main agent's loop is unchanged; this is the one additive seam it gains
-- (default-off when it landed in #1848, default-ON as of #2608). It wraps the
-- agent's `http_post` so that, ONLY when the ws sidecar is enabled AND the link
-- is healthy, an outbound usage/events body is handed to the sidecar via the
-- bounded spool (the sidecar frames + sends it) instead of being POSTed here.
-- In every other case it is a pure pass-through to the real http_post, so with
-- ws off — an explicit `wifihaven.ws.enabled=0`, or a sidecar that stopped
-- refreshing the health sentinel (crash, TLS failure, a server that does not
-- speak ws) — the agent's HTTP path is byte-for-byte unchanged (back-compat,
-- design §3.1). That pass-through is why making ws the default cannot strand a
-- router without a transport.
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

-- The SOLE freshness rule for "is the ws link live enough to be the transport":
-- the sidecar writes os.time() into the health sentinel on every successful
-- send/recv, so a sentinel timestamp within fallback_after of now means the
-- link is healthy. A nil timestamp (absent sentinel) is never fresh. The
-- boundary is inclusive (exactly fallback_after old still counts as fresh) — the
-- tee falls back only once now-h is STRICTLY past the window.
local function fresh(h, now_val, fallback_after)
  return h ~= nil and (now_val - h) <= fallback_after
end

-- health_age(opts) → seconds since the sidecar last touched the sentinel, or
-- nil when there is no age to report. Derived from the SAME `enabled` flag and
-- the SAME read is_healthy judges, so the gauge the agent reports and the gate
-- the agent acts on can never disagree (#2731 — the 9%-suppression bug was
-- invisible from the fleet metrics precisely because nothing reported this).
--
-- nil covers BOTH ways the gate can be false for a reason other than age:
-- the sentinel is absent (never connected, or cleared on disconnect), and ws is
-- disabled. The second matters because clear_health only runs on a clean exit,
-- so a router with ws switched off can be left holding a stale sentinel file
-- whose age climbs forever — reporting that as an age would show a growing,
-- threshold-crossing number for a gate that is correctly and permanently false.
--   opts: enabled bool, health_read fn()→number|nil, now fn()→number
function M.health_age(opts)
  if not opts.enabled then return nil end
  local h = opts.health_read()
  if h == nil then return nil end
  return opts.now() - h
end

-- is_healthy(opts) → bool. The single definition of "the ws link is healthy",
-- consulted by BOTH the outbound tee (M.make, below) and the agent's policy-poll
-- dormancy gate (#2037), so the two cannot drift. True iff ws is enabled AND the
-- health sentinel is fresh.
--   opts: enabled bool, health_read fn()→number|nil, now fn()→number,
--         fallback_after number
function M.is_healthy(opts)
  if not opts.enabled then return false end
  return fresh(opts.health_read(), opts.now(), opts.fallback_after)
end

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
    -- Same freshness rule as the policy-poll dormancy gate (#2037).
    if not fresh(health_read(), now(), fallback_after) then
      metrics_inc("http_fallback")
      return http_post(url, body, headers)
    end
    -- Healthy: hand the body to the sidecar as one frame. The body is already a
    -- JSON object string, so wrapping it as the frame payload is a concat — no
    -- re-encode. A synthetic 200 tells the caller's retry queue the body is now
    -- owned by the sidecar. Delivery is best-effort (usage is best-effort per
    -- docs/resilience.md §1): the sidecar re-sends frames that fail to SEND and
    -- the server dedups, but a frame sent-then-lost is not yet retried — full
    -- ack-gated at-least-once is the follow-up #1928. If the spool write itself
    -- fails, fall back to HTTP so the datum is never silently dropped here.
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
