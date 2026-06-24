-- ws_spool.lua — bounded tmpfs outbound-frame bridge between the agent and the
-- wifihaven-ws sidecar (#1848).
--
-- The main agent and the sidecar are separate procd processes (the sidecar owns
-- the cqueues event loop; the agent loop is unchanged). When ws is enabled, the
-- agent hands each outbound usage/events body to the sidecar by APPENDING one
-- NDJSON frame line here; the sidecar DRAINS new complete lines by byte offset.
-- This is the same lock-free single-writer (agent) / single-reader (sidecar)
-- pattern the nflog spool uses — no cross-process file-rewrite race.
--
-- Bounded-writes (docs/process/router-agent-bounded-writes.md): /tmp is tmpfs =
-- RAM. The writer self-bounds the spool at `max_bytes` on every append by
-- dropping OLDEST whole lines first (the existing usage retry queue's
-- oldest-first high-water behaviour, §5.2) so a wedged socket can never grow
-- tmpfs without limit. A copytruncate rotation cron entry is added as a second
-- belt (the drain detects the resulting shrink and resets its offset).
--
-- Pure over an injected `open_fn` (io.open in production), so the whole
-- writer/reader contract is unit-tested on the dev host exactly as it runs under
-- OpenWrt Lua 5.1.

local M = {}

-- read whole file contents (or nil if absent), via the injected opener.
local function slurp(path, open_fn)
  local f = open_fn(path, "r")
  if not f then return nil end
  local data = f:read("*a") or ""
  f:close()
  return data
end

-- append_bounded(path, line, max_bytes, open_fn) → ok, dropped
--
-- Appends `line` + "\n". Before appending, if the existing spool plus the new
-- line would exceed `max_bytes`, drops oldest whole lines until it fits (or only
-- the new line remains — a single line over the cap is still written; we never
-- silently lose the newest datum). Returns ok plus the count of lines dropped to
-- make room, so the caller can meter spool pressure.
function M.append_bounded(path, line, max_bytes, open_fn)
  open_fn = open_fn or io.open
  local entry = line .. "\n"
  local existing = slurp(path, open_fn) or ""
  local dropped = 0

  if #existing + #entry > max_bytes then
    -- Collect existing whole lines (drop any partial tail) and evict from the
    -- front until existing + entry fits the cap.
    local lines = {}
    for body, nl in existing:gmatch("([^\n]*)(\n?)") do
      if nl == "\n" then lines[#lines + 1] = body end
    end
    local total = 0
    for _, l in ipairs(lines) do total = total + #l + 1 end
    local i = 1
    while i <= #lines and total + #entry > max_bytes do
      total = total - (#lines[i] + 1)
      i = i + 1
      dropped = dropped + 1
    end
    -- Rewrite the spool with the surviving lines, then the new entry.
    local kept = {}
    for j = i, #lines do kept[#kept + 1] = lines[j] end
    local rebuilt = (#kept > 0 and (table.concat(kept, "\n") .. "\n") or "") .. entry
    local wf = open_fn(path, "w")
    if not wf then return nil, dropped end
    wf:write(rebuilt)
    wf:close()
    return true, dropped
  end

  local af = open_fn(path, "a")
  if not af then return nil, 0 end
  af:write(entry)
  af:close()
  return true, 0
end

-- drain(path, state, open_fn) → { line, … }
--
-- Returns the complete lines appended since the last drain, advancing
-- state.offset past them. A partial trailing line (no newline yet) is left in
-- place for the next call. Detects a copytruncate/rotation (file shorter than
-- our cursor) and resets the offset. Mirrors nflog.drain_file — the proven
-- offset-cursor pattern — but is its own spool (a distinct concern), so it stays
-- self-contained rather than coupling ws to the nflog module.
function M.drain(path, state, open_fn)
  open_fn = open_fn or io.open
  state = state or {}
  state.offset = state.offset or 0

  local f = open_fn(path, "r")
  if not f then return {} end
  local size = f:seek("end")
  if size == nil then f:close(); return {} end
  if size < state.offset then state.offset = 0 end
  f:seek("set", state.offset)
  local data = f:read("*a") or ""
  f:close()
  if data == "" then return {} end

  local lines, consumed = {}, 0
  for body, nl in data:gmatch("([^\n]*)(\n?)") do
    if nl == "\n" then
      lines[#lines + 1] = body
      consumed = consumed + #body + 1
    end
  end
  state.offset = state.offset + consumed
  return lines
end

return M
