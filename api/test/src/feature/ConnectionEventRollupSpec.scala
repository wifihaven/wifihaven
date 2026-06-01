package wifihaven.api.feature

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import wifihaven.api.db.*
import wifihaven.api.policy.PolicyService
import wifihaven.api.usage.RollupJobs
import wifihaven.shared.*
import wifihaven.shared.Clock
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import zio.*
import zio.interop.catz.*
import zio.test.*

import java.time.{Instant, LocalDate, ZoneOffset}

// #1265: connection-event rollup tier — V47 tables + reroll behavior, mirroring
// RollupRepoSpec for the traffic tier.
//
// Coverage:
//   - V47 creates connection_events_hourly / _daily with the expected PKs +
//     bucket indexes.
//   - rerollConnEventsHourly / Daily aggregate connection_events into split
//     count_succeeded / count_blocked correctly.
//   - Re-running either is a no-op (UPSERT idempotency); late events update
//     counts in place.
//   - NULL-mac events fold into the '' bucket (no rows dropped).
//   - Advisory lock gates concurrent reroll (multi-instance skip).
//   - RollupJobs.runOnce records a connection_events_* row in rollup_runs.
object ConnectionEventRollupSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Transactor[Task]] {

  override val bootstrap = TestDatabase.layer

  private val cleanDb = TestDatabase.cleanAndMigrate

  private def seedRouter: RIO[RouterRepo, RouterId] =
    ZIO.serviceWithZIO[RouterRepo](_.create("ce-rollup-test", PolicyService.hashToken("et_dummy")))

  private val mac = MacAddress.unsafe("aa:bb:cc:dd:ee:01")

  // Insert one connection_event at `ts` for the given host/allowed.
  private def event(
      routerId: RouterId,
      hostname: String,
      allowed: Boolean,
      ts: Instant,
      macOpt: Option[MacAddress] = Some(mac),
  ): ConnectionEventInsert =
    ConnectionEventInsert(
      routerId,
      macOpt,
      HostId.Fqdn(Hostname.unsafe(hostname)),
      None,
      allowed,
      BlockReason.fromWire(if (allowed) "allowed" else "category:adult"),
      ts,
    )

  private def hourlyCount: RIO[Transactor[Task], Long] =
    ZIO.serviceWithZIO[Transactor[Task]](xa =>
      sql"SELECT COUNT(*) FROM connection_events_hourly".query[Long].unique.transact(xa),
    )
  private def dailyCount: RIO[Transactor[Task], Long]  =
    ZIO.serviceWithZIO[Transactor[Task]](xa =>
      sql"SELECT COUNT(*) FROM connection_events_daily".query[Long].unique.transact(xa),
    )

  // Read a single hourly bucket's counts back.
  private def hourlyCounts(
      bucketStart: Instant,
  ): RIO[Transactor[Task], Option[(Int, Int, Int)]] =
    ZIO.serviceWithZIO[Transactor[Task]](xa =>
      sql"""SELECT count_succeeded, count_blocked, sample_count
            FROM connection_events_hourly WHERE bucket_start = $bucketStart"""
        .query[(Int, Int, Int)]
        .option
        .transact(xa),
    )

  def spec = suite("ConnectionEventRollup + V47 migration")(
    test("V47 creates connection_events_hourly + _daily with PKs and bucket indexes") {
      for {
        _          <- cleanDb
        xa         <- ZIO.service[Transactor[Task]]
        tableNames <-
          sql"""SELECT table_name FROM information_schema.tables
                WHERE table_schema='public'
                  AND table_name IN ('connection_events_hourly','connection_events_daily')
                ORDER BY table_name"""
            .query[String]
            .to[List]
            .transact(xa)
        hourlyIdx  <-
          sql"""SELECT indexname FROM pg_indexes
                WHERE tablename='connection_events_hourly'
                  AND indexname IN ('idx_ce_hourly_bucket_start','idx_ce_hourly_mac_bucket')"""
            .query[String]
            .to[List]
            .transact(xa)
        dailyIdx   <-
          sql"""SELECT indexname FROM pg_indexes
                WHERE tablename='connection_events_daily'
                  AND indexname IN ('idx_ce_daily_date','idx_ce_daily_mac_date')"""
            .query[String]
            .to[List]
            .transact(xa)
        hourlyPk   <-
          sql"""SELECT pg_get_constraintdef(c.oid)
                FROM pg_constraint c
                WHERE c.conrelid='connection_events_hourly'::regclass AND c.contype='p'"""
            .query[String]
            .unique
            .transact(xa)
      } yield assertTrue(
        tableNames == List("connection_events_daily", "connection_events_hourly"),
        hourlyIdx.size == 2,
        dailyIdx.size == 2,
        hourlyPk.contains("router_id") && hourlyPk.contains("mac") &&
          hourlyPk.contains("hostname") && hourlyPk.contains("bucket_start"),
      )
    },
    test("rerollConnEventsHourly aggregates events into one bucket with split allowed/blocked") {
      for {
        _   <- cleanDb
        rid <- seedRouter
        // bucket = 2026-01-01 05:00 UTC; 3 allowed + 2 blocked
        bucket = LocalDate
          .of(2026, 1, 1)
          .atStartOfDay(ZoneOffset.UTC)
          .toInstant
          .plusSeconds(5 * 3600)
        connRepo <- ZIO.service[ConnectionEventRepo]
        _        <- connRepo.insertBatch(
          List(
            event(rid, "example.com", allowed = true, bucket.plusSeconds(60)),
            event(rid, "example.com", allowed = true, bucket.plusSeconds(120)),
            event(rid, "example.com", allowed = true, bucket.plusSeconds(180)),
            event(rid, "example.com", allowed = false, bucket.plusSeconds(240)),
            event(rid, "example.com", allowed = false, bucket.plusSeconds(300)),
          ),
        )
        rolled   <- connRepo.rerollConnEventsHourly(bucket.minusSeconds(3600)).map(_.getOrElse(0))
        counts   <- hourlyCounts(bucket)
      } yield assertTrue(
        rolled > 0,
        counts.contains((3, 2, 5)),
      )
    },
    test("rerollConnEventsHourly is idempotent and updates counts when late events land") {
      for {
        _   <- cleanDb
        rid <- seedRouter
        bucket = LocalDate.of(2026, 1, 2).atStartOfDay(ZoneOffset.UTC).toInstant.plusSeconds(3600)
        connRepo <- ZIO.service[ConnectionEventRepo]
        _        <- connRepo.insertBatch(
          List(
            event(rid, "a.com", allowed = true, bucket.plusSeconds(10)),
            event(rid, "a.com", allowed = false, bucket.plusSeconds(20)),
          ),
        )
        since = bucket.minusSeconds(3600)
        _     <- connRepo.rerollConnEventsHourly(since)
        first <- hourlyCounts(bucket)
        n1    <- hourlyCount
        // Re-roll with no new data: counts unchanged, no extra rows.
        _     <- connRepo.rerollConnEventsHourly(since)
        n2    <- hourlyCount
        // A late event in the same bucket bumps the recomputed count in place.
        _ <- connRepo.insertBatch(List(event(rid, "a.com", allowed = true, bucket.plusSeconds(30))))
        _ <- connRepo.rerollConnEventsHourly(since)
        after <- hourlyCounts(bucket)
        n3    <- hourlyCount
      } yield assertTrue(
        first.contains((1, 1, 2)),
        n1 == 1L,
        n2 == 1L,
        after.contains((2, 1, 3)),
        n3 == 1L,
      )
    },
    test("rerollConnEventsDaily aggregates a day's events into one daily row") {
      for {
        _   <- cleanDb
        rid <- seedRouter
        date  = LocalDate.of(2026, 1, 3)
        start = date.atStartOfDay(ZoneOffset.UTC).toInstant
        connRepo <- ZIO.service[ConnectionEventRepo]
        _        <- connRepo.insertBatch(
          List(
            event(rid, "d.com", allowed = true, start.plusSeconds(3600)),
            event(rid, "d.com", allowed = true, start.plusSeconds(7200)),
            event(rid, "d.com", allowed = false, start.plusSeconds(10800)),
          ),
        )
        _        <- connRepo.rerollConnEventsDaily(date.minusDays(1))
        xa       <- ZIO.service[Transactor[Task]]
        row      <- sql"""SELECT count_succeeded, count_blocked, sample_count
                      FROM connection_events_daily WHERE date = $date"""
          .query[(Int, Int, Int)]
          .option
          .transact(xa)
        cnt      <- dailyCount
      } yield assertTrue(row.contains((2, 1, 3)), cnt == 1L)
    },
    test("NULL-mac events fold into the '' bucket rather than being dropped") {
      for {
        _   <- cleanDb
        rid <- seedRouter
        bucket = LocalDate
          .of(2026, 1, 4)
          .atStartOfDay(ZoneOffset.UTC)
          .toInstant
          .plusSeconds(2 * 3600)
        connRepo <- ZIO.service[ConnectionEventRepo]
        _        <- connRepo.insertBatch(
          List(
            event(rid, "n.com", allowed = true, bucket.plusSeconds(5), macOpt = None),
            event(rid, "n.com", allowed = false, bucket.plusSeconds(6), macOpt = None),
          ),
        )
        _        <- connRepo.rerollConnEventsHourly(bucket.minusSeconds(3600))
        xa       <- ZIO.service[Transactor[Task]]
        row      <- sql"""SELECT mac, count_succeeded, count_blocked
                     FROM connection_events_hourly WHERE bucket_start = $bucket"""
          .query[(String, Int, Int)]
          .option
          .transact(xa)
      } yield assertTrue(row.contains(("", 1, 1)))
    },
    test("rerollConnEventsHourly skips when another session holds the advisory lock (#818)") {
      for {
        _   <- cleanDb
        rid <- seedRouter
        bucket = LocalDate.of(2026, 3, 1).atStartOfDay(ZoneOffset.UTC).toInstant
        connRepo <- ZIO.service[ConnectionEventRepo]
        _ <- connRepo.insertBatch(List(event(rid, "x.com", allowed = true, bucket.plusSeconds(60))))
        db      <- ZIO.service[TestDatabase.TestDb]
        skipped <- ZIO.scoped {
          for {
            conn <- ZIO.acquireRelease(ZIO.attempt(db.ds.getConnection))(c =>
              ZIO.attempt(c.close()).ignore,
            )
            _    <- ZIO.attempt {
              val rs = conn
                .createStatement()
                .executeQuery(s"SELECT pg_advisory_lock(${RollupLockKeys.ConnEventsHourly})")
              rs.close()
            }
            res  <- connRepo.rerollConnEventsHourly(bucket.minusSeconds(3600))
          } yield res
        }
      } yield assertTrue(skipped.isEmpty)
    },
    test("RollupJobs.runOnce records a connection_events_hourly run in rollup_runs") {
      for {
        _   <- cleanDb
        rid <- seedRouter
        bucket = LocalDate.of(2026, 1, 6).atStartOfDay(ZoneOffset.UTC).toInstant
        connRepo   <- ZIO.service[ConnectionEventRepo]
        rollupRepo <- ZIO.service[RollupRepo]
        _ <- connRepo.insertBatch(List(event(rid, "r.com", allowed = true, bucket.plusSeconds(60))))
        clock <- Clock.TestClock
          .makeWithControl(java.time.LocalDateTime.of(2026, 1, 6, 12, 0))
          .map(_._1)
        _     <- RollupJobs.runOnce(
          RollupJobs.ConnEventsHourlyJob,
          rollupRepo,
          clock,
          _ => connRepo.rerollConnEventsHourly(bucket.minusSeconds(3600)),
        )
        runs  <- rollupRepo.recentRuns(10)
      } yield assertTrue(
        runs.exists(r => r.job == RollupJobs.ConnEventsHourlyJob && r.status == "ok"),
      )
    },
    // #1265 (PR3): the rollup is the source for the DEFAULT /series view, which
    // excludes IPv4/IPv6 multicast + broadcast (#969). Since the rollup carries
    // no host_type/host_value, the read path can't re-filter, so the reroll must
    // exclude multicast at write time — keeping the rollup == default-view set.
    test("rerollConnEventsHourly/Daily exclude multicast + broadcast destinations") {
      for {
        _   <- cleanDb
        rid <- seedRouter
        bucket = LocalDate.of(2026, 2, 1).atStartOfDay(ZoneOffset.UTC).toInstant.plusSeconds(3600)
        connRepo <- ZIO.service[ConnectionEventRepo]
        mcast = (ip: String) =>
          ConnectionEventInsert(
            rid,
            None,
            HostId.IPv4(IpAddress.unsafe(ip)),
            None,
            true,
            BlockReason.fromWire("allowed"),
            bucket.plusSeconds(30),
          )
        _           <- connRepo.insertBatch(
          List(
            event(rid, "keep.com", allowed = true, bucket.plusSeconds(10)),
            mcast("239.255.255.250"), // SSDP multicast (224.0.0.0/4)
            mcast("255.255.255.255"), // IPv4 broadcast
            ConnectionEventInsert(
              rid,
              None,
              HostId.IPv6(IpAddress.unsafe("ff02::fb")),
              None,
              true,
              BlockReason.fromWire("allowed"),
              bucket.plusSeconds(40),
            ),                        // IPv6 multicast (ff00::/8)
          ),
        )
        _           <- connRepo.rerollConnEventsHourly(bucket.minusSeconds(3600))
        _           <- connRepo.rerollConnEventsDaily(LocalDate.of(2026, 2, 1).minusDays(1))
        xa          <- ZIO.service[Transactor[Task]]
        hourlyHosts <-
          sql"SELECT hostname FROM connection_events_hourly ORDER BY hostname"
            .query[String]
            .to[List]
            .transact(xa)
        dailyHosts  <-
          sql"SELECT hostname FROM connection_events_daily ORDER BY hostname"
            .query[String]
            .to[List]
            .transact(xa)
      } yield assertTrue(
        hourlyHosts == List("keep.com"),
        dailyHosts == List("keep.com"),
      )
    },
  ) @@ TestAspect.sequential
}
