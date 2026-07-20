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
   * Default implementation ignores the envelope override and delegates to [[send]] — so the
   * [[EmailSender.Disabled]] no-op and any transport that doesn't support a per-send From are
   * correct without change. [[EmailSender.Resend]] overrides it to set `from` + `reply_to`.
   */
  def sendAs(
      from: String,
      replyTo: Option[String],
      to: String,
      subject: String,
      htmlBody: String,
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
    ): UIO[EmailOutcome] = {
      val fromHeader  = Option(from).map(_.trim).filter(_.nonEmpty).getOrElse(cfg.fromTrimmed)
      val replyHeader =
        replyTo.map(_.trim).filter(_.nonEmpty).map(List(_))
      post(ResendRequest(fromHeader, List(to), subject, htmlBody, replyHeader))
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
  )

  def recording(ref: Ref[List[Sent]]): EmailSender = new EmailSender {
    def send(to: String, subject: String, htmlBody: String): UIO[EmailOutcome] =
      ref.update(_ :+ Sent(to, subject, htmlBody)).as(EmailOutcome.Sent)

    // #2233 — record the FROM + Reply-To so the outreach specs can assert the release goes out FROM
    // the press address and REPLIES route to the press inbox.
    override def sendAs(
        from: String,
        replyTo: Option[String],
        to: String,
        subject: String,
        htmlBody: String,
    ): UIO[EmailOutcome] =
      ref.update(_ :+ Sent(to, subject, htmlBody, Some(from), replyTo)).as(EmailOutcome.Sent)
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
      ): UIO[EmailOutcome] =
        ref.update(_ :+ Sent(to, subject, htmlBody, Some(from), replyTo)).as(outcome(to))
    }
}
