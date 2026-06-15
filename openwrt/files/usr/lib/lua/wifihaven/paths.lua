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

-- CNAME-alias map snapshot (#1572): wifihaven-dns-tail writes in lockstep with
-- dns_cache and reloads it on startup via cache.seed_aliases. Persists the
-- target→branded-head edges learned from observed CNAME chains so a sidecar
-- restart does not lose the ability to attribute a directly-queried CDN edge
-- name back to the brand the client originally typed. Same atomic-write/
-- tmpfs-IPC pattern as dns_cache; the agent does not read this file.
M.dns_aliases = "/tmp/wifihaven-dns-aliases.txt"

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

-- SNI capture spool (#573): wifihaven-sni-tail parses the first packet of every
-- outbound TCP/443 flow off the LAN bridge, extracts the TLS ClientHello SNI,
-- and appends one "SNI\t<dst_ip>\t<src_mac>\t<server_name>" line per capture to
-- this tmpfs file. wifihaven-dns-tail tails it alongside the dnsmasq query log
-- and routes SNI-prefixed lines through cache.insert_sni, so the shared
-- ip→hostname cache (paths.dns_cache) stays single-writer (dns-tail). This
-- improves connection-event fqdn ATTRIBUTION only (cache.lookup); it does NOT
-- feed the eb_/ea_/bl_ nft drop-set populators, which fire solely on dnsmasq
-- `reply <name> is <ip>` lines — SNI is observed after the flow's first packet,
-- too late to populate a drop set for that flow.
M.sni_capture = "/tmp/wifihaven-sni.log"

-- SNI capture result tally (#573): wifihaven-sni-tail writes cumulative
-- per-result counts ("<result>\t<count>" lines, same format as dns_metrics) on
-- each periodic flush; wifihaven-agent samples it on its metrics tick and folds
-- the deltas into sni_clienthellos_total{result}. result ∈ {parsed, truncated,
-- no_sni, not_ip, not_tcp, malformed, ech (#1650)} (pre-#1652 agents also emit ipv6_skipped,
-- ageing out as the fleet rolls forward). Same tmpfs-IPC pattern as
-- dns_metrics — the sidecar has no metrics registry of its own.
M.sni_metrics = "/tmp/wifihaven-sni-metrics.txt"

-- nflog drop spool (#1126): wifihaven-nflog-tail tails `logread -f`, greps the
-- nft `log prefix "wh_drop:…"` forward-drop records, and appends them here; the
-- main agent drains new complete lines from this tmpfs spool on its cooperative
-- tick (nflog.drain_file) and synthesizes connection_attempt events. Same
-- own-the-blocking-read / tmpfs-IPC split as the dns-tail cache (#259), so the
-- agent never needs non-blocking I/O.
M.nflog_drops = "/tmp/wifihaven-nflog.log"

return M
