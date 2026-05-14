-- blocklists.lua — fetch, cache, and garbage-collect category blocklists
--
-- Each blocklist is identified by (id, version). Files are stored at:
--   <cache_dir>/<id>-<version>.txt
-- Writes are atomic: body goes to <path>.tmp then renamed to <path>.
--
-- Public API:
--   M.fetch_and_cache(snapshot, http_get_fn, fs, cache_dir)
--     → { hosts_by_id = {[id]={hosts}}, errors = {...} }
--   M.load_cached(snapshot, fs, cache_dir)
--     → { [id] = {hosts} }
--   M.gc(snapshot, fs, cache_dir)
--     → removes stale (id, version) files not in current snapshot

local M = {}

local DEFAULT_CACHE_DIR = "/etc/familydns/blocklists"

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
-- http_get_fn(url, etag) -> body_or_nil, http_status, new_etag
-- fs: optional table with {read, write, rename, remove, list}; defaults to real I/O.
-- Returns: { hosts_by_id = {[id]={hosts}}, errors = {...} }
function M.fetch_and_cache(snapshot, http_get_fn, fs, cache_dir)
  cache_dir = cache_dir or DEFAULT_CACHE_DIR
  local result = { hosts_by_id = {}, errors = {} }

  if type(http_get_fn) ~= "function" then
    result.errors[#result.errors + 1] = "http_get_fn is nil or not a function"
    return result
  end

  -- Default filesystem ops (real I/O) when fs not injected.
  local read_fn, write_fn, rename_fn
  if fs then
    read_fn  = fs.read
    write_fn = fs.write
    rename_fn = fs.rename
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
  end

  local bls = snapshot and snapshot.blocklists or {}
  for id, bl in pairs(bls) do
    local version = bl.version
    local url     = bl.url
    local path    = cache_path(cache_dir, id, version)

    -- Check if (id, version) is already cached.
    local existing = read_fn(path)
    if existing then
      result.hosts_by_id[id] = parse_body(existing)
    else
      -- Fetch from API.
      local body, status, _etag = http_get_fn(url, nil)
      if type(status) ~= "number" or status ~= 200 then
        result.errors[#result.errors + 1] =
          string.format("blocklists: fetch failed for %s (status=%s)", tostring(id), tostring(status))
      else
        -- Atomic write: tmp → final.
        local tmp_path = path .. ".tmp"
        local ok, err  = write_fn(tmp_path, body or "")
        if not ok then
          result.errors[#result.errors + 1] =
            string.format("blocklists: write failed for %s: %s", tostring(id), tostring(err))
        else
          local rok, rerr = rename_fn(tmp_path, path)
          if not rok then
            result.errors[#result.errors + 1] =
              string.format("blocklists: rename failed for %s: %s", tostring(id), tostring(rerr))
          else
            result.hosts_by_id[id] = parse_body(body or "")
          end
        end
      end
    end
  end

  return result
end

-- Load all currently-cached blocklists from disk.
-- Returns { [id] = {hosts} } for ids whose (id, version) file exists.
function M.load_cached(snapshot, fs, cache_dir)
  cache_dir = cache_dir or DEFAULT_CACHE_DIR
  local result = {}

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
    if content then
      result[id] = parse_body(content)
    else
      result[id] = {}
    end
  end

  return result
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
