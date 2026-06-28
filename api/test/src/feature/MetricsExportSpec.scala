package wifihaven.api.feature

import com.zaxxer.hikari.HikariDataSource
import doobie.Transactor
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import wifihaven.api.db.*
import wifihaven.api.metrics.{DbPoolMetrics, MetricsRuntime}
import wifihaven.api.usage.TimeUsedRollupJob
import wifihaven.shared.Clock
import wifihaven.testinfra.*
import zio.*
import zio.metrics.connectors.prometheus.PrometheusPublisher
import zio.test.*

// #1243: DB-pool gauges + rollup counters/histograms published through the
// Prometheus registry. Runs against embedded Postgres (no repo mocks).
object MetricsExportSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Transactor[Task]] {

  override val bootstrap = TestDatabase.layer

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val pollInterval = 100.millis

  // #2042: deterministically fire ONE scrape of the Prometheus publisher fiber. The key is waiting
  // until that background fiber has actually re-parked on the TestClock (its `Schedule.fixed` sleep
  // is registered) BEFORE advancing — under parallel CI load a bare `adjust` can slip past a fiber
  // that's momentarily mid-flight and never trigger the scrape (the original wall-clock flake, just
  // relocated). Once it's parked, advancing exactly one interval fires exactly one registry snapshot.
  private val tickPublisher: UIO[Unit] =
    zio.test.TestClock.sleeps.repeatUntil(_.nonEmpty) *> zio.test.TestClock.adjust(pollInterval)

  private def exposition: ZIO[PrometheusPublisher, Nothing, String] =
    ZIO.serviceWithZIO[PrometheusPublisher](_.get)

  // A Hikari pool wired to the per-spec embedded DB, so getHikariPoolMXBean is real.
  private def hikari(
      maxSize: Int,
  ): ZIO[EmbeddedPostgres & TestDatabase.TestDb & Scope, Throwable, HikariDataSource] =
    for {
      pg <- ZIO.service[EmbeddedPostgres]
      db <- ZIO.service[TestDatabase.TestDb]
      ds <- ZIO.acquireRelease(ZIO.attempt {
        val h = new HikariDataSource()
        h.setJdbcUrl(pg.getJdbcUrl("postgres", db.name))
        h.setUsername("postgres")
        h.setMaximumPoolSize(maxSize)
        h
      })(h => ZIO.succeed(h.close()))
      // Force at least one connection through the pool so the MXBean reports live numbers.
      _  <- ZIO.attempt {
        val c = ds.getConnection
        try c.createStatement().execute("SELECT 1")
        finally c.close()
      }
    } yield ds

  def spec = suite("Metrics export (#1243)")(
    test("db-pool gauges are present with sane values after a poll") {
      (for {
        _    <- cleanDb
        _    <- ZIO.scoped {
          for {
            ds <- hikari(maxSize = 7)
            _  <- DbPoolMetrics.pollOnce(ds, maxSize = 7)
          } yield ()
        }
        // The connector's snapshot fiber runs on ZIO's Clock (the TestClock under
        // TestEnvironment), so advancing by exactly one poll interval drives a
        // deterministic scrape that captures the gauges set above — no wall-clock race.
        _    <- tickPublisher
        body <- exposition
      } yield assertTrue(body.contains("wifihaven_db_pool_active_connections")) &&
        assertTrue(body.contains("wifihaven_db_pool_idle_connections")) &&
        assertTrue(body.contains("wifihaven_db_pool_total_connections")) &&
        assertTrue(body.contains("wifihaven_db_pool_threads_awaiting_connection")) &&
        assertTrue(body.contains("wifihaven_db_pool_max_size")) &&
        // max_size gauge was set to 7 (sample line: `name 7.0 <timestamp>`)
        assertTrue(
          body.linesIterator.exists(l =>
            l.startsWith("wifihaven_db_pool_max_size ") && l.split(" ").lift(1).contains("7.0"),
          ),
        ))
        .provideSome[TestDatabase.AllRepos & EmbeddedPostgres & Transactor[Task]](
          MetricsRuntime.prometheus(pollInterval),
        )
    },
    test("driving a rollup tick emits the rollup counter/histogram/gauge series") {
      (for {
        _              <- cleanDb
        timeRollupRepo <- ZIO.service[TimeUsedRollupRepo]
        appRollupRepo  <- ZIO.service[AppUsedRollupRepo]
        rollupRepo     <- ZIO.service[RollupRepo]
        profileRepo    <- ZIO.service[ProfileRepo]
        deviceRepo     <- ZIO.service[DeviceRepo]
        atlRepo        <- ZIO.service[AppTimeLimitRepo]
        appRepo        <- ZIO.service[AppRepo]
        trafficRepo    <- ZIO.service[TrafficReportRepo]
        hsRepo         <- ZIO.service[HouseholdSettingsRepo]
        clock          <- ZIO.service[Clock]
        // Drive exactly one tick synchronously through the metric-emission site
        // (`runOnce` → `RollupRepo.recordRun`) instead of forking the looping fiber
        // and racing wall time. This is the same code path the production loop body
        // runs each tick.
        _              <- TimeUsedRollupJob.runOnce(
          rollupRepo,
          clock,
          now =>
            TimeUsedRollupJob.oneTickForTest(
              timeRollupRepo,
              appRollupRepo,
              profileRepo,
              deviceRepo,
              atlRepo,
              trafficRepo,
              hsRepo,
              now,
            ),
        )
        // Advance one poll interval so the connector's snapshot fiber (running on the
        // TestClock) scrapes the just-recorded series deterministically.
        _              <- tickPublisher
        body           <- exposition
      } yield assertTrue(body.contains("wifihaven_rollup_runs_total")) &&
        assertTrue(body.contains("wifihaven_rollup_duration_seconds")) &&
        assertTrue(body.contains("wifihaven_rollup_rows_upserted")) &&
        assertTrue(body.contains("time_used_daily")) &&
        // #1291: the fiber name is tagged under `rollup_job`, never the reserved
        // `job` label (which Alloy clobbers with the scrape component name).
        assertTrue(body.contains("rollup_job=\"time_used_daily\"")))
        .provideSome[TestDatabase.AllRepos & EmbeddedPostgres & Transactor[Task]](
          MetricsRuntime.prometheus(pollInterval),
          Clock.live,
        )
    },
  ) @@ TestAspect.sequential
}
