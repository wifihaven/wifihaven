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

-- #2634: the eviction ledger.
--
-- The writer bounds the spool by REWRITING it shorter, which silently renumbers
-- every byte offset in the file — including the cursor the reader holds in
-- another process. Before this, drain saw only "the file got shorter" and could
-- not tell an eviction from a copytruncate rotation, so it restarted from byte
-- 0 and re-sent every surviving line. On a router pinned at the cap (the steady
-- state, since nothing truncates the spool after a successful drain) that is a
-- full replay per drain.
--
-- The writer knows exactly how many bytes it dropped, so it publishes a running
-- total here and the reader rebases its cursor by the delta. Same file-content
-- IPC idiom as paths.ws_health / paths.ws_pending (busybox has no `stat -c %Y`,
-- and the two processes share nothing but the filesystem). The ledger is one
-- short integer, rewritten in place — fixed-size, so it needs no rotation of
-- its own (docs/process/router-agent-bounded-writes.md).
local function ledger_path(path) return path .. ".evicted" end

local function read_ledger(path, open_fn)
  local raw = slurp(ledger_path(path), open_fn)
  if not raw then return 0 end
  return tonumber(raw:match("%d+")) or 0
end

local function bump_ledger(path, bytes, open_fn)
  if bytes <= 0 then return end
  local total = read_ledger(path, open_fn) + bytes
  local f = open_fn(ledger_path(path), "w")
  if not f then return end          -- best-effort: a failed bump degrades to
  f:write(tostring(total))          -- today's behaviour, never to lost data
  f:close()
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
    local evicted_bytes = 0
    while i <= #lines and total + #entry > max_bytes do
      total = total - (#lines[i] + 1)
      evicted_bytes = evicted_bytes + #lines[i] + 1
      i = i + 1
      dropped = dropped + 1
    end
    -- #2634: publish what we removed from the FRONT of the file so the sidecar
    -- can rebase its byte cursor instead of concluding it was rotated and
    -- replaying the survivors.
    bump_ledger(path, evicted_bytes, open_fn)
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

  -- #2634: rebase past anything the writer evicted from the FRONT since our
  -- last drain, BEFORE the rotation check below — an eviction and a rotation
  -- both shrink the file, and only the ledger tells them apart. On the very
  -- first drain adopt the current total as the baseline rather than subtracting
  -- the router's whole eviction history from a cursor of 0.
  local evicted = read_ledger(path, open_fn)
  if state.evicted_seen == nil then
    state.evicted_seen = evicted
  elseif evicted > state.evicted_seen then
    state.offset = math.max(0, state.offset - (evicted - state.evicted_seen))
    state.evicted_seen = evicted
  end

  local f = open_fn(path, "r")
  if not f then return {} end
  local size = f:seek("end")
  if size == nil then f:close(); return {} end
  -- Still shorter than the rebased cursor → a genuine copytruncate rotation
  -- (the cron second belt), which the ledger says nothing about. Restart.
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
