-- resolver.lua — the agent's one DNS lookup path, via BusyBox `nslookup`.
--
-- #2782. Two places in the agent resolve a hostname against the local dnsmasq:
-- `eb_refresh`'s periodic re-resolve of extraBlocked / blocklist hosts (#1658)
-- and `policy.apply`'s post-apply smoke probe (#328/#351). Both shelled out to
-- `dig`, independently. OpenWRT does not ship `dig` — it lives in `bind-dig`,
-- which openwrt/Makefile's DEPENDS never listed — so on every router both calls
-- resolved nothing, forever:
--
--   * `eb_refresh` added no elements. A host in extraBlocked is enforced by
--     `ip daddr @eb_<host>`, populated at client-resolve time by dnsmasq's
--     `--nftset=` callback and aged out by the kernel after 1h. A client whose
--     DNS cache outlives that window connects to a cached IP that is no longer
--     in the set, and the drop predicate misses. `eb_refresh` exists to top the
--     set back up; it never did. Prod 2026-09-13: `eb_www_amazon_com` empty
--     while `www.amazon.com` sat in Octavius's extraBlocked, and the iPad pulled
--     7.28 MB from it before a fresh resolve re-armed the set 8.5 minutes later.
--   * `policy.apply`'s smoke probe read the empty answer as "dnsmasq is serving
--     a stale sinkhole config" and warned. Prod: 123 smoke_warn vs 27 ok.
--
-- Worse than broken: silent. `default_dig` sent stderr to /dev/null and returned
-- the empty string, and the old `if not v4_out and not v6_out then return nil`
-- guard let it through, because an empty string is truthy in Lua. Every one of
-- prod's 179,349,241 resolves was counted `resolves_ok`. Per AGENTS.md
-- §no-dark-by-default a missing dependency is a bug, not a disable switch, so
-- this module makes "the resolver could not run" a distinct, loud outcome.
--
-- Why BusyBox `nslookup` and not a `bind-dig` dependency: `nslookup` is in the
-- BusyBox applet set on every OpenWRT image, so the safety net cannot go dark
-- again on a router that failed to pull one extra package, and `bind-dig` drags
-- the bind libraries onto a flash-constrained device for one lookup.
--
-- No per-query timeout is set, and none is available: BusyBox `nslookup` takes
-- no timeout/retry flags (v1.37.0 usage string) and OpenWRT ships no `timeout`
-- binary or applet, so the `+time=2 +tries=1` the old `dig` call carried has no
-- direct replacement. BusyBox's own bound is 5s per query, measured against a
-- blackholed server (192.0.2.1). That is why the tick-guard ordering still
-- holds: `eb_refresh.refresh` checks its deadline BETWEEN hosts, so one
-- pathological host can overrun a slice by one host — two queries, ~10s — and
-- 2s (eb_refresh_slice) + 10s = 12s stays under tick_step_budget (15s). It
-- holds because the sweep walks the AUTHORED hosts only; the blocklist catalog
-- is gated off (`eb_refresh_blocklists`, see the agent), and a second pass in
-- the same tick would have doubled that overrun past the budget.
--
-- Queries go to 127.0.0.1:53 — the local dnsmasq — deliberately: that is the
-- same resolution path that drives the `--nftset=` callback, so a lookup here
-- populates the kernel set as a side effect as well as returning the addresses
-- we add explicitly.
--
-- Pure logic with an injected `io.popen`-shaped reader, same DI shape as
-- dns_tail_sets / policy / eb_refresh.

local dns_tail_sets = require("wifihaven.dns_tail_sets")

local M = {}

-- ---------------------------------------------------------------------------
-- Command construction
-- ---------------------------------------------------------------------------

-- Hostname allow-list: a-z, 0-9, dot, hyphen, underscore (some dnsmasq aliases
-- contain underscores; see #1572). Anything else is rejected so the shell-out
-- stays hermetic — nothing reaches `io.popen` that we have not vetted. This is
-- the single vetting seam for both callers now, so it also rejects a LEADING
-- hyphen: no shell metacharacter survives the allow-list, but `-foo` would
-- reach `nslookup` as an OPTION rather than as a hostname. A real hostname
-- cannot start with one anyway (RFC 1123 §2.1).
function M.safe_host(host)
  if type(host) ~= "string" or host == "" then return nil end
  if host:find("[^%w%.%-_]") then return nil end
  if host:sub(1, 1) == "-" then return nil end
  return host
end

-- The `nslookup` invocation for one (host, qtype). nil when the host fails the
-- allow-list. stderr is discarded: a resolver that cannot run is detected from
-- stdout (see `answered`), not from its error text.
function M.command(host, qtype)
  local safe = M.safe_host(host)
  if not safe then return nil end
  return string.format("nslookup -type=%s %s 127.0.0.1 2>/dev/null", qtype, safe)
end

-- ---------------------------------------------------------------------------
-- Output parsing
-- ---------------------------------------------------------------------------

-- BusyBox `nslookup` prints a server block, then the answer section:
--
--   Server:         127.0.0.1
--   Address:        127.0.0.1:53
--                                        <- blank line ends the server block
--   Non-authoritative answer:
--   www.amazon.com  canonical name = e15316.dsca.akamaiedge.net
--   Name:   e15316.dsca.akamaiedge.net
--   Address: 23.47.202.67
--
-- The server block carries an `Address:` line too, so `Address:` alone is not a
-- safe key — crediting `127.0.0.1` to a blocked host's drop set would put the
-- ROUTER in it. Answers are only read once the answer section has started,
-- latched on the first `Non-authoritative answer:` / `Name:` line. CNAME lines
-- are ignored; we want the addresses the client will connect to, whatever chain
-- leads there.
--
-- `answered` (below) is what separates "no records" from "no resolver", so this
-- returning {} is never by itself a failure signal.
function M.parse_nslookup(stdout, family)
  if type(stdout) ~= "string" or stdout == "" then return {} end
  local out, seen = {}, {}
  local in_answer = false
  for line in stdout:gmatch("[^\r\n]+") do
    if not in_answer then
      -- The server block has no `Name:` line; the answer section starts at the
      -- first `Non-authoritative answer:` / `Name:` we see.
      if line:match("^Non%-authoritative answer:") or line:match("^Name:") then
        in_answer = true
      end
    end
    if in_answer then
      -- `Address 1:` / `Address 2:` is the older BusyBox form; accepting both
      -- costs nothing and keeps an older image off the empty_answer path.
      local ip = line:match("^Address%s*%d*:%s+(%S+)$")
      local safe = ip and dns_tail_sets.safe_addr(ip)
      if safe then
        local is_v6 = safe:find(":", 1, true) ~= nil
        if (family == "v6") == is_v6 and not seen[safe] then
          seen[safe] = true
          out[#out + 1] = safe
        end
      end
    end
  end
  -- Sorted, matching the `parse_dig_output` this replaces. nft does not care
  -- about add order; determinism keeps specs and logs stable.
  table.sort(out)
  return out
end

-- True when the resolver actually ran. BusyBox `nslookup` always prints its
-- `Server:` header, NXDOMAIN included; a missing binary writes only to stderr,
-- so stdout comes back empty. That is the discriminator, and it is the whole
-- reason #2782 could hide: without it, "cannot run" and "no records" are the
-- same value.
function M.answered(stdout)
  if type(stdout) ~= "string" then return false end
  return stdout:find("^Server:") ~= nil or stdout:find("\nServer:") ~= nil
end

-- ---------------------------------------------------------------------------
-- Lookup
-- ---------------------------------------------------------------------------

local function run(host, qtype, popen_fn)
  local cmd = M.command(host, qtype)
  if not cmd then return nil end
  local popen = popen_fn or io.popen
  local f = popen(cmd, "r")
  if not f then return nil end
  local out = f:read("*a")
  f:close()
  return out
end

-- resolve(host, popen_fn) → { v4 = {...}, v6 = {...} } | nil
--
-- nil means the resolver could not run (missing binary, rejected hostname,
-- popen failure) — the caller should count that as an error, not as an answer.
-- Empty lists mean the resolver answered and there are no records for that
-- family (NXDOMAIN, or a v4-only host queried for AAAA).
function M.resolve(host, popen_fn)
  local v4_out = run(host, "A", popen_fn)
  local v6_out = run(host, "AAAA", popen_fn)
  if not M.answered(v4_out) and not M.answered(v6_out) then return nil end
  return {
    v4 = M.parse_nslookup(v4_out, "v4"),
    v6 = M.parse_nslookup(v6_out, "v6"),
  }
end

-- first_address(host, popen_fn) → string | nil
--
-- The first A record, for `policy.apply`'s post-apply smoke probe. That probe
-- treats nil and "" alike as "we are not getting a real upstream answer", so it
-- does not need the resolver-ran/no-records split `resolve` makes.
function M.first_address(host, popen_fn)
  local out = run(host, "A", popen_fn)
  if not M.answered(out) then return nil end
  return M.parse_nslookup(out, "v4")[1]
end

return M
