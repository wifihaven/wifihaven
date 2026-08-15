package wifihaven.api.feature

import wifihaven.api.db.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import doobie.*
import doobie.implicits.*
import zio.*
import zio.interop.catz.*
import zio.test.*

import java.time.{Instant, LocalDate}

object RouterRepoSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Transactor[Task]] {

  override val bootstrap = TestDatabase.layer

  private val cleanDb = TestDatabase.cleanAndMigrate

  def spec = suite("OpenWRT repos")(
    suite("RouterRepo")(
      test("create returns a UUID and stores enrollment_token_hash, no token_hash") {
        for {
          _        <- cleanDb
          repo     <- ZIO.service[RouterRepo]
          id       <- repo.create("home-router", Sha256Hex.unsafe("a" * 64))
          row      <- repo.findById(id)
          byEnroll <- repo.findByEnrollmentTokenHash(Sha256Hex.unsafe("a" * 64))
        } yield assertTrue(row.exists(_.name == "home-router")) &&
          assertTrue(row.exists(_.enrollmentTokenHash.contains(Sha256Hex.unsafe("a" * 64)))) &&
          assertTrue(row.exists(_.tokenHash.isEmpty)) &&
          assertTrue(row.exists(_.lastSeenAt.isEmpty)) &&
          assertTrue(byEnroll.exists(_.id == id))
      },
      // #2083: enrollment tokens were correctly single-use but never expired. `create`
      // stamps a TTL; `findByEnrollmentTokenHash` must refuse a token past it even
      // though the hash itself is only cleared on first successful use.
      test("create stamps enrollment_expires_at in the future") {
        for {
          _    <- cleanDb
          repo <- ZIO.service[RouterRepo]
          id   <- repo.create("home-router", Sha256Hex.unsafe("a" * 64))
          row  <- repo.findById(id)
        } yield assertTrue(row.flatMap(_.enrollmentExpiresAt).isDefined)
      },
      test("findByEnrollmentTokenHash refuses a token past its TTL") {
        for {
          _        <- cleanDb
          repo     <- ZIO.service[RouterRepo]
          xa       <- ZIO.service[Transactor[Task]]
          id       <- repo.create("home-router", Sha256Hex.unsafe("b" * 64))
          // Simulate TTL elapse: back-date the stamped expiry into the past.
          _        <-
            sql"UPDATE routers SET enrollment_expires_at = NOW() - INTERVAL '1 minute' WHERE id=$id".update.run
              .transact(xa)
          byEnroll <- repo.findByEnrollmentTokenHash(Sha256Hex.unsafe("b" * 64))
          byId     <- repo.findById(id)
        } yield assertTrue(byEnroll.isEmpty) &&
          // The row (and its hash) still exists — expiry doesn't retroactively
          // clear the hash, it just stops the lookup from matching it.
          assertTrue(byId.exists(_.enrollmentTokenHash.isDefined))
      },
      test("findByEnrollmentTokenHash still matches a token within its TTL") {
        for {
          _        <- cleanDb
          repo     <- ZIO.service[RouterRepo]
          id       <- repo.create("home-router", Sha256Hex.unsafe("c" * 64))
          byEnroll <- repo.findByEnrollmentTokenHash(Sha256Hex.unsafe("c" * 64))
        } yield assertTrue(byEnroll.exists(_.id == id))
      },
      test(
        "completeEnrollment swaps enrollment_token_hash for token_hash and stamps last_seen_at",
      ) {
        for {
          _        <- cleanDb
          repo     <- ZIO.service[RouterRepo]
          id       <- repo.create("r1", Sha256Hex.unsafe("c" * 64))
          _        <- repo.completeEnrollment(id, Sha256Hex.unsafe("d" * 64))
          row      <- repo.findById(id)
          byEnroll <- repo.findByEnrollmentTokenHash(Sha256Hex.unsafe("c" * 64))
          byTok    <- repo.findByTokenHash(Sha256Hex.unsafe("d" * 64))
        } yield assertTrue(row.exists(_.enrollmentTokenHash.isEmpty)) &&
          assertTrue(row.exists(_.tokenHash.contains(Sha256Hex.unsafe("d" * 64)))) &&
          assertTrue(row.exists(_.lastSeenAt.isDefined)) &&
          assertTrue(byEnroll.isEmpty) &&
          assertTrue(byTok.exists(_.id == id))
      },
      test("touch updates last_seen_at and last_etag without losing prior etag when None passed") {
        for {
          _    <- cleanDb
          repo <- ZIO.service[RouterRepo]
          id   <- repo.create("r1", Sha256Hex.unsafe("e" * 64))
          _    <- repo.completeEnrollment(id, Sha256Hex.unsafe("f" * 64))
          _    <- repo.touch(id, Some(ETag.unsafe("\"etag-A\"")), None)
          a    <- repo.findById(id)
          _    <- repo.touch(id, None, None) // last_etag must remain "etag-A"
          b    <- repo.findById(id)
        } yield assertTrue(a.flatMap(_.lastEtag).contains(ETag.unsafe("\"etag-A\""))) &&
          assertTrue(b.flatMap(_.lastEtag).contains(ETag.unsafe("\"etag-A\""))) &&
          assertTrue(b.flatMap(_.lastSeenAt).isDefined)
      },
      test("delete removes the row and cascades to traffic_reports") {
        for {
          _     <- cleanDb
          rRepo <- ZIO.service[RouterRepo]
          tRepo <- ZIO.service[TrafficReportRepo]
          id    <- rRepo.create("r1", Sha256Hex.unsafe("g" * 64))
          start = Instant.parse("2026-05-02T14:00:00Z")
          end   = Instant.parse("2026-05-02T14:05:00Z")
          _   <- tRepo.insertBatch(
            List(
              TrafficReportInsert(
                id,
                MacAddress.unsafe("aa:bb:cc:dd:ee:ff"),
                Some(IpAddress.unsafe("192.168.1.10")),
                HostId.Fqdn(Hostname.unsafe("youtube.com")),
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
        } yield assertTrue(row.isEmpty) && assertTrue(rep.isEmpty)
      },
      // #2571: the unscoped `listAll` this once covered is deleted. This test keeps the created_at
      // ordering pinned, now through the household-scoped twin.
      test("listAllForHousehold returns rows ordered by created_at") {
        for {
          _    <- cleanDb
          repo <- ZIO.service[RouterRepo]
          a    <- repo.create("a", Sha256Hex.unsafe("h" * 64))
          b    <- repo.create("b", Sha256Hex.unsafe("i" * 64))
          all  <- repo.listAllForHousehold(HouseholdId.Default)
        } yield assertTrue(all.map(_.id) == List(a, b))
      },
    ),
    suite("TrafficReportRepo")(
      test("insertBatch is idempotent on (router_id, period_start, mac, hostname)") {
        for {
          _     <- cleanDb
          rRepo <- ZIO.service[RouterRepo]
          tRepo <- ZIO.service[TrafficReportRepo]
          rid   <- rRepo.create("r1", Sha256Hex.unsafe("j" * 64))
          start = Instant.parse("2026-05-02T14:00:00Z")
          end   = Instant.parse("2026-05-02T14:05:00Z")
          rec   = TrafficReportInsert(
            rid,
            MacAddress.unsafe("aa:bb:cc:11:22:33"),
            Some(IpAddress.unsafe("192.168.1.42")),
            HostId.Fqdn(Hostname.unsafe("youtube.com")),
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
        } yield assertTrue(n1 == 1) && assertTrue(n2 == 0) && assertTrue(rows.size == 1)
      },
      test("different hostnames in same period are stored as distinct rows") {
        for {
          _     <- cleanDb
          rRepo <- ZIO.service[RouterRepo]
          tRepo <- ZIO.service[TrafficReportRepo]
          rid   <- rRepo.create("r1", Sha256Hex.unsafe("k" * 64))
          start = Instant.parse("2026-05-02T14:00:00Z")
          end   = Instant.parse("2026-05-02T14:05:00Z")
          mac   = MacAddress.unsafe("aa:bb:cc:11:22:33")
          batch = List(
            TrafficReportInsert(
              rid,
              mac,
              None,
              HostId.Fqdn(Hostname.unsafe("youtube.com")),
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
              HostId.Fqdn(Hostname.unsafe("google.com")),
              LocalDate.of(2026, 5, 2),
              start,
              end,
              30,
              2L,
              2L,
            ),
          )
          n    <- tRepo.insertBatch(batch)
          rows <- tRepo.listForDevice(HouseholdId.Default, mac, LocalDate.of(2026, 5, 2))
        } yield assertTrue(n == 2) && assertTrue(rows.size == 2) &&
          assertTrue(
            rows.map(_.host).toSet == Set(
              HostId.Fqdn(Hostname.unsafe("youtube.com")),
              HostId.Fqdn(Hostname.unsafe("google.com")),
            ),
          )
      },
      test("listForDevice filters by mac and date") {
        for {
          _     <- cleanDb
          rRepo <- ZIO.service[RouterRepo]
          tRepo <- ZIO.service[TrafficReportRepo]
          rid   <- rRepo.create("r1", Sha256Hex.unsafe("l" * 64))
          mac1 = MacAddress.unsafe("aa:bb:cc:11:22:33")
          mac2 = MacAddress.unsafe("aa:bb:cc:99:99:99")
          d1   = LocalDate.of(2026, 5, 2)
          d2   = LocalDate.of(2026, 5, 3)
          mk   = (mac: MacAddress, d: LocalDate, h: String) =>
            TrafficReportInsert(
              rid,
              mac,
              None,
              HostId.Fqdn(Hostname.unsafe(h)),
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
          rows <- tRepo.listForDevice(HouseholdId.Default, mac1, d1)
        } yield assertTrue(rows.size == 1) && assertTrue(
          rows.head.host == HostId.Fqdn(Hostname.unsafe("a.com")),
        )
      },
    ),
    suite("BlockEventRepo")(
      test("insertBatch returns count and records appear in recent() ordered by ts desc") {
        for {
          _     <- cleanDb
          repo  <- ZIO.service[BlockEventRepo]
          rRepo <- ZIO.service[RouterRepo]
          // #2572: block_events.router_id is a real FK to routers(id), so a row needs a router.
          rid   <- rRepo.create("be-gw", Sha256Hex.unsafe("b" * 64))
          n     <- repo.insertBatch(
            List(
              BlockEventInsert(
                rid,
                Some(MacAddress.unsafe("aa:bb:cc:00:00:01")),
                HostId.Fqdn(Hostname.unsafe("ads.example.com")),
                BlockReason.fromWire("category:ads"),
              ),
              BlockEventInsert(
                rid,
                Some(MacAddress.unsafe("aa:bb:cc:00:00:02")),
                HostId.Fqdn(Hostname.unsafe("casino.example.com")),
                BlockReason.fromWire("category:gambling"),
              ),
              BlockEventInsert(
                rid,
                None,
                HostId.Fqdn(Hostname.unsafe("unknown.example.com")),
                BlockReason.fromWire("category:adult"),
              ),
            ),
          )
          rows  <- repo.recent(10)
        } yield assertTrue(n == 3) && assertTrue(rows.size == 3) &&
          assertTrue(rows.exists(_.mac.isEmpty)) &&
          // The stamped key round-trips through the read.
          assertTrue(rows.forall(_.routerId.contains(rid)))
      },
    ),
    suite("time_usage byte columns")(
      test("time_usage rows default bytes_in/bytes_out to zero") {
        import doobie.implicits.*
        import doobie.postgres.implicits.*
        import zio.interop.catz.*
        for {
          _  <- cleanDb
          tu <- ZIO.service[TimeUsageRepo]
          xa <- ZIO.service[doobie.Transactor[Task]]
          mac = MacAddress.unsafe("aa:bb:cc:dd:ee:01")
          d   = LocalDate.of(2026, 5, 2)
          _   <- tu.incrementSecondsAndBytes(
            mac,
            HostId.Fqdn(Hostname.unsafe("youtube.com")),
            d,
            5L * 60L,
            0L,
            0L,
          )
          row <-
            sql"SELECT bytes_in, bytes_out FROM time_usage WHERE device_mac=${mac.value} AND host_type='fqdn' AND host_value='youtube.com' AND date=$d"
              .query[(Long, Long)]
              .unique
              .transact(xa)
        } yield assertTrue(row == ((0L, 0L)))
      },
    ),
  ) @@ TestAspect.sequential
}
