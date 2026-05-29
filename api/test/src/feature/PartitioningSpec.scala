package wifihaven.api.feature

import wifihaven.api.db.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import doobie.Transactor
import doobie.implicits.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.*
import zio.interop.catz.*
import zio.test.*

import java.time.Instant

/**
 * #805 / #806: traffic_reports and connection_events are weekly range-partitioned (RANGE on
 * period_start / ts respectively). This spec pins the post-migration structure (so the partitioning
 * can't silently regress to a flat table), proves existing rows survive the detach-and-reattach,
 * that inserts route across a partition boundary, and that the idempotent upserts still collapse
 * replays under partitioning (the connection_events unique key had to grow to include `ts`).
 */
object PartitioningSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Transactor[Task]] {

  override val bootstrap = TestDatabase.layer

  private def cleanDb = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
    TestDatabase.cleanAndMigrate.provide(ZLayer.succeed(pg)),
  )

  private def seedRouter: RIO[RouterRepo, RouterId] =
    ZIO.serviceWithZIO[RouterRepo](_.create("partition-test", Sha256Hex.unsafe("a" * 64)))

  /** Is `table` a RANGE-partitioned parent? */
  private def isRangePartitioned(xa: Transactor[Task], table: String): Task[Boolean] =
    sql"""SELECT EXISTS (
            SELECT 1 FROM pg_partitioned_table pt
              JOIN pg_class c ON c.oid = pt.partrelid
             WHERE c.relname = $table AND pt.partstrat = 'r'
          )""".query[Boolean].unique.transact(xa)

  /** Number of attached partitions of `table`. */
  private def partitionCount(xa: Transactor[Task], table: String): Task[Int] =
    sql"""SELECT count(*)::int FROM pg_inherits i
            JOIN pg_class p ON p.oid = i.inhparent
           WHERE p.relname = $table""".query[Int].unique.transact(xa)

  /** Distinct partitions (by tableoid) that currently hold rows of `table`. */
  private def distinctPartitionsWithRows(xa: Transactor[Task], table: String): Task[Int] = {
    val frag = doobie.Fragment.const(s"SELECT count(DISTINCT tableoid)::int FROM $table")
    frag.query[Int].unique.transact(xa)
  }

  private def insertTraffic(
      xa: Transactor[Task],
      routerId: RouterId,
      mac: String,
      offset: doobie.Fragment,
  ): Task[Unit] =
    (fr"""INSERT INTO traffic_reports
            (router_id, mac, ip, host_type, host_value, date, period_start, period_end,
             active_seconds, bytes_in, bytes_out)
          VALUES
            ($routerId, $mac, NULL, 'fqdn', 'example.com',
             (NOW() """ ++ offset ++ fr""")::date,
             NOW() """ ++ offset ++ fr""",
             NOW() """ ++ offset ++ fr""" + INTERVAL '5 minutes',
             1, 1, 1)""").update.run.transact(xa).unit

  private def insertEvent(
      xa: Transactor[Task],
      routerId: RouterId,
      mac: String,
      offset: doobie.Fragment,
  ): Task[Unit] =
    (fr"""INSERT INTO connection_events
            (router_id, mac, host_type, host_value, dest_ip, allowed, reason, ts)
          VALUES
            ($routerId, $mac, 'fqdn', 'example.com', NULL, true, '{"kind":"allow"}'::jsonb,
             NOW() """ ++ offset ++ fr""")""").update.run.transact(xa).unit

  private val past   = fr"- INTERVAL '40 days'" // omnibus partition
  private val nowOff = fr"+ INTERVAL '0 days'"  // current-week partition
  private val future = fr"+ INTERVAL '8 days'"  // next-week partition

  def spec = suite("table partitioning (#805 #806)")(
    test("traffic_reports is a RANGE-partitioned parent with seeded weekly partitions") {
      for {
        _    <- cleanDb
        xa   <- ZIO.service[Transactor[Task]]
        part <- isRangePartitioned(xa, "traffic_reports")
        n    <- partitionCount(xa, "traffic_reports")
      } yield assertTrue(part) && assertTrue(n >= 2)
    },
    test("connection_events is a RANGE-partitioned parent with seeded weekly partitions") {
      for {
        _    <- cleanDb
        xa   <- ZIO.service[Transactor[Task]]
        part <- isRangePartitioned(xa, "connection_events")
        n    <- partitionCount(xa, "connection_events")
      } yield assertTrue(part) && assertTrue(n >= 2)
    },
    test("traffic_reports rows survive and route across a partition boundary") {
      for {
        _      <- cleanDb
        xa     <- ZIO.service[Transactor[Task]]
        rid    <- seedRouter
        _      <- insertTraffic(xa, rid, "aa:aa:aa:aa:aa:01", past)
        _      <- insertTraffic(xa, rid, "aa:aa:aa:aa:aa:02", nowOff)
        _      <- insertTraffic(xa, rid, "aa:aa:aa:aa:aa:03", future)
        total  <- sql"SELECT count(*)::int FROM traffic_reports".query[Int].unique.transact(xa)
        spread <- distinctPartitionsWithRows(xa, "traffic_reports")
      } yield assertTrue(total == 3) && assertTrue(spread >= 3)
    },
    test("connection_events rows survive and route across a partition boundary") {
      for {
        _      <- cleanDb
        xa     <- ZIO.service[Transactor[Task]]
        rid    <- seedRouter
        _      <- insertEvent(xa, rid, "bb:bb:bb:bb:bb:01", past)
        _      <- insertEvent(xa, rid, "bb:bb:bb:bb:bb:02", nowOff)
        _      <- insertEvent(xa, rid, "bb:bb:bb:bb:bb:03", future)
        total  <- sql"SELECT count(*)::int FROM connection_events".query[Int].unique.transact(xa)
        spread <- distinctPartitionsWithRows(xa, "connection_events")
      } yield assertTrue(total == 3) && assertTrue(spread >= 3)
    },
    test("traffic_reports upsert stays idempotent on its business key under partitioning") {
      for {
        _     <- cleanDb
        rid   <- seedRouter
        tRepo <- ZIO.service[TrafficReportRepo]
        xa    <- ZIO.service[Transactor[Task]]
        ps  = Instant.now()
        row = TrafficReportInsert(
          rid,
          MacAddress.unsafe("cc:cc:cc:cc:cc:01"),
          None,
          HostId.Fqdn(Hostname.unsafe("youtube.com")),
          java.time.LocalDate.now(),
          ps,
          ps.plusSeconds(300),
          240,
          1000L,
          500L,
        )
        first  <- tRepo.insertBatch(List(row))
        second <- tRepo.insertBatch(List(row))
        total  <- sql"SELECT count(*)::int FROM traffic_reports".query[Int].unique.transact(xa)
      } yield assertTrue(first == 1) && assertTrue(second == 0) && assertTrue(total == 1)
    },
    test("connection_events upsert dedups replays on (event_id, ts) under partitioning") {
      for {
        _     <- cleanDb
        rid   <- seedRouter
        cRepo <- ZIO.service[ConnectionEventRepo]
        xa    <- ZIO.service[Transactor[Task]]
        ts  = Instant.now()
        eid = java.util.UUID.randomUUID()
        ev  = ConnectionEventInsert(
          rid,
          Some(MacAddress.unsafe("dd:dd:dd:dd:dd:01")),
          HostId.Fqdn(Hostname.unsafe("youtube.com")),
          None,
          true,
          BlockReason.fromWire("allow"),
          ts,
          Some(eid),
        )
        first  <- cRepo.insertBatch(List(ev))
        second <- cRepo.insertBatch(List(ev))
        total  <- sql"SELECT count(*)::int FROM connection_events".query[Int].unique.transact(xa)
      } yield assertTrue(first == 1) && assertTrue(second == 0) && assertTrue(total == 1)
    },
  ) @@ TestAspect.sequential
}
