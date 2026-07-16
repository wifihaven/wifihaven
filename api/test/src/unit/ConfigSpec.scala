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
       |  jwt  { secret = "test-secret-at-least-32-chars!!x", expiryHours = 24 }
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
      val rawText = new String(Files.readAllBytes(findExample(Paths.get(".").toAbsolutePath)))
      // #2084: JwtConfig now rejects the shipped placeholder secret (by design — it must
      // never be used as-is). This test only cares about the expiryHours default, so swap
      // in a compliant dummy secret rather than relaxing the guard the placeholder itself
      // is there to trip.
      val text    =
        rawText.replaceAll(
          """secret\s*=\s*"change-this[^"]*"""",
          """secret = "test-secret-at-least-32-chars!!x"""",
        )
      for cfg <-
          read(
            deriveConfig[AppConfig]
              .nested("wifihaven")
              .from(TypesafeConfigProvider.fromHoconString(text)),
          )
      yield assertTrue(cfg.jwt.expiryHours == 720)
    },
  ) + suite("#2250 per-env SPA app-base")(
    // The bug: on staging, an approved beta invite link pointed at the PROD SPA
    // (app.wifihaven.net/welcome?…) because BetaConfig.inviteBaseUrl had no per-env
    // env binding and fell through to the prod default. entrypoint.sh now renders a
    // `beta {}` block whose inviteBaseUrl derives from the shared WIFIHAVEN_APP_BASE_URL
    // (staging → app-staging.wifihaven.net). These pin the Scala side of that contract:
    // whatever host the HOCON carries flows through to the derived URLs, and the
    // in-repo code defaults stay prod so self-hosted/unset is unchanged.
    test(
      "staging-configured beta inviteBaseUrl → inviteUrl resolves to the staging SPA, not prod",
    ) {
      for cfg <- loadApp(appBase(Some("https://app-staging.wifihaven.net")))
      yield {
        val url = cfg.beta.inviteUrl("tok123")
        assertTrue(
          url == "https://app-staging.wifihaven.net/welcome?token=tok123",
          !url.contains("app.wifihaven.net/welcome"),
        )
      }
    },
    test("staging email appBaseUrl → alert/notifier dashboardUrl resolves to staging, not prod") {
      for cfg <- loadApp(appBase(Some("https://app-staging.wifihaven.net")))
      yield assertTrue(cfg.email.dashboardUrl == "https://app-staging.wifihaven.net")
    },
    test("staging stripe appBaseUrl → Checkout return URL resolves to staging") {
      for cfg <- loadApp(appBase(Some("https://app-staging.wifihaven.net")))
      yield assertTrue(
        cfg.stripe.checkoutSuccessUrl == "https://app-staging.wifihaven.net/billing?checkout=success",
      )
    },
    test("code defaults (self-hosted/unset) stay on the prod apex — back-compat unchanged") {
      for cfg <- loadApp(appBase(None))
      yield assertTrue(
        cfg.beta.inviteUrl("t") == "https://app.wifihaven.net/welcome?token=t",
        cfg.email.dashboardUrl == "https://app.wifihaven.net",
        cfg.stripe.checkoutSuccessUrl == "https://app.wifihaven.net/billing?checkout=success",
      )
    },
  )

  // Minimal valid HOCON; when `base` is set, the beta/email/stripe blocks all carry that host
  // (mirroring entrypoint.sh deriving each from the shared WIFIHAVEN_APP_BASE_URL). When None,
  // the blocks are omitted so the case-class defaults (the prod apex) apply.
  private def appBase(base: Option[String]): String = {
    val blocks = base.fold("") { b =>
      s"""  beta   { inviteBaseUrl = "$b" }
         |  email  { resendApiKey = "k", fromAddress = "WifiHaven <a@wifihaven.net>", appBaseUrl = "$b" }
         |  stripe { secretKey = "sk_test_x", appBaseUrl = "$b" }
         |""".stripMargin
    }
    s"""wifihaven {
       |  db   { host = "localhost", port = 5432, database = "wifihaven", user = "wifihaven", password = "changeme", poolSize = 5 }
       |  http { host = "0.0.0.0", port = 8080, staticDir = "web/dist", serveSpa = true }
       |  jwt  { secret = "test-secret-at-least-32-chars!!x", expiryHours = 24 }
       |  cors { allowedOrigins = "" }
       |$blocks}""".stripMargin
  }

  private def loadApp(text: String) =
    read(
      deriveConfig[AppConfig]
        .nested("wifihaven")
        .from(TypesafeConfigProvider.fromHoconString(text)),
    )
}
