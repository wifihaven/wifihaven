package wifihaven.api.feature

import wifihaven.api.{JwtConfig, PressOutreachConfig}
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.notify.EmailSender
import wifihaven.api.press.PressOutreach
import wifihaven.api.press.PressOutreach.Contact
import wifihaven.api.routes.PressOutreachRoutes
import wifihaven.shared.Clock
import wifihaven.shared.Clock.TestClock
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import doobie.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*

/**
 * #2233 — the operator-only press-OUTREACH send endpoints, end-to-end through the HTTP stack with a
 * RECORDING [[EmailSender]] (never a live Resend) and the Live press_messages ledger (embedded
 * Postgres, no repo mocks). Pins the guard chain that makes an outward-facing send safe:
 *   - preview/send are operator-gated: 401 unauth, 404 for a non-operator household, 404 when the
 *     feature is dark;
 *   - preview is a pure dry-run — the recorder proves nothing is transmitted;
 *   - send REFUSES without confirm:true, and REFUSES while a fill token is unresolved;
 *   - a confirmed send transmits + records to the ledger, and a re-run is idempotent (skips peers
 *     already recorded), so a partial batch resumes without double-sending.
 */
object PressOutreachRouteSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val macA = MacAddress.unsafe("aa:bb:cc:00:00:2a")
  private val macB = MacAddress.unsafe("aa:bb:cc:00:00:2b")

  private val jwt = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)

  private val cfgOn  =
    PressOutreachConfig(
      enabled = true,
      fromAddress = "WifiHaven Press <press@wifihaven.net>",
      replyTo = "press@wifihaven.net",
      perSendDelayMillis = 0,
    )
  private val cfgOff = PressOutreachConfig(enabled = false)

  private val template =
    """# fill legend
      |---
      |FOR IMMEDIATE RELEASE — WifiHaven beta opens {{date}} at {{betaSignupUrl}}.
      |""".stripMargin

  private val contacts = List(
    Contact("alpha", "Outlet Alpha", "Alice", 1, "your OpenWRT beat", None, Some("https://a/tip")),
    Contact("bravo", "Outlet Bravo", "Bob", 2, "your firewall videos", None, Some("https://b/tip")),
  )
  private val fullFill = """{"date":"July 20, 2026","betaSignupUrl":"https://wifihaven.net/beta"}"""
  private val overrides =
    """"emailOverrides":{"alpha":"alice@outlet.example","bravo":"bob@outlet.example"}"""

  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      hr    <- ZIO.service[HouseholdRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwt, clock, hr): AuthService

  private def login(
      auth: AuthService,
      user: String,
      pw: String,
      slug: Option[String] = None,
  ): Task[String] =
    auth
      .login(slug.fold(user)(s => s"$s/$user"), pw)
      .mapError(e => new RuntimeException(s"login failed: $e"))
      .map(_.token.value)

  private def routesWith(
      auth: AuthService,
      cfg: PressOutreachConfig,
      sender: EmailSender,
      pressLog: PressMessageRepo,
      emailEnabled: Boolean = true,
  ): Routes[Any, Response] =
    PressOutreachRoutes.routes(auth, cfg, emailEnabled, sender, pressLog, contacts, template)

  private def post(
      routes: Routes[Any, Response],
      path: String,
      token: Option[String],
      body: String,
  ): Task[(Status, String)] = {
    val base = Request.post(URL.decode(path).toOption.get, Body.fromString(body))
    val req  = token.fold(base)(t => base.addHeader(Header.Authorization.Bearer(t)))
    routes.runZIO(req).flatMap(r => r.body.asString.map((r.status, _)))
  }

  private val preview = "/api/press/outreach/preview"
  private val send    = "/api/press/outreach/send"

  def spec = suite("Press outreach send endpoints (#2233)")(
    test("preview: 401 unauth; 404 for a non-operator household") {
      for {
        _    <- cleanDb
        two  <- TestLayers.seedTwoHouseholds(macA, macB)
        pl   <- ZIO.service[PressMessageRepo]
        ref  <- Ref.make(List.empty[EmailSender.Sent])
        auth <- makeAuth
        routes = routesWith(auth, cfgOn, EmailSender.recording(ref), pl)
        (unauth, _) <- post(routes, preview, None, "{}")
        tokB        <- login(auth, two.adminB, two.password, Some(two.slugB))
        (hhB, _)    <- post(routes, preview, Some(tokB), "{}")
      } yield assertTrue(unauth == Status.Unauthorized, hhB == Status.NotFound)
    },
    test("preview: an operator admin gets a dry-run — nothing is transmitted") {
      for {
        _    <- cleanDb
        _    <- TestLayers.seedTwoHouseholds(macA, macB)
        pl   <- ZIO.service[PressMessageRepo]
        ref  <- Ref.make(List.empty[EmailSender.Sent])
        auth <- makeAuth
        tok  <- login(auth, "admin", "changeme")
        routes = routesWith(auth, cfgOn, EmailSender.recording(ref), pl)
        (st, body) <- post(routes, preview, Some(tok), s"""{$overrides,"fill":$fullFill}""")
        rpt = body.fromJson[PressOutreach.Report].toOption.get
        sent <- ref.get
      } yield assertTrue(
        st == Status.Ok,
        rpt.mode == "preview",
        rpt.results.forall(_.outcome == "dry_run"),
        rpt.emails.nonEmpty, // preview returns the rendered emails to review
        sent.isEmpty,
      )
    },
    test("send: 404 when the feature is dark (pressOutreach.enabled=false)") {
      for {
        _    <- cleanDb
        _    <- TestLayers.seedTwoHouseholds(macA, macB)
        pl   <- ZIO.service[PressMessageRepo]
        ref  <- Ref.make(List.empty[EmailSender.Sent])
        auth <- makeAuth
        tok  <- login(auth, "admin", "changeme")
        routes = routesWith(auth, cfgOff, EmailSender.recording(ref), pl)
        (st, _) <- post(
          routes,
          send,
          Some(tok),
          s"""{"confirm":true,"fill":$fullFill,$overrides}""",
        )
      } yield assertTrue(st == Status.NotFound)
    },
    test("send: 400 without confirm:true, and 400 while a fill token is unresolved") {
      for {
        _    <- cleanDb
        _    <- TestLayers.seedTwoHouseholds(macA, macB)
        pl   <- ZIO.service[PressMessageRepo]
        ref  <- Ref.make(List.empty[EmailSender.Sent])
        auth <- makeAuth
        tok  <- login(auth, "admin", "changeme")
        routes = routesWith(auth, cfgOn, EmailSender.recording(ref), pl)
        (noConfirm, _)  <- post(routes, send, Some(tok), s"""{"fill":$fullFill,$overrides}""")
        (unresolved, _) <- post(
          routes,
          send,
          Some(tok),
          s"""{"confirm":true,"fill":{"date":"x"},$overrides}""",
        )
        sent            <- ref.get
      } yield assertTrue(
        noConfirm == Status.BadRequest,
        unresolved == Status.BadRequest,
        sent.isEmpty, // neither refused request transmitted anything
      )
    },
    test("send: a confirmed send transmits, records to the ledger, and re-runs idempotently") {
      for {
        _    <- cleanDb
        _    <- TestLayers.seedTwoHouseholds(macA, macB)
        pl   <- ZIO.service[PressMessageRepo]
        ref  <- Ref.make(List.empty[EmailSender.Sent])
        auth <- makeAuth
        tok  <- login(auth, "admin", "changeme")
        routes = routesWith(auth, cfgOn, EmailSender.recording(ref), pl)
        (st1, b1) <- post(
          routes,
          send,
          Some(tok),
          s"""{"confirm":true,"fill":$fullFill,$overrides}""",
        )
        rpt1 = b1.fromJson[PressOutreach.Report].toOption.get
        afterFirst <- ref.get
        ledger     <- pl.outboundPeers()
        // Re-run the SAME send: idempotency must skip both peers (already recorded).
        _          <- ref.set(Nil)
        (st2, b2)  <- post(
          routes,
          send,
          Some(tok),
          s"""{"confirm":true,"fill":$fullFill,$overrides}""",
        )
        rpt2 = b2.fromJson[PressOutreach.Report].toOption.get
        afterSecond <- ref.get
      } yield assertTrue(
        st1 == Status.Ok,
        rpt1.results.forall(_.outcome == "sent"),
        afterFirst.map(_.to).sorted == List("alice@outlet.example", "bob@outlet.example"),
        afterFirst.forall(_.replyTo.contains("press@wifihaven.net")),
        ledger == Set("alice@outlet.example", "bob@outlet.example"),
        st2 == Status.Ok,
        rpt2.results.forall(_.outcome == "skipped_already_sent"),
        afterSecond.isEmpty, // the resume sent NOTHING
      )
    },
  ) @@ TestAspect.sequential
}
