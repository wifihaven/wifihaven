package wifihaven.api.feature

import wifihaven.api.EmailConfig
import wifihaven.api.db.*
import wifihaven.api.notify.{EmailOutcome, EmailSender, Notifier}
import wifihaven.shared.*
import wifihaven.shared.Clock.TestClock
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.test.*

import java.time.Instant

/**
 * #578 — the deferred email-notification half of the block-page kid→parent request flow. Exercises
 * [[Notifier.EmailNotifier]]: on an access-request alert it resolves the alert's household →
 * `household_settings.notify_email` and sends via [[EmailSender]], config-gated and fail-open.
 *
 * Tests drive the notifier directly (not through AlertRoutes' `forkDaemon` call) so the assertions
 * are deterministic; the route→notifier wiring itself is covered by AlertApiSpec. A recording
 * [[EmailSender]] captures sends without touching the network.
 */
object NotifyEmailSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val mac  = MacAddress.unsafe("aa:bb:cc:11:22:33")
  private val host = Hostname.unsafe("example.com")
  private val now  = Instant.parse("2026-05-07T14:00:00Z")
  // #2266: email is now gated by the EXPLICIT `enabled` flag (not secret presence), so this
  // send-path fixture sets enabled=true alongside the secrets.
  private val cfg  =
    EmailConfig(enabled = true, resendApiKey = "re_test", fromAddress = "WifiHaven <a@b.com>")

  // Seed a kid profile + device (household 1 / Default) and return the alert repo + profile id.
  private def setupKid =
    for {
      _       <- cleanDb
      pRepo   <- ZIO.service[ProfileRepo]
      dRepo   <- ZIO.service[DeviceRepo]
      hsRepo  <- ZIO.service[HouseholdSettingsRepo]
      _       <- hsRepo.ensureDefault(java.time.ZoneId.of("UTC"))
      kidsPid <- TestLayers.seedKidsProfile(pRepo)
      _       <- dRepo.upsert(mac, "kid-laptop", Some(kidsPid), "")
    } yield kidsPid

  private def setNotifyEmail(address: Option[String]) =
    for {
      hsRepo <- ZIO.service[HouseholdSettingsRepo]
      s      <- hsRepo.get
      _      <- hsRepo.update(s.copy(notifyEmail = address))
    } yield ()

  // Create an access-request alert row and read it back (so householdId is populated by the join).
  private def createAlert(m: MacAddress, pid: Option[ProfileId]) =
    for {
      alertRepo <- ZIO.service[AlertRepo]
      id        <- alertRepo.createAccessRequest(
        m,
        pid,
        host,
        AccessRequestKind.Extension,
        Some("homework"),
        now,
      )
      alert     <- alertRepo.findById(id).map(_.get)
    } yield alert

  private def recorder = Ref.make(List.empty[EmailSender.Sent])

  def spec = suite("#578 email notification (Notifier.EmailNotifier)")(
    test("sends to the household notify_email on an access-request alert") {
      for {
        kidsPid <- setupKid
        _       <- setNotifyEmail(Some("parent@example.com"))
        ref     <- recorder
        hsRepo  <- ZIO.service[HouseholdSettingsRepo]
        alert   <- createAlert(mac, Some(kidsPid))
        notifier = Notifier.EmailNotifier(hsRepo, EmailSender.recording(ref), cfg)
        _    <- notifier.alertCreated(alert)
        sent <- ref.get
      } yield assertTrue(sent.size == 1) &&
        assertTrue(sent.head.to == "parent@example.com") &&
        assertTrue(sent.head.subject.contains("Kids")) &&
        // the kid's note is escaped into the HTML body
        assertTrue(sent.head.htmlBody.contains("homework"))
    },
    test("does not send when the household has no notify_email") {
      for {
        kidsPid <- setupKid
        _       <- setNotifyEmail(None)
        ref     <- recorder
        hsRepo  <- ZIO.service[HouseholdSettingsRepo]
        alert   <- createAlert(mac, Some(kidsPid))
        notifier = Notifier.EmailNotifier(hsRepo, EmailSender.recording(ref), cfg)
        _    <- notifier.alertCreated(alert)
        sent <- ref.get
      } yield assertTrue(sent.isEmpty)
    },
    // NB: there is no test for "alert's MAC has no household" — alerts.mac FKs devices(mac)
    // (V37) and devices.household_id is NOT NULL (V65), so a real access-request alert ALWAYS
    // resolves a household. The Notifier's `skipped_no_household` branch is defensive only and
    // can't be reached through the create path (the FK rejects a device-less MAC).
    test("escapes HTML metacharacters in the kid note (no injection into the email body)") {
      for {
        kidsPid   <- setupKid
        _         <- setNotifyEmail(Some("parent@example.com"))
        ref       <- recorder
        hsRepo    <- ZIO.service[HouseholdSettingsRepo]
        alertRepo <- ZIO.service[AlertRepo]
        id        <- alertRepo.createAccessRequest(
          mac,
          Some(kidsPid),
          host,
          AccessRequestKind.Exemption,
          Some("<script>alert(1)</script>"),
          now,
        )
        alert     <- alertRepo.findById(id).map(_.get)
        notifier = Notifier.EmailNotifier(hsRepo, EmailSender.recording(ref), cfg)
        _    <- notifier.alertCreated(alert)
        sent <- ref.get
      } yield assertTrue(sent.size == 1) &&
        assertTrue(!sent.head.htmlBody.contains("<script>")) &&
        assertTrue(sent.head.htmlBody.contains("&lt;script&gt;"))
    },
    test("EmailSender.layer yields the no-op Disabled sender when unconfigured") {
      for {
        sender <- ZIO
          .service[EmailSender]
          .provide(EmailSender.layer, ZLayer.succeed(EmailConfig()))
        out    <- sender.send("x@y.com", "s", "<p>b</p>")
      } yield assertTrue(out == EmailOutcome.Disabled)
    },
    test("EmailSender.layer yields a live sender when both secrets are set") {
      for {
        sender <- ZIO
          .service[EmailSender]
          .provide(EmailSender.layer, ZLayer.succeed(cfg))
      } yield assertTrue(sender.isInstanceOf[EmailSender.Resend])
    },
  ) @@ TestAspect.sequential
}
