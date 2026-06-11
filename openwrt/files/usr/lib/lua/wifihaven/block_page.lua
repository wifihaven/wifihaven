-- block_page.lua — pure helpers for the uhttpd-mod-lua block-page handler.
--
-- The static index.html previously served at /www/wifihaven/index.html cannot
-- know which device requested it (the nft DNAT rewrites the destination only;
-- the original URL is whatever the client typed). This module looks up the
-- client MAC from /proc/net/arp using REMOTE_ADDR and renders an HTML page
-- that redirects to the SPA's /blocked page carrying only mac and host — the
-- SPA derives the canonical reason server-side from GET /api/blocked
-- (PolicyService.decide), so the router no longer ships a reason through
-- this path (#1615/#1617/#1618).

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

-- Resolve the block-page redirect base. The block-page (SPA /blocked route)
-- host is deployment config, distinct from the API URL the router polls: in
-- the cloud deploy the SPA lives on a different host (wifihaven.net /
-- post-#1171 app.wifihaven.net) than the API (api.wifihaven.net), so the
-- redirect must target the SPA host, not api_url. Falls back to api_url when
-- the block-page URL is unset — the self-hosted / back-compat case, where the
-- SPA is bundled into the API image and served on the same host. (#1174)
function M.resolve_base(block_page_url, api_url)
  if block_page_url and block_page_url ~= "" then return block_page_url end
  return api_url
end

-- Build the destination URL on the block-page host. Only host and mac are
-- carried — the SPA's React BlockedPage derives the canonical reason
-- server-side from GET /api/blocked (PolicyService.decide), so the router no
-- longer needs to pass it through the URL (#679 / #1617). `base_url` is the
-- resolved block-page host (see resolve_base), not necessarily the API URL.
-- (#1174)
function M.build_dest_url(base_url, host, mac)
  if not base_url or base_url == "" then return nil end
  return base_url .. "/blocked"
      .. "?host=" .. url_encode(host)
      .. "&mac="  .. url_encode(mac or "")
end

-- Render the HTML body for the block page. If base_url is set, returns a tiny
-- redirect document (meta refresh + JS for compatibility). Otherwise returns a
-- self-contained page with neutral block copy. The reason-specific copy moved
-- API-side: the SPA reads GET /api/blocked and renders the canonical message
-- there (#679 / #1617). `base_url` is the resolved block-page host (see
-- resolve_base), not necessarily the API URL.
function M.render_html(base_url, host, mac)
  local dest = M.build_dest_url(base_url, host, mac)
  local copy = "This site is blocked."
  local site_line = (host and host ~= "")
    and ("Site: " .. html_escape(host)) or ""
  -- Shared inline style used by both paths so the page is always visible.
  -- min-height:100vh without flex avoids the iOS Safari height-collapse bug
  -- that occurs when a flex container has no explicit height (#580).
  local style = "body{font-family:sans-serif;margin:0;background:#f5f5f5;display:table;width:100%;min-height:100vh}.wrap{display:table-cell;vertical-align:middle;padding:2rem}.card{background:#fff;border-radius:8px;padding:2rem 2.5rem;max-width:400px;margin:0 auto;box-shadow:0 2px 8px rgba(0,0,0,.12);text-align:center}h1{color:#c0392b;margin-top:0}p{color:#555;line-height:1.6}"
  if dest then
    -- Redirect to the API's /blocked page for richer React UI.
    -- The page shows inline content immediately so iOS Safari users see
    -- something even if the cross-origin redirect to an RFC1918 host is
    -- blocked by the browser (#580).
    return ([[<!DOCTYPE html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Blocked</title>
<meta http-equiv="refresh" content="0;url=%s">
<style>%s</style>
</head><body>
<script>window.location.replace(%q);</script>
<div class="wrap"><div class="card">
<h1>&#128683; Blocked</h1>
<p>%s</p>
<p><small>%s</small></p>
</div></div>
</body></html>
]]):format(html_escape(dest), style, dest, html_escape(copy), site_line)
  end

  return ([[<!DOCTYPE html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Blocked</title>
<style>%s</style>
</head><body>
<div class="wrap"><div class="card">
<h1>&#128683; Blocked</h1>
<p>%s</p>
<p><small>%s</small></p>
</div></div>
</body></html>
]]):format(style, html_escape(copy), site_line)
end

return M
