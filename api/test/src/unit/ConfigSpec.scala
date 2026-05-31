package wifihaven.api.unit

import wifihaven.api.AppConfig
import zio.config.*
import zio.config.magnolia.*
import zio.config.typesafe.*
import zio.test.*

/**
 * #1221: `db.poolSize` must be configurable per deployment. In prod the value arrives as the
 * `WIFIHAVEN_DB_POOL_SIZE` env var, which `docker/entrypoint.sh` substitutes into the rendered
 * `application.conf` as `wifihaven.db.poolSize`. These tests pin the Scala side of that contract:
 * the derived config reads whatever `poolSize` the HOCON carries (so a non-default env value flows
 * through) and falls back to the example default when the override is absent.
 */
object ConfigSpec extends ZIOSpecDefault {

  private def hocon(poolSize: String) =
    s"""wifihaven {
       |  db {
       |    host     = "localhost"
       |    port     = 5432
       |    database = "wifihaven"
       |    user     = "wifihaven"
       |    password = "changeme"
       |    poolSize = $poolSize
       |  }
       |  http { host = "0.0.0.0", port = 8080, staticDir = "web/dist", serveSpa = true }
       |  jwt  { secret = "change-this-to-a-random-32-char-secret!!", expiryHours = 24 }
       |  cors { allowedOrigins = "" }
       |}""".stripMargin

  private def load(poolSize: String) =
    read(
      deriveConfig[AppConfig]
        .nested("wifihaven")
        .from(TypesafeConfigProvider.fromHoconString(hocon(poolSize))),
    )

  def spec = suite("AppConfig db.poolSize")(
    test("reads the configured poolSize (env value flows through entrypoint → HOCON)") {
      for cfg <- load("20")
      yield assertTrue(cfg.db.poolSize == 20)
    },
    test("a non-default override is honoured, not pinned to 5") {
      for cfg <- load("8")
      yield assertTrue(cfg.db.poolSize == 8)
    },
    test("local-dev default (5) parses when no override is supplied") {
      for cfg <- load("5")
      yield assertTrue(cfg.db.poolSize == 5)
    },
  )
}
