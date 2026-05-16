package wifihaven.shared.types

import zio.json.*
import zio.test.*

object MacAddressSpec extends ZIOSpecDefault {

  def spec = suite("MacAddress")(
    test("parses canonical lowercase colon form") {
      val r = MacAddress.parse("aa:bb:cc:11:22:33")
      assertTrue(r.exists(_.value == "aa:bb:cc:11:22:33"))
    },
    test("lowercases mixed-case input") {
      val r = MacAddress.parse("AA:bb:CC:11:22:33")
      assertTrue(r.exists(_.value == "aa:bb:cc:11:22:33"))
    },
    test("accepts dash separator and normalizes to colon") {
      val r = MacAddress.parse("aa-bb-cc-11-22-33")
      assertTrue(r.exists(_.value == "aa:bb:cc:11:22:33"))
    },
    test("rejects empty string") {
      assertTrue(MacAddress.parse("").isLeft)
    },
    test("rejects wrong length") {
      assertTrue(MacAddress.parse("aa:bb:cc:11:22").isLeft) &&
      assertTrue(MacAddress.parse("aa:bb:cc:11:22:33:44").isLeft)
    },
    test("rejects non-hex chars") {
      assertTrue(MacAddress.parse("zz:bb:cc:11:22:33").isLeft)
    },
    test("rejects mixed separators") {
      assertTrue(MacAddress.parse("aa:bb-cc:11:22:33").isLeft)
    },
    test("JSON round-trip preserves canonical form") {
      val mac     = MacAddress.parse("AA-BB-CC-11-22-33").toOption.get
      val json    = mac.toJson
      val decoded = json.fromJson[MacAddress]
      assertTrue(json == "\"aa:bb:cc:11:22:33\"") &&
      assertTrue(decoded.contains(mac))
    },
    test("JSON decode of invalid MAC returns Left") {
      assertTrue("\"not-a-mac\"".fromJson[MacAddress].isLeft)
    },
    test("JSON decode of empty string returns Left") {
      assertTrue("\"\"".fromJson[MacAddress].isLeft)
    },
  )
}
