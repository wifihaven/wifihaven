package wifihaven.api.feature

import wifihaven.api.openapi.OpenApiSpec
import wifihaven.api.openapi.OpenApiSpec.AuthScheme
import zio.test.*

/**
 * #638 — pin `OpenApiSpec.authPartition` against a canonical sample drawn from every route family.
 *
 * The spec's public/router/user partition lives in one hand-maintained list (see
 * `OpenApiSpec.authPartition`); the per-handler enforcement lives in each `*Routes.scala`
 * (`requireAuth` / `requireAdmin` / `routerAuth`). Those two sources can drift — exactly the
 * §single-source-of-truth display-vs-enforcement risk that bit #1539. This spec is the test-pin
 * that catches the drift: every routing family is represented by a known path + the auth scheme its
 * handler enforces today. Flipping a handler's auth without updating the partition (or vice-versa)
 * FAILS this test.
 *
 * When you add a new route family, add one row here matching the handler's auth. When you flip an
 * existing route's auth (e.g. exposing a previously-admin endpoint publicly), update both this row
 * AND `OpenApiSpec.authPartition`.
 */
object OpenApiAuthPartitionSpec extends ZIOSpecDefault {

  /** (path, expected scheme, source-of-truth handler file). */
  private val known: Vector[(String, AuthScheme, String)] =
    Vector(
      // Fully public
      ("/api/health", AuthScheme.Public, "HealthRoutes.scala"),
      ("/api/version", AuthScheme.Public, "VersionRoutes.scala"),
      ("/api/auth/login", AuthScheme.Public, "Routes.scala (AuthRoutes)"),
      ("/api/blocked", AuthScheme.Public, "BlockedRoutes.scala"),
      ("/api/openapi.json", AuthScheme.Public, "OpenApiRoutes.scala (#638)"),
      ("/api/docs", AuthScheme.Public, "OpenApiRoutes.scala (#638)"),
      // Router bearer (enrollment-derived, not user JWT)
      ("/api/router/policy", AuthScheme.Router, "RouterRoutes.scala"),
      ("/api/router/register", AuthScheme.Router, "RouterRoutes.scala"),
      ("/api/router/events", AuthScheme.Router, "RouterIngestRoutes.scala"),
      ("/api/router/usage", AuthScheme.Router, "RouterIngestRoutes.scala"),
      ("/api/router/metrics", AuthScheme.Router, "RouterMetricsRoutes.scala"),
      ("/api/router/decision", AuthScheme.Router, "RouterRoutes.scala"),
      ("/api/blocklists/{id}", AuthScheme.Router, "RouterRoutes.scala (router-served URL)"),
      // User-session JWT (every other authenticated endpoint defaults here)
      ("/api/me", AuthScheme.User, "Routes.scala (AuthRoutes)"),
      ("/api/users", AuthScheme.User, "Routes.scala (AuthRoutes)"),
      ("/api/profiles", AuthScheme.User, "Routes.scala (ProfileRoutes)"),
      ("/api/profiles/{id}", AuthScheme.User, "Routes.scala (ProfileRoutes)"),
      ("/api/devices", AuthScheme.User, "Routes.scala (DeviceRoutes)"),
      ("/api/schedules", AuthScheme.User, "ScheduleRoutes.scala"),
      ("/api/household-settings", AuthScheme.User, "Routes.scala (HouseholdSettingsRoutes)"),
      ("/api/global-policy", AuthScheme.User, "GlobalPolicyRoutes.scala"),
      ("/api/alerts", AuthScheme.User, "AlertRoutes.scala"),
      ("/api/apps", AuthScheme.User, "AppRoutes.scala"),
      ("/api/dashboard/now", AuthScheme.User, "DashboardNowRoutes.scala"),
      ("/api/admin/routers", AuthScheme.User, "RouterRoutes.scala (AdminRouterRoutes)"),
      ("/api/admin/rollup", AuthScheme.User, "RollupAdminRoutes.scala"),
    )

  def spec = suite("OpenApiSpec auth-partition drift (#638)")(
    test("every known route resolves to its handler's auth scheme") {
      val mismatches = known.flatMap { case (path, expected, source) =>
        val actual = OpenApiSpec.authForTesting(path)
        if (actual == expected) None
        else Some(s"$path → expected $expected (per $source), got $actual")
      }
      assertTrue(mismatches.isEmpty) ?? s"auth partition drift:\n  ${mismatches.mkString("\n  ")}"
    },
  )
}
