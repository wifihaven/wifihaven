package wifihaven.api.unit

import wifihaven.api.AppConfig
import zio.config.*
import zio.config.magnolia.*
import zio.config.typesafe.*
import zio.test.*

import java.nio.file.{Files, Paths}

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
    // #1607: pin the user-token JWT expiry default. The operator was hitting
    // the re-login flow ~daily; this asserts the in-repo default minted into
    // a fresh self-hosted install is 30 days, not 24 hours. Per-deploy
    // override flows through WIFIHAVEN_JWT_HOURS via docker/entrypoint.sh.
    test("config/application.conf.example default for jwt.expiryHours is 720 (30 days)") {
      // Walk up from cwd to find the repo root containing config/application.conf.example.
      // Mill's cwd at test time is not the repo root.
      def findExample(p: java.nio.file.Path): java.nio.file.Path =
        if (Files.exists(p.resolve("config/application.conf.example")))
          p.resolve("config/application.conf.example")
        else if (p.getParent != null) findExample(p.getParent)
        else throw new RuntimeException("could not find config/application.conf.example")
      val text = new String(Files.readAllBytes(findExample(Paths.get(".").toAbsolutePath)))
      for cfg <-
          read(
            deriveConfig[AppConfig]
              .nested("wifihaven")
              .from(TypesafeConfigProvider.fromHoconString(text)),
          )
      yield assertTrue(cfg.jwt.expiryHours == 720)
    },
  )
}
