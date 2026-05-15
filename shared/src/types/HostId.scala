package familydns.shared.types

import zio.json.*

/**
 * A `HostId` identifies *something* a device contacted: either a resolved hostname (the common
 * case) or a bare IP literal (direct-IP traffic, DoH- resolved domains, sidecar races where DNS
 * attribution missed).
 *
 * The architecture (docs/architecture.md §6.2, §7.2, fixes #391) treats the "what host did the
 * device contact" field as a tagged union rather than an unconstrained string, so:
 *   - site-limit pattern matching (`*.example.com`) can correctly skip IP rows that could never
 *     match;
 *   - the admin UI can render IPv4/IPv6 traffic distinguishably from named hosts;
 *   - storage carries `host_type` alongside `host_value` so reads and queries don't re-guess the
 *     type from the string.
 */
enum HostId {
  case Fqdn(name: Hostname)
  case IPv4(addr: IpAddress)
  case IPv6(addr: IpAddress)
}

object HostId {

  /** Construct a `HostId` from a Hostname (always FQDN). */
  def fqdn(h: Hostname): HostId = Fqdn(h)

  /**
   * Construct a `HostId` from an IpAddress, picking the right variant by looking at the address
   * form. Caller must have already validated the IpAddress; the `:` test is unambiguous because
   * IpAddress.parse already rejected mixed-form garbage.
   */
  def ip(addr: IpAddress): HostId =
    if addr.value.contains(':') then IPv6(addr) else IPv4(addr)

  /**
   * Best-effort parse of a raw string into a `HostId`. Used **only** for the one-shot DB migration
   * of legacy `hostname` columns whose contents predate the tagged-union wire contract. Errs on
   * FQDN when ambiguous (per #391).
   */
  def parseLegacy(raw: String): Either[String, HostId] =
    IpAddress.parse(raw) match {
      case Right(ip) =>
        Right(if ip.value.contains(':') then IPv6(ip) else IPv4(ip))
      case Left(_)   =>
        Hostname.parse(raw).map(Fqdn(_))
    }

  // ── Wire form: {"type":"fqdn"|"ipv4"|"ipv6","value":"..."} ─────────────

  private case class Wire(`type`: String, value: String) derives JsonCodec

  given JsonCodec[HostId] = JsonCodec[Wire].transformOrFail(
    {
      case Wire("fqdn", v) => Hostname.parse(v).map(Fqdn(_))
      case Wire("ipv4", v) =>
        IpAddress.parse(v).flatMap { ip =>
          if ip.value.contains(':') then Left(s"ipv4 host carried v6 value: $v")
          else Right(IPv4(ip))
        }
      case Wire("ipv6", v) =>
        IpAddress.parse(v).flatMap { ip =>
          if ip.value.contains(':') then Right(IPv6(ip))
          else Left(s"ipv6 host carried v4 value: $v")
        }
      case Wire(other, _)  =>
        Left(s"unknown host type: $other")
    },
    {
      case Fqdn(h) => Wire("fqdn", h.value)
      case IPv4(a) => Wire("ipv4", a.value)
      case IPv6(a) => Wire("ipv6", a.value)
    },
  )

  extension (h: HostId) {

    /** The raw string value, for display, logging, or DB column. */
    def value: String = h match {
      case Fqdn(name) => name.value
      case IPv4(addr) => addr.value
      case IPv6(addr) => addr.value
    }

    /** The discriminator tag, for DB column or display. */
    def kind: String = h match {
      case _: Fqdn => "fqdn"
      case _: IPv4 => "ipv4"
      case _: IPv6 => "ipv6"
    }

    /**
     * True iff this is a resolved hostname (and thus eligible for FQDN pattern matching like
     * `*.example.com`).
     */
    def isFqdn: Boolean = h.isInstanceOf[Fqdn]

    /** Pull out the FQDN if this is one — useful for matchers that only apply to named hosts. */
    def asFqdn: Option[Hostname] = h match {
      case Fqdn(n) => Some(n)
      case _       => None
    }
  }

  given Ordering[HostId] = Ordering.by((h: HostId) => (h.kind, h.value))
}
