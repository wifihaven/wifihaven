package wifihaven.api.unit

import wifihaven.api.SecurityHeaders
import wifihaven.api.routes.HealthRoutes
import zio.*
import zio.http.*
import zio.test.*

/**
 * #2082: every API response carries HSTS/CSP/X-Frame-Options/X-Content-Type-Options — previously
 * none of these were set on any route (verified live via `curl -I` on prod per the #369 audit).
 */
object SecurityHeadersSpec extends ZIOSpecDefault {

  def spec = suite("SecurityHeaders")(
    test("wraps any route with the security response headers") {
      val routes = SecurityHeaders.wrap(HealthRoutes.routes(ZIO.succeed(true), ZIO.unit))
      for {
        resp <- routes(Request.get("/api/health")).merge
      } yield assertTrue(
        resp.headers.get("X-Content-Type-Options").contains("nosniff"),
      ) &&
        assertTrue(resp.headers.get("X-Frame-Options").contains("DENY")) &&
        assertTrue(
          resp.headers.get("Strict-Transport-Security").exists(_.contains("max-age=")),
        ) &&
        assertTrue(
          resp.headers.get("Content-Security-Policy").exists(_.contains("frame-ancestors 'none'")),
        )
    },
    test("CSP blocks framing and restricts default-src to self") {
      val csp = SecurityHeaders.ContentSecurityPolicy
      assertTrue(csp.contains("default-src 'self'")) &&
      assertTrue(csp.contains("frame-ancestors 'none'"))
    },
    test("img-src allowlists the app-icon host so app favicons render (#2115)") {
      val csp = SecurityHeaders.ContentSecurityPolicy
      assertTrue(csp.contains("img-src 'self' data: https://icons.duckduckgo.com"))
    },
  )
}
