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

local ws_loop = require("wifihaven.ws_loop")

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
