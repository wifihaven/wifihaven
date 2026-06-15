package wifihaven.api.routes

import wifihaven.api.usage.RetentionSweepJob
import wifihaven.shared.RetentionHorizons
import zio.http.*
import zio.json.*

/**
 * #1740 — single-source-of-truth surface for the usage retention horizons.
 *
 * The values were duplicated between `RetentionSweepJob` (Scala) and
 * `web/src/components/usage/retentionGating.ts` (TypeScript) — exactly the kind of cross-process
 * drift the §single-source-of-truth audit (#1532) exists to collapse. The route reads the constants
 * directly from `RetentionSweepJob` so there's nowhere for them to diverge from the actual sweep
 * behaviour.
 *
 * Unauthenticated and DB-free, like `/api/version`: the response carries no household-specific or
 * auth-sensitive data — it's the same compile-time constants for every caller, and the SPA fetches
 * it at boot before login is meaningful. Routing through auth would buy nothing and would force the
 * SPA to defer the fetch (or get 401s) on the public block-page surface.
 */
object UsageHorizonsRoutes {

  val payload: RetentionHorizons = RetentionHorizons(
    rawDays = RetentionSweepJob.RawRetentionDays,
    hourlyDays = RetentionSweepJob.HourlyRetentionDays,
    dailyDays = RetentionSweepJob.DailyRetentionDays,
  )

  // Payload is a compile-time constant; pre-serialize once and serve the
  // cached string instead of re-encoding on every request.
  private val body: String = payload.toJson

  val routes: Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "usage" / "horizons" ->
        handler { (_: Request) => Response.json(body) },
    )
}
