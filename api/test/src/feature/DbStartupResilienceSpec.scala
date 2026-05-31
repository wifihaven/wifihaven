package wifihaven.api.feature

import doobie.*
import doobie.implicits.*
import wifihaven.api.DbResilienceConfig
import wifihaven.api.db.*
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.*
import zio.interop.catz.*
import zio.test.*

import java.net.ConnectException
import java.sql.{SQLException, SQLTransientConnectionException}

/**
 * #1255: a transient DB outage (a few-second restart during a resize/failover) must not take the
 * API process down. The DB-heavy startup init has to retry connection-class failures with bounded
 * backoff and come up once the DB returns, while a *genuine* error (bad migration, bad SQL,
 * constraint violation) still fails fast instead of retrying forever.
 *
 * These tests drive the real `Database.withStartupRetry` helper against the embedded Postgres: real
 * PSQLExceptions on the failure paths, a real `SELECT 1` once the simulated outage clears.
 */
object DbStartupResilienceSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Transactor[Task]] {

  override val bootstrap = TestDatabase.layer

  // Tight budget so the suite stays fast; the production defaults are minutes.
  private val fastConfig = DbResilienceConfig(
    startupRetryBaseMillis = 5,
    startupRetryMaxBackoffSeconds = 1,
    startupRetryMaxElapsedSeconds = 10,
  )

  // PSQLException carries SQLState; emulate the connection class (08*) and a
  // non-connection (genuine) class without reaching for the driver internals.
  private def sqlState(state: String): SQLException =
    new SQLException(s"simulated SQLState=$state", state)

  def spec = suite("DbStartupResilienceSpec")(
    test("transient connection failures retry, then startup completes once the DB is reachable") {
      for {
        xa       <- ZIO.service[Transactor[Task]]
        attempts <- Ref.make(0)
        // Fails the first 3 attempts with a Hikari-style transient error, then
        // actually runs a real query against the embedded DB ("DB came back").
        init = attempts.updateAndGet(_ + 1).flatMap { n =>
          if (n <= 3)
            ZIO.fail(
              new SQLTransientConnectionException("HikariPool-1 - Connection is not available"),
            )
          else
            sql"SELECT 1".query[Int].unique.transact(xa)
        }
        result <- Database.withStartupRetry(fastConfig, "test init")(init)
        count  <- attempts.get
      } yield assertTrue(result == 1, count == 4)
    } @@ TestAspect.withLiveClock,
    test("a genuine (non-connection) SQL error fails fast without retrying") {
      for {
        attempts <- Ref.make(0)
        // 42P01 = undefined_table: a real bug, must NOT be retried.
        init = attempts.update(_ + 1) *> ZIO.fail(sqlState("42P01"))
        exit  <- Database.withStartupRetry(fastConfig, "test init")(init).exit
        count <- attempts.get
      } yield assertTrue(exit.isFailure, count == 1)
    } @@ TestAspect.withLiveClock,
    test("a real undefined-table query error fails fast (not treated as transient)") {
      for {
        xa       <- ZIO.service[Transactor[Task]]
        attempts <- Ref.make(0)
        init = attempts.update(_ + 1) *>
          sql"SELECT 1 FROM table_that_does_not_exist".query[Int].unique.transact(xa)
        exit  <- Database.withStartupRetry(fastConfig, "test init")(init).exit
        count <- attempts.get
      } yield assertTrue(exit.isFailure, count == 1)
    } @@ TestAspect.withLiveClock,
    test("a persistent transient failure eventually fails once the elapsed budget is spent") {
      for {
        attempts <- Ref.make(0)
        cfg  = fastConfig.copy(startupRetryMaxElapsedSeconds = 1)
        init = attempts.update(_ + 1) *>
          ZIO.fail(new ConnectException("Connection refused"))
        exit  <- Database.withStartupRetry(cfg, "test init")(init).exit
        count <- attempts.get
      } yield assertTrue(exit.isFailure, count > 1)
    } @@ TestAspect.withLiveClock,
    suite("isTransientConnectionError classification")(
      test("Hikari SQLTransientConnectionException is transient") {
        assertTrue(
          Database.isTransientConnectionError(
            new SQLTransientConnectionException("Connection is not available"),
          ),
        )
      },
      test("ConnectException (connection refused) is transient") {
        assertTrue(Database.isTransientConnectionError(new ConnectException("Connection refused")))
      },
      test("SQLState 08006 (connection failure) is transient") {
        assertTrue(Database.isTransientConnectionError(sqlState("08006")))
      },
      test("a nested ConnectException under a wrapper is transient") {
        val wrapped =
          new RuntimeException("Flyway failed", new ConnectException("Connection refused"))
        assertTrue(Database.isTransientConnectionError(wrapped))
      },
      test("constraint violation (23505) is NOT transient") {
        assertTrue(!Database.isTransientConnectionError(sqlState("23505")))
      },
      test("undefined table (42P01) is NOT transient") {
        assertTrue(!Database.isTransientConnectionError(sqlState("42P01")))
      },
      test("a plain RuntimeException is NOT transient") {
        assertTrue(!Database.isTransientConnectionError(new RuntimeException("boom")))
      },
    ),
  )
}
