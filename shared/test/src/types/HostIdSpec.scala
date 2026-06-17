package wifihaven.shared.types

import zio.json.*
import zio.test.*

object HostIdSpec extends ZIOSpecDefault {

  def spec = suite("HostId")(
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
      // #1708: distinct `label` variant so static IP-range labels (apple-push,
      // google-dns, cloudflare-dns) don't masquerade as FQDNs on the wire.
      test("encodes Label as {type:label,value:...,source:static-ip-range}") {
        val h    = HostId.Label("apple-push"): HostId
        val json = h.toJson
        assertTrue(json == """{"type":"label","value":"apple-push","source":"static-ip-range"}""")
      },
      test("round-trips Label with source") {
        val h    = HostId.Label("apple-push"): HostId
        val json = h.toJson
        assertTrue(json.fromJson[HostId].contains(h))
      },
      test("decodes Label without source (forward-compat for future label sources)") {
        val json = """{"type":"label","value":"google-dns"}"""
        assertTrue(json.fromJson[HostId].contains(HostId.Label("google-dns"): HostId))
      },
      test("decodes Label with an unknown source verbatim (forward-compat)") {
        val json = """{"type":"label","value":"future-label","source":"asn-map"}"""
        assertTrue(json.fromJson[HostId].contains(HostId.Label("future-label"): HostId))
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
      test("#1708: Label.value/kind/isFqdn/asFqdn") {
        val l: HostId = HostId.Label("apple-push")
        assertTrue(
          l.value == "apple-push",
          l.kind == "label",
          !l.isFqdn,
          l.asFqdn.isEmpty,
        )
      },
      test("#1761: decoder strips a trailing :<port> from an fqdn wire value") {
        // Older agents (and any future emitter that interns an SNI/Host-header
        // value verbatim) ship `foo.com:443`; without the tolerant strip the
        // record is rejected as "invalid hostname label 'com:443'". Symmetric
        // with the Lua agent helper (host_norm.lua):
        //  - plain `host:port`        → `host`
        //  - bracketed v6 `[v6]:port` → `[v6]` (parity; v6 doesn't take this branch in practice)
        //  - bare v6 (multi-colon)    → unchanged (ambiguous)
        val stripped =
          """{"type":"fqdn","value":"ws.nas.native-cloud.com:443"}"""
            .fromJson[HostId]
            .toOption
            .flatMap(_.asFqdn)
            .map(_.value)
        val clean    =
          """{"type":"fqdn","value":"foo.example.com"}"""
            .fromJson[HostId]
            .toOption
            .flatMap(_.asFqdn)
            .map(_.value)
        assertTrue(stripped.contains("ws.nas.native-cloud.com")) &&
        assertTrue(clean.contains("foo.example.com"))
      },
      test("#1761: decoder rejects fqdn values that are still invalid after strip") {
        // `:443` alone strips to empty → Hostname.parse fails. A bare v6
        // literal stays as-is (multi-colon, ambiguous) and Hostname.parse
        // rejects it because Hostname is not an IP literal type.
        val badEmpty = """{"type":"fqdn","value":":443"}"""
        val badV6    = """{"type":"fqdn","value":"::1"}"""
        assertTrue(badEmpty.fromJson[HostId].isLeft) &&
        assertTrue(badV6.fromJson[HostId].isLeft)
      },
    ),
  )
}
