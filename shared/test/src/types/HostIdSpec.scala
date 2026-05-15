package familydns.shared.types

import zio.json.*
import zio.test.*

object HostIdSpec extends ZIOSpecDefault {

  def spec = suite("HostId")(
    suite("legacy parse heuristic")(
      test("IPv4 literal becomes IPv4 variant") {
        val r = HostId.parseLegacy("192.0.2.1")
        assertTrue(r.exists {
          case HostId.IPv4(a) => a.value == "192.0.2.1"
          case _              => false
        })
      },
      test("IPv6 literal becomes IPv6 variant") {
        val r = HostId.parseLegacy("2001:db8::1")
        assertTrue(r.exists {
          case HostId.IPv6(a) => a.value == "2001:db8::1"
          case _              => false
        })
      },
      test("FQDN becomes Fqdn variant") {
        val r = HostId.parseLegacy("youtube.com")
        assertTrue(r.exists {
          case HostId.Fqdn(h) => h.value == "youtube.com"
          case _              => false
        })
      },
      test("ambiguous numeric single-label errs on FQDN (no false IP)") {
        // "10" is not a valid IPv4 (needs 4 octets) but IS a valid
        // single-label hostname. The heuristic must err on FQDN here, not
        // misclassify it as some kind of IP fragment.
        val r = HostId.parseLegacy("10")
        assertTrue(r.exists {
          case HostId.Fqdn(h) => h.value == "10"
          case _              => false
        })
      },
      test("empty string is rejected") {
        assertTrue(HostId.parseLegacy("").isLeft)
      },
    ),
    suite("wire JSON form")(
      test("encodes FQDN as {type:fqdn,value:...}") {
        val h    = HostId.Fqdn(Hostname.parse("youtube.com").toOption.get)
        val json = (h: HostId).toJson
        assertTrue(json == """{"type":"fqdn","value":"youtube.com"}""")
      },
      test("encodes IPv4 as {type:ipv4,value:...}") {
        val h    = HostId.IPv4(IpAddress.parse("192.0.2.1").toOption.get)
        val json = (h: HostId).toJson
        assertTrue(json == """{"type":"ipv4","value":"192.0.2.1"}""")
      },
      test("encodes IPv6 as {type:ipv6,value:...}") {
        val h    = HostId.IPv6(IpAddress.parse("2001:db8::1").toOption.get)
        val json = (h: HostId).toJson
        assertTrue(json == """{"type":"ipv6","value":"2001:db8::1"}""")
      },
      test("round-trips FQDN") {
        val h    = HostId.Fqdn(Hostname.parse("a.example.com").toOption.get)
        val json = (h: HostId).toJson
        assertTrue(json.fromJson[HostId].contains(h: HostId))
      },
      test("round-trips IPv4") {
        val h    = HostId.IPv4(IpAddress.parse("10.0.0.1").toOption.get)
        val json = (h: HostId).toJson
        assertTrue(json.fromJson[HostId].contains(h: HostId))
      },
      test("round-trips IPv6") {
        val h    = HostId.IPv6(IpAddress.parse("fe80::1").toOption.get)
        val json = (h: HostId).toJson
        assertTrue(json.fromJson[HostId].contains(h: HostId))
      },
      test("rejects unknown type discriminator") {
        val json = """{"type":"banana","value":"x"}"""
        assertTrue(json.fromJson[HostId].isLeft)
      },
      test("rejects ipv4 carrying v6 value") {
        val json = """{"type":"ipv4","value":"2001:db8::1"}"""
        assertTrue(json.fromJson[HostId].isLeft)
      },
      test("rejects ipv6 carrying v4 value") {
        val json = """{"type":"ipv6","value":"192.0.2.1"}"""
        assertTrue(json.fromJson[HostId].isLeft)
      },
      test("rejects fqdn that looks like IPv4") {
        // Hostname.parse already rejects IPv4 literals, so this should fail
        val json = """{"type":"fqdn","value":"192.0.2.1"}"""
        assertTrue(json.fromJson[HostId].isLeft)
      },
    ),
    suite("extensions")(
      test("value returns underlying string for all variants") {
        val f  = HostId.Fqdn(Hostname.parse("a.example").toOption.get): HostId
        val v4 = HostId.IPv4(IpAddress.parse("1.2.3.4").toOption.get): HostId
        val v6 = HostId.IPv6(IpAddress.parse("::1").toOption.get): HostId
        assertTrue(f.value == "a.example") &&
        assertTrue(v4.value == "1.2.3.4") &&
        assertTrue(v6.value == "::1")
      },
      test("kind returns discriminator") {
        val f  = HostId.Fqdn(Hostname.parse("a.example").toOption.get): HostId
        val v4 = HostId.IPv4(IpAddress.parse("1.2.3.4").toOption.get): HostId
        val v6 = HostId.IPv6(IpAddress.parse("::1").toOption.get): HostId
        assertTrue(f.kind == "fqdn") &&
        assertTrue(v4.kind == "ipv4") &&
        assertTrue(v6.kind == "ipv6")
      },
      test("isFqdn is true only for Fqdn variant") {
        val f  = HostId.Fqdn(Hostname.parse("a.example").toOption.get): HostId
        val v4 = HostId.IPv4(IpAddress.parse("1.2.3.4").toOption.get): HostId
        assertTrue(f.isFqdn) && assertTrue(!v4.isFqdn)
      },
      test("asFqdn extracts Hostname only for Fqdn") {
        val f  = HostId.Fqdn(Hostname.parse("a.example").toOption.get): HostId
        val v4 = HostId.IPv4(IpAddress.parse("1.2.3.4").toOption.get): HostId
        assertTrue(f.asFqdn.exists(_.value == "a.example")) &&
        assertTrue(v4.asFqdn.isEmpty)
      },
    ),
  )
}
