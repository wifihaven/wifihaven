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
    local fs = new_fs()
    local state = {}
    ws_spool.append_bounded("/tmp/sp", "first-long-line", 1e6, fs.open)
    ws_spool.drain("/tmp/sp", state, fs.open)
    -- simulate copytruncate: file shrinks below the saved offset.
    fs.data["/tmp/sp"] = "x\n"
    assert.are.same({ "x" }, ws_spool.drain("/tmp/sp", state, fs.open))
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
