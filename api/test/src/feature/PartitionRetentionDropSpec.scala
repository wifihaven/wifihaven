package wifihaven.api.feature

import wifihaven.api.db.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.*
import zio.interop.catz.*
import zio.test.*

import java.time.{Instant, LocalDate}
import java.time.temporal.IsoFields

/**
 * #812: the retention-drop half of partition maintenance. `ensureFuturePartitions` (#808) only ever
 * creates CURRENT-or-future weekly partitions, so these tests create past-week test partitions
 * directly (mirroring the exact V41/V42 `<table>_<IYYY_IW>` naming + Monday-boundary scheme) rather
 * than relying on that job, then verify `dropExpiredPartitions` DETACHes + DROPs only the ones that
 * have aged past their table's retention window.
 *
 * `now` is pinned to a far-future Monday (matching `PartitionMaintenanceJobSpec`'s convention) so
 * these assertions are deterministic regardless of when the suite runs.
 */
object PartitionRetentionDropSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Transactor[Task]] {

  override val bootstrap = TestDatabase.layer

  private val cleanDb = TestDatabase.cleanAndMigrate

  // 2030-06-12 is a Wednesday; date_trunc('week') resolves it to Monday 2030-06-10 (ISO week
  // 2030_24) — same reference instant as PartitionMaintenanceJobSpec.
  private val refNow: Instant = Instant.parse("2030-06-12T12:00:00Z")
  private val currentMonday   = LocalDate.of(2030, 6, 10)

  private def mkRepo(xa: Transactor[Task]): PartitionRepo = PartitionRepoLive(xa)

  private def isoWeekLabel(monday: LocalDate): String = {
    val year = monday.get(IsoFields.WEEK_BASED_YEAR)
    val week = monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
    f"$year%04d_$week%02d"
  }

  private def partitionName(table: String, monday: LocalDate): String =
    s"${table}_${isoWeekLabel(monday)}"

  // Create a real weekly partition of `table` for [monday, monday+7), matching the V41/V42 scheme
  // byte-for-byte, so dropExpiredForTable's to_regclass probe finds it under the same name it would
  // compute in production.
  private def createTestPartition(
      xa: Transactor[Task],
      table: String,
      monday: LocalDate,
  ): Task[String] = {
    val name = partitionName(table, monday)
    val hi   = monday.plusDays(7)
    Fragment
      .const(s"CREATE TABLE $name PARTITION OF $table FOR VALUES FROM ('$monday') TO ('$hi')")
      .update
      .run
      .transact(xa)
      .as(name)
  }

  private def partitionExists(xa: Transactor[Task], name: String): Task[Boolean] =
    sql"SELECT to_regclass($name) IS NOT NULL".query[Boolean].unique.transact(xa)

  private def insertTrafficRow(xa: Transactor[Task], rid: RouterId, day: LocalDate): Task[Unit] =
    sql"""INSERT INTO traffic_reports
            (router_id, mac, ip, host_type, host_value, date, period_start, period_end,
             active_seconds, bytes_in, bytes_out)
          VALUES
            ($rid, 'aa:aa:aa:aa:aa:aa', NULL, 'fqdn', 'example.com', $day,
             ${day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant},
             ${day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant.plusSeconds(300)},
             1, 1, 1)""".update.run.transact(xa).unit

  private def insertEventRow(xa: Transactor[Task], rid: RouterId, day: LocalDate): Task[Unit] =
    sql"""INSERT INTO connection_events
            (router_id, mac, host_type, host_value, dest_ip, allowed, reason, ts)
          VALUES
            ($rid, 'bb:bb:bb:bb:bb:bb', 'fqdn', 'example.com', NULL, true,
             '{"kind":"allow"}'::jsonb, ${day
        .atStartOfDay(java.time.ZoneOffset.UTC)
        .toInstant})""".update.run
      .transact(xa)
      .unit

  private def seedRouter: RIO[RouterRepo, RouterId] =
    ZIO.serviceWithZIO[RouterRepo](_.create("retention-drop-test", Sha256Hex.unsafe("c" * 64)))

  def spec = suite("PartitionRepo.dropExpiredPartitions")(
    test("drops only weekly partitions fully past the retention window, keeps the rest") {
      for {
        _  <- cleanDb
        xa <- ZIO.service[Transactor[Task]]
        repo = mkRepo(xa)
        rid <- seedRouter
        // Weeks -4..0 relative to the current week (2030_24). With retentionDays=7 the cutoff
        // Monday is 2030-06-03 (2030_23), so weeks strictly before it (-4..-2, i.e. hi <= cutoff)
        // are expired; -1 (2030_23 itself) and 0 (current, 2030_24) are kept.
        mondays = (-4 to 0).map(currentMonday.plusWeeks(_))
        _   <- ZIO.foreach(mondays.toList)(m => createTestPartition(xa, "traffic_reports", m))
        _   <- ZIO.foreach(mondays.toList)(m => insertTrafficRow(xa, rid, m.plusDays(1)))
        _   <- ZIO.foreach(mondays.toList)(m => createTestPartition(xa, "connection_events", m))
        _   <- ZIO.foreach(mondays.toList)(m => insertEventRow(xa, rid, m.plusDays(1)))
        res <- repo.dropExpiredPartitions(
          refNow,
          Map("traffic_reports" -> 7, "connection_events" -> 7),
        )
        expiredMondays = mondays.take(3) // -4, -3, -2
        keptMondays = mondays.drop(3) // -1 (2030_23), 0 (2030_24, current)
        expiredGone <- ZIO.foreach(expiredMondays.toList)(m =>
          partitionExists(xa, partitionName("traffic_reports", m)).map(!_),
        )
        keptStill   <- ZIO.foreach(keptMondays.toList)(m =>
          partitionExists(xa, partitionName("traffic_reports", m)),
        )
        // The embedded-PG test DB also carries V41/V42's migration-time-seeded runway partitions
        // (anchored to the real CURRENT_DATE the migration ran at, unrelated to `refNow`) — those
        // are legitimately expired too under a 7-day retention measured from 2030, so the dropped
        // list is a SUPERSET of just our explicit test weeks, not an exact match.
        trDropped    = res.get.find(_.table == "traffic_reports").map(_.dropped).getOrElse(Nil)
        expiredNames = expiredMondays.map(partitionName("traffic_reports", _)).toSet
      } yield assertTrue(res.isDefined) &&
        assertTrue(expiredGone.forall(identity)) &&
        assertTrue(keptStill.forall(identity)) &&
        assertTrue(expiredNames.subsetOf(trDropped.map(_.name).toSet)) &&
        assertTrue(trDropped.filter(d => expiredNames.contains(d.name)).forall(_.rows == 1L))
    },
    test("never drops the partition containing `now`, even with a 0-day retention window") {
      for {
        _  <- cleanDb
        xa <- ZIO.service[Transactor[Task]]
        repo = mkRepo(xa)
        _          <- createTestPartition(xa, "traffic_reports", currentMonday)
        res        <- repo.dropExpiredPartitions(refNow, Map("traffic_reports" -> 0))
        stillThere <- partitionExists(xa, partitionName("traffic_reports", currentMonday))
      } yield assertTrue(res.isDefined) &&
        assertTrue(stillThere) &&
        assertTrue(res.get.find(_.table == "traffic_reports").exists(_.dropped.isEmpty)) &&
        assertTrue(res.get.find(_.table == "traffic_reports").exists(_.skippedReason.isDefined))
    },
    test("refuses a negative retention window the same way, without dropping anything") {
      for {
        _  <- cleanDb
        xa <- ZIO.service[Transactor[Task]]
        repo = mkRepo(xa)
        old  = currentMonday.minusWeeks(10)
        _          <- createTestPartition(xa, "traffic_reports", old)
        res        <- repo.dropExpiredPartitions(refNow, Map("traffic_reports" -> -1))
        stillThere <- partitionExists(xa, partitionName("traffic_reports", old))
      } yield assertTrue(stillThere) &&
        assertTrue(res.get.find(_.table == "traffic_reports").exists(_.skippedReason.isDefined))
    },
    test("is idempotent — a second pass with nothing new to drop is a safe no-op") {
      for {
        _  <- cleanDb
        xa <- ZIO.service[Transactor[Task]]
        repo = mkRepo(xa)
        old  = currentMonday.minusWeeks(5)
        _  <- createTestPartition(xa, "traffic_reports", old)
        r1 <- repo.dropExpiredPartitions(refNow, Map("traffic_reports" -> 7))
        r2 <- repo.dropExpiredPartitions(refNow, Map("traffic_reports" -> 7))
      } yield assertTrue(r1.get.find(_.table == "traffic_reports").exists(_.dropped.nonEmpty)) &&
        assertTrue(r2.get.find(_.table == "traffic_reports").exists(_.dropped.isEmpty))
    },
    test("never touches the _pre_partition historical omnibus partition") {
      for {
        _  <- cleanDb
        xa <- ZIO.service[Transactor[Task]]
        repo = mkRepo(xa)
        // A valid (non-guarded) window so the drop pass actually runs its probe, rather than
        // short-circuiting on the out-of-bounds guard.
        _      <- repo.dropExpiredPartitions(
          refNow,
          Map("traffic_reports" -> 1, "connection_events" -> 1),
        )
        exists <- partitionExists(xa, "traffic_reports_pre_partition")
      } yield assertTrue(exists)
    },
    test("second pass is skipped while another session holds the retention-drop advisory lock") {
      import java.util.concurrent.CountDownLatch
      val key = PartitionRepo.RetentionDropAdvisoryLockKey
      for {
        _  <- cleanDb
        xa <- ZIO.service[Transactor[Task]]
        repo = mkRepo(xa)
        db <- ZIO.service[TestDatabase.TestDb]
        old = currentMonday.minusWeeks(5)
        _ <- createTestPartition(xa, "traffic_reports", old)
        acquired = new CountDownLatch(1)
        release  = new CountDownLatch(1)
        holder     <- ZIO.attemptBlocking {
          val c = db.ds.getConnection
          try {
            val s = c.createStatement()
            s.execute(s"SELECT pg_advisory_lock($key)")
            acquired.countDown()
            release.await()
            s.execute(s"SELECT pg_advisory_unlock($key)")
          } finally c.close()
        }.fork
        _          <- ZIO.attemptBlocking(acquired.await())
        res        <- repo.dropExpiredPartitions(refNow, Map("traffic_reports" -> 7))
        _          <- ZIO.succeed(release.countDown())
        _          <- holder.join
        stillThere <- partitionExists(xa, partitionName("traffic_reports", old))
      } yield assertTrue(res.isEmpty) && assertTrue(stillThere)
    },
  ) @@ TestAspect.sequential
}
