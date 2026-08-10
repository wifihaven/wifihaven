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
-- race here, and #2634 is what it cost — see the spool ledger below, which
-- publishes the shift so the reader can follow it.
--
-- Bounded-writes (docs/process/router-agent-bounded-writes.md): /tmp is tmpfs =
-- RAM. The writer self-bounds the spool at `max_bytes` on every append by
-- dropping OLDEST whole lines first (the existing usage retry queue's
-- oldest-first high-water behaviour, §5.2) so a wedged socket can never grow
-- tmpfs without limit. A copytruncate rotation cron entry is added as a second
-- belt; the drain follows both the eviction and the rotation through the
-- ledger described below (#2634).
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

-- #2634: the spool ledger.
--
-- The writer bounds the spool by REWRITING it shorter, which silently renumbers
-- every byte offset in the file — including the cursor the reader holds in
-- another process. Before this, drain saw only "the file got shorter" and could
-- not tell an eviction from a copytruncate rotation, so it restarted from byte
-- 0 and re-sent every surviving line. On a router pinned at the cap (the steady
-- state, since nothing truncates the spool after a successful drain) that is a
-- full replay per drain.
--
-- The writer publishes a running total of bytes appended and the reader derives
-- the file's first surviving byte from it (see the ledger block below). Same file-content
-- IPC idiom as paths.ws_health / paths.ws_pending (busybox has no `stat -c %Y`,
-- and the two processes share nothing but the filesystem). The ledger is one
-- short integer, rewritten in place — fixed-size, so it needs no rotation of
-- its own (docs/process/router-agent-bounded-writes.md).
-- The SINGLE definition of the ledger's name (`<spool>.written`). paths.lua deliberately does not
-- carry a second copy (#2634 review).
function M.ledger_path(path) return path .. ".written" end

-- Likewise the single definition of the rebuild staging name.
function M.tmp_path(path) return path .. ".tmp" end
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

-- Add `bytes` to the running total. When the ledger does not exist yet it is
-- seeded from the spool's CURRENT size instead, so a router upgrading with an
-- already-full spool starts consistent (`written >= size`) rather than reporting
-- a total far below the file it describes.
--
-- Returns true on success, false if the ledger could not be written. A failure
-- is NOT silent-by-design: the most likely cause of open(…,"w") failing on tmpfs
-- is /tmp being FULL, which is exactly the condition under which eviction runs on
-- every append — i.e. the fix reverts to the #2634 full-replay bug precisely when
-- that is most expensive. append_bounded propagates this so the caller can log
-- and meter it (docs/process/no-dark-by-default.md).
-- Bytes we appended but could not account for, per spool path. Writer-local and
-- process-local, which is exactly the right scope: the agent is the single writer
-- (#2634 review). Without this a failed bump leaves the ledger PERMANENTLY one
-- entry short, and the deficit does not stay harmless — once the spool reaches
-- the cap, `written - size` under-shoots by that entry and the drain silently
-- eats a LATER frame the caller was told had succeeded. Carrying the shortfall
-- into the next successful bump closes it.
--
-- RESIDUAL, and the lost-sync branch does NOT cover it: if the agent restarts
-- before that next bump, the carry dies with the process and the ledger stays one
-- entry short forever. The lost-sync test is `consumed > base + size`, and a
-- single missing bump lands the cursor at exactly `consumed == base + size` —
-- missed by the strict comparison by the width of the frame it should rescue, so
-- one frame is lost silently. Plausible precisely because a full /tmp is what
-- fails the bump. Closing it needs the accounting inside the spool file so
-- publish-and-account is one atomic write, which is a redesign, not a guard.
local pending_bytes = {}

local function bump_ledger(path, bytes, open_fn)
  bytes = bytes + (pending_bytes[path] or 0)
  if bytes <= 0 then return true end
  local total = read_ledger(path, open_fn)
  if total == nil then
    -- Seeding: we run AFTER the write, so the file already holds this entry AND
    -- any earlier ones whose bump failed (they were appended successfully; only
    -- their accounting was lost). Its size IS the total written.
    total = file_size(path, open_fn)
  else
    total = total + bytes
  end
  local f = open_fn(ledger_path(path), "w")
  if not f then pending_bytes[path] = bytes; return false end
  -- Check the WRITE and the CLOSE, not just the open. On tmpfs, opening an
  -- existing ledger with "w" truncates and frees its blocks first, so a full /tmp
  -- fails at the write — the very case this guard exists for. An unchecked write
  -- would leave the ledger silently EMPTIED and still report success.
  local wrote = f:write(string.format("%d", total))  -- %d, not tostring: a large
  local closed = f:close()                           -- total must never render
  if not (wrote and closed) then                     -- as 1.2e+14 and reparse as 1
    pending_bytes[path] = bytes
    return false
  end
  pending_bytes[path] = nil
  return true
end

-- append_bounded(path, line, max_bytes, open_fn, rename_fn, remove_fn)
--   → ok, dropped, ledger_ok
--
-- Appends `line` + "\n". Before appending, if the existing spool plus the new
-- line would exceed `max_bytes`, drops oldest whole lines until it fits (or only
-- the new line remains — a single line over the cap is still written; we never
-- silently lose the newest datum). Returns ok plus the count of lines dropped to
-- make room, so the caller can meter spool pressure.
function M.append_bounded(path, line, max_bytes, open_fn, rename_fn, remove_fn)
  open_fn = open_fn or io.open
  rename_fn = rename_fn or os.rename
  remove_fn = remove_fn or os.remove
  local entry = line .. "\n"
  local existing = slurp(path, open_fn) or ""
  local dropped = 0

  -- #2634: a partial tail — content not ending in a newline — can only be the
  -- prefix of an append we already reported as FAILED. drain() only ever consumes
  -- complete lines, so those bytes are by construction unconsumable: dropping them
  -- removes nothing any reader can have seen, and it is the one repair that is
  -- safe against a cursor we cannot see. Doing it here rather than at the failure
  -- also means no second copy of the spool is allocated at the moment tmpfs just
  -- reported ENOSPC.
  --
  -- It has to go through the rebuild+rename branch below, which publishes the
  -- stripped content and this entry as one atomic write. Appending would splice
  -- onto the fragment, which is the whole bug.
  local torn = #existing > 0 and existing:sub(-1) ~= "\n"
  if torn then existing = existing:match("^.*\n") or "" end
  -- #2634: the ledger is bumped only once the entry is READABLE — after the
  -- append, or after the rename that publishes the rewritten spool.
  --
  -- Bumping first is unsafe even with an atomic rename, and not merely because it
  -- over-counts: a drain landing between the bump and the publish reads the OLD
  -- file at an offset derived from the NEW total, consumes bytes it has already
  -- seen, and credits them against the pending entry's place in the stream. The
  -- cursor then equals `written` with that entry never delivered, and it is gone.
  -- (Pinned by the drain-inside-the-rewrite spec case.)
  --
  -- The entry is then READABLE but UNACCOUNTED until the bump lands, and that gap
  -- is not self-healing: the lost-sync test is `consumed > base + size`, i.e.
  -- `consumed > written`, and a missing bump leaves the cursor at exactly
  -- `consumed == written` — one entry short, missed by the strict comparison by
  -- the width of the very frame it should rescue. Widening it to `>=` is not the
  -- answer; that resyncs on every idle drain and turns the steady state back into
  -- the #2634 full replay.
  --
  -- So a failed bump REPORTS FAILURE (returns nil) even though the spool write
  -- landed. The tee (ws_outbound.make) then posts the body over HTTP, so the
  -- datum is delivered; the spooled copy may be re-read later, and a duplicate is
  -- what the server dedups. RESIDUAL: a crash between the publish and the bump
  -- has no such report and loses that one frame. Closing that needs the accounting
  -- to live INSIDE the spool file so publish and account are one atomic write.
  -- TODO(#2685).
  local ledger_ok = true

  if torn or #existing + #entry > max_bytes then
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
    -- Write-then-rename, NOT open(path,"w"). `w` truncates at OPEN, so the spool
    -- would sit EMPTY for the whole rebuild — and at the default 1 MiB cap the
    -- steady state rewrites the entire spool on every append, so that window is
    -- open constantly. A drain landing in it sees size 0 with the ledger already
    -- counting the entry, concludes the whole stream was evicted, and advances
    -- the cursor past a frame that was never readable: permanent loss, and worse
    -- than the replay the pre-#2634 code paid for the same interleaving. rename
    -- is atomic, so the reader (which re-opens the path on every drain) gets the
    -- whole old file or the whole new one, never an empty one.
    local tmp = M.tmp_path(path)
    local wf = open_fn(tmp, "w")
    if not wf then return nil, 0, ledger_ok end
    -- Check the write AND the close, not just the open: a short write here would
    -- publish a TRUNCATED spool through the rename, losing whole frames and the
    -- entry we are carrying, while telling the caller it succeeded. Same defect
    -- bump_ledger just had, on the writer that is ~150,000x larger.
    local wrote = wf:write(rebuilt)
    local closed = wf:close()
    if not (wrote and closed) then
      remove_fn(tmp)
      return nil, 0, ledger_ok
    end
    if not rename_fn(tmp, path) then
      -- Nothing was published; drop the copy rather than parking max_bytes of
      -- tmpfs until the next eviction happens to overwrite it.
      remove_fn(tmp)
      return nil, 0, ledger_ok
    end
    ledger_ok = bump_ledger(path, #entry, open_fn)
    if not ledger_ok then return nil, dropped, false end
    return true, dropped, ledger_ok
  end

  local af = open_fn(path, "a")
  if not af then return nil, 0, ledger_ok end
  -- Same write+close check as the eviction writer above, on the path that runs on
  -- every append BELOW the cap — i.e. most of them.
  --
  -- Detecting the short write is not enough on its own: `write` is buffered, so a
  -- failure part-way has ALREADY put a prefix of the entry on disk, and the next
  -- append would concatenate onto that fragment. The repair is at the TOP of the
  -- next append (see `torn`), not here — undoing it here would mean rewriting the
  -- spool SHORTER, and a shrink is the one file operation the stream-offset
  -- ledger cannot express, so a reader that had already consumed those bytes
  -- would skip the following frame.
  local wrote = af:write(entry)
  local closed = af:close()
  if not (wrote and closed) then return nil, 0, ledger_ok end
  ledger_ok = bump_ledger(path, #entry, open_fn)
  if not ledger_ok then return nil, 0, false end
  return true, 0, ledger_ok
end

-- drain(path, state, open_fn) → { line, … }
--
-- Returns the complete lines appended since the last drain, advancing
-- state.consumed past them (a STREAM offset, not a file offset — #2634). A partial trailing line (no newline yet) is left in
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
  -- at MAX_SEQLOCK_TRIES so a busy router cannot livelock the drain. A last
  -- attempt taken as-is may SKIP (a base computed too small), not merely replay,
  -- which is why the bound is a backstop and not the mechanism.
  local data
  for _ = 1, M.MAX_SEQLOCK_TRIES do
    local written = read_ledger(path, open_fn)

    local f = open_fn(path, "r")
    if not f then return {} end
    local size = f:seek("end")
    if size == nil then f:close(); return {} end

    -- No ledger yet (pre-#2634 spool, or a hand-deleted file): assume nothing
    -- was lost off the front. Worst case that re-reads the current file once.
    local base = (written and written >= size) and (written - size) or 0

    -- First drain adopts the current file start, so a restart reads what is in
    -- the spool rather than re-deriving the router's whole history. A cursor
    -- BEHIND the file start means the front was evicted or truncated away, so
    -- skip to what survives.
    -- `size == 0` is not evidence the stream advanced: it is also what a reader
    -- sees mid-rewrite, or right after a copytruncate. Advancing the cursor on it
    -- would skip frames that are about to exist. Only ever move FORWARD against a
    -- file that actually has content.
    if state.consumed == nil then state.consumed = base
    elseif state.consumed < base and size > 0 then state.consumed = base end

    -- LOST SYNC. A cursor past everything the ledger can account for means the
    -- ledger under-counts the stream: /tmp cleared under a running sidecar, the
    -- ledger file deleted, or a ledger write that failed while the spool write
    -- succeeded. origin/main recovered from this via `size < offset → offset =
    -- 0`; moving the cursor into stream coordinates removed that branch, and
    -- without a replacement the drain wedges permanently and drops every later
    -- frame in silence. Resync to the file start and re-read: duplicates, which
    -- the server dedups, in place of loss, which it cannot.
    if state.consumed > base + size then state.consumed = base end

    local off = state.consumed - base
    f:seek("set", off)
    data = f:read("*a") or ""

    -- A non-zero offset must land just past a newline. If it does not, `base`
    -- disagrees with the file by a partial line and reading on would emit a
    -- fragment as if it were a frame — resync rather than ship garbage.
    if off > 0 and data ~= "" then
      f:seek("set", off - 1)
      if (f:read(1) or ""):sub(1, 1) ~= "\n" then
        state.consumed = base
        f:seek("set", 0)
        data = f:read("*a") or ""
      end
    end
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
