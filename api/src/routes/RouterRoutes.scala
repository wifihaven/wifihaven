package familydns.api.routes

import familydns.api.auth.*
import familydns.api.db.*
import familydns.api.policy.*
import familydns.shared.*
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*

import java.security.SecureRandom
import java.util.{Base64, UUID}

/**
 * Routes agent-facing router endpoints. All routes require the router bearer token except
 * `/register`, which uses a one-time enrollment token. `RouterAuth` lives in
 * [[familydns.api.routes.RouterAuth]].
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
          for {
            body <- req.body.asString.orElseFail(Response.badRequest(""))
            rr   <- ZIO
              .fromEither(body.fromJson[RegisterRouterRequest])
              .mapError(e => Response.badRequest(e))
            etHash = PolicyService.hashToken(rr.enrollmentToken)
            router <- routerRepo
              .findByEnrollmentTokenHash(etHash)
              .mapError(ErrorMapper.dbErrorToResponse)
              .flatMap(
                ZIO
                  .fromOption(_)
                  .orElseFail(Response.unauthorized("invalid enrollment token")),
              )
            routerToken = newToken("rt_")
            tokenHash   = PolicyService.hashToken(routerToken)
            _ <- routerRepo
              .completeEnrollment(router.id, tokenHash)
              .mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.json(RegisterRouterResponse(router.id, routerToken).toJson)
        },
      Method.GET / "api" / "router" / "policy"         ->
        handler { (req: Request) =>
          for {
            router <- routerAuth.authenticate(req)
            snap   <- policy.snapshot.mapError(ErrorMapper.dbErrorToResponse)
            ifNoneMatch = req
              .header(Header.IfNoneMatch)
              .map(_.renderedValue)
              .orElse(req.url.queryParam("since"))
            _ <- routerRepo
              .touch(router.id, Some(snap.etag))
              .mapError(ErrorMapper.dbErrorToResponse)
            notMod = ifNoneMatch.contains(snap.etag)
            _ <- ZIO.logDebug(
              s"router policy: router=${router.id} etagIn=${ifNoneMatch.getOrElse("-")} " +
                s"etagOut=${snap.etag} notModified=$notMod devices=${snap.devices.size} " +
                s"profiles=${snap.profiles.size}",
            )
            resp =
              if notMod then
                Response
                  .status(Status.NotModified)
                  .addHeader(Header.ETag.Strong(stripQuotes(snap.etag)))
              else
                Response
                  .json(snap.toJson)
                  .addHeader(Header.ETag.Strong(stripQuotes(snap.etag)))
          } yield resp
        },
      Method.GET / "api" / "blocklists" / string("id") ->
        handler { (id: String, req: Request) =>
          for {
            _    <- routerAuth.authenticate(req)
            out  <- policy.renderBlocklist(id).mapError(ErrorMapper.dbErrorToResponse)
            resp <- ZIO
              .fromOption(out)
              .mapBoth(
                _ => Response.notFound(s"unknown blocklist: $id"),
                { case (etag, body) =>
                  val ifNone = req.header(Header.IfNoneMatch).map(_.renderedValue)
                  if ifNone.contains(etag) then
                    Response
                      .status(Status.NotModified)
                      .addHeader(Header.ETag.Strong(stripQuotes(etag)))
                  else
                    Response(
                      status = Status.Ok,
                      headers = Headers(
                        Header.ContentType(MediaType("text", "plain")),
                        Header.ETag.Strong(stripQuotes(etag)),
                      ),
                      body = Body.fromString(body),
                    )
                },
              )
          } yield resp
        },
      Method.POST / "api" / "router" / "decision"      ->
        handler { (req: Request) =>
          for {
            router <- routerAuth.authenticate(req)
            body   <- req.body.asString.orElseFail(Response.badRequest(""))
            dreq   <- ZIO
              .fromEither(body.fromJson[RouterDecisionRequest])
              .mapError(e => Response.badRequest(e))
            result <- policy
              .decide(dreq.mac, dreq.hostname)
              .mapError(ErrorMapper.dbErrorToResponse)
            _      <- ZIO
              .when(result.decision == "block") {
                blockEventRepo
                  .insertBatch(
                    List(BlockEventInsert(Some(dreq.mac), dreq.hostname, result.reason)),
                  )
                  .mapError(ErrorMapper.dbErrorToResponse)
              }
          } yield Response.json(result.toJson)
        },
    )

  private def stripQuotes(s: String): String =
    if s.startsWith("\"") && s.endsWith("\"") then s.drop(1).dropRight(1) else s

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
          for {
            _    <- requireAdmin(req, auth)
            body <- req.body.asString.orElseFail(Response.badRequest(""))
            cr   <- ZIO
              .fromEither(body.fromJson[CreateRouterRequest])
              .mapError(e => Response.badRequest(e))
            _    <- ZIO
              .fail(Response.badRequest("name required"))
              .when(cr.name.trim.isEmpty)
            enrollmentToken = newEnrollmentToken()
            etHash          = PolicyService.hashToken(enrollmentToken)
            id <- routerRepo
              .create(cr.name.trim, etHash)
              .mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.json(CreateRouterResponse(id, cr.name.trim, enrollmentToken).toJson)
        },
      Method.GET / "api" / "admin" / "routers"                   ->
        handler { (req: Request) =>
          for {
            _   <- requireAdmin(req, auth)
            all <- routerRepo.listAll.mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.json(all.map(toSummary).toJson)
        },
      Method.DELETE / "api" / "admin" / "routers" / string("id") ->
        handler { (id: String, req: Request) =>
          for {
            _   <- requireAdmin(req, auth)
            uid <- ZIO
              .attempt(UUID.fromString(id))
              .orElseFail(Response.badRequest("bad uuid"))
            _   <- routerRepo.delete(uid).mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.ok
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
      lastClockSkewSeconds = r.lastClockSkewSeconds,
    )

  private def newEnrollmentToken(): String = {
    val bytes = new Array[Byte](24)
    new SecureRandom().nextBytes(bytes)
    "et_" + Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
  }
}
