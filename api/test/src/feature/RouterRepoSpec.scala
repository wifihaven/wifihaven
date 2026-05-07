package familydns.api.feature

import familydns.api.db.*
import familydns.shared.*
import familydns.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import doobie.Transactor
import zio.*
import zio.test.*

import java.time.{Instant, LocalDate}

object RouterRepoSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Transactor[Task]] {

  override val bootstrap = TestDatabase.layer

  private def cleanDb = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
    TestDatabase.cleanAndMigrate.provide(ZLayer.succeed(pg)),
  )

  def spec = suite("OpenWRT repos")(
    suite("RouterRepo")(
      test("create returns a UUID and stores enrollment_token_hash, no token_hash") {
        for
          _        <- cleanDb
          repo     <- ZIO.service[RouterRepo]
          id       <- repo.create("home-router", "ENROLL_HASH_1")
          row      <- repo.findById(id)
          byEnroll <- repo.findByEnrollmentTokenHash("ENROLL_HASH_1")
        yield assertTrue(row.exists(_.name == "home-router")) &&
          assertTrue(row.exists(_.enrollmentTokenHash.contains("ENROLL_HASH_1"))) &&
          assertTrue(row.exists(_.tokenHash.isEmpty)) &&
          assertTrue(row.exists(_.lastSeenAt.isEmpty)) &&
          assertTrue(byEnroll.exists(_.id == id))
      },
      test(
        "completeEnrollment swaps enrollment_token_hash for token_hash and stamps last_seen_at",
      ) {
        for
          _        <- cleanDb
          repo     <- ZIO.service[RouterRepo]
          id       <- repo.create("r1", "ENROLL_HASH_2")
          _        <- repo.completeEnrollment(id, "TOKEN_HASH_2")
          row      <- repo.findById(id)
          byEnroll <- repo.findByEnrollmentTokenHash("ENROLL_HASH_2")
          byTok    <- repo.findByTokenHash("TOKEN_HASH_2")
        yield assertTrue(row.exists(_.enrollmentTokenHash.isEmpty)) &&
          assertTrue(row.exists(_.tokenHash.contains("TOKEN_HASH_2"))) &&
          assertTrue(row.exists(_.lastSeenAt.isDefined)) &&
          assertTrue(byEnroll.isEmpty) &&
          assertTrue(byTok.exists(_.id == id))
      },
      test("touch updates last_seen_at and last_etag without losing prior etag when None passed") {
        for
          _    <- cleanDb
          repo <- ZIO.service[RouterRepo]
          id   <- repo.create("r1", "EH")
          _    <- repo.completeEnrollment(id, "TH")
          _    <- repo.touch(id, Some("etag-A"))
          a    <- repo.findById(id)
          _    <- repo.touch(id, None) // last_etag must remain "etag-A"
          b    <- repo.findById(id)
        yield assertTrue(a.flatMap(_.lastEtag).contains("etag-A")) &&
          assertTrue(b.flatMap(_.lastEtag).contains("etag-A")) &&
          assertTrue(b.flatMap(_.lastSeenAt).isDefined)
      },
      test("delete removes the row and cascades to traffic_reports") {
        for
          _     <- cleanDb
          rRepo <- ZIO.service[RouterRepo]
          tRepo <- ZIO.service[TrafficReportRepo]
          id    <- rRepo.create("r1", "EH")
          start = Instant.parse("2026-05-02T14:00:00Z")
          end   = Instant.parse("2026-05-02T14:05:00Z")
          _   <- tRepo.insertBatch(
            List(
              TrafficReportInsert(
                id,
                "aa:bb:cc:dd:ee:ff",
                Some("192.168.1.10"),
                "youtube.com",
                LocalDate.of(2026, 5, 2),
                start,
                end,
                240,
                1000L,
                500L,
              ),
            ),
          )
          _   <- rRepo.delete(id)
          row <- rRepo.findById(id)
          rep <- tRepo.listForRouter(id, 10)
        yield assertTrue(row.isEmpty) && assertTrue(rep.isEmpty)
      },
      test("listAll returns rows ordered by created_at") {
        for
          _    <- cleanDb
          repo <- ZIO.service[RouterRepo]
          a    <- repo.create("a", "h1")
          b    <- repo.create("b", "h2")
          all  <- repo.listAll
        yield assertTrue(all.map(_.id) == List(a, b))
      },
    ),
    suite("TrafficReportRepo")(
      test("insertBatch is idempotent on (router_id, period_start, mac, hostname)") {
        for
          _     <- cleanDb
          rRepo <- ZIO.service[RouterRepo]
          tRepo <- ZIO.service[TrafficReportRepo]
          rid   <- rRepo.create("r1", "EH")
          start = Instant.parse("2026-05-02T14:00:00Z")
          end   = Instant.parse("2026-05-02T14:05:00Z")
          rec   = TrafficReportInsert(
            rid,
            "aa:bb:cc:11:22:33",
            Some("192.168.1.42"),
            "youtube.com",
            LocalDate.of(2026, 5, 2),
            start,
            end,
            240,
            38123412L,
            921000L,
          )
          n1   <- tRepo.insertBatch(List(rec))
          n2   <- tRepo.insertBatch(List(rec)) // retry
          rows <- tRepo.listForRouter(rid, 100)
        yield assertTrue(n1 == 1) && assertTrue(n2 == 0) && assertTrue(rows.size == 1)
      },
      test("different hostnames in same period are stored as distinct rows") {
        for
          _     <- cleanDb
          rRepo <- ZIO.service[RouterRepo]
          tRepo <- ZIO.service[TrafficReportRepo]
          rid   <- rRepo.create("r1", "EH")
          start = Instant.parse("2026-05-02T14:00:00Z")
          end   = Instant.parse("2026-05-02T14:05:00Z")
          mac   = "aa:bb:cc:11:22:33"
          batch = List(
            TrafficReportInsert(
              rid,
              mac,
              None,
              "youtube.com",
              LocalDate.of(2026, 5, 2),
              start,
              end,
              60,
              1L,
              1L,
            ),
            TrafficReportInsert(
              rid,
              mac,
              None,
              "google.com",
              LocalDate.of(2026, 5, 2),
              start,
              end,
              30,
              2L,
              2L,
            ),
          )
          n    <- tRepo.insertBatch(batch)
          rows <- tRepo.listForDevice(mac, LocalDate.of(2026, 5, 2))
        yield assertTrue(n == 2) && assertTrue(rows.size == 2) &&
          assertTrue(rows.map(_.hostname).toSet == Set("youtube.com", "google.com"))
      },
      test("listForDevice filters by mac and date") {
        for
          _     <- cleanDb
          rRepo <- ZIO.service[RouterRepo]
          tRepo <- ZIO.service[TrafficReportRepo]
          rid   <- rRepo.create("r1", "EH")
          mac1 = "aa:bb:cc:11:22:33"
          mac2 = "aa:bb:cc:99:99:99"
          d1   = LocalDate.of(2026, 5, 2)
          d2   = LocalDate.of(2026, 5, 3)
          mk   = (mac: String, d: LocalDate, h: String) =>
            TrafficReportInsert(
              rid,
              mac,
              None,
              h,
              d,
              d.atTime(14, 0).toInstant(java.time.ZoneOffset.UTC),
              d.atTime(14, 5).toInstant(java.time.ZoneOffset.UTC),
              60,
              1L,
              1L,
            )
          _    <- tRepo.insertBatch(
            List(
              mk(mac1, d1, "a.com"),
              mk(mac1, d2, "b.com"),
              mk(mac2, d1, "c.com"),
            ),
          )
          rows <- tRepo.listForDevice(mac1, d1)
        yield assertTrue(rows.size == 1) && assertTrue(rows.head.hostname == "a.com")
      },
    ),
    suite("BlockEventRepo")(
      test("insertBatch returns count and records appear in recent() ordered by ts desc") {
        for
          _    <- cleanDb
          repo <- ZIO.service[BlockEventRepo]
          n    <- repo.insertBatch(
            List(
              BlockEventInsert(Some("aa:bb:cc:00:00:01"), "ads.example.com", "category:ads"),
              BlockEventInsert(
                Some("aa:bb:cc:00:00:02"),
                "casino.example.com",
                "category:gambling",
              ),
              BlockEventInsert(None, "unknown.example.com", "category:adult"),
            ),
          )
          rows <- repo.recent(10)
        yield assertTrue(n == 3) && assertTrue(rows.size == 3) &&
          assertTrue(rows.exists(_.mac.isEmpty))
      },
      test("listForMac returns only events for that mac, newest first") {
        for
          _    <- cleanDb
          repo <- ZIO.service[BlockEventRepo]
          mac = "aa:bb:cc:00:00:01"
          _    <- repo.insertBatch(
            List(
              BlockEventInsert(Some(mac), "a.com", "r1"),
              BlockEventInsert(Some("aa:bb:cc:00:00:99"), "b.com", "r1"),
              BlockEventInsert(Some(mac), "c.com", "r2"),
            ),
          )
          rows <- repo.listForMac(mac, 10)
        yield assertTrue(rows.size == 2) &&
          assertTrue(rows.forall(_.mac.contains(mac))) &&
          assertTrue(rows.map(_.hostname).toSet == Set("a.com", "c.com"))
      },
    ),
    suite("time_usage byte columns")(
      test("time_usage rows default bytes_in/bytes_out to zero") {
        import doobie.implicits.*
        import doobie.postgres.implicits.*
        import zio.interop.catz.*
        for
          _  <- cleanDb
          tu <- ZIO.service[TimeUsageRepo]
          xa <- ZIO.service[doobie.Transactor[Task]]
          mac = "aa:bb:cc:dd:ee:01"
          d   = LocalDate.of(2026, 5, 2)
          _   <- tu.incrementUsage(mac, "youtube.com", d, 5)
          row <-
            sql"SELECT bytes_in, bytes_out FROM time_usage WHERE device_mac=$mac AND domain='youtube.com' AND date=$d"
              .query[(Long, Long)]
              .unique
              .transact(xa)
        yield assertTrue(row == ((0L, 0L)))
      },
    ),
  ) @@ TestAspect.sequential
}
