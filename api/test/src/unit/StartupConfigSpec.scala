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
      test(
        "#2265: support.responderEnabled=true with the chain missing crashes boot, naming EVERY gap",
      ) {
        // The load-bearing junction: an explicitly-enabled support responder makes its whole config
        // chain required, and those gaps flow into the CANONICAL accumulator (not a bespoke one) so
        // boot fails loudly listing them all. A regression dropping the support hook from
        // validateRequired makes this test fail — the silent-no-op class #2265 exists to kill.
        val cfg = loadVia(
          hocon(
            goodSecret,
            """  support { responderEnabled = true }
                                              |""".stripMargin,
          ),
        )
        cfg.flatMap { c =>
          val errs = AppConfig.validateRequired(c)
          bootValidate(
            hocon(
              goodSecret,
              """  support { responderEnabled = true }
                                           |""".stripMargin,
            ),
          ).exit.map { ex =>
            val bootMsg = ex match {
              case Exit.Failure(cause) => cause.failureOption.map(_.getMessage).getOrElse("")
              case Exit.Success(_)     => ""
            }
            assertTrue(
              // The gaps accumulate (jwt is valid here, so ONLY the support keys appear).
              errs.exists(_.contains("support.plain.apiKey")),
              errs.exists(_.contains("support.anthropicApiKey")),
              errs.exists(_.contains("support.claudeAgentId")),
              errs.exists(_.contains("support.claudeEnvironmentId")),
              errs.exists(_.contains("support.agentTokenSecret")),
              errs.exists(_.contains("support.deploymentEnv")),
              // #2437: the escalation label is part of that chain — without it an escalated thread is
              // indistinguishable from an AI-resolved one in the Plain inbox, so it must fail boot
              // rather than degrade to an invisible handoff.
              errs.exists(_.contains("support.plain.escalationLabelTypeId")),
              // …and boot actually CRASHES with them (not just a pure-list check).
              ex.isFailure,
              bootMsg.contains("support.claudeAgentId"),
            )
          }
        }
      },
      test("#2265: support.responderEnabled=false requires no support keys — boot succeeds") {
        // Off is the default posture; a disabled responder must never make any support key required.
        bootValidate(
          hocon(
            goodSecret,
            """  support { responderEnabled = false }
                                         |""".stripMargin,
          ),
        ).exit.map(ex => assertTrue(ex.isSuccess))
      },
      test("#2265: support.responderEnabled=true with the FULL chain set boots clean") {
        val full =
          """  support {
            |    responderEnabled = true
            |    plain { apiKey = "k", webhookSecret = "w", escalationLabelTypeId = "lt_x" }
            |    anthropicApiKey = "sk"
            |    claudeAgentId = "agent_x", claudeEnvironmentId = "env_x"
            |    agentTokenSecret = "secret-at-least-32-chars-long!!x", deploymentEnv = "staging"
            |  }
            |""".stripMargin
        bootValidate(hocon(goodSecret, full)).exit.map(ex => assertTrue(ex.isSuccess))
      },
      test("#2429: the Plain widget on without support.emailAddress crashes boot, naming the key") {
        // A deployment that ships the chat widget has a hosted support desk, so it MUST publish the
        // inbox the SPA renders (#2429). Missing ⇒ fail loud, not a silently-absent support line.
        val widgetNoEmail =
          """  support {
            |    plain { widgetEnabled = true, appId = "app_x", identitySecret = "s" }
            |  }
            |""".stripMargin
        for {
          c  <- loadVia(hocon(goodSecret, widgetNoEmail))
          ex <- bootValidate(hocon(goodSecret, widgetNoEmail)).exit
          errs = AppConfig.validateRequired(c)
        } yield assertTrue(
          errs.exists(_.contains("wifihaven.support.emailAddress")),
          // The message must name the flag that ACTUALLY required the key — the responder /
          // issue-filing wording would send an operator to the wrong switch.
          errs.exists(_.contains("wifihaven.support.plain.widgetEnabled=true")),
          !errs.exists(e =>
            e.contains("emailAddress") && e.contains("support responder / issue filing"),
          ),
          ex.isFailure,
        )
      },
      test("#2429: the Plain widget on WITH support.emailAddress boots clean") {
        val widgetWithEmail =
          """  support {
            |    plain { widgetEnabled = true, appId = "app_x", identitySecret = "s" }
            |    emailAddress = "support@staging.wifihaven.net"
            |  }
            |""".stripMargin
        bootValidate(hocon(goodSecret, widgetWithEmail)).exit.map(ex => assertTrue(ex.isSuccess))
      },
      test("#2429: with the widget off, support.emailAddress is not required (self-hosted)") {
        bootValidate(
          hocon(
            goodSecret,
            """  support { plain { widgetEnabled = false } }
              |""".stripMargin,
          ),
        ).exit.map(ex => assertTrue(ex.isSuccess))
      },
      test("#2437: email on + a responder on with NO operator mailbox crashes boot") {
        // The escalation notice is emailed to ONE operator mailbox. With the transport enabled and a
        // responder live, an unset mailbox would silently drop every escalation — the exact failure
        // #2437 fixes — so it is a REQUIRED key, not a degrade-to-nothing.
        val noOperator =
          """  email { enabled = true, resendApiKey = "re_x", fromAddress = "alerts@x.test" }
            |  support {
            |    responderEnabled = true
            |    plain { apiKey = "k", webhookSecret = "w", escalationLabelTypeId = "lt_x" }
            |    anthropicApiKey = "sk"
            |    claudeAgentId = "agent_x", claudeEnvironmentId = "env_x"
            |    agentTokenSecret = "secret-at-least-32-chars-long!!x", deploymentEnv = "staging"
            |  }
            |""".stripMargin
        for {
          errs <- loadVia(hocon(goodSecret, noOperator)).map(AppConfig.validateRequired)
          ex   <- bootValidate(hocon(goodSecret, noOperator)).exit
          withOperator = noOperator.replace(
            """fromAddress = "alerts@x.test" }""",
            """fromAddress = "alerts@x.test", operatorAddress = "ops@x.test" }""",
          )
          okEx <- bootValidate(hocon(goodSecret, withOperator)).exit
        } yield assertTrue(
          errs.exists(_.contains("wifihaven.email.operatorAddress")),
          ex.isFailure,
          // …and setting the mailbox is all that was missing.
          okEx.isSuccess,
        )
      },
      test("#2437: with the email transport explicitly OFF no operator mailbox is required") {
        // A self-hosted install that sends no email cannot misaddress a notice; that disabled state is
        // named + reported by StartupFeatureReport rather than being an unlabelled silent branch.
        val emailOff =
          """  support {
            |    responderEnabled = true
            |    plain { apiKey = "k", webhookSecret = "w", escalationLabelTypeId = "lt_x" }
            |    anthropicApiKey = "sk"
            |    claudeAgentId = "agent_x", claudeEnvironmentId = "env_x"
            |    agentTokenSecret = "secret-at-least-32-chars-long!!x", deploymentEnv = "staging"
            |  }
            |""".stripMargin
        bootValidate(hocon(goodSecret, emailOff)).exit.map(ex => assertTrue(ex.isSuccess))
      },
    ),
    suite("optional features are observable — enabled/disabled + reason (rule 3)")(
      test("email disabled by default: reason names the explicit flag (#2266)") {
        // #2266: the disabled reason is now the EXPLICIT flag (enabled=false), not "keys unset" —
        // the flag, not secret-absence, is what turns email off.
        loadVia(hocon(goodSecret)).map { c =>
          val states = StartupFeatureReport.states(c)
          val email  = states.find(_.name == "email-notifications").get
          assertTrue(
            !email.enabled,
            email.detail.contains("wifihaven.email.enabled=false"),
          )
        }
      },
      test("email enabled when the explicit flag is set (#2266 — not derived from secrets)") {
        val blocks =
          """  email { enabled = true, resendApiKey = "re_x", fromAddress = "WifiHaven <a@wifihaven.net>" }
            |""".stripMargin
        loadVia(hocon(goodSecret, blocks)).map { c =>
          val email = StartupFeatureReport.states(c).find(_.name == "email-notifications").get
          assertTrue(email.enabled)
        }
      },
      test("stripe billing off by default, on when the explicit flag is set (#2266)") {
        val off = loadVia(hocon(goodSecret))
        val on  = loadVia(
          hocon(
            goodSecret,
            """  stripe { enabled = true, secretKey = "sk_test_x" }
                                              |""".stripMargin,
          ),
        )
        for {
          coff <- off
          con  <- on
        } yield {
          val soff = StartupFeatureReport.states(coff).find(_.name == "stripe-billing").get
          val son  = StartupFeatureReport.states(con).find(_.name == "stripe-billing").get
          assertTrue(!soff.enabled, soff.detail.contains("enabled=false"), son.enabled)
        }
      },
      test("support widget + write API + responder + issue-filing each reported independently") {
        loadVia(hocon(goodSecret)).map { c =>
          val states = StartupFeatureReport.states(c)
          assertTrue(
            states.exists(s => s.name == "support-widget" && !s.enabled),
            states.exists(s => s.name == "support-write-api" && !s.enabled),
            // #2265: the explicit-flag features report their (default-off) state too.
            states.exists(s => s.name == "support-responder" && !s.enabled),
            states.exists(s => s.name == "support-issue-filing" && !s.enabled),
          )
        }
      },
      test("#2429: the support address reports its state — unset names the key + the consequence") {
        // Rule 3: "no support line in the SPA" must be an OBSERVABLE off state, not an unlabeled
        // silent branch derived from an empty string.
        for {
          off <- loadVia(hocon(goodSecret))
          on  <- loadVia(
            hocon(
              goodSecret,
              """  support { emailAddress = "support@staging.wifihaven.net" }
                |""".stripMargin,
            ),
          )
          offState = StartupFeatureReport.states(off).find(_.name == "support-email")
          onState  = StartupFeatureReport.states(on).find(_.name == "support-email")
        } yield assertTrue(
          offState.exists(s => !s.enabled && s.detail.contains("wifihaven.support.emailAddress")),
          offState.exists(_.detail.contains("no support line")),
          onState.exists(s => s.enabled && s.detail.contains("support@staging.wifihaven.net")),
        )
      },
      test("support responder/issue-filing report ENABLED when their #2265 flags are true") {
        val on =
          """  support { responderEnabled = true, issueFilingEnabled = true }
            |""".stripMargin
        loadVia(hocon(goodSecret, on)).map { c =>
          val states = StartupFeatureReport.states(c)
          assertTrue(
            states.exists(s => s.name == "support-responder" && s.enabled),
            states.exists(s => s.name == "support-issue-filing" && s.enabled),
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
              "support-email",
              "support-write-api",
              "support-responder",
              // #2437: the escalation NOTICE channel is its own reported state — with the email
              // transport off a support escalation still labels the Plain thread but emails nobody,
              // and that degraded shape must be named rather than inferred from two other flags.
              "escalation-notices",
              "support-issue-filing",
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
    suite("#2266 metrics.requireToken — cloud-only fail-loud when the scrape token is missing")(
      test("requireToken=true + enabled + empty scrapeToken → one boot error naming the key") {
        val errs = MetricsConfig.validate(
          MetricsConfig(enabled = true, scrapeToken = "", requireToken = true),
        )
        assertTrue(
          errs.length == 1,
          errs.head.contains("wifihaven.metrics.scrapeToken"),
          errs.head.contains("requireToken"),
        )
      },
      test("requireToken=true + scrapeToken present → no error") {
        assertTrue(
          MetricsConfig
            .validate(MetricsConfig(enabled = true, scrapeToken = "tok", requireToken = true))
            .isEmpty,
        )
      },
      test("requireToken=false + empty scrapeToken → no error (self-hosted loopback default)") {
        assertTrue(
          MetricsConfig
            .validate(MetricsConfig(enabled = true, scrapeToken = "", requireToken = false))
            .isEmpty,
        )
      },
      test("metrics disabled → requireToken is moot even with an empty token") {
        assertTrue(
          MetricsConfig
            .validate(MetricsConfig(enabled = false, scrapeToken = "", requireToken = true))
            .isEmpty,
        )
      },
      test("validateRequired ACCUMULATES the metrics error alongside a bad jwt secret") {
        // Bad JWT (short) + metrics requireToken-with-no-token → both surface in one pass (rule 4).
        val cfg  = AppConfig(
          db = DbConfig("localhost", 5432, "wifihaven", "wifihaven", "changeme", 5),
          http = HttpConfig("0.0.0.0", 8080, "web/dist", serveSpa = true),
          jwt = JwtConfig("short", 24),
          cors = CorsConfig(""),
          metrics = MetricsConfig(enabled = true, scrapeToken = "", requireToken = true),
        )
        val errs = AppConfig.validateRequired(cfg)
        assertTrue(
          errs.exists(_.contains("wifihaven.jwt.secret")),
          errs.exists(_.contains("wifihaven.metrics.scrapeToken")),
          errs.length == 2,
        )
      },
      test("feature report's metrics-scrape-token detail names requireToken") {
        val on = StartupFeatureReport
          .states(
            AppConfig(
              db = DbConfig("localhost", 5432, "wifihaven", "wifihaven", "changeme", 5),
              http = HttpConfig("0.0.0.0", 8080, "web/dist", serveSpa = true),
              jwt = JwtConfig(goodSecret, 24),
              cors = CorsConfig(""),
              metrics = MetricsConfig(enabled = true, scrapeToken = "tok", requireToken = true),
            ),
          )
          .find(_.name == "metrics-scrape-token")
          .get
        assertTrue(on.enabled, on.detail.contains("requireToken"))
      },
    ),
    suite("#2266 email + stripe — explicit `enabled` flag replaces secret-presence derivation")(
      test("email: enabled=true + a missing secret fails boot, naming the gap(s)") {
        val errs = EmailConfig.validate(EmailConfig(enabled = true, resendApiKey = "re_x"))
        assertTrue(errs.length == 1, errs.head.contains("wifihaven.email.fromAddress"))
      },
      test("email: enabled=true + both secrets present → no error") {
        assertTrue(
          EmailConfig
            .validate(EmailConfig(enabled = true, resendApiKey = "re_x", fromAddress = "a@b.co"))
            .isEmpty,
        )
      },
      test("email: enabled=false → no secret required even if unset (deliberate off)") {
        assertTrue(EmailConfig.validate(EmailConfig(enabled = false)).isEmpty)
      },
      test("email: enabled is the explicit flag, NOT derived from secret presence") {
        // secrets present but flag false → OFF; this is the whole point of the conversion.
        assertTrue(
          !EmailConfig(enabled = false, resendApiKey = "re_x", fromAddress = "a@b.co").enabled,
          EmailConfig(enabled = true, resendApiKey = "re_x", fromAddress = "a@b.co").enabled,
        )
      },
      test("stripe: enabled=true + empty secretKey fails boot; enabled=false never does") {
        assertTrue(
          StripeConfig
            .validate(StripeConfig(enabled = true, secretKey = ""))
            .exists(
              _.contains("wifihaven.stripe.secretKey"),
            ),
          StripeConfig
            .validate(StripeConfig(enabled = true, secretKey = "sk_x", webhookSecret = "whsec_x"))
            .isEmpty,
          StripeConfig.validate(StripeConfig(enabled = false, secretKey = "")).isEmpty,
        )
      },
      // #2414: billing could boot with enabled=true and NO webhookSecret, and then EVERY Stripe
      // webhook silently no-opped while returning HTTP 200 (BillingService.handleWebhook →
      // WebhookOutcome.NotConfigured → Response.ok), so Stripe marked delivery successful, never
      // retried, and no subscription state ever advanced. Boot-checkable prerequisite ⇒ fail HARD
      // at startup (no-dark-by-default rule 1), not a runtime log.
      test("stripe: enabled=true + empty webhookSecret fails boot (#2414)") {
        val errs = StripeConfig.validate(StripeConfig(enabled = true, secretKey = "sk_x"))
        assertTrue(errs.exists(_.contains("wifihaven.stripe.webhookSecret")))
      },
      test("stripe: a blank-but-nonempty webhookSecret is still a gap (#2414)") {
        assertTrue(
          StripeConfig
            .validate(StripeConfig(enabled = true, secretKey = "sk_x", webhookSecret = "   "))
            .exists(_.contains("wifihaven.stripe.webhookSecret")),
        )
      },
      test("stripe: enabled=false never requires webhookSecret (deliberate off) (#2414)") {
        assertTrue(StripeConfig.validate(StripeConfig(enabled = false, webhookSecret = "")).isEmpty)
      },
      test("stripe: BOTH secret gaps are reported in one pass (rule 4) (#2414)") {
        val errs     = StripeConfig.validate(StripeConfig(enabled = true))
        // Assert each expected key is named and that nothing UNEXPECTED is reported, rather than
        // pinning an exact count — if priceMonthly/priceAnnual are ever promoted to required this
        // still holds without a test edit (a test edit forced by a later change hides regressions).
        val expected = Set("wifihaven.stripe.secretKey", "wifihaven.stripe.webhookSecret")
        assertTrue(
          expected.forall(k => errs.exists(_.contains(k))),
          errs.forall(e => expected.exists(e.contains)),
          errs.distinct.size == errs.size, // no key reported twice
        )
      },
      test("stripe: enabled is the explicit flag, NOT derived from secretKey") {
        assertTrue(
          !StripeConfig(enabled = false, secretKey = "sk_x").enabled,
          StripeConfig(enabled = true, secretKey = "sk_x").enabled,
        )
      },
      test("validateRequired accumulates email + stripe gaps alongside a bad jwt (rule 4)") {
        val cfg  = AppConfig(
          db = DbConfig("localhost", 5432, "wifihaven", "wifihaven", "changeme", 5),
          http = HttpConfig("0.0.0.0", 8080, "web/dist", serveSpa = true),
          jwt = JwtConfig("short", 24),
          cors = CorsConfig(""),
          email = EmailConfig(enabled = true),  // both secrets missing
          stripe = StripeConfig(enabled = true),// both stripe secrets missing
        )
        val errs = AppConfig.validateRequired(cfg)
        assertTrue(
          errs.exists(_.contains("wifihaven.jwt.secret")),
          errs.exists(_.contains("wifihaven.email.")),
          errs.exists(_.contains("wifihaven.stripe.secretKey")),
          // #2414: the webhookSecret gap accumulates here too rather than short-circuiting.
          errs.exists(_.contains("wifihaven.stripe.webhookSecret")),
          errs.length >= 4,
        )
      },
      test("feature report reflects the explicit email/stripe flags") {
        def rep(email: EmailConfig, stripe: StripeConfig) =
          StartupFeatureReport.states(
            AppConfig(
              db = DbConfig("localhost", 5432, "wifihaven", "wifihaven", "changeme", 5),
              http = HttpConfig("0.0.0.0", 8080, "web/dist", serveSpa = true),
              jwt = JwtConfig(goodSecret, 24),
              cors = CorsConfig(""),
              email = email,
              stripe = stripe,
            ),
          )
        val on                                            = rep(
          EmailConfig(enabled = true, resendApiKey = "re_x", fromAddress = "a@b.co"),
          // #2414: a billing-on config the boot gate would actually ACCEPT — before, this fixture
          // left webhookSecret unset, a state validateRequired now makes impossible at runtime.
          StripeConfig(enabled = true, secretKey = "sk_x", webhookSecret = "whsec_x"),
        )
        val off = rep(EmailConfig(enabled = false), StripeConfig(enabled = false))
        assertTrue(
          on.find(_.name == "email-notifications").get.enabled,
          on.find(_.name == "stripe-billing").get.enabled,
          !off.find(_.name == "email-notifications").get.enabled,
          !off.find(_.name == "stripe-billing").get.enabled,
          off.find(_.name == "stripe-billing").get.detail.contains("enabled=false"),
          // #2414: the enabled detail names BOTH required secrets, so the reported posture matches
          // the gate that guarantees it (StripeConfig.validate).
          on.find(_.name == "stripe-billing").get.detail.contains("secretKey"),
          on.find(_.name == "stripe-billing").get.detail.contains("webhookSecret"),
        )
      },
    ),
    suite("#2266 support.plain widget/write — explicit flags (nested PlainConfig)")(
      test("widgetEnabled=true requires appId + identitySecret; names each gap") {
        val errs = PlainConfig.validate(PlainConfig(widgetEnabled = true))
        assertTrue(
          errs.exists(_.contains("wifihaven.support.plain.appId")),
          errs.exists(_.contains("wifihaven.support.plain.identitySecret")),
          errs.length == 2,
        )
      },
      test("widgetEnabled=true with both set → no error") {
        assertTrue(
          PlainConfig
            .validate(PlainConfig(widgetEnabled = true, appId = "app", identitySecret = "sec"))
            .isEmpty,
        )
      },
      test("writeEnabled=true requires apiKey; enabled=false never does") {
        assertTrue(
          PlainConfig
            .validate(PlainConfig(writeEnabled = true))
            .exists(
              _.contains("wifihaven.support.plain.apiKey"),
            ),
          PlainConfig.validate(PlainConfig(writeEnabled = true, apiKey = "k")).isEmpty,
          PlainConfig.validate(PlainConfig(writeEnabled = false)).isEmpty,
        )
      },
      test("widgetEnabled/writeEnabled are EXPLICIT flags, not derived from secret presence") {
        // secrets present but flags false → OFF (the point of the conversion).
        val off = PlainConfig(
          widgetEnabled = false,
          writeEnabled = false,
          apiKey = "k",
          appId = "app",
          identitySecret = "sec",
        )
        assertTrue(!off.widgetEnabled, !off.writeEnabled)
      },
      test("validateRequired accumulates support.plain gaps alongside a bad jwt (rule 4)") {
        val cfg  = AppConfig(
          db = DbConfig("localhost", 5432, "wifihaven", "wifihaven", "changeme", 5),
          http = HttpConfig("0.0.0.0", 8080, "web/dist", serveSpa = true),
          jwt = JwtConfig("short", 24),
          cors = CorsConfig(""),
          support = SupportConfig(plain = PlainConfig(widgetEnabled = true, writeEnabled = true)),
        )
        val errs = AppConfig.validateRequired(cfg)
        assertTrue(
          errs.exists(_.contains("wifihaven.jwt.secret")),
          errs.exists(_.contains("wifihaven.support.plain.appId")),
          errs.exists(_.contains("wifihaven.support.plain.apiKey")),
        )
      },
      test("feature report reflects the explicit plain flags") {
        def rep(plain: PlainConfig) =
          StartupFeatureReport
            .states(
              AppConfig(
                db = DbConfig("localhost", 5432, "wifihaven", "wifihaven", "changeme", 5),
                http = HttpConfig("0.0.0.0", 8080, "web/dist", serveSpa = true),
                jwt = JwtConfig(goodSecret, 24),
                cors = CorsConfig(""),
                support = SupportConfig(plain = plain),
              ),
            )
        val on                      = rep(
          PlainConfig(
            widgetEnabled = true,
            writeEnabled = true,
            apiKey = "k",
            appId = "app",
            identitySecret = "sec",
          ),
        )
        val off                     = rep(PlainConfig())
        assertTrue(
          on.find(_.name == "support-widget").get.enabled,
          on.find(_.name == "support-write-api").get.enabled,
          !off.find(_.name == "support-widget").get.enabled,
          !off.find(_.name == "support-write-api").get.enabled,
          off.find(_.name == "support-widget").get.detail.contains("widgetEnabled=false"),
        )
      },
    ),
  )
}
