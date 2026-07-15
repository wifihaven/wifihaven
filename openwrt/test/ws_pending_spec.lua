-- ws_pending_spec.lua — the #2229 event-driven ws-apply trigger format.
--
-- The sidecar encode()s "<etag>\t<uptime>" the moment it persists a pushed
-- snapshot; the agent decode()s it every on_tick to (a) apply the push promptly
-- and (b) stamp push→apply latency. These assert the two sides agree on the
-- wire format and that decode is tolerant of the degraded shapes the agent can
-- actually observe (no uptime, empty file, legacy bare etag, torn read).

local ws_pending = require("wifihaven.ws_pending")

describe("ws_pending.encode/decode round-trip", function()
  it("round-trips an etag and an uptime stamp", function()
    local etag = '"sha256:abc123"'
    local line = ws_pending.encode(etag, 1386435.51)
    local got_etag, got_up = ws_pending.decode(line)
    assert.are.equal(etag, got_etag)
    assert.are.equal(1386435.51, got_up)
  end)

  it("round-trips when the uptime stamp is absent (nil)", function()
    -- The sidecar couldn't read /proc/uptime: etag still triggers the apply,
    -- the agent just skips the latency observation.
    local etag = '"sha256:no-stamp"'
    local line = ws_pending.encode(etag, nil)
    local got_etag, got_up = ws_pending.decode(line)
    assert.are.equal(etag, got_etag)
    assert.is_nil(got_up)
  end)
end)

describe("ws_pending.decode edge cases", function()
  it("returns nil for an empty string (file present but blank)", function()
    assert.is_nil(ws_pending.decode(""))
  end)

  it("returns nil for a non-string (absent file → nil read)", function()
    assert.is_nil(ws_pending.decode(nil))
  end)

  it("tolerates a legacy bare-etag line with no tab", function()
    -- An older sidecar that wrote only the etag (no stamp) must still trigger
    -- a new agent — it just yields no latency.
    local etag, up = ws_pending.decode('"sha256:legacy"')
    assert.are.equal('"sha256:legacy"', etag)
    assert.is_nil(up)
  end)

  it("ignores a trailing newline the writer/OS may add", function()
    local etag, up = ws_pending.decode('"sha256:nl"\t1234.5\n')
    assert.are.equal('"sha256:nl"', etag)
    assert.are.equal(1234.5, up)
  end)

  it("returns a nil uptime when the stamp is unparseable", function()
    local etag, up = ws_pending.decode('"sha256:bad"\t')
    assert.are.equal('"sha256:bad"', etag)
    assert.is_nil(up)
  end)
end)
