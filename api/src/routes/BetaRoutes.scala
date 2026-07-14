package wifihaven.api.routes

import wifihaven.api.auth.*
import wifihaven.api.beta.*
import wifihaven.api.db.*
import wifihaven.api.metrics.AppMetrics
import wifihaven.shared.*
import wifihaven.shared.types.*
import zio.*
import zio.http.*
import zio.json.*

/**
 * #2132 (multi-tenant P5-2, epic #622): the beta request → operator approval → provisioning →
 * invite accept API (design docs/design/multi-tenant-launch.md §3). Four surfaces:
 *
 *   - PUBLIC `POST /api/beta/request` — unauthenticated + rate-limited intake
 *   - OPERATOR `GET /api/operator/beta-requests` — household-1 admin only (§3.2 gate)
 *   - OPERATOR `POST /api/operator/beta-requests/{id}/approve|reject`
 *   - PUBLIC `POST /api/beta/accept` — unauthenticated invite acceptance
 *
 * The operator surface reads/writes ONLY `beta_requests` (+ the household it provisions) — the one
 * narrow, documented exception to isolation §9. See [[requireOperator]].
 */
object BetaRoutes {

  /**
   * Bound the attacker-controlled free-text `note` on the unauthenticated intake (matches
   * AlertRoutes.MaxNoteLength) — truncate, don't reject.
   */
  val MaxNoteLength: Int  = 500
  val MaxNameLength: Int  = 200
  val MaxEmailLength: Int = 320 // RFC 5321 max

  def routes(
      auth: AuthService,
      beta: BetaService,
      betaRepo: BetaRequestRepo,
      userRepo: UserRepo,
      rateLimiter: RateLimiter,
  ): Routes[Any, Response] =
    Routes(
      // ── Public intake ───────────────────────────────────────────────────────
      Method.POST / "api" / "beta" / "request" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            allowed <- rateLimiter.tryAcquire(s"beta-request:${clientIp(req)}")
            _       <- ZIO
              .unless(allowed)(
                AppMetrics.recordBetaPipeline("request", "rate_limited") *>
                  ZIO.fail(ApiError.RateLimited("Too many requests; try again later")),
              )
            body    <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            cr0     <- ZIO
              .fromEither(body.fromJson[CreateBetaRequest])
              .mapError(ApiError.DecodeFailure(_))
            email = cr0.email.trim.toLowerCase.take(MaxEmailLength)
            _ <- ZIO.fail(ApiError.BadRequest("email required")).when(!looksLikeEmail(email))
            name = cr0.name.map(_.trim).filter(_.nonEmpty).map(_.take(MaxNameLength))
            note = cr0.note.map(_.take(MaxNoteLength))
            // Status-aware intake (#2222): create-if-new / no-op-if-pending-or-rejected /
            // resend-invite-if-approved, all behind the SAME content-free 200 below. A caller can't
            // probe which emails exist or in what state — every outcome yields a byte-identical
            // response; only the server-side side effect (a re-minted invite to an approved
            // applicant's true inbox) differs. The outcome feeds the pipeline metric only.
            outcome <- beta.request(email, name, note).mapError(betaErrorToApi)
            _       <- AppMetrics.recordBetaPipeline(
              "request",
              BetaService.BetaIntakeOutcome.label(outcome),
            )
          } yield Response.json(BetaRequestAck().toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },

      // ── Operator: list the review queue (default pending; ?status= filters) ───
      Method.GET / "api" / "operator" / "beta-requests" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            _ <- requireOperator(req, auth)
            filter = req.url.queryParam("status").flatMap(BetaRequestStatus.parse)
            rows <- betaRepo
              .listByStatus(filter.getOrElse(BetaRequestStatus.Pending))
              .mapError(ApiError.Db(_))
          } yield Response.json(rows.map(toSummary).toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },

      // ── Operator: approve → provision → invite URL ────────────────────────────
      Method.POST / "api" / "operator" / "beta-requests" / long("id") / "approve" ->
        handler { (id: Long, req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims   <- requireOperator(req, auth)
            operator <- operatorUserId(userRepo, claims)
            request  <- betaRepo
              .findById(BetaRequestId(id))
              .mapError(ApiError.Db(_))
              .someOrFail(ApiError.NotFound("beta request not found"))
            resp     <- beta
              .approve(request, operator)
              .tapError(e => AppMetrics.recordBetaPipeline("approve", approveOutcome(e)))
              .mapError(betaErrorToApi)
            _        <- AppMetrics.recordBetaPipeline("approve", "ok")
          } yield Response.json(resp.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },

      // ── Operator: reject a pending request ────────────────────────────────────
      Method.POST / "api" / "operator" / "beta-requests" / long("id") / "reject" ->
        handler { (id: Long, req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims   <- requireOperator(req, auth)
            operator <- operatorUserId(userRepo, claims)
            now      <- beta.clock.instant
            ok       <- betaRepo
              .reject(BetaRequestId(id), operator, now)
              .mapError(ApiError.Db(_))
            _        <- ZIO.unless(ok)(
              AppMetrics.recordBetaPipeline("reject", "not_pending") *>
                ZIO.fail(ApiError.NotFound("no pending beta request with that id")),
            )
            _        <- AppMetrics.recordBetaPipeline("reject", "ok")
          } yield Response.ok
          handle.mapError(ErrorMapper.errorToResponse)
        },

      // ── Public: invite acceptance → new household's first admin ───────────────
      Method.POST / "api" / "beta" / "accept" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            body <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            ar   <- ZIO
              .fromEither(body.fromJson[AcceptInviteRequest])
              .mapError(ApiError.DecodeFailure(_))
            resp <- beta
              .accept(ar)
              .tapError(e => AppMetrics.recordBetaPipeline("accept", acceptOutcome(e)))
              .mapError(betaErrorToApi)
            _    <- AppMetrics.recordBetaPipeline("accept", "ok")
          } yield Response.json(resp.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
    )

  // Resolve the deciding operator's UserId from their JWT via UserRepo (the operator is a real
  // household-1 admin, like RouterRoutes #2106 resolves the enrolling admin). #2140 scoped
  // findByUsername to (household, username); requireOperator guarantees claims.hh == the default
  // household. A valid operator token always names an existing user, so a miss is a 401.
  private def operatorUserId(userRepo: UserRepo, claims: JwtClaims): IO[ApiError, UserId] =
    userRepo
      .findByUsername(claims.hh, claims.sub)
      .mapError(ApiError.Db(_))
      .someOrFail(ApiError.Unauthorized("unknown operator user"))
      .map(_.id)

  // #2132 (design §4, 2026-07-10): validate a deliverable public-FQDN shape — `local@domain` where
  // the domain has at least one dot (a real TLD-bearing FQDN), no whitespace, single `@`. This is a
  // deliverability gate (the email becomes the admin's global login identifier at accept), NOT a
  // parse-disambiguation one — the V66/V67 charset CHECKs already keep the login forms disjoint. A
  // full RFC 5322 validator is deliberately overkill; this rejects the obvious non-FQDN junk.
  private val EmailFqdn                          = """^[^@\s]+@[^@\s.]+(?:\.[^@\s.]+)+$""".r
  private def looksLikeEmail(s: String): Boolean = EmailFqdn.matches(s)

  private def toSummary(r: DbBetaRequest): BetaRequestSummary =
    BetaRequestSummary(
      id = r.id,
      email = r.email,
      name = r.name,
      note = r.note,
      status = r.status,
      requestedAt = r.requestedAt.toString,
      decidedAt = r.decidedAt.map(_.toString),
      householdId = r.householdId,
    )

  private def approveOutcome(e: BetaError): String = e match {
    case BetaError.NotPending => "not_pending"
    case _                    => "error"
  }

  private def acceptOutcome(e: BetaError): String = e match {
    case BetaError.InvalidToken => "invalid_token"
    case BetaError.Expired      => "expired"
    case BetaError.WeakPassword => "weak_password"
    case _                      => "error"
  }

  private def betaErrorToApi(e: BetaError): ApiError = e match {
    case BetaError.NotPending    => ApiError.Wrapped(Response.status(Status.Conflict))
    case BetaError.InvalidToken  => ApiError.BadRequest("invalid or expired invite")
    case BetaError.Expired       => ApiError.BadRequest("invalid or expired invite")
    case BetaError.WeakPassword  =>
      ApiError.BadRequest(s"password must be at least ${AuthService.MinPasswordLength} characters")
    case BetaError.SlugExhausted => ApiError.Internal("could not allocate a household slug")
    case BetaError.Db(c)         => ApiError.Db(c)
  }
}
