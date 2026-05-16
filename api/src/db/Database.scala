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
