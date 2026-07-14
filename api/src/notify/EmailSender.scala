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
      ZIO
        .attemptBlocking {
          val payload = ResendRequest(cfg.fromTrimmed, List(to), subject, htmlBody).toJson
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
  final case class Sent(to: String, subject: String, htmlBody: String)

  def recording(ref: Ref[List[Sent]]): EmailSender = new EmailSender {
    def send(to: String, subject: String, htmlBody: String): UIO[EmailOutcome] =
      ref.update(_ :+ Sent(to, subject, htmlBody)).as(EmailOutcome.Sent)
  }
}
