package wifihaven.shared.types

import zio.json.*

opaque type IpAddress = String

object IpAddress {

  private val Ipv4Pattern = "^(?:0|[1-9]\\d{0,2})(?:\\.(?:0|[1-9]\\d{0,2})){3}$".r

  def parse(raw: String): Either[String, IpAddress] = {
    if raw.isEmpty then Left("empty IP address")
    else if Ipv4Pattern.matches(raw) then {
      val octets = raw.split('.').toList
      if octets.forall(o => { val n = o.toInt; n >= 0 && n <= 255 }) then Right(raw)
      else Left(s"IPv4 octet out of range: $raw")
    } else if raw.contains(':') then {
      val ia = scala.util.Try(java.net.InetAddress.getByName(raw)).toOption
      ia match {
        case Some(_: java.net.Inet6Address) => Right(raw.toLowerCase)
        case _                              => Left(s"invalid IPv6 address: $raw")
      }
    } else Left(s"invalid IP address: $raw")
  }

  def unsafe(s: String): IpAddress = s

  extension (i: IpAddress) def value: String = i

  given Ordering[IpAddress]         = Ordering.String
  given JsonCodec[IpAddress]        = JsonCodec.string.transformOrFail(parse, _.value)
  given JsonFieldEncoder[IpAddress] = JsonFieldEncoder.string.contramap(_.value)
  given JsonFieldDecoder[IpAddress] = JsonFieldDecoder.string.mapOrFail(parse)
}
