package wifihaven.api.db

import com.zaxxer.hikari.HikariDataSource
import doobie.Transactor
import wifihaven.api.{DbConfig, DbResilienceConfig}
import org.flywaydb.core.Flyway
import zio.*
import zio.interop.catz.*

import java.net.{ConnectException, SocketException}
import java.sql.{SQLException, SQLTransientConnectionException}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{Executors, ThreadFactory}
import scala.annotation.tailrec
import scala.concurrent.ExecutionContext

object Database {

  /**
   * #1243: handle on the live HikariCP pool so the metrics fiber can read `getHikariPoolMXBean`.
   * Surfaced as a service alongside the `Transactor[Task]` (they share the same datasource).
   */
  final case class DbPool(dataSource: HikariDataSource, maxSize: Int)

  private def makeDataSource(cfg: DbConfig): HikariDataSource = {
    val ds = new HikariDataSource()
    ds.setJdbcUrl(s"jdbc:postgresql://${cfg.host}:${cfg.port}/${cfg.database}")
    ds.setUsername(cfg.user)
    ds.setPassword(cfg.password)
    ds.setMaximumPoolSize(cfg.poolSize)
    ds.setMinimumIdle(2)
    ds.setConnectionTimeout(cfg.resilience.connectionTimeoutMillis)
    // #1255: with a negative initializationFailTimeout Hikari creates the pool
    // without probing the DB at construction time and establishes connections
    // lazily on first use. So a DB that is briefly down at boot can never fail
    // pool creation (which would propagate to `main` and exit the JVM) — the
    // first query instead throws a transient error that the startup retry rides
    // out. Once the DB returns, Hikari's housekeeping re-establishes connections
    // automatically; a refused/closed connection is never treated as fatal.
    ds.setInitializationFailTimeout(cfg.resilience.initializationFailTimeoutMillis)
    ds.setIdleTimeout(600000)
    ds.setMaxLifetime(1800000)
    ds
  }

  // #1221: dedicated bounded blocking EC for JDBC connection acquisition.
  // Previously the transactor ran connection-acquisition on
  // `ExecutionContext.global` (ZIO's compute pool); a saturated Hikari pool
  // would then block compute threads waiting on `connectionTimeout`, starving
  // unrelated fibers and amplifying the crash loop. doobie's guidance is a
  // dedicated `connectEC` sized to the JDBC pool: a fixed pool of `poolSize`
  // threads can never await more connections than exist, and the blocking lives
  // off the compute pool. Threads are daemon so a leaked pool can't pin the JVM.
  private def makeConnectEC(poolSize: Int): ZIO[Scope, Nothing, ExecutionContext] =
    ZIO
      .acquireRelease(
        ZIO.succeed {
          val tf = new ThreadFactory {
            private val counter                = new AtomicInteger(0)
            def newThread(r: Runnable): Thread = {
              val t = new Thread(r, s"wifihaven-db-connect-${counter.incrementAndGet()}")
              t.setDaemon(true)
              t
            }
          }
          Executors.newFixedThreadPool(math.max(1, poolSize), tf)
        },
      )(es => ZIO.succeed(es.shutdown()))
      .map(ExecutionContext.fromExecutorService)

  /**
   * Scoped transactor layer. Owns both the Hikari datasource and the dedicated connect EC, closing
   * both on scope teardown so neither thread pool leaks.
   */
  private def acquireDataSource(cfg: DbConfig): ZIO[Scope, Throwable, HikariDataSource] =
    ZIO.acquireRelease(ZIO.attempt(makeDataSource(cfg)))(ds => ZIO.succeed(ds.close()))

  // Outputs both the `Transactor[Task]` and a `DbPool` handle over the same Hikari datasource, so
  // the #1243 pool-metrics fiber can poll the live MXBean without a second datasource.
  val transactorLayer: ZLayer[DbConfig, Throwable, Transactor[Task] & DbPool] =
    ZLayer.scopedEnvironment {
      for {
        cfg       <- ZIO.service[DbConfig]
        connectEC <- makeConnectEC(cfg.poolSize)
        ds        <- acquireDataSource(cfg)
        xa = Transactor.fromDataSource[Task](ds, connectEC)
      } yield ZEnvironment[Transactor[Task]](xa).add[DbPool](DbPool(ds, cfg.poolSize))
    }

  def runMigrations(cfg: DbConfig): Task[Unit] =
    ZIO.attempt {
      Flyway
        .configure()
        .dataSource(
          s"jdbc:postgresql://${cfg.host}:${cfg.port}/${cfg.database}",
          cfg.user,
          cfg.password,
        )
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .load()
        .migrate()
      ()
    }

  /**
   * #1255: is `t` (or anything in its cause chain) a *transient* DB-connectivity failure that
   * warrants a retry — as opposed to a genuine error (bad migration, constraint violation, syntax
   * error) that must fail fast and loud?
   *
   * Transient = the kind of thing a few-second DB restart/failover produces:
   *   - `SQLTransientConnectionException` — Hikari "Connection is not available, request timed out"
   *     when the pool can't hand out a live connection.
   *   - `java.net.ConnectException` — "Connection refused" while the DB is down.
   *   - `java.net.SocketException` — connection reset / socket closed mid-flight.
   *   - any `SQLException` whose SQLState is in the connection class `08*` (PostgreSQL: 08001
   *     unable to connect, 08006 connection failure, …).
   *
   * The cause chain matters because Flyway wraps the PSQLException in a FlywayException, which in
   * turn wraps the underlying ConnectException; the outer type alone tells us nothing.
   */
  def isTransientConnectionError(t: Throwable): Boolean = {
    @tailrec
    def loop(e: Throwable, seen: List[Throwable]): Boolean =
      if (e == null || seen.exists(_ eq e)) false
      else {
        val hit = e match {
          case _: SQLTransientConnectionException => true
          case _: ConnectException                => true
          case _: SocketException                 => true
          case sql: SQLException                  =>
            Option(sql.getSQLState).exists(_.startsWith("08"))
          case _                                  => false
        }
        if (hit) true else loop(e.getCause, e :: seen)
      }
    loop(t, Nil)
  }

  /**
   * #1255: run a DB-heavy startup effect, retrying *only* on transient connection-class failures
   * (see [[isTransientConnectionError]]) with bounded exponential backoff + jitter. A genuine error
   * fails fast on the first attempt; a transient error that outlasts the elapsed budget also fails
   * (the original error is re-raised) rather than retrying forever. The wrapped effect must be
   * idempotent — it is re-run from the start on each retry.
   */
  def withStartupRetry[R, A](r: DbResilienceConfig, label: String)(
      io: ZIO[R, Throwable, A],
  ): ZIO[R, Throwable, A] = {
    // delay = min(exponential growth, maxBackoff), then jittered; the union keeps
    // recurring forever (both sides are infinite) — the actual stop conditions are
    // the intersected schedules below.
    val backoff  =
      (Schedule.exponential(r.startupRetryBase) || Schedule.spaced(
        r.startupRetryMaxBackoff,
      )).jittered
    val schedule =
      backoff &&
        Schedule.recurWhile[Throwable](isTransientConnectionError) &&
        Schedule.upTo(r.startupRetryMaxElapsed)
    io.tapError {
      case t if isTransientConnectionError(t) =>
        ZIO.logWarning(
          s"$label: transient DB connectivity error, retrying with backoff " +
            s"(base=${r.startupRetryBaseMillis}ms, cap=${r.startupRetryMaxBackoffSeconds}s, " +
            s"budget=${r.startupRetryMaxElapsedSeconds}s): " +
            s"${t.getClass.getName}: ${t.getMessage}",
        )
      case _                                  => ZIO.unit
    }.retry(schedule)
  }
}
