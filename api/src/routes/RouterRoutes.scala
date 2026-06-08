package wifihaven.api.routes

import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.policy.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*

import java.security.SecureRandom
import java.util.{Base64, UUID}

/**
 * Routes agent-facing router endpoints. All routes require the router bearer token except
 * `/register`, which uses a one-time enrollment token. `RouterAuth` lives in
 * [[wifihaven.api.routes.RouterAuth]].
 *
 * #1570: handlers fail with a typed [[ApiError]] mapped centrally by
 * [[ErrorMapper.errorToResponse]]; the [[wifihaven.api.ErrorBoundary]] logs (4xx WARN / 5xx ERROR)
 * + meters each error. Each case reproduces the EXACT status + body the hand-rolled code produced —
 * the OpenWRT agent branches on status only (4xx = drop, 5xx = retry/back-off), so DB failures stay
 * 503 via [[ApiError.Db]]; a malformed enrollment is 401; an unknown blocklist slug is 404.
 * Success-channel responses (the 200 /304 policy/blocklist bodies with ETag headers) are unchanged;
 * the boundary still observes their status. `RouterAuth` still returns `Response` and is bridged
 * via [[ApiError.Wrapped]].
 */
object RouterRoutes {

  def routes(
      routerRepo: RouterRepo,
      policy: PolicyService,
      routerAuth: RouterAuth,
      blockEventRepo: BlockEventRepo,
  ): Routes[Any, Response] =
    Routes(
      Method.POST / "api" / "router" / "register"      ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            body <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            rr   <- ZIO
              .fromEither(body.fromJson[RegisterRouterRequest])
              .mapError(ApiError.DecodeFailure(_))
            etHash = PolicyService.hashToken(rr.enrollmentToken.value)
            router <- routerRepo
              .findByEnrollmentTokenHash(etHash)
              .mapError(ApiError.Db(_))
              .flatMap(
                ZIO
                  .fromOption(_)
                  .orElseFail(ApiError.Unauthorized("invalid enrollment token")),
              )
            routerToken = RouterToken.unsafe(newToken("rt_"))
            tokenHash   = PolicyService.hashToken(routerToken.value)
            _ <- routerRepo
              .completeEnrollment(router.id, tokenHash)
              .mapError(ApiError.Db(_))
          } yield Response.json(RegisterRouterResponse(router.id, routerToken).toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.GET / "api" / "router" / "policy"         ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            router <- routerAuth.authenticate(req).mapError(ApiError.Wrapped(_))
            snap   <- policy.snapshot.mapError(ApiError.Db(_))
            ifNoneMatch  = req
              .header(Header.IfNoneMatch)
              .map(_.renderedValue)
              .orElse(req.url.queryParam("since"))
            // #771: agents post their baked PKG_VERSION on every policy
            // fetch. Optional — legacy agents omit the header and the
            // stored value is preserved via COALESCE.
            agentVersion = req.headers
              .get("X-WifiHaven-Agent-Version")
              .map(_.trim)
              .filter(_.nonEmpty)
            _ <- routerRepo
              .touch(router.id, Some(snap.etag), agentVersion)
              .mapError(ApiError.Db(_))
            notMod = ifNoneMatch.exists(etagWeakEquals(_, snap.etag.value))
            // #481: 200s (etag changed) are diagnostic gold for snapshot-propagation
            // failures — log them at INFO so they survive the default log level.
            // 304s stay at DEBUG to keep the steady-state poll cadence quiet.
            logMsg = s"router policy: router=${router.id} etagIn=${ifNoneMatch.getOrElse("-")} " +
              s"etagOut=${snap.etag.value} notModified=$notMod devices=${snap.devices.size} " +
              s"profiles=${snap.profiles.size}"
            _ <- if (notMod) ZIO.logDebug(logMsg) else ZIO.logInfo(logMsg)
            resp =
              if notMod then
                Response
                  .status(Status.NotModified)
                  .addHeader(Header.ETag.Strong(stripQuotes(snap.etag.value)))
              else
                Response
                  .json(snap.toJson)
                  .addHeader(Header.ETag.Strong(stripQuotes(snap.etag.value)))
          } yield resp
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.GET / "api" / "blocklists" / string("id") ->
        handler { (id: String, req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            _    <- routerAuth.authenticate(req).mapError(ApiError.Wrapped(_))
            // Treat malformed slugs (e.g. legacy ".rpz" suffix) the same as
            // "no such blocklist" — 404, not 400. They were a route on a prior
            // version of the API and external callers may still probe them.
            bid  <- ZIO
              .fromEither(BlocklistId.parse(id))
              .orElseFail(ApiError.NotFound(s"unknown blocklist: $id"))
            out  <- policy.renderBlocklist(bid).mapError(ApiError.Db(_))
            resp <- ZIO
              .fromOption(out)
              .mapBoth(
                _ => ApiError.NotFound(s"unknown blocklist: $id"),
                { case (etag, body) =>
                  val ifNone = req.header(Header.IfNoneMatch).map(_.renderedValue)
                  if ifNone.exists(etagWeakEquals(_, etag.value)) then
                    Response
                      .status(Status.NotModified)
                      .addHeader(Header.ETag.Strong(stripQuotes(etag.value)))
                  else
                    Response(
                      status = Status.Ok,
                      headers = Headers(
                        Header.ContentType(MediaType("text", "plain")),
                        Header.ETag.Strong(stripQuotes(etag.value)),
                      ),
                      body = Body.fromString(body),
                    )
                },
              )
          } yield resp
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.POST / "api" / "router" / "decision"      ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            router <- routerAuth.authenticate(req).mapError(ApiError.Wrapped(_))
            body   <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            dreq   <- ZIO
              .fromEither(body.fromJson[RouterDecisionRequest])
              .mapError(ApiError.DecodeFailure(_))
            result <- policy
              .decide(dreq.mac.value, dreq.hostname.value)
              .mapError(ApiError.Db(_))
            _      <- ZIO
              .when(result.decision == ConnectionDecision.Block) {
                blockEventRepo
                  .insertBatch(
                    // The decision endpoint is FQDN-only by design (DNS-time decision);
                    // wrap the Hostname in HostId.Fqdn for the tagged-union insert (#391).
                    List(
                      BlockEventInsert(
                        Some(dreq.mac),
                        HostId.Fqdn(dreq.hostname),
                        BlockReason.fromWire(result.reason),
                      ),
                    ),
                  )
                  .mapError(ApiError.Db(_))
              }
          } yield Response.json(result.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
    )

  private def stripQuotes(s: String): String =
    if s.startsWith("\"") && s.endsWith("\"") then s.drop(1).dropRight(1) else s

  // RFC 7232 §2.3.2: If-None-Match uses weak comparison; W/"x" matches "x".
  // Render weakens our outgoing ETag to W/"..." (#688), so the client echoes
  // back the weakened form, which won't string-equal what we stored.
  private def etagWeakEquals(a: String, b: String): Boolean =
    stripWeakPrefix(a) == stripWeakPrefix(b)

  private def stripWeakPrefix(s: String): String =
    if s.startsWith("W/") then s.drop(2) else s

  private def newToken(prefix: String): String = {
    val bytes = new Array[Byte](32)
    new SecureRandom().nextBytes(bytes)
    prefix + Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
  }
}

/** Admin-only routes for managing routers (visible in admin UI). */
object AdminRouterRoutes {
  def routes(
      auth: AuthService,
      routerRepo: RouterRepo,
  ): Routes[Any, Response] =
    Routes(
      Method.POST / "api" / "admin" / "routers"                  ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            _    <- requireAdmin(req, auth).mapError(ApiError.Wrapped(_))
            body <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            cr   <- ZIO
              .fromEither(body.fromJson[CreateRouterRequest])
              .mapError(ApiError.DecodeFailure(_))
            _    <- ZIO
              .fail(ApiError.BadRequest("name required"))
              .when(cr.name.trim.isEmpty)
            enrollmentToken = EnrollmentToken.unsafe(newEnrollmentToken())
            etHash          = PolicyService.hashToken(enrollmentToken.value)
            id <- routerRepo
              .create(cr.name.trim, etHash)
              .mapError(ApiError.Db(_))
          } yield Response.json(CreateRouterResponse(id, cr.name.trim, enrollmentToken).toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.GET / "api" / "admin" / "routers"                   ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            _   <- requireAdmin(req, auth).mapError(ApiError.Wrapped(_))
            all <- routerRepo.listAll.mapError(ApiError.Db(_))
          } yield Response.json(all.map(toSummary).toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.DELETE / "api" / "admin" / "routers" / string("id") ->
        handler { (id: String, req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            _   <- requireAdmin(req, auth).mapError(ApiError.Wrapped(_))
            uid <- ZIO
              .attempt(RouterId(UUID.fromString(id)))
              .orElseFail(ApiError.BadRequest("bad uuid"))
            _   <- routerRepo.delete(uid).mapError(ApiError.Db(_))
          } yield Response.ok
          handle.mapError(ErrorMapper.errorToResponse)
        },
    )

  private def toSummary(r: Router): RouterSummary =
    RouterSummary(
      id = r.id,
      name = r.name,
      enrolled = r.tokenHash.isDefined,
      lastSeenAt = r.lastSeenAt,
      lastEtag = r.lastEtag,
      createdAt = r.createdAt,
      agentVersion = r.agentVersion,
    )

  private def newEnrollmentToken(): String = {
    val bytes = new Array[Byte](24)
    new SecureRandom().nextBytes(bytes)
    "et_" + Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
  }
}
