-- ws_spool.lua — bounded tmpfs outbound-frame bridge between the agent and the
-- wifihaven-ws sidecar (#1848).
--
-- The main agent and the sidecar are separate procd processes (the sidecar owns
-- the cqueues event loop; the agent loop is unchanged). When ws is enabled, the
-- agent hands each outbound usage/events body to the sidecar by APPENDING one
-- NDJSON frame line here; the sidecar DRAINS new complete lines by byte offset.
-- The APPEND path is the same lock-free single-writer (agent) / single-reader
-- (sidecar) pattern the nflog spool uses. The EVICTION path is not: bounding the
-- spool rewrites the file, which renumbers every byte offset in it including the
-- reader's cursor in the other process. That rewrite is the one cross-process
-- race here, and #2634 is what it cost — see the eviction ledger below, which
-- publishes the shift so the reader can follow it.
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

-- Bound on the drain's ledger/spool seqlock retries (#2634). Three is enough to
-- ride out an eviction landing mid-read; more would risk spending the whole
-- drain re-reading on a router that evicts on every append.
M.MAX_SEQLOCK_TRIES = 3

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
-- The SINGLE definition of the ledger's name (`<spool>.written`). paths.lua deliberately does not
-- carry a second copy (#2634 review).
function M.ledger_path(path) return path .. ".written" end
local ledger_path = M.ledger_path

-- The ledger holds the TOTAL BYTES EVER APPENDED to the spool, not the bytes
-- evicted. That distinction matters: with a total, the reader derives the stream
-- offset of the file's first surviving byte as `written - size`, which is
-- correct whether the missing prefix went away because the writer EVICTED it or
-- because the copytruncate cron emptied the file. An evicted-bytes ledger only
-- describes the first, and the two can mask each other — a rotation followed by
-- an eviction leaves the cursor exactly at the new EOF, which is indistinguishable
-- from "nothing new" and strands the reader past live data (pinned by the
-- rotation+eviction spec case).
local function read_ledger(path, open_fn)
  local raw = slurp(ledger_path(path), open_fn)
  if not raw then return nil end            -- absent: no information yet
  return tonumber(raw:match("%d+"))
end

local function file_size(path, open_fn)
  local f = open_fn(path, "r")
  if not f then return 0 end
  local n = f:seek("end") or 0
  f:close()
  return n
end

-- Add `bytes` to the running total. Seeds from the spool's CURRENT size when the
-- ledger does not exist yet, so a router upgrading with an already-full spool
-- starts consistent (`written >= size`) instead of reporting a total far below
-- the file it describes, which would peg the reader at EOF.
--
-- Returns true on success, false if the ledger could not be written. A failure
-- is NOT silent-by-design: the most likely cause of open(…,"w") failing on tmpfs
-- is /tmp being FULL, which is exactly the condition under which eviction runs on
-- every append — i.e. the fix reverts to the #2634 full-replay bug precisely when
-- that is most expensive. append_bounded propagates this so the caller can log
-- and meter it (docs/process/no-dark-by-default.md).
local function bump_ledger(path, bytes, open_fn)
  if bytes <= 0 then return true end
  local total = read_ledger(path, open_fn)
  if total == nil then total = file_size(path, open_fn) end
  total = total + bytes
  local f = open_fn(ledger_path(path), "w")
  if not f then return false end
  f:write(string.format("%d", total))   -- %d, not tostring: a large running
  f:close()                             -- total must never render as 1.2e+14
  return true                           -- and reparse as 1
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
  -- #2634: count this entry into the total-written ledger BEFORE the spool write.
  -- Order is load-bearing: ledger-then-spool can only make the reader think less
  -- has been written than really has (it re-reads and duplicates, which the
  -- server dedups); spool-then-ledger would make it think MORE was written and
  -- skip live frames.
  local ledger_ok = bump_ledger(path, #entry, open_fn)

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
    if not wf then return nil, dropped, ledger_ok end
    wf:write(rebuilt)
    wf:close()
    return true, dropped, ledger_ok
  end

  local af = open_fn(path, "a")
  if not af then return nil, 0, ledger_ok end
  af:write(entry)
  af:close()
  return true, 0, ledger_ok
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

  -- #2634: the cursor is kept in STREAM coordinates (bytes consumed since the
  -- spool began), not as a raw file offset, because the file's coordinate space
  -- shifts under us: the writer evicts from the front to stay under the cap, and
  -- the copytruncate cron empties it outright. `written - size` is the stream
  -- offset of the file's first surviving byte and covers both.
  --
  -- Seqlock: the ledger read and the spool read are separate syscalls with no
  -- lock between them, and the writer is another process. If it appends or
  -- evicts in that window we would compute the wrong file offset and could skip
  -- live frames. Re-read the ledger afterwards and retry when it moved, bounded
  -- at MAX_SEQLOCK_TRIES so a busy router cannot livelock the drain; the last
  -- attempt is taken as-is, which is no worse than the pre-#2634 behaviour.
  local data, base
  for _ = 1, M.MAX_SEQLOCK_TRIES do
    local written = read_ledger(path, open_fn)

    local f = open_fn(path, "r")
    if not f then return {} end
    local size = f:seek("end")
    if size == nil then f:close(); return {} end

    -- No ledger yet (pre-#2634 spool, or a hand-deleted file): assume nothing
    -- was lost off the front. Worst case that re-reads the current file once.
    base = (written and written >= size) and (written - size) or 0

    -- First drain adopts the current file start, so a restart reads what is in
    -- the spool rather than re-deriving the router's whole history. Thereafter a
    -- cursor behind the file start means content was evicted or truncated out
    -- from under us — resync to what survives instead of reading a fragment.
    if state.consumed == nil or state.consumed < base then state.consumed = base end

    local off = state.consumed - base
    if off > size then off = size end     -- ledger behind the file: nothing new
    f:seek("set", off)
    data = f:read("*a") or ""
    f:close()

    if read_ledger(path, open_fn) == written then break end
  end
  if data == "" then return {} end

  local lines, consumed = {}, 0
  for body, nl in data:gmatch("([^\n]*)(\n?)") do
    if nl == "\n" then
      lines[#lines + 1] = body
      consumed = consumed + #body + 1
    end
  end
  state.consumed = state.consumed + consumed
  return lines
end

return M
