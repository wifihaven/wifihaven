-- Tests for openwrt/files/usr/lib/lua/wifihaven/blocklists.lua
-- Run with: cd openwrt && busted test/blocklists_spec.lua

local blocklists = require("blocklists")

-- Minimal snapshot with blocklists field.
local function snap(bl)
  return {
    etag        = "sha256:abc123",
    generatedAt = "2026-05-14T14:00:00Z",
    devices     = {},
    profiles    = {},
    blocklists  = bl or {},
  }
end

-- Build a fake filesystem table that tracks writes/reads/renames/lists.
local function make_fs()
  local files = {}
  return {
    _files = files,
    write = function(path, content)
      files[path] = content
      return true, nil
    end,
    read = function(path)
      return files[path]
    end,
    rename = function(from, to)
      if files[from] then
        files[to] = files[from]
        files[from] = nil
        return true, nil
      end
      return nil, "no such file: " .. tostring(from)
    end,
    remove = function(path)
      files[path] = nil
      return true, nil
    end,
    list = function(dir)
      local result = {}
      local prefix = dir:sub(-1) == "/" and dir or (dir .. "/")
      for k, _ in pairs(files) do
        if k:sub(1, #prefix) == prefix then
          result[#result + 1] = k:sub(#prefix + 1)
        end
      end
      return result
    end,
  }
end

-- ── fetch_and_cache ─────────────────────────────────────────────────────────

describe("blocklists.fetch_and_cache", function()

  it("fetches body and writes to <cache_dir>/<id>-<version>.txt atomically", function()
    local fs       = make_fs()
    local fetches  = 0
    local function http_get(url, _etag)
      fetches = fetches + 1
      if url:find("test_ads", 1, true) then
        return "doubleclick.net\ngoogleadservices.com\n", 200, '"v1"'
      end
      return nil, 404, nil
    end

    local s = snap({ test_ads = { version = "abc123", url = "http://api/api/blocklists/test_ads" } })
    local result = blocklists.fetch_and_cache(s, http_get, fs, "/etc/wifihaven/blocklists")

    assert.equal(1, fetches)
    assert.not_nil(result.hosts_by_id)
    assert.not_nil(result.hosts_by_id["test_ads"])
    -- Final path exists, tmp path does not.
    local final = "/etc/wifihaven/blocklists/test_ads-abc123.txt"
    assert.not_nil(fs._files[final], "expected final cache file to exist")
    assert.is_nil(fs._files[final .. ".tmp"], "tmp file should have been renamed away")
    -- Hosts are parsed from the body.
    local hosts = result.hosts_by_id["test_ads"]
    local found = {}
    for _, h in ipairs(hosts) do found[h] = true end
    assert.truthy(found["doubleclick.net"])
    assert.truthy(found["googleadservices.com"])
    assert.equal(0, #result.errors)
  end)

  it("does NOT re-fetch when (id, version) file already exists in cache", function()
    local fs      = make_fs()
    local fetches = 0
    local function http_get(_url, _etag) fetches = fetches + 1; return "host.example\n", 200, '"v1"' end

    -- Pre-seed the cache file.
    local cache_dir = "/etc/wifihaven/blocklists"
    fs._files[cache_dir .. "/test_ads-abc123.txt"] = "host.example\n"

    local s = snap({ test_ads = { version = "abc123", url = "http://api/api/blocklists/test_ads" } })
    blocklists.fetch_and_cache(s, http_get, fs, cache_dir)

    assert.equal(0, fetches, "must not re-fetch when cache file already exists")
  end)

  it("re-fetches when version changes (old file still in cache_dir)", function()
    local fs      = make_fs()
    local fetches = 0
    local function http_get(_url, _etag) fetches = fetches + 1; return "newhost.example\n", 200, '"v2"' end

    local cache_dir = "/etc/wifihaven/blocklists"
    -- Old version file in cache.
    fs._files[cache_dir .. "/test_ads-old_version.txt"] = "oldhost.example\n"

    local s = snap({ test_ads = { version = "new_version", url = "http://api/api/blocklists/test_ads" } })
    local result = blocklists.fetch_and_cache(s, http_get, fs, cache_dir)

    assert.equal(1, fetches)
    assert.not_nil(fs._files[cache_dir .. "/test_ads-new_version.txt"])
    local hosts = result.hosts_by_id["test_ads"]
    assert.not_nil(hosts)
    local found = {}
    for _, h in ipairs(hosts) do found[h] = true end
    assert.truthy(found["newhost.example"])
  end)

  it("HTTP non-200 response: returns error, does not write cache file", function()
    local fs = make_fs()
    local function http_get(_url, _etag)
      return "server error", 500, nil
    end

    local s = snap({ test_ads = { version = "abc123", url = "http://api/api/blocklists/test_ads" } })
    local result = blocklists.fetch_and_cache(s, http_get, fs, "/etc/wifihaven/blocklists")

    assert.truthy(#result.errors > 0)
    assert.is_nil(fs._files["/etc/wifihaven/blocklists/test_ads-abc123.txt"])
  end)

  it("skips comment lines and blank lines in the body", function()
    local fs = make_fs()
    local function http_get(_url, _etag)
      return "# version: abc123\nads.example.com\n\ndoubleclick.net\n", 200, '"v1"'
    end

    local s = snap({ test_ads = { version = "v1", url = "http://api/api/blocklists/test_ads" } })
    local result = blocklists.fetch_and_cache(s, http_get, fs, "/etc/wifihaven/blocklists")

    local hosts = result.hosts_by_id["test_ads"]
    assert.not_nil(hosts)
    for _, h in ipairs(hosts) do
      assert.is_nil(h:match("^#"), "comment lines must be stripped: " .. h)
      assert.not_equal("", h)
    end
    assert.equal(2, #hosts)
  end)

  it("returns error when http_get_fn is nil", function()
    local fs = make_fs()
    local s  = snap({ test_ads = { version = "abc123", url = "http://api/api/blocklists/test_ads" } })
    local result = blocklists.fetch_and_cache(s, nil, fs, "/etc/wifihaven/blocklists")
    assert.truthy(#result.errors > 0)
  end)

end)

-- ── load_cached ──────────────────────────────────────────────────────────────

describe("blocklists.load_cached", function()

  it("reads the cached file for (id, version) and returns host list", function()
    local fs        = make_fs()
    local cache_dir = "/etc/wifihaven/blocklists"
    fs._files[cache_dir .. "/test_ads-abc123.txt"] =
      "# version: abc123\nads.example.com\ndoubleclick.net\n"

    local s = snap({ test_ads = { version = "abc123", url = "http://api/api/blocklists/test_ads" } })
    local result = blocklists.load_cached(s, fs, cache_dir)

    assert.not_nil(result["test_ads"])
    local found = {}
    for _, h in ipairs(result["test_ads"]) do found[h] = true end
    assert.truthy(found["ads.example.com"])
    assert.truthy(found["doubleclick.net"])
    -- comment lines stripped
    for _, h in ipairs(result["test_ads"]) do
      assert.is_nil(h:match("^#"))
    end
  end)

  it("returns empty table when cache file is absent", function()
    local fs  = make_fs()
    local s   = snap({ test_ads = { version = "abc123", url = "http://api/api/blocklists/test_ads" } })
    local res = blocklists.load_cached(s, fs, "/etc/wifihaven/blocklists")
    -- Either missing key or empty list is acceptable.
    local hosts = res["test_ads"] or {}
    assert.equal(0, #hosts)
  end)

end)

-- ── gc ───────────────────────────────────────────────────────────────────────

describe("blocklists.gc", function()

  it("removes cache files whose (id, version) is not in current snapshot", function()
    local fs        = make_fs()
    local cache_dir = "/etc/wifihaven/blocklists"
    -- Current version file (must be kept).
    fs._files[cache_dir .. "/test_ads-current.txt"]    = "host1\n"
    -- Stale version (must be removed).
    fs._files[cache_dir .. "/test_ads-stale.txt"]      = "host1\n"
    -- Completely removed id (must be removed).
    fs._files[cache_dir .. "/old_id-v1.txt"]           = "host2\n"

    local s = snap({ test_ads = { version = "current", url = "http://api/api/blocklists/test_ads" } })
    blocklists.gc(s, fs, cache_dir)

    assert.not_nil(fs._files[cache_dir .. "/test_ads-current.txt"],
      "current version must be kept")
    assert.is_nil(fs._files[cache_dir .. "/test_ads-stale.txt"],
      "stale version must be removed")
    assert.is_nil(fs._files[cache_dir .. "/old_id-v1.txt"],
      "removed id file must be deleted")
  end)

  it("does not remove files for ids that are still in snapshot", function()
    local fs        = make_fs()
    local cache_dir = "/etc/wifihaven/blocklists"
    fs._files[cache_dir .. "/test_ads-v1.txt"]    = "host1\n"
    fs._files[cache_dir .. "/test_social-v2.txt"] = "host2\n"

    local s = snap({
      test_ads    = { version = "v1", url = "http://api/api/blocklists/test_ads"    },
      test_social = { version = "v2", url = "http://api/api/blocklists/test_social" },
    })
    blocklists.gc(s, fs, cache_dir)

    assert.not_nil(fs._files[cache_dir .. "/test_ads-v1.txt"])
    assert.not_nil(fs._files[cache_dir .. "/test_social-v2.txt"])
  end)

end)
