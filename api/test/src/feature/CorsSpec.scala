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
      handler { (_: String, _: Request) => Response.ok.addHeader(Header.ETag.Strong("abc123")) }

  private val routes = Cors.wrap(Routes(loginEcho, etagRoute), cfg)

  private def runReq(req: Request): UIO[Response] = routes(req).merge

  def spec = suite("CORS middleware (#612)")(
    test("preflight from allowed origin → 204 with echo + configured headers") {
      // #626: send the request header lowercase the way a real browser does
      // (per Fetch spec). Asserts on the actual Allow-Headers content, not
      // just presence — the original #616 test only checked presence, which
      // is how the empty-value regression slipped through.
      val req = Request
        .options("/api/auth/login")
        .addHeader(Header.Origin.parse(allowedOrigin).toOption.get)
        .addHeader(Header.AccessControlRequestMethod(Method.POST))
        .addHeader(
          Header.AccessControlRequestHeaders(NonEmptyChunk("content-type")),
        )
      for {
        resp <- runReq(req)
        ao = resp.header(Header.AccessControlAllowOrigin)
        am = resp.header(Header.AccessControlAllowMethods)
        ah = resp.header(Header.AccessControlAllowHeaders)
        ma = resp.header(Header.AccessControlMaxAge)
        ac = resp.header(Header.AccessControlAllowCredentials)
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
            vs.exists(_.equalsIgnoreCase("content-type"))
          case _                                         => false
        },
        ma.exists(_.duration == 10.minutes),
        ac.contains(Header.AccessControlAllowCredentials.DoNotAllow),
      )
    },
    test("preflight echoes content-type when browser requests it (SPA login shape, #626)") {
      // Exact shape of the SPA login preflight the browser sends: lowercase
      // `content-type` in Access-Control-Request-Headers. Regression guard
      // for the empty Allow-Headers seen on staging.
      val req = Request
        .options("/api/auth/login")
        .addHeader(Header.Origin.parse(allowedOrigin).toOption.get)
        .addHeader(Header.AccessControlRequestMethod(Method.POST))
        .addHeader(
          Header.AccessControlRequestHeaders(NonEmptyChunk("content-type")),
        )
      for {
        resp <- runReq(req)
      } yield assertTrue(
        resp.status == Status.NoContent,
        resp.header(Header.AccessControlAllowHeaders).exists {
          case Header.AccessControlAllowHeaders.Some(vs) =>
            vs.exists(_.equalsIgnoreCase("content-type"))
          case _                                         => false
        },
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
        resp
          .header(Header.AccessControlAllowOrigin)
          .contains(
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
    test("post-soak prod allowlist accepts app.* only — apex/www rejected (#1843)") {
      // #1840 (#1832 rename) added app.wifihaven.net additively alongside the
      // apex/www origins. #1843 dropped apex/www after the soak: since #1842
      // they front the marketing Pages project, serve no SPA bundle and make no
      // API calls, so no browser context can emit those origins. This pins the
      // render.yaml prod value so a future edit cannot silently re-widen it.
      val prodCfg                   = CorsConfig(
        allowedOrigins = "https://app.wifihaven.net",
      )
      val prodRoutes                = Cors.wrap(Routes(loginEcho), prodCfg)
      def preflight(origin: String) =
        Request
          .options("/api/auth/login")
          .addHeader(Header.Origin.parse(origin).toOption.get)
          .addHeader(Header.AccessControlRequestMethod(Method.POST))
      def echoed(origin: String)    =
        prodRoutes(preflight(origin)).merge.map { resp =>
          resp
            .header(Header.AccessControlAllowOrigin)
            .contains(
              Header.AccessControlAllowOrigin.Specific(
                Header.Origin.parse(origin).toOption.get,
              ),
            )
        }
      def rejected(origin: String)  =
        prodRoutes(preflight(origin)).merge
          .map(_.header(Header.AccessControlAllowOrigin).isEmpty)
      for {
        app  <- echoed("https://app.wifihaven.net")
        apex <- rejected("https://wifihaven.net")
        www  <- rejected("https://www.wifihaven.net")
        evil <- rejected("https://evil.example")
      } yield assertTrue(app, apex, www, evil)
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
