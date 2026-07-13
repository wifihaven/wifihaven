-- ws_tls_spec.lua — unit tests for the #2153 client-TLS context builder.
--
-- ws_client.lua's TLS setup is the wss:// cutover blocker (#2153): luaossl's
-- context.new() starts with an EMPTY trust store, so VERIFY_PEER rejected every
-- real cert, AND cqueues/luaossl does NOT apply the hostname check automatically
-- (a valid-chain cert for ANY host would be accepted — a MITM hole). The fix
-- lives in ws_tls.lua, whose build_context() is written with the luaossl modules
-- INJECTED so it is unit-testable on the macOS busted host without cqueues +
-- luaossl present. The real crypto behaviour (store loading + hostname reject)
-- is additionally proven on the live Lua-5.1 router target — see the PR for
-- #2153 and the on-device handshake evidence.

local ws_tls = require("wifihaven.ws_tls")

-- A fake luaossl boundary that records every call build_context() makes, so we
-- assert the context is configured correctly (verify mode, store loaded,
-- hostname bound) without a real TLS stack.
local function fake_deps(opts)
  opts = opts or {}
  local calls = {
    ctx_new = nil, verify = nil, store_added = {}, store_defaults = 0,
    set_store = false, param_host = nil, set_param = false,
  }
  local ctx = {}
  function ctx:setVerify(m) calls.verify = m end
  function ctx:setStore(_) calls.set_store = true end
  function ctx:setParam(_) calls.set_param = true end
  local store_obj = {}
  function store_obj:add(path)
    if opts.add_fails then error("add: no such file", 0) end
    calls.store_added[#calls.store_added + 1] = path
  end
  function store_obj:addDefaults() calls.store_defaults = calls.store_defaults + 1 end
  local vp_obj = {}
  function vp_obj:setHost(h) calls.param_host = h end
  local deps = {
    context = {
      VERIFY_PEER = "PEER", VERIFY_NONE = "NONE",
      new = function(proto, server)
        calls.ctx_new = { proto = proto, server = server }; return ctx
      end,
    },
    store = { new = function() return store_obj end },
    verify_param = { new = function() return vp_obj end },
  }
  return deps, calls
end

describe("ws_tls.build_context", function()
  it("(a) VERIFY_PEER loads the system CA store against a good cert", function()
    local deps, calls = fake_deps()
    ws_tls.build_context(deps, "api.wifihaven.net", {})
    assert.are.equal("PEER", calls.verify)
    assert.are.equal(ws_tls.CA_BUNDLE, calls.store_added[1])
    assert.is_true(calls.set_store)
  end)

  it("(b) binds hostname verification to the connect host (wrong host rejected)", function()
    -- luaossl does NOT auto-enforce the hostname; build_context MUST set the
    -- verify-param host so openssl rejects a valid-chain cert for another name.
    local deps, calls = fake_deps()
    ws_tls.build_context(deps, "api.wifihaven.net", {})
    assert.is_true(calls.set_param)
    assert.are.equal("api.wifihaven.net", calls.param_host)
  end)

  it("(c) insecure=true bypasses verification (loopback self-signed escape hatch)", function()
    local deps, calls = fake_deps()
    ws_tls.build_context(deps, "127.0.0.1", { insecure = true })
    assert.are.equal("NONE", calls.verify)
    assert.are.equal(0, #calls.store_added)   -- no store loaded
    assert.is_false(calls.set_store)
    assert.is_false(calls.set_param)           -- no hostname binding
    assert.is_nil(calls.param_host)
  end)

  it("falls back to addDefaults() when the explicit bundle path is absent", function()
    local deps, calls = fake_deps({ add_fails = true })
    ws_tls.build_context(deps, "api.wifihaven.net", {})
    assert.are.equal(1, calls.store_defaults)
    assert.is_true(calls.set_store)
  end)

  it("builds a client context (context.new server flag = false)", function()
    local deps, calls = fake_deps()
    ws_tls.build_context(deps, "api.wifihaven.net", {})
    assert.are.equal("TLS", calls.ctx_new.proto)
    assert.is_false(calls.ctx_new.server)
  end)
end)

describe("ws_tls.host_matches (#2182 explicit RFC 6125 hostname match)", function()
  it("matches an exact name (case-insensitively)", function()
    assert.is_true(ws_tls.host_matches({ "api.wifihaven.net" }, "api.wifihaven.net"))
    assert.is_true(ws_tls.host_matches({ "API.WifiHaven.NET" }, "api.wifihaven.net"))
    assert.is_true(ws_tls.host_matches({ "api.wifihaven.net" }, "API.wifihaven.NET"))
  end)

  it("accepts a single-label wildcard match", function()
    assert.is_true(ws_tls.host_matches({ "*.badssl.com" }, "a.badssl.com"))
    assert.is_true(ws_tls.host_matches({ "*.wifihaven.net" }, "api-staging.wifihaven.net"))
  end)

  it("REJECTS the #2182 case: wildcard does not span two labels", function()
    -- The exact MITM cert Gate 3a points at: *.badssl.com must NOT cover the
    -- 3-label wrong.host.badssl.com. This is the regression this fix closes.
    assert.is_false(ws_tls.host_matches(
      { "*.badssl.com", "badssl.com" }, "wrong.host.badssl.com"))
  end)

  it("REJECTS a wildcard against the bare apex", function()
    assert.is_false(ws_tls.host_matches({ "*.badssl.com" }, "badssl.com"))
  end)

  it("REJECTS a name that shares only a suffix", function()
    assert.is_false(ws_tls.host_matches(
      { "api.wifihaven.net" }, "evil-api.wifihaven.net"))
    assert.is_false(ws_tls.host_matches(
      { "wifihaven.net" }, "api.wifihaven.net"))
  end)

  it("REJECTS when there are no names, or host is empty/nil", function()
    assert.is_false(ws_tls.host_matches({}, "api.wifihaven.net"))
    assert.is_false(ws_tls.host_matches(nil, "api.wifihaven.net"))
    assert.is_false(ws_tls.host_matches({ "api.wifihaven.net" }, ""))
    assert.is_false(ws_tls.host_matches({ "api.wifihaven.net" }, nil))
  end)

  it("does not wildcard-match an IPv4 literal host", function()
    assert.is_false(ws_tls.host_matches({ "*.168.1.1" }, "192.168.1.1"))
    assert.is_true(ws_tls.host_matches({ "192.168.1.1" }, "192.168.1.1"))
  end)
end)

describe("ws_tls.peer_dns_names (#2182 identity extraction)", function()
  -- A fake luaossl x509 exposing the same surface build_context's peer check
  -- uses on-target: getSubjectAlt() → an altname iterated via its __pairs
  -- metamethod yielding (type, value); getSubject():each() → (nid, value).
  local function fake_cert(sans, cn)
    local san_obj
    if sans and #sans > 0 then
      san_obj = setmetatable({}, {
        __pairs = function(_)
          local i = 0
          return function()
            i = i + 1
            local e = sans[i]
            if e then return e[1], e[2] end
          end
        end,
      })
    end
    local subject = {
      each = function()
        local yielded = false
        return function()
          if cn and not yielded then yielded = true; return "CN", cn end
        end
      end,
    }
    return {
      getSubjectAlt = function() return san_obj end,
      getSubject = function() return subject end,
    }
  end

  it("extracts lowercased dNSName SAN entries", function()
    local names = ws_tls.peer_dns_names(fake_cert(
      { { "DNS", "API-Staging.WifiHaven.net" }, { "DNS", "alt.wifihaven.net" } }, nil))
    assert.are.same({ "api-staging.wifihaven.net", "alt.wifihaven.net" }, names)
  end)

  it("ignores non-DNS SAN entries (IP/email/URI)", function()
    local names = ws_tls.peer_dns_names(fake_cert(
      { { "IP", "10.0.0.1" }, { "DNS", "api.wifihaven.net" }, { "email", "x@y.z" } }, nil))
    assert.are.same({ "api.wifihaven.net" }, names)
  end)

  it("falls back to CN only when there is no dNSName SAN", function()
    local names = ws_tls.peer_dns_names(fake_cert(nil, "api.wifihaven.net"))
    assert.are.same({ "api.wifihaven.net" }, names)
  end)

  it("ignores the CN when a dNSName SAN is present", function()
    local names = ws_tls.peer_dns_names(fake_cert(
      { { "DNS", "san.wifihaven.net" } }, "cn.wifihaven.net"))
    assert.are.same({ "san.wifihaven.net" }, names)
  end)

  it("end-to-end: badssl wrong-host cert does NOT match the connect host", function()
    -- The real cert shape from wrong.host.badssl.com (SAN *.badssl.com,
    -- badssl.com) verified on-target — must fail the host check.
    local cert = fake_cert({ { "DNS", "*.badssl.com" }, { "DNS", "badssl.com" } }, "*.badssl.com")
    assert.is_false(ws_tls.host_matches(
      ws_tls.peer_dns_names(cert), "wrong.host.badssl.com"))
  end)

  it("end-to-end: the staging cert matches its connect host", function()
    local cert = fake_cert({ { "DNS", "api-staging.wifihaven.net" } }, "api-staging.wifihaven.net")
    assert.is_true(ws_tls.host_matches(
      ws_tls.peer_dns_names(cert), "api-staging.wifihaven.net"))
  end)
end)

describe("ws_tls.format_starttls_error", function()
  it("maps a numeric code through the cqueues error-string helper", function()
    local strerror = function(c)
      assert.are.equal(-1935895353, c); return "Unknown TLS/SSL error"
    end
    local msg = ws_tls.format_starttls_error(-1935895353, strerror)
    assert.is_truthy(msg:find("Unknown TLS/SSL error", 1, true))
    assert.is_truthy(msg:find("-1935895353", 1, true))  -- raw code kept for grep
  end)

  it("falls back to the raw value when no helper resolves it", function()
    local msg = ws_tls.format_starttls_error(-1935895353, function() return nil end)
    assert.is_truthy(msg:find("-1935895353", 1, true))
  end)
end)
