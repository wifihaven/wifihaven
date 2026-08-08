-- ws_spool_spec.lua — unit tests for the #1848 outbound frame spool bridge.
--
-- The wifihaven-ws sidecar and the main agent are separate procd processes, so
-- the agent hands outbound usage/events bodies to the sidecar over a bounded
-- tmpfs spool (design §5.2: drain the existing bounded spools OUT to frames, no
-- new UNbounded /tmp growth). The agent APPENDS one NDJSON frame line per body;
-- the sidecar DRAINS new complete lines by byte offset (the same lock-free,
-- single-writer/single-reader pattern nflog uses — no cross-process rewrite
-- race), and the writer self-bounds the file at a byte cap (oldest-first drop)
-- so a wedged socket can never grow tmpfs without limit.
--
-- Both halves are pure over injected file IO, so they run on the dev host
-- exactly as under OpenWrt Lua 5.1.

local ws_spool = require("wifihaven.ws_spool")

-- ── tiny in-memory file backend ──────────────────────────────────────────────
-- A fake `io.open`-alike supporting the read("*a")/seek used by drain and the
-- append-mode write used by the writer.
local function new_fs()
  local fs = { data = {} }

  function fs.open(path, mode)
    mode = mode or "r"
    if mode:match("r") and fs.data[path] == nil then return nil end
    local buf = fs.data[path] or ""
    local pos = (mode:match("a")) and #buf or 0
    if mode:match("w") then buf = ""; fs.data[path] = "" end
    local handle = {}
    function handle:seek(whence, off)
      if whence == "end" then pos = #(fs.data[path] or "")
      elseif whence == "set" then pos = off or 0 end
      return pos
    end
    function handle:read(_) return (fs.data[path] or ""):sub(pos + 1) end
    function handle:write(s)
      fs.data[path] = (fs.data[path] or "") .. s
      return handle
    end
    function handle:close() return true end
    return handle
  end

  return fs
end

describe("ws_spool.append_bounded + drain", function()
  it("round-trips appended frame lines through a drain", function()
    local fs = new_fs()
    ws_spool.append_bounded("/tmp/sp", '{"op":"usage","seq":1}', 1e6, fs.open)
    ws_spool.append_bounded("/tmp/sp", '{"op":"events","seq":2}', 1e6, fs.open)
    local state = {}
    local lines = ws_spool.drain("/tmp/sp", state, fs.open)
    assert.are.same({ '{"op":"usage","seq":1}', '{"op":"events","seq":2}' }, lines)
  end)

  it("drains only NEW complete lines on a second call (offset advances)", function()
    local fs = new_fs()
    local state = {}
    ws_spool.append_bounded("/tmp/sp", "a", 1e6, fs.open)
    assert.are.same({ "a" }, ws_spool.drain("/tmp/sp", state, fs.open))
    -- nothing new yet
    assert.are.same({}, ws_spool.drain("/tmp/sp", state, fs.open))
    ws_spool.append_bounded("/tmp/sp", "b", 1e6, fs.open)
    assert.are.same({ "b" }, ws_spool.drain("/tmp/sp", state, fs.open))
  end)

  it("returns nothing for a missing spool file", function()
    local fs = new_fs()
    assert.are.same({}, ws_spool.drain("/tmp/none", {}, fs.open))
  end)

  it("resets the offset when the spool was truncated/rotated under it", function()
    -- #2634 changed HOW this is expressed, not WHETHER it holds. The cursor is
    -- now kept in stream coordinates against a total-bytes-written ledger, so a
    -- rotation has to be performed the way the cron actually does it — copy the
    -- file aside, then EMPTY it (`: > "$log"`), after which the writer keeps
    -- appending. The old form poked new content straight into the file behind
    -- the writer's back, which under the new model is indistinguishable from
    -- content the reader already consumed. Same property, supported API.
    local fs = new_fs()
    local state = {}
    ws_spool.append_bounded("/tmp/sp", "first-long-line", 1e6, fs.open)
    ws_spool.drain("/tmp/sp", state, fs.open)
    fs.data["/tmp/sp"] = ""                       -- copytruncate
    assert.are.same({}, ws_spool.drain("/tmp/sp", state, fs.open))
    ws_spool.append_bounded("/tmp/sp", "x", 1e6, fs.open)
    -- the reader follows the rotation instead of wedging past the new EOF
    assert.are.same({ "x" }, ws_spool.drain("/tmp/sp", state, fs.open))
  end)

  -- #2634: the eviction/cursor interaction. append_bounded bounds the spool by
  -- REWRITING it shorter, and drain treats "shorter than my cursor" as a
  -- copytruncate rotation and restarts from byte 0. Both are individually
  -- reasonable; together they make every post-eviction drain replay the whole
  -- surviving spool. Measured on hardware: ~30 frames/s for a handful of real
  -- events, and end-to-end latency growing without bound as new events queue
  -- behind the replay.
  it("does not replay surviving lines after a cap eviction shrank the spool", function()
    local fs = new_fs()
    local state = {}
    -- 5 lines of 10 bytes each (9 chars + newline) = 50 bytes, well under cap.
    for i = 1, 5 do
      ws_spool.append_bounded("/tmp/sp", "line" .. i .. "xxxx", 1000, fs.open)
    end
    assert.are.equal(5, #ws_spool.drain("/tmp/sp", state, fs.open))

    -- Append under a cap that forces the writer to evict the two oldest lines
    -- and rewrite the file at 40 bytes — shorter than the reader's 50-byte
    -- cursor. Only the NEW line is new; line3..line5 were already delivered.
    ws_spool.append_bounded("/tmp/sp", "line6xxxx", 40, fs.open)
    assert.are.same({ "line6xxxx" }, ws_spool.drain("/tmp/sp", state, fs.open))
  end)

  it("still returns the new line when eviction drops everything before it", function()
    local fs = new_fs()
    local state = {}
    for i = 1, 3 do
      ws_spool.append_bounded("/tmp/sp", "line" .. i .. "xxxx", 1000, fs.open)
    end
    ws_spool.drain("/tmp/sp", state, fs.open)
    -- Cap smaller than one surviving line + the entry: everything older goes.
    ws_spool.append_bounded("/tmp/sp", "line9xxxx", 10, fs.open)
    assert.are.same({ "line9xxxx" }, ws_spool.drain("/tmp/sp", state, fs.open))
  end)

  it("still restarts cleanly when a rotation and an eviction both land between drains", function()
    -- The one case where the rotation belt and the #2634 ledger interact. A cron
    -- copytruncate empties the spool WITHOUT touching the ledger, so the reader
    -- gets no eviction delta for it; a later eviction then rebases the cursor
    -- down. The reader must not end up mid-file reading a fragment.
    local fs = new_fs()
    local state = {}
    for i = 1, 4 do
      ws_spool.append_bounded("/tmp/sp", "line" .. i .. "xxxx", 1000, fs.open)
    end
    ws_spool.drain("/tmp/sp", state, fs.open)          -- cursor at 40
    fs.data["/tmp/sp"] = ""                             -- cron copytruncate
    -- Refill past the cap so the writer evicts (bumping the ledger) on a file
    -- that is already shorter than the stale cursor.
    for i = 5, 8 do
      ws_spool.append_bounded("/tmp/sp", "line" .. i .. "xxxx", 30, fs.open)
    end
    local got = ws_spool.drain("/tmp/sp", state, fs.open)
    -- Whatever survived the cap must come back whole and in order, with no
    -- fragment and no duplicate.
    assert.is_true(#got > 0)
    for _, l in ipairs(got) do
      assert.are.equal(9, #l, "fragmented line: " .. l)
    end
    assert.are.equal("line8xxxx", got[#got])
    assert.are.same({}, ws_spool.drain("/tmp/sp", state, fs.open))
  end)

  it("reports a failed eviction-ledger write instead of swallowing it", function()
    local fs = new_fs()
    local realopen = fs.open
    -- Simulate a full /tmp: the ledger cannot be created, the spool still can.
    fs.open = function(path, mode)
      if path:match("%.written$") and (mode or ""):match("w") then return nil end
      return realopen(path, mode)
    end
    for i = 1, 3 do
      ws_spool.append_bounded("/tmp/sp", "line" .. i .. "xxxx", 1000, fs.open)
    end
    local ok, _, ledger_ok = ws_spool.append_bounded("/tmp/sp", "line4xxxx", 20, fs.open)
    assert.is_true(ok)                -- the datum is still spooled
    assert.is_false(ledger_ok)        -- but the caller is told the ledger failed
  end)

  it("reports ledger success when no eviction was needed", function()
    local fs = new_fs()
    local ok, dropped, ledger_ok = ws_spool.append_bounded("/tmp/sp", "a", 1e6, fs.open)
    assert.is_true(ok)
    assert.are.equal(0, dropped)
    assert.is_true(ledger_ok)
  end)

  it("self-bounds the spool at the byte cap, dropping oldest lines", function()
    local fs = new_fs()
    -- cap small: each line "L<n>\n" is 4 bytes. Cap 10 holds ~2 lines.
    for i = 1, 5 do
      ws_spool.append_bounded("/tmp/sp", "L" .. i, 10, fs.open)
    end
    assert.is_true(#(fs.data["/tmp/sp"]) <= 10,
      "spool grew past cap: " .. #(fs.data["/tmp/sp"]))
    -- the newest line survives; the oldest were dropped.
    local lines = ws_spool.drain("/tmp/sp", {}, fs.open)
    assert.are.equal("L5", lines[#lines])
    assert.is_nil((function()
      for _, l in ipairs(lines) do if l == "L1" then return true end end
    end)())
  end)

  it("reports how many lines were dropped to bound the cap", function()
    local fs = new_fs()
    for i = 1, 4 do ws_spool.append_bounded("/tmp/sp", "L" .. i, 10, fs.open) end
    -- the 4th append must have evicted at least one earlier line.
    local _, dropped = ws_spool.append_bounded("/tmp/sp", "L5", 10, fs.open)
    assert.is_true(dropped >= 1)
  end)
end)
