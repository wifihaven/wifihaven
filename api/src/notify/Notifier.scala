package wifihaven.api.notify

import wifihaven.api.EmailConfig
import wifihaven.api.db.HouseholdSettingsRepo
import wifihaven.api.metrics.AppMetrics
import wifihaven.shared.{AccessRequestKind, Alert, AlertKind}
import wifihaven.shared.types.HouseholdId
import zio.*

/**
 * #960 / #578: admin-notification sink for newly-raised alerts. The in-app banner (`GET
 * /api/alerts`) is always authoritative; this is the out-of-band nudge so a parent doesn't have to
 * be looking at the dashboard to learn a kid is asking for access.
 *
 * `.live` (#960) logged only. `.layer` (#578) adds the deferred email half: on an access-request
 * alert it resolves the alert's household → `notify_email` and sends via [[EmailSender]].
 * Everything is fail-open and config-gated — with no Resend secrets (or no household recipient) it
 * degrades to exactly the old log line and sends nothing. Call sites are unchanged (that was the
 * point of the trait). Every path emits `notify_email_total{outcome}` so the operator can see the
 * send funnel.
 */
trait Notifier {
  def alertCreated(a: Alert): UIO[Unit]

  /**
   * #2190: a beta household was provisioned on operator approval (design §3.3) — send the
   * single-use invite link to the requester so they can bootstrap their household's first admin.
   * `email` is the requester's (`beta_requests.email`, NOT the per-household `notify_email` the
   * alert path resolves), `slug` the new household's, `inviteUrl` the `<base>/welcome?token=…`
   * accept link (only known in the approve response), `ttlHours` its lifetime so the body can say
   * how long the link is good for.
   *
   * Like [[alertCreated]] this is fail-open: [[layer]] sends via [[EmailSender]] (itself
   * never-fails), [[live]] only logs. A send failure must NOT fail the provisioning transaction —
   * the household already exists and the approve response still carries `inviteUrl` as the
   * operator's manual-resend fallback (#2190).
   */
  def betaInvite(email: String, slug: String, inviteUrl: String, ttlHours: Int): UIO[Unit]
}

object Notifier {

  // The two pre-send skip outcomes for `notify_email_total`. Together with the three
  // transport outcomes from `EmailOutcome.label` (sent / failed / skipped_disabled) these are
  // the full label space enumerated in `MetricGuard.Allowed("notify_email_total")` — kept as
  // named constants here so the metric-label strings live in one place per outcome, not inline.
  private val OutcomeSkippedNoRecipient: String = "skipped_no_recipient"
  private val OutcomeSkippedNoHousehold: String = "skipped_no_household"

  /** The structured line every alert logs, regardless of email outcome — the #960 behavior. */
  private def logAlert(a: Alert): UIO[Unit] =
    ZIO.logInfo(
      s"alert created: id=${a.id.value} kind=${AlertKind.asString(a.kind)} " +
        s"mac=${a.mac.value} " +
        s"host=${a.host.fold("-")(_.value)} " +
        s"requestKind=${a.requestKind.fold("-")(AccessRequestKind.asString)} " +
        s"profile=${a.profileName.getOrElse("-")}",
    )

  private def logBeta(email: String, slug: String): UIO[Unit] =
    ZIO.logInfo(s"beta invite: sending invite link for household slug=$slug to $email")

  /**
   * #960 log-only implementation, kept for call sites that don't need (or can't wire) the email
   * transport — e.g. tests that only assert the in-app path. Prefer [[layer]] in production.
   */
  val live: ULayer[Notifier] = ZLayer.succeed(new Notifier {
    def alertCreated(a: Alert): UIO[Unit] = logAlert(a)
    def betaInvite(email: String, slug: String, inviteUrl: String, ttlHours: Int): UIO[Unit] =
      logBeta(email, slug)
  })

  /**
   * #578 email-enabled implementation. Depends on the household-settings repo (to resolve the
   * per-household `notify_email`), the [[EmailSender]] transport, and [[EmailConfig]] (for the
   * "review in dashboard" link). Never fails.
   */
  val layer: ZLayer[HouseholdSettingsRepo & EmailSender & EmailConfig, Nothing, Notifier] =
    ZLayer.fromFunction { (hsRepo: HouseholdSettingsRepo, sender: EmailSender, cfg: EmailConfig) =>
      new EmailNotifier(hsRepo, sender, cfg)
    }

  final class EmailNotifier(
      hsRepo: HouseholdSettingsRepo,
      sender: EmailSender,
      cfg: EmailConfig,
  ) extends Notifier {

    // #2190: send the invite link to the requester's own email (NOT a household notify_email —
    // the household has no admin yet). Log first (authoritative record that we tried), then send.
    // Fail-open by construction: `send` never fails, and every outcome is metered via
    // `beta_invite_email_total{outcome}` so the operator can see the send funnel. When email is
    // unconfigured the sender yields `Disabled` → this degrades to exactly the old log line.
    def betaInvite(email: String, slug: String, inviteUrl: String, ttlHours: Int): UIO[Unit] =
      logBeta(email, slug) *>
        sender
          .send(email, betaInviteSubject, betaInviteBody(slug, inviteUrl, ttlHours))
          .flatMap(o => AppMetrics.betaInviteEmail(EmailOutcome.label(o)))

    def alertCreated(a: Alert): UIO[Unit] =
      // Log first (authoritative, always), then attempt the out-of-band email.
      logAlert(a) *> notifyByEmail(a)

    private def notifyByEmail(a: Alert): UIO[Unit] =
      a.householdId match {
        case None     =>
          AppMetrics.notifyEmail(OutcomeSkippedNoHousehold)
        case Some(hh) =>
          resolveRecipient(hh).flatMap {
            case None            => AppMetrics.notifyEmail(OutcomeSkippedNoRecipient)
            case Some(recipient) =>
              sender
                .send(recipient, subjectFor(a), bodyFor(a))
                .flatMap(o => AppMetrics.notifyEmail(EmailOutcome.label(o)))
          }
      }

    // getForHousehold can fail (DB) — treat a lookup failure as "no recipient" so a transient DB
    // blip on the notify path never crashes the fiber; the alert row + banner are already durable.
    private def resolveRecipient(hh: HouseholdId): UIO[Option[String]] =
      hsRepo.getForHousehold(hh).map(_.notifyEmail).catchAll { e =>
        ZIO
          .logWarning(
            s"notify: could not read settings for household ${hh.value}: ${e.getMessage}",
          )
          .as(None)
      }

    private def subjectFor(a: Alert): String = {
      val who = a.profileName.orElse(a.deviceName).getOrElse("A device")
      a.requestKind match {
        case Some(AccessRequestKind.Extension) => s"WifiHaven: $who is asking for more screen time"
        case Some(AccessRequestKind.Exemption) =>
          s"WifiHaven: $who is asking to unblock ${a.host.fold("a site")(_.value)}"
        case Some(AccessRequestKind.Unpause)   => s"WifiHaven: $who is asking to be unpaused"
        case _                                 => s"WifiHaven: new request from $who"
      }
    }

    private def bodyFor(a: Alert): String = {
      val who      = esc(a.profileName.orElse(a.deviceName).getOrElse("A device"))
      val ask      = a.requestKind match {
        case Some(AccessRequestKind.Extension) => "more screen time"
        case Some(AccessRequestKind.Exemption) =>
          s"access to <strong>${esc(a.host.fold("a site")(_.value))}</strong>"
        case Some(AccessRequestKind.Unpause)   => "to be unpaused"
        case _                                 => "access"
      }
      val noteLine = a.note.filter(_.trim.nonEmpty).fold("") { n =>
        s"""<p style="margin:16px 0;padding:12px 16px;background:#f4f4f5;border-radius:8px;color:#3f3f46;">&ldquo;${esc(
            n,
          )}&rdquo;</p>"""
      }
      val url      = cfg.dashboardUrl
      s"""<div style="font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;font-size:15px;color:#18181b;line-height:1.5;">
         |  <p><strong>$who</strong> is asking for $ask on your WifiHaven network.</p>
         |  $noteLine
         |  <p style="margin:24px 0;">
         |    <a href="${esc(url)}" style="background:#4f46e5;color:#ffffff;text-decoration:none;padding:10px 18px;border-radius:8px;display:inline-block;">Review in dashboard</a>
         |  </p>
         |  <p style="color:#71717a;font-size:13px;">Approve or deny this request from the WifiHaven dashboard. This email is a notification only — no action is taken automatically.</p>
         |</div>""".stripMargin
    }

    // #2190 — the beta invite email. Subject is fixed; the body carries the household slug, the
    // single-use accept link, and how long it's valid so the requester knows to act.
    private val betaInviteSubject: String = "You're in — set up your WifiHaven household"

    private def betaInviteBody(slug: String, inviteUrl: String, ttlHours: Int): String = {
      // "expires in N days" reads better than hours for the 7-day (168h) default; fall back to
      // hours for a sub-day TTL so a short-lived invite still states its window honestly.
      val validFor =
        if ttlHours >= 24 && ttlHours % 24 == 0 then {
          val d = ttlHours / 24; s"$d day${if d == 1 then "" else "s"}"
        } else s"$ttlHours hour${if ttlHours == 1 then "" else "s"}"
      s"""<div style="font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;font-size:15px;color:#18181b;line-height:1.5;">
         |  <p>Your WifiHaven beta household <strong>${esc(slug)}</strong> is ready.</p>
         |  <p>Click below to set your admin password and finish setup:</p>
         |  <p style="margin:24px 0;">
         |    <a href="${esc(
          inviteUrl,
        )}" style="background:#4f46e5;color:#ffffff;text-decoration:none;padding:10px 18px;border-radius:8px;display:inline-block;">Accept your invite</a>
         |  </p>
         |  <p style="color:#71717a;font-size:13px;">This invite link is single-use and expires in $validFor. If the button doesn't work, copy this link into your browser:<br/><span style="word-break:break-all;">${esc(
          inviteUrl,
        )}</span></p>
         |</div>""".stripMargin
    }

    /** Minimal HTML escape for the user-controlled fields (kid note, profile/host names). */
    private def esc(s: String): String =
      s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
  }
}
