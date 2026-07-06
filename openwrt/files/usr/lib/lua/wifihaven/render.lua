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

-- Lazy require the baked-in encrypted-DNS curated lists / render fragments
-- (#1911). Compatible with both the production on-device path
-- (wifihaven.encrypted_dns) and the busted test path (encrypted_dns). Called at
-- most once per process; result is cached in the upvalue.
local _encrypted_dns_module
local function get_encrypted_dns()
  if not _encrypted_dns_module then
    local ok, m = pcall(require, "wifihaven.encrypted_dns")
    _encrypted_dns_module = ok and m or require("encrypted_dns")
  end
  return _encrypted_dns_module
end

-- #1911: the additive top-level `snapshot.blockEncryptedDns` boolean. When true,
-- the agent enforces the network-wide "block encrypted DNS & relays" toggle:
-- NODATA for the curated relay/DoH hostnames (dnsmasq) + a connection-layer
-- drop chain for DoT/853 and DNS:53-to-resolver-IPs (nft). Absent/false → no-op
-- (old snapshots and old agents both behave as today). Read directly off the
-- snapshot — like every other policy decision, it is pre-computed server-side
-- and the agent is a dumb applier.
local function block_encrypted_dns(snapshot)
  return (snapshot and snapshot.blockEncryptedDns) and true or false
end
M.block_encrypted_dns = block_encrypted_dns

-- Directory where per-blocklist dnsmasq conf shards live (#1782). A SUBDIR of
-- the dnsmasq confdir (/tmp/dnsmasq.d, itself tmpfs = RAM on OpenWRT), NOT bare
-- /tmp — #1812. OpenWRT runs dnsmasq inside a procd ujail that bind-mounts ONLY
-- the confdir (and a handful of known files): see /etc/init.d/dnsmasq's
-- `procd_add_jail_mount $dnsmasqconfdir …`. A `conf-file=` directive in
-- wifihaven.conf that points at a shard OUTSIDE the confdir is unreadable inside
-- the jail — dnsmasq aborts with "cannot read …", procd crash-loops it and
-- gives up, and :53 goes dark (connection refused, no DHCP leases). Keeping the
-- shards under the confdir puts them inside the jail mount. A SUBDIR (not the
-- confdir root) so dnsmasq's `conf-dir=/tmp/dnsmasq.d` does NOT auto-load them
-- (it is non-recursive) — they load only via the explicit, shard-existence-gated
-- conf-file= directives. Overridable for tests via M.SHARD_DIR assignment before
-- calling M.dnsmasq(). Production: always this dir (matches BLOCKLIST_SHARD_DIR
-- in wifihaven-agent, which derives from render.SHARD_DIR).
M.SHARD_DIR = "/tmp/dnsmasq.d/blocklists"

-- Canonical path of a per-blocklist dnsmasq conf shard (#1792). Single source
-- of truth — render.dnsmasq, policy.apply's shard-existence check, and
-- blocklists.render_shards all call this so the prefix/extension can never
-- drift between writer and reader. `dir` defaults to M.SHARD_DIR; pass an
-- explicit dir for callers that already take shard_dir as a parameter
-- (blocklists.render_shards / .gc_shards) so the test-injectable directory
-- still flows through.
function M.shard_path(id, dir)
  return (dir or M.SHARD_DIR) .. "/wifihaven-blocklist-" .. id .. ".conf"
end

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
M.eb_set_name  = eb_set_name
M.eb6_set_name = eb6_set_name

-- nft set names for a blocklist id (#352, #392). Replaces dots, colons, and
-- hyphens with underscores (nftables set names allow only [a-zA-Z0-9_]).
local function bl_sanitize(id)
  return (id:gsub("[%.%:%-%s]", "_"))
end
M.bl_sanitize = bl_sanitize
local function bl_set_name(id)
  return "bl_" .. bl_sanitize(id)
end
local function bl6_set_name(id)
  return "bl6_" .. bl_sanitize(id)
end
M.bl_set_name  = bl_set_name
M.bl6_set_name = bl6_set_name

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
-- global.extraBlocked only.
--
-- Post-#1782: global.blocklistIds are no longer expanded here. When an id
-- appears in global.blocklistIds, blocklists.render_shards appends
-- `4#inet#wifihaven#global_block,6#inet#wifihaven#global_block6` to each host
-- line in that id's shard file, so dnsmasq populates @global_block at DNS
-- resolve time via the shard's nftset= directives — exactly as the per-MAC
-- bl_ ipsets are populated. The @global_block nft set declaration is still
-- emitted by render.nft when global.blocklistIds is non-empty (has_global_block
-- guards on both extraBlocked and blocklistIds), so the set exists for the
-- shard's callbacks to land in.
-- Empty when there is nothing globally blocked by host.
local function global_block_hosts(snapshot)
  local g = global_rules(snapshot)
  if not g then return {} end
  local seen = {}
  if type(g.extraBlocked) == "table" then
    for _, h in ipairs(g.extraBlocked) do seen[h] = true end
  end
  -- Note: g.blocklistIds expansion is intentionally omitted here (#1782).
  -- The @global_block ipset for blocklist-id members is populated at DNS
  -- resolve time via per-shard nftset= directives (see blocklists.render_shards
  -- global_blocklist_ids parameter), not by expanding hosts in-memory.
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
function M.dnsmasq(snapshot, opts)
  opts = opts or {}
  -- #1792: blocklists.render_shards silently skips an id whose cache file is
  -- missing (fetch never completed, transient HTTP error, cap_hit). Emitting
  -- a conf-file= line for that id makes dnsmasq abort at startup with
  -- "cannot read /tmp/wifihaven-blocklist-<id>.conf" and :53 returns
  -- "connection refused". Callers that have observed which shards actually
  -- landed on disk pass opts.bl_shard_exists(id) to gate the emission;
  -- absent the option (e.g. legacy callers, tests) every id is referenced.
  local bl_shard_exists = opts.bl_shard_exists or function() return true end
  local out = {}
  local function emit(s) out[#out + 1] = s end

  emit("# wifihaven — generated by render.lua, do not edit")
  emit("")

  -- #1911: network-wide "block encrypted DNS & relays" NODATA half. When
  -- snapshot.blockEncryptedDns is true, answer the curated relay/DoH hostnames
  -- locally-empty (NODATA) so iOS cleanly disables iCloud Private Relay and DoH
  -- clients fall back to the LAN resolver. This is the one sanctioned, narrow
  -- exception to Architectural Truth #1 ("DNS always resolves") — scoped to
  -- bypass-disable signaling, NOT enforcement (see encrypted_dns.lua and
  -- docs/architecture.md). The connection-layer half lives in M.nft.
  if block_encrypted_dns(snapshot) then
    emit("# block encrypted DNS & relays (#1911): NODATA for curated relay/DoH hosts")
    for _, line in ipairs(get_encrypted_dns().dnsmasq_lines()) do
      emit(line)
    end
    emit("")
  end

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
  -- #1782: per-blocklist shard files are included via conf-file= directives so
  -- dnsmasq loads the nftset= host entries from /tmp/wifihaven-blocklist-<id>.conf
  -- without the agent ever holding the full host list in Lua memory. The shard
  -- files are written by blocklists.render_shards; each line contains:
  --   nftset=/<host>/4#inet#wifihaven#bl_<id>,6#inet#wifihaven#bl6_<id>
  -- Emit one conf-file= line per id in sorted order.
  local bl_ids = sorted_keys(snapshot.blocklists or {})
  local bl_ids_with_shards = {}
  for _, id in ipairs(bl_ids) do
    if bl_shard_exists(id) then
      bl_ids_with_shards[#bl_ids_with_shards + 1] = id
    end
  end
  if #bl_ids_with_shards > 0 then
    emit("# per-blocklist shard files written by blocklists.render_shards (#1782,#1783)")
    emit("# each shard contains nftset= directives for that list's member hosts")
    -- #1792: only reference shards that actually exist on disk; a dangling
    -- conf-file= ref aborts dnsmasq startup with "cannot read ...".
    for _, id in ipairs(bl_ids_with_shards) do
      emit("conf-file=" .. M.shard_path(id))
    end
    emit("")
  end

  local ga_hosts = global_allow_hosts(snapshot)
  local gb_hosts = global_block_hosts(snapshot)

  -- host_order preserves first-seen ordering (deterministic by source);
  -- host_specs accumulates the per-host spec list. Source order:
  --   1. ea_  (per-MAC, sorted by mac then host)
  --   2. eb_  (per-host, in effective_extra_blocked_hosts order)
  --   3. ga_  (global allow, sorted)
  --   4. gb_  (global block, sorted)
  -- Note: bl_ specs are NO LONGER emitted here — they live in per-blocklist
  -- shard files included via conf-file= above (#1782). A host that appears in
  -- both a blocklist AND in ea_/eb_/global sets will have its bl_ spec in the
  -- shard file and its other specs in this merged directive — dnsmasq applies
  -- all matching nftset= directives across config files, so each set is
  -- populated independently. See PR #1782 body for the rationale.
  -- Within each source the v4 spec precedes v6. add_spec de-duplicates
  -- identical specs per host (e.g. the same host listed twice) so the merged
  -- directive never carries a redundant comma entry.
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
  -- bl_ specs removed from here (#1782): they now live in shard files.
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
-- bl_<id>/bl6_<id> sets are populated at DNS resolve time by dnsmasq nftset=
-- callbacks. A device that re-queries a member's CNAME target directly lands on
-- a CDN-anycast IP dnsmasq never added, so the category drop misses (silent
-- filter bypass). dns-tail closes the gap the same way it does for eb_ (#515):
-- it resolves each answered name through the #1344 CNAME-alias map and, when
-- the recovered brand is a blocklist member, adds the IP to that member's bl_
-- set. dns-tail can see which bl_ sets EXIST but not their MEMBERSHIP, so this
-- exports it.
--
-- Post-#1782 (LEGACY / TEST PATH): the agent no longer calls this function to
-- write paths.bl_member_index — it uses blocklists.render_member_index instead,
-- which streams from the on-disk cache files without building a Lua table.
-- This in-memory path is retained for unit tests (render_spec.lua) and any
-- caller that already has _blocklist_hosts in memory. It reads from
-- snapshot._blocklist_hosts; when that table is absent the output is empty.
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
  -- #1782: has_global_block is true when global.extraBlocked is non-empty OR
  -- global.blocklistIds is non-empty. In the latter case global_block_hosts()
  -- returns {} (blocklist members are now populated via shard nftset= callbacks,
  -- not by expanding in-memory), but the @global_block nft set still needs to
  -- be declared so the shard's dnsmasq callbacks have a set to land in.
  local g_for_block = global_rules(snapshot)
  local has_global_block_from_ids = g_for_block and type(g_for_block.blocklistIds) == "table"
    and #g_for_block.blocklistIds > 0
  local has_global_block = #gb_hosts > 0 or (has_global_block_from_ids and true or false)
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

  -- #1796: IPv6 byte-accounting. The v4 sets above only match `ip daddr`, so
  -- every IPv6 destination flow went uncounted and was invisible in
  -- per-device traffic / recent-apexes (a v6-preferring client browsing a
  -- dual-stack host showed nothing). These mirror the v4 sets with an
  -- ipv6_addr shape; usage.lua reads both families through the same parsers
  -- and `nft -j` emits compressed v6 that matches the dns-cache key, so
  -- host attribution works without extra canonicalization.
  ind("set mac_ip6_tracking {")
  ind2("type ether_addr . ipv6_addr")
  ind2("flags dynamic,timeout")
  ind2("timeout 6h")
  ind2("counter")
  ind("}")
  emit("")

  ind("set ip_pair6_tracking_rx {")
  ind2("type ipv6_addr . ipv6_addr")
  ind2("flags dynamic,timeout")
  ind2("timeout 6h")
  ind2("counter")
  ind("}")
  emit("")

  ind("chain wifihaven_account_tx {")
  ind2("type filter hook forward priority 1; policy accept;")
  ind2("iifname \"br-lan\" update @mac_ip_tracking { ether saddr . ip daddr } counter")
  ind2("iifname \"br-lan\" update @mac_ip6_tracking { ether saddr . ip6 daddr } counter")
  ind("}")
  emit("")

  ind("chain wifihaven_account_rx {")
  ind2("type filter hook forward priority 1; policy accept;")
  ind2("oifname \"br-lan\" update @ip_pair_tracking_rx { ip daddr . ip saddr } counter")
  ind2("oifname \"br-lan\" update @ip_pair6_tracking_rx { ip6 daddr . ip6 saddr } counter")
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
  --
  -- #2095: the carve (allow) sets use a LONGER timeout than the 1h block-side
  -- eb_/bl_ sets. Asymmetry rationale: an ea_/ea6_ element expiring is
  -- FAIL-CLOSED — a still-cached, still-in-use extraAllowed host silently
  -- loses its carve and gets caught by the whole-MAC drop (the #2094 residual
  -- / #1929-class transient v6 drop of cdn.jsdelivr.net). nft does NOT refresh
  -- an element's timeout on a duplicate `add` (verified on nft 1.1.6), so live
  -- re-resolution can't keep an actively-used carve alive — only a long
  -- timeout can. Memory stays bounded: per set the element count is
  -- (distinct resolved IPs for that host over the timeout window); the number
  -- of sets is (carved MACs × their small extraAllowed lists); and the whole
  -- table is delete+recreated on every policy apply (policy.apply's `nft -f`,
  -- which also re-seeds via the #2095 apply-time backfill), so accumulation is
  -- capped by the inter-apply interval, not device uptime. 24h (vs 1h) trades a
  -- handful of extra CDN IPs per carved host for surviving a full day of a
  -- stable block without a re-resolve — the exact #2094 scenario.
  local EA_CARVE_TIMEOUT = "24h"
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
        ind2("timeout " .. EA_CARVE_TIMEOUT)
        ind("}")
        emit("")
        ind(string.format("set %s {", ea6_set_name(mac, host)))
        ind2("type ipv6_addr")
        ind2("flags dynamic,timeout")
        ind2("timeout " .. EA_CARVE_TIMEOUT)
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

  -- #1122/#1126: each drop is rendered as a pair of rules sharing one predicate
  -- (see #1826/#1915 / `emit_drop` below):
  --   `<predicate> update @wh_drop_log4 { ether saddr . ip daddr limit rate 1/minute burst 5 packets } log prefix "wh_drop:<mac>:<reason> "`
  --   `<predicate> counter drop comment "wh_drop:<mac>:<reason>"`
  -- (pre-#1826 this was a single `… log prefix … counter drop` rule; the LOG is
  -- now on its own rule so a retry storm can't flood the kernel ring buffer,
  -- while the drop stays unconditional. #1915 made the LOG gate a per-flow
  -- (per-(mac,dst)) limiter set instead of a flat per-rule rate — see the
  -- emit_drop comment below for why.)
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
  -- #1826: a Schedule-blocked device that retry-storms 443 made the old
  -- single-rule form (`… log prefix … counter drop`) emit one kernel-log line
  -- per dropped packet — a ring-buffer flood that pegged wifihaven-agent and
  -- wifihaven-dns-tail (load 4.3). We rate-limit the LOG, but the drop MUST
  -- stay unconditional. An inline `limit rate … log … drop` would NOT achieve
  -- that: in nftables a `limit` statement is a *match*, so packets exceeding the
  -- rate fail the rule and fall through to `policy accept` — silently leaking
  -- the block over budget. So each drop is split into TWO rules with the same
  -- predicate: a rate-limited log rule (no verdict — over-budget packets just
  -- skip the log and continue to the next rule) immediately followed by an
  -- unconditional `counter drop`. The `log prefix` (the agent's nflog read
  -- channel, parsed by nflog.lua off `logread`) lives on the log rule; the
  -- `counter` + `wh_drop:` comment (ops view via `nft list ruleset` and the
  -- nft_drops.lua attribution path, which sums only commented+countered rules)
  -- live on the drop rule, so drop counts stay exact and unaffected by the rate
  -- limit. The limit is per-rule, so each blocked MAC gets its own budget.
  -- #1864: the original 10/second budget was still enough to dominate the
  -- shared `logread` ring buffer when several MACs retry-storm at once — the
  -- aggregate (10/s × N drop rules) evicted wifihaven-agent and dnsmasq lines
  -- from the ring buffer, leaving the router undiagnosable during an incident.
  -- That was first throttled harder (a flat 2/second per-rule limit), but —
  --
  -- #1915: a flat per-RULE `limit rate` is a single token bucket SHARED across
  -- every flow that hits the rule. It conflates two needs the wh_drop LOG
  -- serves: flood control (suppress retry STORMS) and event synthesis (the
  -- agent's nflog tail needs ≥1 line per DISTINCT (mac,reason,dst) to emit a
  -- blocked/paused connection_event). Once a storm drains the shared bucket,
  -- the NEXT distinct flow's first packet is rate-limited away before the tail
  -- reads it — so events silently stop synthesizing (Gate 2
  -- test_paused_mac_https_traffic_surfaces_as_nflog_event hung; the regression
  -- that took Master Router CD red ~2026-06-22 and blocked every router
  -- publish, #1865 included).
  --
  -- Fix: gate the LOG with a PER-ELEMENT (per-flow) limiter instead — a
  -- dynamic set keyed on (ether saddr . ip[6] daddr) with an embedded
  -- `limit rate`. Each distinct (mac,dst) flow gets its OWN token bucket, so
  -- the first packet of every distinct flow always logs (fresh bucket) and
  -- synthesis is never starved; a storm to the SAME (mac,dst) drains only that
  -- element's bucket and is suppressed, so the ring buffer stays usable. The
  -- embedded limit is a match: when the bucket is empty the `update` expression
  -- is false and the rule stops BEFORE the log (no verdict), falling through to
  -- the unconditional `counter drop` on the next rule — enforcement and drop
  -- counts are unchanged, exactly as the two-rule #1826 split guarantees. The
  -- small burst headroom (5) is collapsed back to one event by the agent's own
  -- per-(mac,reason,dst) dedup (nflog.new_dedup, 60s window). The two sets
  -- (wh_drop_log4/6) are declared just above `chain wifihaven_block`.
  --
  -- Per-flow rate keyed on (mac,dst): 1/minute lines up with the agent's 60s
  -- dedup window; burst 5 absorbs a couple of initial retransmits per flow
  -- without flooding. Distinct flows are never throttled against each other.
  local DROP_LOG_RATE = "limit rate 1/minute burst 5 packets"
  -- LOG suffix for a family-constrained predicate ("ip" → v4 set, else v6).
  local function log_suffix(family, mac, reason)
    local set, key
    if family == "ip6" then
      set, key = "wh_drop_log6", "ether saddr . ip6 daddr"
    else
      set, key = "wh_drop_log4", "ether saddr . ip daddr"
    end
    return string.format(
      " update @%s { %s %s } log prefix \"wh_drop:%s:%s \"",
      set, key, DROP_LOG_RATE, mac, reason)
  end
  local function drop_suffix(mac, reason)
    return string.format(" counter drop comment \"wh_drop:%s:%s\"",
                         mac, reason)
  end
  -- Emit the per-flow rate-limited log rule(s) then the unconditional drop
  -- rule for one predicate. `family` is "ip", "ip6", or "any". "any" (the
  -- family-agnostic whole-MAC drop, which has no daddr predicate) emits both a
  -- v4 and a v6 log rule guarded by `meta nfproto` so each can read its
  -- family's daddr into the (mac,dst) key, then one shared family-agnostic
  -- drop. `predicate` is everything up to (but excluding) the log/drop suffix;
  -- the log and drop rules share it verbatim so they match the same packets.
  local function emit_drop(predicate, mac, reason, family)
    family = family or "any"
    if family == "any" then
      ind2(predicate .. " meta nfproto ipv4" .. log_suffix("ip", mac, reason))
      ind2(predicate .. " meta nfproto ipv6" .. log_suffix("ip6", mac, reason))
    else
      ind2(predicate .. log_suffix(family, mac, reason))
    end
    ind2(predicate .. drop_suffix(mac, reason))
  end
  local blocked_reason_by_mac = {}
  for mac, dev in pairs(snapshot.devices or {}) do
    local r = effective_rules(dev, snapshot.profiles)
    if r and r.blocked and not allowall_macs[mac] then
      blocked_reason_by_mac[mac] = r.blockReason or "blocked"
    end
  end

  -- #1915: per-flow wh_drop LOG limiter sets (see emit_drop above). Keyed on
  -- (mac, dst) so each distinct blocked flow gets its own token bucket and logs
  -- on first-seen (synthesis is never starved), while a retry storm to one flow
  -- drains only that element's bucket. The short timeout bounds set memory and
  -- keeps the limiter state alive across a storm (each `update` refreshes it); a
  -- flow that resumes after the timeout logs fresh — a new visibility window.
  ind("set wh_drop_log4 {")
  ind2("type ether_addr . ipv4_addr")
  ind2("flags dynamic,timeout")
  ind2("timeout 2m")
  ind("}")
  emit("")
  ind("set wh_drop_log6 {")
  ind2("type ether_addr . ipv6_addr")
  ind2("flags dynamic,timeout")
  ind2("timeout 2m")
  ind("}")
  emit("")

  ind("chain wifihaven_block {")
  ind2("type filter hook forward priority 0; policy accept;")
  -- #1865: the block page must NEVER be dropped, for any reason. The block-page
  -- DNAT chain (wifihaven_block_nat, prerouting) rewrites a blocked device's
  -- HTTP/HTTPS destination to the local block-page listener so the user sees
  -- *why* they are blocked. With route_localnet (v4 → 127.0.0.1) / redirect
  -- (v6 → br-lan addr) that packet is delivered locally and normally never
  -- reaches this forward chain — but if it ever does (a transient routing edge,
  -- a future DNAT target, or route_localnet not yet applied) the per-MAC / eb_
  -- / bl_ / blockIpOnly / global drops below would kill it and the user would
  -- get a hung connection instead of the block page (the original bug: a
  -- Schedule-blocked v6 device DNAT'd to ::1, then dropped — DST=::1:8443).
  -- One accept guard for router-local destinations encodes the invariant in a
  -- SINGLE place: every block-page DNAT/redirect target is a router-local
  -- address (`fib daddr type local`), whereas normal forwarded traffic is
  -- destined to an external host, so the guard matches only block-page traffic
  -- and never widens who is blocked. Threading a per-rule carve-out suffix
  -- instead would be one missed rule away from re-introducing the bug.
  -- `accept` is a per-chain verdict — the priority-1 accounting chains still
  -- see the packet, so block-page bytes are still counted. This changes no
  -- existing drop rule: the forward hook never governed traffic to the router
  -- itself (that is the input hook), so router-local destinations were never
  -- dropped here regardless.
  ind2("fib daddr type local counter accept comment \"wh_block_page:never-drop:#1865\"")
  -- #1122: the family-agnostic @blocked_macs drop is replaced by per-MAC
  -- rules so each can carry its own `wh_drop:<mac>:<reason>` comment. The
  -- set is still declared (used by the DNAT chain below) but no longer
  -- driven by a single drop rule. MACs without extraAllowed get one
  -- family-agnostic per-MAC drop; MACs with extraAllowed get per-family
  -- rules carrying the ea exception (same shape as pre-#1122).
  for _, mac in ipairs(blocked_macs_list) do
    emit_drop(string.format("ether saddr %s", mac),
              mac, blocked_reason_by_mac[mac] or "blocked")
  end
  -- #1319: each per-MAC blocked rule also carries the `!= @global_allow`
  -- carve-out (ga_suffix), so a globally-allowed host stays reachable for a
  -- blocked MAC. ea_suffix (per-MAC allow) comes first, then ga_suffix.
  for _, mac in ipairs(blocked_ea_macs) do
    local reason = blocked_reason_by_mac[mac] or "blocked"
    emit_drop(string.format("ether saddr %s%s%s", mac, ea_suffix(mac, "ip"),
                            ga_suffix("ip")), mac, reason, "ip")
    emit_drop(string.format("ether saddr %s%s%s", mac, ea_suffix(mac, "ip6"),
                            ga_suffix("ip6")), mac, reason, "ip6")
  end
  -- v4 drops first, then v6 (#392). One ipset directive populates both sets
  -- at DNS time; here we gate on whichever family the destination matched.
  -- #421: each eb_/bl_ drop carries `ip daddr != @ea_<m>_<a>` exception
  -- clauses (one per a ∈ extraAllowed(m)) so an allowed host's resolved
  -- IPs suppress the drop. ea_suffix("") for MACs with no extraAllowed,
  -- so behaviour for those is unchanged.
  -- #1319: ga_suffix appended after the per-MAC ea exception, so an allowed
  -- host (per-MAC or global) suppresses the per-host / per-category drop.
  -- #1645: `host:<host>` names the matched eb_<host> rule in the kernel
  -- log line and the nft comment. Kernel `log prefix` is bounded by
  -- NF_LOG_PREFIXLEN=128; with `wh_drop:<mac>:host:<host> ` the fixed
  -- envelope is 31 chars, leaving 96 chars of headroom — comfortably above
  -- any realistic hostname (RFC 1035 caps a single label at 63 chars and
  -- typical extraBlocked apex hosts are < 30). If a pathologically long
  -- CNAME ever surfaces, the kernel truncates silently and the nflog
  -- parser sees a partial host; for now we accept that gracefully (the
  -- drop still fires correctly — only the label is lossy).
  for _, p in ipairs(eb_pairs) do
    emit_drop(string.format("ether saddr %s ip daddr @%s%s%s",
                            p.mac, eb_set_name(p.host), ea_suffix(p.mac, "ip"),
                            ga_suffix("ip")), p.mac, "host:" .. p.host, "ip")
  end
  for _, p in ipairs(eb_pairs) do
    emit_drop(string.format("ether saddr %s ip6 daddr @%s%s%s",
                            p.mac, eb6_set_name(p.host), ea_suffix(p.mac, "ip6"),
                            ga_suffix("ip6")), p.mac, "host:" .. p.host, "ip6")
  end
  for _, p in ipairs(bl_pairs) do
    emit_drop(string.format("ether saddr %s ip daddr @%s%s%s",
                            p.mac, bl_set_name(p.id), ea_suffix(p.mac, "ip"),
                            ga_suffix("ip")), p.mac, "category:" .. tostring(p.id), "ip")
  end
  for _, p in ipairs(bl_pairs) do
    emit_drop(string.format("ether saddr %s ip6 daddr @%s%s%s",
                            p.mac, bl6_set_name(p.id), ea_suffix(p.mac, "ip6"),
                            ga_suffix("ip6")), p.mac, "category:" .. tostring(p.id), "ip6")
  end
  -- #353: blockIpOnly drop. Predicate `ip daddr != @resolved_<mac>` matches
  -- any v4 destination the device did not DNS-resolve via our resolver in
  -- the last 5 minutes — i.e. DoH / DoT / hard-coded IPs / stale cache
  -- entries past the set timeout.
  for _, mac in ipairs(bio_macs) do
    emit_drop(string.format("ether saddr %s ip daddr != @%s",
                            mac, resolved_set_name(mac)), mac, "ip_only", "ip")
  end
  for _, mac in ipairs(bio_macs) do
    emit_drop(string.format("ether saddr %s ip6 daddr != @%s",
                            mac, resolved6_set_name(mac)), mac, "ip_only", "ip6")
  end
  -- #1319: global block (hosts ∪ categories) → drop for every managed MAC on
  -- @global_block, carved out ONLY by @global_allow (a per-MAC extraAllowed
  -- does NOT save — "a profile may not un-block a global block"). One v4 +
  -- one v6 rule per managed MAC.
  if has_global_block then
    for _, mac in ipairs(managed_macs) do
      emit_drop(string.format("ether saddr %s ip daddr @%s%s",
                              mac, GLOBAL_BLOCK4, ga_suffix("ip")),
                mac, "global_block", "ip")
    end
    for _, mac in ipairs(managed_macs) do
      emit_drop(string.format("ether saddr %s ip6 daddr @%s%s",
                              mac, GLOBAL_BLOCK6, ga_suffix("ip6")),
                mac, "global_block", "ip6")
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
        emit_drop(string.format("ether saddr %s%s", mac, ga_suffix("ip")),
                  mac, g_block_reason, "ip")
      end
      for _, mac in ipairs(managed_macs) do
        emit_drop(string.format("ether saddr %s%s", mac, ga_suffix("ip6")),
                  mac, g_block_reason, "ip6")
      end
    else
      -- No global allow → one family-agnostic unconditional drop per MAC.
      for _, mac in ipairs(managed_macs) do
        emit_drop(string.format("ether saddr %s", mac), mac, g_block_reason)
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
    -- #1865: IPv6 has no `route_localnet` equivalent, and ::1 must never appear
    -- on the wire (RFC 4291), so DNAT'ing forwarded v6 traffic to ::1 never
    -- delivers — the routing decision treats ::1 as non-local and forwards the
    -- packet out the WAN instead (observed live: DST=::1:8443 OUT=eth1). Use
    -- `redirect` instead: it rewrites the destination to the inbound interface's
    -- (br-lan) own address, which IS router-local, so the packet is delivered to
    -- the local uhttpd block-page listener. uhttpd binds [::]:8081 / [::]:8443
    -- (see setup-uhttpd-block-page.sh) so the redirected packet — now destined
    -- to a br-lan v6 address rather than ::1 — lands on a live listener. (The v4
    -- path keeps DNAT-to-127.0.0.1: route_localnet makes loopback deliverable on
    -- v4, and that path already works on prod — leave it untouched.)
    local function dnat6(predicate)
      ind2(predicate .. " tcp dport 80 redirect to :8081")
      ind2(predicate .. " tcp dport 443 redirect to :8443")
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
    -- #411/#1865: v6 siblings, delivered via `redirect` (see dnat6 above).
    -- uhttpd binds [::]:8081 + [::]:8443 so the redirected packet (now destined
    -- to the br-lan v6 address) lands on a live listener. The retained
    -- `ip6 daddr != ::1` guard keeps us from redirecting traffic literally
    -- addressed to loopback (also avoids any self-redirect if the block page
    -- ever issues an outbound v6 request).
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

  -- #1911: network-wide "block encrypted DNS & relays" connection-layer half.
  -- When snapshot.blockEncryptedDns is true, a dedicated forward-hook chain
  -- drops DoT (TCP/853 to any IP) and DNS:53 to the curated public-resolver IPs
  -- (the NODATA half lives in M.dnsmasq). Kept in its OWN chain so the two
  -- halves stay cleanly separable and so these network-wide drops never tangle
  -- with the per-MAC attribution / block-page-DNAT logic above. No block-page
  -- DNAT: these are protocol-level bypass channels, not user-facing browsing —
  -- a clean drop forces fallback to the LAN resolver. Rules are NOT scoped per
  -- MAC (the toggle is household-wide); the forward hook governs only forwarded
  -- LAN traffic, so the router's own upstream DNS (output hook) is unaffected.
  if block_encrypted_dns(snapshot) then
    ind("chain wifihaven_encrypted_dns {")
    ind2("type filter hook forward priority 0; policy accept;")
    for _, rule in ipairs(get_encrypted_dns().nft_rules()) do
      ind2(rule)
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
--                      eb_hosts_by_mac, ea_hosts_by_mac, bl_hosts_by_mac,
--                      bl_member_iterator)
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
--
-- bl_member_iterator (optional, #1782): a function `iterator(id)` that returns
-- an iterable of hosts for blocklist `id` — typically by reading the cache file
-- line-by-line, avoiding a full in-memory table. When nil, falls back to
-- `snapshot._blocklist_hosts` (legacy / test path). Signature:
--   iterator(id) → table-of-strings or iterator-function-returning-string
-- The returned value must be iterable with `ipairs` OR be a 0-arg function
-- returning successive host strings (then nil when done). For simplicity
-- the agent passes a closure that returns a Lua table (streamed from disk).
-- The residual memory cost is one flat list of hosts per subscribed blocklist id
-- (no MAC multiplier), which is acceptable for update_shared's transient call
-- scope — the steady-state OOM was the per-snapshot _blocklist_hosts table held
-- across the agent's lifetime.
function M.update_shared(snapshot, nft_sets, blocked_macs, blocked_reason,
                         eb_hosts_by_mac, ea_hosts_by_mac, bl_hosts_by_mac,
                         bl_member_iterator)
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
  -- #1782: when bl_member_iterator is provided (the agent streams from cache
  -- files), use it instead of snapshot._blocklist_hosts. The iterator is called
  -- once per unique id across all subscribed MACs; we cache the result per-id
  -- so multiple MACs sharing the same blocklist id only trigger one read. The
  -- in-memory cost is one flat host list per subscribed id — no MAC multiplier.
  local bl_hosts_cache = {}
  local function get_bl_hosts(id)
    if bl_hosts_cache[id] ~= nil then return bl_hosts_cache[id] end
    if bl_member_iterator then
      local hosts = bl_member_iterator(id)
      bl_hosts_cache[id] = hosts or {}
    else
      local legacy = (snapshot and snapshot._blocklist_hosts) or {}
      bl_hosts_cache[id] = legacy[id] or {}
    end
    return bl_hosts_cache[id]
  end

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
          local hosts = get_bl_hosts(id)
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
