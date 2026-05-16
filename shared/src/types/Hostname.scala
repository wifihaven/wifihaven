package wifihaven.shared.types

import zio.json.*

opaque type Hostname = String

object Hostname {

  private val LabelPattern = "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$".r
  private val Ipv4Like     = "^\\d{1,3}(?:\\.\\d{1,3}){3}$".r

  def parse(raw: String): Either[String, Hostname] = {
    if raw.isEmpty then Left("empty hostname")
    else {
      val noTrail = if raw.endsWith(".") then raw.dropRight(1) else raw
      val lower   = noTrail.toLowerCase
      if lower.isEmpty then Left("hostname is just '.'")
      else if lower.length > 253 then Left(s"hostname too long: ${lower.length} > 253")
      else if Ipv4Like.matches(lower) then Left(s"IPv4 literal is not a hostname: $raw")
      else {
        val labels  = lower.split('.').toList
        val invalid = labels.find(l => l.isEmpty || l.length > 63 || !LabelPattern.matches(l))
        invalid match {
          case Some(l) => Left(s"invalid hostname label '$l' in $raw")
          case None    => Right(lower)
        }
      }
    }
  }

  def unsafe(s: String): Hostname = s

  extension (h: Hostname) def value: String = h

  given Ordering[Hostname]         = Ordering.String
  given JsonCodec[Hostname]        = JsonCodec.string.transformOrFail(parse, _.value)
  given JsonFieldEncoder[Hostname] = JsonFieldEncoder.string.contramap(_.value)
  given JsonFieldDecoder[Hostname] = JsonFieldDecoder.string.mapOrFail(parse)
}
