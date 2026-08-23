-- policy.lua — policy snapshot loader and atomic config applier
--
-- #2736: `policy.fetch` (GET /api/router/policy) is GONE. The agent is
-- websocket-only: the ws sidecar receives pushed snapshots and persists them to
-- /etc/wifihaven/policy.json, and this module's job is to load that file and
-- apply it. The server still SERVES the REST endpoint — retiring it is #1850,
-- and it must wait until the whole fleet has self-updated (the router↔API wire
-- is a public contract; a router that stops CALLING an endpoint the server
-- still serves is the safe direction, the reverse is not).
--
-- Public API:
--   policy.apply(snapshot, write_fn, reload_fn, log, opts)
--     → bool (true on success)
--     write_fn(path, content) → ok, err
--     reload_fn(cmd)          → exit_code
--     opts (optional table)   → bag of options. Recognised keys:
--       opts.read_fn(path) → string|nil — used to read the on-disk copy of
--                                   the rendered dnsmasq.conf for the
--                                   change-detection check (#414). Defaults
--                                   to a plain io.open read.
--       opts.dns_check_fn(domain) → string|nil — result of resolving `domain`
--                                   via the router's own resolver, used by
--                                   the #328 smoke probe. After #351 DNS no
--                                   longer sinkholes blocked hosts (Truth 1:
--                                   blocking is at the connection layer); the
--                                   probe now confirms that dnsmasq is
--                                   answering at all, by checking that an
--                                   extraBlocked host resolves to a real IP
--                                   (NOT 0.0.0.0 / :: / NXDOMAIN). A
--                                   sinkhole-shaped answer means dnsmasq
--                                   is still applying a stale rendering
--                                   (failed restart) and triggers a WARN.
--       opts.link_failed → forwarded to render.nft for the #385/#422
--                                 failover branch (three modes: block-all,
--                                 allow-all, last-known-good). True iff we are
--                                 out of contact with the API. Pre-#2736 that
--                                 meant "the last snapshot poll failed"; with
--                                 the poll gone it means the ws link's health
--                                 sentinel is stale or absent. Failover trips
--                                 immediately (no 300s cushion).

local M = {}

local render   = require("wifihaven.render")
local paths    = require("wifihaven.paths")
local dns_log  = require("wifihaven.dns_log")       -- #2095: dns_cache parse
local dns_sets = require("wifihaven.dns_tail_sets") -- #2095: ea_/ea6_ backfill

-- #2095: the apply-time ea_/ea6_ backfill consumes paths.dns_cache verbatim.
-- The dns-tail sidecar is the SINGLE authority on cache freshness — it drops
-- resolutions older than its own ttl (dns_tail_sets header / wifihaven-dns-tail
-- `dns_log.new{ttl_seconds=...}`) at dump time, so the on-disk file only ever
-- holds fresh entries. We deliberately do NOT re-encode that horizon here (it
-- would be a second copy of the 1h that could drift, #2018-class); load_table
-- is called with a permissive ttl so it accepts whatever the pre-filtered file
-- holds. Backfilling a briefly-stale IP into an ALLOW set is fail-closed-safe
-- anyway — worst case an explicitly-allowed host stays reachable a bit longer.
local DNS_CACHE_ACCEPT_ALL = math.huge

-- #2207: where `nft -f`'s stderr is captured so a rejected ruleset load is
-- diagnosable. tmpfs; truncated (`2>`) on every apply, so it is bounded to a
-- single apply's error output and needs no rotation (docs/process/
-- router-agent-bounded-writes.md).
local NFT_ERR_PATH = "/tmp/wifihaven-nft-err.log"

-- log is injectable for tests; default uses the real logger wrapper.
local function default_log()
  local ok, l = pcall(require, "wifihaven.log")
  if ok then return l end
  -- Fallback to a stderr shim when the module isn't on the path (e.g. older
  -- test harnesses).
  return {
    info  = function(fmt, ...) io.stderr:write(string.format(fmt .. "\n", ...)) end,
    err   = function(fmt, ...) io.stderr:write(string.format(fmt .. "\n", ...)) end,
    warn  = function(fmt, ...) io.stderr:write(string.format(fmt .. "\n", ...)) end,
    debug = function() end,
  }
end

-- ---------------------------------------------------------------------------
-- policy.apply
-- ---------------------------------------------------------------------------
-- write_fn(path, content) must return (truthy, nil) on success or (nil, err_string).
-- reload_fn(cmd) is called with a shell command string; return value is ignored.
--
-- opts is an optional table. opts.dns_check_fn (#328 / #351): when the
-- rendered dnsmasq.conf contains at least one `nftset=/<host>/...#eb_...`
-- line (i.e. there is something to enforce), we probe one of those hosts via
-- the router's own resolver after the dnsmasq restart. Post-#351 DNS is
-- NOT the enforcement plane — blocked hosts must resolve to a real IP, so
-- the probe inverts the old semantics: a sinkhole-shaped answer
-- (`0.0.0.0`, `::`, empty / NXDOMAIN) means dnsmasq is still running a
-- stale config that has DNS-layer blocks in it, which is the failure mode
-- we want to catch.
-- opts.link_failed (#331/#422/#2736): forwarded to render.nft so the agent's
-- failover-edge re-render can request the closed-mode drop chain the moment we
-- lose contact with the API (and clear it on recovery).

-- Default reader for the change-detection check (#414). Returns nil if the
-- file is absent — first apply on a fresh boot treats that as "changed".
local function default_read(path)
  local f = io.open(path, "r")
  if not f then return nil end
  local content = f:read("*a")
  f:close()
  return content
end

-- Default smoke probe: `dig @127.0.0.1` and return the first line of stdout.
local function default_dns_check(domain)
  local cmd = string.format(
    "dig @127.0.0.1 -p 53 %s +short +time=2 +tries=1 2>/dev/null",
    domain)
  local f = io.popen(cmd, "r")
  if not f then return nil end
  local out = f:read("*l")
  f:close()
  return out
end

-- Sinkhole-shaped DNS answers. Post-#351 these are *failures* (DNS must
-- resolve normally — blocking happens at the connection layer), but we
-- still need to recognise the shape to detect a dnsmasq that's running a
-- stale, sinkhole-containing config.
local BLOCKED_RESULTS = {
  ["0.0.0.0"] = true,
  ["::"]      = true,
  ["::0"]     = true,
}

-- True when the DNS answer indicates the resolver is *blocking* at the
-- DNS layer (sinkhole IP) or returning no answer at all (NXDOMAIN /
-- SERVFAIL / timeout → empty first line from `dig +short`). Both shapes
-- mean we are NOT getting a real upstream answer, which post-#351 is a
-- regression — dnsmasq should resolve every host normally and let nft
-- handle blocking.
local function is_blocked_at_connection(result)
  if result == nil or result == "" then return true end
  return BLOCKED_RESULTS[result] == true
end

-- Extract the first extraBlocked host from the rendered dnsmasq content by
-- looking for an `nftset=/<host>/...#eb_...` directive (the per-host set
-- name prefix is the agent's canonical marker for #351 enforcement; the
-- nftset= form replaces legacy ipset= post-OpenWRT-23.05 per #392). Returns
-- nil if there are no extraBlocked hosts active.
local function first_extrablocked_host(dnsmasq_content)
  return dnsmasq_content:match("\nnftset=/([^/\n]+)/[^\n]-#eb_")
      or dnsmasq_content:match("^nftset=/([^/\n]+)/[^\n]-#eb_")
end

-- True when a reload_fn / os.execute-style exit status indicates success.
-- os.execute returns 0 on Lua 5.1 and `true` on 5.2+; the agent's run_cmd
-- forwards either, and tests inject numeric exit codes directly.
local function exec_ok(rc)
  return rc == 0 or rc == true
end

-- #1792: default shard-existence check for render.dnsmasq's bl_shard_exists
-- hook. Module-level so the closure is allocated once, not per apply call.
-- Reads render.SHARD_DIR at call time (not at module-load) so tests that
-- mutate render.SHARD_DIR before driving policy.apply still see the override.
local function default_bl_shard_exists(id)
  local f = io.open(render.shard_path(id), "r")
  if f then f:close(); return true end
  return false
end

function M.apply(snapshot, write_fn, reload_fn, log, opts)
  log = log or default_log()
  opts = opts or {}
  -- #1206 metrics seam: an optional opts.on_apply(info) callback is invoked
  -- exactly once per apply with { result, dnsmasq_restarted }. This lets the
  -- agent increment policy_apply_total{result} and dnsmasq_restarts_total at
  -- the real restart call site without policy.lua depending on the metrics
  -- module. `result` is the apply outcome enum (write_failed / nft_failed /
  -- smoke_warn / ok); `dnsmasq_restarted` is true only when we actually issued
  -- a dnsmasq restart (false on the #414 nft-only short-circuit). The callback
  -- is for observability only — it never alters the boolean return.
  local on_apply = opts.on_apply
  -- #2208 apply-latency instrumentation: an optional opts.phase_timer(phase,
  -- seconds) is invoked once per internal phase with a BOUNDED phase name
  -- (render_dnsmasq / render_nft / dnsmasq_restart / nft_load /
  -- ea_backfill / smoke_probe). nil in tests/legacy callers → zero cost. The
  -- agent wires it to observe policy_apply_duration_seconds{phase} so per-phase
  -- apply latency is visible on the fleet dashboard.
  local phase_timer = opts.phase_timer
  local mono
  if phase_timer then
    local ok_clock, clk = pcall(require, "wifihaven.clock")
    mono = (ok_clock and clk.monotonic_seconds) or os.time
  end
  local function timed(name, fn)
    if not phase_timer then return fn() end
    local t0 = mono()
    local a, b, c = fn()
    pcall(phase_timer, name, mono() - t0)
    return a, b, c
  end
  -- #2095: ea_backfilled carries the count of ea_/ea6_ carve elements the
  -- apply-time backfill seeded (0 unless the final report). Lets the agent
  -- emit ea_carve_backfill_total so we can confirm in prod that the carve is
  -- actually being re-seeded after each apply (the #2094/#2095 fix firing).
  local function report(result, dnsmasq_restarted, ea_backfilled)
    if on_apply then
      pcall(on_apply, {
        result           = result,
        dnsmasq_restarted = dnsmasq_restarted,
        ea_backfilled    = ea_backfilled or 0,
      })
    end
  end
  -- #1792: gate per-blocklist conf-file= emission on whether the shard file
  -- actually exists on disk. blocklists.render_shards silently skips an id
  -- whose cache file is missing (fetch not yet completed, transient HTTP
  -- error, cap_hit); a dangling conf-file= ref aborts dnsmasq startup with
  -- "cannot read ..." and :53 returns "connection refused" (Gate 3a regression
  -- after #1788). Tests inject opts.bl_shard_exists; production uses the
  -- module-level default_bl_shard_exists which stats the canonical shard path.
  local bl_shard_exists = opts.bl_shard_exists or default_bl_shard_exists
  -- #2381: the local enforcement escape hatch. The agent sets this from the
  -- UCI flag wifihaven.settings.enforcement_disabled (read fresh each apply).
  -- When true, render.dnsmasq/render.nft emit a PERMISSIVE ruleset (no drops,
  -- no block-page DNAT, no nftset= populators) so all forwarded traffic passes
  -- regardless of snapshot freshness. render.nft already receives the full
  -- `opts`; render.dnsmasq gets a fresh opts table below, so forward the flag
  -- explicitly. Also skips the ea_ carve backfill (below) — the permissive
  -- ruleset declares no ea_ sets to seed.
  local enforcement_disabled = opts.enforcement_disabled and true or false
  local dnsmasq_content = timed("render_dnsmasq", function()
    return render.dnsmasq(snapshot, {
      enforcement_disabled = enforcement_disabled,
      bl_shard_exists = function(id)
        local ok = bl_shard_exists(id)
        if not ok then
          log.debug("policy.apply: omitted conf-file= for id %s (shard missing)", tostring(id))
        end
        return ok
      end,
    })
  end)
  local nft_content     = timed("render_nft", function()
    return render.nft(snapshot, opts)
  end)

  -- #414: dnsmasq only needs a full restart when its config-dir file
  -- actually changes (dhcp-host= and nftset= directives are NOT picked up
  -- on SIGHUP — see #328). Most policy.apply calls flip only nft-side
  -- state (blocked_macs membership from schedules / pause / time limits,
  -- failover transitions). Compare against the on-disk copy and skip the
  -- restart when the rendered content is byte-identical; this avoids a
  -- DNS service blip on every schedule boundary.
  local read_fn = opts.read_fn or default_read
  local existing_dnsmasq = read_fn("/tmp/dnsmasq.d/wifihaven.conf")
  local dnsmasq_changed = (existing_dnsmasq ~= dnsmasq_content)

  local ok1, err1 = write_fn("/tmp/dnsmasq.d/wifihaven.conf", dnsmasq_content)
  if not ok1 then
    log.err("policy.apply: write dnsmasq conf failed: %s", tostring(err1))
    report("write_failed", false)
    return false
  end

  local ok2, err2 = write_fn("/tmp/nftables.d/wifihaven.nft", nft_content)
  if not ok2 then
    log.err("policy.apply: write nft file failed: %s", tostring(err2))
    report("write_failed", false)
    return false
  end

  -- #1348: blocklist member → bl_ set index for the dns-tail bl_ populator.
  -- Post-#1782: this write is a no-op (the agent writes paths.bl_member_index
  -- from blocklists.render_member_index, which streams from the cache files).
  -- The old render.blocklist_member_index(snapshot) read from
  -- snapshot._blocklist_hosts which is no longer populated; emitting it here
  -- would overwrite the agent's populated index with an empty file on every
  -- policy.apply call. The agent calls refresh_blocklists → render_member_index
  -- → paths.bl_member_index BEFORE policy.apply so the index is ready when
  -- dnsmasq restarts. (#1782 BLOCKER fix)
  --
  -- (The index is written by the agent's refresh_blocklists, not here.)

  -- #2229: the dnsmasq restart is deferred until AFTER `nft -f` below. The
  -- enforcement plane (the whole-MAC drop, per-host drops, blocked_macs) lives
  -- ENTIRELY in the nft ruleset; the dnsmasq restart only propagates changed
  -- dhcp-host=/nftset= *directives* (which populate ipsets on the NEXT resolve).
  -- A pause toggles the profile's extraAllowed carve → its nftset= lines appear/
  -- disappear → dnsmasq_changed flips → a full ~3.6s (much more on prod)
  -- `/etc/init.d/dnsmasq restart`. When that restart ran FIRST it gated the
  -- block behind itself, so a pushed pause took seconds to actually drop traffic
  -- even though `nft -f` is ~0.1s (the #2229 latency). Loading nft first lands
  -- enforcement immediately and lets the (unavoidable) restart run without
  -- gating it. Ordering is safe: `nft -f` delete+recreates `table inet
  -- wifihaven`, so every ea_/eb_/bl_ set is empty right after it regardless of
  -- restart order; the ea_backfill below re-seeds ea_ from cache, and dnsmasq
  -- repopulates the rest lazily on resolve after the restart — identical end
  -- state, just enforcement-first.

  -- Single atomic `nft -f`. The rendered file's prelude removes both the
  -- boot default-deny skeleton (table inet wifihaven_boot — #308) and any
  -- prior runtime table in one transaction, then installs the new ruleset.
  --
  -- #2207: a failed `nft -f` is ATOMIC — the kernel keeps the PREVIOUS ruleset
  -- and the newly-rendered rules never take effect, even though the file on
  -- disk is correct. This function used to `return true` unconditionally, so a
  -- rejected load was invisible: the caller recorded the snapshot as applied,
  -- rebuilt its in-memory tables, and (over ws, poll-independent) advanced the
  -- applied etag — so the enforcement plane silently never changed and the
  -- apply was NEVER retried (etag dedup). That is exactly the ws-cutover
  -- failure in #2207 (a pushed pause "applied" cleanly yet the whole-MAC drop
  -- never rendered). We now capture nft's stderr, log the rejection loudly,
  -- and return the load outcome so every caller retries (they gate on the
  -- return and leave the applied etag unadvanced on false). The `2>` truncates
  -- the capture file each apply, keeping it bounded.
  local nft_rc = timed("nft_load", function()
    return reload_fn("nft -f /tmp/nftables.d/wifihaven.nft 2>" .. NFT_ERR_PATH)
  end)
  local nft_ok = exec_ok(nft_rc)
  if not nft_ok then
    local nft_err = (read_fn(NFT_ERR_PATH) or ""):gsub("%s+$", "")
    log.err("policy.apply: `nft -f` FAILED (rc=%s) — ruleset NOT loaded; the "
            .. "previous ruleset persists and enforcement is stale. Reporting "
            .. "the apply as failed so the caller retries. nft: %s",
            tostring(nft_rc), nft_err)
  end

  -- #2095: seed the per-(mac,host) extraAllowed carve sets (ea_/ea6_) from the
  -- persisted dns-tail ip->host cache immediately after the reload. The
  -- `nft -f` above delete+recreated `table inet wifihaven`, so every ea_/ea6_
  -- set is now EMPTY and only refills when the device next RESOLVES a carved
  -- host over the live query log (dns-tail tails with latency). A device
  -- holding a long-cached CDN IP (KaTeX/MathJax on cdn.jsdelivr.net) can
  -- reconnect before that refill and get caught by the whole-MAC drop even
  -- though the host IS in extraAllowed — the #2094 residual / #1929-class
  -- transient v6 drop. Backfilling from the recent-resolution cache closes
  -- that window for BOTH families. This does NOT teach the router any policy:
  -- it only makes the existing extraAllowed carve robust against a timing gap.
  -- Runs only when the reload succeeded and some MAC has a non-empty effective
  -- extraAllowed (skips the cache read + scan otherwise).
  --
  -- Scope: only the ALLOW carve (ea_/ea6_) is re-seeded here. The other dynamic
  -- sets share the flush but don't need it: eb_/bl_ are BLOCK sets, so a flush
  -- fails OPEN (host briefly not-blocked) and the #1658 eb_refresh timer already
  -- re-resolves them on a cadence; resolved_ (blockIpOnly) is a separate, rarely
  -- enabled path left out of #2094's scope. Only ea_/ea6_ fail CLOSED — a
  -- carved host silently dropped — which is the confirmed #2094 symptom.
  local ea_backfilled = 0
  if nft_ok and not enforcement_disabled then
    timed("ea_backfill", function()
    local carve = {}
    for mac, hosts in pairs(render.effective_extra_allowed_by_mac(snapshot)) do
      local sanmac = dns_sets.sanitize(mac)
      for _, host in ipairs(hosts) do
        local sanhost = dns_sets.sanitize(host)
        carve[sanhost] = carve[sanhost] or {}
        carve[sanhost][sanmac] = true
      end
    end
    if next(carve) then
      local now_fn     = opts.now_fn or os.time
      local cache_text = read_fn(paths.dns_cache)
      local cache      = dns_log.load_table(cache_text or "", DNS_CACHE_ACCEPT_ALL, now_fn())
      -- #2208: seed the carve in ONE `nft -f` batch rather than one `nft add
      -- element` process per element. The old per-element path spawned
      -- O(matching-cache-entries) nft processes (~6ms fork+exec each) and, on a
      -- busy prod cache with broad carve hosts, dominated apply latency (the
      -- v0.3.19→v0.3.20 regression). All target sets were just declared by the
      -- `nft -f` above from the same SSOT, so the single transaction is safe.
      local script, n = dns_sets.build_ea_backfill_script(cache, carve, {
        nft_table = "inet wifihaven",
      })
      if n > 0 then
        local ok_batch, err_batch = write_fn(paths.ea_backfill_nft, script)
        local batch_rc = nil
        if ok_batch then
          batch_rc = reload_fn("nft -f " .. paths.ea_backfill_nft)
        end
        if ok_batch and exec_ok(batch_rc) then
          ea_backfilled = n
        else
          -- Batch load failed (a target set raced away, or the write failed).
          -- Fall back to the per-element path so a single stale set can't drop
          -- the whole carve seeding (each add is best-effort, ENOENT-tolerant).
          log.warn("policy.apply: ea_ backfill batch failed (write_ok=%s rc=%s), "
                   .. "falling back to per-element seeding",
                   tostring(ok_batch), tostring(batch_rc or err_batch))
          ea_backfilled = dns_sets.backfill_ea(cache, carve, {
            nft_table = "inet wifihaven",
            exec_fn   = reload_fn,
          })
        end
      end
      if ea_backfilled > 0 then
        log.debug("policy.apply: ea_/ea6_ carve backfill added %d element(s)", ea_backfilled)
      end
    end
    end)
  end

  -- #2229: dnsmasq restart, deferred to here (was above `nft -f`). Enforcement
  -- is already live from the load above, so this restart — needed only to make
  -- dnsmasq re-read changed dhcp-host=/nftset= directives (#328: SIGHUP doesn't
  -- re-read conf-dir, so it must be a full `restart`, not `reload`) — no longer
  -- gates the block. Skipped when the rendered conf is byte-identical (#414), so
  -- a pure schedule/pause/time-limit flip that only moves nft state pays nothing.
  if dnsmasq_changed then
    log.debug("policy.apply: wrote dnsmasq=%dB (changed) nft=%dB; restarting dnsmasq (post-nft, #2229)",
              #dnsmasq_content, #nft_content)
    timed("dnsmasq_restart", function()
      reload_fn("/etc/init.d/dnsmasq restart")
    end)
  else
    log.debug("policy.apply: wrote dnsmasq=%dB (unchanged) nft=%dB; skipping dnsmasq restart",
              #dnsmasq_content, #nft_content)
  end

  -- #328 / #351 smoke probe. Runs only when we actually restarted dnsmasq:
  -- the probe exists to catch "we restarted but dnsmasq is still serving
  -- a stale config", so it adds nothing when no restart happened.
  local smoke_warn = false
  if dnsmasq_changed then
    local probe_domain = first_extrablocked_host(dnsmasq_content)
    if probe_domain then
      local check = opts.dns_check_fn or default_dns_check
      local result = timed("smoke_probe", function() return check(probe_domain) end)
      if is_blocked_at_connection(result) then
        smoke_warn = true
        log.warn(
          "policy.apply: smoke check failed; dnsmasq may be serving a stale " ..
          "config — got %q for %s (expected a real upstream IP)",
          tostring(result), probe_domain)
      end
    end
  end

  -- #1206: classify the apply outcome for policy_apply_total{result}. nft
  -- load failure ranks above the smoke warning (it's the harder failure). The
  -- smoke warning does NOT fail the apply — a stale-config DNS answer is a
  -- propagation concern, separate from whether the ruleset loaded (#351).
  local result
  if not nft_ok then
    result = "nft_failed"
  elseif smoke_warn then
    result = "smoke_warn"
  else
    result = "ok"
  end
  report(result, dnsmasq_changed, ea_backfilled)

  -- #2207: report the ACTUAL nft load outcome. A false return tells the caller
  -- the enforcement plane did not change, so it retries and does not advance
  -- the applied etag (the ws apply-on-push + poll paths both gate on this).
  -- `nft_ok` is the only new failure channel — a write failure already
  -- returned false above, and a smoke warning still returns true (it does not
  -- mean the ruleset failed to load).
  return nft_ok
end

-- ---------------------------------------------------------------------------
-- Flash persistence (#309). Survives reboot during API outage so the agent
-- comes back up enforcing the last-known policy instead of dropping to the
-- default-deny boot skeleton (§1 / #308) until the first poll succeeds.
-- ---------------------------------------------------------------------------

local SNAPSHOT_PATH = "/etc/wifihaven/policy.json"

-- save_snapshot(snap, write_fn, rename_fn, log[, read_fn, expect_etag]) → bool
--   write_fn(path, content)  → ok, err
--   rename_fn(from_path, to) → ok[, err]
--   read_fn(path)            → content|nil  (optional — enables the #2001 guard)
--   expect_etag              → string|nil   (the etag we fetched against)
-- Atomic: writes to <path>.tmp then renames over <path>. Skips the rename
-- if the write fails, so a torn or partial write never replaces a good
-- on-disk snapshot.
--
-- #2001 compare-and-swap guard: when ws push is the IN channel (#1849/#1945)
-- the sidecar ALSO writes policy.json. A poll/startup save that fetched an
-- OLDER snapshot must not clobber a NEWER one the sidecar persisted while the
-- (slow, under-load) apply was in flight — otherwise the frozen-poll agent
-- never re-applies the push and enforcement silently reverts. When a `read_fn`
-- is supplied we re-read the on-disk etag just before the rename and SKIP the
-- write (returning true — the fresher file is intentionally kept) iff the
-- on-disk etag is a different, non-nil value than both `expect_etag` (what we
-- fetched against) and the snapshot we're about to write. With no `read_fn`
-- the behaviour is the unconditional write legacy callers/tests expect.
function M.save_snapshot(snap, write_fn, rename_fn, log, read_fn, expect_etag)
  log = log or default_log()
  if read_fn then
    local on_disk = M.load_snapshot(read_fn, log)
    local disk_etag = on_disk and on_disk.etag or nil
    if disk_etag ~= nil
       and disk_etag ~= expect_etag
       and disk_etag ~= (snap and snap.etag) then
      log.info("policy.save_snapshot: on-disk etag=%s differs from expected=%s "
               .. "and from to-write=%s — keeping the fresher (ws-pushed) snapshot (#2001)",
               tostring(disk_etag), tostring(expect_etag), tostring(snap and snap.etag))
      return true
    end
  end
  local jsonc = require("luci.jsonc")
  local body = jsonc.stringify(snap)
  local tmp = SNAPSHOT_PATH .. ".tmp"
  local ok, err = write_fn(tmp, body)
  if not ok then
    log.err("policy.save_snapshot: write %s failed: %s", tmp, tostring(err))
    return false
  end
  local rok, rerr = rename_fn(tmp, SNAPSHOT_PATH)
  if not rok then
    log.err("policy.save_snapshot: rename %s → %s failed: %s",
            tmp, SNAPSHOT_PATH, tostring(rerr))
    return false
  end
  return true
end

-- load_snapshot(read_fn) → snapshot|nil
--   read_fn(path) → content|nil (nil if the file doesn't exist)
-- Returns nil on missing/empty/corrupt content so the caller can fall back
-- to a fresh-start posture without crashing.
function M.load_snapshot(read_fn, log)
  log = log or default_log()
  local content = read_fn(SNAPSHOT_PATH)
  if not content or content == "" then return nil end
  local ok, snap_or_err = pcall(function()
    local jsonc = require("luci.jsonc")
    local parsed = jsonc.parse(content)
    if parsed == nil then error("invalid JSON") end
    return parsed
  end)
  if not ok then
    log.warn("policy.load_snapshot: failed to parse cached snapshot: %s",
             tostring(snap_or_err))
    return nil
  end
  return snap_or_err
end

-- ---------------------------------------------------------------------------
-- Successful-poll timestamp tracking (#309, consumed by #311).
-- ---------------------------------------------------------------------------

M.last_successful_poll_ts = nil

function M.mark_poll_success(now)
  M.last_successful_poll_ts = now
end

function M.poll_age_seconds(now)
  if not M.last_successful_poll_ts then return math.huge end
  -- Both args are wall-clock (os.time()). A backward wall-clock jump (NTP
  -- correction, DST, manual change) can momentarily make `now` smaller than
  -- the stored timestamp; clamp to 0 so callers don't see a negative age
  -- (defensive bandage for #336). The scheduler proper runs on a monotonic
  -- clock and is not affected.
  local age = now - M.last_successful_poll_ts
  if age < 0 then return 0 end
  return age
end

-- Format a poll_age value for log output. Returns "inf" for the cold-boot
-- sentinel (math.huge from poll_age_seconds before any successful poll);
-- otherwise "<seconds>s". string.format("%d", math.huge) does not substitute,
-- so logging poll_age=%ds with a raw value would emit the literal "%ds inf".
function M.format_poll_age(poll_age)
  if poll_age == math.huge then return "inf" end
  return string.format("%ds", poll_age)
end

-- Test-only: reset module-level poll state between specs.
function M.reset_poll_state()
  M.last_successful_poll_ts = nil
end

-- ---------------------------------------------------------------------------
-- Failover transition decision (#331/#422). Pure function so the agent's
-- on_tick can stay thin and the boundary behavior is unit-testable.
--
-- Inputs:
--   in_failover : bool — was the last apply rendered with failover opts?
--   link_ok     : bool — are we in contact with the API right now? Since
--                        #2736 that is the ws link's health sentinel being
--                        fresh, not a poll result.
-- Returns:
--   should_apply    : bool       — re-render is needed this tick
--   apply_opts      : table|nil  — opts to pass to policy.apply
--   new_in_failover : bool       — updated state flag
--
-- Semantics (#422): failover trips immediately on losing contact and lifts
-- immediately on regaining it. There is no time-based cushion — the
-- previous 300s gate contradicted the per-profile failureMode design intent
-- (block-all should drop traffic the moment we can't reach the API).
-- ---------------------------------------------------------------------------
function M.failover_transition(in_failover, link_ok)
  if link_ok then
    if in_failover then
      return true, { link_failed = false }, false
    end
    return false, nil, false
  end
  if not in_failover then
    return true, { link_failed = true }, true
  end
  return false, nil, true
end

return M
