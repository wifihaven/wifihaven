-- blocklists.lua — fetch, cache, and garbage-collect category blocklists
--
-- Each blocklist is identified by (id, version). Files are stored at:
--   <cache_dir>/<id>-<version>.txt
-- Writes are atomic: body goes to <path>.tmp then renamed to <path>.
--
-- Public API:
--   M.fetch_and_cache(snapshot, http_get_fn, fs, cache_dir, base_url)
--     → { hosts_by_id = {[id]={hosts}}, errors = {...} }
--   M.load_cached(snapshot, fs, cache_dir)
--     → { [id] = {hosts} }
--   M.gc(snapshot, fs, cache_dir)
--     → removes stale (id, version) files not in current snapshot

local M = {}

local DEFAULT_CACHE_DIR = "/etc/wifihaven/blocklists"

-- Lazy require render module — compatible with both the production on-device
-- path (wifihaven.render) and the busted test path (render). Called at most
-- once per process; result is cached in the upvalue.
local _render_module
local function get_render()
  if not _render_module then
    local ok, m = pcall(require, "wifihaven.render")
    _render_module = ok and m or require("render")
  end
  return _render_module
end

-- classify_fetch_status(status) → bounded status enum (#1301).
--
-- The blocklist fetch surfaces as the `status` label of
-- blocklist_fetch_failures_total. It MUST stay a small bounded enum (§4
-- cardinality firewall), so a numeric HTTP status collapses to its class
-- (`4xx` / `5xx` / …) rather than the exact code, and a non-HTTP outcome maps
-- to a fixed token. #705: the old failure path logged `status=nil` for a
-- transport failure (curl returned no numeric status); that empty status is now
-- captured explicitly as "error" so the metric has a real label instead of a
-- missing one.
local function classify_fetch_status(status)
  if type(status) == "number" then
    local class = math.floor(status / 100)
    if class >= 1 and class <= 5 then return tostring(class) .. "xx" end
    return "error"
  end
  return "error"
end
M.classify_fetch_status = classify_fetch_status

-- Parse a blocklist body: strip comment lines (^#) and blank lines.
-- Returns a list of hostnames.
local function parse_body(body)
  local hosts = {}
  for raw in (body .. "\n"):gmatch("([^\n]*)\n") do
    local line = raw:match("^%s*(.-)%s*$")  -- trim whitespace
    if line ~= "" and line:sub(1, 1) ~= "#" then
      hosts[#hosts + 1] = line
    end
  end
  return hosts
end

-- Returns the expected cache file path for (id, version).
local function cache_path(cache_dir, id, version)
  return cache_dir .. "/" .. id .. "-" .. version .. ".txt"
end

-- Fetch and cache all blocklists in the snapshot.
-- http_get_fn(url, headers) -> http_status, body, resp_headers
--   This is the same signature wifihaven-agent's http_get and policy.fetch
--   use — status FIRST. (#1334: an earlier body-first assumption here meant
--   the status check always tripped, so every category fetch "failed" and the
--   bl_ ipset stayed empty, turning category enforcement into a silent no-op.)
-- base_url: optional api base (e.g. "http://router:8080"). The snapshot ships
--   relative blocklist urls ("/api/blocklists/<id>"); when base_url is given
--   and the url is root-relative, they're joined so curl gets an absolute URL.
-- fs: optional table with {read, write, rename, remove, list, mkdir};
--   defaults to real I/O. `mkdir(path)` must behave like `mkdir -p` — it is
--   called once for cache_dir before any write, so a fresh image where
--   /etc/wifihaven/blocklists/ doesn't exist yet doesn't ENOENT on the
--   tmp-file open (#1363).
-- auth_token: optional router bearer token. The API's GET /api/blocklists/<id>
--   route is router-authenticated (RouterRoutes.scala: routerAuth.authenticate),
--   exactly like GET /api/router/policy — so the fetch MUST send
--   `Authorization: Bearer <token>` or the server replies 401 and the bl_ ipset
--   stays empty, turning category enforcement into a silent no-op (#1360; the
--   same failure class as the #1334 arg-order bug, distinct cause). When nil
--   (e.g. legacy callers / unit tests against an unauthenticated stub) no
--   Authorization header is sent.
-- Returns: { hosts_by_id = {[id]={hosts}}, errors = {...}, failures = {...} }
--   errors:   human-readable strings for logread.
--   failures: structured { id?, status } records the agent folds into
--             blocklist_fetch_failures_total{status} (#1301). `status` is the
--             bounded enum from classify_fetch_status / a fixed local-IO token.
-- max_bytes (optional): #1412 OOM guard. A cached/fetched body larger than this
-- is still WRITTEN to cache (so gc + version-busting keep working) but is NOT
-- parsed into a host table — parsing an 80k-line list builds a Lua table large
-- enough to OOM a 1 GB router when several such lists are assigned. nil = no cap
-- (legacy callers / tests). The cap is checked by byte size BEFORE parse_body so
-- the big table is never allocated.
function M.fetch_and_cache(snapshot, http_get_fn, fs, cache_dir, base_url, auth_token, max_bytes)
  cache_dir = cache_dir or DEFAULT_CACHE_DIR
  local result = { hosts_by_id = {}, errors = {}, failures = {} }

  -- Parse a body into hosts unless it exceeds the byte cap (then return empty
  -- without building the table). See max_bytes above (#1412).
  local function parse_bounded(text)
    if max_bytes and text and #text > max_bytes then return {} end
    return parse_body(text or "")
  end

  -- Record both a human string and a structured {id, status} failure so the
  -- agent can emit the bounded-cardinality metric without re-parsing strings.
  local function fail(id, status, msg)
    result.errors[#result.errors + 1] = msg
    result.failures[#result.failures + 1] = { id = id, status = status }
  end

  if type(http_get_fn) ~= "function" then
    fail(nil, "error", "http_get_fn is nil or not a function")
    return result
  end

  -- The blocklist route is router-authenticated; build the same bearer header
  -- policy.fetch sends. nil token → no header (unauthenticated callers/tests).
  local auth_headers = auth_token
    and { ["Authorization"] = "Bearer " .. auth_token }
    or nil

  -- Default filesystem ops (real I/O) when fs not injected.
  local read_fn, write_fn, rename_fn, mkdir_fn
  if fs then
    read_fn  = fs.read
    write_fn = fs.write
    rename_fn = fs.rename
    mkdir_fn = fs.mkdir
  else
    read_fn  = function(path)
      local f, err = io.open(path, "r")
      if not f then return nil end
      local content = f:read("*a")
      f:close()
      return content
    end
    write_fn = function(path, content)
      local f, err = io.open(path, "w")
      if not f then return nil, err end
      f:write(content)
      f:close()
      return true, nil
    end
    rename_fn = function(from, to)
      local ok, err = os.rename(from, to)
      if not ok then return nil, err end
      return true, nil
    end
    mkdir_fn = function(path)
      -- mkdir -p; quoting the path so spaces / shell metachars don't break.
      local ok = os.execute(string.format("mkdir -p %q", path))
      if ok == true or ok == 0 then return true, nil end
      return nil, "mkdir -p failed for " .. tostring(path)
    end
  end

  -- Ensure the cache dir exists before the first atomic-tmp write (#1363).
  -- On a fresh image /etc/wifihaven/blocklists/ doesn't exist yet, which
  -- previously caused every fetch to ENOENT and the bl_ ipset to stay empty
  -- — masked before #1360 by the upstream 401, now critical for Suite G G4.
  if mkdir_fn then
    local mok, merr = mkdir_fn(cache_dir)
    if not mok then
      fail(nil, "mkdir_failed",
        string.format("blocklists: mkdir failed for %s: %s", tostring(cache_dir), tostring(merr)))
    end
  end

  local bls = snapshot and snapshot.blocklists or {}
  for id, bl in pairs(bls) do
    local version = bl.version
    local url     = bl.url
    local path    = cache_path(cache_dir, id, version)

    -- Resolve a root-relative url ("/api/blocklists/<id>") against the
    -- configured api base (#1334). Absolute urls (scheme://…) pass through.
    if base_url and type(url) == "string" and url:sub(1, 1) == "/" then
      url = base_url .. url
    end

    -- Check if (id, version) is already cached.
    local existing = read_fn(path)
    if existing then
      result.hosts_by_id[id] = parse_bounded(existing)
    else
      -- Fetch from API. Signature matches the agent's http_get: status first.
      -- Send the router bearer token — the route is router-authenticated (#1360).
      local status, body = http_get_fn(url, auth_headers)
      if type(status) ~= "number" or status ~= 200 then
        fail(id, classify_fetch_status(status),
          string.format("blocklists: fetch failed for %s (status=%s)", tostring(id), tostring(status)))
      else
        -- Atomic write: tmp → final.
        local tmp_path = path .. ".tmp"
        local ok, err  = write_fn(tmp_path, body or "")
        if not ok then
          fail(id, "write_failed",
            string.format("blocklists: write failed for %s: %s", tostring(id), tostring(err)))
        else
          local rok, rerr = rename_fn(tmp_path, path)
          if not rok then
            fail(id, "write_failed",
              string.format("blocklists: rename failed for %s: %s", tostring(id), tostring(rerr)))
          else
            result.hosts_by_id[id] = parse_bounded(body or "")
          end
        end
      end
    end
  end

  return result
end

-- Load all currently-cached blocklists from disk.
-- Returns `result, skipped`:
--   result  = { [id] = {hosts} } for ids whose (id, version) file exists.
--   skipped = { id, ... } ids whose cache file exceeded max_bytes and was left
--             unparsed (result[id] = {}). The caller logs these so an oversized
--             list that is silently not enforced is observable.
-- max_bytes (optional): #1412 OOM guard — see fetch_and_cache. nil = no cap.
function M.load_cached(snapshot, fs, cache_dir, max_bytes)
  cache_dir = cache_dir or DEFAULT_CACHE_DIR
  local result  = {}
  local skipped = {}

  local read_fn
  if fs then
    read_fn = fs.read
  else
    read_fn = function(path)
      local f = io.open(path, "r")
      if not f then return nil end
      local c = f:read("*a")
      f:close()
      return c
    end
  end

  local bls = snapshot and snapshot.blocklists or {}
  for id, bl in pairs(bls) do
    local path    = cache_path(cache_dir, id, bl.version)
    local content = read_fn(path)
    if content and max_bytes and #content > max_bytes then
      -- Oversized: do NOT parse (would build a huge table — the #1412 OOM).
      result[id]               = {}
      skipped[#skipped + 1]    = id
    elseif content then
      result[id] = parse_body(content)
    else
      result[id] = {}
    end
  end

  return result, skipped
end

-- refresh(snapshot, http_get_fn, fs, cache_dir, base_url, auth_token)
--   One-call fetch_and_cache → gc → load_cached. Returns:
--     { hosts = {[id]={host,...}}, failures = {...}, errors = {...} }
--
-- This is the entry point the agent calls BOTH at startup and on the periodic
-- blocklist timer, so the per-blocklist cache (and therefore the bl_ ipset
-- nftset= directives render emits from snapshot._blocklist_hosts) is populated
-- independent of whether the policy poll returned 200 or 304.
--
-- #1412: previously the agent only fetched/cached blocklists inside the
-- policy-poll HTTP-200 branch, and not at startup at all. A household whose
-- snapshot is stable (the steady state — every poll is a 304) therefore never
-- populated the cache: render emitted zero `nftset=/<member>/...#bl_<id>`
-- directives, the bl_ sets stayed empty, and an assigned list member (e.g.
-- taboola.com ∈ 'ads') was reachable despite correct policy. Surfacing the
-- failures back to the caller lets the agent emit blocklist_fetch_failures_total
-- and simply retry on the next cadence rather than no-op until the next snapshot
-- change (which on a stable household may be hours/days away).
function M.refresh(snapshot, http_get_fn, fs, cache_dir, base_url, auth_token, max_bytes)
  local fc             = M.fetch_and_cache(
    snapshot, http_get_fn, fs, cache_dir, base_url, auth_token, max_bytes)
  M.gc(snapshot, fs, cache_dir)
  local hosts, skipped = M.load_cached(snapshot, fs, cache_dir, max_bytes)
  return { hosts = hosts, failures = fc.failures, errors = fc.errors, skipped = skipped }
end

-- hosts_differ(a, b) → boolean
--   Order-insensitive comparison of two {[id]={host,...}} maps. Nil is treated
--   as the empty map. Returns true iff the set of ids differs, or any id's host
--   membership differs.
--
-- The agent calls this after a periodic refresh to decide whether the cached
-- host set actually changed before re-applying. A blocklist host change alters
-- the dnsmasq fragment (the nftset= directives) and so forces a dnsmasq reload;
-- gating the re-apply on a real change keeps a steady state from churning
-- dnsmasq every cadence while still catching the empty→populated cold-start
-- transition (#1412).
function M.hosts_differ(a, b)
  a = a or {}
  b = b or {}

  local function host_set(list)
    local set, n = {}, 0
    for _, h in ipairs(list or {}) do
      if not set[h] then
        set[h] = true
        n = n + 1
      end
    end
    return set, n
  end

  -- Every id in `a` must exist in `b` with identical host membership.
  -- Iterating both directions catches ids present in only one side.
  local function subset_match(x, y)
    for id, xlist in pairs(x) do
      local xset, xn = host_set(xlist)
      local yset, yn = host_set(y[id])
      if xn ~= yn then return false end
      for h in pairs(xset) do
        if not yset[h] then return false end
      end
    end
    return true
  end

  if not subset_match(a, b) then return true end
  if not subset_match(b, a) then return true end
  return false
end

-- ---------------------------------------------------------------------------
-- M.render_shards(snapshot, fs, cache_dir, shard_dir, max_bytes,
--                 global_blocklist_ids)
-- ---------------------------------------------------------------------------
-- Streams per-blocklist shard files to <shard_dir>/wifihaven-blocklist-<id>.conf,
-- one file per blocklist in snapshot.blocklists. Each line in the shard is:
--
--   nftset=/<host>/4#inet#wifihaven#bl_<sanid>,6#inet#wifihaven#bl6_<sanid>\n
--
-- When global_blocklist_ids[id] is truthy, each line also appends:
--   ,4#inet#wifihaven#global_block,6#inet#wifihaven#global_block6
-- before the newline.
--
-- Reads from the on-disk cache file line-by-line (via fs.open_read) so the
-- full host list is NEVER accumulated in a Lua table — the #1412 OOM source.
-- Writes atomically: <id>.conf.tmp → <id>.conf.
--
-- Parameters:
--   snapshot           — current policy snapshot (snapshot.blocklists used)
--   fs                 — injectable filesystem ops (open_read, open_write,
--                        rename, remove, fsync; defaults to real I/O)
--   cache_dir          — directory holding <id>-<version>.txt cache files
--                        (default /etc/wifihaven/blocklists)
--   shard_dir          — directory to write shards into (default /tmp)
--   max_bytes          — stop emitting once shard exceeds this many bytes;
--                        nil = no cap (default 10 MB = 10485760 in agent)
--   global_blocklist_ids — optional { [id] = true } set; ids in this set
--                          have global_block/global_block6 appended to each
--                          shard line
--
-- Returns:
--   { rendered = { [id] = bytes }, skipped = { ids }, errors = { strings } }
function M.render_shards(snapshot, fs, cache_dir, shard_dir, max_bytes, global_blocklist_ids)
  cache_dir  = cache_dir  or "/etc/wifihaven/blocklists"
  shard_dir  = shard_dir  or "/tmp"
  global_blocklist_ids = global_blocklist_ids or {}

  -- Require render.bl_sanitize for set-name normalization so there is
  -- one source of truth (see get_render() at the top of this module).
  local bl_sanitize = get_render().bl_sanitize

  -- Resolve fs ops (injectable for tests, real I/O otherwise).
  local open_read_fn, open_write_fn, rename_fn, remove_fn, fsync_fn
  if fs then
    open_read_fn  = fs.open_read
    open_write_fn = fs.open_write
    rename_fn     = fs.rename
    remove_fn     = fs.remove
    fsync_fn      = fs.fsync
  else
    open_read_fn = function(path)
      local f = io.open(path, "r")
      return f  -- nil if missing; caller checks
    end
    open_write_fn = function(path)
      local f, err = io.open(path, "w")
      if not f then return nil, err end
      return f
    end
    rename_fn = function(from, to)
      local ok, err = os.rename(from, to)
      if not ok then return nil, err end
      return true, nil
    end
    remove_fn = function(path)
      os.remove(path)
      return true, nil
    end
    fsync_fn = function(path)
      -- Best-effort: sync the parent directory so the rename is durable.
      os.execute("sync " .. string.format("%q", path) .. " 2>/dev/null")
      return true
    end
  end

  local result = { rendered = {}, skipped = {}, errors = {} }
  local bls    = snapshot and snapshot.blocklists or {}

  for id, bl in pairs(bls) do
    local version    = bl.version
    local cache_path = cache_dir .. "/" .. id .. "-" .. tostring(version) .. ".txt"
    local shard_tmp  = shard_dir .. "/wifihaven-blocklist-" .. id .. ".conf.tmp"
    local shard_final = shard_dir .. "/wifihaven-blocklist-" .. id .. ".conf"

    -- Open the cache file for line-by-line reading.
    local rf = open_read_fn(cache_path)
    if not rf then
      result.skipped[#result.skipped + 1] = id
    else
      -- Build the per-host spec suffix for this id.
      local san    = bl_sanitize(id)
      local spec   = "4#inet#wifihaven#bl_" .. san .. ",6#inet#wifihaven#bl6_" .. san
      if global_blocklist_ids[id] then
        spec = spec .. ",4#inet#wifihaven#global_block,6#inet#wifihaven#global_block6"
      end

      local wf, werr = open_write_fn(shard_tmp)
      if not wf then
        result.errors[#result.errors + 1] =
          string.format("render_shards: open_write failed for %s: %s", shard_tmp, tostring(werr))
        rf:close()
      else
        local bytes_written = 0
        local cap_hit = false
        -- Stream line-by-line — never accumulate.
        while true do
          local line = rf:read("*l")
          if line == nil then break end
          -- Trim whitespace; skip blank and comment lines.
          line = line:match("^%s*(.-)%s*$")
          if line ~= "" and line:sub(1, 1) ~= "#" then
            local directive = "nftset=/" .. line .. "/" .. spec .. "\n"
            -- Check byte cap BEFORE writing.
            if max_bytes and (bytes_written + #directive) > max_bytes then
              cap_hit = true
              break
            end
            wf:write(directive)
            bytes_written = bytes_written + #directive
          end
        end
        rf:close()
        wf:close()

        if cap_hit then
          -- Remove the truncated tmp file; record the id as skipped.
          remove_fn(shard_tmp)
          result.skipped[#result.skipped + 1] = id
        else
          -- fsync before rename for durability.
          if fsync_fn then fsync_fn(shard_tmp) end
          local rok, rerr = rename_fn(shard_tmp, shard_final)
          if not rok then
            result.errors[#result.errors + 1] =
              string.format("render_shards: rename failed for %s → %s: %s",
                shard_tmp, shard_final, tostring(rerr))
            -- Best-effort cleanup of the tmp file.
            remove_fn(shard_tmp)
          else
            result.rendered[id] = bytes_written
          end
        end
      end
    end
  end

  return result
end

-- ---------------------------------------------------------------------------
-- M.gc_shards(snapshot, fs, shard_dir)
-- ---------------------------------------------------------------------------
-- Removes shard files in shard_dir whose blocklist id is no longer in
-- snapshot.blocklists, and clears leftover .tmp files from a crash during
-- render. Only touches files matching the pattern
-- `wifihaven-blocklist-*.conf` or `wifihaven-blocklist-*.conf.tmp`.
--
-- Called at every render and at agent startup (defense against crash-
-- during-render leaving stale shards visible to dnsmasq).
function M.gc_shards(snapshot, fs, shard_dir)
  shard_dir = shard_dir or "/tmp"

  local list_fn, remove_fn
  if fs then
    list_fn   = fs.list
    remove_fn = fs.remove
  else
    list_fn = function(dir)
      local files = {}
      local p = io.popen("ls -1 " .. dir .. " 2>/dev/null")
      if p then
        for line in p:lines() do files[#files + 1] = line end
        p:close()
      end
      return files
    end
    remove_fn = function(path)
      os.remove(path)
      return true, nil
    end
  end

  -- Build the set of ids present in the current snapshot.
  local present = {}
  local bls = snapshot and snapshot.blocklists or {}
  for id, _ in pairs(bls) do present[id] = true end

  local files = list_fn(shard_dir)
  for _, fname in ipairs(files or {}) do
    -- Match wifihaven-blocklist-<id>.conf or wifihaven-blocklist-<id>.conf.tmp
    local id_from_conf     = fname:match("^wifihaven%-blocklist%-(.+)%.conf$")
    local id_from_conf_tmp = fname:match("^wifihaven%-blocklist%-(.+)%.conf%.tmp$")

    if id_from_conf_tmp then
      -- Always remove .tmp files (leftover from crash-during-render).
      remove_fn(shard_dir .. "/" .. fname)
    elseif id_from_conf then
      if not present[id_from_conf] then
        remove_fn(shard_dir .. "/" .. fname)
      end
    end
  end
end

-- ---------------------------------------------------------------------------
-- M.render_member_index(snapshot, fs, cache_dir, index_path)
-- ---------------------------------------------------------------------------
-- Streams the blocklist member index (used by wifihaven-dns-tail for CNAME-
-- aware bl_ set population) to index_path. Each line is:
--   <host>\t<bl_set>\t<bl6_set>\n
-- Reads from the on-disk cache files line-by-line so no large Lua table is
-- built. Writes atomically: index_path.tmp → index_path.
--
-- This replaces render.blocklist_member_index for the on-disk output path
-- while keeping render.blocklist_member_index available for the in-memory
-- (tests / dns-tail in-process) path.
function M.render_member_index(snapshot, fs, cache_dir, index_path)
  cache_dir  = cache_dir  or "/etc/wifihaven/blocklists"
  index_path = index_path or "/var/run/wifihaven/blocklist_members.tsv"

  local bl_set_name  = get_render().bl_set_name
  local bl6_set_name = get_render().bl6_set_name

  local open_read_fn, open_write_fn, rename_fn
  if fs then
    open_read_fn  = fs.open_read
    open_write_fn = fs.open_write
    rename_fn     = fs.rename
  else
    open_read_fn = function(path)
      return io.open(path, "r")
    end
    open_write_fn = function(path)
      local f, err = io.open(path, "w")
      if not f then return nil, err end
      return f
    end
    rename_fn = function(from, to)
      local ok, err = os.rename(from, to)
      if not ok then return nil, err end
      return true, nil
    end
  end

  local tmp_path = index_path .. ".tmp"
  local wf, werr = open_write_fn(tmp_path)
  if not wf then
    return { error = "render_member_index: open_write failed: " .. tostring(werr) }
  end

  local bls    = snapshot and snapshot.blocklists or {}
  -- Sort ids for deterministic output (matches render.blocklist_member_index).
  local ids = {}
  for id, _ in pairs(bls) do ids[#ids + 1] = id end
  table.sort(ids)

  for _, id in ipairs(ids) do
    local bl         = bls[id]
    local cache_path = cache_dir .. "/" .. id .. "-" .. tostring(bl.version) .. ".txt"
    local set4       = bl_set_name(id)
    local set6       = bl6_set_name(id)

    local rf = open_read_fn(cache_path)
    if rf then
      while true do
        local line = rf:read("*l")
        if line == nil then break end
        line = line:match("^%s*(.-)%s*$")
        if line ~= "" and line:sub(1, 1) ~= "#" then
          wf:write(line .. "\t" .. set4 .. "\t" .. set6 .. "\n")
        end
      end
      rf:close()
    end
  end
  wf:close()

  local rok, rerr = rename_fn(tmp_path, index_path)
  if not rok then
    return { error = "render_member_index: rename failed: " .. tostring(rerr) }
  end
  return {}
end

-- Remove cache files that are not referenced by the current snapshot.
-- A file is kept only if its name matches "<id>-<version>.txt" where
-- (id, version) is an entry in snapshot.blocklists.
function M.gc(snapshot, fs, cache_dir)
  cache_dir = cache_dir or DEFAULT_CACHE_DIR

  -- Build the set of expected filenames.
  local keep = {}
  local bls  = snapshot and snapshot.blocklists or {}
  for id, bl in pairs(bls) do
    local fname = id .. "-" .. bl.version .. ".txt"
    keep[fname] = true
  end

  local list_fn, remove_fn
  if fs then
    list_fn   = fs.list
    remove_fn = fs.remove
  else
    list_fn = function(dir)
      local files = {}
      local p = io.popen("ls -1 " .. dir .. " 2>/dev/null")
      if p then
        for line in p:lines() do files[#files + 1] = line end
        p:close()
      end
      return files
    end
    remove_fn = function(path)
      os.remove(path)
      return true, nil
    end
  end

  local files = list_fn(cache_dir)
  for _, fname in ipairs(files or {}) do
    -- Only touch .txt files (skip .tmp etc).
    if fname:match("%.txt$") and not keep[fname] then
      remove_fn(cache_dir .. "/" .. fname)
    end
  end
end

return M
