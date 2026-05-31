package wifihaven.api.feature

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import wifihaven.api.db.*
import wifihaven.api.usage.{RollupJobs, TimeUsedRollupJob}
import wifihaven.shared.Clock
import wifihaven.testinfra.*
import zio.{Clock as _, *}
import zio.test.*

import java.sql.SQLException
import java.time.LocalDateTime

/**
 * #1247: on shutdown the scoped Hikari pool finalizer can close the pool while a rollup tick is mid
 * `getConnection`, so the tick throws "HikariDataSource (HikariPool-N) has been closed". Without
 * special handling each such tick logs an ERROR stack trace and records an `error` row in
 * `rollup_runs` — false rollup-failure signal on every deploy, which would pollute the
 * Prometheus/Grafana export (#1243) and alert (#1240).
 *
 * The rollup `runOnce` wrappers must treat a pool-closed failure (and a mid-tick interruption) as a
 * benign shutdown artifact: no ERROR log, no `error` run. A *real* SQL error during a normal tick
 * must still log ERROR and record an `error` run, so the guard can't hide genuine failures.
 */
object RollupShutdownSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ Clock.TestClock.make(LocalDateTime.of(2026, 5, 31, 21, 10, 0))

  private val cleanDb = TestDatabase.cleanAndMigrate

  // The exact shape Hikari throws once the datasource is closed.
  private def poolClosed =
    new SQLException("HikariDataSource (HikariPool-1) has been closed.")

  // A genuine query error that is NOT a shutdown artifact — must still surface.
  private def realSqlError =
    new SQLException("ERROR: relation \"traffic_reports\" does not exist")

  private def errorRuns(rollup: RollupRepo) =
    rollup.recentRuns(20).map(_.filter(_.status == "error"))

  private def loggedError(logs: Chunk[ZTestLogger.LogEntry]) =
    logs.exists(_.logLevel == LogLevel.Error)

  def spec = suite("RollupShutdownSpec")(
    test("RollupJobs.runOnce: pool-closed failure writes no error row and logs no ERROR") {
      (for {
        _      <- cleanDb
        rollup <- ZIO.service[RollupRepo]
        clock  <- ZIO.service[Clock]
        _      <- RollupJobs.runOnce("hourly", rollup, clock, _ => ZIO.fail(poolClosed))
        runs   <- errorRuns(rollup)
        logs   <- ZTestLogger.logOutput
      } yield assertTrue(runs.isEmpty, !loggedError(logs)))
        .provideSome[RollupRepo & Clock & EmbeddedPostgres & TestDatabase.TestDb](
          ZTestLogger.default,
        )
    },
    test("RollupJobs.runOnce: interrupted tick writes no error row and logs no ERROR") {
      (for {
        _      <- cleanDb
        rollup <- ZIO.service[RollupRepo]
        clock  <- ZIO.service[Clock]
        gate   <- Promise.make[Nothing, Unit]
        fiber  <- RollupJobs
          .runOnce("hourly", rollup, clock, _ => gate.succeed(()) *> ZIO.never)
          .fork
        _      <- gate.await
        _      <- fiber.interrupt
        runs   <- errorRuns(rollup)
        logs   <- ZTestLogger.logOutput
      } yield assertTrue(runs.isEmpty, !loggedError(logs)))
        .provideSome[RollupRepo & Clock & EmbeddedPostgres & TestDatabase.TestDb](
          ZTestLogger.default,
        )
    },
    test("RollupJobs.runOnce: real SQL failure logs ERROR and records an error run") {
      (for {
        _      <- cleanDb
        rollup <- ZIO.service[RollupRepo]
        clock  <- ZIO.service[Clock]
        _      <- RollupJobs.runOnce("hourly", rollup, clock, _ => ZIO.fail(realSqlError))
        runs   <- errorRuns(rollup)
        logs   <- ZTestLogger.logOutput
      } yield assertTrue(
        runs.exists(r => r.job == "hourly"),
        loggedError(logs),
      ))
        .provideSome[RollupRepo & Clock & EmbeddedPostgres & TestDatabase.TestDb](
          ZTestLogger.default,
        )
    },
    test("TimeUsedRollupJob.runOnce: pool-closed failure writes no error row and logs no ERROR") {
      (for {
        _      <- cleanDb
        rollup <- ZIO.service[RollupRepo]
        clock  <- ZIO.service[Clock]
        _      <- TimeUsedRollupJob.runOnce(rollup, clock, _ => ZIO.fail(poolClosed))
        runs   <- errorRuns(rollup)
        logs   <- ZTestLogger.logOutput
      } yield assertTrue(runs.isEmpty, !loggedError(logs)))
        .provideSome[RollupRepo & Clock & EmbeddedPostgres & TestDatabase.TestDb](
          ZTestLogger.default,
        )
    },
    test("TimeUsedRollupJob.runOnce: real SQL failure logs ERROR and records an error run") {
      (for {
        _      <- cleanDb
        rollup <- ZIO.service[RollupRepo]
        clock  <- ZIO.service[Clock]
        _      <- TimeUsedRollupJob.runOnce(rollup, clock, _ => ZIO.fail(realSqlError))
        runs   <- errorRuns(rollup)
        logs   <- ZTestLogger.logOutput
      } yield assertTrue(
        runs.exists(r => r.job == "time_used_daily"),
        loggedError(logs),
      ))
        .provideSome[RollupRepo & Clock & EmbeddedPostgres & TestDatabase.TestDb](
          ZTestLogger.default,
        )
    },
  ) @@ TestAspect.sequential
}
