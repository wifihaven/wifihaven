-- wifihaven.clock — monotonic-clock helper for scheduling math.
--
-- Rule of thumb when picking which time source to use:
--   * Use clock.monotonic_seconds() for any "has X elapsed?" check
--     (poll cadences, retry backoffs, cache TTL refreshes). The value
--     never goes backward, so NTP corrections, DST shifts, or deliberate
--     wall-clock manipulation cannot stall a timer (issue #336).
--   * Use os.time() / os.date() for any "what is the wall-clock right now?"
--     need: RFC3339 timestamps in API payloads and human-readable log
--     timestamps.
--
-- The two time bases must not be mixed within a single diff: e.g. a
-- `last_run` captured with monotonic_seconds() must only be compared
-- against another monotonic_seconds() reading.
--
-- Backend selection: prefers luaposix's clock_gettime(CLOCK_MONOTONIC) when
-- available (sub-second resolution); falls back to reading /proc/uptime
-- (BusyBox-friendly, second-resolution on most kernels). On the rare system
-- where neither is reachable, falls back to os.time() — at least preserves
-- existing behavior rather than crashing.

local M = {}

local ok, posix_time = pcall(require, "posix.time")
if ok and posix_time and posix_time.clock_gettime and posix_time.CLOCK_MONOTONIC then
  M._backend = "posix"
  function M.monotonic_seconds()
    local ts = posix_time.clock_gettime(posix_time.CLOCK_MONOTONIC)
    if type(ts) == "table" then
      return (ts.tv_sec or 0) + (ts.tv_nsec or 0) / 1e9
    end
    return ts or os.time()
  end
else
  M._backend = "uptime"
  function M.monotonic_seconds()
    local f = io.open("/proc/uptime", "r")
    if not f then return os.time() end
    local line = f:read("*l") or ""
    f:close()
    local up = tonumber(line:match("^(%S+)"))
    return up or os.time()
  end
end

return M
