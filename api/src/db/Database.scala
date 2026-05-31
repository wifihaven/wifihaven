package wifihaven.api.db

import com.zaxxer.hikari.HikariDataSource
import doobie.Transactor
import wifihaven.api.DbConfig
import org.flywaydb.core.Flyway
import zio.*
import zio.interop.catz.*

object Database {

  def makeTransactor(cfg: DbConfig): Transactor[Task] = {
    val ds = new HikariDataSource()
    ds.setJdbcUrl(s"jdbc:postgresql://${cfg.host}:${cfg.port}/${cfg.database}")
    ds.setUsername(cfg.user)
    ds.setPassword(cfg.password)
    ds.setMaximumPoolSize(cfg.poolSize)
    ds.setMinimumIdle(2)
    ds.setConnectionTimeout(30000)
    ds.setIdleTimeout(600000)
    ds.setMaxLifetime(1800000)
    // #1197: do not block JVM startup on a successful first connection. If
    // Postgres is cold at boot, Hikari's default initializationFailTimeout=1
    // would attempt-and-fail-fast which is fine, but the route handler path
    // is what eventually retries. Setting -1 explicitly documents that
    // pool init is best-effort and never gates port-bind.
    ds.setInitializationFailTimeout(-1)
    Transactor.fromDataSource[Task](ds, scala.concurrent.ExecutionContext.global)
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
