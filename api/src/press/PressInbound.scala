package wifihaven.api.press

import wifihaven.api.support.SupportService
import zio.json.ast.Json

/**
 * #2203 — inbound press-webhook signature verification + minimal envelope parsing. PURE, no
 * external I/O, so "unsigned/forged payloads are rejected" and "a well-formed press message is
 * parsed" are unit- and feature-testable without the live Cloudflare Worker. This is the security
 * boundary for `POST /api/press/inbound`.
 *
 * Signature scheme: the Cloudflare Email Worker (deploy/press-worker/) HMAC-SHA256s the RAW request
 * body under the shared `press.webhookSecret`, lowercase-hex, in the `X-WifiHaven-Signature`
 * header. We recompute over the RAW body (never re-serialized — a re-encode would change the bytes
 * and break the MAC) and compare in constant time. This is exactly the Plain/Stripe webhook shape,
 * but the envelope is OUR small JSON (the Worker's, not a vendor's):
 *
 * { "from": "reporter@example.com", "subject": "Comment for a story", "text": "…the untrusted
 * message body…", "messageId": "<abc@mail>" }
 *
 * `from` is the reply target (the responder locks it into the session token); `text` is UNTRUSTED
 * PUBLIC sender content quoted to the agent as data, never instructions (#2203 injection model).
 */
object PressInbound {

  val SignatureHeader: String = "X-WifiHaven-Signature"

  sealed trait VerifyError
  object VerifyError {
    case object MissingSignature extends VerifyError
    case object BadSignature     extends VerifyError
    case object MalformedPayload extends VerifyError
  }

  /**
   * Verify the `X-WifiHaven-Signature` header against `payload` using `secret`, then parse the
   * press envelope. Constant-time comparison avoids leaking the expected signature via timing.
   */
  def verifyAndParse(
      payload: String,
      sigHeader: Option[String],
      secret: String,
  ): Either[VerifyError, PressInboundEvent] =
    for {
      header <- sigHeader.map(_.trim).filter(_.nonEmpty).toRight(VerifyError.MissingSignature)
      expected = SupportService.hmacSha256Hex(secret, payload)
      _     <- Either.cond(constantTimeEquals(header, expected), (), VerifyError.BadSignature)
      event <- parse(payload).toRight(VerifyError.MalformedPayload)
    } yield event

  /** Length-independent constant-time compare — avoids leaking the signature via timing. */
  private def constantTimeEquals(a: String, b: String): Boolean =
    java.security.MessageDigest.isEqual(a.getBytes("UTF-8"), b.getBytes("UTF-8"))

  // A well-formed press event needs a `from` (the reply target) and some `text`; `subject` and
  // `messageId` default to empty. A missing `from`/`text` is a malformed payload (the responder has
  // no one to reply to / nothing to answer), not a silent skip.
  private def parse(payload: String): Option[PressInboundEvent] =
    Json.decoder.decodeJson(payload).toOption.flatMap {
      case root: Json.Obj =>
        // `from` is used verbatim as the outbound reply recipient, so strip CR/LF/control chars —
        // the reply is emailed autonomously, and a control char in the recipient has no legitimate
        // place. (The Cloudflare Worker sends a parsed address; this is defense-in-depth.)
        val from = str(root, "from").map(stripControl).map(_.trim).filter(_.nonEmpty)
        val text = str(root, "text").filter(_.trim.nonEmpty)
        (from, text) match {
          case (Some(f), Some(t)) =>
            Some(
              PressInboundEvent(
                from = f,
                // The subject is attacker-controlled and lands in the outbound reply's Subject
                // header (via the session token); strip control chars for the same defense-in-depth
                // reason as `from` — an autonomous email header must not carry CR/LF material. (Resend
                // JSON-encodes the subject so raw injection is already prevented; this is belt+braces.)
                subject = str(root, "subject").map(stripControl).getOrElse(""),
                messageText = t,
                messageId = str(root, "messageId").map(stripControl).getOrElse(""),
              ),
            )
          case _                  => None
        }
      case _              => None
    }

  private def str(o: Json.Obj, key: String): Option[String] =
    o.fields.collectFirst { case (k, Json.Str(v)) if k == key => v }

  // Strip control characters (incl. CR/LF) — attacker-controlled fields that flow into an autonomous
  // email (the recipient, and the subject/message-id that ride the reply headers) must not carry
  // header-injection material. The recipient is additionally trimmed by the caller.
  private def stripControl(s: String): String =
    s.filter(c => !c.isControl)
}

/**
 * The functional slice of a press inbound message the responder acts on. `from` is the reply target
 * (locked into the session token). `messageText` is UNTRUSTED PUBLIC sender content (#2203
 * injection model): quoted to the agent as data, never as instructions.
 */
final case class PressInboundEvent(
    from: String,
    subject: String,
    messageText: String,
    messageId: String,
)
