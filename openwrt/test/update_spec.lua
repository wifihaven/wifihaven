-- Tests for openwrt/files/usr/lib/lua/wifihaven/update.lua (#244).
--
-- The auto-updater previously keyed off the rolling `openwrt-latest`
-- pre-release's asset digest (#245 / #870). With the coordinated semver
-- release flow (#499 / #1134) the canonical artifact is now the
-- non-prerelease `v<X.Y.Z>` GitHub Release, so the agent decides "should
-- I upgrade?" by comparing the installed package version against the
-- release's `tag_name`. This spec exercises the decision logic in
-- isolation — the shell wrapper still owns curl + install side effects.

local update = require("update")

describe("update.tag_to_version", function()

  it("strips a leading 'v' from the tag", function()
    assert.equal("0.2.8", update.tag_to_version("v0.2.8"))
  end)

  it("leaves a bare semver alone", function()
    assert.equal("1.2.3", update.tag_to_version("1.2.3"))
  end)

  it("returns nil for an empty or nil tag", function()
    assert.is_nil(update.tag_to_version(nil))
    assert.is_nil(update.tag_to_version(""))
  end)

end)

describe("update.pick_asset", function()

  local META = {
    tag_name = "v0.2.8",
    assets = {
      { name = "luci-app-wifihaven_0.2.8-1_all.ipk",
        browser_download_url = "https://example.com/luci.ipk" },
      { name = "wifihaven_0.2.8-1_all.apk",
        browser_download_url = "https://example.com/wifihaven.apk" },
      { name = "wifihaven_0.2.8-1_all.ipk",
        browser_download_url = "https://example.com/wifihaven.ipk" },
    },
  }

  it("returns the ipk asset url + version for the opkg path", function()
    local got = update.pick_asset(META, "ipk")
    assert.same({
      url = "https://example.com/wifihaven.ipk",
      version = "0.2.8",
    }, got)
  end)

  it("returns the apk asset url + version for the apk path", function()
    local got = update.pick_asset(META, "apk")
    assert.same({
      url = "https://example.com/wifihaven.apk",
      version = "0.2.8",
    }, got)
  end)

  it("skips the luci-app asset and picks the main package", function()
    -- pick_asset must not return the luci-app-* ipk even though it
    -- matches both the extension and a `wifihaven` substring.
    local got = update.pick_asset(META, "ipk")
    assert.equal("https://example.com/wifihaven.ipk", got.url)
  end)

  it("returns nil when no matching asset exists", function()
    local meta = { tag_name = "v0.2.8", assets = {} }
    assert.is_nil(update.pick_asset(meta, "ipk"))
  end)

end)

describe("update.decide", function()

  it("returns 'install' when installed != latest", function()
    assert.equal("install", update.decide("0.2.7", "0.2.8"))
  end)

  it("returns 'skip' when installed == latest", function()
    assert.equal("skip", update.decide("0.2.8", "0.2.8"))
  end)

  it("returns 'install' when installed version is unknown", function()
    -- An older agent without /usr/lib/wifihaven/VERSION should pick up
    -- the latest on first run rather than getting stuck.
    assert.equal("install", update.decide(nil, "0.2.8"))
  end)

end)
