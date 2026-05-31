package wifihaven.api.feature

import doobie.*
import doobie.implicits.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import wifihaven.api.db.*
import wifihaven.api.policy.PolicyService
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import zio.*
import zio.interop.catz.*
import zio.test.*

import java.time.{Instant, LocalDate, ZoneOffset}

// #809: rollup table migration + repo behavior.
//
// Coverage:
//   - V38 migration creates traffic_hourly / traffic_daily / rollup_runs with
//     the expected PKs and bucket indexes.
//   - rerollHourly / rerollDaily aggregate traffic_reports rows correctly.
//   - Re-running either is a no-op (UPSERT idempotency).
//   - Daily backfill sums match the hourly backfill grouped to day.
object RollupRepoSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Transactor[Task]] {

  override val bootstrap = TestDatabase.layer

  private val cleanDb = TestDatabase.cleanAndMigrate

  // Seed one router row so the rollup tables' FK has something to point at.
  private def seedRouter: RIO[RouterRepo, RouterId] =
    ZIO.serviceWithZIO[RouterRepo] { rr => rr.create("test", PolicyService.hashToken("et_dummy")) }

  private val mac  = MacAddress.unsafe("aa:bb:cc:dd:ee:01")
  private val host = HostId.Fqdn(Hostname.unsafe("example.com"))

  // Seed `bucketCount` 5-min traffic_reports rows starting at `startUtc`.
  private def seedReports(
      routerId: RouterId,
      startUtc: Instant,
      bucketCount: Int,
      bytesPerBucket: Long = 100L,
  ): RIO[TrafficReportRepo, Unit] =
    ZIO.serviceWithZIO[TrafficReportRepo] { tr =>
      val inserts = (0 until bucketCount).map { i =>
        val s = startUtc.plusSeconds(i.toLong * 300L)
        val e = s.plusSeconds(300L)
        TrafficReportInsert(
          routerId,
          mac,
          None,
          host,
          s.atZone(ZoneOffset.UTC).toLocalDate,
          s,
          e,
          300,
          bytesPerBucket,
          bytesPerBucket * 2,
        )
      }.toList
      tr.insertBatch(inserts).unit
    }

  // Snapshot counts for the rollup tables (for idempotency assertions).
  private def hourlyCount: RIO[Transactor[Task], Long] =
    ZIO.serviceWithZIO[Transactor[Task]](xa =>
      sql"SELECT COUNT(*) FROM traffic_hourly".query[Long].unique.transact(xa),
    )
  private def dailyCount: RIO[Transactor[Task], Long]  =
    ZIO.serviceWithZIO[Transactor[Task]](xa =>
      sql"SELECT COUNT(*) FROM traffic_daily".query[Long].unique.transact(xa),
    )

  // ─────────────────────────────────────────────────────────────────────────
  def spec = suite("RollupRepo + V38 migration")(
    test("V38 creates traffic_hourly + traffic_daily + rollup_runs with PKs and indexes") {
      for {
        _          <- cleanDb
        xa         <- ZIO.service[Transactor[Task]]
        tableNames <-
          sql"""SELECT table_name FROM information_schema.tables
                WHERE table_schema='public'
                  AND table_name IN ('traffic_hourly','traffic_daily','rollup_runs')
                ORDER BY table_name"""
            .query[String]
            .to[List]
            .transact(xa)
        hourlyIdx  <-
          sql"""SELECT indexname FROM pg_indexes
                WHERE tablename='traffic_hourly' AND indexname='idx_traffic_hourly_bucket_start'"""
            .query[String]
            .to[List]
            .transact(xa)
        dailyIdx   <-
          sql"""SELECT indexname FROM pg_indexes
                WHERE tablename='traffic_daily' AND indexname='idx_traffic_daily_date'"""
            .query[String]
            .to[List]
            .transact(xa)
        hourlyPk   <-
          sql"""SELECT pg_get_constraintdef(c.oid)
                FROM pg_constraint c
                WHERE c.conrelid='traffic_hourly'::regclass AND c.contype='p'"""
            .query[String]
            .unique
            .transact(xa)
      } yield assertTrue(
        tableNames == List("rollup_runs", "traffic_daily", "traffic_hourly"),
        hourlyIdx.nonEmpty,
        dailyIdx.nonEmpty,
        hourlyPk.contains("router_id") && hourlyPk.contains("mac") &&
          hourlyPk.contains("hostname") && hourlyPk.contains("bucket_start"),
      )
    },
    test("rerollHourly aggregates 5-min rows into a single hour bucket") {
      for {
        _   <- cleanDb
        rid <- seedRouter
        // 12 buckets × 5 min = exactly one hour
        start = LocalDate
          .of(2026, 1, 1)
          .atStartOfDay(ZoneOffset.UTC)
          .toInstant
        _      <- seedReports(rid, start, 12, bytesPerBucket = 100L)
        rollup <- ZIO.service[RollupRepo]
        rolled <- rollup.rerollHourly(start.minusSeconds(3600)).map(_.getOrElse(0))
        // Read back what landed
        rows   <- rollup.listHourlyInRange(List(mac), start, start.plusSeconds(3600 * 2))
      } yield assertTrue(
        rows.size == 1,
        rows.head.bucketStart == start,
        rows.head.activeSeconds == 12 * 300,
        rows.head.bytesIn == 12 * 100L,
        rows.head.bytesOut == 12 * 200L,
        rolled > 0,
      )
    },
    test("rerollHourly is idempotent — second run preserves counts") {
      for {
        _   <- cleanDb
        rid <- seedRouter
        start = LocalDate
          .of(2026, 1, 1)
          .atStartOfDay(ZoneOffset.UTC)
          .toInstant
        _      <- seedReports(rid, start, 24, bytesPerBucket = 50L) // 2 hours
        rollup <- ZIO.service[RollupRepo]
        _      <- rollup.rerollHourly(start.minusSeconds(3600))
        before <- hourlyCount
        _      <- rollup.rerollHourly(start.minusSeconds(3600))
        after  <- hourlyCount
      } yield assertTrue(before == 2L, after == before)
    },
    test("rerollDaily aggregates a full day's hourly rows into one daily row") {
      for {
        _   <- cleanDb
        rid <- seedRouter
        date  = LocalDate.of(2026, 1, 1)
        start = date.atStartOfDay(ZoneOffset.UTC).toInstant
        // 288 5-min buckets = 24 hours
        _      <- seedReports(rid, start, 288, bytesPerBucket = 10L)
        rollup <- ZIO.service[RollupRepo]
        _      <- rollup.rerollDaily(date.minusDays(1))
        rows   <- rollup.listDailyInRange(List(mac), start, start.plusSeconds(86400))
      } yield assertTrue(
        rows.size == 1,
        rows.head.activeSeconds == 288 * 300,
        rows.head.bytesIn == 288 * 10L,
        rows.head.bytesOut == 288 * 20L,
      )
    },
    test("rerollDaily is idempotent") {
      for {
        _   <- cleanDb
        rid <- seedRouter
        date  = LocalDate.of(2026, 1, 5)
        start = date.atStartOfDay(ZoneOffset.UTC).toInstant
        _      <- seedReports(rid, start, 50)
        rollup <- ZIO.service[RollupRepo]
        _      <- rollup.rerollDaily(date.minusDays(1))
        before <- dailyCount
        _      <- rollup.rerollDaily(date.minusDays(1))
        after  <- dailyCount
      } yield assertTrue(before == 1L, after == before)
    },
    test("recordRun + recentRuns round-trip") {
      for {
        _      <- cleanDb
        rollup <- ZIO.service[RollupRepo]
        // Future timestamps so our manual rows sort ahead of the V38 backfill
        // rows (which use NOW() at migration time).
        t0 = Instant.now().plusSeconds(86400)
        _      <- rollup.recordRun("hourly", t0, t0.plusSeconds(2), "ok", None, 42)
        _      <- rollup.recordRun(
          "daily",
          t0.plusSeconds(60),
          t0.plusSeconds(63),
          "error",
          Some("boom"),
          0,
        )
        recent <- rollup.recentRuns(10)
      } yield assertTrue(
        recent.size >= 2,
        recent.head.job == "daily",
        recent.head.status == "error",
        recent.head.error.contains("boom"),
      )
    },
    test("rerollHourly gates concurrent calls behind pg_try_advisory_xact_lock (#818)") {
      // Hold the rollup advisory lock on a raw JDBC connection (separate from
      // the doobie pool the repo uses), then verify rerollHourly returns None
      // — i.e. it tried, didn't get the lock, and skipped. This is the
      // multi-instance contract: two API processes racing on the same tick,
      // one wins, the other skips without blocking.
      for {
        _   <- cleanDb
        rid <- seedRouter
        start = LocalDate.of(2026, 3, 1).atStartOfDay(ZoneOffset.UTC).toInstant
        _       <- seedReports(rid, start, 6, bytesPerBucket = 25L)
        rollup  <- ZIO.service[RollupRepo]
        db      <- ZIO.service[TestDatabase.TestDb]
        // Hold the session-level lock on a dedicated connection drawn from
        // the *same* per-spec database the repo runs against — pg advisory
        // locks are per-database, so a lock acquired on a different DB
        // (e.g. the `postgres` admin DB) would not be visible to the repo
        // and the test would race-pass (#1188).
        skipped <- ZIO.scoped {
          for {
            conn <- ZIO.acquireRelease(
              ZIO.attempt(db.ds.getConnection),
            )(c => ZIO.attempt(c.close()).ignore)
            _    <- ZIO.attempt {
              val rs = conn
                .createStatement()
                .executeQuery(s"SELECT pg_advisory_lock(${RollupLockKeys.Hourly})")
              rs.close()
            }
            // While the lock is held above, the repo's reroll must skip.
            res  <- rollup.rerollHourly(start.minusSeconds(3600))
          } yield res
        }
        // After the holding scope releases (connection closed → session lock
        // released), a fresh call succeeds.
        ok      <- rollup.rerollHourly(start.minusSeconds(3600))
      } yield assertTrue(skipped.isEmpty, ok.isDefined)
    },
    test("listHourlyInRange filters by mac and window") {
      for {
        _   <- cleanDb
        rid <- seedRouter
        start = LocalDate.of(2026, 2, 1).atStartOfDay(ZoneOffset.UTC).toInstant
        _      <- seedReports(rid, start, 48) // 4 hours of data
        rollup <- ZIO.service[RollupRepo]
        _      <- rollup.rerollHourly(start.minusSeconds(3600))
        // Window covers only the middle 2 hours
        windowFrom = start.plusSeconds(3600)
        windowTo   = start.plusSeconds(3600 * 3)
        rows  <- rollup.listHourlyInRange(List(mac), windowFrom, windowTo)
        // Filter to a non-matching mac returns nothing
        other <- rollup.listHourlyInRange(
          List(MacAddress.unsafe("ff:ff:ff:ff:ff:ff")),
          start,
          start.plusSeconds(86400),
        )
      } yield assertTrue(rows.size == 2, other.isEmpty)
    },
  ) @@ TestAspect.sequential
}
