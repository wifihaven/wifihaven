package wifihaven.api.feature

import wifihaven.api.PolicyConfig
import zio.test.*

/**
 * #944: PolicyConfig.uiAllowedHosts is a comma-separated list of host[:port] entries. Bare
 * hostnames parse via Hostname; host:port is accepted as-is (dev installs reach the API on a
 * non-default port).
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
    test("host:port preserved through to the Hostname wire string") {
      val out = PolicyConfig("api.lan:8080").uiAllowedHostsParsed.map(_.value)
      assertTrue(out == List("api.lan:8080"))
    },
    test("mixed entries (with and without port) and whitespace") {
      val out =
        PolicyConfig(" wifihaven.net , api.lan:8080 ,api.wifihaven.net").uiAllowedHostsParsed
          .map(_.value)
      assertTrue(out == List("wifihaven.net", "api.lan:8080", "api.wifihaven.net"))
    },
    test("invalid hostname throws at config load") {
      val ex = scala.util.Try(PolicyConfig("not_a_host").uiAllowedHostsParsed)
      assertTrue(ex.isFailure)
    },
    test("non-numeric port throws at config load") {
      val ex = scala.util.Try(PolicyConfig("api.lan:abc").uiAllowedHostsParsed)
      assertTrue(ex.isFailure)
    },
    test("port out of range throws at config load") {
      val ex = scala.util.Try(PolicyConfig("api.lan:99999").uiAllowedHostsParsed)
      assertTrue(ex.isFailure)
    },
  )
}
