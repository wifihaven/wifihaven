-- ws_outbound_spec.lua — unit tests for the #1848 agent-side outbound tee.
--
-- When the ws sidecar is enabled AND the link is healthy, the main agent hands
-- its outbound usage/events bodies to the sidecar over the bounded spool instead
-- of POSTing them itself (the sidecar frames + sends them). When ws is disabled
-- (the default) OR the link has been down past fallback_after, the tee is a pure
-- pass-through to the real http_post — so the agent's HTTP path is byte-for-byte
-- unchanged in the default-off case (back-compat, design §3.1). Pure over
-- injected io/now/http_post, so it runs on the dev host as under OpenWrt 5.1.

local ws_outbound = require("wifihaven.ws_outbound")

local USAGE_URL  = "http://api/api/router/usage"
local EVENTS_URL = "http://api/api/router/events"
local METRICS_URL = "http://api/api/router/metrics"

-- a recording http_post stub
local function rec_post()
  local calls = {}
  local fn = function(url, body, headers)
    calls[#calls + 1] = { url = url, body = body }
    return 200, "ok-http", nil
  end
  return fn, calls
end

describe("ws_outbound.make — disabled (default)", function()
  it("is a pure pass-through to http_post and never spools", function()
    local http, calls = rec_post()
    local spooled = {}
    local post = ws_outbound.make({
      enabled = false,
      http_post = http,
      spool_append = function(l) spooled[#spooled + 1] = l; return true end,
      health_read = function() return os.time() end,
      now = os.time, fallback_after = 300,
    })
    local status = post(USAGE_URL, '{"records":[]}', {})
    assert.are.equal(200, status)
    assert.are.equal(1, #calls)
    assert.are.equal(0, #spooled)
  end)
end)

describe("ws_outbound.make — enabled + healthy link", function()
  local function healthy_tee(spooled, http)
    return ws_outbound.make({
      enabled = true,
      http_post = http,
      spool_append = function(l) spooled[#spooled + 1] = l; return true end,
      health_read = function() return 1000 end,
      now = function() return 1100 end,      -- 100s < 300 → fresh
      fallback_after = 300,
    })
  end

  it("spools a usage frame and does NOT call http_post", function()
    local http, calls = rec_post()
    local spooled = {}
    local status = healthy_tee(spooled, http)(USAGE_URL, '{"records":[1]}', {})
    assert.are.equal(200, status)               -- synthetic success (owned by sidecar)
    assert.are.equal(0, #calls)
    assert.are.equal(1, #spooled)
    assert.are.equal('{"op":"usage","payload":{"records":[1]}}', spooled[1])
  end)

  it("spools an events frame", function()
    local http, calls = rec_post()
    local spooled = {}
    healthy_tee(spooled, http)(EVENTS_URL, '{"events":[]}', {})
    assert.are.equal(0, #calls)
    assert.are.equal('{"op":"events","payload":{"events":[]}}', spooled[1])
  end)

  it("never tees a non-usage/events URL (e.g. metrics) — stays on HTTP", function()
    local http, calls = rec_post()
    local spooled = {}
    healthy_tee(spooled, http)(METRICS_URL, '{"m":1}', {})
    assert.are.equal(1, #calls)
    assert.are.equal(0, #spooled)
  end)
end)

describe("ws_outbound.make — enabled but link down (fallback)", function()
  it("resumes http_post when the health sentinel is stale", function()
    local http, calls = rec_post()
    local spooled = {}
    local post = ws_outbound.make({
      enabled = true, http_post = http,
      spool_append = function(l) spooled[#spooled + 1] = l; return true end,
      health_read = function() return 1000 end,
      now = function() return 1400 end,         -- 400s > 300 → stale
      fallback_after = 300,
    })
    post(USAGE_URL, '{"records":[]}', {})
    assert.are.equal(1, #calls)
    assert.are.equal(0, #spooled)
  end)

  it("resumes http_post when the health sentinel is absent", function()
    local http, calls = rec_post()
    local post = ws_outbound.make({
      enabled = true, http_post = http,
      spool_append = function() return true end,
      health_read = function() return nil end,
      now = os.time, fallback_after = 300,
    })
    post(USAGE_URL, '{"records":[]}', {})
    assert.are.equal(1, #calls)
  end)

  -- #2608: ws is now the shipped default, so "the sidecar died" is a
  -- first-boot-onwards reality, not an opt-in operator's problem. A sidecar
  -- that crashes (or never got past the TLS handshake, or hit a server that
  -- does not speak ws) stops refreshing the health sentinel; once it ages past
  -- fallback_after BOTH gates flip together — the outbound tee resumes POSTing
  -- and the policy poll resumes fetching — so a default-on router is never
  -- left without a transport.
  it("#2608 default-on + dead sidecar: usage AND events both resume HTTP", function()
    local http, calls = rec_post()
    local opts = {
      enabled = true, http_post = http,
      spool_append = function() error("must not spool with a dead sidecar") end,
      health_read = function() return 1000 end,
      now = function() return 1901 end,        -- 901s > 300 → sidecar gone
      fallback_after = 300,
    }
    local post = ws_outbound.make(opts)
    post(USAGE_URL, '{"records":[]}', {})
    post(EVENTS_URL, '{"events":[]}', {})
    assert.are.equal(2, #calls)
    -- Same predicate drives the policy-poll dormancy gate (#2037), so the poll
    -- resumes on the very same tick the tee falls back.
    assert.is_false(ws_outbound.is_healthy({
      enabled = true, health_read = opts.health_read,
      now = opts.now, fallback_after = 300,
    }))
  end)

  it("falls back to http_post if the spool write fails", function()
    local http, calls = rec_post()
    local post = ws_outbound.make({
      enabled = true, http_post = http,
      spool_append = function() return nil end,  -- spool write failed
      health_read = function() return 1000 end,
      now = function() return 1100 end, fallback_after = 300,
    })
    local status = post(USAGE_URL, '{"records":[]}', {})
    assert.are.equal(200, status)
    assert.are.equal(1, #calls)                 -- fell back to HTTP
  end)
end)
