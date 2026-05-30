-- update.lua — pure decision logic for the wifihaven auto-updater (#244).
--
-- Before #499/#1134 the agent pulled the rolling `openwrt-latest`
-- GitHub pre-release and re-installed whenever the matched asset's
-- sha256 digest changed. With coordinated semver versioning, the
-- canonical artifact is now the `v<X.Y.Z>` non-prerelease release;
-- the agent compares its installed package version against
-- `tag_name` and only re-installs on a mismatch.
--
-- This module owns the decision (which asset, which version, install
-- or skip). Side effects — curl, opkg/apk install, init.d restart —
-- stay in /usr/sbin/wifihaven-update so they remain easy to drive
-- from the shell-level smoke tests.

local M = {}

-- Strip a leading "v" from a release tag. Returns nil for nil/empty.
function M.tag_to_version(tag)
  if tag == nil or tag == "" then return nil end
  local stripped = tag:gsub("^v", "")
  if stripped == "" then return nil end
  return stripped
end

-- Find the main `wifihaven_<X.Y.Z>-<rel>_all.<ext>` asset in a release
-- metadata table (as produced by GitHub's REST API). Skips the
-- luci-app-wifihaven_* siblings, which share the extension and a
-- substring of the package name. Returns { url, version } or nil.
function M.pick_asset(meta, ext)
  if meta == nil or meta.assets == nil then return nil end
  local version = M.tag_to_version(meta.tag_name)
  local pattern = "^wifihaven_.+%." .. ext .. "$"
  for _, a in ipairs(meta.assets) do
    if a.name and a.name:match(pattern) then
      return { url = a.browser_download_url, version = version }
    end
  end
  return nil
end

-- Decide whether to install. Unknown installed version (older agents
-- without /usr/lib/wifihaven/VERSION) installs once to bootstrap;
-- otherwise reinstall only on version mismatch.
function M.decide(installed, latest)
  if latest == nil or latest == "" then return "skip" end
  if installed == nil or installed == "" then return "install" end
  if installed == latest then return "skip" end
  return "install"
end

return M
