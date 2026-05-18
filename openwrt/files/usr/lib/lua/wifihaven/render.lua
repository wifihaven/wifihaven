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
--         ( m ∈ blocked_macs ∧ ¬ea_hit(m, d) )
--      ∨  ( ∃ h ∈ extraBlocked(m). d ∈ @eb_h ∧ ¬ea_hit(m, d) )
--      ∨  ( ∃ id ∈ blocklistIds(m). d ∈ @bl_id ∧ ¬ea_hit(m, d) )
--      ∨  blockIpOnly(m) ∧ d ∉ @resolved_<m>       (or @resolved6_<m> for v6)
--      ∨  m ∈ @failover_drop                       (block-all failover only)
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

  -- #421: per-(MAC, host) extraAllowed nftset populators. One line per
  -- (mac, host) pair; the set is named per-(mac, host) but populated by
  -- any client's resolution of host. The MAC scoping happens in the nft
  -- rule (`ether saddr <mac> ... != @ea_<mac>_<host>`), not at DNS time.
  -- (#496: dnsmasq 2.91's `nftset=` does NOT parse a `tag:` prefix — the
  -- earlier tag-scoped form was silently rejected, leaving every ea_ set
  -- empty and turning the per-MAC drop rule into an unconditional drop.)
  do
    local ea_macs = {}
    for m in pairs(ea_by_mac) do ea_macs[#ea_macs + 1] = m end
    table.sort(ea_macs)
    if #ea_macs > 0 then
      emit("# extraAllowed → per-(MAC, host) ea_ nftsets (#421, #496)")
      for _, mac in ipairs(ea_macs) do
        for _, host in ipairs(ea_by_mac[mac]) do
          emit(string.format(
            "nftset=/%s/4#inet#wifihaven#%s,6#inet#wifihaven#%s",
            host,
            ea_set_name(mac, host), ea6_set_name(mac, host)))
        end
      end
      emit("")
    end
  end

  -- #351: extraBlocked is enforced at the connection layer (per-MAC nft
  -- drop), not via DNS sinkhole. dnsmasq's only role here is to populate
  -- an nftables set with the resolved IPs of each blocked host, so the
  -- forward-hook drop can match by `ip daddr ∈ @eb_<host>`. DNS still
  -- resolves the host normally — see docs/architecture.md §0.1 (Truth 1).
  --
  -- OpenWRT 23.05+ ships dnsmasq-full without HAVE_IPSET; the legacy
  -- ipset framework was dropped in favour of native nftables sets, so we
  -- emit `nftset=` and populate the per-host nft set in the `inet
  -- wifihaven` table directly. Syntax:
  --   nftset=/<host>/<af>#<family>#<table>#<set>[,<af>#...]
  -- The `4#` / `6#` address-family prefix selects which dnsmasq response
  -- type routes to which set (#392): A → v4 set, AAAA → v6 set. One
  -- comma-joined directive per host keeps the config compact.
  emit("# extraBlocked → per-host nftset populated at resolve time (#351, #392)")
  for _, host in ipairs(effective_extra_blocked_hosts(snapshot)) do
    emit(string.format(
      "nftset=/%s/4#inet#wifihaven#%s,6#inet#wifihaven#%s",
      host, eb_set_name(host), eb6_set_name(host)))
  end
  emit("")

  -- #352: category blocklists — populate bl_<id> / bl6_<id> sets at DNS
  -- resolve time. snapshot._blocklist_hosts = { [id] = {host1, ...} } is
  -- populated by the agent before calling render (so tests can inject
  -- without filesystem). Same nftset= migration as eb_ above (#392).
  local bl_hosts = snapshot._blocklist_hosts or {}
  local bl_ids   = sorted_keys(snapshot.blocklists or {})
  if #bl_ids > 0 then
    emit("# blocklist nftsets → resolved at DNS time (#352, #392)")
    for _, id in ipairs(bl_ids) do
      local hosts   = bl_hosts[id] or {}
      local set4    = bl_set_name(id)
      local set6    = bl6_set_name(id)
      for _, host in ipairs(hosts) do
        emit(string.format(
          "nftset=/%s/4#inet#wifihaven#%s,6#inet#wifihaven#%s",
          host, set4, set6))
      end
    end
    emit("")
  end

  return table.concat(out, "\n")
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

  -- Per-flow accounting set.
  ind("set mac_ip_tracking {")
  ind2("type ether_addr . ipv4_addr")
  ind2("flags dynamic,timeout")
  ind2("timeout 6h")
  ind2("counter")
  ind("}")
  emit("")

  -- Accounting chain: forward hook, low priority; updates tracking set per packet.
  ind("chain wifihaven_account {")
  ind2("type filter hook forward priority 1; policy accept;")
  ind2("update @mac_ip_tracking { ether saddr . ip daddr } counter")
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
  for mac, dev in sorted_devices(snapshot.devices) do
    local r = effective_rules(dev, snapshot.profiles)
    if r and r.blocked and not allowall_macs[mac] then
      if ea_by_mac_early[mac] then
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
  local bio_all = block_ip_only_macs(snapshot)
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

  ind("chain wifihaven_block {")
  ind2("type filter hook forward priority 0; policy accept;")
  if #blocked_macs_list > 0 then
    ind2("ether saddr @blocked_macs drop")
  end
  -- #421: blocked MAC + non-empty extraAllowed → per-MAC drop carrying the
  -- ea exception, one rule per family. (The @blocked_macs set rule above
  -- is family-agnostic, but the ea suffix is `ip daddr` / `ip6 daddr`, so
  -- the rules below must be family-scoped.) The eb_/bl_ drops further
  -- down also fire for these MACs, but the ea suffix on those rules
  -- already keeps them inert when daddr is in an ea set, so the net
  -- behaviour is "drop iff blocked and not in any ea set."
  for _, mac in ipairs(blocked_ea_macs) do
    -- The leading `ip daddr != @ea_...` / `ip6 daddr != @ea6_...` clause
    -- in ea_suffix itself scopes the rule to v4 / v6 packets, so we don't
    -- need an extra family keyword. ea_suffix returns " ip daddr ..." with
    -- a leading space, so the rule reads cleanly.
    ind2(string.format("ether saddr %s%s drop", mac, ea_suffix(mac, "ip")))
    ind2(string.format("ether saddr %s%s drop", mac, ea_suffix(mac, "ip6")))
  end
  -- v4 drops first, then v6 (#392). One ipset directive populates both sets
  -- at DNS time; here we gate on whichever family the destination matched.
  -- #421: each eb_/bl_ drop carries `ip daddr != @ea_<m>_<a>` exception
  -- clauses (one per a ∈ extraAllowed(m)) so an allowed host's resolved
  -- IPs suppress the drop. ea_suffix("") for MACs with no extraAllowed,
  -- so behaviour for those is unchanged.
  for _, p in ipairs(eb_pairs) do
    ind2(string.format("ether saddr %s ip daddr @%s%s drop",
                       p.mac, eb_set_name(p.host), ea_suffix(p.mac, "ip")))
  end
  for _, p in ipairs(eb_pairs) do
    ind2(string.format("ether saddr %s ip6 daddr @%s%s drop",
                       p.mac, eb6_set_name(p.host), ea_suffix(p.mac, "ip6")))
  end
  for _, p in ipairs(bl_pairs) do
    ind2(string.format("ether saddr %s ip daddr @%s%s drop",
                       p.mac, bl_set_name(p.id), ea_suffix(p.mac, "ip")))
  end
  for _, p in ipairs(bl_pairs) do
    ind2(string.format("ether saddr %s ip6 daddr @%s%s drop",
                       p.mac, bl6_set_name(p.id), ea_suffix(p.mac, "ip6")))
  end
  -- #353: blockIpOnly drop. Predicate `ip daddr != @resolved_<mac>` matches
  -- any v4 destination the device did not DNS-resolve via our resolver in
  -- the last 5 minutes — i.e. DoH / DoT / hard-coded IPs / stale cache
  -- entries past the set timeout.
  for _, mac in ipairs(bio_macs) do
    ind2(string.format("ether saddr %s ip daddr != @%s drop",
                       mac, resolved_set_name(mac)))
  end
  for _, mac in ipairs(bio_macs) do
    ind2(string.format("ether saddr %s ip6 daddr != @%s drop",
                       mac, resolved6_set_name(mac)))
  end
  ind("}")
  emit("")

  -- Block-page DNAT chain (#303 + #351 + #352 + #353): redirect HTTP/80 to
  -- the local uhttpd block page so users see *why* a connection failed.
  -- Four triggers: MAC-wide block, per-(MAC, host) extraBlocked, per-(MAC,
  -- blocklistId), and per-MAC blockIpOnly (un-resolved daddr).
  if #blocked_macs_list > 0 or #blocked_ea_macs > 0 or #eb_pairs > 0 or #bl_pairs > 0 or #bio_macs > 0 then
    ind("chain wifihaven_block_nat {")
    ind2("type nat hook prerouting priority dstnat; policy accept;")
    if #blocked_macs_list > 0 then
      ind2("ether saddr @blocked_macs tcp dport 80 dnat ip to 127.0.0.1:8081")
    end
    -- #421: blocked + extraAllowed → per-MAC v4 DNAT with ea exception.
    for _, mac in ipairs(blocked_ea_macs) do
      ind2(string.format(
        "ether saddr %s%s tcp dport 80 dnat ip to 127.0.0.1:8081",
        mac, ea_suffix(mac, "ip")))
    end
    for _, p in ipairs(eb_pairs) do
      ind2(string.format(
        "ether saddr %s ip daddr @%s%s tcp dport 80 dnat ip to 127.0.0.1:8081",
        p.mac, eb_set_name(p.host), ea_suffix(p.mac, "ip")))
    end
    for _, p in ipairs(bl_pairs) do
      ind2(string.format(
        "ether saddr %s ip daddr @%s%s tcp dport 80 dnat ip to 127.0.0.1:8081",
        p.mac, bl_set_name(p.id), ea_suffix(p.mac, "ip")))
    end
    -- #353: blockIpOnly v4 DNAT. Same predicate as the forward-chain drop;
    -- the DNAT fires *before* the drop (prerouting < forward), so the
    -- device sees the block page instead of a silent timeout.
    for _, mac in ipairs(bio_macs) do
      ind2(string.format(
        "ether saddr %s ip daddr != @%s tcp dport 80 dnat ip to 127.0.0.1:8081",
        mac, resolved_set_name(mac)))
    end
    -- #411: v6 siblings. uhttpd also binds [::1]:8081 (see install.sh).
    -- `ip6 daddr != ::1` guards against self-DNAT recursion if the block
    -- page itself ever issues an outbound v6 request.
    if #blocked_macs_list > 0 then
      ind2("ether saddr @blocked_macs ip6 daddr != ::1 tcp dport 80 dnat ip6 to ::1:8081")
    end
    -- #421: blocked + extraAllowed → per-MAC v6 DNAT with ea6 exception.
    -- `ip6 daddr != ::1` guards against self-DNAT (same as the @blocked_macs
    -- v6 line above).
    for _, mac in ipairs(blocked_ea_macs) do
      ind2(string.format(
        "ether saddr %s ip6 daddr != ::1%s tcp dport 80 dnat ip6 to ::1:8081",
        mac, ea_suffix(mac, "ip6")))
    end
    for _, p in ipairs(eb_pairs) do
      ind2(string.format(
        "ether saddr %s ip6 daddr @%s%s tcp dport 80 dnat ip6 to ::1:8081",
        p.mac, eb6_set_name(p.host), ea_suffix(p.mac, "ip6")))
    end
    for _, p in ipairs(bl_pairs) do
      ind2(string.format(
        "ether saddr %s ip6 daddr @%s%s tcp dport 80 dnat ip6 to ::1:8081",
        p.mac, bl6_set_name(p.id), ea_suffix(p.mac, "ip6")))
    end
    -- #353 + #411: blockIpOnly v6 DNAT. `ip6 daddr != ::1` guards against
    -- self-redirect (the uhttpd listener at ::1 is "not in resolved set"
    -- by definition and we must not DNAT its own responses).
    for _, mac in ipairs(bio_macs) do
      ind2(string.format(
        "ether saddr %s ip6 daddr != ::1 ip6 daddr != @%s tcp dport 80 dnat ip6 to ::1:8081",
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

-- render.write_blocked_reasons(blocked_reason, path) → ok, err
--
-- Persist the in-memory { mac → reason_string } map (built by
-- update_shared above) to a file the block-page uhttpd handler can read.
-- Format: one "<mac>\t<reason>\n" per blocked MAC, sorted by MAC for
-- deterministic output. Written atomically via tmp+rename so the handler
-- never sees a partial write.
--
-- The block page handler (#437) looks the requesting device's MAC up in
-- this file to render reason-specific copy ("This profile is paused.",
-- etc.) instead of the generic "blocked" fallback.
function M.write_blocked_reasons(blocked_reason, path)
  local lines = {}
  for mac, reason in pairs(blocked_reason or {}) do
    table.insert(lines, mac .. "\t" .. tostring(reason))
  end
  table.sort(lines)
  local body = table.concat(lines, "\n")
  if #lines > 0 then body = body .. "\n" end
  local tmp = path .. ".tmp"
  local f, err = io.open(tmp, "w")
  if not f then return nil, err end
  f:write(body)
  f:close()
  local ok, rerr = os.rename(tmp, path)
  if not ok then return nil, rerr end
  return true
end

-- render.write_blocked_hosts(eb_hosts_by_mac, bl_hosts_by_mac, path) → ok, err
--
-- Persist the per-(MAC, host) block source classification so the block-page
-- handler can distinguish an extraBlocked hit from a category-blocklist hit
-- (#594). Format: one "<mac>\t<host>\t<source>\n" per (mac, host) entry,
-- sorted for deterministic output. <source> is "extra_blocked" for an
-- extraBlocked hit or "category:<blocklist_id>" for a category-blocklist hit.
-- Written atomically via tmp+rename so the handler never sees a partial file.
function M.write_blocked_hosts(eb_hosts_by_mac, bl_hosts_by_mac, path)
  local lines = {}
  for mac, hosts in pairs(eb_hosts_by_mac or {}) do
    for host in pairs(hosts) do
      table.insert(lines, mac .. "\t" .. host .. "\textra_blocked")
    end
  end
  for mac, hosts in pairs(bl_hosts_by_mac or {}) do
    for host, id in pairs(hosts) do
      table.insert(lines, mac .. "\t" .. host .. "\tcategory:" .. tostring(id))
    end
  end
  table.sort(lines)
  local body = table.concat(lines, "\n")
  if #lines > 0 then body = body .. "\n" end
  local tmp = path .. ".tmp"
  local f, err = io.open(tmp, "w")
  if not f then return nil, err end
  f:write(body)
  f:close()
  local ok, rerr = os.rename(tmp, path)
  if not ok then return nil, rerr end
  return true
end

return M
