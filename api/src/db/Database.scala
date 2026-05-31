package wifihaven.api.db

import com.zaxxer.hikari.HikariDataSource
import doobie.Transactor
import wifihaven.api.DbConfig
import org.flywaydb.core.Flyway
import zio.*
import zio.interop.catz.*

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{Executors, ThreadFactory}
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
    ds.setConnectionTimeout(30000)
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
}
