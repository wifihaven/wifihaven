package wifihaven.api.billing

import zio.json.ast.Json

/**
 * #2135: tiny helpers over a parsed JSON body — used to pull the handful of fields we need out of
 * Stripe REST responses (`id`, `url`) and webhook event payloads (`type`, and nested object fields)
 * without modeling Stripe's full schema. Pure; unit-tested alongside StripeWebhook.
 */
object StripeJson {

  private def parse(s: String): Option[Json] = Json.decoder.decodeJson(s).toOption

  /** Top-level string field, e.g. `stringField(customerJson, "id")`. */
  def stringField(json: String, key: String): Option[String] =
    parse(json).flatMap(topStringField(_, key))

  private def topStringField(j: Json, key: String): Option[String] =
    j match {
      case obj: Json.Obj => obj.fields.collectFirst { case (k, Json.Str(v)) if k == key => v }
      case _             => None
    }
}
