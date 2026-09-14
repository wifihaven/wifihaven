-- http_bounds.lua — curl timeout bounds for every agent HTTP call (#2785).
--
-- The agent's cooperative on_tick loop is single-fibered: the policy apply, the
-- usage flush, the event flush, the nflog drain and the metrics push all run in
-- sequence inside one callback driven off a blocking read. Any synchronous call
-- in there that can hang does not slow the agent down, it STOPS it — the same
-- lesson #2719 recorded for the conntrack slow path, arrived at from the other
-- direction.
--
-- Three of those steps shell out to curl (the metrics push, the block-page
-- token fetch, the blocklist fetch), and until #2785 none of them passed a
-- timeout flag. curl's own defaults are the problem: CURLOPT_CONNECTTIMEOUT is
-- 300 seconds and there is NO default total-transfer cap at all, so one
-- blackholed request wedges the loop for five minutes or longer. On
-- 2026-09-13 that showed up on the prod family router as a pushed snapshot
-- persisted at 19:09:23 and not applied until 19:13:34, with usage and event
-- reporting silent across the whole window.
--
-- This module owns the flags so the bound is defined once and unit-tested,
-- rather than being a literal typed at each call site (where the next call site
-- forgets it — which is exactly how we got here).
local M = {}

-- Interactive path: the metrics push and the block-page token fetch. Both are
-- small request/response pairs against our own API, so a healthy call is well
-- under a second. The bound has to stay small because #2785's acceptance bar is
-- "a pushed snapshot applies within seconds": the worst case is one hung
-- network step sitting ahead of the apply in the same tick.
-- These four defaults form ONE ordering, and it is load-bearing:
--
--   eb_refresh_slice (2) < DEFAULT_MAX_SECONDS (10)
--                        < tick_guard.DEFAULT_STEP_BUDGET_SECONDS (15)
--                        < tick_guard.DEFAULT_STALL_SECONDS (30)
--
-- A step is allowed to run to its own timeout without being reported as slow,
-- and a slow step is reported before the whole tick is called stalled. Get the
-- order wrong and the new instrumentation cries wolf on a call that is behaving
-- exactly as configured. tick_guard.check_bounds() asserts it, and
-- tick_guard_spec pins it.
M.DEFAULT_CONNECT_SECONDS = 5
M.DEFAULT_MAX_SECONDS     = 10

-- Bulk path: blocklist bodies, which are fetched only when a list's (id,
-- version) is not already cached and can run to several MB (the cap is
-- blocklist_max_list_bytes, 10 MB). Longer, but still finite — an unbounded
-- bulk fetch stalls the loop just as thoroughly as an unbounded small one.
M.DEFAULT_BULK_MAX_SECONDS = 120

-- sanitize_seconds(v, default) -> integer >= 1
--   `v` is a UCI value (a string, or nil when unset). Anything that is not a
--   positive number falls back to `default`. Zero is rejected deliberately:
--   curl reads `--max-time 0` / `--connect-timeout 0` as "no timeout", so a
--   stray 0 in UCI would silently restore the exact defect this module exists
--   to close. Fractions are floored — these flags accept decimals in modern
--   curl, but keeping them integral avoids a locale-dependent "%.1f" rendering
--   a comma into the shell command.
function M.sanitize_seconds(v, default)
  local n = tonumber(v)
  if not n then return default end
  n = math.floor(n)
  if n < 1 then return default end
  return n
end

-- curl_args(connect_s, max_s) -> " --connect-timeout N --max-time M"
--   Leading space so callers splice it straight into their command string.
--   `max_s` is clamped up to `connect_s`: a total budget shorter than the
--   connect budget makes the latter unreachable, which is a config mistake we
--   correct rather than propagate.
function M.curl_args(connect_s, max_s)
  local c = M.sanitize_seconds(connect_s, M.DEFAULT_CONNECT_SECONDS)
  local m = M.sanitize_seconds(max_s, M.DEFAULT_MAX_SECONDS)
  if m < c then m = c end
  return string.format(" --connect-timeout %d --max-time %d", c, m)
end

return M
