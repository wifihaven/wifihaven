package wifihaven.shared.types

import zio.json.*
import zio.test.*

object IpAddressSpec extends ZIOSpecDefault {

  def spec = suite("IpAddress")(
    test("parses IPv4 dotted quad") {
      assertTrue(IpAddress.parse("192.168.1.1").exists(_.value == "192.168.1.1"))
    },
    test("parses IPv4 boundaries") {
      assertTrue(IpAddress.parse("0.0.0.0").isRight) &&
      assertTrue(IpAddress.parse("255.255.255.255").isRight)
    },
    test("parses IPv6") {
      assertTrue(IpAddress.parse("2001:db8::1").isRight) &&
      assertTrue(IpAddress.parse("::1").isRight)
    },
    test("rejects empty string") {
      assertTrue(IpAddress.parse("").isLeft)
    },
    test("rejects out-of-range IPv4 octet") {
      assertTrue(IpAddress.parse("999.0.0.0").isLeft) &&
      assertTrue(IpAddress.parse("256.1.1.1").isLeft)
    },
    test("rejects partial IPv4") {
      assertTrue(IpAddress.parse("1.2.3").isLeft)
    },
    test("rejects hostname") {
      assertTrue(IpAddress.parse("example.com").isLeft)
    },
    test("rejects IPv4 with leading zeros (ambiguous octal)") {
      assertTrue(IpAddress.parse("192.168.001.001").isLeft)
    },
    test("JSON round-trip") {
      val ip   = IpAddress.parse("10.0.0.1").toOption.get
      val json = ip.toJson
      assertTrue(json == "\"10.0.0.1\"") &&
      assertTrue(json.fromJson[IpAddress].contains(ip))
    },
    test("JSON decode of invalid IP returns Left") {
      assertTrue("\"not-an-ip\"".fromJson[IpAddress].isLeft) &&
      assertTrue("\"\"".fromJson[IpAddress].isLeft)
    },
  )
}
