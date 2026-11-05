-- #2995: every nft set a resolved name belongs in must be filled by the ONE
-- nftset= directive dnsmasq picks for that name.
--
-- dnsmasq fires a single nftset= directive per reply: the longest suffix match
-- on the query name, and on a tie the directive it parsed first
-- (domain_find_sets in src/forward.c keeps one `ret`, replaced on
-- `domainlen >= matchlen`; src/option.c prepends each directive to
-- daemon->nftsets). A host that is both a blocklist member (shard directive)
-- and in extraAllowed / extraBlocked / global (main-conf directive) used to
-- fill only one of its sets at resolve time; the other waited for dns-tail,
-- after the reply, so the client's first connection hit the wrong verdict.
--
-- These specs render the real conf + shards and replay dnsmasq's selection.
-- Run with: cd openwrt && busted test/nftset_coverage_spec.lua

local render     = require("render")
local blocklists = require("blocklists")

local CACHE = "/etc/wifihaven/blocklists"
local INDEX = "/tmp/wifihaven-bl-members.tsv"

local function stream_fs()
  local files = {}
  return {
    _files = files,
    open_read = function(p)
      local content = files[p]
      if not content then return nil end
      local pos = 1
      return {
        read = function()
          if pos > #content then return nil end
          local nl = content:find("\n", pos, true) or (#content + 1)
          local line = content:sub(pos, nl - 1)
          pos = nl + 1
          return line
        end,
        close = function() end,
      }
    end,
    open_write = function(p)
      files[p] = ""
      return { write = function(_, c) files[p] = files[p] .. c end, close = function() end }
    end,
    rename = function(a, b)
      if not files[a] then return nil, "missing " .. a end
      files[b] = files[a]; files[a] = nil; return true
    end,
    remove = function(p) files[p] = nil; return true end,
    fsync = function() return true end,
    list = function(dir)
      local out = {}
      for k in pairs(files) do
        if k:sub(1, #dir + 1) == dir .. "/" then out[#out + 1] = k:sub(#dir + 2) end
      end
      return out
    end,
  }
end

-- Parse the main conf into nftset= directives in dnsmasq's parse order,
-- reading each conf-file= shard inline where it is referenced.
local function directives(conf, fs)
  local out = {}
  local function parse(text)
    for line in text:gmatch("[^\n]+") do
      local inc = line:match("^conf%-file=(.+)$")
      if inc then
        parse(assert(fs._files[inc], "conf-file= points at a missing shard " .. inc))
      else
        local domain, specs = line:match("^nftset=/([^/]+)/(.+)$")
        if domain then
          local sets = {}
          for spec in specs:gmatch("[^,]+") do
            sets[spec:match("([^#]+)$")] = true
          end
          out[#out + 1] = { domain = domain, sets = sets }
        end
      end
    end
  end
  parse(conf)
  return out
end

-- dnsmasq's domain_find_sets: longest suffix match; on a tie the first-parsed
-- directive wins (the list is built by prepending).
local function chosen_sets(dirs, name)
  local best, best_len
  for _, d in ipairs(dirs) do
    local dl = #d.domain
    local matches = name == d.domain
      or (#name > dl and name:sub(-dl - 1) == "." .. d.domain)
    if matches and (not best_len or dl > best_len) then
      best, best_len = d, dl
    end
  end
  return best and best.sets or {}
end

local function snapshot(rules, global)
  return {
    etag = "sha256:2995", generatedAt = "2026-11-05T00:00:00Z",
    devices  = { ["aa:bb:cc:11:22:33"] = { profileId = 3, name = "kid" } },
    profiles = {
      ["3"] = {
        name = "kids",
        rules = {
          blocked      = false,
          extraBlocked = rules.extraBlocked or {},
          extraAllowed = rules.extraAllowed or {},
          blocklistIds = { "games" },
          blockIpOnly  = false,
        },
        failureMode = "block-all",
      },
    },
    global = global or {
      blocked = false, extraBlocked = {}, extraAllowed = {}, blocklistIds = {}, blockIpOnly = false,
    },
    blocklists = { games = { version = "g1", url = "/api/blocklists/games" } },
  }
end

-- Render exactly as the agent does: shards (refresh_blocklists) first, then the
-- main conf (policy.apply), handing the shard render's main-host bl_ specs on.
local function render_all(s, members)
  local fs = stream_fs()
  fs._files[CACHE .. "/games-g1.txt"] = members
  local _, main_specs = render.nftset_host_specs(s)
  local state = {}
  blocklists.render_outputs(s, fs, CACHE, render.SHARD_DIR, INDEX, nil, {}, state, main_specs)
  local conf = render.dnsmasq(s, { bl_specs_by_host = state.main_bl_specs })
  return directives(conf, fs)
end

local function assert_fills(dirs, name, want)
  local got = chosen_sets(dirs, name)
  for _, set in ipairs(want) do
    assert.is_true(got[set] == true,
      string.format("%s must fill %s at resolve time", name, set))
  end
end

describe("nftset= coverage under dnsmasq's one-directive rule (#2995)", function()
  it("an extraAllowed host that is also a blocklist member fills both sets", function()
    local dirs = render_all(snapshot({ extraAllowed = { "mojang.com" } }), "mojang.com\nroblox.com\n")
    for _, name in ipairs({ "mojang.com", "www.mojang.com" }) do
      assert_fills(dirs, name, { "ea_mojang_com", "ea6_mojang_com", "bl_games", "bl6_games" })
    end
    assert_fills(dirs, "roblox.com", { "bl_games" })
  end)

  it("an extraAllowed host under a blocklist member still fills the bl_ set", function()
    local dirs = render_all(snapshot({ extraAllowed = { "www.example.com" } }), "example.com\n")
    assert_fills(dirs, "www.example.com", { "ea_www_example_com", "bl_games" })
    assert_fills(dirs, "cdn.www.example.com", { "ea_www_example_com", "bl_games" })
    assert_fills(dirs, "api.example.com", { "bl_games" })
  end)

  it("a blocklist member under an extraAllowed host still fills the ea_ set", function()
    local dirs = render_all(snapshot({ extraAllowed = { "example.org" } }), "cdn.example.org\n")
    assert_fills(dirs, "cdn.example.org", { "ea_example_org", "bl_games" })
    assert_fills(dirs, "a.cdn.example.org", { "ea_example_org", "bl_games" })
    assert_fills(dirs, "www.example.org", { "ea_example_org" })
  end)

  it("an extraBlocked host that is also a blocklist member fills both drop sets", function()
    local dirs = render_all(snapshot({ extraBlocked = { "roblox.com" } }), "roblox.com\n")
    assert_fills(dirs, "www.roblox.com", { "eb_roblox_com", "bl_games" })
  end)

  it("global allow and global block hosts that are blocklist members fill both sets", function()
    local global = {
      blocked = false, blockIpOnly = false, blocklistIds = {},
      extraAllowed = { "allowed.example" }, extraBlocked = { "blocked.example" },
    }
    local dirs = render_all(snapshot({}, global), "allowed.example\nblocked.example\n")
    assert_fills(dirs, "x.allowed.example", { "global_allow", "bl_games" })
    assert_fills(dirs, "x.blocked.example", { "global_block", "bl_games" })
  end)
end)
