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
 * Authentication for the agent-facing `/api/router/` and `/api/blocklists/` endpoints. The agent
 * sends a per-router bearer token; the API stores only a SHA-256 hash. NOTE: kept here (not in a
 * separate file) because issue #69 adds its own `RouterAuth.scala`; centralizing in #71 once the
 * dust settles.
 */
trait RouterAuth:
  def authenticate(req: Request): IO[Response, Router]

class RouterAuthLive(repo: RouterRepo) extends RouterAuth:
  def authenticate(req: Request): IO[Response, Router] =
    RouterAuth.bearer(req) match
      case None    => ZIO.fail(Response.unauthorized("Missing router token"))
      case Some(t) =>
        repo
          .findByTokenHash(PolicyService.hashToken(t))
          .orElseFail(Response.internalServerError(""))
          .flatMap(ZIO.fromOption(_).orElseFail(Response.unauthorized("Invalid router token")))

object RouterAuth:
  private[routes] def bearer(req: Request): Option[String] =
    req.header(Header.Authorization).flatMap { h =>
      val v = h.renderedValue
      if v.startsWith("Bearer ") then Some(v.drop(7)) else None
    }

/**
 * Routes the OpenWRT agent calls. Auth: all routes here require the router's bearer token, except
 * `/register` which uses a one-time enrollment token.
 */
object RouterRoutes:

  def routes(
      routerRepo: RouterRepo,
      policy: PolicyService,
      routerAuth: RouterAuth,
  ): Routes[Any, Response] =
    Routes(
      Method.POST / "api" / "router" / "register"        ->
        handler { (req: Request) =>
          for
            body <- req.body.asString.orElseFail(Response.badRequest(""))
            rr   <- ZIO
              .fromEither(body.fromJson[RegisterRouterRequest])
              .mapError(e => Response.badRequest(e))
            etHash = PolicyService.hashToken(rr.enrollmentToken)
            router <- routerRepo
              .findByEnrollmentTokenHash(etHash)
              .orElseFail(Response.internalServerError(""))
              .flatMap(
                ZIO
                  .fromOption(_)
                  .orElseFail(Response.unauthorized("invalid enrollment token")),
              )
            routerToken = newToken("rt_")
            tokenHash   = PolicyService.hashToken(routerToken)
            _ <- routerRepo
              .completeEnrollment(router.id, tokenHash)
              .orElseFail(Response.internalServerError(""))
          yield Response.json(RegisterRouterResponse(router.id, routerToken).toJson)
        },
      Method.GET / "api" / "router" / "policy"           ->
        handler { (req: Request) =>
          for
            router <- routerAuth.authenticate(req)
            snap   <- policy.snapshot.orElseFail(Response.internalServerError(""))
            ifNoneMatch = req
              .header(Header.IfNoneMatch)
              .map(_.renderedValue)
              .orElse(req.url.queryParam("since"))
            _ <- routerRepo
              .touch(router.id, Some(snap.etag))
              .orElseFail(Response.internalServerError(""))
            resp =
              if ifNoneMatch.contains(snap.etag) then
                Response
                  .status(Status.NotModified)
                  .addHeader(Header.ETag.Strong(stripQuotes(snap.etag)))
              else
                Response
                  .json(snap.toJson)
                  .addHeader(Header.ETag.Strong(stripQuotes(snap.etag)))
          yield resp
        },
      Method.GET / "api" / "blocklists" / string("file") ->
        handler { (file: String, req: Request) =>
          for
            _    <- routerAuth.authenticate(req)
            cat  <- ZIO
              .succeed(file.stripSuffix(".rpz"))
              .filterOrFail(_ != file)(Response.notFound("expected .rpz suffix"))
            out  <- policy.renderRpz(cat).orElseFail(Response.internalServerError(""))
            resp <- ZIO
              .fromOption(out)
              .mapBoth(
                _ => Response.notFound(s"unknown category: $cat"),
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
                        Header.ContentType(MediaType("text", "dns")),
                        Header.ETag.Strong(stripQuotes(etag)),
                      ),
                      body = Body.fromString(body),
                    )
                },
              )
          yield resp
        },
    )

  private def stripQuotes(s: String): String =
    if s.startsWith("\"") && s.endsWith("\"") then s.drop(1).dropRight(1) else s

  private def newToken(prefix: String): String =
    val bytes = new Array[Byte](32)
    new SecureRandom().nextBytes(bytes)
    prefix + Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

/** Admin-only routes for managing routers (visible in admin UI). */
object AdminRouterRoutes:
  def routes(
      auth: AuthService,
      routerRepo: RouterRepo,
  ): Routes[Any, Response] =
    Routes(
      Method.POST / "api" / "admin" / "routers"                  ->
        handler { (req: Request) =>
          for
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
              .orElseFail(Response.internalServerError(""))
          yield Response.json(CreateRouterResponse(id, cr.name.trim, enrollmentToken).toJson)
        },
      Method.GET / "api" / "admin" / "routers"                   ->
        handler { (req: Request) =>
          for
            _   <- requireAdmin(req, auth)
            all <- routerRepo.listAll.orElseFail(Response.internalServerError(""))
          yield Response.json(all.map(toSummary).toJson)
        },
      Method.DELETE / "api" / "admin" / "routers" / string("id") ->
        handler { (id: String, req: Request) =>
          for
            _   <- requireAdmin(req, auth)
            uid <- ZIO
              .attempt(UUID.fromString(id))
              .orElseFail(Response.badRequest("bad uuid"))
            _   <- routerRepo.delete(uid).orElseFail(Response.internalServerError(""))
          yield Response.ok
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
    )

  private def newEnrollmentToken(): String =
    val bytes = new Array[Byte](24)
    new SecureRandom().nextBytes(bytes)
    "et_" + Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
