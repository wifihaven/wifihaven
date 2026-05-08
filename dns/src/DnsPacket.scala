package familydns.dns

import java.nio.ByteBuffer

/** Minimal DNS packet parser — enough for filtering, no need for full RFC 1035 */
object DnsPacket {

  val TYPE_A    = 1
  val TYPE_AAAA = 28
  val TYPE_MX   = 15
  val TYPE_TXT  = 16

  case class Query(
      id: Short,
      domain: String,
      qtype: Int,
      raw: Array[Byte],
  )

  /** Parse a raw DNS query packet. Returns None if malformed. */
  def parseQuery(data: Array[Byte]): Option[Query] = {
    if data.length < 12 then return None
    try {
      val buf     = ByteBuffer.wrap(data)
      val id      = buf.getShort
      val flags   = buf.getShort & 0xffff
      // QR bit (bit 15) must be 0 for query
      if (flags & 0x8000) != 0 then return None
      val qdCount = buf.getShort & 0xffff
      buf.getShort // anCount (skip)
      buf.getShort // nsCount (skip)
      buf.getShort // arCount (skip)

      if qdCount == 0 then return None

      val domain = readDomain(buf)
      val qtype  = buf.getShort & 0xffff
      // qclass (skip — we only handle IN = 1)

      Some(Query(id, domain, qtype, data))
    } catch case _: Exception => None
  }

  /** Read a DNS name from current buffer position, handling pointers */
  private def readDomain(buf: ByteBuffer): String = {
    val labels    = scala.collection.mutable.ListBuffer[String]()
    var jumped    = false
    var safeguard = 0

    while safeguard < 128 do {
      safeguard += 1
      val len = buf.get & 0xff
      if len == 0 then return labels.mkString(".")
      else if (len & 0xc0) == 0xc0 then {
        // Pointer
        val lo     = buf.get & 0xff
        val offset = ((len & 0x3f) << 8) | lo
        if !jumped then jumped = true
        val _      = buf.position(offset)
      } else {
        val bytes = new Array[Byte](len)
        buf.get(bytes)
        labels += new String(bytes, "ASCII")
      }
    }

    labels.mkString(".")
  }

  /**
   * Build an NXDOMAIN response for the given query. Sets QR=1, RCODE=3, copies question section,
   * zeroes answer counts.
   */
  def buildNxdomain(query: Array[Byte]): Array[Byte] = {
    if query.length < 12 then return query
    val response = query.clone()
    // Set QR=1 (response), AA=0, RCODE=3 (NXDOMAIN)
    response(2) = (response(2) | 0x80).toByte        // QR=1
    response(2) = (response(2) & 0xfb).toByte        // AA=0
    response(3) = (response(3) & 0xf0 | 0x03).toByte // RCODE=3
    // Zero out ANCOUNT, NSCOUNT, ARCOUNT
    response(6) = 0; response(7) = 0
    response(8) = 0; response(9) = 0
    response(10) = 0; response(11) = 0
    response
  }

  /**
   * Build a REFUSED response (for schedule/pause blocks — cleaner UX than NXDOMAIN which gets
   * cached by the client).
   */
  def buildRefused(query: Array[Byte]): Array[Byte] = {
    if query.length < 12 then return query
    val response = query.clone()
    response(2) = (response(2) | 0x80).toByte
    response(3) = (response(3) & 0xf0 | 0x05).toByte // RCODE=5 REFUSED
    response(6) = 0; response(7) = 0
    response(8) = 0; response(9) = 0
    response(10) = 0; response(11) = 0
    response
  }
}
