package wifihaven.api.feature

import wifihaven.api.db.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.api.usage.RetentionSweepJob
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import doobie.Transactor
import doobie.implicits.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.*
import zio.interop.catz.*
import zio.test.*

/**
 * #811: tests for the daily retention sweep — row deletion, advisory-lock multi-instance safety,
 * and graceful no-op for rollup tables (#809) before their migration lands.
 */
object RetentionSweepJobSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Transactor[Task]] {

  override val bootstrap = TestDatabase.layer

  private def cleanDb = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
    TestDatabase.cleanAndMigrate.provide(ZLayer.succeed(pg)),
  )

  private def seedRouter: RIO[RouterRepo, RouterId] =
    ZIO.serviceWithZIO[RouterRepo](_.create("retention-test", Sha256Hex.unsafe("a" * 64)))

  // Insert one traffic_reports row at a configurable `period_start` offset
  // from NOW(). `ageDays` is added with negative sign to push the row into
  // the past.
  private def insertTraffic(
      xa: Transactor[Task],
      routerId: RouterId,
      mac: String,
      ageDays: Int,
  ): Task[Unit] = {
    val days = ageDays
    sql"""INSERT INTO traffic_reports
            (router_id, mac, ip, host_type, host_value, date, period_start, period_end,
             active_seconds, bytes_in, bytes_out)
          VALUES
            ($routerId, $mac, NULL, 'fqdn', 'example.com',
             (NOW() - make_interval(days => $days))::date,
             NOW() - make_interval(days => $days),
             NOW() - make_interval(days => $days) + INTERVAL '5 minutes',
             1, 1, 1)""".update.run.transact(xa).unit
  }

  private def insertEvent(
      xa: Transactor[Task],
      routerId: RouterId,
      mac: String,
      ageDays: Int,
  ): Task[Unit] = {
    val days = ageDays
    sql"""INSERT INTO connection_events
            (router_id, mac, host_type, host_value, dest_ip, allowed, reason, ts)
          VALUES
            ($routerId, $mac, 'fqdn', 'example.com', NULL, true, 'allow',
             NOW() - make_interval(days => $days))""".update.run.transact(xa).unit
  }

  private def countTraffic(xa: Transactor[Task]): Task[Int] =
    sql"SELECT count(*) FROM traffic_reports".query[Int].unique.transact(xa)

  private def countEvents(xa: Transactor[Task]): Task[Int] =
    sql"SELECT count(*) FROM connection_events".query[Int].unique.transact(xa)

  def spec = suite("RetentionSweepJob")(
    test("deletes traffic_reports older than 30d and leaves recent rows intact") {
      for {
        _    <- cleanDb
        xa   <- ZIO.service[Transactor[Task]]
        rid  <- seedRouter
        _    <- insertTraffic(xa, rid, "aa:aa:aa:aa:aa:01", ageDays = 45) // expired
        _    <- insertTraffic(xa, rid, "aa:aa:aa:aa:aa:02", ageDays = 31) // expired
        _    <- insertTraffic(xa, rid, "aa:aa:aa:aa:aa:03", ageDays = 29) // kept
        _    <- insertTraffic(xa, rid, "aa:aa:aa:aa:aa:04", ageDays = 0)  // kept
        res  <- RetentionSweepJob.sweepOnce(xa)
        rows <- countTraffic(xa)
      } yield assertTrue(rows == 2) &&
        assertTrue(res.exists(_.exists(r => r.table == "traffic_reports" && r.rowsDeleted == 2)))
    },
    test("deletes connection_events older than 30d and leaves recent rows intact") {
      for {
        _    <- cleanDb
        xa   <- ZIO.service[Transactor[Task]]
        rid  <- seedRouter
        _    <- insertEvent(xa, rid, "bb:bb:bb:bb:bb:01", ageDays = 60)
        _    <- insertEvent(xa, rid, "bb:bb:bb:bb:bb:02", ageDays = 31)
        _    <- insertEvent(xa, rid, "bb:bb:bb:bb:bb:03", ageDays = 1)
        res  <- RetentionSweepJob.sweepOnce(xa)
        rows <- countEvents(xa)
      } yield assertTrue(rows == 1) &&
        assertTrue(res.exists(_.exists(r => r.table == "connection_events" && r.rowsDeleted == 2)))
    },
    test("gracefully no-ops on rollup tables when they don't exist") {
      // #809's V38 created traffic_hourly / traffic_daily, but the sweep must
      // still tolerate their absence (defense for older deployments mid-rollout
      // and for any future operator-driven DROP). Simulate by dropping both
      // tables, then assert the sweep skips them rather than raising "relation
      // does not exist".
      for {
        _   <- cleanDb
        xa  <- ZIO.service[Transactor[Task]]
        _   <- sql"DROP TABLE traffic_hourly CASCADE".update.run.transact(xa)
        _   <- sql"DROP TABLE traffic_daily CASCADE".update.run.transact(xa)
        res <- RetentionSweepJob.sweepOnce(xa)
      } yield assertTrue(
        res.exists(rs =>
          rs.exists(_.table == "traffic_reports") &&
            rs.exists(_.table == "connection_events") &&
            !rs.exists(_.table == "traffic_hourly") &&
            !rs.exists(_.table == "traffic_daily"),
        ),
      )
    },
    test("deletes traffic_hourly older than 90d and traffic_daily older than 180d") {
      for {
        _    <- cleanDb
        xa   <- ZIO.service[Transactor[Task]]
        rid  <- seedRouter
        // hourly: 100d old expires, 89d old kept
        _    <- sql"""INSERT INTO traffic_hourly
                       (router_id, mac, hostname, bucket_start, active_seconds,
                        bytes_in, bytes_out, sample_count)
                     VALUES
                       ($rid, 'dd:dd:dd:dd:dd:01', 'example.com',
                        NOW() - INTERVAL '100 days', 1, 1, 1, 1),
                       ($rid, 'dd:dd:dd:dd:dd:02', 'example.com',
                        NOW() - INTERVAL '89 days', 1, 1, 1, 1)""".update.run.transact(xa)
        // daily: 200d old expires, 179d old kept (uses `date` column)
        _    <- sql"""INSERT INTO traffic_daily
                       (router_id, mac, hostname, date, active_seconds,
                        bytes_in, bytes_out, sample_count)
                     VALUES
                       ($rid, 'ee:ee:ee:ee:ee:01', 'example.com',
                        (NOW() - INTERVAL '200 days')::date, 1, 1, 1, 1),
                       ($rid, 'ee:ee:ee:ee:ee:02', 'example.com',
                        (NOW() - INTERVAL '179 days')::date, 1, 1, 1, 1)""".update.run.transact(xa)
        res  <- RetentionSweepJob.sweepOnce(xa)
        hRow <- sql"SELECT count(*) FROM traffic_hourly".query[Int].unique.transact(xa)
        dRow <- sql"SELECT count(*) FROM traffic_daily".query[Int].unique.transact(xa)
      } yield assertTrue(hRow == 1) && assertTrue(dRow == 1) &&
        assertTrue(res.exists(_.exists(r => r.table == "traffic_hourly" && r.rowsDeleted == 1))) &&
        assertTrue(res.exists(_.exists(r => r.table == "traffic_daily" && r.rowsDeleted == 1)))
    },
    test("acquires + releases advisory lock cleanly across consecutive ticks") {
      // If the previous tick failed to release the lock, the next tick would
      // come back as `None` (skipped). Verify two ticks in a row both run.
      for {
        _  <- cleanDb
        xa <- ZIO.service[Transactor[Task]]
        r1 <- RetentionSweepJob.sweepOnce(xa)
        r2 <- RetentionSweepJob.sweepOnce(xa)
      } yield assertTrue(r1.isDefined) && assertTrue(r2.isDefined)
    },
    test("second sweep is skipped while another session holds the advisory lock") {
      // Hold the lock on a separate JDBC connection drawn straight from the
      // embedded PG datasource. Coordinated with a Java CountDownLatch so we
      // don't depend on ZIO's TestClock advancing (which would deadlock the
      // pg_sleep approach under ZIOSpec).
      import java.util.concurrent.CountDownLatch
      val key = RetentionSweepJob.AdvisoryLockKey
      for {
        _   <- cleanDb
        xa  <- ZIO.service[Transactor[Task]]
        pg  <- ZIO.service[EmbeddedPostgres]
        rid <- seedRouter
        _   <- insertTraffic(xa, rid, "cc:cc:cc:cc:cc:01", ageDays = 60)
        acquired = new CountDownLatch(1)
        release  = new CountDownLatch(1)
        holder <- ZIO.attemptBlocking {
          val c = pg.getPostgresDatabase.getConnection
          try {
            val s = c.createStatement()
            s.execute(s"SELECT pg_advisory_lock($key)")
            acquired.countDown()
            release.await()
            s.execute(s"SELECT pg_advisory_unlock($key)")
          } finally c.close()
        }.fork
        _      <- ZIO.attemptBlocking(acquired.await())
        res    <- RetentionSweepJob.sweepOnce(xa)
        rows   <- countTraffic(xa)
        _      <- ZIO.succeed(release.countDown())
        _      <- holder.join
      } yield assertTrue(res.isEmpty) && assertTrue(rows == 1)
    },
    test("computes initial delay to next 03:00 UTC tick") {
      import java.time.{ZoneOffset, ZonedDateTime}
      val before  = ZonedDateTime.of(2026, 5, 26, 1, 0, 0, 0, ZoneOffset.UTC)
      val after   = ZonedDateTime.of(2026, 5, 26, 5, 0, 0, 0, ZoneOffset.UTC)
      val atRun   = ZonedDateTime.of(2026, 5, 26, 3, 0, 0, 0, ZoneOffset.UTC)
      val dBefore = RetentionSweepJob.initialDelay(before)
      val dAfter  = RetentionSweepJob.initialDelay(after)
      val dAt     = RetentionSweepJob.initialDelay(atRun)
      assertTrue(dBefore == 2.hours) &&
      assertTrue(dAfter == 22.hours) &&
      // At the run instant itself, todayRun is not *after* now, so we roll to tomorrow.
      assertTrue(dAt == 24.hours)
    },
  ) @@ TestAspect.sequential
}
