package familydns.shared.types

import zio.json.*

opaque type MacAddress = String

object MacAddress {

  private val Pattern = "^(?:[0-9a-f]{2}:){5}[0-9a-f]{2}$|^(?:[0-9a-f]{2}-){5}[0-9a-f]{2}$".r

  def parse(raw: String): Either[String, MacAddress] = {
    val lower = raw.toLowerCase
    if Pattern.matches(lower) then Right(lower.replace('-', ':'))
    else Left(s"invalid MAC address: $raw")
  }

  /** Unchecked constructor — only safe when the source has already been validated. */
  def unsafe(s: String): MacAddress = s

  extension (m: MacAddress) def value: String = m

  given Ordering[MacAddress]         = Ordering.String
  given JsonCodec[MacAddress]        = JsonCodec.string.transformOrFail(parse, _.value)
  given JsonFieldEncoder[MacAddress] = JsonFieldEncoder.string.contramap(_.value)
  given JsonFieldDecoder[MacAddress] = JsonFieldDecoder.string.mapOrFail(parse)
}
