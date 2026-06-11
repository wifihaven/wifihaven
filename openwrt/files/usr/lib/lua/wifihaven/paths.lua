-- Shared IPC paths used by the wifihaven agent, dns-tail sidecar, and the
-- uhttpd block-page handler. Centralised here (#738) so a future rename
-- touches one file instead of three — handler.lua in particular lives under
-- /www and is easy to miss.
local M = {}

-- Block-page IPC: the agent writes the API URL after every policy apply,
-- the uhttpd lua handler reads it on each request. The per-MAC reason and
-- per-(MAC, host) classifier files (#437 / #594) were removed in #1618 —
-- post-#1615/#1617 the SPA derives the canonical reason from
-- GET /api/blocked, so the on-disk IPC has no readers left.
M.block_page_api_url  = "/var/run/wifihaven/api_url"

-- ip → hostname cache (#259): wifihaven-dns-tail writes, wifihaven-agent reads.
M.dns_cache = "/tmp/wifihaven-dns-cache.txt"

-- DNS query-result tally (#1302): wifihaven-dns-tail writes cumulative
-- per-result counts ("<result>\t<count>" lines) on each flush; wifihaven-agent
-- samples it on its metrics tick and folds the deltas into
-- dns_queries_total{result} for the /api/router/metrics push. The sidecar has
-- no metrics registry of its own, so this tmpfs file is the IPC (same pattern
-- as dns_cache).
M.dns_metrics = "/tmp/wifihaven-dns-metrics.txt"

-- blocklist member → bl_ set index (#1348): policy.apply writes after each
-- snapshot apply (in lockstep with /tmp/dnsmasq.d/wifihaven.conf), the
-- wifihaven-dns-tail bl_ populator reads it on its set-refresh cadence.
M.bl_member_index = "/tmp/wifihaven-bl-members.txt"

-- nflog drop spool (#1126): wifihaven-nflog-tail tails `logread -f`, greps the
-- nft `log prefix "wh_drop:…"` forward-drop records, and appends them here; the
-- main agent drains new complete lines from this tmpfs spool on its cooperative
-- tick (nflog.drain_file) and synthesizes connection_attempt events. Same
-- own-the-blocking-read / tmpfs-IPC split as the dns-tail cache (#259), so the
-- agent never needs non-blocking I/O.
M.nflog_drops = "/tmp/wifihaven-nflog.log"

return M
