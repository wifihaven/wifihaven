-- lan_warn.lua — agent-startup misconfiguration sniffer (#1174)
--
-- The original #1174 incident reproduced as: a prod router enrolled against a
-- LAN-address API URL (e.g. http://api.lan:8080 / http://192.168.10.43:8080)
-- with NO block_page_url set. resolve_base then falls the block-page redirect
-- back to api_url, so the kid's blocked traffic gets a 302 to a LAN host the
-- kid's browser can't reach (or shouldn't reach in a cloud deploy).
--
-- A self-hosted install legitimately enrols against api.lan and bundles the
-- SPA into the API image, so we cannot HARD-fail this — that's a real shape we
-- support. The cheapest defence is a clear startup log line the operator can
-- spot in `logread` / on the install console.
--
-- Pure helper: `check(api_url, block_page_url) → message?`. Returns the
-- warning string when the combination is suspicious, nil otherwise. The agent
-- calls this once at startup and routes the message through log.info; the
-- helper is pure so it can be busted-tested without UCI / logging stubs.

local M = {}

-- Strip the scheme + leading slashes, port, and trailing path so we're left
-- with the bare host. Returns "" for unparsable input. Intentionally permissive
-- — the agent already validates api_url before using it; we just want the host.
local function host_of(url)
  if type(url) ~= "string" or url == "" then return "" end
  local h = url:gsub("^%s+", ""):gsub("%s+$", "")
  h = h:gsub("^[%w%+%-%.]+://", "")
  h = h:gsub("/.*$", "")
  -- IPv6 literals are wrapped in [ ]; strip them before the port split.
  if h:sub(1, 1) == "[" then
    local close = h:find("]", 1, true)
    if close then return h:sub(2, close - 1) end
    return ""
  end
  h = h:gsub(":%d+$", "")
  return h
end
M.host_of = host_of

-- Return the four IPv4 octets as integers, or nil if `h` isn't a dotted-quad.
local function ipv4_octets(h)
  local a, b, c, d = h:match("^(%d+)%.(%d+)%.(%d+)%.(%d+)$")
  if not a then return nil end
  a, b, c, d = tonumber(a), tonumber(b), tonumber(c), tonumber(d)
  if not (a and b and c and d) then return nil end
  if a > 255 or b > 255 or c > 255 or d > 255 then return nil end
  return a, b, c, d
end

-- True iff `h` is an IPv4 address in RFC1918 private space:
--   10.0.0.0/8 · 172.16.0.0/12 · 192.168.0.0/16
local function is_private_ipv4(h)
  local a, b = ipv4_octets(h)
  if not a then return false end
  if a == 10 then return true end
  if a == 172 and b >= 16 and b <= 31 then return true end
  if a == 192 and b == 168 then return true end
  return false
end
M.is_private_ipv4 = is_private_ipv4

-- True iff the host should be treated as LAN-only: a private IPv4 literal, or
-- a multicast-DNS / OpenWRT-style local hostname (.lan / .local / localhost).
-- We deliberately do NOT resolve DNS here — the agent runs this once at
-- startup and a resolver lookup would pull in more failure modes than the
-- warning is worth. The hostname suffixes catch the common shapes (api.lan,
-- foo.local, localhost) without paying for resolution.
local function is_lan_host(h)
  if h == "" then return false end
  if h == "localhost" then return true end
  if is_private_ipv4(h) then return true end
  local lower = h:lower()
  if lower:sub(-4) == ".lan"   then return true end
  if lower:sub(-6) == ".local" then return true end
  return false
end
M.is_lan_host = is_lan_host

-- check(api_url, block_page_url) → string | nil
--
-- Warning fires iff:
--   1. api_url resolves (textually) to a LAN host, AND
--   2. block_page_url is unset / empty.
--
-- (When block_page_url IS set, the operator has explicitly picked the
-- redirect host, so the LAN api_url is fine: that's the self-hosted shape.)
function M.check(api_url, block_page_url)
  local h = host_of(api_url)
  if not is_lan_host(h) then return nil end
  if block_page_url and block_page_url ~= "" then return nil end
  return string.format(
    "wifihaven: api_url resolves to a LAN address (%s); the block-page redirect will point there. " ..
    "If this router is intended for production, re-enroll against the public API URL and set " ..
    "wifihaven.block_page_url.",
    h)
end

return M
