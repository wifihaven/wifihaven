package familydns.shared.types

import zio.json.*
import zio.test.*

object HostnameSpec extends ZIOSpecDefault {

  def spec = suite("Hostname")(
    test("parses simple domain") {
      assertTrue(Hostname.parse("example.com").exists(_.value == "example.com"))
    },
    test("lowercases input") {
      assertTrue(Hostname.parse("Example.COM").exists(_.value == "example.com"))
    },
    test("strips trailing dot") {
      assertTrue(Hostname.parse("example.com.").exists(_.value == "example.com"))
    },
    test("accepts single-label hostname (localhost)") {
      assertTrue(Hostname.parse("localhost").exists(_.value == "localhost"))
    },
    test("accepts IDN punycode") {
      assertTrue(Hostname.parse("xn--bcher-kva.example").isRight)
    },
    test("accepts hyphenated labels but not leading/trailing hyphen") {
      assertTrue(Hostname.parse("foo-bar.example.com").isRight) &&
      assertTrue(Hostname.parse("-foo.example.com").isLeft) &&
      assertTrue(Hostname.parse("foo-.example.com").isLeft)
    },
    test("rejects empty string") {
      assertTrue(Hostname.parse("").isLeft)
    },
    test("rejects leading dot") {
      assertTrue(Hostname.parse(".example.com").isLeft)
    },
    test("rejects double dot") {
      assertTrue(Hostname.parse("foo..example.com").isLeft)
    },
    test("rejects label > 63 chars") {
      val tooLong = "a" * 64
      assertTrue(Hostname.parse(s"$tooLong.example.com").isLeft)
    },
    test("rejects total length > 253 chars") {
      val label   = "a" * 60
      val tooLong = List.fill(5)(label).mkString(".") + ".com"
      assertTrue(Hostname.parse(tooLong).isLeft)
    },
    test("rejects IPv4 literal") {
      assertTrue(Hostname.parse("192.168.1.1").isLeft)
    },
    test("JSON round-trip preserves canonical form") {
      val h    = Hostname.parse("Example.COM.").toOption.get
      val json = h.toJson
      assertTrue(json == "\"example.com\"") &&
      assertTrue(json.fromJson[Hostname].contains(h))
    },
    test("JSON decode of empty string returns Left") {
      assertTrue("\"\"".fromJson[Hostname].isLeft)
    },
  )
}
