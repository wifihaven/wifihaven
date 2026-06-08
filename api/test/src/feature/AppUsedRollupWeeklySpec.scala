package wifihaven.api.feature

import wifihaven.api.db.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.test.*

import java.time.{Instant, LocalDate}

/**
 * #1089 — weekly per-(profile, app) engaged-minutes aggregated FROM the per-(profile, app, date)
 * `app_used_daily` rollup over a 7-day window. The weekly view is a pure aggregation of the same
 * primitive the daily view reads, so the heartbeat filter / household-settings invalidation that
 * shape the daily rollup at write time flow through unchanged (CLAUDE.md §single-source-of-truth —
 * no parallel pipeline).
 */
object AppUsedRollupWeeklySpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres] {

  override val bootstrap = TestDatabase.layer

  private val cleanDb = TestDatabase.cleanAndMigrate

  private def seedApp(
      appRepo: AppRepo,
      slug: String,
      hosts: List[String],
  ): Task[AppId] =
    for {
      id <- appRepo.create(slug, slug, None, None)
      _  <- appRepo.setHosts(id, hosts.map(Hostname.unsafe))
    } yield id

  // Plant one rolled row at (profile, app, date) with `engagedSeconds` and an arbitrary watermark.
  private def plant(
      aru: AppUsedRollupRepo,
      profileId: ProfileId,
      appId: AppId,
      date: LocalDate,
      seconds: Long,
  ): Task[Unit] =
    aru.upsertDay(
      profileId,
      appId,
      date,
      RolledAppDay(seconds, Instant.parse("2025-01-01T00:00:00Z")),
    )

  def spec = suite("AppUsedRollupWeeklySpec (#1089)")(
    test("getRangeForProfile returns every rollup row in [from,to] inclusive, others excluded") {
      // 14 days planted; query a 7-day window in the middle returns only those 7 days' rows for the
      // requested profile, never for a different profile or outside the window.
      for {
        _      <- cleanDb
        pr     <- ZIO.service[ProfileRepo]
        sr     <- ZIO.service[ScheduleRepo]
        ar     <- ZIO.service[AppRepo]
        aru    <- ZIO.service[AppUsedRollupRepo]
        kid    <- TestLayers.seedKidsProfile(pr, sr)
        adults <- TestLayers.seedAdultsProfile(pr)
        ytApp  <- seedApp(ar, "yt", List("youtube.com"))
        d0 = LocalDate.of(2025, 1, 1)
        // 14 days, 600 s (10 min) each, kids profile, yt app.
        _    <- ZIO.foreachDiscard(0 until 14)(i =>
          plant(aru, kid, ytApp, d0.plusDays(i.toLong), 600L),
        )
        // Adults gets one row at d0 — must NOT appear in a kids range query.
        _    <- plant(aru, adults, ytApp, d0, 99L)
        // Query days 3..9 inclusive (a 7-day mid window): should return exactly 7 kids rows.
        rows <- aru.getRangeForProfile(kid, d0.plusDays(3), d0.plusDays(9))
      } yield assertTrue(rows.size == 7) &&
        assertTrue(rows.forall(_._3 == 600L)) &&
        assertTrue(rows.map(_._1).toSet == (3 to 9).map(i => d0.plusDays(i.toLong)).toSet)
    },
    test("two weeklies in a 14-day span sum per app correctly") {
      // Week 1 (Jan 1–7): yt=10 min/day x 7 days = 70 min, social=5 min/day x 4 days = 20 min.
      // Week 2 (Jan 8–14): yt=20 min/day x 7 days = 140 min, social=0.
      for {
        _         <- cleanDb
        pr        <- ZIO.service[ProfileRepo]
        sr        <- ZIO.service[ScheduleRepo]
        ar        <- ZIO.service[AppRepo]
        aru       <- ZIO.service[AppUsedRollupRepo]
        kid       <- TestLayers.seedKidsProfile(pr, sr)
        ytApp     <- seedApp(ar, "yt", List("youtube.com"))
        socialApp <- seedApp(ar, "social", List("social.com"))
        w1Start = LocalDate.of(2025, 1, 1)
        w2Start = LocalDate.of(2025, 1, 8)
        // Week 1.
        _      <- ZIO.foreachDiscard(0 until 7)(i =>
          plant(aru, kid, ytApp, w1Start.plusDays(i.toLong), 600L),
        )
        _      <- ZIO.foreachDiscard(0 until 4)(i =>
          plant(aru, kid, socialApp, w1Start.plusDays(i.toLong), 300L),
        )
        // Week 2.
        _      <- ZIO.foreachDiscard(0 until 7)(i =>
          plant(aru, kid, ytApp, w2Start.plusDays(i.toLong), 1200L),
        )
        w1Rows <- aru.getRangeForProfile(kid, w1Start, w1Start.plusDays(6))
        w2Rows <- aru.getRangeForProfile(kid, w2Start, w2Start.plusDays(6))
        sumPerApp1 = w1Rows.groupMapReduce(_._2)(_._3)(_ + _)
        sumPerApp2 = w2Rows.groupMapReduce(_._2)(_._3)(_ + _)
      } yield assertTrue(sumPerApp1.get(ytApp).contains(7 * 600L)) &&
        assertTrue(sumPerApp1.get(socialApp).contains(4 * 300L)) &&
        assertTrue(sumPerApp2.get(ytApp).contains(7 * 1200L)) &&
        assertTrue(!sumPerApp2.contains(socialApp))
    },
  ) @@ TestAspect.sequential
}
