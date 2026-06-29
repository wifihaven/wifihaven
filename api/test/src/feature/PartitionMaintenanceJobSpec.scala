package wifihaven.api.feature

import wifihaven.api.db.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.api.usage.PartitionMaintenanceJob
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import doobie.Transactor
import doobie.implicits.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.*
import zio.interop.catz.*
import zio.metrics.*
import zio.test.*

import java.time.Instant

/**
 * #808 / #2053: the in-process job that auto-creates future weekly partitions for the RANGE-
 * partitioned ingest tables (traffic_reports V41, connection_events V42). Verifies it creates the
 * expected weeks ahead with the EXACT V41/V42 naming + Monday-boundary range scheme, is idempotent,
 * lets an insert dated into a provisioned future week succeed, surfaces the runway via the
 * `partition_weeks_ahead{table}` gauge, and is advisory-lock guarded for multi-instance safety.
 *
 * `now` is pinned to a far-future Monday so the partitions the job creates never collide with the
 * current-week partitions V41/V42 seed at migration time — the assertions are deterministic
 * regardless of when the suite runs.
 */
object PartitionMaintenanceJobSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Transactor[Task]] {

  override val bootstrap = TestDatabase.layer

  private val cleanDb = TestDatabase.cleanAndMigrate

  // A fixed reference instant well past any real migration-seed window. 2030-06-12 is a Wednesday;
  // date_trunc('week') resolves it to Monday 2030-06-10 (ISO week 2030-24).
  private val refNow: Instant = Instant.parse("2030-06-12T12:00:00Z")

  private val weeksAhead = 6

  // Expected ISO-week partition names for weeks 0..6 from refNow's Monday (2030-06-10 = 2030_24).
  private val expectedWeeks: List[String] =
    List("2030_24", "2030_25", "2030_26", "2030_27", "2030_28", "2030_29", "2030_30")

  private def partitionExists(xa: Transactor[Task], name: String): Task[Boolean] =
    sql"SELECT to_regclass($name) IS NOT NULL".query[Boolean].unique.transact(xa)

  private def gaugeValue(table: String): UIO[Double] =
    Metric.gauge("partition_weeks_ahead").tagged("table", table).value.map(_.value)

  private def mkRepo(xa: Transactor[Task]): PartitionRepo = PartitionRepoLive(xa)

  def spec = suite("PartitionMaintenanceJob")(
    test("creates the current week + N ahead with V41/V42 naming and Monday boundaries") {
      for {
        _  <- cleanDb
        xa <- ZIO.service[Transactor[Task]]
        repo = mkRepo(xa)
        res      <- repo.ensureFuturePartitions(weeksAhead, refNow)
        // traffic_reports: every expected week now resolves to a real relation.
        trExists <- ZIO.foreach(expectedWeeks)(w => partitionExists(xa, s"traffic_reports_$w"))
        ceExists <- ZIO.foreach(expectedWeeks)(w => partitionExists(xa, s"connection_events_$w"))
        // bounds of the 2030_24 traffic_reports partition: Monday 2030-06-10 → next Monday.
        bounds   <- sql"""SELECT pg_get_expr(c.relpartbound, c.oid)
                          FROM pg_class c WHERE c.relname = 'traffic_reports_2030_24'"""
          .query[String]
          .unique
          .transact(xa)
      } yield assertTrue(res.isDefined) &&
        assertTrue(trExists.forall(identity)) &&
        assertTrue(ceExists.forall(identity)) &&
        assertTrue(bounds.contains("2030-06-10")) &&
        assertTrue(bounds.contains("2030-06-17"))
    },
    test("is idempotent — a second pass creates nothing new") {
      for {
        _  <- cleanDb
        xa <- ZIO.service[Transactor[Task]]
        repo = mkRepo(xa)
        r1 <- repo.ensureFuturePartitions(weeksAhead, refNow)
        r2 <- repo.ensureFuturePartitions(weeksAhead, refNow)
        created1 = r1.toList.flatten.flatMap(_.created)
        created2 = r2.toList.flatten.flatMap(_.created)
      } yield
      // First pass creates the weeks 0..6 for both tables; second pass creates none.
      assertTrue(created1.nonEmpty) && assertTrue(created2.isEmpty)
    },
    test("an insert dated into a provisioned future week succeeds") {
      for {
        _  <- cleanDb
        xa <- ZIO.service[Transactor[Task]]
        repo = mkRepo(xa)
        _   <- repo.ensureFuturePartitions(weeksAhead, refNow)
        rid <- ZIO.serviceWithZIO[RouterRepo](_.create("part-test", Sha256Hex.unsafe("b" * 64)))
        // period_start in week +3 (2030-07-03 falls in ISO week 2030_27, which we provisioned).
        inserted <- sql"""INSERT INTO traffic_reports
                            (router_id, mac, ip, host_type, host_value, date, period_start,
                             period_end, active_seconds, bytes_in, bytes_out)
                          VALUES
                            ($rid, 'aa:aa:aa:aa:aa:aa', NULL, 'fqdn', 'example.com',
                             '2030-07-03', '2030-07-03T10:00:00Z'::timestamptz,
                             '2030-07-03T10:05:00Z'::timestamptz, 1, 1, 1)""".update.run
          .transact(xa)
          .either
      } yield assertTrue(inserted.isRight)
    },
    test("runway gauge reflects the consecutive weeks-ahead per table") {
      for {
        _  <- cleanDb
        xa <- ZIO.service[Transactor[Task]]
        repo = mkRepo(xa)
        _   <- PartitionMaintenanceJob.runOnce(repo, weeksAhead, refNow)
        trG <- gaugeValue("traffic_reports")
        ceG <- gaugeValue("connection_events")
      } yield
      // weeks 0..6 present and consecutive from the current week ⇒ runway 7.
      assertTrue(trG == 7.0) && assertTrue(ceG == 7.0)
    },
    test("second pass is skipped while another session holds the advisory lock") {
      import java.util.concurrent.CountDownLatch
      val key = PartitionRepo.AdvisoryLockKey
      for {
        _  <- cleanDb
        xa <- ZIO.service[Transactor[Task]]
        repo = mkRepo(xa)
        db <- ZIO.service[TestDatabase.TestDb]
        acquired = new CountDownLatch(1)
        release  = new CountDownLatch(1)
        holder <- ZIO.attemptBlocking {
          val c = db.ds.getConnection
          try {
            val s = c.createStatement()
            s.execute(s"SELECT pg_advisory_lock($key)")
            acquired.countDown()
            release.await()
            s.execute(s"SELECT pg_advisory_unlock($key)")
          } finally c.close()
        }.fork
        _      <- ZIO.attemptBlocking(acquired.await())
        res    <- repo.ensureFuturePartitions(weeksAhead, refNow)
        _      <- ZIO.succeed(release.countDown())
        _      <- holder.join
      } yield assertTrue(res.isEmpty)
    },
  ) @@ TestAspect.sequential
}
