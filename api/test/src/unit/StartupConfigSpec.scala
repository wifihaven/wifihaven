package wifihaven.api.unit

import wifihaven.api.*
import zio.*
import zio.config.*
import zio.config.magnolia.*
import zio.config.typesafe.*
import zio.test.*

/**
 * #2266 (no-dark-by-default): pin the startup-validation + feature-report framework.
 *
 *   - REQUIRED config fails boot LOUDLY, and [[AppConfig.validateRequired]] accumulates EVERY
 *     violation in one pass (doc rule 1 + rule 4) rather than aborting on the first.
 *   - GENUINELY-OPTIONAL, config-gated features stay optional but their disabled state is made
 *     EXPLICIT + OBSERVABLE via [[StartupFeatureReport]] (doc rule 3): each feature reports
 *     enabled/disabled with a detail string that NAMES the keys behind the decision, so "disabled
 *     because nobody set the key" is diagnosable from one boot.
 *
 * See docs/process/no-dark-by-default.md.
 */
object StartupConfigSpec extends ZIOSpecDefault {

  // Minimal valid HOCON with pluggable jwt secret and optional extra feature blocks.
  private def hocon(jwtSecret: String, extraBlocks: String = ""): String =
    s"""wifihaven {
       |  db   { host = "localhost", port = 5432, database = "wifihaven", user = "wifihaven", password = "changeme", poolSize = 5 }
       |  http { host = "0.0.0.0", port = 8080, staticDir = "web/dist", serveSpa = true }
       |  jwt  { secret = "$jwtSecret", expiryHours = 24 }
       |  cors { allowedOrigins = "" }
       |$extraBlocks}""".stripMargin

  private def loadVia(text: String): IO[Config.Error, AppConfig] =
    read(
      deriveConfig[AppConfig]
        .nested("wifihaven")
        .from(TypesafeConfigProvider.fromHoconString(text)),
    )

  // Decode-then-validate, mirroring what AppConfig.layer does at boot.
  private def bootValidate(text: String): IO[Config.Error, AppConfig] =
    loadVia(text).flatMap(AppConfig.validateRequiredZIO)

  private val goodSecret = "test-secret-at-least-32-chars!!x"

  def spec = suite("StartupConfigSpec")(
    suite("required config fails loud + accumulates all (rule 1 + rule 4)")(
      test("a short AND placeholder jwt secret reports BOTH errors in one pass") {
        // "change-this-please" is < 32 chars AND is the shipped placeholder → two distinct violations.
        val cfg = loadVia(hocon("change-this-please"))
        cfg.map { c =>
          val errs = AppConfig.validateRequired(c)
          assertTrue(
            errs.length == 2,
            errs.exists(_.contains("at least 32 characters")),
            errs.exists(_.contains("placeholder")),
          )
        }
      },
      test("a valid config yields no required-config errors") {
        loadVia(hocon(goodSecret)).map(c => assertTrue(AppConfig.validateRequired(c).isEmpty))
      },
      test("boot validation FAILS (crashes) on an invalid required config, naming the key") {
        bootValidate(hocon("short")).exit.map {
          case Exit.Failure(cause) =>
            val msg = cause.failureOption.map(_.getMessage).getOrElse("")
            assertTrue(msg.contains("wifihaven.jwt.secret"), msg.contains("at least 32 characters"))
          case Exit.Success(_)     =>
            assertTrue(false) // boot must NOT succeed with an invalid required config
        }
      },
      test("boot validation SUCCEEDS on a valid required config") {
        bootValidate(hocon(goodSecret)).exit.map(ex => assertTrue(ex.isSuccess))
      },
    ),
    suite("optional features are observable — enabled/disabled + reason (rule 3)")(
      test("email disabled when no key: named + reason surfaces the unset keys") {
        loadVia(hocon(goodSecret)).map { c =>
          val states = StartupFeatureReport.states(c)
          val email  = states.find(_.name == "email-notifications").get
          assertTrue(
            !email.enabled,
            email.detail.contains("resendApiKey"),
            email.detail.contains("fromAddress"),
          )
        }
      },
      test("email enabled when both secrets present") {
        val blocks =
          """  email { resendApiKey = "re_x", fromAddress = "WifiHaven <a@wifihaven.net>" }
            |""".stripMargin
        loadVia(hocon(goodSecret, blocks)).map { c =>
          val email = StartupFeatureReport.states(c).find(_.name == "email-notifications").get
          assertTrue(email.enabled)
        }
      },
      test("stripe billing disabled when secretKey unset, enabled when set") {
        val off = loadVia(hocon(goodSecret))
        val on  = loadVia(
          hocon(
            goodSecret,
            """  stripe { secretKey = "sk_test_x" }
                                              |""".stripMargin,
          ),
        )
        for {
          coff <- off
          con  <- on
        } yield {
          val soff = StartupFeatureReport.states(coff).find(_.name == "stripe-billing").get
          val son  = StartupFeatureReport.states(con).find(_.name == "stripe-billing").get
          assertTrue(!soff.enabled, soff.detail.contains("secretKey"), son.enabled)
        }
      },
      test("support widget + write API each reported independently") {
        loadVia(hocon(goodSecret)).map { c =>
          val states = StartupFeatureReport.states(c)
          assertTrue(
            states.exists(s => s.name == "support-widget" && !s.enabled),
            states.exists(s => s.name == "support-write-api" && !s.enabled),
          )
        }
      },
      test("every optional feature the audit named has a state entry") {
        loadVia(hocon(goodSecret)).map { c =>
          val names = StartupFeatureReport.states(c).map(_.name).toSet
          assertTrue(
            Set(
              "email-notifications",
              "stripe-billing",
              "support-widget",
              "support-write-api",
              "metrics-endpoint",
              "metrics-scrape-token",
              "cors",
              "ws-origin-enforcement",
              "ui-allowed-hosts",
              "debug-endpoints",
              "seed-test-blocklists",
              "loki-log-export",
            ).subsetOf(names),
          )
        }
      },
      test("log emits one line per feature without failing") {
        for {
          c <- loadVia(hocon(goodSecret))
          _ <- StartupFeatureReport.log(c, lokiConfigured = false)
        } yield assertTrue(true)
      },
    ),
  )
}
