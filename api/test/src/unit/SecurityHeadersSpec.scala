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
    // #2240: the Plain chat-widget hosts (https://help.plain.com/article/chat) must be present so the
    // widget script loads and the SDK can connect/load fonts+images. Pinned here (and on the
    // web/public/_headers side by web/src/security-headers.test.ts) so the two copies stay in sync.
    test("allowlists Plain chat-widget hosts across script/connect/style/img (#2240)") {
      val csp = SecurityHeaders.ContentSecurityPolicy
      assertTrue(csp.contains("https://chat.cdn-plain.com")) &&   // script-src
      assertTrue(csp.contains("https://chat.uk.plain.com")) &&    // connect-src (UK region)
      assertTrue(csp.contains("https://fonts.googleapis.com")) && // style-src
      assertTrue(csp.contains("https://i0.wp.com")) &&            // img-src (Gravatar avatars)
      assertTrue(
        csp.contains(
          "https://prod-uk-services-attachm-attachmentsuploadbucket2-1l2e4906o2asm.s3.eu-west-2.amazonaws.com",
        ),
      ) && // connect-src attachment-upload bucket
      assertTrue(
        csp.contains(
          "https://prod-uk-services-workspac-workspacefilespublicbuck-vs4gjqpqjkh6.s3.amazonaws.com",
        ),
      ) && // img-src workspace-logo bucket
      assertTrue(
        csp.contains(
          "https://prod-uk-services-attachm-attachmentsbucket28b3ccf-uwfssb4vt2us.s3.eu-west-2.amazonaws.com",
        ),
      )    // img-src attachment bucket
    },
    // #2418: the SDK renders an agent message's avatar from `actor.avatarUrl`, which for an API_USER
    // machine user is https://static-assets.plain.com/email-images/machine-user.png (and the bundle
    // hard-codes .../avatars/ari-avatar.svg for AI_AGENT). Plain's published CSP list omits this
    // host, so it broke every AI reply's avatar until #2418. Pinned so it can't be dropped again
    // (#2115 lesson: a silently dropped img-src host breaks images with no test failure).
    test("img-src allowlists Plain's machine-user avatar host (#2418)") {
      val csp = SecurityHeaders.ContentSecurityPolicy
      assertTrue(csp.contains("https://static-assets.plain.com"))
    },
    // Over-broadening guard: Plain documents no iframe, so we must NOT loosen frame-ancestors or add
    // a wildcard for Plain — the additions are exact hosts only.
    test("Plain additions do not introduce wildcards or loosen frame-ancestors (#2240)") {
      val csp = SecurityHeaders.ContentSecurityPolicy
      assertTrue(csp.contains("frame-ancestors 'none'")) &&
      assertTrue(!csp.contains("*.plain.com")) &&
      assertTrue(!csp.contains("https://*"))
    },
  )
}
