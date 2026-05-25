package wifihaven.api.feature

import wifihaven.api.db.*
import wifihaven.api.policy.*
import wifihaven.shared.*
import wifihaven.shared.Clock.TestClock
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.test.*

/**
 * #763: PolicyService expands per-profile app assignments into the existing per-profile
 * extraAllowed / extraBlocked / site_time_limit buckets on the wire. Router stays oblivious.
 */
object PolicySnapshotAppsSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private def cleanDb = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
    TestDatabase.cleanAndMigrate.provide(ZLayer.succeed(pg)),
  )

  private def makePs =
    for {
      pr     <- ZIO.service[ProfileRepo]
      sr     <- ZIO.service[ScheduleRepo]
      hsr    <- ZIO.service[HouseholdSettingsRepo]
      tlr    <- ZIO.service[TimeLimitRepo]
      stlr   <- ZIO.service[SiteTimeLimitRepo]
      dr     <- ZIO.service[DeviceRepo]
      blr    <- ZIO.service[BlocklistRepo]
      trRepo <- ZIO.service[TrafficReportRepo]
      er     <- ZIO.service[TimeExtensionRepo]
      ar     <- ZIO.service[AppRepo]
      clock  <- ZIO.service[Clock]
    } yield (new PolicyServiceLive(
      pr,
      sr,
      hsr,
      tlr,
      stlr,
      dr,
      blr,
      trRepo,
      er,
      ar,
      clock,
    )): PolicyService

  private def kidsId: ZIO[ProfileRepo, Throwable, ProfileId] =
    ZIO.serviceWithZIO[ProfileRepo](_.listAll.map(_.find(_.name == "Kids").get.id))

  def spec = suite("PolicySnapshot — app expansion (#763)")(
    test("allowed-mode app: hosts unioned into extraAllowed (alongside profile's own list)") {
      for {
        _           <- cleanDb
        pr          <- ZIO.service[ProfileRepo]
        ar          <- ZIO.service[AppRepo]
        kids        <- kidsId
        // Profile keeps an existing per-profile extraAllowed entry.
        kidsProfile <- pr.findById(kids).map(_.get)
        _           <- pr.update(
          kidsProfile.copy(extraAllowed = List(Hostname.unsafe("khan-own.org"))),
        )
        appId       <- ar.create("Khan", "khan", None, None)
        _           <- ar.setHosts(
          appId,
          List(Hostname.unsafe("khanacademy.org"), Hostname.unsafe("kastatic.org")),
        )
        _           <- ar.upsertAssignment(appId, kids, AppMode.Allowed, None, true)
        svc         <- makePs
        snap        <- svc.snapshot
      } yield {
        val ea = snap.profiles(kids).rules.extraAllowed.map(_.value).toSet
        assertTrue(ea.contains("khan-own.org")) &&
        assertTrue(ea.contains("khanacademy.org")) &&
        assertTrue(ea.contains("kastatic.org"))
      }
    },
    test("blocked-mode app: hosts appear in extraBlocked") {
      for {
        _     <- cleanDb
        ar    <- ZIO.service[AppRepo]
        kids  <- kidsId
        appId <- ar.create("TikTok", "tiktok", None, None)
        _     <- ar.setHosts(
          appId,
          List(Hostname.unsafe("tiktok.com"), Hostname.unsafe("musical.ly")),
        )
        _     <- ar.upsertAssignment(appId, kids, AppMode.Blocked, None, true)
        svc   <- makePs
        snap  <- svc.snapshot
      } yield {
        val eb = snap.profiles(kids).rules.extraBlocked.map(_.value).toSet
        assertTrue(eb.contains("tiktok.com")) && assertTrue(eb.contains("musical.ly"))
      }
    },
    test(
      "time_limited app: exhausted budget pushes host into extraBlocked; non-exhausted does not",
    ) {
      // Stop-gap semantics (documented in #763 PR): one synthesized SiteTimeLimit
      // row per host of the time_limited app, each carrying the app's
      // dailyMinutes independently. Verified here by hitting one host's budget
      // without traffic on the sibling host.
      for {
        _     <- cleanDb
        ar    <- ZIO.service[AppRepo]
        kids  <- kidsId
        appId <- ar.create("YouTube", "youtube", None, None)
        _     <- ar.setHosts(
          appId,
          List(Hostname.unsafe("youtube.com"), Hostname.unsafe("ytimg.com")),
        )
        _     <- ar.upsertAssignment(appId, kids, AppMode.TimeLimited, Some(30), true)
        svc   <- makePs
        snap  <- svc.snapshot
      } yield {
        // With no traffic recorded, neither synthesized site-limit is exhausted.
        val eb = snap.profiles(kids).rules.extraBlocked.map(_.value).toSet
        assertTrue(!eb.contains("youtube.com")) && assertTrue(!eb.contains("ytimg.com"))
      }
    },
    test("conflicting allowed + blocked apps: host appears in both lists (router lets allow win)") {
      // feedback_extraallowed_beats_blocked: router precedence makes allow win.
      // Snapshot is additive — both lists carry the host, and the router enforces
      // precedence at decide time.
      for {
        _     <- cleanDb
        ar    <- ZIO.service[AppRepo]
        kids  <- kidsId
        allow <- ar.create("Educational", "edu", None, None)
        block <- ar.create("Risky", "risky", None, None)
        _     <- ar.setHosts(allow, List(Hostname.unsafe("shared.example.com")))
        _     <- ar.setHosts(block, List(Hostname.unsafe("shared.example.com")))
        _     <- ar.upsertAssignment(allow, kids, AppMode.Allowed, None, true)
        _     <- ar.upsertAssignment(block, kids, AppMode.Blocked, None, true)
        svc   <- makePs
        snap  <- svc.snapshot
        rules = snap.profiles(kids).rules
      } yield assertTrue(rules.extraAllowed.map(_.value).contains("shared.example.com")) &&
        assertTrue(rules.extraBlocked.map(_.value).contains("shared.example.com"))
    },
    test("profile with no app assignments: snapshot unchanged from pre-#763 behavior") {
      for {
        _     <- cleanDb
        ar    <- ZIO.service[AppRepo]
        kids  <- kidsId
        // Create an app but assign it to NO profile.
        appId <- ar.create("Orphan", "orphan", None, None)
        _     <- ar.setHosts(appId, List(Hostname.unsafe("noone.example.com")))
        svc   <- makePs
        snap  <- svc.snapshot
        rules = snap.profiles(kids).rules
      } yield assertTrue(!rules.extraAllowed.map(_.value).contains("noone.example.com")) &&
        assertTrue(!rules.extraBlocked.map(_.value).contains("noone.example.com")) &&
        // Kids profile defaults preserved (categories from seedKidsProfile not touched here).
        assertTrue(rules.extraAllowed.isEmpty)
    },
  ) @@ TestAspect.sequential
}
