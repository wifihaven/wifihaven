package familydns.dns.unit

import familydns.dns.DnsPacket
import zio.test.*
import zio.test.Assertion.*

/** Unit tests for DNS packet parsing edge cases. */
object DnsPacketSpec extends ZIOSpecDefault {

  // A minimal valid DNS query for "google.com" type A
  // Built manually to test parsing without depending on a DNS library
  private def buildQuery(domain: String, qtype: Int = 1): Array[Byte] = {
    val header   = Array[Byte](
      0x00, 0x01, // ID = 1
      0x01, 0x00, // Flags: standard query
      0x00, 0x01, // QDCOUNT = 1
      0x00, 0x00, // ANCOUNT = 0
      0x00, 0x00, // NSCOUNT = 0
      0x00, 0x00, // ARCOUNT = 0
    )
    val labels   = domain.split('.').flatMap { label =>
      val bytes = label.getBytes("ASCII")
      bytes.length.toByte +: bytes
    }
    val question = labels ++ Array[Byte](
      0x00,                // root label
      (qtype >> 8).toByte, // QTYPE high
      qtype.toByte,        // QTYPE low
      0x00,
      0x01,                // QCLASS IN
    )
    header ++ question
  }

  def spec = suite("DnsPacket")(
    suite("parseQuery")(
      test("parses a simple A query") {
        val data  = buildQuery("google.com")
        val query = DnsPacket.parseQuery(data)
        assertTrue(query.isDefined) &&
        assertTrue(query.get.domain == "google.com") &&
        assertTrue(query.get.qtype == DnsPacket.TYPE_A)
      },
      test("parses an AAAA query") {
        val data  = buildQuery("example.com", DnsPacket.TYPE_AAAA)
        val query = DnsPacket.parseQuery(data)
        assertTrue(query.isDefined) &&
        assertTrue(query.get.qtype == DnsPacket.TYPE_AAAA)
      },
      test("parses multi-label domain") {
        val data  = buildQuery("www.sub.example.co.uk")
        val query = DnsPacket.parseQuery(data)
        assertTrue(query.get.domain == "www.sub.example.co.uk")
      },
      test("returns None for packet too short") {
        val data = Array.fill[Byte](5)(0)
        assertTrue(DnsPacket.parseQuery(data).isEmpty)
      },
      test("returns None for response packet (QR bit set)") {
        val data     = buildQuery("google.com")
        val response = data.clone()
        response(2) = (response(2) | 0x80).toByte // set QR bit
        assertTrue(DnsPacket.parseQuery(response).isEmpty)
      },
      test("returns None for empty byte array") {
        assertTrue(DnsPacket.parseQuery(Array.empty).isEmpty)
      },
    ),
    suite("buildNxdomain")(
      test("sets QR bit in response") {
        val query    = buildQuery("blocked.com")
        val response = DnsPacket.buildNxdomain(query)
        assertTrue((response(2) & 0x80) != 0)
      },
      test("sets RCODE to 3 (NXDOMAIN)") {
        val query    = buildQuery("blocked.com")
        val response = DnsPacket.buildNxdomain(query)
        assertTrue((response(3) & 0x0f) == 3)
      },
      test("zeroes answer count") {
        val query    = buildQuery("blocked.com")
        val response = DnsPacket.buildNxdomain(query)
        assertTrue(response(6) == 0 && response(7) == 0)
      },
      test("preserves original query ID") {
        val query    = buildQuery("blocked.com")
        val response = DnsPacket.buildNxdomain(query)
        assertTrue(response(0) == query(0) && response(1) == query(1))
      },
    ),
    suite("buildRefused")(
      test("sets RCODE to 5 (REFUSED)") {
        val query    = buildQuery("blocked.com")
        val response = DnsPacket.buildRefused(query)
        assertTrue((response(3) & 0x0f) == 5)
      },
      test("sets QR bit") {
        val query    = buildQuery("blocked.com")
        val response = DnsPacket.buildRefused(query)
        assertTrue((response(2) & 0x80) != 0)
      },
    ),
  )
}
