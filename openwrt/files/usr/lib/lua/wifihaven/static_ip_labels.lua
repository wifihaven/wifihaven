-- static_ip_labels.lua — last-resort IP-range → label map (#1655, #1708).
--
-- ATTRIBUTION ONLY. This map MUST NOT participate in any drop predicate or
-- carve-out: enforcement lives entirely in the per-MAC BlockRules / nftables
-- pipeline (see AGENTS.md "Architectural model"). The map exists so that
-- connection_events for flows that have NO SNI and NO DNS resolution carry
-- a meaningful label instead of a bare IP literal — Apple push (APNs) on
-- 17.0.0.0/8 is the canonical case. Promotion to an enforcement input
-- requires explicit operator approval and a tracking issue.
--
-- Precedence in conntrack.handle_flow:
--   1. attribute_hostname (dnsmasq query-log cache, which SNI/QUIC sidecars
--      also feed via cache.insert_sni — see #573 / #1651)
--   2. ipset_lookup_hostname (legacy nft_sets fallback)
--   3. static_ip_labels.lookup (THIS module — last resort)
--
-- Wire shape: the produced label is emitted as a HostId.Label variant
-- (#1708) — { type = "label", value = <label>, source = "static-ip-range" }
-- — NOT as an fqdn. HostMatch.matchesAny returns false for label-typed
-- hosts, so a label can never pattern-match against a real apex.
-- `lookup` returns the (label, source) pair so the caller can plumb both
-- through to build_event without a second branch.
--
-- Initial map is intentionally small (< 10 entries). Operators should extend
-- it only with prod evidence that a range dominates the unattributed-IP tail
-- AND has a well-known, unambiguous owner. Add an entry + a test in the same
-- PR; cite the source in the comment beside the entry.

local M = {}

-- The single wire `source` string for everything this module produces.
-- Kept here (rather than at the call site) so an operator extending the
-- map cannot accidentally vary it. Matches HostId.LabelSourceStaticIpRange
-- on the API side (shared/src/types/HostId.scala).
M.SOURCE = "static-ip-range"

-- IPv4 ranges. Each entry: { cidr, label }.
--
-- 17.0.0.0/8         — Apple's RIR-allocated /8. APNs hosts and many Apple
--                      infrastructure services live here; clients pin them
--                      and bypass dnsmasq entirely.
--                      <https://www.iana.org/assignments/ipv4-address-space>
-- 8.8.8.8, 8.8.4.4   — Google Public DNS. Apps that bypass the local
--                      resolver hit these directly.
-- 1.1.1.1, 1.0.0.1   — Cloudflare Public DNS. Same pattern as Google DNS.
--
-- Time servers (pool.ntp.org and friends) are NOT included: there are too
-- many rotating IPs to enumerate, and NTP volume is tiny.
M._ranges = {
  { cidr = "17.0.0.0/8",     label = "apple-push"     },
  { cidr = "8.8.8.8/32",     label = "google-dns"     },
  { cidr = "8.8.4.4/32",     label = "google-dns"     },
  { cidr = "1.1.1.1/32",     label = "cloudflare-dns" },
  { cidr = "1.0.0.1/32",     label = "cloudflare-dns" },
}

-- IPv6 ranges. Each entry: { cidr, label }. Host /128 entries are the common
-- case — clients that bypass the local resolver pin these literals.
--
-- 2606:4700:4700::1111, ::1001 — Cloudflare Public DNS (the v6 siblings of
--                      1.1.1.1 / 1.0.0.1).
--                      <https://developers.cloudflare.com/1.1.1.1/ip-addresses/>
-- 2001:4860:4860::8888, ::8844 — Google Public DNS (the v6 siblings of
--                      8.8.8.8 / 8.8.4.4).
--                      <https://developers.google.com/speed/public-dns/docs/using>
--
-- Apple's v6 push ranges (e.g. 2620:149::/32) are intentionally NOT included
-- yet — larger/messier, deferred to a follow-up with prod evidence (#2006).
M._ranges_v6 = {
  { cidr = "2606:4700:4700::1111/128", label = "cloudflare-dns" },
  { cidr = "2606:4700:4700::1001/128", label = "cloudflare-dns" },
  { cidr = "2001:4860:4860::8888/128", label = "google-dns"     },
  { cidr = "2001:4860:4860::8844/128", label = "google-dns"     },
}

-- Parse "a.b.c.d" → 32-bit unsigned integer, or nil on malformed input.
local function ipv4_to_uint(ip)
  if type(ip) ~= "string" then return nil end
  local a, b, c, d = ip:match("^(%d+)%.(%d+)%.(%d+)%.(%d+)$")
  if not a then return nil end
  a, b, c, d = tonumber(a), tonumber(b), tonumber(c), tonumber(d)
  if a > 255 or b > 255 or c > 255 or d > 255 then return nil end
  return a * 16777216 + b * 65536 + c * 256 + d
end

-- Parse "a.b.c.d/N" → { network = uint, mask = uint }, or nil on malformed.
local function parse_cidr_v4(cidr)
  local addr, prefix = cidr:match("^([%d%.]+)/(%d+)$")
  if not addr then return nil end
  local ip = ipv4_to_uint(addr)
  prefix = tonumber(prefix)
  -- prefix=0 would match every IPv4 address (0.0.0.0/0) and is almost
  -- certainly a typo for an operator-curated last-resort map. Fail loud
  -- at module load rather than silently swallow every flow.
  if not ip or not prefix or prefix < 1 or prefix > 32 then return nil end
  local mask = 0xFFFFFFFF - (2 ^ (32 - prefix) - 1)
  return { network = ip - (ip % (2 ^ (32 - prefix))), mask = mask }
end

-- Pre-compile the v4 ranges once at module load. We keep a parallel array
-- (not a map) so first-match-wins ordering is preserved. `blocksize` is
-- `2^(32-prefix)` — pre-computed so the hot path in `lookup` is a single
-- modulo + subtract per range, no per-call shift.
local compiled_v4 = {}
for _, entry in ipairs(M._ranges) do
  local parsed = parse_cidr_v4(entry.cidr)
  if parsed then
    compiled_v4[#compiled_v4 + 1] = {
      network   = parsed.network,
      blocksize = 0xFFFFFFFF - parsed.mask + 1,
      label     = entry.label,
    }
  end
end

-- Parse a v6 literal → array of 8 hextets (each 0..65535), or nil on
-- malformed input. We deliberately keep each hextet as a 16-bit Lua double
-- (NOT a 128-bit int / bit32) so the prefix math below works on Lua 5.1,
-- which has neither. `::` compression is expanded to fill the address to 8
-- groups; a leading/trailing/embedded `::` is handled. IPv4-embedded forms
-- (`::ffff:1.2.3.4`) are NOT supported — the curated map is pure hex, so any
-- dot is treated as malformed.
local function parse_group_list(s)
  -- "" → empty list (the side of a leading/trailing `::`). A dangling colon
  -- or an empty / over-long / non-hex token is malformed.
  local out = {}
  if s == "" then return out end
  if s:sub(1, 1) == ":" or s:sub(-1) == ":" then return nil end
  local start = 1
  while true do
    local colon = s:find(":", start, true)
    local tok = colon and s:sub(start, colon - 1) or s:sub(start)
    -- 1..4 hex digits ⇒ a hextet ≤ 0xFFFF (fits a double). Rejects "",
    -- non-hex ("gggg"), and >16-bit ("12345") tokens.
    if not tok:match("^%x%x?%x?%x?$") then return nil end
    out[#out + 1] = tonumber(tok, 16)
    if not colon then break end
    start = colon + 1
  end
  return out
end

local function ipv6_to_hextets(ip)
  if type(ip) ~= "string" then return nil end
  if ip:find(".", 1, true) then return nil end -- no IPv4-embedded form
  local dc_start, dc_end = ip:find("::", 1, true)
  if dc_start then
    -- A second "::" is illegal.
    if ip:find("::", dc_end + 1, true) then return nil end
    local left = parse_group_list(ip:sub(1, dc_start - 1))
    local right = parse_group_list(ip:sub(dc_end + 1))
    if not left or not right then return nil end
    local nfill = 8 - (#left + #right)
    if nfill < 1 then return nil end -- "::" must stand for ≥1 zero group
    local hextets = {}
    for i = 1, #left do hextets[#hextets + 1] = left[i] end
    for _ = 1, nfill do hextets[#hextets + 1] = 0 end
    for i = 1, #right do hextets[#hextets + 1] = right[i] end
    return hextets
  end
  local groups = parse_group_list(ip)
  if not groups or #groups ~= 8 then return nil end
  return groups
end

-- Parse "<v6 addr>/N" → { hextets = {..8}, full, rem }, or nil on malformed.
-- `full` = number of whole 16-bit hextets the prefix covers; `rem` = the
-- leftover bit count in the partial hextet. The partial network hextet is
-- masked here at compile time so the hot path is a plain equality compare.
local function parse_cidr_v6(cidr)
  local addr, prefix = cidr:match("^(.+)/(%d+)$")
  if not addr then return nil end
  local hextets = ipv6_to_hextets(addr)
  prefix = tonumber(prefix)
  -- prefix=0 would match every v6 address (::/0); reject it for the same
  -- reason as the v4 /0 guard above. Valid v6 prefix is 1..128.
  if not hextets or not prefix or prefix < 1 or prefix > 128 then return nil end
  local full = math.floor(prefix / 16)
  local rem = prefix % 16
  if rem > 0 then
    local shift = 2 ^ (16 - rem)
    hextets[full + 1] = hextets[full + 1] - (hextets[full + 1] % shift)
  end
  return { hextets = hextets, full = full, rem = rem }
end

-- Pre-compile the v6 ranges once at module load, parallel-array style like
-- compiled_v4 so first-match-wins ordering holds. Malformed entries are
-- skipped (mirrors the v4 compile step).
local compiled_v6 = {}
for _, entry in ipairs(M._ranges_v6) do
  local parsed = parse_cidr_v6(entry.cidr)
  if parsed then
    compiled_v6[#compiled_v6 + 1] = {
      hextets = parsed.hextets,
      full    = parsed.full,
      rem     = parsed.rem,
      label   = entry.label,
    }
  end
end

-- lookup(ip) → (label, source) | (nil)
--
-- Returns the first matching (label, M.SOURCE) pair, or nil. Handles both
-- v4 and v6 literals (a colon selects the v6 path). Same M.SOURCE wire
-- string for both families.
--
-- Returning the source alongside the label keeps the caller from having to
-- know which module produced the attribution: build_event can branch on the
-- presence of a source value to emit type='label' vs type='fqdn'.
function M.lookup(ip)
  if type(ip) ~= "string" or ip == "" then return nil end
  -- v6 literals always contain a colon.
  if ip:find(":", 1, true) then
    local addr = ipv6_to_hextets(ip)
    if not addr then return nil end
    for _, range in ipairs(compiled_v6) do
      local net = range.hextets
      local ok = true
      -- Compare the full hextets the prefix covers.
      for i = 1, range.full do
        if addr[i] ~= net[i] then ok = false; break end
      end
      -- Then the partial hextet under a modulo mask, if the prefix isn't a
      -- whole multiple of 16 (no-op for the /128 host entries here).
      if ok and range.rem > 0 then
        local shift = 2 ^ (16 - range.rem)
        local masked = addr[range.full + 1] - (addr[range.full + 1] % shift)
        if masked ~= net[range.full + 1] then ok = false end
      end
      if ok then return range.label, M.SOURCE end
    end
    return nil
  end
  local n = ipv4_to_uint(ip)
  if not n then return nil end
  for _, range in ipairs(compiled_v4) do
    -- Bitwise AND via float math: (n & mask) == network ⇔
    --   n - (n % blocksize) == network, where blocksize = 2^(32-prefix).
    -- Equivalent and avoids requiring bit32 on Lua 5.1.
    if (n - (n % range.blocksize)) == range.network then
      return range.label, M.SOURCE
    end
  end
  return nil
end

return M
