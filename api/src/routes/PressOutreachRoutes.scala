package wifihaven.api.routes

import wifihaven.api.PressOutreachConfig
import wifihaven.api.auth.AuthService
import wifihaven.api.db.PressMessageRepo
import wifihaven.api.metrics.AppMetrics
import wifihaven.api.notify.EmailSender
import wifihaven.api.press.PressOutreach
import wifihaven.shared.types.HouseholdId
import zio.*
import zio.http.*
import zio.json.*

/**
 * #2233 (beta press outreach, epic #2197) — the operator-only admin surface for the press-OUTREACH
 * send capability. Two endpoints, both gated (authenticated + `admin` + household 1, else 404 — the
 * PressRoutes convention: press is company-global, its existence isn't disclosed across the tenant
 * boundary), and both 404 when `press.outreach.enabled` is false (dark-by-default #2265):
 *
 *   - `POST /api/press/outreach/preview` — DRY-RUN. Renders the exact per-recipient emails
 *     (subject, from, reply-to, to, HTML body) and reports the counts + any unresolved fill tokens.
 *     NEVER transmits. This is the default review surface.
 *   - `POST /api/press/outreach/send` — the actual send, guarded THREE ways beyond the auth gate:
 *     the request must carry `confirm:true`; outbound email must be configured; and the release
 *     must have ZERO unresolved fill tokens (else 400 listing them). The send is idempotent (skips
 *     peers already in the press_messages outbound ledger), batched + rate-limited
 *     (cfg.perSendDelay), and every real Sent/Failed is recorded back to press_messages (fail-open)
 *     so a re-run resumes without double-sending. Replies route to the press inbox (cfg.replyTo) →
 *     the #2203 responder.
 *
 * NO autonomous path reaches [[send]]: it is only ever an operator's admin HTTP call carrying an
 * explicit confirm. The `testRecipient` field redirects a real transmit to one safe address (for
 * validating the send) without ever marking a real journalist contacted.
 */
object PressOutreachRoutes {

  final case class OutreachRequest(
      fill: Map[String, String] = Map.empty,
      emailOverrides: Map[String, String] = Map.empty,
      testRecipient: Option[String] = None,
      confirm: Option[Boolean] = None,
  ) derives JsonCodec

  def routes(
      auth: AuthService,
      cfg: PressOutreachConfig,
      emailEnabled: Boolean,
      sender: EmailSender,
      pressLog: PressMessageRepo,
      contacts: List[PressOutreach.Contact],
      releaseTemplate: String,
  ): Routes[Any, Response] =
    Routes(
      Method.POST / "api" / "press" / "outreach" / "preview" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            _   <- operatorGate(req, auth, cfg)
            in  <- decodeBody(req)
            rpt <- PressOutreach.run(
              contacts = contacts,
              rawReleaseTemplate = releaseTemplate,
              fill = in.fill,
              cfg = cfg,
              sender = sender,
              emailOverrides = in.emailOverrides,
              testRecipient = in.testRecipient.map(_.trim).filter(_.nonEmpty),
              confirmed = false,
            )
            _   <- emitMetrics(rpt)
          } yield Response.json(rpt.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.POST / "api" / "press" / "outreach" / "send"    ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] =
            for {
              _  <- operatorGate(req, auth, cfg)
              in <- decodeBody(req)
              // Guard 1: explicit confirm. A body without confirm:true never transmits.
              _  <- ZIO
                .fail(ApiError.BadRequest("confirm:true is required to send"))
                .when(!in.confirm.contains(true))
              // Guard 2: outbound email must be live (config cross-check normally guarantees this).
              _  <- ZIO
                .fail(
                  ApiError.BadRequest("outbound email is not configured (wifihaven.email.enabled)"),
                )
                .when(!emailEnabled)
              // Guard 3: refuse while the release still has unresolved fill tokens.
              unresolved = PressOutreach.resolveRelease(releaseTemplate, in.fill)._2
              _           <- ZIO
                .fail(
                  ApiError.BadRequest(
                    s"release has unresolved fill tokens: ${unresolved.mkString(", ")} — supply them in `fill`",
                  ),
                )
                .when(unresolved.nonEmpty)
              // Idempotency ledger: peers we already have successful outbound correspondence with.
              alreadySent <- pressLog
                .outboundPeers()
                .orElseFail(ApiError.Internal("could not read outreach ledger"))
              rpt         <- PressOutreach.run(
                contacts = contacts,
                rawReleaseTemplate = releaseTemplate,
                fill = in.fill,
                cfg = cfg,
                sender = sender,
                emailOverrides = in.emailOverrides,
                alreadySentPeers = alreadySent,
                testRecipient = in.testRecipient.map(_.trim).filter(_.nonEmpty),
                confirmed = true,
                emailEnabled = emailEnabled,
              )
              _           <- recordSends(pressLog, rpt)
              _           <- emitMetrics(rpt)
            } yield Response.json(
              rpt.copy(emails = Nil).toJson,
            ) // omit the bulky rendered bodies on send
          handle.mapError(ErrorMapper.errorToResponse)
        },
    )

  /**
   * Auth + operator (household-1 admin) gate, and the dark-by-default flag — all resolve to 404.
   */
  private def operatorGate(
      req: Request,
      auth: AuthService,
      cfg: PressOutreachConfig,
  ): IO[ApiError, Unit] =
    for {
      claims <- requireAdmin(req, auth)
      // Non-operator household (even an admin) → 404, indistinguishable from "no such endpoint".
      _      <- ZIO.fail(ApiError.NotFound("not found")).when(claims.hh != HouseholdId.Default)
      // Feature dark → 404 (mirrors the disabled press responder / #2265 no-dark posture).
      _      <- ZIO.fail(ApiError.NotFound("not found")).when(!cfg.enabled)
    } yield ()

  private def decodeBody(req: Request): IO[ApiError, OutreachRequest] =
    req.body.asString.orElseFail(ApiError.BadRequest("")).flatMap { raw =>
      // An empty body is a valid, all-defaults request (a bare preview with no fill).
      if raw.trim.isEmpty then ZIO.succeed(OutreachRequest())
      else ZIO.fromEither(raw.fromJson[OutreachRequest]).mapError(ApiError.DecodeFailure(_))
    }

  private def emitMetrics(rpt: PressOutreach.Report): UIO[Unit] =
    ZIO.foreachDiscard(rpt.results)(r => AppMetrics.pressOutreach(r.outcome))

  /**
   * Record every REAL (non-test) Sent/Failed to the press_messages outbound log — fail-open AUDIT:
   * a recording error is logged + swallowed so it never fails the response (the email already went,
   * and a lost ledger row at worst risks a re-send on the next run, which idempotency then can't
   * dedup).
   */
  private def recordSends(pressLog: PressMessageRepo, rpt: PressOutreach.Report): UIO[Unit] =
    ZIO.foreachDiscard(rpt.results.filter(_.recordable)) { r =>
      r.peerEmail match {
        case None       => ZIO.unit
        case Some(peer) =>
          pressLog
            .recordOutbound(
              peerEmail = peer,
              subject = r.subject,
              body = s"[press-outreach] release sent to ${r.outlet}",
              inReplyTo = None,
              outcome = r.outcome, // "sent" | "failed"
            )
            .unit
            .catchAll(e =>
              ZIO.logWarning(
                s"press-outreach: ledger recording failed (fail-open): ${e.getMessage}",
              ),
            )
      }
    }
}
