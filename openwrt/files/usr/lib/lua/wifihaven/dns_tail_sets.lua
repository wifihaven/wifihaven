-- dns_tail_sets.lua — pure nft-set populator logic for the wifihaven-dns-tail
-- sidecar.
--
-- The sidecar tails dnsmasq's `--log-queries=extra` output and, for each
-- resolved reply, populates a handful of nftables sets out of band (dnsmasq's
-- own `nftset=` callback is unreliable / unscoped in the deployed 2.91 build —
-- #496/#505/#515). Three set families are driven here:
--
--   * resolved_<mac>        — blockIpOnly allow-by-resolution (#505)
--   * eb_<sanhost>          — extraBlocked drop set (#515)
--   * ea_<sanmac>_<sanhost> — extraAllowed carve-out (#421); NEW dns-tail
--                             populator added in #1346 (was dnsmasq-only).
--
-- #1346 — the directly-queried CNAME-target gap. Some clients (Apple devices)
-- resolve a branded host, then re-query the FINAL CNAME target *directly* as a
-- separate lookup. On that direct query the answered name is the CDN target
-- (e.g. `prod.khan.map.fastly.net`), which does NOT suffix-match the declared
-- ea_/eb_<brand> set — so dnsmasq's nftset callback AND the pre-#1346 eb_
-- populator both miss it, leaving the resolved IP out of the kernel set (an
-- allowed app is falsely blocked / an extraBlocked host silently bypassed).
-- We recover the branded chain head from the dns_log alias map learned in
-- #1344/#1345 (`resolve_head`) and walk THAT name's labels too.
--
-- This logic lives in its own module (rather than inline in the sidecar) so it
-- is unit-testable with an injected exec function — the same get_fn/write_fn/
-- exec_fn injection pattern used in policy.lua / conntrack.lua. The sidecar
-- wires the real `os.execute`, the live dns_log cache's `resolve_head`, the
-- DHCP-derived ip→MAC map, and the declared-set membership tables.

local M = {}

-- Replace dots and colons with underscores. Mirrors render.sanitize so the
-- (MAC|host) → set-name mapping is byte-identical to what render.lua emits —
-- keep them in concert if either changes.
function M.sanitize(s)
  return (s:gsub("[%.%:]", "_"))
end

-- The answer IP comes from dnsmasq's log line (already validated as v4/v6 by
-- dns_log.parse_resolved_reply), but pass it through a strict allow-list
-- anyway so the value handed to `nft add element` can never carry shell
-- metacharacters.
function M.safe_addr(ip)
  if type(ip) ~= "string" then return nil end
  if ip:find("[^%x%.:]") then return nil end
  return ip
end

-- Build the `nft add element` command for one (set, ip), or nil if the ip
-- fails the allow-list. Output is discarded: ENOENT is expected if the set was
-- removed between our refresh and this call (admin policy change), and
-- "element exists" is a legitimate duplicate. Neither is actionable.
function M.add_element_cmd(nft_table, set_name, ip)
  local safe = M.safe_addr(ip)
  if not safe then return nil end
  return string.format(
    "nft add element %s %s { %s } >/dev/null 2>&1",
    nft_table, set_name, safe)
end

-- Run the add via exec_fn (defaults to os.execute). Returns true if a command
-- was issued. exec_fn is injectable for tests (capture instead of execute).
function M.nft_add_element(nft_table, set_name, ip, exec_fn)
  local cmd = M.add_element_cmd(nft_table, set_name, ip)
  if not cmd then return false end
  local exec = exec_fn or os.execute
  exec(cmd)
  return true
end

-- Sanitized-MAC sub-pattern: six hex pairs joined by `_`. The ea_ set name
-- embeds BOTH mac and host (`ea_<sanmac>_<sanhost>`, e.g.
-- `ea_04_72_ef_d6_e4_5a_kastatic_org`); the fixed-width MAC prefix is what
-- lets us split it back into (sanmac, sanhost) unambiguously even though both
-- halves contain underscores.
local SANMAC = "%x%x_%x%x_%x%x_%x%x_%x%x_%x%x"

-- Classify one `nft -a list table` line, mutating the membership tables in
-- `s` (s = { bio={}, eb4={}, eb6={}, ea4={}, ea6={} }):
--   bio[sanmac]            = true      resolved_<sanmac>      (#505)
--   eb4[sanhost]           = true      eb_<sanhost>           (#515)
--   eb6[sanhost]           = true      eb6_<sanhost>          (#515)
--   ea4[sanmac][sanhost]   = true      ea_<sanmac>_<sanhost>  (#421/#1346)
--   ea6[sanmac][sanhost]   = true      ea6_<sanmac>_<sanhost>
-- The literal `resolved_`/`eb_`/`eb6_`/`ea_`/`ea6_` prefixes are mutually
-- exclusive (none is a prefix of another after the trailing `_`), so the
-- independent matches below cannot cross-fire.
function M.classify_set_line(line, s)
  local sanmac = line:match("set%s+resolved_([%w_]+)%s*{")
  if sanmac then s.bio[sanmac] = true end
  local eb4 = line:match("set%s+eb_([%w_]+)%s*{")
  if eb4 then s.eb4[eb4] = true end
  local eb6 = line:match("set%s+eb6_([%w_]+)%s*{")
  if eb6 then s.eb6[eb6] = true end
  local ea4m, ea4h = line:match("set%s+ea_(" .. SANMAC .. ")_([%w_]+)%s*{")
  if ea4m then
    s.ea4[ea4m] = s.ea4[ea4m] or {}
    s.ea4[ea4m][ea4h] = true
  end
  local ea6m, ea6h = line:match("set%s+ea6_(" .. SANMAC .. ")_([%w_]+)%s*{")
  if ea6m then
    s.ea6[ea6m] = s.ea6[ea6m] or {}
    s.ea6[ea6m][ea6h] = true
  end
end

-- Walk the candidate sanitized-host suffixes for an answered name and invoke
-- fn(sanhost) for each, stopping the CURRENT name's walk when fn returns true
-- (first-hit-wins, mirroring dnsmasq's `nftset=/example.com/...` semantics
-- where example.com matches itself and every subdomain).
--
-- Two candidate names are walked: the answered `name` itself, then — when
-- distinct — the branded chain head recovered from the #1344 alias map. That
-- second walk is the #1346 fix: a directly-queried CDN target only matches a
-- declared <brand> set through its recovered head.
function M.each_candidate_host(name, resolve_head, fn)
  local head  = (resolve_head and resolve_head(name)) or name
  local names = (head ~= name) and { name, head } or { name }
  for _, nm in ipairs(names) do
    local n = nm
    while n and n ~= "" do
      if fn(M.sanitize(n)) then break end
      local dot = n:find("%.")
      if not dot then break end
      n = n:sub(dot + 1)
    end
  end
end

-- maybe_populate_eb(r, deps) — extraBlocked populator (#515 + #1346).
-- r    : a dns_log.parse_resolved_reply result { name, ip, family }.
-- deps : { eb4, eb6, nft_table, exec_fn?, resolve_head?, log? }
-- Returns the number of `nft add element` commands issued (0+). A host whose
-- answered name OR recovered branded head suffix-matches a declared
-- eb_/eb6_<sanhost> set gets its resolved IP added to that set.
function M.maybe_populate_eb(r, deps)
  if not r or not r.name then return 0 end
  local set    = (r.family == "v6") and deps.eb6 or deps.eb4
  local prefix = (r.family == "v6") and "eb6_" or "eb_"
  local adds, seen = 0, {}
  M.each_candidate_host(r.name, deps.resolve_head, function(sanhost)
    if set[sanhost] then
      if not seen[sanhost] then
        seen[sanhost] = true
        M.nft_add_element(deps.nft_table, prefix .. sanhost, r.ip, deps.exec_fn)
        adds = adds + 1
        if deps.log then deps.log(prefix .. sanhost, r) end
      end
      return true  -- first match for this candidate name wins
    end
    return false
  end)
  return adds
end

-- maybe_populate_ea(r, deps) — extraAllowed carve-out populator (#1346, NEW).
-- Mirrors maybe_populate_eb but the sets are per-(mac, host): map the reply's
-- client_ip → MAC, then for each ea_<sanmac>_<sanhost> set belonging to that
-- MAC whose host suffix-matches the answered name OR its recovered branded
-- head, add the resolved IP.
-- r    : a dns_log.parse_resolved_reply result { client_ip, name, ip, family }.
-- deps : { ea4, ea6, ip_to_mac, nft_table, exec_fn?, resolve_head?, log? }
-- Returns the number of `nft add element` commands issued (0+).
function M.maybe_populate_ea(r, deps)
  if not r or not r.name then return 0 end
  local mac = deps.ip_to_mac and deps.ip_to_mac[r.client_ip]
  if not mac then return 0 end
  local sanmac = M.sanitize(mac)
  local by_mac = (r.family == "v6") and deps.ea6 or deps.ea4
  local hosts  = by_mac and by_mac[sanmac]
  if not hosts then return 0 end
  local prefix = (r.family == "v6") and "ea6_" or "ea_"
  local adds, seen = 0, {}
  M.each_candidate_host(r.name, deps.resolve_head, function(sanhost)
    if hosts[sanhost] then
      if not seen[sanhost] then
        seen[sanhost] = true
        M.nft_add_element(deps.nft_table,
          prefix .. sanmac .. "_" .. sanhost, r.ip, deps.exec_fn)
        adds = adds + 1
        if deps.log then deps.log(prefix .. sanmac .. "_" .. sanhost, r, mac) end
      end
      return true
    end
    return false
  end)
  return adds
end

-- backfill_ea(cache, carve, deps) — apply-time pre-population of the
-- per-(mac,host) extraAllowed carve sets (#2095).
--
-- policy.apply reloads the ruleset with a single `nft -f` whose prelude
-- delete+recreates `table inet wifihaven`, so EVERY ea_/ea6_ carve set is
-- empty immediately after an apply and only refills when the device next
-- RESOLVES a carved host over the live query log (the maybe_populate_ea path
-- above, which tails with latency). A device holding a long-cached CDN IP
-- (KaTeX/MathJax on cdn.jsdelivr.net) can reconnect before that refill and get
-- caught by the whole-MAC drop even though the host IS in extraAllowed — the
-- #2094 residual / #1929-class transient v6 drop. This closes the post-apply
-- window by seeding the carve sets from the recent ip->host resolutions the
-- dns-tail sidecar already persisted (paths.dns_cache), for BOTH families.
--
-- cache : { [ip] = hostname }  — dns_log.load_table output (the answered name
--         attributed to each resolved ip; already TTL-bounded on load).
-- carve : { [sanhost] = { [sanmac]=true, ... } } — the sanitized hosts in some
--         MAC's effective extraAllowed and the MACs that carve each. Built by
--         the caller from render.effective_extra_allowed_by_mac (the SAME SSOT
--         that DECLARES the sets), so every add targets a set that exists.
-- deps  : { nft_table, exec_fn?, resolve_head?, log? }
--
-- For each cached (ip, hostname): walk the answered name's candidate suffixes
-- (+ the #1346 recovered branded head via resolve_head) and, on the first
-- carved-host hit, add ip to ea_/ea6_<sanmac>_<sanhost> for every MAC that
-- carves that host. Family is chosen from the ip literal (colon => v6). The ip
-- passes through safe_addr (inside nft_add_element) so a malformed cache line
-- can never reach the shell. Returns the number of `nft add element` commands
-- issued.
--
-- Cost: one linear pass over the cache (× label depth) with a hash lookup per
-- candidate suffix. Runs only at apply time (infrequent, etag-deduped) and
-- only when some MAC has a non-empty extraAllowed, so it does not reintroduce
-- a hot-path O(N) scan (cf. #2068).
function M.backfill_ea(cache, carve, deps)
  if type(cache) ~= "table" or type(carve) ~= "table" then return 0 end
  local adds = 0
  for ip, name in pairs(cache) do
    if type(name) == "string" then
      local is6    = (type(ip) == "string") and ip:find(":", 1, true) ~= nil
      local prefix = is6 and "ea6_" or "ea_"
      local seen   = {}
      M.each_candidate_host(name, deps.resolve_head, function(sanhost)
        local macs = carve[sanhost]
        if macs then
          if not seen[sanhost] then
            seen[sanhost] = true
            for sanmac in pairs(macs) do
              if M.nft_add_element(deps.nft_table,
                   prefix .. sanmac .. "_" .. sanhost, ip, deps.exec_fn) then
                adds = adds + 1
                if deps.log then
                  deps.log(prefix .. sanmac .. "_" .. sanhost, ip, name)
                end
              end
            end
          end
          return true  -- first carved suffix wins (mirror maybe_populate_ea)
        end
        return false
      end)
    end
  end
  return adds
end

-- #2208 — build a SINGLE `nft -f` batch script that seeds every carve set from
-- the cache, instead of one `nft add element` process per element. The old
-- per-element M.backfill_ea (above) spawns O(matching-cache-entries) nft
-- processes; on a busy prod cache with broad carve hosts that is thousands of
-- ~6ms fork+exec's, dominating apply latency (the v0.3.19→v0.3.20 regression
-- #2094/#2095 introduced). Grouping all adds into one `nft -f` invocation makes
-- the cost O(1) process spawns regardless of element count.
--
-- Returns (script, count):
--   script — an nft ruleset string of `add element <table> <set> { ip, ... }`
--            lines (one per set, ips grouped + sorted), "" when count==0.
--   count  — number of distinct (set, ip) elements the script adds.
--
-- Walk/dedup semantics are byte-identical to M.backfill_ea: same candidate-host
-- suffix walk (+ #1346 resolve_head head), same first-carved-suffix-wins, same
-- v4/v6 prefix split. The only difference is emission — accumulate into a
-- per-set element map and render one batch rather than exec per element.
--
-- Safety: every target set is DECLARED by render.nft from the SAME
-- render.effective_extra_allowed_by_mac SSOT the caller builds `carve` from,
-- and the `nft -f` that created them ran immediately before, so all sets exist
-- and are empty (no missing-set or duplicate-element aborts inside the single
-- transaction). IPs pass safe_addr; set names are sanitized — no shell/nft
-- metacharacters reach the script. The caller runs the batch and, if the load
-- fails (a set genuinely raced away), falls back to the per-element path so a
-- single stale set can never drop the whole carve seeding.
function M.build_ea_backfill_script(cache, carve, deps)
  if type(cache) ~= "table" or type(carve) ~= "table" then return "", 0 end
  deps = deps or {}
  local by_set = {}   -- set_name -> { [ip]=true } (dedup within a set)
  local order  = {}   -- set-name insertion order (sorted before render)
  local count  = 0
  for ip, name in pairs(cache) do
    if type(name) == "string" then
      local safe = M.safe_addr(ip)
      if safe then
        local is6    = ip:find(":", 1, true) ~= nil
        local prefix = is6 and "ea6_" or "ea_"
        local seen   = {}
        M.each_candidate_host(name, deps.resolve_head, function(sanhost)
          local macs = carve[sanhost]
          if macs then
            if not seen[sanhost] then
              seen[sanhost] = true
              for sanmac in pairs(macs) do
                local set = prefix .. sanmac .. "_" .. sanhost
                local ips = by_set[set]
                if not ips then ips = {}; by_set[set] = ips; order[#order + 1] = set end
                if not ips[safe] then ips[safe] = true; count = count + 1 end
              end
            end
            return true  -- first carved suffix wins (mirror M.backfill_ea)
          end
          return false
        end)
      end
    end
  end
  if count == 0 then return "", 0 end
  table.sort(order)
  local tbl   = deps.nft_table or "inet wifihaven"
  local lines = {}
  for _, set in ipairs(order) do
    local ips = {}
    for ip in pairs(by_set[set]) do ips[#ips + 1] = ip end
    table.sort(ips)
    lines[#lines + 1] = string.format("add element %s %s { %s }",
                                      tbl, set, table.concat(ips, ", "))
  end
  return table.concat(lines, "\n") .. "\n", count
end

return M
