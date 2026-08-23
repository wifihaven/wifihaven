-- ws_outbound.lua — agent-side outbound bridge for the ws transport (#1848).
--
-- The main agent's loop is unchanged; this is the one additive seam it gains.
-- It wraps the agent's `http_post` so that an outbound usage/events body is
-- handed to the wifihaven-ws sidecar via the bounded spool (the sidecar frames
-- + sends it) instead of being POSTed here.
--
-- #2736: THE HTTP FALL-THROUGH IS GONE. This shipped in #1848 as a tee with a
-- fallback — when the sidecar's health sentinel went stale past
-- `ws.fallback_after`, usage/events went back out over REST and the agent
-- resumed the HTTP snapshot poll. That fallback did its job and is now retired:
-- #2608 made ws the fleet default, #2731 closed the suppression gap, and prod
-- then measured `snapshot_poll_total` increase = 0 over 24h AND 48h on both
-- routers while `policy_poll_skipped_total` climbed 28k in 24h — live agents
-- actively declining to poll, not a dead agent's silence. So the spool is the
-- only exit for usage/events now, in both link states.
--
-- WHY SPOOLING WHILE THE LINK IS DOWN IS THE RIGHT MOVE and not a data leak:
-- the spool is bounded (ws_spool self-bounds at `spool_max_bytes` by dropping
-- OLDEST whole lines, per docs/process/router-agent-bounded-writes.md — /tmp is
-- tmpfs = RAM), and the sidecar drains it on reconnect. So a short outage now
-- REPLAYS rather than being dropped, which is strictly better than the old
-- fallback could manage. A long one degrades to the oldest-first eviction the
-- usage retry queue has always used.
--
-- Only `usage` and `events` are bridged. THE METRICS PUSH STAYS ON HTTP, and
-- that is load-bearing rather than incidental: it is what keeps the ws_*
-- observability series — including the `ws_health_age_seconds` gauge that alert
-- W15 (#2736) keys on — arriving while the socket is down. A health signal
-- carried over the link it reports on would be the #2546 shape, where silence
-- reads as health.

local M = {}

-- How many missed heartbeats mean the link is down (#2736). The sidecar
-- refreshes the health sentinel on every successful send/recv AND on the
-- control ping/pong heartbeat (#2731), so the cadence that keeps the sentinel
-- warm is `ws.heartbeat_interval` — which is why the staleness bound is derived
-- from it here instead of from the standalone `ws.fallback_after` this replaces.
-- Three is two full heartbeats of slack past the one that should have landed:
-- prod's sentinel age never left [0, 31] over the whole retained window of the
-- gauge (2026-08-21 → 08-23) against a 30s heartbeat, so 90s clears the
-- observed steady state by ~3x while still noticing a dead link inside two
-- minutes.
M.STALE_HEARTBEATS = 3

-- The SINGLE definition of the staleness bound, so the agent and anything else
-- judging the link derive it the same way from the same cadence.
function M.stale_after(heartbeat_interval)
  return heartbeat_interval * M.STALE_HEARTBEATS
end

-- The SOLE freshness rule for "is the ws link live": the sidecar writes
-- os.time() into the health sentinel on every successful send/recv and on each
-- heartbeat pong, so a sentinel timestamp within stale_after of now means the
-- link is healthy. A nil timestamp (absent sentinel — never connected, or
-- cleared by the sidecar on disconnect) is never fresh. The boundary is
-- inclusive: exactly stale_after old still counts as fresh.
local function fresh(h, now_val, stale_after)
  return h ~= nil and (now_val - h) <= stale_after
end

-- health_age(opts) → seconds since the sidecar last touched the sentinel, or
-- nil when there is no age to report (the sentinel is absent: never connected,
-- or cleared on disconnect). Derived from the SAME read is_healthy judges, so
-- the gauge the agent reports and the judgement the agent acts on can never
-- disagree (#2731 — the 9%-suppression bug was invisible from the fleet metrics
-- precisely because nothing reported this).
--
-- The agent reports nil as `-1`, which is the arm alert W15 exists to catch:
-- an absent sentinel is the clean "the websocket is DOWN" case, and it is a
-- negative number no greater-than threshold can see.
--   opts: health_read fn()→number|nil, now fn()→number
function M.health_age(opts)
  local h = opts.health_read()
  if h == nil then return nil end
  return opts.now() - h
end

-- is_healthy(opts) → bool. The single definition of "the ws link is healthy".
-- Consulted by the agent's failover edge (#331/#422), which since #2736 keys on
-- the LINK rather than on a poll result — the condition it always meant to
-- express ("we are out of contact with the API") over the transport that now
-- carries that contact.
--   opts: health_read fn()→number|nil, now fn()→number, stale_after number
function M.is_healthy(opts)
  return fresh(opts.health_read(), opts.now(), opts.stale_after)
end

-- make(opts) → post_fn(url, body, headers) → status, resp_body, err
--
-- opts:
--   http_post      fn(url,body,hdrs)     — the real curl post (non-teed ops)
--   spool_append   fn(line) → ok         — append one bounded frame line
--   metrics_inc    fn(result)?           — optional outcome meter
function M.make(opts)
  local http_post    = opts.http_post
  local spool_append = opts.spool_append
  local metrics_inc  = opts.metrics_inc or function() end

  return function(url, body, headers)
    -- Only usage/events are bridged; the trailing path segment is the op.
    -- Everything else — the metrics push, the block-page token fetch, blocklist
    -- fetches — is still ordinary HTTP and passes straight through.
    local op = url:match("/api/router/(usage)$") or url:match("/api/router/(events)$")
    if not op then
      return http_post(url, body, headers)
    end
    -- Hand the body to the sidecar as one frame. The body is already a JSON
    -- object string, so wrapping it as the frame payload is a concat — no
    -- re-encode. A synthetic 200 tells the caller's retry queue the body is now
    -- owned by the sidecar. Delivery is best-effort (usage is best-effort per
    -- docs/resilience.md §1): the sidecar re-sends frames that fail to SEND and
    -- the server dedups, but a frame sent-then-lost is not yet retried — full
    -- ack-gated at-least-once is the follow-up #1928.
    local line = '{"op":"' .. op .. '","payload":' .. body .. '}'
    if spool_append(line) then
      metrics_inc("spooled")
      return 200, "", nil
    end
    -- #2736: a failed spool write used to fall through to HTTP. With no REST
    -- ingest left, the honest answer is a NON-2xx, which hands the datum back
    -- to the caller's retry queue (usage.post / the conntrack event queue both
    -- read status that way) rather than reporting a success that never left the
    -- box. Returning 200 here would silently drop the bucket.
    metrics_inc("spool_failed")
    return 0, "", "ws spool append failed"
  end
end

return M
