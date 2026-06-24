-- ws_loop.lua — the wifihaven-ws sidecar orchestration (#1848).
--
-- Owns the websocket event loop the main agent deliberately lacks (design §0.1):
-- connect → (drain outbound usage/events frames OUT / apply pushed `policy` IN)
-- → heartbeat → reconnect with exponential backoff + jitter → HTTP-fallback
-- signalling. The cqueues TLS client (ws_client.lua), the clock, the spool, the
-- metrics sink, the policy writer and the health sentinel are ALL injected, so
-- this state machine is unit-tested on the dev host against a fake client and
-- the live cqueues round-trip is validated on plex.lan (the spike's e2e_test.sh).
--
-- The handshake (`hello`/`ready`) is NOT implemented — it was closed won't-do
-- (design §2 status, #1847); the agent simply upgrades and is ready to apply a
-- pushed `policy` whenever the server sends one (the first-policy-on-connect push
-- is #1849). Until then policy continues to flow over the main agent's unchanged
-- HTTP poll; this sidecar carries usage/events/heartbeat and is ready for push.
--
-- Reliability model (design §5): at-least-once. A frame that fails to send is
-- retained in memory and re-sent on the next connection; the server dedups on
-- the persistence-layer unique keys, so a redelivery is a no-op (§5.3). Best-
-- effort across a reboot, exactly like the existing usage retry queue (§5.2).

local ws_backoff = require("wifihaven.ws_backoff")

local M = {}

-- classify_connect_error(err) → bounded `ws_connect_total{result}` enum.
-- A 401 at upgrade is an auth failure (drop, don't hammer — same as a REST 401);
-- any other rejected upgrade is `upgrade_fail`; a connect/TLS/handshake timeout
-- is `timeout`; anything else collapses to `upgrade_fail`. Pure.
function M.classify_connect_error(err)
  err = tostring(err or "")
  if err:find("timeout", 1, true) then return "timeout" end
  if err:find("upgrade rejected", 1, true) then
    if err:find("401", 1, true) then return "auth_fail" end
    return "upgrade_fail"
  end
  return "upgrade_fail"
end

-- should_fallback(disconnected_secs, fallback_after) → bool. Once the socket has
-- been down longer than fallback_after, the main agent resumes HTTP polling so
-- enforcement never goes stale waiting on a flapping link (design §3.1/§5.1).
function M.should_fallback(disconnected_secs, fallback_after)
  return disconnected_secs >= fallback_after
end

-- Drain the outbound spool (plus any frames retained from a failed prior
-- connection) and send each as a frame. A `usage` frame whose encoded size
-- exceeds ws_max_frame_bytes is split across multiple `usage` frames (the #1017
-- fix, §5.3). On a send failure the un-sent remainder is retained in ctx.pending
-- for the next connection and `false` is returned (→ reconnect). Returns true if
-- the whole batch was sent.
local function drain_and_send(cfg, client, ctx)
  local batch = {}
  for _, l in ipairs(ctx.pending) do batch[#batch + 1] = l end
  ctx.pending = {}
  for _, l in ipairs(cfg.spool_drain()) do batch[#batch + 1] = l end

  for i = 1, #batch do
    local line = batch[i]
    local frame = cfg.decode_frame(line)
    if frame then
      local to_send
      if cfg.frame_op(frame) == "usage" then
        to_send = cfg.split_usage(frame)
      else
        to_send = { frame }
      end
      for _, f in ipairs(to_send) do
        local ok = client:send_text(cfg.encode_frame(f))
        if not ok then
          -- Retain this line and every later line for the next connection.
          local keep = {}
          for j = i, #batch do keep[#keep + 1] = batch[j] end
          ctx.pending = keep
          return false
        end
        cfg.metrics.frame_sent(cfg.frame_op(f))
        cfg.touch_health()
      end
    end
    -- An undecodable spool line is dropped (it can never be framed); the agent
    -- only ever writes well-formed frames, so this is defence-in-depth.
  end
  return true
end

-- Handle one inbound application frame (text payload already received). A
-- `policy` push is written to the snapshot file the main agent applies (etag-
-- guarded, unchanged); `ack`/`pong` are liveness/observability only; an
-- unrecognised op is metered and ignored (forward-compat, §1.3).
local function handle_inbound(cfg, payload)
  cfg.touch_health()
  local frame = cfg.decode_frame(payload)
  if not frame then
    cfg.metrics.frame_recv("unknown")
    return
  end
  local op = cfg.frame_op(frame) or "unknown"
  if op == "policy" then
    cfg.on_policy(frame.payload)
    cfg.metrics.frame_recv("policy")
  else
    cfg.metrics.frame_recv(op)
  end
end

-- serve(cfg, client, ctx) — the connected steady-state loop. Drains outbound,
-- beats the heartbeat, and processes inbound frames until the socket drops (or a
-- send fails). Returns when disconnected so run() can reconnect.
local function serve(cfg, client, ctx)
  local last_ping = cfg.now()
  while true do
    if not drain_and_send(cfg, client, ctx) then return end

    local t = cfg.now()
    if (t - last_ping) >= cfg.heartbeat_interval then
      client:send_ping("hb")
      last_ping = t
      cfg.metrics.frame_sent("ping")
    end

    local op, payload = client:recv(cfg.heartbeat_interval)
    if not op then
      -- A read timeout is the normal idle gap between heartbeats — the socket is
      -- still live, so keep looping. Anything else (eof / protocol / close) is a
      -- real drop → return to reconnect.
      if payload ~= "timeout" then return end
    else
      handle_inbound(cfg, payload)
    end
  end
end

-- run(cfg) — the outer reconnect loop. Runs until cfg.stop() returns true (the
-- production sidecar's stop() is always false — it runs for the process's life;
-- tests bound it). Injected cfg seams:
--   connect()            → client, err    (wraps ws_client.connect with the
--                                           wss URI + Authorization header)
--   now()                → monotonic seconds
--   sleep(secs)          → cooperative sleep (cqueues.sleep on target)
--   stop()               → bool
--   spool_drain()        → { frame_line, … }  (outbound bodies)
--   on_policy(payload)   → write the pushed snapshot for the agent to apply
--   decode_frame(line)   → { op, payload } | nil
--   encode_frame(frame)  → wire string
--   frame_op(frame)      → op string
--   split_usage(frame)   → { frame, … }   (the #1017 split)
--   touch_health()/clear_health()         (the ws-health sentinel the agent reads)
--   metrics{connect,state,fallback,frame_sent,frame_recv}
--   heartbeat_interval, fallback_after, rng, log
function M.run(cfg)
  local ctx = { pending = {} }
  local attempt = 0
  local fell_back = false
  local disconnected_since = cfg.now()

  while not cfg.stop() do
    -- Belt-and-suspenders fallback: if we've been disconnected longer than
    -- fallback_after, signal the main agent to resume HTTP polling (once per
    -- outage, not per attempt). The agent reads the health sentinel; emitting
    -- the metric here keeps the rollout dashboard honest (§3.1/§7).
    local disc = cfg.now() - disconnected_since
    if not fell_back and M.should_fallback(disc, cfg.fallback_after) then
      cfg.metrics.fallback("to_http")
      fell_back = true
    end

    local client, err = cfg.connect()
    if not client then
      attempt = attempt + 1
      cfg.metrics.connect(M.classify_connect_error(err))
      cfg.metrics.state(0)
      cfg.clear_health()
      if cfg.log and cfg.log.warn then
        cfg.log.warn("ws: connect failed (attempt %d): %s", attempt, tostring(err))
      end
      cfg.sleep(ws_backoff.delay(attempt, cfg.rng))
    else
      attempt = 0
      cfg.metrics.connect("ok")
      cfg.metrics.state(1)
      cfg.touch_health()
      if fell_back then
        cfg.metrics.fallback("back_to_ws")
        fell_back = false
      end
      if cfg.log and cfg.log.info then cfg.log.info("ws: connected") end

      serve(cfg, client, ctx)

      -- Disconnected. Lower the link gauge, clear the health sentinel (so the
      -- agent's outbound tee falls back to HTTP after fallback_after), and start
      -- the disconnect clock for the fallback decision above.
      cfg.metrics.state(0)
      cfg.clear_health()
      pcall(function() client:close() end)
      disconnected_since = cfg.now()
    end
  end
end

return M
