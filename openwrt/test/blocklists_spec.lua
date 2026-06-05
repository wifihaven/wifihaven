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
    local function http_get(url, _headers)
      fetches = fetches + 1
      if url:find("test_ads", 1, true) then
        return 200, "doubleclick.net\ngoogleadservices.com\n", {}
      end
      return 404, nil, {}
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

  -- #1334 regression: in production the agent passes its own `http_get`
  -- (wifihaven-agent), whose signature is `http_get(url, headers) -> status,
  -- body, resp_headers` — status FIRST. The snapshot also ships a *relative*
  -- blocklist url (`/api/blocklists/<id>`), which must be resolved against the
  -- router's configured api_url before it reaches curl. Both were previously
  -- mismatched here (mock returned body-first; url was absolute), so the bug —
  -- every category fetch silently failing and the bl_ ipset staying empty —
  -- was invisible to the suite while category enforcement was a no-op on prod.
  it("uses the agent's real http_get signature (status, body) and resolves relative urls via base_url", function()
    local fs   = make_fs()
    local seen_url
    -- Mirrors wifihaven-agent's http_get: returns (status, body, headers).
    local function prod_http_get(url, _headers)
      seen_url = url
      return 200, "doubleclick.net\ngoogleadservices.com\n", {}
    end

    local s = snap({ ads = { version = "v1", url = "/api/blocklists/ads" } })
    local result = blocklists.fetch_and_cache(
      s, prod_http_get, fs, "/etc/wifihaven/blocklists", "http://api.example:8080")

    -- Relative url was joined to the configured api base.
    assert.equal("http://api.example:8080/api/blocklists/ads", seen_url)
    assert.equal(0, #result.errors)
    local hosts = result.hosts_by_id["ads"]
    assert.not_nil(hosts, "ads hosts must be cached (otherwise bl_ ipset stays empty)")
    local found = {}
    for _, h in ipairs(hosts) do found[h] = true end
    assert.truthy(found["doubleclick.net"])
    assert.truthy(found["googleadservices.com"])
    assert.not_nil(fs._files["/etc/wifihaven/blocklists/ads-v1.txt"])
  end)

  -- #1360 regression: the API's GET /api/blocklists/<id> route is
  -- router-authenticated (RouterRoutes.scala: routerAuth.authenticate), exactly
  -- like GET /api/router/policy. The agent must send `Authorization: Bearer
  -- <router_token>` on the blocklist fetch — without it the server replies 401,
  -- the bl_ ipset stays empty, and category enforcement is a silent no-op (the
  -- same *symptom* as the #1334 arg-order bug, a distinct *cause*). Gate-2
  -- Suite G G4 (test_bl_direct_requery_blocked) reproduced this end-to-end:
  -- "blocklist fetch: fetch failed for e2e-cname-bl (status=401)".
  it("sends Authorization: Bearer <token> when an auth_token is given (#1360)", function()
    local fs = make_fs()
    local seen_headers
    -- Mimic the real router endpoint: 401 unless a bearer token is present.
    local function authed_http_get(_url, headers)
      seen_headers = headers
      local auth = headers and headers["Authorization"]
      if auth ~= "Bearer rtok-123" then
        return 401, "Missing router token", {}
      end
      return 200, "doubleclick.net\n", {}
    end

    local s = snap({ ads = { version = "v1", url = "/api/blocklists/ads" } })
    local result = blocklists.fetch_and_cache(
      s, authed_http_get, fs, "/etc/wifihaven/blocklists",
      "http://api.example:8080", "rtok-123")

    assert.not_nil(seen_headers, "fetch must pass a headers table")
    assert.equal("Bearer rtok-123", seen_headers["Authorization"])
    assert.equal(0, #result.errors, "authenticated fetch must succeed")
    assert.not_nil(result.hosts_by_id["ads"],
      "ads hosts must be cached (otherwise bl_ ipset stays empty)")
    assert.not_nil(fs._files["/etc/wifihaven/blocklists/ads-v1.txt"])
  end)

  it("401s and records an error when no auth_token is passed to an authed route (#1360)", function()
    local fs = make_fs()
    local function authed_http_get(_url, headers)
      local auth = headers and headers["Authorization"]
      if auth ~= "Bearer rtok-123" then
        return 401, "Missing router token", {}
      end
      return 200, "doubleclick.net\n", {}
    end

    -- No auth_token argument → no Authorization header → server 401s.
    local s = snap({ ads = { version = "v1", url = "/api/blocklists/ads" } })
    local result = blocklists.fetch_and_cache(
      s, authed_http_get, fs, "/etc/wifihaven/blocklists", "http://api.example:8080")

    assert.truthy(#result.errors > 0, "missing token must surface a fetch error")
    assert.is_nil(result.hosts_by_id["ads"])
    assert.is_nil(fs._files["/etc/wifihaven/blocklists/ads-v1.txt"])
  end)

  it("does NOT re-fetch when (id, version) file already exists in cache", function()
    local fs      = make_fs()
    local fetches = 0
    local function http_get(_url, _headers) fetches = fetches + 1; return 200, "host.example\n", {} end

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
    local function http_get(_url, _headers) fetches = fetches + 1; return 200, "newhost.example\n", {} end

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
    local function http_get(_url, _headers)
      return 500, "server error", {}
    end

    local s = snap({ test_ads = { version = "abc123", url = "http://api/api/blocklists/test_ads" } })
    local result = blocklists.fetch_and_cache(s, http_get, fs, "/etc/wifihaven/blocklists")

    assert.truthy(#result.errors > 0)
    assert.is_nil(fs._files["/etc/wifihaven/blocklists/test_ads-abc123.txt"])
  end)

  it("skips comment lines and blank lines in the body", function()
    local fs = make_fs()
    local function http_get(_url, _headers)
      return 200, "# version: abc123\nads.example.com\n\ndoubleclick.net\n", {}
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

  -- #1363 regression: on a fresh image the cache_dir
  -- (/etc/wifihaven/blocklists) does not exist yet, so the atomic-tmp open
  -- fails with ENOENT and every category fetch records a "write failed: No
  -- such file or directory" error. The bl_ ipset stays empty and category
  -- enforcement is a silent no-op. fetch_and_cache must mkdir -p the cache
  -- dir up front (via the injected fs.mkdir hook, defaulting to a real
  -- mkdir on prod) before the first write.
  it("creates the cache dir before writing when it does not exist (#1363)", function()
    local cache_dir = "/etc/wifihaven/blocklists"
    -- fs whose write_fn errors with ENOENT until mkdir is called for the
    -- parent dir. Mirrors the real busted_temp / POSIX behaviour.
    local files = {}
    local dirs  = {}
    local mkdir_calls = {}
    local fs = {
      _files = files,
      _dirs  = dirs,
      mkdir = function(path)
        mkdir_calls[#mkdir_calls + 1] = path
        dirs[path] = true
        return true, nil
      end,
      write = function(path, content)
        local parent = path:match("^(.*)/[^/]+$")
        if parent and not dirs[parent] then
          return nil, path .. ": No such file or directory"
        end
        files[path] = content
        return true, nil
      end,
      read = function(path) return files[path] end,
      rename = function(from, to)
        if files[from] then
          files[to] = files[from]
          files[from] = nil
          return true, nil
        end
        return nil, "no such file: " .. tostring(from)
      end,
    }

    local function http_get(_url, _headers)
      return 200, "ads.example.com\n", {}
    end

    local s = snap({ test_ads = { version = "v1", url = "http://api/api/blocklists/test_ads" } })
    local result = blocklists.fetch_and_cache(s, http_get, fs, cache_dir)

    assert.truthy(#mkdir_calls > 0, "fetch_and_cache must mkdir the cache dir")
    assert.equal(cache_dir, mkdir_calls[1])
    -- No "No such file or directory" errors after the mkdir.
    for _, e in ipairs(result.errors) do
      assert.is_nil(e:match("No such file or directory"),
        "unexpected ENOENT error after mkdir: " .. e)
    end
    assert.not_nil(files[cache_dir .. "/test_ads-v1.txt"],
      "cache file must exist after fetch_and_cache succeeds")
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

-- ── structured failures + bounded status enum (#1301) ───────────────────────

describe("blocklists.classify_fetch_status (#1301)", function()
  it("collapses HTTP statuses to their class", function()
    assert.equal("4xx", blocklists.classify_fetch_status(404))
    assert.equal("4xx", blocklists.classify_fetch_status(401))
    assert.equal("5xx", blocklists.classify_fetch_status(503))
    assert.equal("3xx", blocklists.classify_fetch_status(302))
  end)

  it("maps a missing/non-numeric status (transport failure) to error", function()
    -- #705: the old path logged status=nil; capture it as a real label instead.
    assert.equal("error", blocklists.classify_fetch_status(nil))
    assert.equal("error", blocklists.classify_fetch_status("boom"))
  end)
end)

describe("blocklists.fetch_and_cache structured failures (#1301)", function()
  it("records a fetch failure with the HTTP status class", function()
    local function http_get(_url, _headers) return 503, nil, {} end
    local s = snap({ ads = { version = "v1", url = "http://api/api/blocklists/ads" } })
    local result = blocklists.fetch_and_cache(s, http_get, make_fs(), "/etc/wifihaven/blocklists")
    assert.equal(1, #result.failures)
    assert.equal("ads", result.failures[1].id)
    assert.equal("5xx", result.failures[1].status)
  end)

  it("records a transport failure (nil status) as error", function()
    local function http_get(_url, _headers) return nil, nil, nil end
    local s = snap({ ads = { version = "v1", url = "http://api/api/blocklists/ads" } })
    local result = blocklists.fetch_and_cache(s, http_get, make_fs(), "/etc/wifihaven/blocklists")
    assert.equal(1, #result.failures)
    assert.equal("error", result.failures[1].status)
  end)

  it("records a write failure as write_failed", function()
    local fs = make_fs()
    fs.write = function(_p, _c) return nil, "ENOSPC" end
    local function http_get(_url, _headers) return 200, "doubleclick.net\n", {} end
    local s = snap({ ads = { version = "v1", url = "http://api/api/blocklists/ads" } })
    local result = blocklists.fetch_and_cache(s, http_get, fs, "/etc/wifihaven/blocklists")
    assert.equal("write_failed", result.failures[1].status)
  end)

  it("emits no failure when every fetch succeeds", function()
    local function http_get(_url, _headers) return 200, "doubleclick.net\n", {} end
    local s = snap({ ads = { version = "v1", url = "http://api/api/blocklists/ads" } })
    local result = blocklists.fetch_and_cache(s, http_get, make_fs(), "/etc/wifihaven/blocklists")
    assert.equal(0, #result.failures)
  end)
end)

-- ── refresh + change detection (#1412) ──────────────────────────────────────
--
-- #1412: category enforcement silently no-op'd in prod because the agent only
-- fetched/cached blocklists inside the policy-poll HTTP-200 branch and never at
-- startup. A household whose snapshot is stable (the steady state — every poll
-- returns 304) therefore never populated the per-blocklist cache, so render
-- emitted zero `nftset=/<member>/...#bl_<id>` directives, the bl_ ipsets stayed
-- empty, and an assigned list (e.g. taboola.com ∈ 'ads') was reachable. The fix
-- makes the agent call blocklists.refresh() at startup AND on a periodic timer
-- (against the last good snapshot) independent of 200/304, re-applying only when
-- hosts_differ() reports the cached set actually changed.

describe("blocklists.refresh (#1412)", function()

  it("fetches, caches, and returns the loaded host map in one call", function()
    local fs = make_fs()
    local function http_get(_url, _headers)
      return 200, "doubleclick.net\ntaboola.com\n", {}
    end
    local s = snap({ ads = { version = "v1", url = "http://api/api/blocklists/ads" } })

    local res = blocklists.refresh(s, http_get, fs, "/etc/wifihaven/blocklists")

    assert.equal(0, #res.failures)
    assert.not_nil(res.hosts.ads)
    local found = {}
    for _, h in ipairs(res.hosts.ads) do found[h] = true end
    assert.truthy(found["taboola.com"], "refresh must surface the cached member host")
    -- The cache file was written so a later load_cached (e.g. after a restart)
    -- finds it without re-fetching.
    assert.not_nil(fs._files["/etc/wifihaven/blocklists/ads-v1.txt"])
  end)

  it("surfaces fetch failures and leaves the host map empty for the failed id", function()
    -- The #1412/#1360 failure mode: the fetch 401s, so nothing is cached and the
    -- bl_ set would stay empty. refresh must report the failure (so the agent can
    -- emit the metric + retry next cadence) rather than silently returning {}.
    local function http_get(_url, _headers) return 401, nil, {} end
    local s = snap({ ads = { version = "v1", url = "http://api/api/blocklists/ads" } })

    local res = blocklists.refresh(s, http_get, make_fs(), "/etc/wifihaven/blocklists")

    assert.equal(1, #res.failures)
    assert.equal("ads", res.failures[1].id)
    assert.equal("4xx", res.failures[1].status)
    assert.equal(0, #(res.hosts.ads or {}))
  end)

end)

describe("blocklists.hosts_differ (#1412)", function()

  it("returns false for identical maps regardless of host order", function()
    local a = { ads = { "a.com", "b.com" }, malware = { "m.com" } }
    local b = { ads = { "b.com", "a.com" }, malware = { "m.com" } }
    assert.is_false(blocklists.hosts_differ(a, b))
  end)

  it("returns true when host membership changes", function()
    local a = { ads = { "a.com", "b.com" } }
    local b = { ads = { "a.com", "c.com" } }
    assert.is_true(blocklists.hosts_differ(a, b))
  end)

  it("returns true when an id is added or removed", function()
    local a = { ads = { "a.com" } }
    local b = { ads = { "a.com" }, malware = { "m.com" } }
    assert.is_true(blocklists.hosts_differ(a, b))
    assert.is_true(blocklists.hosts_differ(b, a))
  end)

  it("returns true when an empty cache becomes populated (the cold-start fix)", function()
    -- Startup applies with an empty map; the first refresh populates it. The
    -- agent must detect this transition and re-apply so bl_ directives appear.
    assert.is_true(blocklists.hosts_differ({}, { ads = { "taboola.com" } }))
    assert.is_true(blocklists.hosts_differ({ ads = {} }, { ads = { "taboola.com" } }))
  end)

  it("treats nil and empty as equal (no spurious re-apply)", function()
    assert.is_false(blocklists.hosts_differ(nil, {}))
    assert.is_false(blocklists.hosts_differ({}, nil))
  end)

end)
