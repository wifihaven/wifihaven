package familydns.shared.types

import zio.json.*

/** A category slug like "ads" / "social". Lowercase alphanumerics, plus `_-`. */
opaque type BlocklistId = String

object BlocklistId {

  private val Pattern = "^[a-z0-9][a-z0-9_-]{0,63}$".r

  def parse(raw: String): Either[String, BlocklistId] =
    if Pattern.matches(raw) then Right(raw)
    else Left(s"invalid blocklist id: $raw")

  def unsafe(s: String): BlocklistId = s

  extension (b: BlocklistId) def value: String = b

  given Ordering[BlocklistId]         = Ordering.String
  given JsonCodec[BlocklistId]        = JsonCodec.string.transformOrFail(parse, _.value)
  given JsonFieldEncoder[BlocklistId] = JsonFieldEncoder.string.contramap(_.value)
  given JsonFieldDecoder[BlocklistId] = JsonFieldDecoder.string.mapOrFail(parse)
}
