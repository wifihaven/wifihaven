package wifihaven.api.support

import zio.json.ast.Json

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * #2200 (support intake C, epic #2197) — inbound Plain webhook signature verification + minimal
 * event parsing. PURE, no external I/O, so "unsigned/forged payloads are rejected" and "a UI-
 * originated thread is recognised" are unit- and feature-testable without a live Plain. This is the
 * security boundary for `POST /api/support/webhook`: only a payload whose `Plain-Request-Signature`
 * HMAC matches the configured signing secret is acted on (design/umbrella #2206 §3).
 *
 * Signature scheme (Plain webhooks, https://www.plain.com/docs/webhooks): the raw request body is
 * signed with HMAC-SHA256 under the workspace webhook signing secret; the lowercase-hex digest is
 * sent in the `Plain-Request-Signature` header. We recompute it over the RAW body and compare in
 * constant time. The body is NEVER re-serialized before verification — a re-encode would change the
 * bytes and break the MAC.
 *
 * The parsed [[PlainNewMessageEvent]] carries only the functional fields the responder needs: the
 * thread id (to reply into), the customer external id, the `tenantIdentifier` (the household id
 * stamped on the customer by the #2199 identified-widget upsert — the UI-origin key the responder
 * gates on), the inbound message text (UNTRUSTED DATA), and a consent flag. Inbound text is quoted
 * to Claude as content, never as instructions (#2200 injection model).
 */
object PlainWebhook {

  val SignatureHeader: String = "Plain-Request-Signature"

  sealed trait VerifyError
  object VerifyError {
    case object MissingSignature extends VerifyError
    case object BadSignature     extends VerifyError
    case object MalformedPayload extends VerifyError
  }

  /**
   * Verify the `Plain-Request-Signature` header against `payload` using `secret`, then parse the
   * new-message event. Constant-time comparison avoids leaking the expected signature via timing.
   */
  def verifyAndParse(
      payload: String,
      sigHeader: Option[String],
      secret: String,
  ): Either[VerifyError, PlainNewMessageEvent] =
    for {
      header <- sigHeader.map(_.trim).filter(_.nonEmpty).toRight(VerifyError.MissingSignature)
      expected = hmacSha256Hex(secret, payload)
      _     <- Either.cond(constantTimeEquals(header, expected), (), VerifyError.BadSignature)
      event <- parseEvent(payload).toRight(VerifyError.MalformedPayload)
    } yield event

  private def hmacSha256Hex(secret: String, data: String): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    mac.doFinal(data.getBytes("UTF-8")).map(b => f"${b & 0xff}%02x").mkString
  }

  /** Length-independent constant-time compare — avoids leaking the signature via timing. */
  private def constantTimeEquals(a: String, b: String): Boolean =
    java.security.MessageDigest.isEqual(a.getBytes("UTF-8"), b.getBytes("UTF-8"))

  /**
   * Parse the Plain webhook envelope. Plain wraps every event as `{type, payload: {...}}`; the
   * new-message events (`thread.thread_created`, `thread.chat_sent`, customer replies) carry a
   * `thread` object and the message text. We pull fields leniently (absent → None / "") since the
   * exact shape varies by event type — the responder only acts when `tenantIdentifier` resolves to
   * a household, so a partially-parsed cold event simply falls through to skipped_unauthenticated.
   */
  private def parseEvent(payload: String): Option[PlainNewMessageEvent] =
    Json.decoder.decodeJson(payload).toOption.flatMap {
      case root: Json.Obj =>
        val eventType = str(root, "type").getOrElse("")
        val inner     = objField(root, "payload").getOrElse(root)
        val thread    = objField(inner, "thread")
        // tenantIdentifier is `{externalId: "..."}` on Plain; accept a bare string too.
        val tenant    = thread
          .flatMap(t => objField(t, "tenantIdentifier"))
          .flatMap(o => str(o, "externalId"))
          .orElse(thread.flatMap(t => str(t, "tenantIdentifier")))
        val threadId  =
          thread.flatMap(t => str(t, "id")).orElse(str(inner, "threadId")).getOrElse("")
        val customer  = thread
          .flatMap(t => objField(t, "customer"))
          .flatMap(c => str(c, "externalId"))
          .orElse(thread.flatMap(t => str(t, "customerId")))
          .getOrElse("")
        Some(
          PlainNewMessageEvent(
            eventType = eventType,
            threadId = threadId,
            customerExternalId = customer,
            tenantIdentifier = tenant,
            messageText = messageText(inner),
            consent = consentFlag(thread, inner),
          ),
        )
      case _              => None
    }

  // The inbound message text — the UNTRUSTED customer content. Try the common carriers in order:
  // a `chat` component, a `timelineEntry` component list, or a bare `text`/`message` field.
  private def messageText(inner: Json.Obj): String =
    objField(inner, "chat")
      .flatMap(c => str(c, "text"))
      .orElse(firstComponentText(inner))
      .orElse(str(inner, "text"))
      .orElse(objField(inner, "message").flatMap(m => str(m, "text")))
      .getOrElse("")

  private def firstComponentText(inner: Json.Obj): Option[String] =
    objField(inner, "timelineEntry")
      .flatMap(te => arrField(te, "components"))
      .flatMap(_.collectFirst {
        case c: Json.Obj if objField(c, "componentText").flatMap(t => str(t, "text")).isDefined =>
          objField(c, "componentText").flatMap(t => str(t, "text")).get
      })

  // Consent — the customer opted into per-household data access (#2241). Read from a thread label /
  // attribute or a top-level flag; absent ⇒ false (no data token minted).
  private def consentFlag(thread: Option[Json.Obj], inner: Json.Obj): Boolean =
    boolField(inner, "dataConsent")
      .orElse(thread.flatMap(t => boolField(t, "dataConsent")))
      .getOrElse(false)

  private def str(o: Json.Obj, key: String): Option[String] =
    o.fields.collectFirst { case (k, Json.Str(v)) if k == key => v }

  private def boolField(o: Json.Obj, key: String): Option[Boolean] =
    o.fields.collectFirst { case (k, Json.Bool(v)) if k == key => v }

  private def objField(o: Json.Obj, key: String): Option[Json.Obj] =
    o.fields.collectFirst { case (k, v: Json.Obj) if k == key => v }

  private def arrField(o: Json.Obj, key: String): Option[List[Json]] =
    o.fields.collectFirst { case (k, Json.Arr(v)) if k == key => v.toList }
}

/**
 * The functional slice of a Plain new-message webhook the responder acts on. `tenantIdentifier` is
 * the household id stamped on the Plain customer by the #2199 identified widget — its presence (and
 * resolution to a real household) is the UI-origin gate. `messageText` is UNTRUSTED customer
 * content (#2200 injection model): quoted to Claude as data, never as instructions.
 */
final case class PlainNewMessageEvent(
    eventType: String,
    threadId: String,
    customerExternalId: String,
    tenantIdentifier: Option[String],
    messageText: String,
    consent: Boolean,
)
