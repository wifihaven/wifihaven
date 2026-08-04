package wifihaven.api.routes

import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.observability.LogContext
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
 * the boundary still observes their status.
 */
object RouterRoutes {

  def routes(
      routerRepo: RouterRepo,
      policy: PolicyService,
      routerAuth: RouterAuth,
      blockEventRepo: BlockEventRepo,
      // #2566/#2569/#2322: HMAC secret for the block-page token minted below. The API's existing
      // JWT secret, domain-separated by BlockPageToken — no new secret to provision.
      blockPageSecret: String,
  ): Routes[Any, Response] =
    Routes(
      Method.POST / "api" / "router" / "register"        ->
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
      Method.GET / "api" / "router" / "policy"           ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            router <- routerAuth.authenticate(req)
            // #2107: the snapshot is scoped to the router's household (resolved from the token by
            // #2106). WIRE UNCHANGED — household_id never appears on the payload; for a single-
            // household install this is byte-identical to the old global snapshot.
            snap   <- policy.snapshot(router.householdId).mapError(ApiError.Db(_))
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
            // #602: route + method are already on the MDC via LoggingMiddleware;
            // only attach the per-handler dynamic data here.
            _ <- LogContext.annotateAll(
              LogContext.RouterId -> router.id.toString,
              LogContext.Etag     -> snap.etag.value,
              LogContext.NotMod   -> notMod.toString,
            )(if (notMod) ZIO.logDebug(logMsg) else ZIO.logInfo(logMsg))
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
      // #2566/#2569/#2322: mint this router's block-page token. The agent fetches it on startup
      // (retrying on its policy tick until it lands) and stamps it onto the HTTP/80 block-page
      // redirect, which is what finally gives the unauthenticated block-page endpoints a household.
      //
      // Router-authenticated like the rest of `/api/router/*`, and the router can only ever mint
      // its OWN token — the id comes from the authenticated record, never from the request.
      Method.GET / "api" / "router" / "block-page-token" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = routerAuth
            .authenticate(req)
            .map { router =>
              Response.json(
                BlockPageTokenResponse(
                  wifihaven.api.auth.BlockPageToken.mint(blockPageSecret, router.id),
                ).toJson,
              )
            }
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.GET / "api" / "blocklists" / string("id")   ->
        handler { (id: String, req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            router <- routerAuth.authenticate(req)
            // Treat malformed slugs (e.g. legacy ".rpz" suffix) the same as
            // "no such blocklist" — 404, not 400. They were a route on a prior
            // version of the API and external callers may still probe them.
            bid    <- ZIO
              .fromEither(BlocklistId.parse(id))
              .orElseFail(ApiError.NotFound(s"unknown blocklist: $id"))
            // #2107: threaded for API symmetry; the blocklist CATALOG is a shared global resource
            // (design §0.2), so content is identical across households — the household does not
            // filter the bytes.
            out    <- policy.renderBlocklist(router.householdId, bid).mapError(ApiError.Db(_))
            resp   <- ZIO
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
      Method.POST / "api" / "router" / "decision"        ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            router <- routerAuth.authenticate(req)
            body   <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            dreq   <- ZIO
              .fromEither(body.fromJson[RouterDecisionRequest])
              .mapError(ApiError.DecodeFailure(_))
            result <- policy
              // #2107: scope the per-host decision to the router's household, so a MAC that exists
              // in another household never resolves the other tenant's device/profile row here.
              .decide(router.householdId, dreq.mac.value, dreq.hostname.value)
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

  // #1641: package-visible so [[AdminDebugRoutes]] (which emits the same router-shape ETag
  // header on `GET /api/admin/snapshot`) can reuse this instead of hand-mirroring a copy.
  // `Header.ETag.Strong` wants the bare token without surrounding quotes; our stored ETag
  // value is the quoted form (`"sha256:..."`), so trim the wrapping quotes before constructing
  // the header. Shared helper, not a "keep in sync" mirror (per AGENTS.md §Single source of
  // truth).
  private[routes] def stripQuotes(s: String): String =
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
      userRepo: UserRepo,
      entitlements: EntitlementsRepo,
  ): Routes[Any, Response] =
    Routes(
      Method.POST / "api" / "admin" / "routers"                  ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims      <- requireAdmin(req, auth)
            // #2106 (multi-tenant, epic #622): bind the new router to the creating admin's
            // household so it inherits tenancy at enrollment. The admin's household rides in the
            // JWT `hh` claim (#2105, now merged); we still round-trip through UserRepo — scoped to
            // that household (#2140: usernames are unique per household only) — so a stale token
            // naming a since-deleted user is a 401 rather than silently minting into `claims.hh`.
            householdId <- userRepo
              .findByUsername(claims.hh, claims.sub)
              .mapError(ApiError.Db(_))
              .someOrFail(ApiError.Unauthorized("unknown admin user"))
              .map(_.householdId)
            body        <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            cr          <- ZIO
              .fromEither(body.fromJson[CreateRouterRequest])
              .mapError(ApiError.DecodeFailure(_))
            _           <- ZIO
              .fail(ApiError.BadRequest("name required"))
              .when(cr.name.trim.isEmpty)
            // #2134 (multi-tenant P5-4, design §6): enforce the household's
            // router-cap entitlement. The cap resolves PER HOUSEHOLD from the
            // `households.router_cap` column via the `Entitlements` accessor —
            // never a global constant (pricing §7) — so household 1 and founding
            // households keep their higher cap purely by the column value, and
            // the count is scoped to this household alone (isolation pin: hh-B's
            // routers never affect hh-A's check). A created-but-not-yet-enrolled
            // router still consumes a slot: `listAllForHousehold` counts it.
            ent         <- entitlements.forHousehold(householdId).mapError(ApiError.Db(_))
            existing    <- routerRepo.listAllForHousehold(householdId).mapError(ApiError.Db(_))
            _           <- ZIO
              .fail(ApiError.Forbidden(s"your plan includes ${ent.routerCap} router(s)"))
              .when(existing.sizeIs >= ent.routerCap)
            enrollmentToken = EnrollmentToken.unsafe(newEnrollmentToken())
            etHash          = PolicyService.hashToken(enrollmentToken.value)
            id <- routerRepo
              .create(cr.name.trim, etHash, householdId)
              .mapError(ApiError.Db(_))
          } yield Response.json(CreateRouterResponse(id, cr.name.trim, enrollmentToken).toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.GET / "api" / "admin" / "routers"                   ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireAdmin(req, auth)
            // #2108: an admin enumerates only their own household's routers (design §2 gap 4).
            all    <- routerRepo.listAllForHousehold(claims.hh).mapError(ApiError.Db(_))
          } yield Response.json(all.map(toSummary).toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.DELETE / "api" / "admin" / "routers" / string("id") ->
        handler { (id: String, req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireAdmin(req, auth)
            uid    <- ZIO
              .attempt(RouterId(UUID.fromString(id)))
              .orElseFail(ApiError.BadRequest("bad uuid"))
            // #2108: an admin can only delete a router IN THEIR HOUSEHOLD — 404 otherwise (never
            // leak existence across the tenant boundary).
            _      <- routerRepo
              .findById(uid)
              .mapError(ApiError.Db(_))
              .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("Router not found")))
              .filterOrFail(_.householdId == claims.hh)(ApiError.NotFound("Router not found"))
            _      <- routerRepo.delete(uid).mapError(ApiError.Db(_))
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
