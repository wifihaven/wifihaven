-- ws_loop_spec.lua — unit tests for the #1848 wifihaven-ws sidecar orchestration.
--
-- ws_loop.lua owns the websocket event loop: connect → (apply pushed policy in
-- / drain outbound frames out) → heartbeat → reconnect with exp backoff + jitter
-- → HTTP-fallback signalling (design §3.1, §5.1, §5.3, §5.5). The cqueues TLS
-- client is INJECTED (cfg.connect), so this whole state machine is exercised on
-- the dev host against a scripted fake client; the real client + cqueues loop
-- are validated live on plex.lan (Lua 5.1) via spike/ws-1845/e2e_test.sh.
--
-- Everything time/IO is injected (now/sleep/stop/metrics/spool/on_policy), so
-- the tests are deterministic and codec-agnostic.

local ws_loop  = require("wifihaven.ws_loop")
local ws_frame = require("wifihaven.ws_frame")

-- ── a recording metrics sink ────────────────────────────────────────────────
local function new_metrics()
  local m = { connect = {}, fallback = {}, sent = {}, recv = {}, state = {} }
  return {
    rec = m,
    connect      = function(r) m.connect[#m.connect + 1] = r end,
    fallback     = function(r) m.fallback[#m.fallback + 1] = r end,
    frame_sent   = function(op) m.sent[#m.sent + 1] = op end,
    frame_recv   = function(op) m.recv[#m.recv + 1] = op end,
    state        = function(v) m.state[#m.state + 1] = v end,
  }
end

-- ── a scripted fake ws client ───────────────────────────────────────────────
-- `recvs` is a queue of {op=, payload=} | {drop=reason} | {timeout=true}.
-- send_text/send_ping record calls and can be forced to fail.
local function new_client(recvs)
  local c = {
    sent = {}, pings = 0, closed = false, fail_send = false,
    _recvs = recvs or {},
  }
  function c:send_text(s)
    if self.fail_send then return nil, "write: broken" end
    self.sent[#self.sent + 1] = s
    return true
  end
  function c:send_ping(_) self.pings = self.pings + 1; return true end
  function c:recv(_)
    local r = table.remove(self._recvs, 1)
    if not r then return nil, "closed" end
    if r.timeout then return nil, "timeout" end
    -- #2731: ws_client:recv surfaces a consumed control PONG as a non-message
    -- outcome so the caller can treat it as proof of liveness. The reason string
    -- comes from ws_frame.RECV_PONG — the same constant the real client returns
    -- — so this fake cannot drift from it.
    if r.control then return nil, r.control end
    if r.drop then return nil, r.drop end
    return r.op, r.payload
  end
  function c:close() self.closed = true end
  return c
end

-- Codec the loop uses to read/split outbound frame lines. The fake spool stores
-- frames as Lua tables wrapped in a 1-element holder, so decode/encode are
-- identity-ish and no real JSON parser is needed in the unit test.
local fake_codec = {
  decode = function(line) return line end,        -- line is already a table here
  encode = function(tbl) return tbl end,
}

-- Base cfg with sensible deterministic stubs; tests override per-case.
local function base_cfg(overrides)
  local cfg = {
    now = function() return 0 end,
    sleep = function() end,
    stop = function() return true end,           -- one pass by default
    metrics = new_metrics(),
    spool_drain = function() return {} end,
    on_policy = function() end,
    touch_health = function() end,
    clear_health = function() end,
    decode_frame = fake_codec.decode,
    encode_frame = fake_codec.encode,
    frame_op = function(f) return f.op end,
    split_usage = function(f) return { f } end,   -- no split by default
    heartbeat_interval = 30,
    poll_interval = 1,
    fallback_after = 300,
    rng = function() return 1 end,
    log = { info = function() end, warn = function() end, err = function() end, debug = function() end },
  }
  for k, v in pairs(overrides or {}) do cfg[k] = v end
  return cfg
end

describe("ws_loop.classify_connect_error", function()
  it("maps a 401 upgrade rejection to auth_fail", function()
    assert.are.equal("auth_fail", ws_loop.classify_connect_error("upgrade rejected: HTTP/1.1 401 Unauthorized"))
  end)
  it("maps a non-401 upgrade rejection to upgrade_fail", function()
    assert.are.equal("upgrade_fail", ws_loop.classify_connect_error("upgrade rejected: HTTP/1.1 502 Bad Gateway"))
  end)
  it("maps a connect/handshake timeout to timeout", function()
    assert.are.equal("timeout", ws_loop.classify_connect_error("connect: timeout"))
    assert.are.equal("timeout", ws_loop.classify_connect_error("starttls: timeout"))
  end)
  it("falls back to upgrade_fail for anything else", function()
    assert.are.equal("upgrade_fail", ws_loop.classify_connect_error("connect: connection refused"))
    assert.are.equal("upgrade_fail", ws_loop.classify_connect_error(nil))
  end)
end)

-- stop_after(n) → a cfg.stop closure that lets the outer reconnect loop run n
-- passes. Same idiom the older cases inline; named here because the #2620 cases
-- below all want exactly one pass.
local function stop_after(n)
  local calls = 0
  return function() calls = calls + 1; return calls > n end
end

describe("ws_loop.sanitize_poll_interval (#2620)", function()
  it("falls back to the documented default when unset or unparseable", function()
    assert.are.equal(ws_loop.DEFAULT_POLL_INTERVAL, ws_loop.sanitize_poll_interval(nil, 30))
    assert.are.equal(ws_loop.DEFAULT_POLL_INTERVAL, ws_loop.sanitize_poll_interval("banana", 30))
  end)

  it("clamps up to the busy-loop floor", function()
    assert.are.equal(ws_loop.MIN_POLL_INTERVAL, ws_loop.sanitize_poll_interval(0, 30))
    assert.are.equal(ws_loop.MIN_POLL_INTERVAL, ws_loop.sanitize_poll_interval(-5, 30))
  end)

  it("clamps down to the heartbeat interval — polling slower than that is today's pre-#2620 shape", function()
    assert.are.equal(30, ws_loop.sanitize_poll_interval(120, 30))
  end)

  it("passes a sane in-range value through", function()
    assert.are.equal(2, ws_loop.sanitize_poll_interval(2, 30))
    assert.are.equal(2, ws_loop.sanitize_poll_interval("2", 30))
  end)

  it("does not let an unparseable heartbeat drag the poll down to the floor", function()
    -- The ceiling comes from the heartbeat; if that value is junk, falling back
    -- to MIN would turn one bad config key into 10 wakeups/s.
    assert.are.equal(ws_loop.DEFAULT_POLL_INTERVAL, ws_loop.sanitize_poll_interval(1, nil))
    assert.are.equal(ws_loop.DEFAULT_POLL_INTERVAL, ws_loop.sanitize_poll_interval(1, "banana"))
  end)
end)

describe("ws_loop.should_fallback", function()
  it("is false before the threshold and true at/after it", function()
    assert.is_false(ws_loop.should_fallback(120, 300))
    assert.is_true(ws_loop.should_fallback(300, 300))
    assert.is_true(ws_loop.should_fallback(901, 300))
  end)
end)

describe("ws_loop.run — connect failure path", function()
  it("meters the connect result, drops ws_state to 0, clears health, and backs off", function()
    local slept = {}
    local cleared = 0
    local m = new_metrics()
    local cfg = base_cfg({
      connect = function() return nil, "upgrade rejected: HTTP/1.1 401 Unauthorized" end,
      sleep = function(s) slept[#slept + 1] = s end,
      clear_health = function() cleared = cleared + 1 end,
      metrics = m,
      stop = (function() local n = 0; return function() n = n + 1; return n > 1 end end)(),
    })
    ws_loop.run(cfg)
    assert.are.same({ "auth_fail" }, m.rec.connect)
    assert.are.same({ 0 }, m.rec.state)
    assert.is_true(cleared >= 1)
    assert.are.equal(1, #slept)          -- one backoff sleep before the stop
    assert.is_true(slept[1] > 0)
  end)

  it("emits a single ws_fallback_total{to_http} once disconnected past fallback_after", function()
    local t = 0
    local m = new_metrics()
    -- time advances 200s per failed attempt; threshold 300 → fallback on attempt 2.
    local cfg = base_cfg({
      connect = function() return nil, "connect: connection refused" end,
      now = function() return t end,
      sleep = function() t = t + 200 end,
      metrics = m,
      stop = (function() local n = 0; return function() n = n + 1; return n > 3 end end)(),
    })
    ws_loop.run(cfg)
    assert.are.same({ "to_http" }, m.rec.fallback)   -- exactly once, not per-attempt
  end)
end)

describe("ws_loop.run — successful connect", function()
  it("meters connect ok, raises ws_state, touches health, and serves until drop", function()
    local m = new_metrics()
    local touched = 0
    local client = new_client({ { drop = "closed" } })   -- immediate drop → reconnect
    local cfg = base_cfg({
      connect = function() return client, nil end,
      metrics = m,
      touch_health = function() touched = touched + 1 end,
      stop = (function() local n = 0; return function() n = n + 1; return n > 1 end end)(),
    })
    ws_loop.run(cfg)
    assert.are.equal("ok", m.rec.connect[1])
    assert.are.equal(1, m.rec.state[1])             -- raised on connect
    assert.are.equal(0, m.rec.state[#m.rec.state])  -- lowered on drop
    assert.is_true(touched >= 1)
    assert.is_true(client.closed)
  end)

  it("emits ws_fallback_total{back_to_ws} when it reconnects after a prior fallback", function()
    local m = new_metrics()
    local attempt = 0
    local t = 0
    local cfg = base_cfg({
      now = function() return t end,
      sleep = function() t = t + 400 end,           -- one failure crosses the 300 threshold
      connect = function()
        attempt = attempt + 1
        if attempt == 1 then return nil, "connect: refused" end
        return new_client({ { drop = "closed" } }), nil
      end,
      metrics = m,
      stop = (function() local n = 0; return function() n = n + 1; return n > 2 end end)(),
    })
    ws_loop.run(cfg)
    assert.are.same({ "to_http", "back_to_ws" }, m.rec.fallback)
  end)
end)

describe("ws_loop.run — outbound drain", function()
  it("sends each spooled frame and meters frame_sent by op", function()
    local m = new_metrics()
    local drained = false
    local client = new_client({ { drop = "closed" } })
    local cfg = base_cfg({
      connect = function() return client, nil end,
      spool_drain = function()
        if drained then return {} end
        drained = true
        return { { op = "usage", payload = {} }, { op = "events", payload = {} } }
      end,
      metrics = m,
      stop = (function() local n = 0; return function() n = n + 1; return n > 1 end end)(),
    })
    ws_loop.run(cfg)
    assert.are.equal(2, #client.sent)
    assert.are.same({ "usage", "events" }, m.rec.sent)
  end)

  it("splits an oversized usage frame into multiple usage sends (the #1017 fix)", function()
    local m = new_metrics()
    local drained = false
    local client = new_client({ { drop = "closed" } })
    local cfg = base_cfg({
      connect = function() return client, nil end,
      spool_drain = function()
        if drained then return {} end
        drained = true
        return { { op = "usage", payload = { records = { 1, 2, 3 } } } }
      end,
      split_usage = function(f)        -- pretend it split into 3
        return { { op = "usage" }, { op = "usage" }, { op = "usage" } }
      end,
      metrics = m,
      stop = (function() local n = 0; return function() n = n + 1; return n > 1 end end)(),
    })
    ws_loop.run(cfg)
    assert.are.equal(3, #client.sent)
    assert.are.same({ "usage", "usage", "usage" }, m.rec.sent)
  end)

  it("retains un-sent frames for the next connection on a send failure", function()
    local m = new_metrics()
    local connects = 0
    local first_client = new_client({})
    first_client.fail_send = true
    local second_client = new_client({ { drop = "closed" } })
    local sent_on_second = {}
    second_client.send_text = function(self, s)
      sent_on_second[#sent_on_second + 1] = s; return true
    end
    local gave_frames = false
    local cfg = base_cfg({
      connect = function()
        connects = connects + 1
        return (connects == 1) and first_client or second_client, nil
      end,
      spool_drain = function()
        if gave_frames then return {} end
        gave_frames = true
        return { { op = "usage", payload = {} } }
      end,
      metrics = m,
      stop = (function() local n = 0; return function() n = n + 1; return n > 2 end end)(),
    })
    ws_loop.run(cfg)
    -- the frame that failed to send on connection 1 is re-sent on connection 2.
    assert.are.equal(1, #sent_on_second)
  end)
end)

describe("ws_loop.run — inbound + heartbeat", function()
  it("applies a pushed policy frame and meters frame_recv(policy)", function()
    local m = new_metrics()
    local applied
    local client = new_client({
      { op = "text", payload = "POLICY_JSON" },
      { drop = "closed" },
    })
    local cfg = base_cfg({
      connect = function() return client, nil end,
      -- decode the inbound text frame envelope → {op=policy, payload=...}
      decode_frame = function(line)
        if line == "POLICY_JSON" then return { op = "policy", payload = "SNAP" } end
        return nil
      end,
      on_policy = function(p) applied = p end,
      metrics = m,
      -- serve consumes the scripted [policy, drop] in the first connection; stop
      -- the outer reconnect loop after that single pass.
      stop = (function() local n = 0; return function() n = n + 1; return n > 1 end end)(),
    })
    ws_loop.run(cfg)
    assert.are.equal("SNAP", applied)
    assert.is_true((function()
      for _, op in ipairs(m.rec.recv) do if op == "policy" then return true end end
    end)())
  end)

  it("sends a heartbeat ping once the interval elapses", function()
    local m = new_metrics()
    local t = 0
    local client = new_client({ { timeout = true }, { drop = "closed" } })
    local cfg = base_cfg({
      connect = function() return client, nil end,
      now = function() return t end,
      -- advance time past the heartbeat interval between recv calls.
      sleep = function() end,
      heartbeat_interval = 30,
      metrics = m,
      stop = (function() local n = 0; return function() n = n + 1; return n > 1 end end)(),
    })
    -- bump the clock on each recv so the heartbeat condition trips.
    local orig_recv = client.recv
    client.recv = function(self, to) t = t + 31; return orig_recv(self, to) end
    ws_loop.run(cfg)
    assert.is_true(client.pings >= 1)
  end)
end)

-- ── #2620: the outbound drain tick is decoupled from the heartbeat ───────────
-- serve() used to block in client:recv(heartbeat_interval), so a frame spooled
-- just after a drain waited up to heartbeat_interval (default 30s) before
-- anything sent it — the dominant hop in the measured 26s drop→SPA latency.
-- The recv timeout is now its own short poll_interval; the heartbeat keeps its
-- own last_ping-gated cadence.
describe("ws_loop.run — outbound drain tick (#2620)", function()
  it("blocks in recv for poll_interval, not heartbeat_interval", function()
    local timeouts = {}
    local client = new_client({ { timeout = true }, { drop = "closed" } })
    local orig_recv = client.recv
    client.recv = function(self, to) timeouts[#timeouts + 1] = to; return orig_recv(self, to) end
    local cfg = base_cfg({
      connect = function() return client, nil end,
      heartbeat_interval = 30,
      poll_interval = 1,
      stop = stop_after(1),
    })
    ws_loop.run(cfg)
    assert.are.equal(1, timeouts[1])
    assert.are.equal(1, timeouts[2])
  end)

  it("sends a frame spooled after the first drain within one poll tick", function()
    local t = 0
    local drains = 0
    local sent_at
    local client = new_client({ { timeout = true }, { timeout = true }, { drop = "closed" } })
    local orig_recv = client.recv
    client.recv = function(self, to) t = t + to; return orig_recv(self, to) end
    client.send_text = function(self, s)
      sent_at = t; self.sent[#self.sent + 1] = s; return true
    end
    local cfg = base_cfg({
      connect = function() return client, nil end,
      now = function() return t end,
      heartbeat_interval = 30,
      poll_interval = 1,
      spool_drain = function()
        drains = drains + 1
        -- Nothing on the first drain; the event lands on the spool right after,
        -- i.e. exactly the case that used to wait out the heartbeat.
        if drains == 2 then return { { op = "events", payload = {} } } end
        return {}
      end,
      stop = stop_after(1),
    })
    ws_loop.run(cfg)
    assert.are.equal(1, #client.sent)
    assert.are.equal(1, sent_at)   -- one poll tick, not the 30s heartbeat
  end)

  it("still beats the heartbeat on its own cadence when the recv timeout is shorter", function()
    local t = 0
    local recvs = {}
    for _ = 1, 65 do recvs[#recvs + 1] = { timeout = true } end
    recvs[#recvs + 1] = { drop = "closed" }
    local client = new_client(recvs)
    local orig_recv = client.recv
    client.recv = function(self, to) t = t + to; return orig_recv(self, to) end
    local cfg = base_cfg({
      connect = function() return client, nil end,
      now = function() return t end,
      heartbeat_interval = 30,
      poll_interval = 1,
      stop = stop_after(1),
    })
    ws_loop.run(cfg)
    -- 66 polls of 1s each. The heartbeat is last_ping-gated, so it fires at
    -- t=30 and t=60 — twice, not once per poll.
    assert.are.equal(2, client.pings)
  end)

  it("sanitizes a poll_interval of 0 so an idle socket cannot busy-spin", function()
    local timeouts = {}
    local client = new_client({ { timeout = true }, { drop = "closed" } })
    local orig_recv = client.recv
    client.recv = function(self, to) timeouts[#timeouts + 1] = to; return orig_recv(self, to) end
    local cfg = base_cfg({
      connect = function() return client, nil end,
      poll_interval = 0,
      stop = stop_after(1),
    })
    ws_loop.run(cfg)
    assert.are.equal(ws_loop.MIN_POLL_INTERVAL, timeouts[1])
  end)
end)


-- ── #2731: the heartbeat is what proves the link is alive ───────────────────
--
-- Before this change the health sentinel was refreshed ONLY by connect and by
-- application frames in either direction. The agent's outbound tee only spools
-- application frames while the sentinel is already fresh, so any gap in
-- application traffic longer than fallback_after latched the router into HTTP
-- polling for the life of an otherwise perfectly healthy connection: nothing
-- was spooled, so nothing was sent, so nothing was acked, so nothing touched
-- the sentinel. Measured on prod as an 80-minute-stale sentinel under a live
-- socket that was still heartbeating (#2731).
--
-- The control ping/pong exchange is the one thing that proves the peer is alive
-- on a quiet link, and it runs every heartbeat_interval (30 s) against a 300 s
-- window. ws_client:recv now surfaces it instead of swallowing it, and serve
-- refreshes health on it.

describe("ws_loop.classify_recv — #2731 recv outcome vocabulary", function()
  it("an application frame is a message", function()
    assert.are.equal("message", ws_loop.classify_recv(1, '{"op":"policy"}'))
  end)
  it("a benign read timeout is idle — the socket is live but said nothing", function()
    assert.are.equal("idle", ws_loop.classify_recv(nil, "timeout"))
  end)
  it("a control pong is liveness — the peer answered our heartbeat", function()
    assert.are.equal("liveness", ws_loop.classify_recv(nil, ws_frame.RECV_PONG))
  end)
  -- An inbound server PING is deliberately absent from the liveness vocabulary:
  -- ws_client answers it and keeps reading, because returning mid-reassembly
  -- would abort an in-flight fragmented message (#1959). So a "ping" reason is
  -- something recv never produces, and if one ever appeared it would be an
  -- unrecognised reason — i.e. a drop, the safe direction (resume polling).
  it("does not treat a ping reason as liveness — recv never surfaces one", function()
    assert.are.equal("drop", ws_loop.classify_recv(nil, "ping"))
  end)
  it("eof / closed / protocol are drops", function()
    assert.are.equal("drop", ws_loop.classify_recv(nil, "eof"))
    assert.are.equal("drop", ws_loop.classify_recv(nil, "closed"))
    assert.are.equal("drop", ws_loop.classify_recv(nil, "protocol: bad opcode"))
  end)
end)

describe("ws_loop.serve — #2731 heartbeat liveness refreshes the health sentinel", function()
  it("touches health on every control pong, with NO application traffic at all", function()
    local touches, clears = 0, 0
    local recvs = {}
    -- Ten heartbeat round-trips on a completely quiet link: no usage, no
    -- events, no policy push. Pre-#2731 this touched health exactly once (the
    -- connect) and the sentinel went stale 300 s later.
    for _ = 1, 10 do
      recvs[#recvs + 1] = { timeout = true }
      recvs[#recvs + 1] = { control = ws_frame.RECV_PONG }
    end
    recvs[#recvs + 1] = { drop = "closed" }
    local client = new_client(recvs)
    local cfg = base_cfg({
      connect = function() return client, nil end,
      touch_health = function() touches = touches + 1 end,
      clear_health = function() clears = clears + 1 end,
      stop = stop_after(1),
    })
    ws_loop.run(cfg)
    -- 1 connect + 10 pongs. The count is exact, not ">= 1": an absence-style
    -- "the poll did not run" assertion passes for free against a dead harness,
    -- so this pins the positive number of refreshes instead.
    assert.are.equal(11, touches)
    -- Liveness anchor: the loop really ran to the scripted disconnect rather
    -- than bailing out on the first control frame.
    assert.are.equal(0, #client._recvs)
    assert.are.equal(1, clears)
  end)

  -- ws_frames_recv_total's `op` enum is the APPLICATION frame vocabulary
  -- (policy / ack / unknown). Folding ~2,880 control pongs/router/day into it
  -- would add a label value AND silently change what its existing `pong` bucket
  -- means, so the heartbeat is deliberately not metered there — its effect shows
  -- up on ws_health_age_seconds instead.
  it("does not fold the heartbeat into the application frame counter", function()
    local client = new_client({ { control = ws_frame.RECV_PONG }, { op = 1, payload = "x" }, { drop = "closed" } })
    local cfg = base_cfg({
      connect = function() return client, nil end,
      decode_frame = function() return { op = "ack" } end,
      stop = stop_after(1),
    })
    ws_loop.run(cfg)
    -- Liveness anchor: the application frame WAS metered, so an empty recv list
    -- would mean a dead harness rather than a correctly-unmetered heartbeat.
    assert.are.same({ "ack" }, cfg.metrics.rec.recv)
  end)

  it("keeps serving after a control frame — a later outbound frame still goes out", function()
    local drained = false
    local client = new_client({ { control = ws_frame.RECV_PONG }, { timeout = true }, { drop = "closed" } })
    local cfg = base_cfg({
      connect = function() return client, nil end,
      spool_drain = function()
        if drained then return {} end
        drained = true
        return { { op = "usage", payload = {} } }
      end,
      stop = stop_after(1),
    })
    ws_loop.run(cfg)
    assert.are.equal(1, #client.sent)
    assert.are.equal(0, #client._recvs)
  end)

  it("a genuinely dead link still drops out and clears health (fallback intact)", function()
    local touches, clears = 0, 0
    local client = new_client({ { control = ws_frame.RECV_PONG }, { drop = "eof" } })
    local cfg = base_cfg({
      connect = function() return client, nil end,
      touch_health = function() touches = touches + 1 end,
      clear_health = function() clears = clears + 1 end,
      stop = stop_after(1),
    })
    ws_loop.run(cfg)
    assert.are.equal(2, touches)   -- connect + the one pong before the drop
    assert.are.equal(1, clears)    -- the eof cleared it: the poll resumes
    assert.is_true(client.closed)
  end)
end)
