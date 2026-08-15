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

// #1091: pre-attribute owning app(s) at rollup-write time so the per-app read
// paths can aggregate via SQL `GROUP BY app_id` instead of re-running the
// in-memory HostMatch.lookupApex tail-walk over every row.
//
// Coverage:
//   - rerollHourly/Daily populate traffic_{hourly,daily}_apps via lookupApex
//     and stamp the app_hosts_version onto every rolled row.
//   - aggregateByApp{Hourly,Daily} reads pre-attributed rows via SQL GROUP BY.
//   - An app_hosts mutation bumps the version; a read against rollup rows
//     stamped at the old version falls back to live in-memory matching rather
//     than serving stale attribution.
//   - A host in multiple apps fans to multiple side-table rows, but the
//     aggregation dedups to the lowest app id (no double-counting).
object RollupAppAttributionSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Transactor[Task]] {

  override val bootstrap = TestDatabase.layer

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val mac       = MacAddress.unsafe("aa:bb:cc:dd:ee:01")
  // Subdomain in traffic, apex in app_hosts — exercises the lookupApex walk.
  private val ytHost    = HostId.Fqdn(Hostname.unsafe("m.youtube.com"))
  private val otherHost = HostId.Fqdn(Hostname.unsafe("example.net"))
  private val ytApex    = "youtube.com"

  private def seedRouter: RIO[RouterRepo, RouterId] =
    ZIO.serviceWithZIO[RouterRepo](rr => rr.create("test", PolicyService.hashToken("et_dummy")))

  // Seed `bucketCount` 5-min traffic_reports rows for `host` starting at `startUtc`.
  private def seedReports(
      routerId: RouterId,
      startUtc: Instant,
      bucketCount: Int,
      host: HostId,
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

  private def createApp(name: String, slug: String): RIO[AppRepo, AppId] =
    ZIO.serviceWithZIO[AppRepo](_.create(name, slug, None, None))

  private def countHourlyApps: RIO[Transactor[Task], Long]              =
    ZIO.serviceWithZIO[Transactor[Task]](xa =>
      sql"SELECT COUNT(*) FROM traffic_hourly_apps".query[Long].unique.transact(xa),
    )
  private def countDailyApps: RIO[Transactor[Task], Long]               =
    ZIO.serviceWithZIO[Transactor[Task]](xa =>
      sql"SELECT COUNT(*) FROM traffic_daily_apps".query[Long].unique.transact(xa),
    )
  private def hourlyVersions: RIO[Transactor[Task], List[Option[Long]]] =
    ZIO.serviceWithZIO[Transactor[Task]](xa =>
      sql"SELECT DISTINCT app_hosts_version FROM traffic_hourly ORDER BY 1"
        .query[Option[Long]]
        .to[List]
        .transact(xa),
    )

  private val start =
    LocalDate.of(2026, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant

  def spec = suite("RollupRepo app attribution (#1091)")(
    test("rerollHourly attributes the owning app via SQL and stamps the version") {
      for {
        _       <- cleanDb
        rid     <- seedRouter
        _       <- seedReports(rid, start, 12, ytHost, bytesPerBucket = 100L)
        _       <- seedReports(rid, start, 12, otherHost, bytesPerBucket = 50L)
        appRepo <- ZIO.service[AppRepo]
        appId   <- createApp("YouTube", "youtube")
        _       <- appRepo.setHosts(appId, List(Hostname.unsafe(ytApex)))
        version <- appRepo.currentHostsVersion
        appsByApex = Map(ytApex -> List(appId))
        rollup <- ZIO.service[RollupRepo]
        _      <- rollup.rerollHourly(start.minusSeconds(3600), appsByApex, version)
        sideN  <- countHourlyApps
        stamps <- hourlyVersions
        aggs   <- rollup.aggregateByAppHourly(
          HouseholdId.Default,
          List(mac),
          start,
          start.plusSeconds(7200),
          version,
          appsByApex,
        )
        ytAgg    = aggs.find(_.appId.contains(appId))
        otherAgg = aggs.find(_.appId.isEmpty)
      } yield assertTrue(
        version == 1L,
        sideN == 1L, // only the youtube row is in an app; example.net is not
        stamps == List(Some(1L)),
        ytAgg.exists(a => a.activeSeconds == 12 * 300 && a.bytesIn == 12 * 100L),
        otherAgg.exists(_.activeSeconds == 12 * 300),
      )
    },
    test(
      "an app_hosts mutation bumps the version, so stale rollup rows fall back to live matching",
    ) {
      for {
        _       <- cleanDb
        rid     <- seedRouter
        _       <- seedReports(rid, start, 12, ytHost, bytesPerBucket = 100L)
        appRepo <- ZIO.service[AppRepo]
        rollup  <- ZIO.service[RollupRepo]
        // Roll before any app exists: empty snapshot, version 0.
        v0      <- appRepo.currentHostsVersion
        _       <- rollup.rerollHourly(start.minusSeconds(3600), Map.empty, v0)
        aggs0   <- rollup.aggregateByAppHourly(
          HouseholdId.Default,
          List(mac),
          start,
          start.plusSeconds(3600),
          v0,
          Map.empty,
        )
        // Operator adds the host -> version bumps. Rollup rows are now stale.
        appId   <- createApp("YouTube", "youtube")
        _       <- appRepo.setHosts(appId, List(Hostname.unsafe(ytApex)))
        v1      <- appRepo.currentHostsVersion
        appsByApex = Map(ytApex -> List(appId))
        // Read WITHOUT re-rolling: rows stamped v0 < v1 -> fallback to live match.
        aggs1 <- rollup.aggregateByAppHourly(
          HouseholdId.Default,
          List(mac),
          start,
          start.plusSeconds(3600),
          v1,
          appsByApex,
        )
      } yield assertTrue(
        v0 == 0L,
        v1 == 1L,
        aggs0 == List(AppUsageAgg(None, 12 * 300L, 12 * 100L, 12 * 200L)),
        aggs1.find(_.appId.contains(appId)).exists(_.activeSeconds == 12 * 300L),
        // No silent staleness: nothing leaks into "Other" once the app owns it.
        !aggs1.exists(a => a.appId.isEmpty && a.activeSeconds > 0),
      )
    },
    test(
      "a host in multiple apps fans to side-table rows but aggregation dedups to lowest app id",
    ) {
      for {
        _       <- cleanDb
        rid     <- seedRouter
        _       <- seedReports(rid, start, 12, ytHost, bytesPerBucket = 100L)
        appRepo <- ZIO.service[AppRepo]
        rollup  <- ZIO.service[RollupRepo]
        appA    <- createApp("Alpha", "alpha")
        appB    <- createApp("Beta", "beta")
        _       <- appRepo.setHosts(appA, List(Hostname.unsafe(ytApex)))
        _       <- appRepo.setHosts(appB, List(Hostname.unsafe(ytApex)))
        version <- appRepo.currentHostsVersion
        appsByApex = Map(ytApex -> List(appA, appB))
        _     <- rollup.rerollHourly(start.minusSeconds(3600), appsByApex, version)
        sideN <- countHourlyApps
        aggs  <- rollup.aggregateByAppHourly(
          HouseholdId.Default,
          List(mac),
          start,
          start.plusSeconds(3600),
          version,
          appsByApex,
        )
      } yield assertTrue(
        appA.value < appB.value,
        version == 2L,
        sideN == 2L,                         // both apps recorded for the one rollup row (fan)
        aggs.size == 1,
        aggs.head.appId.contains(appA),      // dedup to lowest id
        aggs.head.activeSeconds == 12 * 300L,// not double-counted
      )
    },
    test("rerollDaily attributes the owning app and aggregateByAppDaily reads it via SQL") {
      for {
        _   <- cleanDb
        rid <- seedRouter
        date   = LocalDate.of(2026, 1, 1)
        dStart = date.atStartOfDay(ZoneOffset.UTC).toInstant
        _       <- seedReports(rid, dStart, 288, ytHost, bytesPerBucket = 10L) // full day
        appRepo <- ZIO.service[AppRepo]
        rollup  <- ZIO.service[RollupRepo]
        appId   <- createApp("YouTube", "youtube")
        _       <- appRepo.setHosts(appId, List(Hostname.unsafe(ytApex)))
        version <- appRepo.currentHostsVersion
        appsByApex = Map(ytApex -> List(appId))
        _     <- rollup.rerollDaily(date.minusDays(1), appsByApex, version)
        sideN <- countDailyApps
        aggs  <- rollup.aggregateByAppDaily(
          HouseholdId.Default,
          List(mac),
          dStart,
          dStart.plusSeconds(86400),
          version,
          appsByApex,
        )
      } yield assertTrue(
        sideN == 1L,
        aggs.find(_.appId.contains(appId)).exists(_.activeSeconds == 288 * 300),
      )
    },
  ) @@ TestAspect.sequential
}
