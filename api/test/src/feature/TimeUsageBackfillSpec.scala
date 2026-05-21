package wifihaven.api.feature

// Tests for the stop-gap read-side FQDN coalesce added in #731.
//
// When a time_usage row was written with host_type='ipv4' because the router's
// conntrack/dns-tail race fired before the DNS reply arrived, the read paths
// (listForDevice, listForDeviceMacs, snapshotAll) now LEFT JOIN connection_events
// to look up a resolved hostname for the same (mac, dest_ip) pair within the
// same calendar day.  These tests verify:
//
//   1. ipv4 row + matching resolved connection_event → promoted to fqdn.
//   2. ipv4 row + NO matching connection_event → stays ipv4.
//   3. Different mac, same host_value (dest_ip) → no cross-attribution.
//   4. connection_event is from a different day → no promotion.
//
// TODO(#730): delete this spec along with the join when #730 lands.

import wifihaven.api.db.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.*
import zio.test.*

import java.time.{Instant, LocalDate}
import java.util.UUID

object TimeUsageBackfillSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres] {

  override val bootstrap = TestDatabase.layer

  private def cleanDb = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
    TestDatabase.cleanAndMigrate.provide(ZLayer.succeed(pg)),
  )

  private val testDate   = LocalDate.of(2026, 5, 7)
  private val mac1       = MacAddress.unsafe("aa:bb:cc:11:22:33")
  private val mac2       = MacAddress.unsafe("aa:bb:cc:44:55:66")
  private val destIp     = IpAddress.unsafe("34.223.124.45")
  private val resolvedFqdn = Hostname.unsafe("neverssl.com")

  // Timestamp within testDate (UTC)
  private def tsOnDay(hour: Int): Instant =
    testDate.atTime(hour, 0).toInstant(java.time.ZoneOffset.UTC)

  // Directly insert a connection_events row with a resolved_host_value set.
  // We bypass the ingest route and insert directly so we can precisely control
  // resolved_host_value without triggering the ingest-time backfill logic.
  private def seedResolvedEvent(
      cRepo: ConnectionEventRepo,
      rRepo: RouterRepo,
      mac: MacAddress,
      destIp: IpAddress,
      resolved: Hostname,
      ts: Instant,
  ): Task[Unit] =
    for {
      rid <- rRepo.create("backfill-test-router", Sha256Hex.unsafe("e" * 64))
      _   <- rRepo.completeEnrollment(rid, Sha256Hex.unsafe("f" * 64))
      _   <- cRepo.insertBatch(
        List(
          ConnectionEventInsert(
            routerId = rid,
            mac = Some(mac),
            host = HostId.IPv4(destIp),
            destIp = Some(destIp),
            allowed = true,
            reason = "allow",
            ts = ts,
            eventId = Some(UUID.randomUUID()),
            resolvedHost = Some(resolved),
          ),
        ),
      )
    } yield ()

  def spec = suite("TimeUsage read-side FQDN coalesce (#731 stop-gap)")(

    // ── 1. ipv4 row + matching resolved connection_event → promoted to fqdn ──
    test("listForDevice: ipv4 time_usage row with matching resolved connection_event reads as fqdn") {
      for {
        _     <- cleanDb
        tu    <- ZIO.service[TimeUsageRepo]
        cRepo <- ZIO.service[ConnectionEventRepo]
        rRepo <- ZIO.service[RouterRepo]
        // Write an ipv4 time_usage row for mac1 / destIp
        _     <- tu.incrementSecondsAndBytes(
          mac1,
          HostId.IPv4(destIp),
          testDate,
          300L,
          1000L,
          500L,
        )
        // Seed a resolved connection_event for same (mac1, destIp) within the same day
        _     <- seedResolvedEvent(cRepo, rRepo, mac1, destIp, resolvedFqdn, tsOnDay(14))
        // Read back via listForDevice — should be promoted to fqdn
        rows  <- tu.listForDevice(mac1, testDate)
      } yield assertTrue(rows.size == 1) &&
        assertTrue(rows.head.host == HostId.Fqdn(resolvedFqdn))
    },

    // ── 2. ipv4 row + NO matching connection_event → stays ipv4 ───────────
    test("listForDevice: ipv4 row with no matching connection_event stays ipv4") {
      for {
        _    <- cleanDb
        tu   <- ZIO.service[TimeUsageRepo]
        _    <- tu.incrementSecondsAndBytes(
          mac1,
          HostId.IPv4(destIp),
          testDate,
          60L,
          100L,
          50L,
        )
        rows <- tu.listForDevice(mac1, testDate)
      } yield assertTrue(rows.size == 1) &&
        assertTrue(rows.head.host == HostId.IPv4(destIp))
    },

    // ── 3. Different mac, same dest_ip → no cross-attribution ─────────────
    test("listForDevice: resolved event for mac1 does NOT promote ipv4 row for mac2") {
      for {
        _     <- cleanDb
        tu    <- ZIO.service[TimeUsageRepo]
        cRepo <- ZIO.service[ConnectionEventRepo]
        rRepo <- ZIO.service[RouterRepo]
        // ipv4 time_usage for mac2
        _     <- tu.incrementSecondsAndBytes(
          mac2,
          HostId.IPv4(destIp),
          testDate,
          60L,
          100L,
          50L,
        )
        // Resolved event is for mac1, not mac2
        _     <- seedResolvedEvent(cRepo, rRepo, mac1, destIp, resolvedFqdn, tsOnDay(14))
        rows  <- tu.listForDevice(mac2, testDate)
      } yield assertTrue(rows.size == 1) &&
        // mac2's ipv4 row must NOT be attributed to mac1's fqdn resolution
        assertTrue(rows.head.host == HostId.IPv4(destIp))
    },

    // ── 4. Stale connection_event (different day) → no promotion ──────────
    test("listForDevice: connection_event from a different day does not promote the ipv4 row") {
      for {
        _     <- cleanDb
        tu    <- ZIO.service[TimeUsageRepo]
        cRepo <- ZIO.service[ConnectionEventRepo]
        rRepo <- ZIO.service[RouterRepo]
        // time_usage on testDate
        _     <- tu.incrementSecondsAndBytes(
          mac1,
          HostId.IPv4(destIp),
          testDate,
          60L,
          100L,
          50L,
        )
        // connection_event from the day *before* testDate — outside the same-day window
        staleTs = testDate.minusDays(1).atTime(23, 59).toInstant(java.time.ZoneOffset.UTC)
        _     <- seedResolvedEvent(cRepo, rRepo, mac1, destIp, resolvedFqdn, staleTs)
        rows  <- tu.listForDevice(mac1, testDate)
      } yield assertTrue(rows.size == 1) &&
        assertTrue(rows.head.host == HostId.IPv4(destIp))
    },

    // ── 5. listForDeviceMacs: multi-mac promotion works correctly ──────────
    test("listForDeviceMacs: ipv4 rows are promoted per-mac independently") {
      for {
        _     <- cleanDb
        tu    <- ZIO.service[TimeUsageRepo]
        cRepo <- ZIO.service[ConnectionEventRepo]
        rRepo <- ZIO.service[RouterRepo]
        // ipv4 time_usage for both macs, same dest_ip
        _     <- tu.incrementSecondsAndBytes(mac1, HostId.IPv4(destIp), testDate, 120L, 200L, 100L)
        _     <- tu.incrementSecondsAndBytes(mac2, HostId.IPv4(destIp), testDate, 60L, 100L, 50L)
        // Only mac1 has a resolved event
        _     <- seedResolvedEvent(cRepo, rRepo, mac1, destIp, resolvedFqdn, tsOnDay(14))
        rows  <- tu.listForDeviceMacs(List(mac1, mac2), testDate)
      } yield {
        val mac1Row = rows.filter(_.deviceMac == mac1)
        val mac2Row = rows.filter(_.deviceMac == mac2)
        assertTrue(mac1Row.size == 1) &&
        assertTrue(mac1Row.head.host == HostId.Fqdn(resolvedFqdn)) &&
        assertTrue(mac2Row.size == 1) &&
        assertTrue(mac2Row.head.host == HostId.IPv4(destIp))
      }
    },

    // ── 6. snapshotAll: promotion flows through the snapshot map ──────────
    test("snapshotAll: promoted ipv4 row appears under its fqdn key in the snapshot map") {
      for {
        _     <- cleanDb
        tu    <- ZIO.service[TimeUsageRepo]
        cRepo <- ZIO.service[ConnectionEventRepo]
        rRepo <- ZIO.service[RouterRepo]
        _     <- tu.incrementSecondsAndBytes(
          mac1,
          HostId.IPv4(destIp),
          testDate,
          // 2 minutes → 2 mins in snapshot
          120L,
          200L,
          100L,
        )
        _     <- seedResolvedEvent(cRepo, rRepo, mac1, destIp, resolvedFqdn, tsOnDay(14))
        snap  <- tu.snapshotAll(testDate)
      } yield {
        val fqdnKey = (mac1, HostId.Fqdn(resolvedFqdn))
        val ipv4Key = (mac1, HostId.IPv4(destIp))
        assertTrue(snap.contains(fqdnKey)) &&
        assertTrue(!snap.contains(ipv4Key)) &&
        assertTrue(snap(fqdnKey) == 2)
      }
    },

    // ── 7. De-dup / overlap: both ipv4 and native-fqdn rows exist ─────────
    // When the agent emits some flows as ipv4 (race losers) and later emits
    // the same destination as fqdn (after DNS resolved), we get TWO time_usage
    // rows: one ipv4 row (promoted by the join to fqdn) and one genuine fqdn
    // row. The join promotes the ipv4 bucket to the same fqdn key, so after
    // promotion both rows share the same HostId. listForDevice returns both
    // rows separately (they are distinct time_usage rows); callers that GROUP
    // BY host will SUM them. We document this here: it's a known, bounded
    // overlap for the stop-gap period. #730 eliminates it at ingest time.
    test("listForDevice: promoted ipv4 row and native fqdn row both appear (documented overlap)") {
      for {
        _    <- cleanDb
        tu   <- ZIO.service[TimeUsageRepo]
        cRepo <- ZIO.service[ConnectionEventRepo]
        rRepo <- ZIO.service[RouterRepo]
        // ipv4 time_usage row (race-loser)
        _    <- tu.incrementSecondsAndBytes(mac1, HostId.IPv4(destIp), testDate, 60L, 100L, 50L)
        // genuine fqdn time_usage row (agent resolved on a later flow)
        _    <- tu.incrementSecondsAndBytes(
          mac1,
          HostId.Fqdn(resolvedFqdn),
          testDate,
          120L,
          200L,
          80L,
        )
        _    <- seedResolvedEvent(cRepo, rRepo, mac1, destIp, resolvedFqdn, tsOnDay(14))
        rows <- tu.listForDevice(mac1, testDate)
      } yield {
        // Both rows should be present; after promotion they both show as the fqdn.
        // A GROUP BY rollup will see two entries for the same fqdn — this is the
        // known small overlap documented in the #731 PR body.
        assertTrue(rows.size == 2) &&
        assertTrue(rows.forall(_.host == HostId.Fqdn(resolvedFqdn)))
      }
    },

  ) @@ TestAspect.sequential
}
