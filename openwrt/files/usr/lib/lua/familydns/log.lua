-- log.lua — small wrapper around the BusyBox `logger` command for the
-- familydns-agent.  All log lines are tagged "familydns" so they're easy
-- to grep in `logread`.
--
-- Public API:
--   log.init({ debug = bool|string })  -- call once at agent start
--   log.debug_enabled() -> bool
--   log.info(fmt, ...)
--   log.warn(fmt, ...)
--   log.err(fmt, ...)
--   log.debug(fmt, ...)  -- no-op unless init({debug=true}) was called
--
-- Format is string.format-style: log.debug("foo=%s bar=%d", x, y).
--
-- We deliberately shell out to `logger` instead of writing to stderr because
-- procd's stderr capture has been a source of "silent agent" bugs in the
-- past (see #228).  `logger -t familydns ...` reliably appears in `logread`
-- without depending on procd_set_param wiring.

local M = {}

local _debug = false

-- Injectable for tests.  Default just runs the command; we don't care about
-- the exit code (best-effort logging).
function M._exec(cmd)
  os.execute(cmd)
end

local function truthy(v)
  if v == true then return true end
  if v == nil or v == false then return false end
  if type(v) == "number" then return v ~= 0 end
  if type(v) == "string" then
    local s = v:lower()
    return s == "1" or s == "true" or s == "yes" or s == "on"
  end
  return false
end

function M.init(opts)
  opts = opts or {}
  _debug = truthy(opts.debug)
end

function M.debug_enabled()
  return _debug
end

-- Shell-quote a string for safe use inside a `sh -c` argument: wrap in
-- single quotes and escape any embedded single quotes by closing, escaping,
-- and reopening: 'foo'\''bar'
local function shquote(s)
  return "'" .. tostring(s):gsub("'", "'\\''") .. "'"
end

local function emit(priority, fmt, ...)
  local ok, msg
  if select("#", ...) == 0 then
    msg = tostring(fmt)
  else
    ok, msg = pcall(string.format, fmt, ...)
    if not ok then
      -- string.format failed (bad %d etc.); fall back to raw fmt + args dump.
      local parts = { tostring(fmt) }
      for i = 1, select("#", ...) do
        parts[#parts + 1] = tostring((select(i, ...)))
      end
      msg = table.concat(parts, " ")
    end
  end
  local cmd = string.format(
    "logger -t familydns -p %s %s",
    priority, shquote(msg))
  M._exec(cmd)
end

function M.info(fmt, ...) emit("user.info",    fmt, ...) end
function M.warn(fmt, ...) emit("user.warning", fmt, ...) end
function M.err (fmt, ...) emit("user.err",     fmt, ...) end
function M.debug(fmt, ...)
  if not _debug then return end
  emit("user.debug", fmt, ...)
end

return M
