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

  function fs.rename(from, to)
    if fs.data[from] == nil then return nil end
    fs.data[to] = fs.data[from]
    fs.data[from] = nil
    return true
  end

  return fs
end

describe("ws_spool.append_bounded + drain", function()
  it("round-trips appended frame lines through a drain", function()
    local fs = new_fs()
    ws_spool.append_bounded("/tmp/sp", '{"op":"usage","seq":1}', 1e6, fs.open, fs.rename)
    ws_spool.append_bounded("/tmp/sp", '{"op":"events","seq":2}', 1e6, fs.open, fs.rename)
    local state = {}
    local lines = ws_spool.drain("/tmp/sp", state, fs.open)
    assert.are.same({ '{"op":"usage","seq":1}', '{"op":"events","seq":2}' }, lines)
  end)

  it("drains only NEW complete lines on a second call (offset advances)", function()
    local fs = new_fs()
    local state = {}
    ws_spool.append_bounded("/tmp/sp", "a", 1e6, fs.open, fs.rename)
    assert.are.same({ "a" }, ws_spool.drain("/tmp/sp", state, fs.open))
    -- nothing new yet
    assert.are.same({}, ws_spool.drain("/tmp/sp", state, fs.open))
    ws_spool.append_bounded("/tmp/sp", "b", 1e6, fs.open, fs.rename)
    assert.are.same({ "b" }, ws_spool.drain("/tmp/sp", state, fs.open))
  end)

  it("returns nothing for a missing spool file", function()
    local fs = new_fs()
    assert.are.same({}, ws_spool.drain("/tmp/none", {}, fs.open))
  end)

  it("resets the offset when the spool was truncated/rotated under it", function()
    -- #2634 changed HOW the cursor is expressed (stream coordinates against the
    -- <spool>.written ledger), not WHETHER this holds. Two halves, both required:
    -- the ledger-INTACT rotation, and the LOST-SYNC case where the cursor ends up
    -- past everything the ledger can account for. The second is the one that
    -- matters — it is the recovery `size < offset -> offset = 0` used to provide.
    local fs = new_fs()
    local state = {}
    ws_spool.append_bounded("/tmp/sp", "first-long-line", 1e6, fs.open, fs.rename)
    ws_spool.drain("/tmp/sp", state, fs.open)
    fs.data["/tmp/sp"] = ""                       -- copytruncate, ledger intact
    assert.are.same({}, ws_spool.drain("/tmp/sp", state, fs.open))
    ws_spool.append_bounded("/tmp/sp", "x", 1e6, fs.open, fs.rename)
    assert.are.same({ "x" }, ws_spool.drain("/tmp/sp", state, fs.open))
  end)

  it("resyncs when the cursor outruns the ledger (/tmp cleared under the sidecar)", function()
    -- The sidecar is its own procd process and does NOT restart when /tmp is
    -- cleared, so it keeps a megabyte-scale cursor against a spool that can never
    -- grow back past it. Without a lost-sync branch the drain wedges forever and
    -- every later frame is silently lost.
    local fs = new_fs()
    local state = {}
    for i = 1, 20 do
      ws_spool.append_bounded("/tmp/sp", "line" .. i .. "xxxx", 1e6, fs.open, fs.rename)
    end
    ws_spool.drain("/tmp/sp", state, fs.open)
    fs.data["/tmp/sp"] = nil                      -- /tmp cleared: spool AND
    fs.data[ws_spool.ledger_path("/tmp/sp")] = nil -- ledger both gone
    for i = 21, 23 do
      ws_spool.append_bounded("/tmp/sp", "line" .. i .. "xxxx", 1e6, fs.open, fs.rename)
    end
    assert.are.same({ "line21xxxx", "line22xxxx", "line23xxxx" },
                    ws_spool.drain("/tmp/sp", state, fs.open))
  end)

  it("still delivers frames when the ledger write fails and later recovers", function()
    -- /tmp full: the ledger write fails while the spool append lands. The ledger
    -- then UNDER-counts, which is the recoverable direction — the cursor outruns
    -- it and the lost-sync branch resyncs. Frames must keep flowing, never as a
    -- fragment, and delivery must resume when the ledger recovers.
    local fs = new_fs()
    local realopen = fs.open
    fs.open = function(path, mode)
      if path:match("%.written$") and (mode or ""):match("w") then return nil end
      return realopen(path, mode)
    end
    local state = {}
    for i = 1, 3 do
      ws_spool.append_bounded("/tmp/sp", "line" .. i .. "xxxx", 1e6, fs.open, fs.rename)
    end
    local got = ws_spool.drain("/tmp/sp", state, fs.open)
    assert.are.equal("line3xxxx", got[#got])
    for _, l in ipairs(got) do assert.are.equal(9, #l, "fragment: " .. l) end
  end)

  it("never ships a fragment when a failed spool write leaves the ledger ahead", function()
    -- The one hazard the ledger-first ordering accepts: the bump lands, the spool
    -- write then fails, so `written` exceeds the stream and `written - size`
    -- overshoots. The reader must not seek into the middle of a line and emit the
    -- tail as if it were a frame — it resyncs and duplicates instead, which the
    -- server dedups.
    local fs = new_fs()
    local state = {}
    for i = 1, 3 do
      ws_spool.append_bounded("/tmp/sp", "line" .. i .. "xxxx", 1e6, fs.open, fs.rename)
    end
    ws_spool.drain("/tmp/sp", state, fs.open)

    local realopen = fs.open
    fs.open = function(path, mode)
      if path == "/tmp/sp" and (mode or ""):match("[wa]") then return nil end
      return realopen(path, mode)
    end
    -- Uneven length, so an overshoot would land mid-line rather than on a boundary.
    assert.is_nil(ws_spool.append_bounded("/tmp/sp", "a-much-longer-line", 1e6, fs.open, fs.rename))
    fs.open = realopen
    ws_spool.append_bounded("/tmp/sp", "line4xxxx", 1e6, fs.open, fs.rename)

    local got = ws_spool.drain("/tmp/sp", state, fs.open)
    for _, l in ipairs(got) do
      assert.is_true(l:match("^line%d+xxxx$") ~= nil,
                     "fragment shipped as a frame: " .. string.format("%q", l))
    end
    assert.are.equal("line4xxxx", got[#got])
  end)

  -- #2634: the eviction/cursor interaction. append_bounded bounds the spool by
  -- REWRITING it shorter, and the pre-#2634 drain treated "shorter than my
  -- cursor" as a copytruncate rotation and restarted from byte 0. Both are
  -- individually reasonable; together they made every post-eviction drain replay
  -- the whole surviving spool. Measured on hardware: 591 event frames in 60s for
  -- a handful of real events, against 7 with the fix.
  it("does not replay surviving lines after a cap eviction shrank the spool", function()
    local fs = new_fs()
    local state = {}
    for i = 1, 5 do
      ws_spool.append_bounded("/tmp/sp", "line" .. i .. "xxxx", 1000, fs.open, fs.rename)
    end
    assert.are.equal(5, #ws_spool.drain("/tmp/sp", state, fs.open))
    ws_spool.append_bounded("/tmp/sp", "line6xxxx", 40, fs.open, fs.rename)
    assert.are.same({ "line6xxxx" }, ws_spool.drain("/tmp/sp", state, fs.open))
  end)

  it("still returns the new line when eviction drops everything before it", function()
    local fs = new_fs()
    local state = {}
    for i = 1, 3 do
      ws_spool.append_bounded("/tmp/sp", "line" .. i .. "xxxx", 1000, fs.open, fs.rename)
    end
    ws_spool.drain("/tmp/sp", state, fs.open)
    ws_spool.append_bounded("/tmp/sp", "line9xxxx", 10, fs.open, fs.rename)
    assert.are.same({ "line9xxxx" }, ws_spool.drain("/tmp/sp", state, fs.open))
  end)

  it("still restarts cleanly when a rotation and an eviction both land between drains", function()
    -- Where the rotation belt and the ledger interact: a cron copytruncate empties
    -- the spool, then the writer evicts on a file already shorter than the cursor.
    local fs = new_fs()
    local state = {}
    for i = 1, 4 do
      ws_spool.append_bounded("/tmp/sp", "line" .. i .. "xxxx", 1000, fs.open, fs.rename)
    end
    ws_spool.drain("/tmp/sp", state, fs.open)
    fs.data["/tmp/sp"] = ""
    for i = 5, 8 do
      ws_spool.append_bounded("/tmp/sp", "line" .. i .. "xxxx", 30, fs.open, fs.rename)
    end
    local got = ws_spool.drain("/tmp/sp", state, fs.open)
    assert.is_true(#got > 0)
    for _, l in ipairs(got) do
      assert.are.equal(9, #l, "fragmented line: " .. l)
    end
    assert.are.equal("line8xxxx", got[#got])
    assert.are.same({}, ws_spool.drain("/tmp/sp", state, fs.open))
  end)

  it("reports a failed ledger write instead of swallowing it", function()
    local fs = new_fs()
    local realopen = fs.open
    fs.open = function(path, mode)
      if path:match("%.written$") and (mode or ""):match("w") then return nil end
      return realopen(path, mode)
    end
    local ok, _, ledger_ok = ws_spool.append_bounded("/tmp/sp", "line1xxxx", 1e6, fs.open, fs.rename)
    assert.is_true(ok)                -- the datum is spooled: it IS readable
    assert.is_false(ledger_ok)        -- and the caller is told the ledger lagged
  end)

  it("reports ledger success on an ordinary append", function()
    local fs = new_fs()
    local ok, dropped, ledger_ok = ws_spool.append_bounded("/tmp/sp", "a", 1e6, fs.open, fs.rename)
    assert.is_true(ok)
    assert.are.equal(0, dropped)
    assert.is_true(ledger_ok)
  end)

  it("never loses a frame to a drain landing inside the eviction rewrite", function()
    -- The eviction rewrite used to be open(path,"w"), which truncates at OPEN, so
    -- the spool sat EMPTY for the whole rebuild while the ledger already counted
    -- the new entry. A drain in that window saw size 0, concluded the entire
    -- stream had been evicted, and advanced the cursor past a frame that was
    -- never readable — permanent loss, and worse than the replay the pre-#2634
    -- code paid for the same interleaving. At the 1 MiB default cap the whole
    -- spool is rewritten on every append, so the window was open constantly.
    -- Writing to <spool>.tmp and renaming over closes it: the reader re-opens the
    -- path each drain, so it sees the whole old file or the whole new one.
    local fs = new_fs()
    local state = {}
    for i = 1, 5 do
      ws_spool.append_bounded("/tmp/sp", "line" .. i .. "xxxx", 1e6, fs.open, fs.rename)
    end
    ws_spool.drain("/tmp/sp", state, fs.open)

    -- Drive the writer's rewrite by hand, draining at the moment the spool would
    -- have been truncated but the new content is not in place yet.
    local realrename = fs.rename
    local drained_mid
    fs.rename = function(from, to)
      drained_mid = ws_spool.drain("/tmp/sp", state, fs.open)
      return realrename(from, to)
    end
    ws_spool.append_bounded("/tmp/sp", "line6xxxx", 30, fs.open, fs.rename)
    fs.rename = realrename

    -- Mid-rewrite the reader sees the OLD file, with the ledger already counting
    -- the pending entry. That over-count makes it re-read the tail — a duplicate,
    -- which the server dedups. What it must never do is skip ahead.
    for _, l in ipairs(drained_mid) do
      assert.are.equal(9, #l, "fragment mid-rewrite: " .. l)
    end
    local got = ws_spool.drain("/tmp/sp", state, fs.open)
    local saw_six = false
    for _, l in ipairs(got) do
      assert.are.equal(9, #l, "fragment: " .. l)
      if l == "line6xxxx" then saw_six = true end
    end
    assert.is_true(saw_six, "the frame written during the rewrite was lost")
  end)

  it("self-bounds the spool at the byte cap, dropping oldest lines", function()
    local fs = new_fs()
    -- cap small: each line "L<n>\n" is 4 bytes. Cap 10 holds ~2 lines.
    for i = 1, 5 do
      ws_spool.append_bounded("/tmp/sp", "L" .. i, 10, fs.open, fs.rename)
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
    for i = 1, 4 do ws_spool.append_bounded("/tmp/sp", "L" .. i, 10, fs.open, fs.rename) end
    -- the 4th append must have evicted at least one earlier line.
    local _, dropped = ws_spool.append_bounded("/tmp/sp", "L5", 10, fs.open, fs.rename)
    assert.is_true(dropped >= 1)
  end)
end)
