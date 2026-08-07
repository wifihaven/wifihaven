package wifihaven.api.feature

import wifihaven.api.PolicyConfig
import zio.test.*

/**
 * #944: PolicyConfig.uiAllowedHosts is a comma-separated list of hostnames validated by
 * Hostname.parse. Port-aware allow/block is out of scope here and tracked in #296.
 */
object PolicyConfigSpec extends ZIOSpecDefault {

  def spec = suite("PolicyConfig — uiAllowedHosts (#944)")(
    test("empty string → empty list") {
      assertTrue(PolicyConfig("").uiAllowedHostsParsed.isEmpty)
    },
    test("single bare hostname") {
      val out = PolicyConfig("wifihaven.net").uiAllowedHostsParsed.map(_.value)
      assertTrue(out == List("wifihaven.net"))
    },
    test("multiple entries with whitespace") {
      val out =
        PolicyConfig(" wifihaven.net , www.wifihaven.net ,api.wifihaven.net").uiAllowedHostsParsed
          .map(_.value)
      assertTrue(out == List("wifihaven.net", "www.wifihaven.net", "api.wifihaven.net"))
    },
    test("invalid hostname throws at config load") {
      val ex = scala.util.Try(PolicyConfig("not_a_host").uiAllowedHostsParsed)
      assertTrue(ex.isFailure)
    },
    test("host:port rejected — port support tracked in #296") {
      val ex = scala.util.Try(PolicyConfig("api.lan:8080").uiAllowedHostsParsed)
      assertTrue(ex.isFailure)
    },
    test("post-soak prod set parses to app + api only — apex/www dropped (#1843)") {
      // Documents the intended post-#1843 prod shape of WIFIHAVEN_UI_ALLOWED_HOSTS
      // and shows that `app.`/`api.` subdomains parse as distinct hosts rather
      // than collapsing into the apex. #1843 dropped wifihaven.net /
      // www.wifihaven.net after the #1842 soak: apex+www front the marketing
      // site, the block page lives on app.wifihaven.net, and no apex /blocked
      // compat shim was ever shipped. These hosts are unioned into every
      // profile's snapshot extraAllowed, so re-widening the set re-opens a
      // fleet-wide carve-out.
      //
      // This does NOT pin render.yaml — the literal is hand-copied and cannot
      // fail when render.yaml changes. scripts/check-spa-allowlists.test.sh is
      // the pin; it parses render.yaml directly.
      val out = PolicyConfig("api.wifihaven.net,app.wifihaven.net").uiAllowedHostsParsed
        .map(_.value)
      assertTrue(
        out == List("api.wifihaven.net", "app.wifihaven.net"),
        !out.contains("wifihaven.net"),
        !out.contains("www.wifihaven.net"),
      )
    },
  )
}
