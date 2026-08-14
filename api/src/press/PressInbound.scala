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
 *
 * #2442 adds one additive field, `"loopGuard": "auto_submitted"` — the Worker's verdict that this
 * delivery is machine-generated (an out-of-office, a mailing-list post, a bounce). Absent or empty
 * means a person wrote it. Additive per the wire rule: an API build that predates the field ignores
 * it, and a Worker that predates it simply never sends one.
 */
object PressInbound {

  val SignatureHeader: String = "X-WifiHaven-Signature"

  /**
   * What a signature-valid body turned out to be. [[Message]] is a press inquiry to answer;
   * [[AutoSubmitted]] is one the Cloudflare Worker classified as machine-generated (#2442) — read
   * BEFORE the from/text requirement, because a bounce/DSN carries neither and would otherwise land
   * in `MalformedPayload` where the loop is invisible.
   */
  sealed trait Verified
  object Verified {
    final case class Message(event: PressInboundEvent) extends Verified

    /**
     * `messageId` is carried for ONE reason: it is the join key. The API's WARN log names the
     * marker but deliberately not the sender; the Worker's log line names the sender. Without a
     * shared id, reconciling "which message did we refuse to answer" across the two logs falls back
     * to timestamp guessing — and that reconciliation is the whole recovery path for a journalist
     * misclassified as an autoresponder. Empty when the inbound carried no Message-ID (a bounce
     * often does not).
     */
    final case class AutoSubmitted(marker: LoopGuardMarker, messageId: String) extends Verified
  }

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
  ): Either[VerifyError, Verified] =
    for {
      header <- sigHeader.map(_.trim).filter(_.nonEmpty).toRight(VerifyError.MissingSignature)
      expected = SupportService.hmacSha256Hex(secret, payload)
      _      <- Either.cond(constantTimeEquals(header, expected), (), VerifyError.BadSignature)
      // #2442: the Worker's loop-guard verdict is read BEFORE the from/text requirement, and
      // short-circuits it. A bounce/DSN is the commonest thing that closes the loop and it arrives
      // with a null return path — i.e. exactly the envelope `parse` calls malformed. Reading the
      // marker first is what makes that drop show up as a LOOP GUARD skip on its own series
      // rather than disappearing into `outcome=malformed`, where nobody could tell an
      // autoresponder from a broken Worker.
      json   <- Json.decoder.decodeJson(payload).toOption.toRight(VerifyError.MalformedPayload)
      result <- json match {
        case root: Json.Obj =>
          loopMarker(root) match {
            case Some(marker) =>
              Right(Verified.AutoSubmitted(marker, messageId(root)))
            case None         =>
              parse(root).map(Verified.Message.apply).toRight(VerifyError.MalformedPayload)
          }
        case _              => Left(VerifyError.MalformedPayload)
      }
    } yield result

  /** Length-independent constant-time compare — avoids leaking the signature via timing. */
  private def constantTimeEquals(a: String, b: String): Boolean =
    java.security.MessageDigest.isEqual(a.getBytes("UTF-8"), b.getBytes("UTF-8"))

  // A well-formed press event needs a `from` (the reply target) and some `text`; `subject`,
  // `messageId` and `references` default to empty. A missing `from`/`text` is a malformed payload (the responder has
  // no one to reply to / nothing to answer), not a silent skip.
  private def parse(root: Json.Obj): Option[PressInboundEvent] = {
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
            messageId = messageId(root),
            // #2467 — the inbound `References` chain, taken RAW apart from a length cap. It is
            // deliberately NOT control-stripped here: deleting a smuggled CRLF would glue
            // `<a@x>\r\nBcc: …` into one unparseable token and cost the reply an id it
            // legitimately had. `EmailSender.normalizeReferences` is the single sanitiser (a
            // msg-id whitelist, so nothing else can survive to a header) and the responder runs
            // it before anything is persisted or minted. The cap here only bounds the work that
            // sanitiser has to do; the whole request body is already capped upstream.
            references = str(root, "references").map(_.take(MaxRawReferencesChars)).getOrElse(""),
          ),
        )
      case _                  => None
    }
  }

  // Trimmed as well as control-stripped. The id is bounded downstream (PressResponder truncates it
  // before minting), so leading whitespace left on it would eat into that budget and could chop the
  // closing `>` off an otherwise-valid id — silently costing the reply its thread (#2451). ONE
  // reader for both the dispatch path and the #2442 loop-guard log, so the two can never disagree
  // about what this message's id is.
  private def messageId(root: Json.Obj): String =
    str(root, "messageId").map(stripControl).map(_.trim).getOrElse("")

  // #2442: the Worker's auto-reply/DSN verdict. Absent (a pre-#2442 Worker) or empty (classified,
  // nothing found) both mean "a person wrote this" — only a non-empty value is a skip.
  private def loopMarker(root: Json.Obj): Option[LoopGuardMarker] =
    str(root, "loopGuard")
      .map(stripControl)
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(LoopGuardMarker.fromWire)

  // #2467 — a generous cap on the RAW inbound `References` before normalisation. Normalisation
  // bounds the result to one header line (986 chars); this only stops a pathological header from
  // making the parse itself do unbounded work. Sized well above any real chain: a long-running
  // thread of 100-char ids fills one header line in ~10 entries.
  private val MaxRawReferencesChars: Int = 8 * 1024

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
    // #2467 — the RAW inbound `References` header (length-capped only). Sanitised by
    // `EmailSender.normalizeReferences` in the responder, before it is persisted or minted.
    references: String,
)

/**
 * #2442 — WHY the press Worker classified an inbound delivery as auto-submitted. The Worker detects
 * (only it can see the raw MIME headers — deploy/press-worker/src/loop-guard.ts); the responder
 * ENFORCES and meters, because dispatch and the metric pipeline live here.
 *
 * This is the `press_loop_guard_total{reason}` label set, and the reason the wire value is mapped
 * through [[fromWire]] rather than passed through: the label space must be bounded by THIS build
 * (§4 cardinality firewall), so a newer Worker's unrecognized marker collapses to [[Unknown]] —
 * still a skip (fail closed: an unrecognized marker is still a Worker saying "machine-generated"),
 * never a new series.
 */
enum LoopGuardMarker {
  case AutoSubmitted
  case Precedence
  case AutoResponseSuppress
  case ListId
  case NullReturnPath
  case Unknown
}

object LoopGuardMarker {
  def fromWire(s: String): LoopGuardMarker = s.toLowerCase match {
    case "auto_submitted"           => AutoSubmitted
    case "precedence"               => Precedence
    case "x_auto_response_suppress" => AutoResponseSuppress
    case "list_id"                  => ListId
    case "null_return_path"         => NullReturnPath
    case _                          => Unknown
  }

  def label(m: LoopGuardMarker): String = m match {
    case AutoSubmitted        => "auto_submitted"
    case Precedence           => "precedence"
    case AutoResponseSuppress => "x_auto_response_suppress"
    case ListId               => "list_id"
    case NullReturnPath       => "null_return_path"
    case Unknown              => "unknown"
  }
}
