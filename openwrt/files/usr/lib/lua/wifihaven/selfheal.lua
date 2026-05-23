-- selfheal.lua — agent-side self-healing for state the package postinst owns.
--
-- Why this exists (#896): apk-tools v3 does NOT re-run a package's
-- post-install script on a same-version reinstall (even with
-- --force-reinstall). So a fix that only lives in the postinst — like
-- #869's wifihaven-update cron registration — never reaches routers that
-- already had the package installed before the fix shipped. Operators
-- would have to `apk del + apk add` every router by hand.
--
-- The agent runs constantly, so it can heal this on every startup:
-- idempotent on the happy path, repairing on every other shape.

local M = {}

M.CANONICAL_LINE = "0 4 * * * /usr/sbin/wifihaven-update"
M.DEFAULT_PATH   = "/etc/crontabs/root"

local function read_lines(path)
  local f = io.open(path, "r")
  if not f then return nil end
  local t = {}
  for line in f:lines() do t[#t+1] = line end
  f:close()
  return t
end

local function write_lines(path, lines)
  local f, err = io.open(path, "w")
  if not f then return nil, err end
  for _, l in ipairs(lines) do f:write(l, "\n") end
  f:close()
  return true
end

-- Ensure /etc/crontabs/root contains exactly the canonical wifihaven-update
-- cron entry, and that crond is enabled and running. Returns true if a
-- heal was applied, false if the file was already in the desired state.
--
-- Heals three shapes: missing /etc/crontabs/ dir, missing root crontab, and
-- stale cadence (e.g. "0 */6 * * *" from pre-#869 routers — same sed -i
-- behavior as the postinst block).
function M.selfheal_cron(opts)
  opts = opts or {}
  local path    = opts.path    or M.DEFAULT_PATH
  local run_cmd = opts.run_cmd or os.execute
  local log     = opts.log

  local dir = path:match("^(.*)/[^/]+$") or "/etc/crontabs"
  run_cmd(string.format("mkdir -p %q", dir))

  local lines = read_lines(path) or {}
  local kept  = {}
  local has_canonical = false
  local has_other     = false
  for _, l in ipairs(lines) do
    if l:find("wifihaven-update", 1, true) then
      if l == M.CANONICAL_LINE and not has_canonical then
        has_canonical = true
        kept[#kept+1] = l
      else
        has_other = true
      end
    else
      kept[#kept+1] = l
    end
  end

  if has_canonical and not has_other then
    return false
  end

  if not has_canonical then
    kept[#kept+1] = M.CANONICAL_LINE
  end

  local ok, werr = write_lines(path, kept)
  if not ok then
    if log then log.warn("selfheal: failed to write %s: %s", path, tostring(werr)) end
    return false
  end

  if log then log.info("selfheal: installed missing wifihaven-update cron entry") end
  run_cmd("/etc/init.d/cron enable 2>/dev/null")
  run_cmd("/etc/init.d/cron restart 2>/dev/null")
  return true
end

return M
