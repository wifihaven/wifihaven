-- ws_usage_split_spec.lua — unit tests for the #1017 oversized-UsageReport split.
--
-- Over the websocket each frame is bounded by ws_max_frame_bytes (design §5.3).
-- A UsageReport whose encoded body exceeds the cap is split across multiple
-- `usage` frames, each independently idempotent (the server dedups on
-- (routerId, periodStart, mac, host) so re-split records can't double-count).
-- The split is a pure function over the report's `records` array, tested here
-- with an injected encoder so the size math is deterministic.

local split = require("wifihaven.ws_usage_split")

-- Fake encoder: body size ≈ a fixed envelope (20) + 10 bytes per record. Lets
-- the tests reason about the cap in record units without a real JSON encoder.
local function fake_encode(report)
  return string.rep("x", 20 + 10 * #(report.records or {}))
end

local function report(n)
  local recs = {}
  for i = 1, n do recs[i] = { mac = "aa", host = "h" .. i, bytes = i } end
  return { routerId = "r", periodStart = "s", periodEnd = "e", records = recs }
end

describe("ws_usage_split.split", function()
  it("returns the report unchanged (single chunk) when it fits the cap", function()
    local r = report(3)                       -- 20 + 30 = 50 bytes
    local chunks = split.split(r, 100, fake_encode)
    assert.are.equal(1, #chunks)
    assert.are.equal(3, #chunks[1].records)
    assert.are.equal("s", chunks[1].periodStart)
    assert.are.equal("e", chunks[1].periodEnd)
  end)

  it("splits into multiple frames that each fit the cap", function()
    -- cap 60 → header 20 + 4 records (40) = 60 fits; 5 records (50) = 70 too big.
    local chunks = split.split(report(10), 60, fake_encode)
    assert.is_true(#chunks >= 3)
    for _, c in ipairs(chunks) do
      assert.is_true(#fake_encode(c) <= 60,
        "chunk encodes to " .. #fake_encode(c) .. " > 60")
      -- period + routerId metadata is preserved on every chunk so each frame is
      -- a standalone, idempotent UsageReport.
      assert.are.equal("r", c.routerId)
      assert.are.equal("s", c.periodStart)
      assert.are.equal("e", c.periodEnd)
    end
  end)

  it("preserves every record exactly once across the split", function()
    local chunks = split.split(report(10), 60, fake_encode)
    local seen = {}
    for _, c in ipairs(chunks) do
      for _, rec in ipairs(c.records) do
        assert.is_nil(seen[rec.host], "record " .. rec.host .. " duplicated")
        seen[rec.host] = true
      end
    end
    for i = 1, 10 do assert.is_true(seen["h" .. i], "record h" .. i .. " lost") end
  end)

  it("emits an oversized single record as its own solo frame (cannot split further)", function()
    -- cap 25 → even 1 record (20+10=30) exceeds the cap. We still emit it
    -- (one record per frame is the minimum) rather than drop usage data.
    local chunks = split.split(report(2), 25, fake_encode)
    assert.are.equal(2, #chunks)
    assert.are.equal(1, #chunks[1].records)
    assert.are.equal(1, #chunks[2].records)
  end)

  it("returns an empty list for a report with no records", function()
    local chunks = split.split(report(0), 100, fake_encode)
    assert.are.same({}, chunks)
  end)
end)
