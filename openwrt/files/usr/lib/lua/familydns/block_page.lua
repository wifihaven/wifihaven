-- block_page.lua — pure helpers for the uhttpd-mod-lua block-page handler.
--
-- The static index.html previously served at /www/familydns/index.html cannot
-- know which device requested it (the nft DNAT rewrites the destination only;
-- the original URL is whatever the client typed). This module looks up the
-- client MAC from /proc/net/arp using REMOTE_ADDR and the policy reason from
-- the agent-written reasons file, then renders an HTML page that redirects to
-- the API's /blocked page with mac and reason populated.

local M = {}

-- Parse /proc/net/arp content and return the MAC associated with `ip`, or nil.
-- The file format is whitespace-separated, one header line + entries:
--   IP address       HW type     Flags       HW address            Mask     Device
--   192.168.1.10     0x1         0x2         aa:bb:cc:11:22:33     *        br-lan
function M.parse_arp(arp_content, ip)
  if not arp_content or not ip or ip == "" then return nil end
  for line in arp_content:gmatch("[^\n]+") do
    local fields = {}
    for f in line:gmatch("%S+") do table.insert(fields, f) end
    if fields[1] == ip
       and fields[4]
       and fields[4]:match("^%x%x:%x%x:%x%x:%x%x:%x%x:%x%x$")
       and fields[4] ~= "00:00:00:00:00:00" then
      return fields[4]:lower()
    end
  end
  return nil
end

-- Parse the reasons file (lines of "<mac>\t<reason>") and return the reason
-- for `mac`, or nil. Mac comparison is case-insensitive.
function M.parse_reasons(content, mac)
  if not content or not mac then return nil end
  local target = mac:lower()
  for line in content:gmatch("[^\n]+") do
    local m, r = line:match("^(%S+)%s+(%S+)$")
    if m and m:lower() == target then return r end
  end
  return nil
end

local function html_escape(s)
  s = tostring(s or "")
  return (s:gsub("&", "&amp;")
           :gsub("<", "&lt;")
           :gsub(">", "&gt;")
           :gsub('"', "&quot;")
           :gsub("'", "&#39;"))
end

local function url_encode(s)
  return (tostring(s or ""):gsub("[^%w%-_.~]", function(c)
    return string.format("%%%02X", string.byte(c))
  end))
end

M.html_escape = html_escape
M.url_encode  = url_encode

-- Build the destination URL on the API server. Reason and mac are passed
-- through as-is; the API's React BlockedPage matches against the wire-format
-- MacBlockReason strings ("Paused", "Schedule", "TimeLimit", "Manual").
function M.build_dest_url(api_url, host, mac, reason)
  if not api_url or api_url == "" then return nil end
  return api_url .. "/blocked"
      .. "?host="   .. url_encode(host)
      .. "&reason=" .. url_encode(reason or "")
      .. "&mac="    .. url_encode(mac or "")
end

-- Inline copy used when API_URL is not (yet) configured. Mirrors the
-- BlockedPage.tsx mapping so the local fallback page still communicates the
-- reason instead of generic "blocked".
local INLINE_COPY = {
  Paused    = "This profile is paused.",
  Schedule  = "This is scheduled quiet time.",
  TimeLimit = "Daily screen time limit reached.",
  Manual    = "This device has been blocked by a parent.",
}

function M.inline_copy_for(reason)
  return INLINE_COPY[reason or ""] or "This site is blocked."
end

-- Render the HTML body for the block page. If api_url is set, returns a tiny
-- redirect document (meta refresh + JS for compatibility). Otherwise returns a
-- self-contained page with inline copy keyed on `reason`.
function M.render_html(api_url, host, mac, reason)
  local dest = M.build_dest_url(api_url, host, mac, reason)
  if dest then
    return ([[<!DOCTYPE html>
<html lang="en"><head>
<meta charset="utf-8">
<title>Blocked</title>
<meta http-equiv="refresh" content="0;url=%s">
</head><body>
<script>window.location.replace(%q);</script>
<p>Redirecting...</p>
</body></html>
]]):format(html_escape(dest), dest)
  end

  local copy = M.inline_copy_for(reason)
  local site_line = (host and host ~= "")
    and ("Site: " .. html_escape(host)) or ""
  return ([[<!DOCTYPE html>
<html lang="en"><head>
<meta charset="utf-8">
<title>Blocked</title>
<style>body{font-family:sans-serif;display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0;background:#f5f5f5}.card{background:#fff;border-radius:8px;padding:2rem 2.5rem;max-width:400px;box-shadow:0 2px 8px rgba(0,0,0,.12);text-align:center}h1{color:#c0392b;margin-top:0}p{color:#555;line-height:1.6}</style>
</head><body>
<div class="card">
<h1>&#128683; Blocked</h1>
<p>%s</p>
<p><small>%s</small></p>
</div></body></html>
]]):format(html_escape(copy), site_line)
end

return M
