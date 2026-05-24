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
  )
}
