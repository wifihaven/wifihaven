package wifihaven.api.feature

// #2174: SQL-side pre-aggregation for the raw-tier fixed-step buckets of /api/usage/traffic.
//
// The aggregate path used to fetch EVERY raw traffic_reports row in the window
// (`listRawInRange`, unbounded — 755k rows / 24h at prod volume, ~24s) and aggregate in Scala.
// `listRawAggregatedInRange` pushes the per-(mac, host, bucket) SUM into Postgres and ships one
// row per group. These tests pin the load-bearing invariant: for the UTC-grid buckets (1m / 10m /
// 1h) the pre-aggregated fetch fed through `UsageTraffic.buildAggregate` produces EXACTLY the same
// aggregate rows as the old per-row fetch — same windows, same sums, same distinct counts, same
// ipv4→fqdn attribution. Full stack over embedded Postgres, no repo mocks (AGENTS.md testing
// philosophy).
//
//   1. Equivalence: multi-period, multi-mac, multi-host seed (incl. an ipv4 row with a resolved
//      connection_event) → old path and new path produce identical row sets, across groupBy
//      variants.
//   2. In-bucket summing: two report periods of one (mac, host) inside one 10m bucket → one
//      aggregate row carrying the sums, with the floored [start, start+step) window.
//   3. ipv4 rows with a same-day resolved connection_event attribute to the fqdn domain (the
//      LATERAL resolve runs post-GROUP BY in the new path — must not change attribution).

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import wifihaven.api.db.*
import wifihaven.api.usage.{UsageTraffic, UsageTrafficQuery}
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import zio.*
import zio.test.*

import java.time.{Instant, LocalDate, ZoneOffset}
import java.util.UUID

object UsageTrafficSqlPreAggSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres] {

  override val bootstrap = TestDatabase.layer

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val testDate = LocalDate.of(2026, 5, 7)
  private val mac1     = MacAddress.unsafe("aa:bb:cc:11:22:33")
  private val mac2     = MacAddress.unsafe("aa:bb:cc:44:55:66")
  private val destIp   = IpAddress.unsafe("34.223.124.45")
  private val resolved = Hostname.unsafe("neverssl.com")

  private def at(hour: Int, minute: Int = 0): Instant =
    testDate.atTime(hour, minute).toInstant(ZoneOffset.UTC)

  private val dayStart = at(0)
  private val dayEnd   = dayStart.plusSeconds(86400L)

  private def seedRouter: RIO[RouterRepo, RouterId] =
    for {
      rRepo <- ZIO.service[RouterRepo]
      rid   <- rRepo.create("preagg-test-router", Sha256Hex.unsafe("a" * 64))
      _     <- rRepo.completeEnrollment(rid, Sha256Hex.unsafe("b" * 64))
    } yield rid

  private def report(
      rid: RouterId,
      mac: MacAddress,
      host: HostId,
      start: Instant,
      seconds: Int,
      bytesIn: Long,
      bytesOut: Long,
  ): TrafficReportInsert =
    TrafficReportInsert(
      rid,
      mac,
      None,
      host,
      start.atZone(ZoneOffset.UTC).toLocalDate,
      start,
      start.plusSeconds(300L),
      seconds,
      bytesIn,
      bytesOut,
    )

  // The shared seed: enough shape to make aggregation non-trivial.
  //   - mac1 × example.com: two periods inside the 10:00–10:10 bucket (must sum), one in the
  //     10:10–10:20 bucket, one in the 11:00 hour (distinct 1h bucket).
  //   - mac2 × other.com: one period inside the 10:00–10:10 bucket (distinct-device count).
  //   - mac1 × destIp (ipv4): one period at 10:00, with a same-day resolved connection_event →
  //     attributes to `neverssl.com` in BOTH paths. Distinct byte totals everywhere so aggregate
  //     rows never tie on the sort key.
  private def seedAll: RIO[TestDatabase.AllRepos, Unit] =
    for {
      rid   <- seedRouter
      tRepo <- ZIO.service[TrafficReportRepo]
      cRepo <- ZIO.service[ConnectionEventRepo]
      _     <- tRepo
        .insertBatch(
          List(
            report(
              rid,
              mac1,
              HostId.Fqdn(Hostname.unsafe("example.com")),
              at(10, 0),
              300,
              1000L,
              100L,
            ),
            report(
              rid,
              mac1,
              HostId.Fqdn(Hostname.unsafe("example.com")),
              at(10, 5),
              200,
              2000L,
              200L,
            ),
            report(
              rid,
              mac1,
              HostId.Fqdn(Hostname.unsafe("example.com")),
              at(10, 15),
              100,
              4000L,
              400L,
            ),
            report(
              rid,
              mac1,
              HostId.Fqdn(Hostname.unsafe("example.com")),
              at(11, 0),
              50,
              8000L,
              800L,
            ),
            report(
              rid,
              mac2,
              HostId.Fqdn(Hostname.unsafe("other.com")),
              at(10, 0),
              60,
              16000L,
              1600L,
            ),
            report(rid, mac1, HostId.IPv4(destIp), at(10, 0), 30, 32000L, 3200L),
          ),
        )
      _     <- cRepo.insertBatch(
        List(
          ConnectionEventInsert(
            routerId = rid,
            mac = Some(mac1),
            host = HostId.IPv4(destIp),
            destIp = Some(destIp),
            allowed = true,
            reason = BlockReason.Allow,
            ts = at(9, 55),
            eventId = Some(UUID.randomUUID()),
            resolvedHost = Some(resolved),
          ),
        ),
      )
    } yield ()

  private val deviceByMac: Map[MacAddress, Device] = Map(
    mac1 -> Device(DeviceId(1L), mac1, "iPad", Some(ProfileId(1L)), Some("Kids"), None, None),
    mac2 -> Device(DeviceId(2L), mac2, "Phone", Some(ProfileId(2L)), Some("Adults"), None, None),
  )

  private val profileNames: Map[ProfileId, String] =
    Map(ProfileId(1L) -> "Kids", ProfileId(2L) -> "Adults")

  // Run both fetch paths through the SAME buildAggregate and return (old, new) row sets.
  private def bothPaths(
      bucket: UsageTraffic.Bucket,
      groupBy: Set[UsageTraffic.GroupBy],
      macs: List[MacAddress] = Nil,
  ): RIO[TestDatabase.AllRepos, (Set[TrafficUsageAggregateRow], Set[TrafficUsageAggregateRow])] =
    for {
      tRepo <- ZIO.service[TrafficReportRepo]
      step = UsageTraffic.stepOf(bucket).map(_.toSeconds).getOrElse(60L)
      oldRows <- tRepo.listRawInRange(macs, dayStart, dayEnd)
      newRows <- tRepo.listRawAggregatedInRange(macs, dayStart, dayEnd, step)
      build = (rows: List[wifihaven.api.usage.TrafficUsageDbRow]) =>
        UsageTraffic
          .buildAggregate(rows, bucket, ZoneOffset.UTC, groupBy, deviceByMac, profileNames)
          .toSet
    } yield (build(oldRows), build(newRows))

  def spec = suite("/api/usage/traffic SQL pre-aggregation equivalence (#2174)")(
    test("10m: pre-aggregated fetch produces identical aggregate rows to the per-row fetch") {
      for {
        _      <- cleanDb
        _      <- seedAll
        domain <- bothPaths(UsageTraffic.Bucket.TenMin, Set(UsageTraffic.GroupBy.Domain))
        multi  <- bothPaths(
          UsageTraffic.Bucket.TenMin,
          Set(
            UsageTraffic.GroupBy.Domain,
            UsageTraffic.GroupBy.Device,
            UsageTraffic.GroupBy.Profile,
          ),
        )
        bare   <- bothPaths(UsageTraffic.Bucket.TenMin, Set.empty)
      } yield assertTrue(
        domain._1.nonEmpty,
        domain._1 == domain._2,
        multi._1 == multi._2,
        bare._1 == bare._2,
      )
    },
    test("1m and 1h grids are equivalent too") {
      for {
        _   <- cleanDb
        _   <- seedAll
        m1  <- bothPaths(UsageTraffic.Bucket.OneMin, Set(UsageTraffic.GroupBy.Domain))
        h1  <- bothPaths(UsageTraffic.Bucket.OneHour, Set(UsageTraffic.GroupBy.Device))
        mac <- bothPaths(UsageTraffic.Bucket.TenMin, Set(UsageTraffic.GroupBy.Domain), List(mac1))
      } yield assertTrue(
        m1._1 == m1._2,
        h1._1 == h1._2,
        mac._1.nonEmpty,
        mac._1 == mac._2,
      )
    },
    test("10m: two report periods of one (mac, host) in one bucket sum into one floored row") {
      for {
        _     <- cleanDb
        _     <- seedAll
        tRepo <- ZIO.service[TrafficReportRepo]
        rows  <- tRepo.listRawAggregatedInRange(List(mac1), dayStart, dayEnd, 600L)
        exampleAt10 = rows.filter(r => r.host.value == "example.com" && r.periodStart == at(10, 0))
      } yield assertTrue(
        // 10:00 + 10:05 periods collapsed into ONE pre-aggregated row…
        exampleAt10.size == 1,
        // …carrying the sums…
        exampleAt10.head.activeSeconds == 500,
        exampleAt10.head.bytesIn == 3000L,
        exampleAt10.head.bytesOut == 300L,
        // …with the floored [start, start+step) window.
        exampleAt10.head.periodEnd == at(10, 10),
      )
    },
    test("ipv4 rows with a same-day resolved connection_event attribute to the fqdn") {
      for {
        _     <- cleanDb
        _     <- seedAll
        tRepo <- ZIO.service[TrafficReportRepo]
        rows  <- tRepo.listRawAggregatedInRange(List(mac1), dayStart, dayEnd, 600L)
      } yield assertTrue(
        rows.exists(r => r.host.value == resolved.value),
        !rows.exists(r => r.host.value == destIp.value),
      )
    },
    test("UsageTrafficQuery.aggregate routes 10m raw-tier reads through the pre-aggregated fetch") {
      // Behavioural pin at the query-core level (the exact seam SpaPush + the GET share): a 10m
      // aggregate over the seeded day yields the same rows whether computed via the core or via
      // the old fetch fed to buildAggregate directly.
      for {
        _       <- cleanDb
        _       <- seedAll
        tRepo   <- ZIO.service[TrafficReportRepo]
        rRepo   <- ZIO.service[RollupRepo]
        core    <- UsageTrafficQuery.aggregate(
          tRepo,
          rRepo,
          Nil,
          dayStart,
          dayEnd,
          UsageTraffic.Bucket.TenMin,
          Set(UsageTraffic.GroupBy.Domain),
          ZoneOffset.UTC,
          deviceByMac,
          profileNames,
          Map.empty,
        )
        oldRows <- tRepo.listRawInRange(Nil, dayStart, dayEnd)
        expected = UsageTraffic.buildAggregate(
          oldRows,
          UsageTraffic.Bucket.TenMin,
          ZoneOffset.UTC,
          Set(UsageTraffic.GroupBy.Domain),
          deviceByMac,
          profileNames,
        )
      } yield assertTrue(core.nonEmpty, core.toSet == expected.toSet)
    },
  ) @@ TestAspect.sequential
}
