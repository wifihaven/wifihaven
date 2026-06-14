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
  if not ip or not prefix or prefix < 0 or prefix > 32 then return nil end
  local mask = (prefix == 0) and 0 or (0xFFFFFFFF - (2 ^ (32 - prefix) - 1))
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

-- lookup(ip) → (label, source) | (nil)
--
-- Returns the first matching (label, M.SOURCE) pair, or nil. v4-only for
-- now; v6 is parsed off (returns nil) so callers can wire it in safely.
-- To add a v6 entry later, extend M._ranges with `family = "v6"` rows and
-- add an ipv6_to_uint128 path here.
--
-- Returning the source alongside the label keeps the caller from having to
-- know which module produced the attribution: build_event can branch on the
-- presence of a source value to emit type='label' vs type='fqdn'.
function M.lookup(ip)
  if type(ip) ~= "string" or ip == "" then return nil end
  -- v6 literals always contain a colon; punt for now (returns nil).
  if ip:find(":", 1, true) then return nil end
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
