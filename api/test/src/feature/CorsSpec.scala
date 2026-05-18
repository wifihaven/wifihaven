package wifihaven.api.feature

import wifihaven.api.{Cors, CorsConfig}
import zio.*
import zio.http.*
import zio.test.*

// #612: exercises the CORS middleware against a representative subset of
// routes — a POST endpoint (login-shaped) and a GET endpoint that emits an
// ETag (router-blocklist-shaped). The middleware is configured once with the
// staging origin to mirror the production wiring.
object CorsSpec extends ZIOSpecDefault {

  private val allowedOrigin    = "https://staging.wifihaven.net"
  private val disallowedOrigin = "https://evil.example"
  private val cfg              = CorsConfig(allowedOrigins = allowedOrigin)

  private val loginEcho =
    Method.POST / "api" / "auth" / "login" -> handler(Response.ok)

  private val etagRoute =
    Method.GET / "api" / "router" / "blocklist" / string("id") ->
      handler { (_: String, _: Request) =>
        Response.ok.addHeader(Header.ETag.Strong("abc123"))
      }

  private val routes = Cors.wrap(Routes(loginEcho, etagRoute), cfg)

  private def runReq(req: Request): UIO[Response] = routes(req).merge

  def spec = suite("CORS middleware (#612)")(
    test("preflight from allowed origin → 204 with echo + configured headers") {
      val req = Request
        .options("/api/auth/login")
        .addHeader(Header.Origin.parse(allowedOrigin).toOption.get)
        .addHeader(Header.AccessControlRequestMethod(Method.POST))
        .addHeader(
          Header.AccessControlRequestHeaders(NonEmptyChunk("Content-Type")),
        )
      for {
        resp <- runReq(req)
        ao    = resp.header(Header.AccessControlAllowOrigin)
        am    = resp.header(Header.AccessControlAllowMethods)
        ah    = resp.header(Header.AccessControlAllowHeaders)
        ma    = resp.header(Header.AccessControlMaxAge)
        ac    = resp.header(Header.AccessControlAllowCredentials)
      } yield assertTrue(
        resp.status == Status.NoContent,
        ao.contains(
          Header.AccessControlAllowOrigin.Specific(
            Header.Origin.parse(allowedOrigin).toOption.get,
          ),
        ),
        am.exists(_.contains(Method.POST)),
        am.exists(_.contains(Method.DELETE)),
        ah.exists {
          case Header.AccessControlAllowHeaders.Some(vs) =>
            vs.exists(_.equalsIgnoreCase("Content-Type"))
          case _                                         => false
        },
        ma.exists(_.duration == 10.minutes),
        ac.contains(Header.AccessControlAllowCredentials.DoNotAllow),
      )
    },
    test("preflight from disallowed origin → no allow-origin header") {
      val req = Request
        .options("/api/auth/login")
        .addHeader(Header.Origin.parse(disallowedOrigin).toOption.get)
        .addHeader(Header.AccessControlRequestMethod(Method.POST))
      for {
        resp <- runReq(req)
      } yield assertTrue(
        resp.header(Header.AccessControlAllowOrigin).isEmpty,
        resp.status != Status.NoContent,
      )
    },
    test("actual POST from allowed origin → allow-origin echoed, ETag exposed") {
      val req = Request
        .post("/api/auth/login", Body.empty)
        .addHeader(Header.Origin.parse(allowedOrigin).toOption.get)
      for {
        resp <- runReq(req)
      } yield assertTrue(
        resp.status == Status.Ok,
        resp.header(Header.AccessControlAllowOrigin).contains(
          Header.AccessControlAllowOrigin.Specific(
            Header.Origin.parse(allowedOrigin).toOption.get,
          ),
        ),
        resp.header(Header.AccessControlExposeHeaders).exists {
          case Header.AccessControlExposeHeaders.Some(vs) =>
            vs.exists(_.toString.equalsIgnoreCase("ETag"))
          case _                                          => false
        },
      )
    },
    test("actual POST from disallowed origin → no allow-origin header") {
      val req = Request
        .post("/api/auth/login", Body.empty)
        .addHeader(Header.Origin.parse(disallowedOrigin).toOption.get)
      for {
        resp <- runReq(req)
      } yield assertTrue(
        resp.status == Status.Ok,
        resp.header(Header.AccessControlAllowOrigin).isEmpty,
      )
    },
    test(
      "router agent path (no Origin) → no CORS headers, ETag still served",
    ) {
      val req = Request.get("/api/router/blocklist/foo")
      for {
        resp <- runReq(req)
      } yield assertTrue(
        resp.status == Status.Ok,
        resp.header(Header.AccessControlAllowOrigin).isEmpty,
        resp.header(Header.AccessControlExposeHeaders).isEmpty,
        resp.header(Header.ETag).isDefined,
      )
    },
    test("empty allowedOrigins disables middleware entirely") {
      val bare =
        Cors.wrap(Routes(loginEcho), CorsConfig(allowedOrigins = ""))
      val req  = Request
        .post("/api/auth/login", Body.empty)
        .addHeader(Header.Origin.parse(allowedOrigin).toOption.get)
      for {
        resp <- bare(req).merge
      } yield assertTrue(
        resp.status == Status.Ok,
        resp.header(Header.AccessControlAllowOrigin).isEmpty,
      )
    },
  )
}
