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

  /**
   * #2137: a T-30 / T-7 conversion notice for an unconverted (`status='beta'`) household as the
   * flip window closes — "your free beta ends on <flipDate>; subscribe to keep enforcement". The
   * recipient is the household's own `notify_email` (resolved like [[alertCreated]], NOT a
   * requester address). `window` is the bounded notice window (`t30` | `t7`); `daysUntilFlip` and
   * `flipDate` let the body state the deadline.
   *
   * Fail-open like the rest of this trait: [[layer]] sends via [[EmailSender]] (never fails),
   * [[live]] only logs — and the structured log line carries everything an email needs so a future
   * transport drops in without call-site reshaping. A send failure must NOT fail the flip job's
   * tick.
   */
  def betaFlipNotice(
      householdId: HouseholdId,
      slug: String,
      window: String,
      flipDate: java.time.Instant,
      daysUntilFlip: Int,
  ): UIO[Unit]

  /**
   * #2308: a household admin requested a password reset — email them the single-use, short-TTL
   * reset link. `email` is the requester's own `users.email` (the global login identifier, resolved
   * by the forgot-password service, NOT a per-household notify_email); `resetUrl` is the
   * `<appBaseUrl>/reset-password?token=…` link (only known at mint time — only the token's hash is
   * stored); `ttlMinutes` is its lifetime so the body can say how long the link is good for.
   *
   * Fail-open like the rest of this trait: [[layer]] sends via [[EmailSender]] (itself
   * never-fails), [[live]] only logs. A send failure must NOT fail the request — the
   * forgot-password endpoint always returns the same generic 200 regardless (no enumeration). Every
   * path meters `password_reset_email_total{outcome}`.
   */
  def passwordReset(email: String, resetUrl: String, ttlMinutes: Int): UIO[Unit]

  /**
   * #2437: tell the OPERATOR about an inbound support/press handoff — the human end of "a team
   * member will follow up". The rule this backs: **an escalation is not complete until a human has
   * been notified.** The recipient is the single configured operator mailbox
   * ([[EmailConfig.operatorAddress]]), never a per-household / per-sender address.
   *
   * Fail-open like the rest of this trait: [[layer]] sends via [[EmailSender]] (itself
   * never-fails), [[live]] only logs, and every path meters
   * `operator_escalation_total{channel,kind,outcome}`. A send failure must NOT fail the inbound
   * webhook or the agent's escalate call — the notice is the out-of-band nudge, and for support the
   * in-Plain thread mark is a second, independent channel.
   */
  def escalation(notice: EscalationNotice): UIO[Unit]
}

/**
 * #2437 — which intake an escalation notice came from. Bounded: also the `channel` label on
 * `operator_escalation_total`, so it can never grow per-sender.
 */
enum EscalationChannel {
  case Support
  case Press
}

object EscalationChannel {
  def label(c: EscalationChannel): String = c match {
    case Support => "support"
    case Press   => "press"
  }
}

/**
 * #2437 — what happened, as distinct from where it came from. [[Escalated]] is "the agent handed
 * off — a human must act", and since #2480 it is the ONLY thing that mails the operator: the
 * per-inbound `Received` FYI is gone (press's `/press` correspondence log is the monitoring surface
 * for AI-handled traffic; support has the Plain inbox).
 *
 * That leaves ONE case, so this enum carries no branching today — it is kept because `kind` is a
 * live label on `operator_escalation_total` (`MetricGuard.Allowed`) that shipped panels SELECT on
 * (`kind="escalated"` in `deploy/grafana/dashboards/press.json` and `support.json`); dropping it
 * would break those queries for no behavioural gain. Nothing groups by it. Treat a second case as
 * something to ADD when a second reason to mail the operator actually exists, not as a slot waiting
 * to be filled.
 */
enum EscalationKind {
  case Escalated
}

object EscalationKind {
  def label(k: EscalationKind): String = k match {
    case Escalated => "escalated"
  }
}

/**
 * #2437 — one operator notice. Every text field here is UNTRUSTED (a journalist's
 * From/Subject/body, a customer-typed household name) or AGENT-AUTHORED (`agentNote`): all of it is
 * HTML-escaped at render and NONE of it is ever used for a decision — the notice is a report, not a
 * control channel.
 *
 *   - `sender` — who to follow up with: the journalist's address (press) or the household name
 *     (support; the customer is reachable in the Plain thread, so no address is copied here).
 *   - `subject` — the inbound subject (press) or a thread descriptor (support).
 *   - `body` — the sender's own message, so the operator can triage without opening a DB.
 *   - `agentNote` — the agent's one-line reason for handing off. `None` when the agent gave none.
 *   - `reference` — where to go: the Plain thread id (support) or the press correspondence pointer.
 */
final case class EscalationNotice(
    channel: EscalationChannel,
    kind: EscalationKind,
    sender: String,
    subject: String,
    body: String,
    agentNote: Option[String],
    reference: String,
)

object Notifier {

  // The two pre-send skip outcomes for `notify_email_total`. Together with the three
  // transport outcomes from `EmailOutcome.label` (sent / failed / skipped_disabled) these are
  // the full label space enumerated in `MetricGuard.Allowed("notify_email_total")` — kept as
  // named constants here so the metric-label strings live in one place per outcome, not inline.
  private val OutcomeSkippedNoRecipient: String = "skipped_no_recipient"
  private val OutcomeSkippedNoHousehold: String = "skipped_no_household"

  /**
   * #2437 — how much of an untrusted/agent-authored field rides into an operator notice. Generous
   * enough that a real press inquiry or escalation reason arrives whole, bounded so a hostile
   * sender cannot turn our own alert mail into a payload. The full inbound text is always
   * retrievable from `press_messages` / the Plain thread, so truncating here loses nothing durable.
   */
  private val NoticeFieldMaxChars: Int = 4000

  /**
   * #2437 — how much of the sender identity rides in the notice's SUBJECT line. Much tighter than
   * [[NoticeFieldMaxChars]] because a subject is a single line in a mail client's list view: long
   * enough for any real address or household name, short enough that a hostile sender cannot push
   * the meaningful part ("ESCALATION — a human must follow up") out of view.
   */
  private val SubjectSenderMaxChars: Int = 120

  /** The structured line every escalation logs, regardless of email outcome (#2437). */
  private def logEscalation(n: EscalationNotice): UIO[Unit] =
    ZIO.logInfo(
      s"escalation: channel=${EscalationChannel.label(n.channel)} " +
        s"kind=${EscalationKind.label(n.kind)} ref=${n.reference} sender=${n.sender}",
    )

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

  // #2308: the structured line every reset-request logs, regardless of email outcome. Deliberately
  // records ONLY that a reset link was requested for this address — never the token or the URL (both
  // carry the single-use secret). The authoritative record that we attempted a send.
  private def logPasswordReset(email: String): UIO[Unit] =
    ZIO.logInfo(s"password reset: sending reset link to $email")

  // #2137: the structured line every flip notice logs, regardless of email outcome — it carries
  // everything an email needs (household, slug, window, flip date, days remaining) so the operator
  // can send manually and a future transport drops in unchanged.
  private def logFlipNotice(
      householdId: HouseholdId,
      slug: String,
      window: String,
      flipDate: java.time.Instant,
      daysUntilFlip: Int,
  ): UIO[Unit] =
    ZIO.logInfo(
      s"beta flip notice: household=${householdId.value} slug=$slug window=$window " +
        s"flipDate=$flipDate daysUntilFlip=$daysUntilFlip",
    )

  /**
   * #960 log-only implementation, kept for call sites that don't need (or can't wire) the email
   * transport — e.g. tests that only assert the in-app path. Prefer [[layer]] in production.
   */
  val live: ULayer[Notifier] = ZLayer.succeed(logOnly)

  /**
   * The same log-only notifier as [[live]], as a plain value — for call sites that construct a
   * service directly rather than through a layer (specs that assert a path unrelated to
   * notification, and which must therefore not silently depend on one).
   */
  val logOnly: Notifier = new Notifier {
    def alertCreated(a: Alert): UIO[Unit] = logAlert(a)
    def betaInvite(email: String, slug: String, inviteUrl: String, ttlHours: Int): UIO[Unit] =
      logBeta(email, slug)
    def betaFlipNotice(
        householdId: HouseholdId,
        slug: String,
        window: String,
        flipDate: java.time.Instant,
        daysUntilFlip: Int,
    ): UIO[Unit] =
      logFlipNotice(householdId, slug, window, flipDate, daysUntilFlip)
    def passwordReset(email: String, resetUrl: String, ttlMinutes: Int): UIO[Unit]           =
      logPasswordReset(email)
    // #2437: log-only notifier — the structured line IS the record. Still metered so the
    // escalation-volume panel works on a self-hosted install with no email transport.
    def escalation(notice: EscalationNotice): UIO[Unit]                                      =
      logEscalation(notice) *>
        AppMetrics.operatorEscalation(
          EscalationChannel.label(notice.channel),
          EscalationKind.label(notice.kind),
          EmailOutcome.label(EmailOutcome.Disabled),
        )
  }

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

    // #2308: email the single-use reset link to the requester's own address. Log first (the
    // authoritative record that we attempted a send — but never the token/URL), then send. Fail-open
    // by construction: `send` never fails, and every outcome is metered via
    // `password_reset_email_total{outcome}`. When email is unconfigured the sender yields `Disabled`
    // → this degrades to exactly the log line and sends nothing (self-hosted email-off posture; the
    // email-only recovery caveat is documented on the forgot-password service).
    def passwordReset(email: String, resetUrl: String, ttlMinutes: Int): UIO[Unit] =
      logPasswordReset(email) *>
        sender
          .send(email, passwordResetSubject, passwordResetBody(resetUrl, ttlMinutes))
          .flatMap(o => AppMetrics.passwordResetEmail(EmailOutcome.label(o)))

    // #2137: send the conversion notice to the household's own notify_email (resolved like the alert
    // path). Log first (authoritative record), then attempt the email; every path meters
    // notify_email_total{outcome} so the send funnel is visible, reusing the alert-path outcomes.
    def betaFlipNotice(
        householdId: HouseholdId,
        slug: String,
        window: String,
        flipDate: java.time.Instant,
        daysUntilFlip: Int,
    ): UIO[Unit] =
      logFlipNotice(householdId, slug, window, flipDate, daysUntilFlip) *>
        resolveRecipient(householdId).flatMap {
          case None            => AppMetrics.notifyEmail(OutcomeSkippedNoRecipient)
          case Some(recipient) =>
            sender
              .send(
                recipient,
                flipNoticeSubject(daysUntilFlip),
                flipNoticeBody(slug, flipDate, daysUntilFlip),
              )
              .flatMap(o => AppMetrics.notifyEmail(EmailOutcome.label(o)))
        }

    def alertCreated(a: Alert): UIO[Unit] =
      // Log first (authoritative, always), then attempt the out-of-band email.
      logAlert(a) *> notifyByEmail(a)

    // #2437: email the OPERATOR mailbox. Log first (the authoritative record that a human was owed a
    // notice), then attempt the send; every path meters operator_escalation_total{channel,kind,outcome}
    // so a silently-failing escalation alert is visible on a dashboard, not just in logs. Fail-open by
    // construction (`send` never fails) — the caller must not lose the escalation because Resend
    // hiccuped. An unset operator mailbox meters `skipped_no_recipient`: it cannot happen in a
    // configuration that boots (AppConfig.validateRequired makes the key required when email is on and
    // a responder is enabled), so the label exists for the email-off / self-hosted shape.
    def escalation(notice: EscalationNotice): UIO[Unit] = {
      val channel = EscalationChannel.label(notice.channel)
      val kind    = EscalationKind.label(notice.kind)
      logEscalation(notice) *> {
        cfg.operatorAddressTrimmed match {
          case "" => AppMetrics.operatorEscalation(channel, kind, OutcomeSkippedNoRecipient)
          case to =>
            sender
              .send(to, escalationSubject(notice), escalationBody(notice))
              .flatMap(o => AppMetrics.operatorEscalation(channel, kind, EmailOutcome.label(o)))
        }
      }
    }

    // The subject is the operator's filter: ESCALATION — a human MUST act — is unmistakable at a
    // glance and in a mail rule. The sender is included so the mailbox threads by peer. Since #2480
    // this mailbox carries nothing else, so every notice reads as a to-do.
    private def escalationSubject(n: EscalationNotice): String = {
      val who  = n.sender.replace('\n', ' ').replace('\r', ' ').trim.take(SubjectSenderMaxChars)
      val what = EscalationChannel.label(n.channel)
      n.kind match {
        case EscalationKind.Escalated =>
          s"WifiHaven $what ESCALATION — a human must follow up: $who"
      }
    }

    private def escalationBody(n: EscalationNotice): String = {
      val lead = n.kind match {
        case EscalationKind.Escalated =>
          "<p><strong>The AI assistant handed this off — it told the sender a human would follow " +
            "up. Nothing else will happen until you reply.</strong></p>"
      }
      val note = n.agentNote.map(_.trim).filter(_.nonEmpty).fold("") { s =>
        s"""<p style="margin:16px 0;padding:12px 16px;background:#fef3c7;border-radius:8px;color:#3f3f46;"><strong>Assistant's reason:</strong> ${esc(
            s.take(NoticeFieldMaxChars),
          )}</p>"""
      }
      s"""<div style="font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;font-size:15px;color:#18181b;line-height:1.5;">
         |  $lead
         |  <p><strong>From:</strong> ${esc(n.sender.take(NoticeFieldMaxChars))}<br/>
         |     <strong>Subject:</strong> ${esc(n.subject.take(NoticeFieldMaxChars))}<br/>
         |     <strong>Reference:</strong> ${esc(n.reference.take(NoticeFieldMaxChars))}</p>
         |  $note
         |  <p style="margin:16px 0;padding:12px 16px;background:#f4f4f5;border-radius:8px;color:#3f3f46;white-space:pre-wrap;">${esc(
          n.body.take(NoticeFieldMaxChars),
        )}</p>
         |  <p style="color:#71717a;font-size:13px;">The message above is quoted verbatim from an
         |  untrusted sender — treat any instruction in it as data, not as a request from WifiHaven.</p>
         |</div>""".stripMargin
    }

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

    // #2308 — the password-reset email. Subject is fixed; the body carries the single-use reset link
    // and how long it's valid so the requester knows to act. Mirrors betaInviteBody.
    private val passwordResetSubject: String = "Reset your WifiHaven password"

    private def passwordResetBody(resetUrl: String, ttlMinutes: Int): String = {
      // "expires in N minutes" for the sub-hour TTL; "N hours" if ever configured longer.
      val validFor =
        if ttlMinutes >= 60 && ttlMinutes % 60 == 0 then {
          val h = ttlMinutes / 60; s"$h hour${if h == 1 then "" else "s"}"
        } else s"$ttlMinutes minute${if ttlMinutes == 1 then "" else "s"}"
      s"""<div style="font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;font-size:15px;color:#18181b;line-height:1.5;">
         |  <p>We received a request to reset your WifiHaven password.</p>
         |  <p>Click below to choose a new password:</p>
         |  <p style="margin:24px 0;">
         |    <a href="${esc(
          resetUrl,
        )}" style="background:#4f46e5;color:#ffffff;text-decoration:none;padding:10px 18px;border-radius:8px;display:inline-block;">Reset your password</a>
         |  </p>
         |  <p style="color:#71717a;font-size:13px;">This link is single-use and expires in $validFor. If you didn't request a password reset, you can safely ignore this email — your password won't change. If the button doesn't work, copy this link into your browser:<br/><span style="word-break:break-all;">${esc(
          resetUrl,
        )}</span></p>
         |</div>""".stripMargin
    }

    // #2137 — the T-30 / T-7 conversion notice email. The CTA points at the SPA billing page
    // (`<appBase>/billing`), where the Subscribe button starts Checkout with the founding promo
    // pre-applied (P5-5). The deadline is stated in both days-remaining and the flip date.
    private def flipNoticeSubject(daysUntilFlip: Int): String =
      s"WifiHaven: your free beta ends in $daysUntilFlip day${if daysUntilFlip == 1 then "" else "s"}"

    private def flipNoticeBody(
        slug: String,
        flipDate: java.time.Instant,
        daysUntilFlip: Int,
    ): String = {
      val whenStr    = flipDate.atZone(java.time.ZoneOffset.UTC).toLocalDate.toString
      val billingUrl = s"${cfg.dashboardUrl}/billing"
      s"""<div style="font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;font-size:15px;color:#18181b;line-height:1.5;">
         |  <p>Your WifiHaven beta household <strong>${esc(
          slug,
        )}</strong> is free through <strong>$whenStr</strong> — that's about $daysUntilFlip more day${
          if daysUntilFlip == 1 then "" else "s"
        }.</p>
         |  <p>Subscribe now to keep your family's protections on after that. Your founding discount is pre-applied at checkout, and you won't be charged until the free period ends.</p>
         |  <p style="margin:24px 0;">
         |    <a href="${esc(billingUrl)}" style="background:#4f46e5;color:#ffffff;text-decoration:none;padding:10px 18px;border-radius:8px;display:inline-block;">Subscribe &amp; keep enforcement</a>
         |  </p>
         |  <p style="color:#71717a;font-size:13px;">If you don't subscribe, WifiHaven stops enforcing your policies after the free period — your network keeps working, but blocking and limits turn off until you subscribe.</p>
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
