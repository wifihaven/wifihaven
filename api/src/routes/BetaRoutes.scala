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
            // Idempotent on the unique email: a new insert vs a duplicate both return the SAME
            // content-free 200, so a caller can't probe which emails already requested (no
            // enumeration leak, #2132 scope 1).
            inserted <- betaRepo.create(email, name, note).mapError(ApiError.Db(_))
            _        <- AppMetrics.recordBetaPipeline(
              "request",
              if inserted then "created" else "duplicate",
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

  // Resolve the deciding operator's UserId from their JWT `sub` via UserRepo (the operator is a real
  // household-1 admin, like RouterRoutes #2106 resolves the enrolling admin). A valid operator token
  // always names an existing user, so a miss is a 401.
  // TODO(#2140): `findByUsername` is un-scoped and ambiguous now that username is
  // UNIQUE(household_id, username). Harmless here — the operator is always household 1 and no
  // colliding usernames exist until #2133 — and it mirrors the RouterRoutes #2106 pattern; it will
  // be resolved when #2140 scopes findByUsername to (hh, username).
  private def operatorUserId(userRepo: UserRepo, claims: JwtClaims): IO[ApiError, UserId] =
    userRepo
      .findByUsername(claims.sub)
      .mapError(ApiError.Db(_))
      .someOrFail(ApiError.Unauthorized("unknown operator user"))
      .map(_.id)

  // Minimal email sanity — a full RFC 5322 validator is overkill; we only need to reject obvious
  // junk before persisting. Real deliverability is proven by the operator sending the invite.
  private def looksLikeEmail(s: String): Boolean =
    s.length >= 3 && s.contains("@") && !s.startsWith("@") && !s.endsWith("@")

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
