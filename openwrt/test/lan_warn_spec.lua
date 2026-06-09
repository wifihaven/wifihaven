-- lan_warn_spec.lua — pin the agent-startup LAN-misconfig warning (#1174).
--
-- We don't care which logger the agent routes the message through; the helper
-- is a pure (api_url, block_page_url) → string|nil classifier.

local lan_warn = require("wifihaven.lan_warn")

describe("lan_warn.host_of", function()
  it("strips scheme, port, and path", function()
    assert.equals("api.lan",          lan_warn.host_of("http://api.lan:8080"))
    assert.equals("api.wifihaven.net", lan_warn.host_of("https://api.wifihaven.net"))
    assert.equals("192.168.10.43",     lan_warn.host_of("http://192.168.10.43:8080/api/router/policy"))
    assert.equals("",                  lan_warn.host_of(""))
    assert.equals("",                  lan_warn.host_of(nil))
  end)

  it("handles IPv6 literals in brackets", function()
    assert.equals("::1",          lan_warn.host_of("http://[::1]:8080"))
    assert.equals("fe80::1234",   lan_warn.host_of("https://[fe80::1234]/x"))
  end)
end)

describe("lan_warn.is_private_ipv4", function()
  it("flags RFC1918 ranges", function()
    assert.is_true(lan_warn.is_private_ipv4("10.0.0.1"))
    assert.is_true(lan_warn.is_private_ipv4("10.255.255.255"))
    assert.is_true(lan_warn.is_private_ipv4("192.168.10.43"))
    assert.is_true(lan_warn.is_private_ipv4("172.16.0.1"))
    assert.is_true(lan_warn.is_private_ipv4("172.31.255.254"))
  end)

  it("does NOT flag adjacent public ranges or non-IPv4 strings", function()
    assert.is_false(lan_warn.is_private_ipv4("11.0.0.1"))
    assert.is_false(lan_warn.is_private_ipv4("172.15.0.1"))
    assert.is_false(lan_warn.is_private_ipv4("172.32.0.1"))
    assert.is_false(lan_warn.is_private_ipv4("8.8.8.8"))
    assert.is_false(lan_warn.is_private_ipv4("api.wifihaven.net"))
    assert.is_false(lan_warn.is_private_ipv4("256.1.1.1"))
    assert.is_false(lan_warn.is_private_ipv4(""))
  end)
end)

describe("lan_warn.is_lan_host", function()
  it("flags .lan / .local / localhost / private IPv4", function()
    assert.is_true(lan_warn.is_lan_host("api.lan"))
    assert.is_true(lan_warn.is_lan_host("router.LAN"))     -- case-insensitive
    assert.is_true(lan_warn.is_lan_host("foo.local"))
    assert.is_true(lan_warn.is_lan_host("localhost"))
    assert.is_true(lan_warn.is_lan_host("192.168.10.43"))
  end)

  it("does NOT flag public DNS names or public IPs", function()
    assert.is_false(lan_warn.is_lan_host("api.wifihaven.net"))
    assert.is_false(lan_warn.is_lan_host("wifihaven.net"))
    assert.is_false(lan_warn.is_lan_host("8.8.8.8"))
    assert.is_false(lan_warn.is_lan_host(""))
  end)
end)

describe("lan_warn.check — startup misconfig warning (#1174)", function()
  it("warns when api_url is LAN AND block_page_url is unset", function()
    local msg = lan_warn.check("http://api.lan:8080", "")
    assert.is_string(msg)
    assert.truthy(msg:find("api.lan",          1, true))
    assert.truthy(msg:find("block_page_url",   1, true))
  end)

  it("warns for private IPv4 api_url with empty block_page_url (the #1174 prod shape)", function()
    local msg = lan_warn.check("http://192.168.10.43:8080", nil)
    assert.is_string(msg)
    assert.truthy(msg:find("192.168.10.43", 1, true))
  end)

  it("does NOT warn when block_page_url is explicitly set (self-hosted is OK)", function()
    assert.is_nil(lan_warn.check("http://api.lan:8080",      "https://wifihaven.net"))
    assert.is_nil(lan_warn.check("http://192.168.10.43:8080", "http://192.168.10.43:8080"))
  end)

  it("does NOT warn for a public api_url (the cloud shape)", function()
    assert.is_nil(lan_warn.check("https://api.wifihaven.net", ""))
    assert.is_nil(lan_warn.check("https://api.wifihaven.net", "https://wifihaven.net"))
  end)
end)
