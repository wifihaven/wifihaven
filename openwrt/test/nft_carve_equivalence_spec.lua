-- #2901: the rendered forward-drop and block-page DNAT rules enforce exactly
-- the documented precedence (docs/architecture.md §0.2-0.3), whatever shape
-- render.nft gives them.
--
-- Each case renders a snapshot, walks a matrix of (mac, family, destination)
-- packets through the ruleset with test/nft_eval.lua, and compares every
-- verdict with an oracle written from the precedence alone:
--
--   global.extraAllowed → global block → per-MAC extraAllowed → per-MAC block
--   → blockIpOnly
--
-- i.e. a per-MAC block (blocked / extraBlocked / blocklist) is carved out by
-- the MAC's extraAllowed hosts and by global.extraAllowed; a global block
-- (hosts, categories, lockdown) is carved out only by global.extraAllowed;
-- blockIpOnly is carved out by nothing. A destination is symbolic: the set of
-- nft sets dnsmasq would have put its address in.
--
-- The oracle reads policy through render's resolution helpers
-- (effective_rules, effective_extra_allowed_by_mac) and set-name helpers, never
-- through rule text, so a change to how rules are laid out cannot move it.

package.path = "./test/?.lua;" .. package.path
local render   = require("render")
local nft_eval = require("nft_eval")

-- ── snapshots ───────────────────────────────────────────────────────────────

local function hosts(prefix, n)
  local out = {}
  for i = 1, n do out[#out + 1] = string.format("%s%d.example", prefix, i) end
  return out
end

local function concat(...)
  local out = {}
  for _, t in ipairs({ ... }) do
    for _, v in ipairs(t) do out[#out + 1] = v end
  end
  return out
end

local function rules(t)
  return {
    blocked      = t.blocked or false,
    blockReason  = t.blockReason,
    extraBlocked = t.extraBlocked or {},
    extraAllowed = t.extraAllowed or {},
    blocklistIds = t.blocklistIds or {},
    blockIpOnly  = t.blockIpOnly or false,
  }
end

-- Shaped like the family router's snapshot (#2901): several profiles with
-- 14-20 allowed hosts and many blocked hosts each, blocked profiles with
-- carve-outs, a host that is both blocked and allowed, a host allowed both
-- per-profile and globally, category lists, blockIpOnly, an inline
-- (unmanaged-path) override, an allow-all profile, and global allow + block.
local function family_shape()
  local shared = "shared.example"      -- blocked AND allowed on profile 1
  local both   = "both.example"        -- allowed per-profile AND globally
  return {
    etag = "sha256:2901", generatedAt = "2026-10-12T00:00:00Z",
    devices = {
      ["02:00:00:00:00:01"] = { profileId = 1 },
      ["02:00:00:00:00:02"] = { profileId = 1 },
      ["02:00:00:00:00:03"] = { profileId = 2 },
      ["02:00:00:00:00:04"] = { profileId = 3 },
      ["02:00:00:00:00:05"] = { profileId = 4 },
      ["02:00:00:00:00:06"] = { profileId = 5 },
      ["02:00:00:00:00:07"] = { profileId = 6 },
      ["02:00:00:00:00:08"] = { rules = rules({ blocked = true, blockReason = "Unmanaged" }) },
      ["02:00:00:00:00:09"] = { profileId = 7 },
      ["02:00:00:00:00:0a"] = {},
    },
    profiles = {
      ["1"] = { failureMode = "block-all", rules = rules({
        extraBlocked = concat(hosts("kb", 12), { shared }),
        extraAllowed = concat(hosts("ka", 14), { shared, both }),
        blocklistIds = { "ads", "social" },
      }) },
      ["2"] = { failureMode = "last-known-good", rules = rules({
        blocked = true, blockReason = "Schedule",
        extraBlocked = hosts("tb", 8),
        extraAllowed = hosts("ta", 20),
        blocklistIds = { "ads" },
      }) },
      ["3"] = { failureMode = "block-all", rules = rules({
        blocked = true, blockReason = "Paused",
        extraBlocked = hosts("lb", 5),
        extraAllowed = hosts("la", 14),
        blockIpOnly = true,
      }) },
      ["4"] = { failureMode = "last-known-good", rules = rules({}) },
      ["5"] = { failureMode = "last-known-good", rules = rules({
        blocked = true, blockReason = "DefaultDeny",
        extraAllowed = hosts("da", 3),
      }) },
      ["6"] = { failureMode = "allow-all", rules = rules({
        extraBlocked = hosts("fb", 3),
        extraAllowed = hosts("fa", 2),
        blocklistIds = { "social" },
      }) },
      -- extraAllowed but nothing to carve it out of: blockIpOnly is never
      -- carved, so this MAC has no carvable drop at all.
      ["7"] = { failureMode = "last-known-good", rules = rules({
        extraAllowed = hosts("oa", 4),
        blockIpOnly = true,
      }) },
    },
    blocklists = {
      ads    = { version = "v1", url = "/api/blocklists/ads" },
      social = { version = "v1", url = "/api/blocklists/social" },
    },
    global = rules({
      extraAllowed = { "app.example", both },
      extraBlocked = { "gblock.example" },
      blocklistIds = { "ads" },
    }),
  }
end

local function no_global()
  local s = family_shape(); s.global = nil; return s
end

local function lockdown()
  local s = family_shape()
  s.global.blocked = true
  s.global.blockReason = "Manual"
  return s
end

local function global_ip_only()
  local s = family_shape(); s.global.blockIpOnly = true; return s
end

-- ── oracle ──────────────────────────────────────────────────────────────────

local function set_name(kind, family, key)
  local v6 = family == "ip6"
  if kind == "ea" then return v6 and render.ea6_set_name(key) or render.ea_set_name(key) end
  if kind == "eb" then return v6 and render.eb6_set_name(key) or render.eb_set_name(key) end
  if kind == "bl" then return v6 and render.bl6_set_name(key) or render.bl_set_name(key) end
  if kind == "resolved" then
    local base = "resolved_" .. render.sanitize(key)
    return v6 and ("resolved6_" .. render.sanitize(key)) or base
  end
  if kind == "ga" then return v6 and "global_allow6" or "global_allow" end
  if kind == "gb" then return v6 and "global_block6" or "global_block" end
  error(kind)
end

local Oracle = {}
Oracle.__index = Oracle

function Oracle.new(snap, opts)
  local o = setmetatable({ snap = snap }, Oracle)
  local g = render.global_rules(snap) or {}
  o.has_ga = type(g.extraAllowed) == "table" and #g.extraAllowed > 0
  o.has_gb = (type(g.extraBlocked) == "table" and #g.extraBlocked > 0)
          or (type(g.blocklistIds) == "table" and #g.blocklistIds > 0)
  o.g_blocked = g.blocked and true or false
  o.g_reason = g.blockReason or "blocked"
  o.g_ip_only = g.blockIpOnly and true or false
  o.ea_by_mac = render.effective_extra_allowed_by_mac(snap)
  o.in_failover = opts and opts.link_failed and true or false
  return o
end

function Oracle:profile_mode(mac)
  local dev = self.snap.devices[mac]
  if not (dev and dev.profileId) then return nil end
  local p = self.snap.profiles[string.format("%d", dev.profileId)]
  return p and p.failureMode
end

function Oracle:allowall(mac)
  return self.in_failover and self:profile_mode(mac) == "allow-all"
end

-- What a packet from `mac` to a destination in `sets` would be subject to.
-- c.reason is the label of the first drop that applies, in the order render
-- has always met them: whole-MAC, extraBlocked, blocklists, blockIpOnly,
-- global block, lockdown.
function Oracle:conditions(mac, family, sets)
  local c = {}
  local dev = self.snap.devices[mac]
  if not dev or self:allowall(mac) then return c end
  local r = render.effective_rules(dev, self.snap.profiles)
  local function in_set(kind, key) return sets[set_name(kind, family, key)] == true end

  local ga = self.has_ga and in_set("ga")
  local ea = false
  for _, h in ipairs(self.ea_by_mac[mac] or {}) do
    if in_set("ea", h) then ea = true end
  end

  local reasons = {}
  if r and not ea and not ga then
    if r.blocked then reasons[#reasons + 1] = r.blockReason or "blocked" end
    for _, h in ipairs(r.extraBlocked or {}) do
      if in_set("eb", h) then reasons[#reasons + 1] = "host:" .. h end
    end
    for _, id in ipairs(r.blocklistIds or {}) do
      if self.snap.blocklists[id] and in_set("bl", id) then
        reasons[#reasons + 1] = "category:" .. id
      end
    end
  end
  c.per_mac = #reasons > 0
  if (r and r.blockIpOnly) or self.g_ip_only then
    c.ip_only = not in_set("resolved", mac)
    if c.ip_only then reasons[#reasons + 1] = "ip_only" end
  end
  if not ga then
    if self.has_gb and in_set("gb") then
      c.global = true
      reasons[#reasons + 1] = "global_block"
    end
    if self.g_blocked then
      c.global = true
      reasons[#reasons + 1] = self.g_reason
    end
  end
  if reasons[1] then c.reason = "wh_drop:" .. mac .. ":" .. reasons[1] end
  return c
end

-- → verdict, label of the dropping rule (nil for the failover chain's drop,
-- which carries no label).
function Oracle:forward(pkt)
  if self.in_failover and self:profile_mode(pkt.mac) == "block-all" then return "drop" end
  if pkt.is_local then return "accept" end
  local c = self:conditions(pkt.mac, pkt.family, pkt.sets)
  if c.reason then return "drop", c.reason end
  return "accept"
end

function Oracle:prerouting(pkt)
  if pkt.dport ~= 80 and pkt.dport ~= 443 then return "none" end
  if pkt.is_loopback6 then return "none" end
  local c = self:conditions(pkt.mac, pkt.family, pkt.sets)
  if c.per_mac or c.ip_only or c.global then return "redirect" end
  return "none"
end

-- ── packet matrix ───────────────────────────────────────────────────────────

local STRANGER = "02:00:00:00:00:ff"

-- Every set relevant to `mac` in `family`, plus one foreign MAC's set.
local function universe(o, snap, mac, family)
  local u, seen = {}, {}
  local function add(n) if not seen[n] then seen[n] = true; u[#u + 1] = n end end
  local dev = snap.devices[mac]
  local r = dev and render.effective_rules(dev, snap.profiles)
  for _, h in ipairs(o.ea_by_mac[mac] or {}) do add(set_name("ea", family, h)) end
  if r then
    for _, h in ipairs(r.extraBlocked or {}) do add(set_name("eb", family, h)) end
    for _, id in ipairs(r.blocklistIds or {}) do add(set_name("bl", family, id)) end
  end
  add(set_name("resolved", family, mac))
  if o.has_ga then add(set_name("ga", family)) end
  if o.has_gb then add(set_name("gb", family)) end
  add(set_name("eb", family, "kb1.example"))
  add(set_name("bl", family, "social"))
  return u
end

local function each_packet(o, snap, fn)
  local macs = {}
  for mac in pairs(snap.devices) do macs[#macs + 1] = mac end
  macs[#macs + 1] = STRANGER
  table.sort(macs)
  for _, mac in ipairs(macs) do
    for _, family in ipairs({ "ip", "ip6" }) do
      local u = universe(o, snap, mac, family)
      local classes = { {} }
      for i = 1, #u do
        classes[#classes + 1] = { [u[i]] = true }
        for j = i + 1, #u do
          classes[#classes + 1] = { [u[i]] = true, [u[j]] = true }
        end
      end
      -- Triples over one set of each kind, so every carve × drop × global
      -- combination is reached with a third set present.
      local picks, kinds = {}, {}
      for _, n in ipairs(u) do
        local k = n:match("^(%a+)")
        if not kinds[k] then kinds[k] = true; picks[#picks + 1] = n end
      end
      for i = 1, #picks do
        for j = i + 1, #picks do
          for k = j + 1, #picks do
            classes[#classes + 1] = { [picks[i]] = true, [picks[j]] = true, [picks[k]] = true }
          end
        end
      end
      for _, sets in ipairs(classes) do
        for _, dport in ipairs({ 80, 443, 22 }) do
          fn({ mac = mac, family = family, sets = sets, dport = dport,
               iif = "br-lan", oif = "wan" })
        end
      end
      fn({ mac = mac, family = family, sets = {}, dport = 80, is_local = true,
           iif = "br-lan", oif = "br-lan" })
      if family == "ip6" then
        fn({ mac = mac, family = family, sets = {}, dport = 80, is_loopback6 = true,
             iif = "br-lan", oif = "wan" })
      end
    end
  end
end

local function describe_pkt(p)
  local s = {}
  for n in pairs(p.sets) do s[#s + 1] = n end
  table.sort(s)
  return string.format("%s %s dport=%d local=%s lo6=%s {%s}", p.mac, p.family, p.dport,
    tostring(p.is_local or false), tostring(p.is_loopback6 or false), table.concat(s, ","))
end

-- Returns the mismatches (at most `limit`) and the number of packets checked.
local function compare(nft_text, snap, opts, limit)
  local ev = nft_eval.parse(nft_text)
  local o = Oracle.new(snap, opts)
  local bad, n = {}, 0
  each_packet(o, snap, function(p)
    n = n + 1
    local want_f, want_label = o:forward(p)
    local got_f, got_label = nft_eval.forward(ev, p)
    local want_n, got_n = o:prerouting(p), nft_eval.prerouting(ev, p)
    if (want_f ~= got_f or want_label ~= got_label or want_n ~= got_n)
        and #bad < (limit or 5) then
      bad[#bad + 1] = string.format(
        "%s: forward want %s (%s) got %s (%s); dnat want %s got %s",
        describe_pkt(p), want_f, tostring(want_label), got_f, tostring(got_label),
        want_n, got_n)
    end
  end)
  return bad, n
end

local CASES = {
  { name = "family-shaped snapshot",          snap = family_shape },
  { name = "no global section",               snap = no_global },
  { name = "global lockdown",                 snap = lockdown },
  { name = "global blockIpOnly",              snap = global_ip_only },
  { name = "API unreachable (failover)",      snap = family_shape, opts = { link_failed = true } },
}

describe("#2901 render.nft enforces the documented precedence", function()
  for _, case in ipairs(CASES) do
    it("matches the oracle for every packet: " .. case.name, function()
      local snap = case.snap()
      local bad, n = compare(render.nft(snap, case.opts), snap, case.opts)
      assert.is_true(n > 10000, "matrix too small: " .. n)
      assert.are.same({}, bad)
    end)
  end
end)

describe("#2901 rule shape: carve sets are listed once per MAC, not per drop", function()
  it("references each MAC's ea_ sets at most once in the forward chain and once in the DNAT chain", function()
    local snap = family_shape()
    local nft = render.nft(snap)
    local _, refs = nft:gsub("@ea_", "")
    local _, refs6 = nft:gsub("@ea6_", "")
    local bound = 0
    for _, hs in pairs(render.effective_extra_allowed_by_mac(snap)) do
      bound = bound + 2 * #hs
    end
    assert.is_true(refs <= bound, string.format("%d @ea_ references, bound %d", refs, bound))
    assert.is_true(refs6 <= bound, string.format("%d @ea6_ references, bound %d", refs6, bound))
  end)

  it("no rule line carries more than one carve set", function()
    local nft = render.nft(family_shape())
    for line in nft:gmatch("[^\n]+") do
      local _, n = line:gsub("@ea6?_", "")
      assert.is_true(n <= 1, line)
    end
  end)
end)

-- The equivalence check above is only worth something if it fails when the
-- precedence is broken. Each mutation below breaks the rendered ruleset in
-- one way that changes enforcement; the oracle comparison must catch it.
describe("#2901 the equivalence check catches a broken precedence", function()
  -- Apply `fn` to the rendered text; the mutation must change something, or
  -- an "undetected" pass would be vacuous.
  local function mutated(fn)
    local snap = family_shape()
    local nft = render.nft(snap)
    local out = fn(nft)
    assert.are_not.equal(nft, out, "mutation did not apply")
    return out, snap
  end

  local function gsub_n(pattern, repl)
    return function(nft)
      local out, n = nft:gsub(pattern, repl)
      assert.is_true(n > 0, "pattern not found: " .. pattern)
      return out
    end
  end

  -- Drop one line from the named chain only.
  local function drop_line_in_chain(chain, needle)
    return function(nft)
      local out, in_chain, hit = {}, false, false
      for line in (nft .. "\n"):gmatch("([^\n]*)\n") do
        if line:match("^  chain " .. chain .. " {$") then in_chain = true
        elseif in_chain and line == "  }" then in_chain = false end
        if in_chain and line:find(needle, 1, true) then
          hit = true
        else
          out[#out + 1] = line
        end
      end
      assert.is_true(hit, needle .. " not found in chain " .. chain)
      return table.concat(out, "\n")
    end
  end

  local MUTATIONS = {
    { "global allow no longer carves a per-MAC drop",
      drop_line_in_chain("wh_carve_020000000003", "ip daddr @global_allow return") },
    { "an extraAllowed host no longer carves a per-MAC drop",
      drop_line_in_chain("wh_carve_020000000001", "ip daddr @ea_ka1_example return") },
    { "an extraAllowed host no longer carves a block-page DNAT",
      drop_line_in_chain("wh_carve_nat_020000000003", "ip daddr @ea_ta1_example return") },
    { "a carve accepts instead of returning (skips blockIpOnly and global drops)",
      gsub_n("(@ea6?_la1_example) return", "%1 accept") },
    { "jump becomes goto (a carve return would end the base chain)",
      gsub_n("jump (wh_carve_)", "goto %1") },
    { "the jump moves after the global drops",
      function(nft)
        local j = "    ether saddr 02:00:00:00:00:01 jump wh_carve_020000000001\n"
        local out, n = nft:gsub(j, "", 1)
        assert.are.equal(1, n)
        local anchor = "    fib daddr type local counter accept comment \"wh_block_page:never-drop:#1865\"\n"
        assert.truthy(out:find(anchor, 1, true))
        -- Re-insert it as the base chain's last rule.
        local head, tail = out:match("^(.-chain wifihaven_block {.-)(\n  }\n.*)$")
        assert.truthy(head)
        return head .. "\n" .. j:gsub("\n$", "") .. tail
      end },
  }

  for _, m in ipairs(MUTATIONS) do
    it("detects: " .. m[1], function()
      local nft, snap = mutated(m[2])
      local bad = compare(nft, snap, nil, 1)
      assert.is_true(#bad > 0, "mutation went undetected: " .. m[1])
    end)
  end
end)
