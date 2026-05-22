package wifihaven.shared.types

import zio.json.*

/**
 * Identifier for a library app template (e.g. "youtube", "tiktok"). When an app row was seeded from
 * a YAML template, `apps.template_id` carries this slug; operator-created apps leave it NULL.
 *
 * Same character class as [[BlocklistId]] (lowercase alphanumerics plus `_-`).
 */
opaque type AppTemplateId = String

object AppTemplateId {

  private val Pattern = "^[a-z0-9][a-z0-9_-]{0,63}$".r

  def parse(raw: String): Either[String, AppTemplateId] =
    if Pattern.matches(raw) then Right(raw)
    else Left(s"invalid app template id: $raw")

  def unsafe(s: String): AppTemplateId = s

  extension (a: AppTemplateId) def value: String = a

  given Ordering[AppTemplateId]         = Ordering.String
  given JsonCodec[AppTemplateId]        = JsonCodec.string.transformOrFail(parse, _.value)
  given JsonFieldEncoder[AppTemplateId] = JsonFieldEncoder.string.contramap(_.value)
  given JsonFieldDecoder[AppTemplateId] = JsonFieldDecoder.string.mapOrFail(parse)
}
