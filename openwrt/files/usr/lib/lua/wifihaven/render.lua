-- render.lua — generates dnsmasq and nftables config from a policy snapshot
--
-- Snapshot field naming: the API serializes PolicySnapshot/DevicePolicy/
-- ProfilePolicy/BlockRules via zio-json deriveCodec, which keeps the Scala
-- case-class field names verbatim (camelCase) — see shared/src/Models.scala.
--
-- Snapshot shape (post-#354 — see docs/architecture.md §0.2):
--   snapshot.devices    table   mac -> { profileId, name, rules? }
--   snapshot.profiles   table   profileIdStr -> { name, rules, failureMode }
--   snapshot.blocklists table   id -> { version, url }
--
-- ── Policy layer split ────────────────────────────────────────────────────
--
-- Enforcement is a predicate, not an ordered rule list. nft chains here are
-- emitted with `policy accept` and a flat list of independent terminal
-- `drop` rules — a packet is dropped iff *any* drop rule matches. Order of
-- the `drop` lines does not affect the outcome (only minor packet-path
-- perf), so the predicate is the disjunction of all drop conditions.
--
-- For a forwarded packet from MAC m to v4/v6 destination d, let
--   ea_hit(m, d) ⇔ ∃ a ∈ extraAllowed(m). d ∈ @ea_<m>_a   (or @ea6_<m>_a v6).
-- Then:
--
--   drop(m, d) ⇔
--         ( m ∈ blocked_macs ∧ ¬ea_hit(m, d) ∧ ¬ga_hit(d) )
--      ∨  ( ∃ h ∈ extraBlocked(m). d ∈ @eb_h ∧ ¬ea_hit(m, d) ∧ ¬ga_hit(d) )
--      ∨  ( ∃ id ∈ blocklistIds(m). d ∈ @bl_id ∧ ¬ea_hit(m, d) ∧ ¬ga_hit(d) )
--      ∨  ( d ∈ @global_block ∧ ¬ga_hit(d) )         (#1319 global block, all MACs)
--      ∨  ( G.blocked ∧ ¬ga_hit(d) )                 (#1319 global lockdown)
--      ∨  blockIpOnly(m) ∧ d ∉ @resolved_<m>       (or @resolved6_<m> for v6)
--      ∨  m ∈ @failover_drop                       (block-all failover only)
--
-- where ga_hit(d) ⇔ d ∈ @global_allow (or @global_allow6) and @global_block =
-- global.extraBlocked ∪ members(global.blocklistIds). G is the snapshot's flat
-- `global` BlockRules (#1316). The asymmetry is load-bearing: a per-MAC ea_hit
-- suppresses only the per-MAC drops, but @global_allow (ga_hit) suppresses
-- EVERY drop incl. the global ones; a global block is suppressed ONLY by
-- @global_allow. See the "#1319 global policy composition" helpers below and
-- docs/design/global-policy-layer.md §5.2.
--
-- allow := ¬drop. extraAllowed beats every "blocked" path (#421):
-- the @blocked_macs / eb_ / bl_ drop rules each carry one `ip daddr != @ea_<m>_<a>`
-- clause per host in m's effective extraAllowed list, so a hit in any
-- ea_ set suppresses those drops for that (mac, packet). For the
-- @blocked_macs path: blocked MACs *with* extraAllowed are pulled out of
-- the set into per-MAC rules carrying the ea exception (one rule per
-- family; the family-agnostic `ether saddr @blocked_macs drop` cannot
-- carry an `ip daddr` predicate). Blocked MACs with no extraAllowed
-- stay in the set and drop unconditionally as before.
-- blockIpOnly is intentionally NOT suppressed by ea sets — extraAllowed
-- composes via the dnsmasq resolver populating resolved_<m> at A/AAAA
-- time, so a resolved-allowed host already lands in resolved_<m>.
-- Time-limit / schedule / pause are collapsed server-side into
-- rules.blocked + extraBlocked / extraAllowed by the API, so the agent
-- never sees a temporal field.
--
-- Layer split:
--   API server  — pre-evaluates schedules, time limits, pauses; resolves
--                 admin time-limit-exhausted carve-outs into extraBlocked /
--                 extraAllowed; ships a "what to enforce right now"
--                 BlockRules snapshot.
--   Router      — emits the predicates above as nft drop rules. dnsmasq
--                 populates the ipv4/ipv6_addr sets at DNS resolve time
--                 via `nftset=` callbacks. No re-evaluation of policy.
--
-- The agent never evaluates schedules / time limits / pause. Server has
-- already collapsed them into per-profile BlockRules.blocked.
--
-- Public API:
--   render.dnsmasq(snapshot)  → string  (/tmp/dnsmasq.d/wifihaven.conf content)
--   render.nft(snapshot, opts) → string (/tmp/nftables.d/wifihaven.nft content)
--   render.update_shared(snapshot, nft_sets, blocked_macs, blocked_reason)
--     Rebuilds blocked_macs / blocked_reason in place from per-MAC
--     effective rules. nft_sets is cleared of stale per-host entries that
--     have no current source; population is otherwise driven by dnsmasq
--     --ipset= callbacks and dns-tail (#259).

local M = {}

-- Replace dots and colons with underscores (nftables set/counter name-safe).
local function sanitize(s)
  return (s:gsub("[%.%:]", "_"))
end
M.sanitize = sanitize

-- Resolve a device's effective BlockRules. Returns the rules table or nil
-- if the device has no profile and no override (and therefore no rules
-- apply — caller must treat as "permitted").
local function effective_rules(dev, profiles)
  if dev == nil then return nil end
  -- JSON decoders represent `null` differently (cjson uses a userdata
  -- sentinel; luci.jsonc uses nil). Only treat a real table as an override.
  if type(dev.rules) == "table" then return dev.rules end
  if dev.profileId == nil then return nil end
  -- cjson decodes JSON integers as Lua *floats*, so a profileId of 3
  -- arrives as 3.0 and `tostring(3.0)` yields "3.0" on Lua 5.3+, missing
  -- the profiles["3"] key. Use %d formatting to coerce to an integer
  -- decimal representation that matches the JSON-object key shape.
  local key = string.format("%d", dev.profileId)
  local prof = profiles and profiles[key]
  if type(prof) ~= "table" then return nil end
  if type(prof.rules) ~= "table" then return nil end
  return prof.rules
end
M.effective_rules = effective_rules

-- Iterate the device map in a deterministic (mac-sorted) order. Many of
-- our nft set element lists and dnsmasq dhcp-host lines should be stable
-- across rerenders so the etag-equivalent on-disk content doesn't churn.
local function sorted_devices(devices)
  local keys = {}
  for mac, _ in pairs(devices or {}) do keys[#keys + 1] = mac end
  table.sort(keys)
  local i = 0
  return function()
    i = i + 1
    local mac = keys[i]
    if mac == nil then return nil end
    return mac, devices[mac]
  end
end

local function sorted_keys(tbl)
  local keys = {}
  for k, _ in pairs(tbl or {}) do keys[#keys + 1] = k end
  table.sort(keys)
  return keys
end

local function sorted_profiles(profiles)
  local keys = {}
  for pid, _ in pairs(profiles or {}) do keys[#keys + 1] = pid end
  table.sort(keys)
  local i = 0
  return function()
    i = i + 1
    local pid = keys[i]
    if pid == nil then return nil end
    return pid, profiles[pid]
  end
end

-- Build the set of hosts that appear in any device's *effective* extraBlocked
-- list. Hosts only referenced by a profile that has no assigned device are
-- skipped — no point populating an ipset that nothing will drop on. Returns
-- a sorted unique list of hostnames.
local function effective_extra_blocked_hosts(snapshot)
  local seen = {}
  for _, dev in pairs(snapshot.devices or {}) do
    local r = effective_rules(dev, snapshot.profiles)
    if r and type(r.extraBlocked) == "table" then
      for _, host in ipairs(r.extraBlocked) do seen[host] = true end
    end
  end
  local hosts = {}
  for h in pairs(seen) do hosts[#hosts + 1] = h end
  table.sort(hosts)
  return hosts
end

-- nft set names for an extraBlocked host. `eb_` is the v4 ipset; `eb6_` is
-- the v6 sibling (#392) so a dual-stack or v6-only host can't escape the
-- per-(MAC, host) drop via AAAA records. Same hash for both so they stay
-- parallel in the config.
local function eb_set_name(host)
  return "eb_" .. sanitize(host)
end
local function eb6_set_name(host)
  return "eb6_" .. sanitize(host)
end

-- nft set names for a blocklist id (#352, #392). Replaces dots, colons, and
-- hyphens with underscores (nftables set names allow only [a-zA-Z0-9_]).
local function bl_sanitize(id)
  return (id:gsub("[%.%:%-%s]", "_"))
end
local function bl_set_name(id)
  return "bl_" .. bl_sanitize(id)
end
local function bl6_set_name(id)
  return "bl6_" .. bl_sanitize(id)
end

-- #353: per-MAC "resolved IP" sets. A device with BlockRules.blockIpOnly=true
-- gets a forward-chain drop on any daddr NOT in its resolved_<mac> /
-- resolved6_<mac> set — enforcing "use the LAN resolver or you're dropped."
--
-- Population path (#505): dnsmasq 2.91 does NOT accept a `tag:` prefix in
-- `nftset=` directives (the parser logs `Error: syntax error, unexpected
-- colon` and silently drops the line). Per-MAC scoping at DNS resolve time
-- is therefore impossible at the dnsmasq layer, and cross-MAC pollination
-- is NOT acceptable here — the whole semantic point of blockIpOnly is "this
-- MAC may only contact destinations it itself resolved". So the sets are
-- populated out-of-band by the `wifihaven-dns-tail` sidecar, which already
-- tails dnsmasq's query log and parses `reply ... is <ip>` lines: it maps
-- the client IP in the log line back to a MAC via /tmp/dhcp.leases and runs
-- `nft add element` against the matching resolved_/resolved6_ set.
local function resolved_set_name(mac)
  return "resolved_" .. sanitize(mac)
end
local function resolved6_set_name(mac)
  return "resolved6_" .. sanitize(mac)
end

-- #421: per-(MAC, host) extraAllowed exception sets. ea_<sanmac>_<sanhost>
-- holds the v4 resolved IPs of host a; ea6_ is the v6 sibling. The eb_/bl_
-- drop rules append `ip daddr != @ea_<m>_<a>` for each a ∈ extraAllowed(m),
-- so a hit suppresses the drop. The MAC scoping lives entirely in the nft
-- rule (`ether saddr <m>`); the set name carries the MAC only as a unique
-- per-(MAC, host) identifier — dnsmasq populates it from any client's
-- resolution of the host (#496: dnsmasq's `nftset=` does not support a
-- `tag:` prefix in 2.91, so per-MAC DNS-time scoping isn't an option here).
-- Cross-pollination is benign: an IP belonging to host h enters every
-- ea_<m>_h set declared, but a MAC <m'> without h in its extraAllowed has
-- no corresponding nft rule referencing ea_<m'>_h, so the set is unread.
local function ea_set_name(mac, host)
  return "ea_" .. sanitize(mac) .. "_" .. sanitize(host)
end
local function ea6_set_name(mac, host)
  return "ea6_" .. sanitize(mac) .. "_" .. sanitize(host)
end

-- Per-MAC effective extraAllowed hosts. Returns { [mac] = {host, ...} } with
-- hosts sorted + deduplicated; macs with no effective extraAllowed entries
-- are absent from the map.
local function effective_extra_allowed_by_mac(snapshot)
  local result = {}
  for mac, dev in pairs(snapshot.devices or {}) do
    local r = effective_rules(dev, snapshot.profiles)
    if r and type(r.extraAllowed) == "table" and #r.extraAllowed > 0 then
      local seen = {}
      local hosts = {}
      for _, h in ipairs(r.extraAllowed) do
        if not seen[h] then
          seen[h] = true
          hosts[#hosts + 1] = h
        end
      end
      table.sort(hosts)
      result[mac] = hosts
    end
  end
  return result
end
M.effective_extra_allowed_by_mac = effective_extra_allowed_by_mac

-- Sorted list of MACs with effective blockIpOnly=true. Used by both
-- render.dnsmasq (to emit the per-MAC nftset populator) and render.nft
-- (to declare sets + emit drops).
local function block_ip_only_macs(snapshot)
  local macs = {}
  for mac, dev in pairs(snapshot.devices or {}) do
    local r = effective_rules(dev, snapshot.profiles)
    if r and r.blockIpOnly then
      macs[#macs + 1] = mac
    end
  end
  table.sort(macs)
  return macs
end
M.block_ip_only_macs = block_ip_only_macs

-- ---------------------------------------------------------------------------
-- #1319: global policy composition.
-- ---------------------------------------------------------------------------
-- The snapshot carries one fleet-wide `global` BlockRules (#1316), applied
-- FLAT to every MAC alongside that MAC's resolved per-MAC BlockRules. It is
-- NOT a third merge tier — the router holds one extra BlockRules and layers
-- it with fixed precedence (docs/design/global-policy-layer.md §5.2):
--
--   ga(d)       ⇔ d ∈ G.extraAllowed                  (@global_allow)
--   gblock(d)   ⇔ G.blocked ∨ d ∈ G.extraBlocked ∨ d ∈ ⋃ipset(G.blocklistIds)
--   rblock(m,d) ⇔ R.blocked ∨ d ∈ R.extraBlocked ∨ d ∈ ⋃ipset(R.blocklistIds)
--   drop(m,d)   ⇔ ¬ga(d) ∧ ( gblock(d) ∨ (¬ra(m,d) ∧ rblock(m,d)) )
--                 ∨ (G.blockIpOnly ∨ R.blockIpOnly) ∧ d ∉ resolved_<m>
--
-- Asymmetry: per-MAC extraAllowed (ra) suppresses only per-MAC drops; ONLY
-- @global_allow suppresses a global block / lockdown. @global_allow in turn
-- carves out EVERY drop (per-MAC or global). On nft this is two fleet-wide
-- ipsets — @global_allow (v4) / @global_allow6 and @global_block (v4) /
-- @global_block6 — populated from resolved IPs by dnsmasq `nftset=` callbacks,
-- exactly like the per-host eb_/bl_ sets. blockIpOnly is intentionally NOT
-- carved by ga (allowed hosts are resolved into resolved_<m> naturally), same
-- as the per-MAC ea sets.
local GLOBAL_ALLOW4 = "global_allow"
local GLOBAL_ALLOW6 = "global_allow6"
local GLOBAL_BLOCK4 = "global_block"
local GLOBAL_BLOCK6 = "global_block6"

-- Return the global BlockRules table, or nil if the snapshot carries no global
-- section (older snapshots / tests). nil is treated as BlockRules.allowAll — no
-- global allow, no global block, no lockdown, no strict-IP.
local function global_rules(snapshot)
  local g = snapshot and snapshot.global
  if type(g) == "table" then return g end
  return nil
end
M.global_rules = global_rules

-- Sorted, de-duplicated list of global.extraAllowed hosts (the @global_allow
-- membership). Empty when there is no global section or no global allows.
local function global_allow_hosts(snapshot)
  local g = global_rules(snapshot)
  if not g or type(g.extraAllowed) ~= "table" then return {} end
  local seen, hosts = {}, {}
  for _, h in ipairs(g.extraAllowed) do
    if not seen[h] then seen[h] = true; hosts[#hosts + 1] = h end
  end
  table.sort(hosts)
  return hosts
end
M.global_allow_hosts = global_allow_hosts

-- Sorted, de-duplicated list of hosts forming the @global_block set:
-- global.extraBlocked ∪ the member hosts of every id in global.blocklistIds
-- (expanded from snapshot._blocklist_hosts, the same source that drives the
-- per-category bl_ sets). Empty when there is nothing globally blocked by host.
local function global_block_hosts(snapshot)
  local g = global_rules(snapshot)
  if not g then return {} end
  local seen = {}
  if type(g.extraBlocked) == "table" then
    for _, h in ipairs(g.extraBlocked) do seen[h] = true end
  end
  if type(g.blocklistIds) == "table" then
    local bl = (snapshot and snapshot._blocklist_hosts) or {}
    for _, id in ipairs(g.blocklistIds) do
      for _, h in ipairs(bl[id] or {}) do seen[h] = true end
    end
  end
  local hosts = {}
  for h in pairs(seen) do hosts[#hosts + 1] = h end
  table.sort(hosts)
  return hosts
end
M.global_block_hosts = global_block_hosts

local function global_blocked(snapshot)
  local g = global_rules(snapshot)
  return (g and g.blocked) and true or false
end

local function global_block_ip_only(snapshot)
  local g = global_rules(snapshot)
  return (g and g.blockIpOnly) and true or false
end

-- ---------------------------------------------------------------------------
-- render.dnsmasq(snapshot) → string
-- ---------------------------------------------------------------------------
function M.dnsmasq(snapshot)
  local out = {}
  local function emit(s) out[#out + 1] = s end

  emit("# wifihaven — generated by render.lua, do not edit")
  emit("")

  -- #421: which MACs have non-empty effective extraAllowed. We use this
  -- below to drive per-(MAC, host) ea_ nftset declarations, but no
  -- dhcp-host tag is needed (#496: dnsmasq's nftset= parser rejects the
  -- `tag:` prefix, so the per-MAC scoping lives in the nft rule instead
  -- and the dnsmasq populator is untagged).
  local ea_by_mac = effective_extra_allowed_by_mac(snapshot)

  -- MAC → profile tag so dnsmasq can apply per-profile tagged callbacks.
  -- Devices auto-created from first_seen_mac events have profileId=nil
  -- until an admin assigns them; skip them (no policy to apply yet).
  --
  -- #353/#505: no `set:resolvetag_<sanmac>` tag is appended for blockIpOnly
  -- MACs. The tag-scoped `nftset=tag:...` directive it used to feed is
  -- rejected by dnsmasq 2.91, so resolved_/resolved6_ sets are populated
  -- by the wifihaven-dns-tail sidecar from the dnsmasq query log instead.
  emit("# device MAC → profile tag")
  for mac, dev in sorted_devices(snapshot.devices) do
    if dev.profileId then
      local tags = { string.format("set:profile%d", dev.profileId) }
      emit(string.format("dhcp-host=%s,%s", mac, table.concat(tags, ",")))
    end
  end
  emit("")

  -- #353/#505: per-MAC resolved-IP set population is NOT done via dnsmasq
  -- `nftset=` — dnsmasq 2.91 rejects the `tag:` prefix needed for per-MAC
  -- scoping, and cross-MAC pollination is unacceptable for blockIpOnly
  -- (the whole point of the feature is "this MAC may only contact what
  -- IT resolved"). wifihaven-dns-tail tails the dnsmasq query log and
  -- runs `nft add element` for the right per-MAC set instead. See
  -- openwrt/files/usr/sbin/wifihaven-dns-tail.

  -- nftset= populators for every host the snapshot cares about, across all
  -- channels: per-(MAC, host) extraAllowed (ea_/ea6_, #421/#496), per-host
  -- extraBlocked (eb_/eb6_, #351/#392), per-category blocklist members
  -- (bl_/bl6_, #352/#392), and the fleet-wide #1319 global policy sets
  -- (@global_allow / @global_block).
  --
  -- Syntax: nftset=/<host>/<af>#<family>#<table>#<set>[,<af>#...]
  -- The `4#` / `6#` prefix selects which response type routes to which set:
  -- A → v4 set, AAAA → v6 set. DNS still resolves normally (Truth 1 —
  -- blocking is the connection layer); these directives only populate the
  -- nft sets that the forward-chain rules consume.
  --
  -- **One merged directive per host.** dnsmasq matches `nftset=/<host>/...`
  -- directives by domain, and when multiple separate directives target the
  -- same domain only ONE wins — the others are silently dropped (resulting
  -- in empty sets and a silent enforcement gap). Concretely: if profile.
  -- extraAllowed names a host that global.extraBlocked also names, an
  -- ea_ directive emitted before a global_block directive would leave
  -- @global_block empty and the global block would never fire. The H3
  -- scenario from scripts/e2e/scenarios_fake/test_global_policy.py (and
  -- issue #1460) reproduces this on a real router. Merging every set for
  -- a given host into ONE comma-joined directive sidesteps the parser quirk
  -- (verified live with dnsmasq 2.91 on OpenWRT 23.05).
  --
  -- **The merged directive must stay within dnsmasq's line limit (#1489).**
  -- dnsmasq reads config lines into a fixed MAXDNAME(1025)-byte buffer with no
  -- line continuation, so a directive over 1024 bytes is truncated and its
  -- remainder is parsed as a bogus option — dnsmasq then refuses to start and
  -- :53 returns "connection refused" (the Gate 3a staging-smoke regression in
  -- #1489). Because only one directive per host is honoured we cannot split a
  -- host across lines, so the per-host spec count must stay bounded. The only
  -- per-host spec that scales with device count is the per-(MAC,host) ea_
  -- populator — the #1307 infra-allow copy lands the same host in every
  -- profile's extraAllowed, so one ea_ spec per device would pile onto a
  -- single line. We skip the redundant ones in the ea_ loop below.
  local bl_hosts = snapshot._blocklist_hosts or {}
  local bl_ids   = sorted_keys(snapshot.blocklists or {})
  local ga_hosts = global_allow_hosts(snapshot)
  local gb_hosts = global_block_hosts(snapshot)

  -- host_order preserves first-seen ordering (deterministic by source);
  -- host_specs accumulates the per-host spec list. Source order:
  --   1. ea_  (per-MAC, sorted by mac then host)
  --   2. eb_  (per-host, in effective_extra_blocked_hosts order)
  --   3. bl_  (per-category, in bl_ids order; hosts in source order)
  --   4. ga_  (global allow, sorted)
  --   5. gb_  (global block, sorted)
  -- Within each source the v4 spec precedes v6. add_spec de-duplicates
  -- identical specs per host (e.g. the same host listed twice in one
  -- blocklist) so the merged directive never carries a redundant comma entry.
  local host_order, host_specs, host_seen = {}, {}, {}
  local function add_spec(host, spec)
    if not host_specs[host] then
      host_specs[host] = {}
      host_seen[host] = {}
      host_order[#host_order + 1] = host
    end
    if not host_seen[host][spec] then
      host_seen[host][spec] = true
      host_specs[host][#host_specs[host] + 1] = spec
    end
  end

  -- #1489: a host that is also in global.extraAllowed needs NO per-(MAC,host)
  -- ea_ populator. @global_allow already carves it out of every drop for every
  -- MAC (render.nft's ga_suffix), so the ea_ specs are pure redundancy — and
  -- they are the only per-host nftset spec that scales with device count, so
  -- emitting one per device is what overflows dnsmasq's config-line buffer
  -- above. Skip them; enforcement rides on @global_allow, and dns-tail (#1346)
  -- still populates the per-(MAC,host) ea_ sets from live replies regardless.
  -- This mirrors the #1307→#1321 redundancy the global-allow layer retires.
  local ga_host_set = {}
  for _, h in ipairs(ga_hosts) do ga_host_set[h] = true end

  local ea_macs = {}
  for m in pairs(ea_by_mac) do ea_macs[#ea_macs + 1] = m end
  table.sort(ea_macs)
  for _, mac in ipairs(ea_macs) do
    for _, host in ipairs(ea_by_mac[mac]) do
      if not ga_host_set[host] then
        add_spec(host, "4#inet#wifihaven#" .. ea_set_name(mac, host))
        add_spec(host, "6#inet#wifihaven#" .. ea6_set_name(mac, host))
      end
    end
  end
  for _, host in ipairs(effective_extra_blocked_hosts(snapshot)) do
    add_spec(host, "4#inet#wifihaven#" .. eb_set_name(host))
    add_spec(host, "6#inet#wifihaven#" .. eb6_set_name(host))
  end
  for _, id in ipairs(bl_ids) do
    local set4 = bl_set_name(id)
    local set6 = bl6_set_name(id)
    for _, host in ipairs(bl_hosts[id] or {}) do
      add_spec(host, "4#inet#wifihaven#" .. set4)
      add_spec(host, "6#inet#wifihaven#" .. set6)
    end
  end
  for _, host in ipairs(ga_hosts) do
    add_spec(host, "4#inet#wifihaven#" .. GLOBAL_ALLOW4)
    add_spec(host, "6#inet#wifihaven#" .. GLOBAL_ALLOW6)
  end
  for _, host in ipairs(gb_hosts) do
    add_spec(host, "4#inet#wifihaven#" .. GLOBAL_BLOCK4)
    add_spec(host, "6#inet#wifihaven#" .. GLOBAL_BLOCK6)
  end

  if #host_order > 0 then
    emit("# nftset populators — merged per host so dnsmasq fires every set")
    emit("# (ea+eb+bl+global; see issues 421, 496, 351, 392, 352, 1319 above)")
    for _, host in ipairs(host_order) do
      local specs = host_specs[host]
      local line  = string.format("nftset=/%s/%s", host, table.concat(specs, ","))
      -- #1489 follow-up: the global-extraAllowed skip above only spares a
      -- host that lives in @global_allow. A host in many profiles'
      -- extraAllowed but NOT in global.extraAllowed still piles one ea_ spec
      -- per device onto a single merged line and overflows dnsmasq's 1024-
      -- byte config-line buffer (Gate 3a staging-smoke regression after
      -- #1493 shipped). When that happens, drop the per-(MAC,host) ea_/ea6_
      -- specs from the merged directive: dns-tail (#1346) repopulates the
      -- ea_ sets from live replies, so the per-MAC carve-out is only delayed
      -- until the first reply lands for that host, not lost. Crashing
      -- dnsmasq (which takes :53 down for every device) is strictly worse.
      if #line > 1024 then
        local kept, dropped = {}, 0
        for _, s in ipairs(specs) do
          if s:find("#ea_", 1, true) or s:find("#ea6_", 1, true) then
            dropped = dropped + 1
          else
            kept[#kept + 1] = s
          end
        end
        emit(string.format(
          "# wifihaven: dropped %d per-(MAC,host) ea_ spec(s) for %s to fit dnsmasq's 1024-byte line limit (#1489); dns-tail (#1346) repopulates ea_ sets from live replies",
          dropped, host))
        if #kept > 0 then
          line = string.format("nftset=/%s/%s", host, table.concat(kept, ","))
        else
          -- Only ea_ specs existed for this host. With them gone there is
          -- nothing left to emit; the host carries no surviving nftset=
          -- directive. dns-tail still populates the ea_ sets from live
          -- replies, so the (MAC,host) carve-out is recovered after the
          -- first resolution. Keeping dnsmasq alive is the goal.
          line = nil
        end
      end
      if line then emit(line) end
    end
    emit("")
  end

  return table.concat(out, "\n")
end

-- ---------------------------------------------------------------------------
-- render.blocklist_member_index(snapshot) → string  (#1348)
-- ---------------------------------------------------------------------------
-- A host → bl_ set mapping for the wifihaven-dns-tail bl_ populator.
--
-- bl_<id>/bl6_<id> sets are populated at DNS resolve time by the dnsmasq
-- `nftset=/<member>/...#bl_<id>` directives emitted above, which only fire for
-- queries that suffix-match a member host. A device that re-queries a member's
-- CNAME target directly lands on a CDN-anycast IP dnsmasq never added, so the
-- category drop misses (silent filter bypass). dns-tail closes the gap the same
-- way it does for eb_ (#515): it resolves each answered name through the #1344
-- CNAME-alias map and, when the recovered brand is a blocklist member, adds the
-- IP to that member's bl_ set. dns-tail can see which bl_ sets EXIST but not
-- their MEMBERSHIP, so this exports it.
--
-- Derived from the same `snapshot._blocklist_hosts` that drives the `nftset=`
-- directives, so the set names line up exactly with what render.nft declares.
-- One row per (member host, blocklist id): "<host>\t<bl_set>\t<bl6_set>".
function M.blocklist_member_index(snapshot)
  local bl_hosts = snapshot and snapshot._blocklist_hosts or {}
  local bl_ids   = sorted_keys(snapshot and snapshot.blocklists or {})
  local lines = {}
  for _, id in ipairs(bl_ids) do
    local set4 = bl_set_name(id)
    local set6 = bl6_set_name(id)
    for _, host in ipairs(bl_hosts[id] or {}) do
      lines[#lines + 1] = string.format("%s\t%s\t%s", host, set4, set6)
    end
  end
  return table.concat(lines, "\n") .. (#lines > 0 and "\n" or "")
end

-- ---------------------------------------------------------------------------
-- render.nft(snapshot, opts) → string
-- ---------------------------------------------------------------------------
-- opts (optional table):
--   poll_failed       boolean — true iff the most-recent policy poll attempt
--                              did not succeed. When true, the per-profile
--                              failureMode decides what happens (#385/#422):
--                                "block-all"       → emit an additional drop
--                                                    rule for the profile's
--                                                    devices (fail-safe).
--                                "allow-all"       → suppress this profile's
--                                                    devices from
--                                                    @blocked_macs and from
--                                                    every per-(MAC, host)
--                                                    and per-(MAC,
--                                                    blocklistId) drop, and
--                                                    from the DNAT chain.
--                                                    Cached enforcement is
--                                                    erased for these MACs.
--                                "last-known-good" → no change. The profile's
--                                                    cached snapshot rules
--                                                    keep enforcing exactly
--                                                    as-is (this is the
--                                                    behaviour the original
--                                                    binary "open" had).
--                              When false / absent, all profiles behave as
--                              LastKnownGood — the cached snapshot stands.
--                              The transition is immediate: a single failed
--                              poll trips failover; the next successful poll
--                              lifts it. There is no time-based cushion
--                              (#422 removed the prior 300s gate).
function M.nft(snapshot, opts)
  local out = {}
  local function emit(s)  out[#out + 1] = s  end
  local function ind(s)   out[#out + 1] = "  " .. s  end
  local function ind2(s)  out[#out + 1] = "    " .. s end

  emit("# wifihaven — generated by render.lua, do not edit")
  emit("# Load with a single `nft -f <this file>`. The prelude below performs an")
  emit("# atomic handover from the boot default-deny skeleton (table inet")
  emit("# wifihaven_boot, installed by /etc/init.d/wifihaven-boot — see #308) to")
  emit("# the runtime table in one transaction.")
  emit("add table inet wifihaven_boot")
  emit("delete table inet wifihaven_boot")
  emit("add table inet wifihaven")
  emit("delete table inet wifihaven")
  emit("table inet wifihaven {")
  emit("")

  -- Build profileId → [macs] index for per-profile nft sets used by future
  -- per-MAC enforcement rules (#351/#352/#353). Skip unassigned devices.
  local profile_macs = {}
  for mac, dev in sorted_devices(snapshot.devices) do
    local pid = dev.profileId
    if pid then
      if not profile_macs[pid] then profile_macs[pid] = {} end
      profile_macs[pid][#profile_macs[pid] + 1] = mac
    end
  end

  -- #385/#422: during failover (most-recent poll failed), an AllowAll
  -- profile's devices must NOT receive any drop rule — not the
  -- @blocked_macs entry, not the per-(MAC, host) eb_ drops, not the
  -- per-(MAC, blocklistId) bl_ drops, and not the block-page DNAT. The
  -- cached snapshot is intentionally discarded for these MACs (cf.
  -- LastKnownGood, where the cached snapshot keeps enforcing). Compute
  -- the suppress set once so every subsequent rule-emission step can
  -- filter cheaply. Failover trips on a single failed poll (#422) — there
  -- is no time-keyed cushion.
  local in_failover = opts and opts.poll_failed and true or false
  local allowall_macs = {}
  if in_failover then
    local allowall_pids = {}
    for pidStr, prof in pairs(snapshot.profiles or {}) do
      if prof.failureMode == "allow-all" then
        local pid = tonumber(pidStr)
        if pid then allowall_pids[pid] = true end
      end
    end
    for mac, dev in pairs(snapshot.devices or {}) do
      if dev.profileId and allowall_pids[dev.profileId] then
        allowall_macs[mac] = true
      end
    end
  end

  -- #1319: global composition state. The global BlockRules applies to every
  -- MAC the router knows. `managed_macs` is every device MAC (sorted) minus
  -- any suppressed by allow-all failover — global enforcement, like the
  -- per-MAC paths, is lifted for an allow-all profile's devices during an
  -- API outage. `has_global_allow` gates the @global_allow carve-out suffix;
  -- `gb_hosts`/`g_blocked`/`g_block_ip_only` gate the global drops.
  local ga_hosts_list = global_allow_hosts(snapshot)
  local has_global_allow = #ga_hosts_list > 0
  local gb_hosts = global_block_hosts(snapshot)
  local has_global_block = #gb_hosts > 0
  local g_blocked = global_blocked(snapshot)
  local g_block_ip_only = global_block_ip_only(snapshot)
  local g_block_reason = (global_rules(snapshot) and global_rules(snapshot).blockReason)
                         or "blocked"
  local managed_macs = {}
  for mac, _ in sorted_devices(snapshot.devices) do
    if not allowall_macs[mac] then managed_macs[#managed_macs + 1] = mac end
  end

  -- ga_suffix(family) → the fleet-wide @global_allow carve-out clause appended
  -- to EVERY hostname/MAC drop and DNAT (per-MAC or global). Returns "" when
  -- there is no global allow list, so output is byte-identical to pre-#1319
  -- for snapshots without a global section. NOT applied to blockIpOnly drops
  -- (an allowed host lands in resolved_<m> at DNS time, so it passes the
  -- IP-only test naturally — same composition as the per-MAC ea sets).
  local function ga_suffix(family)
    if not has_global_allow then return "" end
    if family == "ip6" then return " ip6 daddr != @" .. GLOBAL_ALLOW6 end
    return " ip daddr != @" .. GLOBAL_ALLOW4
  end

  -- Per-profile MAC sets. Emit in numeric-id order for stable output.
  for pidStr, _ in sorted_profiles(snapshot.profiles) do
    local pid = tonumber(pidStr)
    local macs = (pid and profile_macs[pid]) or {}
    ind(string.format("set profile%d_macs {", pid or 0))
    ind2("type ether_addr")
    if #macs > 0 then
      ind2("elements = { " .. table.concat(macs, ", ") .. " }")
    end
    ind("}")
    emit("")
  end

  -- Per-flow accounting sets — one per direction, each updated from its own
  -- chain (#897). Both chains hook `forward` at priority 1; the direction
  -- predicate (`iifname` / `oifname "br-lan"`) is what keeps tx from
  -- absorbing WAN→LAN packets (which would otherwise land in the tx set
  -- keyed on the upstream-gateway MAC + the LAN device's IP — pure noise)
  -- and what keeps rx from absorbing LAN→WAN packets.
  --
  -- tx (device→remote, `iifname br-lan`): `ether saddr (device_mac) .
  -- ip daddr (remote_ip)`. The L2 saddr at the forward hook is the real LAN
  -- device MAC for LAN→WAN traffic, so per-(mac, remote_ip) attribution works.
  --
  -- rx (remote→device, `oifname br-lan`): `ip daddr (lan_device_ip) .
  -- ip saddr (remote_ip)`. We *cannot* use `ether daddr` here: for a WAN→LAN
  -- routed packet the L2 daddr at the forward hook is still the router's
  -- WAN-interface MAC (the rewrite to the LAN device's MAC happens at egress
  -- via the neighbor cache, after the forward hook runs) — keying on it
  -- attributed every download to the router itself (#879). The agent
  -- resolves the LAN IP back to a MAC via the dnsmasq lease table.
  ind("set mac_ip_tracking {")
  ind2("type ether_addr . ipv4_addr")
  ind2("flags dynamic,timeout")
  ind2("timeout 6h")
  ind2("counter")
  ind("}")
  emit("")

  -- #897: fresh set name (was `ip_tracking_rx`, single-key) so a hot agent
  -- restart against an older nft ruleset can't misparse the old shape.
  ind("set ip_pair_tracking_rx {")
  ind2("type ipv4_addr . ipv4_addr")
  ind2("flags dynamic,timeout")
  ind2("timeout 6h")
  ind2("counter")
  ind("}")
  emit("")

  ind("chain wifihaven_account_tx {")
  ind2("type filter hook forward priority 1; policy accept;")
  ind2("iifname \"br-lan\" update @mac_ip_tracking { ether saddr . ip daddr } counter")
  ind("}")
  emit("")

  ind("chain wifihaven_account_rx {")
  ind2("type filter hook forward priority 1; policy accept;")
  ind2("oifname \"br-lan\" update @ip_pair_tracking_rx { ip daddr . ip saddr } counter")
  ind("}")
  emit("")

  -- blocked_macs: derived from per-device effective rules. The API server
  -- precomputes BlockRules.blocked from pause / time limit / schedule, so
  -- we just collect MACs whose effective rules.blocked == true.
  --
  -- #421: blocked MACs whose effective rules also have a non-empty
  -- extraAllowed list get pulled out of the @blocked_macs set into
  -- per-MAC rules carrying the `ip daddr != @ea_<m>_<a>` exception. The
  -- @blocked_macs set itself stays family-agnostic (it cannot carry an
  -- `ip daddr` predicate), so MACs with ea exceptions need per-family
  -- rules instead.
  local ea_by_mac_early = effective_extra_allowed_by_mac(snapshot)
  local blocked_macs_list   = {}   -- in @blocked_macs set (drop unconditionally)
  local blocked_ea_macs     = {}   -- blocked AND has extraAllowed → per-MAC rules
  -- #1319: when a global allow list is present, every blocked-MAC drop must
  -- carry the family-specific `!= @global_allow` carve-out, so even MACs with
  -- no per-MAC extraAllowed move to the per-family rule path (the family-
  -- agnostic @blocked_macs drop can't carry an `ip daddr` predicate).
  for mac, dev in sorted_devices(snapshot.devices) do
    local r = effective_rules(dev, snapshot.profiles)
    if r and r.blocked and not allowall_macs[mac] then
      if ea_by_mac_early[mac] or has_global_allow then
        blocked_ea_macs[#blocked_ea_macs + 1] = mac
      else
        blocked_macs_list[#blocked_macs_list + 1] = mac
      end
    end
  end

  ind("set blocked_macs {")
  ind2("type ether_addr")
  if #blocked_macs_list > 0 then
    ind2("elements = { " .. table.concat(blocked_macs_list, ", ") .. " }")
  end
  ind("}")
  emit("")

  -- #1319: fleet-wide global allow / block sets. Declared only when the
  -- global section actually carries entries, so a snapshot with no global
  -- policy renders byte-identically to pre-#1319. Populated at DNS resolve
  -- time by dnsmasq nftset= (rendered in M.dnsmasq); dynamic + 1h timeout so
  -- resolved entries age out, same as eb_/bl_.
  if has_global_allow then
    ind(string.format("set %s {", GLOBAL_ALLOW4))
    ind2("type ipv4_addr")
    ind2("flags dynamic,timeout")
    ind2("timeout 1h")
    ind("}")
    emit("")
    ind(string.format("set %s {", GLOBAL_ALLOW6))
    ind2("type ipv6_addr")
    ind2("flags dynamic,timeout")
    ind2("timeout 1h")
    ind("}")
    emit("")
  end
  if has_global_block then
    ind(string.format("set %s {", GLOBAL_BLOCK4))
    ind2("type ipv4_addr")
    ind2("flags dynamic,timeout")
    ind2("timeout 1h")
    ind("}")
    emit("")
    ind(string.format("set %s {", GLOBAL_BLOCK6))
    ind2("type ipv6_addr")
    ind2("flags dynamic,timeout")
    ind2("timeout 1h")
    ind("}")
    emit("")
  end

  -- #351/#392: per-host v4 + v6 sets for extraBlocked. Each set is populated
  -- at DNS resolve time by dnsmasq nftset= (rendered by dnsmasq() above):
  -- A records → eb_<host>, AAAA → eb6_<host>. Dynamic + 1h timeout so
  -- resolved entries age out and we don't leak shared-CDN IPs indefinitely.
  local eb_hosts = effective_extra_blocked_hosts(snapshot)
  for _, host in ipairs(eb_hosts) do
    ind(string.format("set %s {", eb_set_name(host)))
    ind2("type ipv4_addr")
    ind2("flags dynamic,timeout")
    ind2("timeout 1h")
    ind("}")
    emit("")
    ind(string.format("set %s {", eb6_set_name(host)))
    ind2("type ipv6_addr")
    ind2("flags dynamic,timeout")
    ind2("timeout 1h")
    ind("}")
    emit("")
  end

  -- #421: per-(MAC, host) extraAllowed sets. One v4 + one v6 set per
  -- (mac, host) pair that appears in some device's effective extraAllowed
  -- list. Sets are declared even for allowall-suppressed MACs (the dnsmasq
  -- populator still runs harmlessly; sets are cheap) — the drop/DNAT
  -- suffixes below are what allowall actually suppresses, by virtue of
  -- the eb_/bl_ rules themselves being suppressed for those MACs.
  local ea_by_mac = ea_by_mac_early
  do
    local ea_macs = {}
    for m in pairs(ea_by_mac) do ea_macs[#ea_macs + 1] = m end
    table.sort(ea_macs)
    for _, mac in ipairs(ea_macs) do
      for _, host in ipairs(ea_by_mac[mac]) do
        ind(string.format("set %s {", ea_set_name(mac, host)))
        ind2("type ipv4_addr")
        ind2("flags dynamic,timeout")
        ind2("timeout 1h")
        ind("}")
        emit("")
        ind(string.format("set %s {", ea6_set_name(mac, host)))
        ind2("type ipv6_addr")
        ind2("flags dynamic,timeout")
        ind2("timeout 1h")
        ind("}")
        emit("")
      end
    end
  end

  -- #421: helper — emit the `ip daddr != @ea_<m>_<a>` exception suffix
  -- to append to a (mac, family) drop / dnat predicate. Joins one clause
  -- per a ∈ extraAllowed(m); returns "" if the MAC has no extraAllowed.
  local function ea_suffix(mac, family)
    local hosts = ea_by_mac[mac]
    if not hosts or #hosts == 0 then return "" end
    local parts = {}
    for _, host in ipairs(hosts) do
      if family == "ip6" then
        parts[#parts + 1] = " ip6 daddr != @" .. ea6_set_name(mac, host)
      else
        parts[#parts + 1] = " ip daddr != @" .. ea_set_name(mac, host)
      end
    end
    return table.concat(parts)
  end

  -- #351: per-(MAC, host) drop pairs. We build the cross-product from each
  -- device's *effective* extraBlocked list (device override > profile rules),
  -- so a profile's extraBlocked applies only to MACs in that profile and a
  -- device override applies only to that one MAC.
  local eb_pairs = {}
  for mac, dev in sorted_devices(snapshot.devices) do
    if not allowall_macs[mac] then
      local r = effective_rules(dev, snapshot.profiles)
      if r and type(r.extraBlocked) == "table" then
        for _, host in ipairs(r.extraBlocked) do
          eb_pairs[#eb_pairs + 1] = { mac = mac, host = host }
        end
      end
    end
  end

  -- #352: per-blocklist ipsets. Declared for every id in snapshot.blocklists
  -- regardless of whether any device references the id, so the dnsmasq
  -- ipset= callbacks have a set to populate. Dynamic + 1h timeout.
  local bl_ids = sorted_keys(snapshot.blocklists or {})
  for _, id in ipairs(bl_ids) do
    ind(string.format("set %s {", bl_set_name(id)))
    ind2("type ipv4_addr")
    ind2("flags dynamic,timeout")
    ind2("timeout 1h")
    ind("}")
    emit("")
    ind(string.format("set %s {", bl6_set_name(id)))
    ind2("type ipv6_addr")
    ind2("flags dynamic,timeout")
    ind2("timeout 1h")
    ind("}")
    emit("")
  end

  -- #353: per-MAC blockIpOnly. Declare resolved_<mac> / resolved6_<mac>
  -- sets and collect the MACs whose forward-chain drop + DNAT we will emit
  -- below. Sets are declared even for allowall-suppressed MACs (the dnsmasq
  -- populator still runs harmlessly; sets are cheap), but the drop/DNAT
  -- rules are suppressed under allow-all failover, mirroring eb_/bl_.
  -- #1319: global.blockIpOnly is a network-wide strict-IP floor — it unions
  -- with the per-MAC blockIpOnly set so EVERY device MAC gets a resolved_<m>
  -- drop. (G.blockIpOnly ∨ R.blockIpOnly per the §5.2 predicate.)
  local bio_seen = {}
  for _, mac in ipairs(block_ip_only_macs(snapshot)) do bio_seen[mac] = true end
  if g_block_ip_only then
    for mac, _ in pairs(snapshot.devices or {}) do bio_seen[mac] = true end
  end
  local bio_all = {}
  for mac in pairs(bio_seen) do bio_all[#bio_all + 1] = mac end
  table.sort(bio_all)
  local bio_macs = {}
  for _, mac in ipairs(bio_all) do
    if not allowall_macs[mac] then
      bio_macs[#bio_macs + 1] = mac
    end
  end
  for _, mac in ipairs(bio_all) do
    ind(string.format("set %s {", resolved_set_name(mac)))
    ind2("type ipv4_addr")
    ind2("flags dynamic,timeout")
    ind2("timeout 5m")
    ind("}")
    emit("")
    ind(string.format("set %s {", resolved6_set_name(mac)))
    ind2("type ipv6_addr")
    ind2("flags dynamic,timeout")
    ind2("timeout 5m")
    ind("}")
    emit("")
  end

  -- #352: per-(MAC, blocklistId) drop pairs. For each device, for each
  -- blocklistId in its effective rules, if the id is in snapshot.blocklists,
  -- emit a drop rule. Ids absent from snapshot.blocklists are silently skipped.
  local bl_pairs = {}
  for mac, dev in sorted_devices(snapshot.devices) do
    if not allowall_macs[mac] then
      local r = effective_rules(dev, snapshot.profiles)
      if r and type(r.blocklistIds) == "table" then
        for _, id in ipairs(r.blocklistIds) do
          if (snapshot.blocklists or {})[id] then
            bl_pairs[#bl_pairs + 1] = { mac = mac, id = id }
          end
        end
      end
    end
  end

  -- #1122/#1126: each drop rule carries
  --   `log prefix "wh_drop:<mac>:<reason> " counter drop comment "wh_drop:<mac>:<reason>"`.
  -- The `log prefix` form writes to the kernel ring buffer (the LOG backend),
  -- so the wifihaven-nflog-tail sidecar can read the dropped-packet records
  -- straight off `logread -f` on stock OpenWRT — no NFLOG netlink consumer
  -- (ulogd2) required. The agent then synthesizes connection_attempt events
  -- with allowed=false + reason parsed from the prefix, closing the visibility
  -- gap for forward-chain drops (which never get conntrack-confirmed and so
  -- never reach the conntrack -E NEW watcher). The trailing space separates the
  -- prefix token from the kernel's `IN=…` field block. `counter` stays for ops
  -- debugging via `nft list ruleset`; the `comment` keeps the full
  -- `wh_drop:<mac>:<reason>` legible there (and survives even if a future
  -- kernel truncates a very long prefix).
  --
  -- #1126: this replaces the original #1122 NFLOG netlink form
  -- (`log ... group <N>`). NFLOG had no stock userspace consumer, so the
  -- production reader could never be wired without pulling in ulogd2; logread is
  -- already understood by the agent. The load-critical kernel backend is now
  -- the syslog logger (kmod-nf-log / kmod-nf-log6) rather than the netlink
  -- logger (kmod-nfnetlink-log); nft_log_dep_spec.sh enforces that coherence.
  --
  -- We also build a per-MAC blockReason lookup so the prefix/comment on each
  -- whole-MAC drop carries the MacBlockReason that the API server resolved
  -- (Paused / Schedule / TimeLimit / Manual / Unmanaged). See
  -- PolicySnapshotMacDropAttributionSpec for the wire-string contract.
  local function drop_suffix(mac, reason)
    return string.format(
      " log prefix \"wh_drop:%s:%s \" counter drop comment \"wh_drop:%s:%s\"",
      mac, reason, mac, reason)
  end
  local blocked_reason_by_mac = {}
  for mac, dev in pairs(snapshot.devices or {}) do
    local r = effective_rules(dev, snapshot.profiles)
    if r and r.blocked and not allowall_macs[mac] then
      blocked_reason_by_mac[mac] = r.blockReason or "blocked"
    end
  end

  ind("chain wifihaven_block {")
  ind2("type filter hook forward priority 0; policy accept;")
  -- #1122: the family-agnostic @blocked_macs drop is replaced by per-MAC
  -- rules so each can carry its own `wh_drop:<mac>:<reason>` comment. The
  -- set is still declared (used by the DNAT chain below) but no longer
  -- driven by a single drop rule. MACs without extraAllowed get one
  -- family-agnostic per-MAC drop; MACs with extraAllowed get per-family
  -- rules carrying the ea exception (same shape as pre-#1122).
  for _, mac in ipairs(blocked_macs_list) do
    ind2(string.format("ether saddr %s%s", mac,
                       drop_suffix(mac, blocked_reason_by_mac[mac] or "blocked")))
  end
  -- #1319: each per-MAC blocked rule also carries the `!= @global_allow`
  -- carve-out (ga_suffix), so a globally-allowed host stays reachable for a
  -- blocked MAC. ea_suffix (per-MAC allow) comes first, then ga_suffix.
  for _, mac in ipairs(blocked_ea_macs) do
    local reason = blocked_reason_by_mac[mac] or "blocked"
    ind2(string.format("ether saddr %s%s%s%s", mac, ea_suffix(mac, "ip"),
                       ga_suffix("ip"), drop_suffix(mac, reason)))
    ind2(string.format("ether saddr %s%s%s%s", mac, ea_suffix(mac, "ip6"),
                       ga_suffix("ip6"), drop_suffix(mac, reason)))
  end
  -- v4 drops first, then v6 (#392). One ipset directive populates both sets
  -- at DNS time; here we gate on whichever family the destination matched.
  -- #421: each eb_/bl_ drop carries `ip daddr != @ea_<m>_<a>` exception
  -- clauses (one per a ∈ extraAllowed(m)) so an allowed host's resolved
  -- IPs suppress the drop. ea_suffix("") for MACs with no extraAllowed,
  -- so behaviour for those is unchanged.
  -- #1319: ga_suffix appended after the per-MAC ea exception, so an allowed
  -- host (per-MAC or global) suppresses the per-host / per-category drop.
  for _, p in ipairs(eb_pairs) do
    ind2(string.format("ether saddr %s ip daddr @%s%s%s%s",
                       p.mac, eb_set_name(p.host), ea_suffix(p.mac, "ip"),
                       ga_suffix("ip"), drop_suffix(p.mac, "host:" .. p.host)))
  end
  for _, p in ipairs(eb_pairs) do
    ind2(string.format("ether saddr %s ip6 daddr @%s%s%s%s",
                       p.mac, eb6_set_name(p.host), ea_suffix(p.mac, "ip6"),
                       ga_suffix("ip6"), drop_suffix(p.mac, "host:" .. p.host)))
  end
  for _, p in ipairs(bl_pairs) do
    ind2(string.format("ether saddr %s ip daddr @%s%s%s%s",
                       p.mac, bl_set_name(p.id), ea_suffix(p.mac, "ip"),
                       ga_suffix("ip"), drop_suffix(p.mac, "category:" .. tostring(p.id))))
  end
  for _, p in ipairs(bl_pairs) do
    ind2(string.format("ether saddr %s ip6 daddr @%s%s%s%s",
                       p.mac, bl6_set_name(p.id), ea_suffix(p.mac, "ip6"),
                       ga_suffix("ip6"), drop_suffix(p.mac, "category:" .. tostring(p.id))))
  end
  -- #353: blockIpOnly drop. Predicate `ip daddr != @resolved_<mac>` matches
  -- any v4 destination the device did not DNS-resolve via our resolver in
  -- the last 5 minutes — i.e. DoH / DoT / hard-coded IPs / stale cache
  -- entries past the set timeout.
  for _, mac in ipairs(bio_macs) do
    ind2(string.format("ether saddr %s ip daddr != @%s%s",
                       mac, resolved_set_name(mac), drop_suffix(mac, "ip_only")))
  end
  for _, mac in ipairs(bio_macs) do
    ind2(string.format("ether saddr %s ip6 daddr != @%s%s",
                       mac, resolved6_set_name(mac), drop_suffix(mac, "ip_only")))
  end
  -- #1319: global block (hosts ∪ categories) → drop for every managed MAC on
  -- @global_block, carved out ONLY by @global_allow (a per-MAC extraAllowed
  -- does NOT save — "a profile may not un-block a global block"). One v4 +
  -- one v6 rule per managed MAC.
  if has_global_block then
    for _, mac in ipairs(managed_macs) do
      ind2(string.format("ether saddr %s ip daddr @%s%s%s",
                         mac, GLOBAL_BLOCK4, ga_suffix("ip"),
                         drop_suffix(mac, "global_block")))
    end
    for _, mac in ipairs(managed_macs) do
      ind2(string.format("ether saddr %s ip6 daddr @%s%s%s",
                         mac, GLOBAL_BLOCK6, ga_suffix("ip6"),
                         drop_suffix(mac, "global_block")))
    end
  end
  -- #1319: global lockdown (global.blocked) → whole-network kill switch. Drop
  -- all forwarded traffic for every managed MAC except @global_allow. Keyed on
  -- ether saddr so return traffic for allowed flows is unaffected. Emitted for
  -- every managed MAC (even already-blocked ones) because this carry only the
  -- ga carve-out, not the per-MAC ea exception that the per-MAC blocked rule
  -- carries.
  if g_blocked then
    if has_global_allow then
      -- Per-family so each can carry its family-specific @global_allow carve-out.
      for _, mac in ipairs(managed_macs) do
        ind2(string.format("ether saddr %s%s%s",
                           mac, ga_suffix("ip"), drop_suffix(mac, g_block_reason)))
      end
      for _, mac in ipairs(managed_macs) do
        ind2(string.format("ether saddr %s%s%s",
                           mac, ga_suffix("ip6"), drop_suffix(mac, g_block_reason)))
      end
    else
      -- No global allow → one family-agnostic unconditional drop per MAC.
      for _, mac in ipairs(managed_macs) do
        ind2(string.format("ether saddr %s%s", mac, drop_suffix(mac, g_block_reason)))
      end
    end
  end
  ind("}")
  emit("")

  -- Block-page DNAT chain (#303 + #351 + #352 + #353): redirect HTTP/80 to
  -- the local uhttpd block page so users see *why* a connection failed.
  -- Four triggers: MAC-wide block, per-(MAC, host) extraBlocked, per-(MAC,
  -- blocklistId), and per-MAC blockIpOnly (un-resolved daddr).
  if #blocked_macs_list > 0 or #blocked_ea_macs > 0 or #eb_pairs > 0 or #bl_pairs > 0 or #bio_macs > 0
      or has_global_block or g_blocked then
    ind("chain wifihaven_block_nat {")
    ind2("type nat hook prerouting priority dstnat; policy accept;")
    -- #383: every block-page DNAT predicate emits a pair of rules — TCP/80 →
    -- the HTTP uhttpd listener AND TCP/443 → the parallel TLS uhttpd listener
    -- (self-signed cert, CN block.wifihaven.local). HTTPS used to time out
    -- silently; now the user sees a cert warning and, after clicking through,
    -- the same block page. The cert warning IS the design — we do NOT install
    -- a CA on managed devices. The pair is emitted via the dnat4 / dnat6
    -- helpers below so the predicate construction is single-source-of-truth.
    local function dnat4(predicate)
      ind2(predicate .. " tcp dport 80 dnat ip to 127.0.0.1:8081")
      ind2(predicate .. " tcp dport 443 dnat ip to 127.0.0.1:8443")
    end
    local function dnat6(predicate)
      ind2(predicate .. " tcp dport 80 dnat ip6 to ::1:8081")
      ind2(predicate .. " tcp dport 443 dnat ip6 to ::1:8443")
    end
    if #blocked_macs_list > 0 then
      dnat4("ether saddr @blocked_macs")
    end
    -- #421: blocked + extraAllowed → per-MAC v4 DNAT with ea exception.
    -- #1319: ga_suffix adds the @global_allow carve-out so an allowed host is
    -- never redirected to the block page.
    for _, mac in ipairs(blocked_ea_macs) do
      dnat4(string.format("ether saddr %s%s%s",
        mac, ea_suffix(mac, "ip"), ga_suffix("ip")))
    end
    for _, p in ipairs(eb_pairs) do
      dnat4(string.format("ether saddr %s ip daddr @%s%s%s",
        p.mac, eb_set_name(p.host), ea_suffix(p.mac, "ip"), ga_suffix("ip")))
    end
    for _, p in ipairs(bl_pairs) do
      dnat4(string.format("ether saddr %s ip daddr @%s%s%s",
        p.mac, bl_set_name(p.id), ea_suffix(p.mac, "ip"), ga_suffix("ip")))
    end
    -- #1319: global block / lockdown v4 DNAT → block page. Carved out only by
    -- @global_allow (per-MAC ea does not save a global block).
    if has_global_block then
      for _, mac in ipairs(managed_macs) do
        dnat4(string.format("ether saddr %s ip daddr @%s%s",
          mac, GLOBAL_BLOCK4, ga_suffix("ip")))
      end
    end
    if g_blocked then
      for _, mac in ipairs(managed_macs) do
        dnat4(string.format("ether saddr %s%s", mac, ga_suffix("ip")))
      end
    end
    -- #353: blockIpOnly v4 DNAT. Same predicate as the forward-chain drop;
    -- the DNAT fires *before* the drop (prerouting < forward), so the
    -- device sees the block page instead of a silent timeout.
    for _, mac in ipairs(bio_macs) do
      dnat4(string.format("ether saddr %s ip daddr != @%s",
        mac, resolved_set_name(mac)))
    end
    -- #411: v6 siblings. uhttpd also binds [::1]:8081 + [::1]:8443.
    -- `ip6 daddr != ::1` guards against self-DNAT recursion if the block
    -- page itself ever issues an outbound v6 request.
    if #blocked_macs_list > 0 then
      dnat6("ether saddr @blocked_macs ip6 daddr != ::1")
    end
    -- #421: blocked + extraAllowed → per-MAC v6 DNAT with ea6 exception.
    -- `ip6 daddr != ::1` guards against self-DNAT (same as the @blocked_macs
    -- v6 line above).
    for _, mac in ipairs(blocked_ea_macs) do
      dnat6(string.format("ether saddr %s ip6 daddr != ::1%s%s",
        mac, ea_suffix(mac, "ip6"), ga_suffix("ip6")))
    end
    for _, p in ipairs(eb_pairs) do
      dnat6(string.format("ether saddr %s ip6 daddr @%s%s%s",
        p.mac, eb6_set_name(p.host), ea_suffix(p.mac, "ip6"), ga_suffix("ip6")))
    end
    for _, p in ipairs(bl_pairs) do
      dnat6(string.format("ether saddr %s ip6 daddr @%s%s%s",
        p.mac, bl6_set_name(p.id), ea_suffix(p.mac, "ip6"), ga_suffix("ip6")))
    end
    -- #1319: global block / lockdown v6 DNAT → block page. The @global_block6
    -- match can't include ::1 (the listener never resolves into a block set),
    -- so the block-host form needs no self-DNAT guard; the lockdown form is
    -- unconditional, so it carries `ip6 daddr != ::1`.
    if has_global_block then
      for _, mac in ipairs(managed_macs) do
        dnat6(string.format("ether saddr %s ip6 daddr @%s%s",
          mac, GLOBAL_BLOCK6, ga_suffix("ip6")))
      end
    end
    if g_blocked then
      for _, mac in ipairs(managed_macs) do
        dnat6(string.format("ether saddr %s ip6 daddr != ::1%s",
          mac, ga_suffix("ip6")))
      end
    end
    -- #353 + #411: blockIpOnly v6 DNAT. `ip6 daddr != ::1` guards against
    -- self-redirect (the uhttpd listener at ::1 is "not in resolved set"
    -- by definition and we must not DNAT its own responses).
    for _, mac in ipairs(bio_macs) do
      dnat6(string.format("ether saddr %s ip6 daddr != ::1 ip6 daddr != @%s",
        mac, resolved6_set_name(mac)))
    end
    ind("}")
    emit("")
  end

  -- #385: API-unreachable failover. failureMode is per-profile (carried on
  -- ProfilePolicy) with three variants — see the opts.poll_failed
  -- doc at the top of M.nft for the per-mode behaviour. This block only
  -- handles BlockAll: collect MACs of devices whose profile's failureMode
  -- is "block-all" and emit a drop chain for them. AllowAll is enforced
  -- by SUPPRESSION earlier in this function (allowall_macs gates the
  -- @blocked_macs / eb_pairs / bl_pairs lists, so AllowAll devices reach
  -- this point with zero drop rules attributed to them — exactly what
  -- "pass forwarded traffic with no enforcement" means). LastKnownGood
  -- is the no-op default: the cached snapshot's rules keep enforcing
  -- exactly as-is.
  if in_failover then
    local blockall_pids = {}
    for pidStr, prof in pairs(snapshot.profiles or {}) do
      if prof.failureMode == "block-all" then
        local pid = tonumber(pidStr)
        if pid then blockall_pids[pid] = true end
      end
    end
    local failover_macs = {}
    for mac, dev in sorted_devices(snapshot.devices) do
      if dev.profileId and blockall_pids[dev.profileId] then
        failover_macs[#failover_macs + 1] = mac
      end
    end
    if #failover_macs > 0 then
      ind("set failover_drop {")
      ind2("type ether_addr")
      ind2("elements = { " .. table.concat(failover_macs, ", ") .. " }")
      ind("}")
      emit("")
      ind("chain wifihaven_failover {")
      ind2("type filter hook forward priority -1; policy accept;")
      ind2("ether saddr @failover_drop drop")
      ind("}")
      emit("")
    end
  end

  emit("}")
  emit("")

  return table.concat(out, "\n")
end

-- ---------------------------------------------------------------------------
-- render.update_shared(snapshot, nft_sets, blocked_macs, blocked_reason,
--                      eb_hosts_by_mac, ea_hosts_by_mac, bl_hosts_by_mac)
-- ---------------------------------------------------------------------------
-- Rebuilds blocked_macs / blocked_reason in place from each device's
-- effective BlockRules. nft_sets is left intact — population is driven by
-- dnsmasq --ipset= callbacks at resolution time (and by dns-tail per #259).
--
-- Also rebuilds eb_hosts_by_mac, ea_hosts_by_mac, and bl_hosts_by_mac when
-- provided:
--   eb_hosts_by_mac: { mac -> { hostname -> true } }
--     — hostnames whose nft eb_ drop rules fire for this MAC (effective
--       extraBlocked only).
--   ea_hosts_by_mac: { mac -> { hostname -> true } }
--     — hostnames in the MAC's effective extraAllowed list; a hit suppresses
--       the eb_/bl_ block classification.
--   bl_hosts_by_mac: { mac -> { hostname -> blocklist_id } }
--     — hostnames whose nft bl_ drop rules fire for this MAC, tagged with the
--       blocklist id that matched (used to surface a category-specific reason
--       on the block page and in connection_event.reason — #594). If multiple
--       blocklists contain the same host, the first by sorted id wins.
-- All three tables are cleared and rebuilt on every call. Callers that do not
-- need per-host block classification may omit them (pass nil).
function M.update_shared(snapshot, nft_sets, blocked_macs, blocked_reason,
                         eb_hosts_by_mac, ea_hosts_by_mac, bl_hosts_by_mac)
  if blocked_macs then
    for k in pairs(blocked_macs) do blocked_macs[k] = nil end
  end
  if blocked_reason then
    for k in pairs(blocked_reason) do blocked_reason[k] = nil end
  end
  if eb_hosts_by_mac then
    for k in pairs(eb_hosts_by_mac) do eb_hosts_by_mac[k] = nil end
  end
  if ea_hosts_by_mac then
    for k in pairs(ea_hosts_by_mac) do ea_hosts_by_mac[k] = nil end
  end
  if bl_hosts_by_mac then
    for k in pairs(bl_hosts_by_mac) do bl_hosts_by_mac[k] = nil end
  end

  -- Build a blocklist-id → [hosts] lookup so we can expand blocklistIds below.
  local bl_hosts = (snapshot and snapshot._blocklist_hosts) or {}

  for mac, dev in pairs(snapshot.devices or {}) do
    local r = effective_rules(dev, snapshot.profiles)
    if r then
      if r.blocked then
        if blocked_macs   then blocked_macs[mac]   = true end
        if blocked_reason then blocked_reason[mac] = r.blockReason or "blocked" end
      end

      -- Per-host extraBlocked: collect all hostnames that the nft eb_ rules
      -- would drop for this MAC (regardless of whether the MAC is also
      -- blanket-blocked).
      if eb_hosts_by_mac and type(r.extraBlocked) == "table" and #r.extraBlocked > 0 then
        if not eb_hosts_by_mac[mac] then eb_hosts_by_mac[mac] = {} end
        for _, host in ipairs(r.extraBlocked) do
          eb_hosts_by_mac[mac][host] = true
        end
      end

      -- Per-blocklist blocklistIds: expand each id to its constituent hosts
      -- using the cached blocklist data attached to the snapshot.
      -- #594: tag each (mac, host) with the blocklist id that matched so the
      -- block page and connection_event can name the category. Iterate ids in
      -- sorted order so the chosen id is deterministic when a host appears in
      -- multiple lists. extraBlocked (eb_) takes precedence over category
      -- (bl_) when the same host is in both — populate bl_hosts_by_mac only
      -- if the host isn't already in eb_hosts_by_mac[mac].
      if bl_hosts_by_mac and type(r.blocklistIds) == "table" and #r.blocklistIds > 0 then
        local ids = {}
        for _, id in ipairs(r.blocklistIds) do ids[#ids + 1] = id end
        table.sort(ids)
        for _, id in ipairs(ids) do
          local hosts = bl_hosts[id]
          if type(hosts) == "table" then
            for _, host in ipairs(hosts) do
              local eb_for_mac = eb_hosts_by_mac and eb_hosts_by_mac[mac]
              local already_eb = eb_for_mac and eb_for_mac[host]
              if not bl_hosts_by_mac[mac] then bl_hosts_by_mac[mac] = {} end
              if not already_eb and not bl_hosts_by_mac[mac][host] then
                bl_hosts_by_mac[mac][host] = id
              end
            end
          end
        end
      end

      -- extraAllowed: collect all hostnames that suppress eb_/bl_ drops for
      -- this MAC. A dst_ip in any ea_ set for this MAC escapes classification
      -- as blocked.
      if ea_hosts_by_mac and type(r.extraAllowed) == "table" and #r.extraAllowed > 0 then
        if not ea_hosts_by_mac[mac] then ea_hosts_by_mac[mac] = {} end
        for _, host in ipairs(r.extraAllowed) do
          ea_hosts_by_mac[mac][host] = true
        end
      end
    end
  end
end

-- (#1618) write_blocked_reasons / write_blocked_hosts were removed: the
-- block-page handler stopped reading their on-disk output in #1615/#1617
-- (the SPA derives the canonical reason from GET /api/blocked). The in-memory
-- `blocked_reason` / `eb_hosts_by_mac` / `ea_hosts_by_mac` / `bl_hosts_by_mac`
-- maps populated by `update_shared` above stay — they feed conntrack.lua's
-- per-MAC connection_event labels on POST /api/router/events, which is a
-- separate path.

return M
