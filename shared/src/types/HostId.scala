package wifihaven.shared.types

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
  // #1708: A synthetic name attached by the agent because the destination IP fell
  // inside a hardcoded operator-curated range (apple-push, google-dns, cloudflare-dns;
  // see openwrt static_ip_labels). NOT a hostname the agent ever observed at the
  // resolver — it cannot be pattern-matched against `*.example.com` and `asFqdn`
  // returns None, the same as IP literals. The label string is intentionally a
  // plain `String` (not a validated opaque type): label validity is enforced
  // UPSTREAM by the curated `static_ip_labels.lua` map; the decoder's only
  // defense here is rejecting an empty value.
  case Label(name: String)
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

  // ── Wire form: {"type":"fqdn"|"ipv4"|"ipv6"|"label","value":"...","source":"..."?} ──
  //
  // #1708: `label`-typed hosts carry an optional `source` field naming the
  // attribution path that produced the label (today: `static-ip-range`). The
  // decoder is tolerant of source's absence so a future label source (e.g. an
  // ASN-based map) can ship without a wire break; the encoder always emits the
  // source for forward observability.

  private case class Wire(
      `type`: String,
      value: String,
      source: Option[String] = None,
  ) derives JsonCodec

  /** The single label source today — see openwrt `static_ip_labels.lua` (#1655). */
  val LabelSourceStaticIpRange: String = "static-ip-range"

  // #1761: tolerantly strip a trailing `:<port>` from an fqdn wire value.
  // Older agents (and any future emitter that interns an SNI/Host-header value
  // verbatim) can ship `ws.nas.native-cloud.com:443`; without this strip the
  // decoder rejects "com:443" as an invalid Hostname label and the record is
  // metered as a decode failure. Defense-in-depth: agent emitters also strip
  // at the wire (openwrt host_norm.lua), so newer fleets never produce the
  // suffix — this branch keeps the API tolerant of legacy / re-introduced
  // emitters. Bare IPv6 literals contain colons too; they're emitted as
  // type="ipv6" so they don't take this path, but as a guard we only strip
  // when exactly one colon is present (a plain `host:port`).
  private val FqdnPortSuffix                       = "^([^:\\[\\]]+):\\d+$".r
  private def stripPortFromFqdn(v: String): String = v match {
    case FqdnPortSuffix(host) => host
    case _                    => v
  }

  given JsonCodec[HostId] = JsonCodec[Wire].transformOrFail(
    {
      case Wire("fqdn", v, _)  => Hostname.parse(stripPortFromFqdn(v)).map(Fqdn(_))
      case Wire("ipv4", v, _)  =>
        IpAddress.parse(v).flatMap { ip =>
          if ip.value.contains(':') then Left(s"ipv4 host carried v6 value: $v")
          else Right(IPv4(ip))
        }
      case Wire("ipv6", v, _)  =>
        IpAddress.parse(v).flatMap { ip =>
          if ip.value.contains(':') then Right(IPv6(ip))
          else Left(s"ipv6 host carried v4 value: $v")
        }
      case Wire("label", v, _) =>
        if v.isEmpty then Left("label host has empty value")
        else Right(Label(v))
      case Wire(other, _, _)   =>
        Left(s"unknown host type: $other")
    },
    {
      case Fqdn(h)  => Wire("fqdn", h.value, None)
      case IPv4(a)  => Wire("ipv4", a.value, None)
      case IPv6(a)  => Wire("ipv6", a.value, None)
      case Label(v) => Wire("label", v, Some(LabelSourceStaticIpRange))
    },
  )

  extension (h: HostId) {

    /** The raw string value, for display, logging, or DB column. */
    def value: String = h match {
      case Fqdn(name)  => name.value
      case IPv4(addr)  => addr.value
      case IPv6(addr)  => addr.value
      case Label(name) => name
    }

    /** The discriminator tag, for DB column or display. */
    def kind: String = h match {
      case _: Fqdn  => "fqdn"
      case _: IPv4  => "ipv4"
      case _: IPv6  => "ipv6"
      case _: Label => "label"
    }

    /**
     * True iff this is a resolved hostname (and thus eligible for FQDN pattern matching like
     * `*.example.com`).
     */
    def isFqdn: Boolean = h.isInstanceOf[Fqdn]

    /**
     * Pull out the FQDN if this is one — useful for matchers that only apply to named hosts.
     * Returns None for IP literals AND for synthetic Label hosts (#1708): labels are not domain
     * names and must never be pattern-matched against `*.example.com`.
     */
    def asFqdn: Option[Hostname] = h match {
      case Fqdn(n) => Some(n)
      case _       => None
    }
  }

  given Ordering[HostId] = Ordering.by((h: HostId) => (h.kind, h.value))
}
