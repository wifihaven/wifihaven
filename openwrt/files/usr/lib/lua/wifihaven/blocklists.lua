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
-- Returns: { hosts_by_id = {[id]={hosts}}, errors = {...} }
function M.fetch_and_cache(snapshot, http_get_fn, fs, cache_dir, base_url, auth_token)
  cache_dir = cache_dir or DEFAULT_CACHE_DIR
  local result = { hosts_by_id = {}, errors = {} }

  if type(http_get_fn) ~= "function" then
    result.errors[#result.errors + 1] = "http_get_fn is nil or not a function"
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
      result.errors[#result.errors + 1] =
        string.format("blocklists: mkdir failed for %s: %s", tostring(cache_dir), tostring(merr))
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
      result.hosts_by_id[id] = parse_body(existing)
    else
      -- Fetch from API. Signature matches the agent's http_get: status first.
      -- Send the router bearer token — the route is router-authenticated (#1360).
      local status, body = http_get_fn(url, auth_headers)
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
