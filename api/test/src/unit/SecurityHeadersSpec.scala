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
    test("style-src allowlists the Google Fonts stylesheet host so Inter loads") {
      val styleSrc = SecurityHeaders.ContentSecurityPolicy
        .split(';')
        .map(_.trim)
        .find(_.startsWith("style-src"))
        .getOrElse("")
      assertTrue(styleSrc.contains("https://fonts.googleapis.com"))
    },
    // #2468: style-src allowed https://fonts.googleapis.com (the Google Fonts CSS) but there was no
    // font-src at all, so the woff2 files — served from https://fonts.gstatic.com — fell back to
    // default-src 'self' and were blocked. web/index.html loads Inter from Google Fonts, so the
    // self-hosted SPA rendered in the system fallback font from #2082 until this pin. Scoped to the
    // font-src directive: allowing the host under style-src does not let the browser fetch the file.
    test("font-src allowlists the Google Fonts file host so Inter loads (#2468)") {
      val fontSrc = SecurityHeaders.ContentSecurityPolicy
        .split(';')
        .map(_.trim)
        .find(_.startsWith("font-src"))
        .getOrElse("")
      assertTrue(fontSrc.contains("'self'")) &&
      assertTrue(fontSrc.contains("https://fonts.gstatic.com"))
    },
    // #2954: the Plain support widget was removed, so its script/connect/img hosts must be gone (a
    // lingering third-party script-src is an XSS-surface regression), and nothing wildcarded may
    // have replaced them.
    test("no Plain hosts or wildcards remain in the CSP (#2954)") {
      val csp = SecurityHeaders.ContentSecurityPolicy
      assertTrue(!csp.contains("plain.com")) &&
      assertTrue(!csp.contains("cdn-plain")) &&
      assertTrue(!csp.contains("amazonaws.com")) &&
      assertTrue(!csp.contains("https://*")) &&
      assertTrue(csp.contains("script-src 'self';")) &&
      assertTrue(csp.contains("frame-ancestors 'none'"))
    },
  )
}
