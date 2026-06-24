-- ws_backoff.lua — reconnect backoff policy for the wifihaven-ws sidecar (#1848).
--
-- Design §5.1: the sidecar reconnects with exponential backoff + jitter
-- (1s → 2s → 4s → … capped at 60s), reset to attempt 1 on every successful
-- connect (the 101 upgrade). "Reconnect IS the throttle" — there is no per-frame
-- retry. This is a pure function (no I/O, no clock) so it runs identically under
-- OpenWrt Lua 5.1 and the dev-host busted suite.

local M = {}

local BASE_SECONDS = 1
local MAX_SECONDS  = 60

-- delay(attempt, rng_fn) → seconds to sleep before the next connect attempt.
--
-- `attempt` is 1-indexed (1 = the first reconnect after the initial drop).
-- Nominal = min(BASE * 2^(attempt-1), MAX); the returned value applies FULL
-- jitter — uniformly in [0.5 * nominal, nominal] — so a fleet of routers that
-- all dropped at once (e.g. an API restart) re-spreads their reconnects instead
-- of stampeding. rng_fn() returns [0, 1]; defaults to math.random.
function M.delay(attempt, rng_fn)
  if not attempt or attempt < 1 then attempt = 1 end
  local nominal = BASE_SECONDS * (2 ^ (attempt - 1))
  if nominal > MAX_SECONDS then nominal = MAX_SECONDS end
  local j = (rng_fn or math.random)()
  return nominal * (0.5 + 0.5 * j)
end

return M
