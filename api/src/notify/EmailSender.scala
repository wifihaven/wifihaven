package wifihaven.api.notify

import wifihaven.api.EmailConfig
import zio.*
import zio.json.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration as JDuration

/**
 * #578 — the outbound email transport behind [[Notifier]]. Deliberately a tiny one-method trait so
 * the notifier depends on "send an email", not on Resend specifics, and tests inject a recorder
 * (see [[EmailSender.recording]]) instead of hitting the network.
 *
 * Fail-open by construction: `send` returns a UIO that never fails — a transport error is logged
 * and surfaced as [[EmailOutcome.Failed]], so a notification hiccup can never take down the fiber
 * that raised the alert (the in-app banner remains the authoritative surface either way).
 */
trait EmailSender {

  /** Send one email. `htmlBody` is the pre-rendered HTML. Never fails. */
  def send(to: String, subject: String, htmlBody: String): UIO[EmailOutcome]

  /**
   * #2233 — send one email with an explicit envelope FROM override and an optional Reply-To. Used
   * by the press-OUTREACH path, where the release must be FROM the press address (not the alerts@
   * notification sender [[EmailConfig.fromAddress]] this trait's other callers use) and REPLY-TO
   * must point at the press inbox so a journalist's reply routes to the #2203 responder.
   *
   * #2451 — `inReplyTo` is the RFC 5322 `Message-ID` of the message being replied to. When set, the
   * transport emits BOTH `In-Reply-To` and `References` with that value, so the reply threads under
   * the original in any compliant client. ONE parameter renders both headers (they carry the same
   * single id) rather than two that could disagree. Used by the press RESPONDER path, where the id
   * rides the signed [[wifihaven.api.press.PressToken]] so a hijacked agent cannot repoint the
   * thread.
   *
   * Default implementation ignores the envelope override and the threading id and delegates to
   * [[send]] — so the [[EmailSender.Disabled]] no-op and any transport that doesn't support
   * per-send headers are correct without change. [[EmailSender.Resend]] overrides it to set `from`
   * + `reply_to` + the threading headers.
   */
  def sendAs(
      from: String,
      replyTo: Option[String],
      to: String,
      subject: String,
      htmlBody: String,
      inReplyTo: Option[String] = None,
  ): UIO[EmailOutcome] =
    send(to, subject, htmlBody)
}

/** Result of an attempted send — also the bounded label space for the notify metric. */
enum EmailOutcome {
  case Sent
  case Failed
  case Disabled
}

object EmailOutcome {
  def label(o: EmailOutcome): String = o match {
    case Sent     => "sent"
    case Failed   => "failed"
    case Disabled => "skipped_disabled"
  }
}

object EmailSender {

  /** The User-Agent we send to Resend. */
  private val UserAgent: String = "wifihaven-api/1 (+https://wifihaven.net)"

  private val ResendEndpoint: String = "https://api.resend.com/emails"

  // Send is a single tiny JSON POST (one email), so these are deliberately shorter than
  // BlocklistFetcher's 30s/60s (which pulls multi-MB upstream lists). A notification is
  // best-effort and fail-open, so a slow Resend shouldn't tie up the alert fiber for long.
  private val ConnectTimeout: JDuration = JDuration.ofSeconds(10)
  private val RequestTimeout: JDuration = JDuration.ofSeconds(20)

  /**
   * Config-gated layer. When [[EmailConfig.enabled]] is false (the self-hosted default and any
   * deployment missing either secret) this yields a no-op sender that returns
   * [[EmailOutcome.Disabled]] without touching the network — so the whole feature ships dark until
   * the operator sets the two Resend secrets. When enabled it yields the live Resend HTTP client.
   */
  val layer: ZLayer[EmailConfig, Nothing, EmailSender] =
    ZLayer.fromFunction { (cfg: EmailConfig) =>
      if cfg.enabled then new Resend(cfg): EmailSender
      else Disabled
    }

  /**
   * #2451 — normalize a `Message-ID` for header use: strip control characters, trim, and drop it
   * entirely if nothing is left. This is the authoritative sanitizer for the threading headers, and
   * it sits HERE (at the header-render boundary) because the id is attacker-controlled — it comes
   * from the sender's own mail client — and flows into an outbound email header. `PressInbound`
   * also strips control chars when it parses the inbound envelope; that is belt-and-braces at
   * ingest, this is the one that guards the write.
   *
   * Public so the recording senders normalize identically to [[Resend]] and specs can assert the
   * value that would really be emitted.
   */
  def threadingId(raw: Option[String]): Option[String] =
    raw.map(_.filter(c => !c.isControl).trim).filter(_.nonEmpty)

  /**
   * #2451 — the RFC 5322 threading pair Resend renders verbatim. Both headers carry the SAME single
   * id (this is a first-level reply to the journalist's original), so there is nothing to disagree.
   */
  private def threadingHeaders(inReplyTo: Option[String]): Option[Map[String, String]] =
    threadingId(inReplyTo).map(id => Map("In-Reply-To" -> id, "References" -> id))

  /** No-op sender used when email is unconfigured. */
  val Disabled: EmailSender = new EmailSender {
    def send(to: String, subject: String, htmlBody: String): UIO[EmailOutcome] =
      ZIO.succeed(EmailOutcome.Disabled)
  }

  private final case class ResendRequest(
      from: String,
      to: List[String],
      subject: String,
      html: String,
      // #2233 — Resend's `reply_to` field (snake_case on the wire). Only set for the outreach path
      // via `sendAs`; `None` for the notification path so the JSON is byte-identical to before.
      reply_to: Option[List[String]] = None,
      // #2451 — Resend's arbitrary-header escape hatch. Only set for the press-RESPONDER path (the
      // RFC 5322 `In-Reply-To` / `References` threading pair); `None` everywhere else, so the JSON
      // stays byte-identical for the notification and outreach paths.
      headers: Option[Map[String, String]] = None,
  )
  private object ResendRequest {
    given JsonEncoder[ResendRequest] = DeriveJsonEncoder.gen[ResendRequest]
  }

  /**
   * Live Resend transport. One blocking HTTPS POST per send (same JDK-HttpClient /
   * `attemptBlocking` shape as [[wifihaven.api.BlocklistFetcher]] — no new build dependency). Any
   * non-2xx or thrown error is logged and mapped to [[EmailOutcome.Failed]]; `send` never fails.
   */
  final class Resend(cfg: EmailConfig) extends EmailSender {
    private val client = HttpClient
      .newBuilder()
      .connectTimeout(ConnectTimeout)
      .build()

    def send(to: String, subject: String, htmlBody: String): UIO[EmailOutcome] =
      post(ResendRequest(cfg.fromTrimmed, List(to), subject, htmlBody))

    // #2233 — the outreach envelope: FROM the press address, REPLY-TO the press inbox. A blank
    // `from` falls back to the configured notification sender so a misconfig can't send with an
    // empty From header. `replyTo` is set only when non-blank.
    override def sendAs(
        from: String,
        replyTo: Option[String],
        to: String,
        subject: String,
        htmlBody: String,
        inReplyTo: Option[String],
    ): UIO[EmailOutcome] = {
      val fromHeader  = Option(from).map(_.trim).filter(_.nonEmpty).getOrElse(cfg.fromTrimmed)
      val replyHeader =
        replyTo.map(_.trim).filter(_.nonEmpty).map(List(_))
      post(
        ResendRequest(
          fromHeader,
          List(to),
          subject,
          htmlBody,
          replyHeader,
          threadingHeaders(inReplyTo),
        ),
      )
    }

    private def post(request: ResendRequest): UIO[EmailOutcome] =
      ZIO
        .attemptBlocking {
          val payload = request.toJson
          val req     = HttpRequest
            .newBuilder(URI.create(ResendEndpoint))
            .header("Authorization", s"Bearer ${cfg.apiKeyTrimmed}")
            .header("Content-Type", "application/json")
            .header("User-Agent", UserAgent)
            .timeout(RequestTimeout)
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
          client.send(req, HttpResponse.BodyHandlers.ofString())
        }
        .flatMap { resp =>
          if resp.statusCode() / 100 == 2 then ZIO.succeed(EmailOutcome.Sent)
          else
            ZIO
              .logWarning(
                s"email send failed: HTTP ${resp.statusCode()} from Resend (body: ${resp.body().take(500)})",
              )
              .as(EmailOutcome.Failed)
        }
        .catchAll(e =>
          ZIO.logWarning(s"email send errored: ${e.getMessage}").as(EmailOutcome.Failed),
        )
  }

  /**
   * Test/inspection sender: records every send into a `Ref` and always reports
   * [[EmailOutcome.Sent]]. Used by the feature suite to assert the notifier addressed the right
   * recipient without a network.
   */
  final case class Sent(
      to: String,
      subject: String,
      htmlBody: String,
      // #2233 — the envelope override captured on the `sendAs` path (press outreach). `None` on the
      // plain `send` path, so existing recorders/assertions are unaffected.
      from: Option[String] = None,
      replyTo: Option[String] = None,
      // #2451 — the RFC 5322 threading pair actually emitted (both carry the same normalized
      // Message-ID). `None` when the reply cannot thread, and on the plain `send` path.
      inReplyTo: Option[String] = None,
      references: Option[String] = None,
  )

  /** The `Sent` row a `sendAs` call would produce — normalized exactly as [[Resend]] would emit. */
  private def sentAs(
      from: String,
      replyTo: Option[String],
      to: String,
      subject: String,
      htmlBody: String,
      inReplyTo: Option[String],
  ): Sent = {
    val id = threadingId(inReplyTo)
    Sent(to, subject, htmlBody, Some(from), replyTo, id, id)
  }

  def recording(ref: Ref[List[Sent]]): EmailSender = new EmailSender {
    def send(to: String, subject: String, htmlBody: String): UIO[EmailOutcome] =
      ref.update(_ :+ Sent(to, subject, htmlBody)).as(EmailOutcome.Sent)

    // #2233 — record the FROM + Reply-To so the outreach specs can assert the release goes out FROM
    // the press address and REPLIES route to the press inbox. #2451 adds the threading pair.
    override def sendAs(
        from: String,
        replyTo: Option[String],
        to: String,
        subject: String,
        htmlBody: String,
        inReplyTo: Option[String],
    ): UIO[EmailOutcome] =
      ref
        .update(_ :+ sentAs(from, replyTo, to, subject, htmlBody, inReplyTo))
        .as(EmailOutcome.Sent)
  }

  /**
   * #2233 — a recording sender that reports [[EmailOutcome.Failed]] for a chosen set of recipients
   * (and [[EmailOutcome.Sent]] otherwise), so send specs can exercise the failed-send branch
   * (metric label, ledger `outcome=failed`, not marking the contact done) without a live transport.
   */
  def recordingWithFailures(ref: Ref[List[Sent]], failFor: Set[String]): EmailSender =
    new EmailSender {
      private def outcome(to: String): EmailOutcome                              =
        if failFor.contains(to) then EmailOutcome.Failed else EmailOutcome.Sent
      def send(to: String, subject: String, htmlBody: String): UIO[EmailOutcome] =
        ref.update(_ :+ Sent(to, subject, htmlBody)).as(outcome(to))
      override def sendAs(
          from: String,
          replyTo: Option[String],
          to: String,
          subject: String,
          htmlBody: String,
          inReplyTo: Option[String],
      ): UIO[EmailOutcome] =
        ref.update(_ :+ sentAs(from, replyTo, to, subject, htmlBody, inReplyTo)).as(outcome(to))
    }
}
