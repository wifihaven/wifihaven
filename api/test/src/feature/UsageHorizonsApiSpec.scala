package wifihaven.api.feature

import wifihaven.api.routes.UsageHorizonsRoutes
import wifihaven.api.usage.RetentionSweepJob
import wifihaven.shared.RetentionHorizons
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

object UsageHorizonsApiSpec extends ZIOSpecDefault {

  def spec = suite("Usage horizons API")(
    test("GET /api/usage/horizons returns the RetentionSweepJob constants verbatim") {
      val routes = UsageHorizonsRoutes.routes
      for {
        resp    <- routes(Request.get("/api/usage/horizons")).merge
        body    <- resp.body.asString
        decoded <- ZIO.fromEither(body.fromJson[RetentionHorizons])
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(decoded.rawDays == RetentionSweepJob.RawRetentionDays) &&
        assertTrue(decoded.hourlyDays == RetentionSweepJob.HourlyRetentionDays) &&
        assertTrue(decoded.dailyDays == RetentionSweepJob.DailyRetentionDays) &&
        assertTrue(decoded == RetentionHorizons(30, 90, 180))
    },
  )
}
