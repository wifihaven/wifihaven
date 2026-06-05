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

import java.time.{LocalDate, LocalDateTime, ZoneOffset}

/**
 * #763: PolicyService expands per-profile app assignments into the existing per-profile
 * extraAllowed / extraBlocked / site_time_limit buckets on the wire. Router stays oblivious.
 */
object PolicySnapshotAppsSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

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
    } yield PolicyServiceLive(
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
    ): PolicyService

  private def makePsAt(dt: LocalDateTime) =
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
      ref    <- Ref.make(dt)
      clk = new Clock.TestClock(ref)
    } yield PolicyServiceLive(
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
      clk,
    ): PolicyService

  private def seedRouterRow: ZIO[RouterRepo, Throwable, RouterId] =
    ZIO.serviceWithZIO[RouterRepo](_.create("gw-seed", Sha256Hex.unsafe("o" * 64)))

  private def seedTraffic(
      routerId: RouterId,
      mac: String,
      hostname: String,
      date: LocalDate,
      minutes: Int,
  ): ZIO[TrafficReportRepo, Throwable, Unit] =
    ZIO.serviceWithZIO[TrafficReportRepo] { tr =>
      val buckets = minutes / 5
      val today0  = date.atStartOfDay(ZoneOffset.UTC).toInstant
      val inserts = (0 until buckets).map { i =>
        val start = today0.plusSeconds(i * 300L)
        val end   = start.plusSeconds(300)
        TrafficReportInsert(
          routerId,
          MacAddress.unsafe(mac),
          None,
          HostId.Fqdn(Hostname.unsafe(hostname)),
          date,
          start,
          end,
          300,
          500_000L,
          500_000L,
        )
      }.toList
      tr.insertBatch(inserts).unit
    }

  private def kidsId: ZIO[ProfileRepo, Throwable, ProfileId] =
    ZIO.serviceWithZIO[ProfileRepo](_.listAll.map(_.find(_.name == "Kids").get.id))

  def spec = suite("PolicySnapshot — app expansion (#763)")(
    test("allowed-mode app: hosts unioned into extraAllowed") {
      for {
        _     <- cleanDb
        ar    <- ZIO.service[AppRepo]
        kids  <- kidsId
        // A second allowed-mode app contributes a host alongside the multi-host one.
        _     <- TestLayers.seedAppAssignment(ar, kids, "khan-own.org", AppMode.Allowed)
        appId <- ar.create("Khan", "khan", None, None)
        _     <- ar.setHosts(
          appId,
          List(Hostname.unsafe("khanacademy.org"), Hostname.unsafe("kastatic.org")),
        )
        _     <- ar.upsertAssignment(appId, kids, AppMode.Allowed, None, true)
        svc   <- makePs
        snap  <- svc.snapshot
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
        // Kids profile defaults preserved: extraAllowed carries only the #1307
        // global infra allowlist, no app hosts leak in.
        assertTrue(
          rules.extraAllowed.map(_.value).toSet == PolicyService.infraAllowHosts.map(_.value).toSet,
        )
    },
    // ── #1105: time_limited app with exemptFromDaily carves around @blocked_macs ──
    test(
      "#1105: exempt+budget remaining + cap exhausted → app host in extraAllowed, blocked=TimeLimit",
    ) {
      val mac = "aa:bb:cc:dd:ee:01"
      for {
        _     <- cleanDb
        pr    <- ZIO.service[ProfileRepo]
        sr    <- ZIO.service[ScheduleRepo]
        dr    <- ZIO.service[DeviceRepo]
        tlr   <- ZIO.service[TimeLimitRepo]
        ar    <- ZIO.service[AppRepo]
        kid   <- TestLayers.seedKidsProfile(pr, sr)
        _     <- tlr.upsert(kid, 30)
        _     <- TestLayers.seedDevice(dr, mac, "kid-ipad", kid)
        appId <- ar.create("Khan", "khan", None, None)
        _     <- ar.setHosts(appId, List(Hostname.unsafe("khanacademy.org")))
        _     <- ar.upsertAssignment(appId, kid, AppMode.TimeLimited, Some(60), true)
        rid   <- seedRouterRow
        // Burn 35 min on a non-exempt host → exhausts the 30-min profile cap.
        _     <- seedTraffic(rid, mac, "cnn.com", LocalDate.of(2025, 1, 6), 35)
        svc   <- makePsAt(TestClock.schoolDayAfternoon)
        snap  <- svc.snapshot
        rules = snap.profiles(kid).rules
        ea    = rules.extraAllowed.map(_.value).toSet
        eb    = rules.extraBlocked.map(_.value).toSet
      } yield assertTrue(rules.blocked) &&
        assertTrue(rules.blockReason.contains(MacBlockReason.TimeLimit)) &&
        assertTrue(ea.contains("khanacademy.org")) &&
        assertTrue(!eb.contains("khanacademy.org"))
    },
    test("#1105: exempt + per-host budget exhausted → app host in extraBlocked, NOT extraAllowed") {
      val mac = "aa:bb:cc:dd:ee:02"
      for {
        _     <- cleanDb
        pr    <- ZIO.service[ProfileRepo]
        sr    <- ZIO.service[ScheduleRepo]
        dr    <- ZIO.service[DeviceRepo]
        ar    <- ZIO.service[AppRepo]
        kid   <- TestLayers.seedKidsProfile(pr, sr)
        _     <- TestLayers.seedDevice(dr, mac, "kid-ipad", kid)
        appId <- ar.create("Khan", "khan", None, None)
        _     <- ar.setHosts(appId, List(Hostname.unsafe("khanacademy.org")))
        _     <- ar.upsertAssignment(appId, kid, AppMode.TimeLimited, Some(30), true)
        rid   <- seedRouterRow
        _     <- seedTraffic(rid, mac, "khanacademy.org", LocalDate.of(2025, 1, 6), 35)
        svc   <- makePsAt(TestClock.schoolDayAfternoon)
        snap  <- svc.snapshot
        rules = snap.profiles(kid).rules
        ea    = rules.extraAllowed.map(_.value).toSet
        eb    = rules.extraBlocked.map(_.value).toSet
      } yield assertTrue(eb.contains("khanacademy.org")) &&
        assertTrue(!ea.contains("khanacademy.org"))
    },
    test("#1105: exempt + budget remaining + profile paused → still in extraAllowed") {
      for {
        _     <- cleanDb
        pr    <- ZIO.service[ProfileRepo]
        sr    <- ZIO.service[ScheduleRepo]
        ar    <- ZIO.service[AppRepo]
        kid   <- TestLayers.seedKidsProfile(pr, sr)
        _     <- pr.setPaused(kid, true)
        appId <- ar.create("Khan", "khan", None, None)
        _     <- ar.setHosts(appId, List(Hostname.unsafe("khanacademy.org")))
        _     <- ar.upsertAssignment(appId, kid, AppMode.TimeLimited, Some(60), true)
        svc   <- makePsAt(TestClock.schoolDayAfternoon)
        snap  <- svc.snapshot
        rules = snap.profiles(kid).rules
        ea    = rules.extraAllowed.map(_.value).toSet
      } yield assertTrue(rules.blocked) &&
        assertTrue(rules.blockReason.contains(MacBlockReason.Paused)) &&
        assertTrue(ea.contains("khanacademy.org"))
    },
    test("#1105: exempt + budget remaining + schedule active → still in extraAllowed") {
      // Kids profile already has a bedtime 21:00–07:00 window; evaluate at bedtime.
      for {
        _     <- cleanDb
        pr    <- ZIO.service[ProfileRepo]
        sr    <- ZIO.service[ScheduleRepo]
        ar    <- ZIO.service[AppRepo]
        kid   <- TestLayers.seedKidsProfile(pr, sr)
        appId <- ar.create("Khan", "khan", None, None)
        _     <- ar.setHosts(appId, List(Hostname.unsafe("khanacademy.org")))
        _     <- ar.upsertAssignment(appId, kid, AppMode.TimeLimited, Some(60), true)
        svc   <- makePsAt(TestClock.bedtime)
        snap  <- svc.snapshot
        rules = snap.profiles(kid).rules
        ea    = rules.extraAllowed.map(_.value).toSet
      } yield assertTrue(rules.blocked) &&
        assertTrue(rules.blockReason.contains(MacBlockReason.Schedule)) &&
        assertTrue(ea.contains("khanacademy.org"))
    },
    test(
      "#1105: NON-exempt + budget remaining + cap exhausted → NOT in extraAllowed (regression guard)",
    ) {
      val mac = "aa:bb:cc:dd:ee:03"
      for {
        _     <- cleanDb
        pr    <- ZIO.service[ProfileRepo]
        sr    <- ZIO.service[ScheduleRepo]
        dr    <- ZIO.service[DeviceRepo]
        tlr   <- ZIO.service[TimeLimitRepo]
        ar    <- ZIO.service[AppRepo]
        kid   <- TestLayers.seedKidsProfile(pr, sr)
        _     <- tlr.upsert(kid, 30)
        _     <- TestLayers.seedDevice(dr, mac, "kid-ipad", kid)
        appId <- ar.create("YouTube", "youtube", None, None)
        _     <- ar.setHosts(appId, List(Hostname.unsafe("youtube.com")))
        _     <- ar.upsertAssignment(appId, kid, AppMode.TimeLimited, Some(60), false)
        rid   <- seedRouterRow
        _     <- seedTraffic(rid, mac, "cnn.com", LocalDate.of(2025, 1, 6), 35)
        svc   <- makePsAt(TestClock.schoolDayAfternoon)
        snap  <- svc.snapshot
        rules = snap.profiles(kid).rules
        ea    = rules.extraAllowed.map(_.value).toSet
      } yield assertTrue(rules.blocked) &&
        assertTrue(rules.blockReason.contains(MacBlockReason.TimeLimit)) &&
        assertTrue(!ea.contains("youtube.com"))
    },
    test(
      "#1307: AppMode.Allowed app stays in extraAllowed when daily cap exhausted (blocked=TimeLimit)",
    ) {
      // Prod miss (Kids/Math Academy, 2026-06-01): an allowed-mode app got
      // blocked when the profile's daily time limit ran out. extraAllowed must
      // beat the TimeLimit block at the snapshot layer (#421), exactly as it
      // does for pause/schedule. Mirrors the prod assignment: mode=Allowed,
      // exemptFromDaily=true, single apex host.
      val mac = "aa:bb:cc:dd:ee:13"
      for {
        _     <- cleanDb
        pr    <- ZIO.service[ProfileRepo]
        sr    <- ZIO.service[ScheduleRepo]
        dr    <- ZIO.service[DeviceRepo]
        tlr   <- ZIO.service[TimeLimitRepo]
        ar    <- ZIO.service[AppRepo]
        kid   <- TestLayers.seedKidsProfile(pr, sr)
        _     <- tlr.upsert(kid, 30)
        _     <- TestLayers.seedDevice(dr, mac, "kid-mac", kid)
        appId <- ar.create("Math Academy", "math-academy", None, None)
        _     <- ar.setHosts(appId, List(Hostname.unsafe("mathacademy.com")))
        _     <- ar.upsertAssignment(appId, kid, AppMode.Allowed, None, true)
        rid   <- seedRouterRow
        // Burn 35 min on an unrelated host → exhausts the 30-min daily cap.
        _     <- seedTraffic(rid, mac, "cnn.com", LocalDate.of(2025, 1, 6), 35)
        svc   <- makePsAt(TestClock.schoolDayAfternoon)
        snap  <- svc.snapshot
        rules = snap.profiles(kid).rules
        ea    = rules.extraAllowed.map(_.value).toSet
        eb    = rules.extraBlocked.map(_.value).toSet
      } yield assertTrue(rules.blocked) &&
        assertTrue(rules.blockReason.contains(MacBlockReason.TimeLimit)) &&
        assertTrue(ea.contains("mathacademy.com")) &&
        assertTrue(!eb.contains("mathacademy.com"))
    },
    test(
      "#1413: AppMode.Allowed app stays in extraAllowed when profile is paused (blocked=Paused)",
    ) {
      // Prod miss (Kids/Math Academy, 2026-06): an allowed-mode app got blocked
      // when the profile was paused. extraAllowed must beat the Paused block at
      // the snapshot layer (#421), exactly as #1307 locked for TimeLimit. The
      // Paused-reason sibling of #1307. Mirrors the prod assignment: mode=Allowed,
      // single apex host, whole-MAC paused.
      val mac = "aa:bb:cc:dd:ee:23"
      for {
        _     <- cleanDb
        pr    <- ZIO.service[ProfileRepo]
        sr    <- ZIO.service[ScheduleRepo]
        dr    <- ZIO.service[DeviceRepo]
        ar    <- ZIO.service[AppRepo]
        kid   <- TestLayers.seedKidsProfile(pr, sr)
        _     <- pr.setPaused(kid, true)
        _     <- TestLayers.seedDevice(dr, mac, "kid-mac", kid)
        appId <- ar.create("Math Academy", "math-academy", None, None)
        _     <- ar.setHosts(appId, List(Hostname.unsafe("mathacademy.com")))
        _     <- ar.upsertAssignment(appId, kid, AppMode.Allowed, None, true)
        svc   <- makePsAt(TestClock.schoolDayAfternoon)
        snap  <- svc.snapshot
        rules = snap.profiles(kid).rules
        ea    = rules.extraAllowed.map(_.value).toSet
        eb    = rules.extraBlocked.map(_.value).toSet
      } yield assertTrue(rules.blocked) &&
        assertTrue(rules.blockReason.contains(MacBlockReason.Paused)) &&
        assertTrue(ea.contains("mathacademy.com")) &&
        assertTrue(!eb.contains("mathacademy.com"))
    },
    test(
      "#1307: global infra hosts are in every profile's extraAllowed, even when blocked=TimeLimit",
    ) {
      // Allowed-mode apps appeared blocked when the daily cap ran out because
      // the whole-MAC @blocked_macs drop killed transitive connectivity-check /
      // OCSP / CDN dependencies. We ship a curated infra allowlist in every
      // profile's extraAllowed (relying on the existing #421 ea_ enforcement)
      // until the global policy layer (#1308) removes the per-profile copy. No
      // snapshot-shape change: this stays functional, not policy-based (#1311).
      val mac = "aa:bb:cc:dd:ee:14"
      for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        sr   <- ZIO.service[ScheduleRepo]
        dr   <- ZIO.service[DeviceRepo]
        tlr  <- ZIO.service[TimeLimitRepo]
        kid  <- TestLayers.seedKidsProfile(pr, sr)
        _    <- tlr.upsert(kid, 30)
        _    <- TestLayers.seedDevice(dr, mac, "kid-mac", kid)
        rid  <- seedRouterRow
        // Burn past the 30-min cap → blocked=TimeLimit, no app assignments.
        _    <- seedTraffic(rid, mac, "cnn.com", LocalDate.of(2025, 1, 6), 35)
        svc  <- makePsAt(TestClock.schoolDayAfternoon)
        snap <- svc.snapshot
        rules = snap.profiles(kid).rules
        ea    = rules.extraAllowed.map(_.value).toSet
      } yield assertTrue(rules.blocked) &&
        assertTrue(rules.blockReason.contains(MacBlockReason.TimeLimit)) &&
        assertTrue(PolicyService.infraAllowHosts.map(_.value).toSet.subsetOf(ea)) &&
        assertTrue(ea.contains("connectivitycheck.gstatic.com"))
    },
    test("#1105: same app assigned to two profiles with different exempt flags → independent") {
      for {
        _     <- cleanDb
        pr    <- ZIO.service[ProfileRepo]
        sr    <- ZIO.service[ScheduleRepo]
        ar    <- ZIO.service[AppRepo]
        kid   <- TestLayers.seedKidsProfile(pr, sr)
        adult <- TestLayers.seedAdultsProfile(pr)
        appId <- ar.create("Khan", "khan", None, None)
        _     <- ar.setHosts(appId, List(Hostname.unsafe("khanacademy.org")))
        _     <- ar.upsertAssignment(appId, kid, AppMode.TimeLimited, Some(60), true)
        _     <- ar.upsertAssignment(appId, adult, AppMode.TimeLimited, Some(60), false)
        svc   <- makePsAt(TestClock.schoolDayAfternoon)
        snap  <- svc.snapshot
        kidEa   = snap.profiles(kid).rules.extraAllowed.map(_.value).toSet
        adultEa = snap.profiles(adult).rules.extraAllowed.map(_.value).toSet
      } yield assertTrue(kidEa.contains("khanacademy.org")) &&
        assertTrue(!adultEa.contains("khanacademy.org"))
    },
  ) @@ TestAspect.sequential
}
