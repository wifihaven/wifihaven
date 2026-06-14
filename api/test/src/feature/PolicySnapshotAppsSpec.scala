package wifihaven.api.feature

import wifihaven.api.db.*
import wifihaven.api.policy.*
import wifihaven.api.usage.{AppUsedRollupServiceLive, TimeUsedRollupJob}
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
      hsr    <- ZIO.service[HouseholdSettingsRepo]
      tlr    <- ZIO.service[TimeLimitRepo]
      atlr   <- ZIO.service[AppTimeLimitRepo]
      dr     <- ZIO.service[DeviceRepo]
      blr    <- ZIO.service[BlocklistRepo]
      trRepo <- ZIO.service[TrafficReportRepo]
      er     <- ZIO.service[TimeExtensionRepo]
      ar     <- ZIO.service[AppRepo]
      clock  <- ZIO.service[Clock]
    } yield PolicyServiceLive(
      pr,
      hsr,
      tlr,
      atlr,
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
      hsr    <- ZIO.service[HouseholdSettingsRepo]
      tlr    <- ZIO.service[TimeLimitRepo]
      atlr   <- ZIO.service[AppTimeLimitRepo]
      dr     <- ZIO.service[DeviceRepo]
      blr    <- ZIO.service[BlocklistRepo]
      trRepo <- ZIO.service[TrafficReportRepo]
      er     <- ZIO.service[TimeExtensionRepo]
      ar     <- ZIO.service[AppRepo]
      nsr    <- ZIO.service[NamedScheduleRepo]
      ref    <- Ref.make(dt)
      clk = new Clock.TestClock(ref)
    } yield PolicyServiceLive(
      pr,
      hsr,
      tlr,
      atlr,
      dr,
      blr,
      trRepo,
      er,
      ar,
      clk,
      namedScheduleRepo = nsr,
    ): PolicyService

  // #1515: a PolicyService whose TimeStatusService uses the REAL today-rollup repos (TimeUsedRollup +
  // AppUsedRollup) and the real per-app rollup read accessor — so the snapshot reads the rollup +
  // live-tail path (the prod steady state), not the all-live path the other `makePs*` helpers force
  // by wiring `NoopTimeUsedRollupRepo`. Used to prove the per-app cap enforces from `app_used_daily`.
  private def makePsRollup =
    for {
      pr   <- ZIO.service[ProfileRepo]
      hsr  <- ZIO.service[HouseholdSettingsRepo]
      tlr  <- ZIO.service[TimeLimitRepo]
      atlr <- ZIO.service[AppTimeLimitRepo]
      dr   <- ZIO.service[DeviceRepo]
      blr  <- ZIO.service[BlocklistRepo]
      trr  <- ZIO.service[TrafficReportRepo]
      er   <- ZIO.service[TimeExtensionRepo]
      ar   <- ZIO.service[AppRepo]
      nsr  <- ZIO.service[NamedScheduleRepo]
      ru   <- ZIO.service[TimeUsedRollupRepo]
      aru  <- ZIO.service[AppUsedRollupRepo]
      clk  <- ZIO.service[Clock]
      aus = new AppUsedRollupServiceLive(pr, dr, atlr, trr, aru)
      tss = new TimeStatusServiceLive(pr, tlr, atlr, dr, trr, er, ru, nsr, aus)
    } yield new PolicyServiceLive(
      pr,
      hsr,
      tlr,
      atlr,
      dr,
      blr,
      trr,
      er,
      ar,
      tss,
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
      "time_limited app: exhausting the app's budget on ONE host blocks the whole host-set (#1505)",
    ) {
      // #1505: the limit is per-app, aggregated across the whole host-set — not a separate per-host
      // budget. So 30 m spent on the off-domain asset host alone exhausts the app's 30 m limit and
      // pushes BOTH the apex and the asset host into extraBlocked (the router then drops the whole
      // app). The pre-#1505 behavior blocked only the host whose own bucket-count hit the limit.
      for {
        _    <- cleanDb
        ar   <- ZIO.service[AppRepo]
        kids <- kidsId
        dr   <- ZIO.service[DeviceRepo]
        _    <- dr.upsert(MacAddress.unsafe("aa:bb:cc:dd:ee:90"), "iPad", Some(kids), "10.0.0.9")
        routerId <- seedRouterRow
        appId    <- ar.create("YouTube", "youtube", None, None)
        _        <- ar.setHosts(
          appId,
          List(Hostname.unsafe("youtube.com"), Hostname.unsafe("ytimg.com")),
        )
        _        <- ar.upsertAssignment(appId, kids, AppMode.TimeLimited, Some(30), true)
        today = TestClock.schoolDayAfternoon.toLocalDate
        // 30 m entirely on the off-domain asset host; the apex saw no traffic at all.
        _    <- seedTraffic(routerId, "aa:bb:cc:dd:ee:90", "ytimg.com", today, 30)
        svc  <- makePs
        snap <- svc.snapshot
      } yield {
        val eb = snap.profiles(kids).rules.extraBlocked.map(_.value).toSet
        assertTrue(eb.contains("youtube.com")) && assertTrue(eb.contains("ytimg.com"))
      }
    },
    test("time_limited app: under-budget app keeps its whole host-set reachable") {
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
        // With no traffic recorded, the app's aggregate is 0 < 30 — nothing blocked.
        val eb = snap.profiles(kids).rules.extraBlocked.map(_.value).toSet
        assertTrue(!eb.contains("youtube.com")) && assertTrue(!eb.contains("ytimg.com"))
      }
    },
    test(
      "#1515 boundary: aggregate exactly AT the app cap blocks the whole host-set; one minute under does not",
    ) {
      // The per-app cap is a `>=` test on the gap-bridged aggregate across the whole host-set. Pin
      // both sides of the boundary in one test so an off-by-one (`>` vs `>=`) can't slip through:
      // 30 m on the asset host alone (apex idle) hits the 30 m cap → both hosts blocked; 25 m leaves
      // 5 m of budget → neither blocked. Both readings come from the SAME aggregate the router caps
      // on, so the whole app moves together either way.
      for {
        _    <- cleanDb
        ar   <- ZIO.service[AppRepo]
        kids <- kidsId
        dr   <- ZIO.service[DeviceRepo]
        _    <- dr.upsert(MacAddress.unsafe("aa:bb:cc:dd:ee:91"), "iPad", Some(kids), "10.0.0.91")
        rid  <- seedRouterRow
        today = TestClock.schoolDayAfternoon.toLocalDate
        // ── at the limit: 30 m on the asset host alone → aggregate == cap → whole set blocked ──
        atApp <- ar.create("YouTube", "youtube", None, None)
        _ <- ar.setHosts(atApp, List(Hostname.unsafe("youtube.com"), Hostname.unsafe("ytimg.com")))
        _ <- ar.upsertAssignment(atApp, kids, AppMode.TimeLimited, Some(30), true)
        _ <- seedTraffic(rid, "aa:bb:cc:dd:ee:91", "ytimg.com", today, 30)
        atSnap <- makePs.flatMap(_.snapshot)
        atEb = atSnap.profiles(kids).rules.extraBlocked.map(_.value).toSet
        // ── one under: same app, reseed with 25 m → aggregate < cap → whole set reachable ──
        _     <- cleanDb
        kids2 <- kidsId
        _     <- dr.upsert(MacAddress.unsafe("aa:bb:cc:dd:ee:91"), "iPad", Some(kids2), "10.0.0.91")
        rid2  <- seedRouterRow
        unApp <- ar.create("YouTube", "youtube", None, None)
        _ <- ar.setHosts(unApp, List(Hostname.unsafe("youtube.com"), Hostname.unsafe("ytimg.com")))
        _ <- ar.upsertAssignment(unApp, kids2, AppMode.TimeLimited, Some(30), true)
        _ <- seedTraffic(rid2, "aa:bb:cc:dd:ee:91", "ytimg.com", today, 25)
        unSnap <- makePs.flatMap(_.snapshot)
        unEb = unSnap.profiles(kids2).rules.extraBlocked.map(_.value).toSet
      } yield assertTrue(atEb.contains("youtube.com")) &&
        assertTrue(atEb.contains("ytimg.com")) &&
        assertTrue(!unEb.contains("youtube.com")) &&
        assertTrue(!unEb.contains("ytimg.com"))
    },
    test(
      "#1515 rollup path: per-app cap enforces from app_used_daily + tail, not just the all-live path",
    ) {
      // Regression guard for the gap that the snapshot's per-app cap only fired on the all-live path:
      // once a profile has rolled (the prod steady state) the snapshot read `perApp.usedMinutes` as
      // ZERO and the cap never bit. Roll the day up so the snapshot takes the rollup + live-tail
      // path, and assert the whole host-set is still blocked from `app_used_daily`.
      for {
        _     <- cleanDb
        ar    <- ZIO.service[AppRepo]
        kids  <- kidsId
        dr    <- ZIO.service[DeviceRepo]
        _     <- dr.upsert(MacAddress.unsafe("aa:bb:cc:dd:ee:92"), "iPad", Some(kids), "10.0.0.92")
        rid   <- seedRouterRow
        appId <- ar.create("YouTube", "youtube", None, None)
        _     <- ar.setHosts(
          appId,
          List(Hostname.unsafe("youtube.com"), Hostname.unsafe("ytimg.com")),
        )
        _     <- ar.upsertAssignment(appId, kids, AppMode.TimeLimited, Some(30), false)
        today = TestClock.schoolDayAfternoon.toLocalDate
        // 30 m on the asset host alone → aggregate hits the 30 m cap.
        _      <- seedTraffic(rid, "aa:bb:cc:dd:ee:92", "ytimg.com", today, 30)
        // Roll the day up: writes app_used_daily (30 m YouTube) + time_used_daily for every profile,
        // so the snapshot below reads the ROLLUP path rather than re-aggregating live.
        pr     <- ZIO.service[ProfileRepo]
        atlr   <- ZIO.service[AppTimeLimitRepo]
        trr    <- ZIO.service[TrafficReportRepo]
        hsr    <- ZIO.service[HouseholdSettingsRepo]
        ru     <- ZIO.service[TimeUsedRollupRepo]
        aru    <- ZIO.service[AppUsedRollupRepo]
        clk    <- ZIO.service[Clock]
        now    <- clk.instant
        _      <- TimeUsedRollupJob.oneTickForTest(ru, aru, pr, dr, atlr, trr, hsr, now)
        // Proves the rollup branch is exercised: the per-app rollup row exists for this profile.
        rolled <- aru.getDayForProfile(kids, today)
        svc    <- makePsRollup
        snap   <- svc.snapshot
      } yield {
        val eb = snap.profiles(kids).rules.extraBlocked.map(_.value).toSet
        assertTrue(rolled.nonEmpty) &&
        assertTrue(eb.contains("youtube.com")) &&
        assertTrue(eb.contains("ytimg.com"))
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
        // #1321: a profile with no app assignments now has an EMPTY per-profile
        // extraAllowed — the infra allowlist no longer copies in (it rides global).
        assertTrue(rules.extraAllowed.isEmpty)
    },
    // ── #1105: time_limited app with exemptFromDaily carves around @blocked_macs ──
    test(
      "#1105: exempt+budget remaining + cap exhausted → app host in extraAllowed, blocked=TimeLimit",
    ) {
      val mac = "aa:bb:cc:dd:ee:01"
      for {
        _     <- cleanDb
        pr    <- ZIO.service[ProfileRepo]
        dr    <- ZIO.service[DeviceRepo]
        tlr   <- ZIO.service[TimeLimitRepo]
        ar    <- ZIO.service[AppRepo]
        kid   <- TestLayers.seedKidsProfile(pr)
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
        dr    <- ZIO.service[DeviceRepo]
        ar    <- ZIO.service[AppRepo]
        kid   <- TestLayers.seedKidsProfile(pr)
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
        ar    <- ZIO.service[AppRepo]
        kid   <- TestLayers.seedKidsProfile(pr)
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
        ar    <- ZIO.service[AppRepo]
        kid   <- TestLayers.seedKidsProfile(pr)
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
        dr    <- ZIO.service[DeviceRepo]
        tlr   <- ZIO.service[TimeLimitRepo]
        ar    <- ZIO.service[AppRepo]
        kid   <- TestLayers.seedKidsProfile(pr)
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
        dr    <- ZIO.service[DeviceRepo]
        tlr   <- ZIO.service[TimeLimitRepo]
        ar    <- ZIO.service[AppRepo]
        kid   <- TestLayers.seedKidsProfile(pr)
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
        dr    <- ZIO.service[DeviceRepo]
        ar    <- ZIO.service[AppRepo]
        kid   <- TestLayers.seedKidsProfile(pr)
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
      "#1321: infra hosts ride global.extraAllowed (not per-profile) under blocked=TimeLimit",
    ) {
      // Allowed-mode apps appeared blocked when the daily cap ran out because
      // the whole-MAC @blocked_macs drop killed transitive connectivity-check /
      // OCSP / CDN dependencies. The curated infra allowlist now ships ONCE via
      // global.extraAllowed (#1321), which the router carves out of every drop
      // for every MAC (#1319/#421) — so the host stays reachable while the
      // per-profile extraAllowed no longer duplicates it. Functional, not
      // policy-based (#1311); no snapshot-shape change.
      val mac = "aa:bb:cc:dd:ee:14"
      for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        dr   <- ZIO.service[DeviceRepo]
        tlr  <- ZIO.service[TimeLimitRepo]
        kid  <- TestLayers.seedKidsProfile(pr)
        _    <- tlr.upsert(kid, 30)
        _    <- TestLayers.seedDevice(dr, mac, "kid-mac", kid)
        rid  <- seedRouterRow
        // Burn past the 30-min cap → blocked=TimeLimit, no app assignments.
        _    <- seedTraffic(rid, mac, "cnn.com", LocalDate.of(2025, 1, 6), 35)
        svc  <- makePsAt(TestClock.schoolDayAfternoon)
        snap <- svc.snapshot
        rules = snap.profiles(kid).rules
        ea    = rules.extraAllowed.map(_.value).toSet
        ga    = snap.global.extraAllowed.map(_.value).toSet
      } yield assertTrue(rules.blocked) &&
        assertTrue(rules.blockReason.contains(MacBlockReason.TimeLimit)) &&
        // infra no longer copied into the per-profile list…
        assertTrue(PolicyService.infraAllowHosts.map(_.value).toSet.intersect(ea).isEmpty) &&
        // …it lives in the global section, reachable for this (and every) MAC.
        assertTrue(PolicyService.infraAllowHosts.map(_.value).toSet.subsetOf(ga)) &&
        assertTrue(ga.contains("connectivitycheck.gstatic.com"))
    },
    test(
      "#1418/#1321: HARD pause empties per-profile extraAllowed; infra survives via global",
    ) {
      // Hard pause is a true off-switch for per-profile carve-outs: unlike soft
      // pause (#421/#1413), the allowed-mode app host is dropped from the
      // profile's own extraAllowed, so the router drops the MAC unconditionally
      // (no `ip daddr != @ea_…`). As of #1321 the curated infra allowlist no
      // longer lives per-profile — it rides global.extraAllowed, which the router
      // carves out of every drop (#1319). So a hard pause still cuts the kid off
      // from real apps/sites while pure infra probes (OCSP / connectivity-check /
      // gvt2) stay reachable fleet-wide, exactly like the deployment UI hosts.
      for {
        _     <- cleanDb
        pr    <- ZIO.service[ProfileRepo]
        ar    <- ZIO.service[AppRepo]
        kid   <- TestLayers.seedKidsProfile(pr)
        appId <- ar.create("Khan", "khan", None, None)
        _     <- ar.setHosts(appId, List(Hostname.unsafe("khanacademy.org")))
        _     <- ar.upsertAssignment(appId, kid, AppMode.Allowed, None, true)
        p     <- pr.findById(kid).map(_.get)
        _     <- pr.update(p.copy(paused = true, pauseMode = PauseMode.Hard))
        svc   <- makePs
        snap  <- svc.snapshot
        rules = snap.profiles(kid).rules
        ea    = rules.extraAllowed.map(_.value).toSet
        ga    = snap.global.extraAllowed.map(_.value).toSet
      } yield assertTrue(rules.blocked) &&
        assertTrue(rules.blockReason.contains(MacBlockReason.Paused)) &&
        // per-profile extraAllowed is empty — the app host AND infra are gone from it
        assertTrue(ea.isEmpty) &&
        assertTrue(!ea.contains("khanacademy.org")) &&
        // but infra is still reachable globally (survives the hard pause)
        assertTrue(ga.contains("connectivitycheck.gstatic.com"))
    },
    test(
      "#1418/#1321: SOFT pause keeps the allowed-app host per-profile; infra rides global",
    ) {
      // The default, unchanged behavior: a soft-paused profile still spares its
      // own extraAllowed hosts (#421/#1413). The curated infra allowlist now
      // surfaces via global.extraAllowed (#1321) rather than the per-profile copy.
      for {
        _     <- cleanDb
        pr    <- ZIO.service[ProfileRepo]
        ar    <- ZIO.service[AppRepo]
        kid   <- TestLayers.seedKidsProfile(pr)
        appId <- ar.create("Khan", "khan", None, None)
        _     <- ar.setHosts(appId, List(Hostname.unsafe("khanacademy.org")))
        _     <- ar.upsertAssignment(appId, kid, AppMode.Allowed, None, true)
        p     <- pr.findById(kid).map(_.get)
        _     <- pr.update(p.copy(paused = true, pauseMode = PauseMode.Soft))
        svc   <- makePs
        snap  <- svc.snapshot
        rules = snap.profiles(kid).rules
        ea    = rules.extraAllowed.map(_.value).toSet
        ga    = snap.global.extraAllowed.map(_.value).toSet
      } yield assertTrue(rules.blocked) &&
        assertTrue(rules.blockReason.contains(MacBlockReason.Paused)) &&
        assertTrue(ea.contains("khanacademy.org")) &&
        // infra no longer copied per-profile; it is in the global section
        assertTrue(PolicyService.infraAllowHosts.map(_.value).toSet.intersect(ea).isEmpty) &&
        assertTrue(PolicyService.infraAllowHosts.map(_.value).toSet.subsetOf(ga))
    },
    test(
      "#1418/#1318: HARD pause still spares the deployment uiAllowedHosts via global.extraAllowed",
    ) {
      // We deliberately keep the deployment UI hosts reachable through a hard
      // pause so the kid sees the block page and the admin SPA loads on the LAN —
      // only the per-profile app allowlist is cut. As of #1318 the UI hosts live
      // in `global.extraAllowed` (not per-profile), and #1321 moves the curated
      // infra allowlist there too; the router carves the global section out of
      // every drop (#1319), so the per-profile `extraAllowed` is empty under a
      // hard pause while the global section carries the UI hosts + infra.
      val uiHosts = List(Hostname.unsafe("blockpage.wifihaven.lan"))
      for {
        _     <- cleanDb
        pr    <- ZIO.service[ProfileRepo]
        hsr   <- ZIO.service[HouseholdSettingsRepo]
        tlr   <- ZIO.service[TimeLimitRepo]
        atlr  <- ZIO.service[AppTimeLimitRepo]
        dr    <- ZIO.service[DeviceRepo]
        blr   <- ZIO.service[BlocklistRepo]
        trr   <- ZIO.service[TrafficReportRepo]
        er    <- ZIO.service[TimeExtensionRepo]
        ar    <- ZIO.service[AppRepo]
        clk   <- ZIO.service[Clock]
        kid   <- TestLayers.seedKidsProfile(pr)
        appId <- ar.create("Khan", "khan", None, None)
        _     <- ar.setHosts(appId, List(Hostname.unsafe("khanacademy.org")))
        _     <- ar.upsertAssignment(appId, kid, AppMode.Allowed, None, true)
        p     <- pr.findById(kid).map(_.get)
        _     <- pr.update(p.copy(paused = true, pauseMode = PauseMode.Hard))
        svc =
          PolicyServiceLive(
            pr,
            hsr,
            tlr,
            atlr,
            dr,
            blr,
            trr,
            er,
            ar,
            clk,
            uiHosts,
          ): PolicyService
        snap <- svc.snapshot
        rules = snap.profiles(kid).rules
        ea    = rules.extraAllowed.map(_.value).toSet
        ga    = snap.global.extraAllowed.map(_.value).toSet
      } yield assertTrue(rules.blocked) &&
        // per-profile extraAllowed is empty under hard pause (app cut, UI + infra relocated)
        assertTrue(ea.isEmpty) &&
        // the deployment UI hosts survive via the fleet-wide global section…
        assertTrue(ga.contains("blockpage.wifihaven.lan")) &&
        // …alongside the curated infra allowlist, which also now rides global
        assertTrue(PolicyService.infraAllowHosts.map(_.value).toSet.subsetOf(ga)) &&
        assertTrue(!ea.contains("khanacademy.org")) &&
        assertTrue(!ea.contains("connectivitycheck.gstatic.com"))
    },
    test("#1418: profile round-trips pauseMode through the repo") {
      for {
        _   <- cleanDb
        pr  <- ZIO.service[ProfileRepo]
        kid <- TestLayers.seedKidsProfile(pr)
        p0  <- pr.findById(kid).map(_.get)
        _   <- pr.update(p0.copy(pauseMode = PauseMode.Hard))
        p1  <- pr.findById(kid).map(_.get)
        _   <- pr.update(p1.copy(pauseMode = PauseMode.Soft))
        p2  <- pr.findById(kid).map(_.get)
      } yield assertTrue(p0.pauseMode == PauseMode.Soft) &&
        assertTrue(p1.pauseMode == PauseMode.Hard) &&
        assertTrue(p2.pauseMode == PauseMode.Soft)
    },
    // ── #1627: exempt-from-daily app with NO per-app limit (daily_minutes=NULL) ──
    // Repro of the Math Academy prod symptom (#1626 symptom 3). When a
    // time_limited assignment carries exempt_from_daily=true and a NULL
    // per-app daily_minutes, the exempt carve-out must still trigger across
    // every whole-MAC block reason (TimeLimit/Schedule/Paused). The carve is
    // reason-agnostic per `PolicyService.exemptUnderCapHosts` and a
    // never-exhausted (no-limit) app must never be excluded.
    test(
      "#1627: exempt + no per-app limit (NULL) + cap exhausted → in extraAllowed (blocked=TimeLimit)",
    ) {
      val mac = "aa:bb:cc:dd:ee:27"
      for {
        _     <- cleanDb
        pr    <- ZIO.service[ProfileRepo]
        dr    <- ZIO.service[DeviceRepo]
        tlr   <- ZIO.service[TimeLimitRepo]
        ar    <- ZIO.service[AppRepo]
        kid   <- TestLayers.seedKidsProfile(pr)
        _     <- tlr.upsert(kid, 30)
        _     <- TestLayers.seedDevice(dr, mac, "kid-ipad", kid)
        appId <- ar.create("Math", "math", None, None)
        _     <- ar.setHosts(appId, List(Hostname.unsafe("mathacademy.com")))
        _     <- ar.upsertAssignment(appId, kid, AppMode.TimeLimited, None, true)
        rid   <- seedRouterRow
        _     <- seedTraffic(rid, mac, "cnn.com", LocalDate.of(2025, 1, 6), 35)
        svc   <- makePsAt(TestClock.schoolDayAfternoon)
        snap  <- svc.snapshot
        rules = snap.profiles(kid).rules
        ea    = rules.extraAllowed.map(_.value).toSet
      } yield assertTrue(rules.blocked) &&
        assertTrue(rules.blockReason.contains(MacBlockReason.TimeLimit)) &&
        assertTrue(ea.contains("mathacademy.com"))
    },
    test("#1627: exempt + no per-app limit (NULL) + profile paused → in extraAllowed") {
      for {
        _     <- cleanDb
        pr    <- ZIO.service[ProfileRepo]
        ar    <- ZIO.service[AppRepo]
        kid   <- TestLayers.seedKidsProfile(pr)
        _     <- pr.setPaused(kid, true)
        appId <- ar.create("Math", "math", None, None)
        _     <- ar.setHosts(appId, List(Hostname.unsafe("mathacademy.com")))
        _     <- ar.upsertAssignment(appId, kid, AppMode.TimeLimited, None, true)
        svc   <- makePsAt(TestClock.schoolDayAfternoon)
        snap  <- svc.snapshot
        rules = snap.profiles(kid).rules
        ea    = rules.extraAllowed.map(_.value).toSet
      } yield assertTrue(rules.blocked) &&
        assertTrue(rules.blockReason.contains(MacBlockReason.Paused)) &&
        assertTrue(ea.contains("mathacademy.com"))
    },
    test("#1627: exempt + no per-app limit (NULL) + schedule active → in extraAllowed") {
      for {
        _     <- cleanDb
        pr    <- ZIO.service[ProfileRepo]
        ar    <- ZIO.service[AppRepo]
        kid   <- TestLayers.seedKidsProfile(pr)
        appId <- ar.create("Math", "math", None, None)
        _     <- ar.setHosts(appId, List(Hostname.unsafe("mathacademy.com")))
        _     <- ar.upsertAssignment(appId, kid, AppMode.TimeLimited, None, true)
        svc   <- makePsAt(TestClock.bedtime)
        snap  <- svc.snapshot
        rules = snap.profiles(kid).rules
        ea    = rules.extraAllowed.map(_.value).toSet
      } yield assertTrue(rules.blocked) &&
        assertTrue(rules.blockReason.contains(MacBlockReason.Schedule)) &&
        assertTrue(ea.contains("mathacademy.com"))
    },
    test(
      "#1627: exempt + no per-app limit (NULL) + non-zero own usage → still carves (no cap to exhaust)",
    ) {
      val mac = "aa:bb:cc:dd:ee:28"
      for {
        _     <- cleanDb
        pr    <- ZIO.service[ProfileRepo]
        dr    <- ZIO.service[DeviceRepo]
        tlr   <- ZIO.service[TimeLimitRepo]
        ar    <- ZIO.service[AppRepo]
        kid   <- TestLayers.seedKidsProfile(pr)
        _     <- tlr.upsert(kid, 30)
        _     <- TestLayers.seedDevice(dr, mac, "kid-ipad", kid)
        appId <- ar.create("Math", "math", None, None)
        _     <- ar.setHosts(appId, List(Hostname.unsafe("mathacademy.com")))
        _     <- ar.upsertAssignment(appId, kid, AppMode.TimeLimited, None, true)
        rid   <- seedRouterRow
        // Profile cap exhausted by an unrelated host AND the exempt app itself
        // has logged usage today — with no per-app limit, the carve must still fire.
        _     <- seedTraffic(rid, mac, "cnn.com", LocalDate.of(2025, 1, 6), 35)
        _     <- seedTraffic(rid, mac, "mathacademy.com", LocalDate.of(2025, 1, 6), 25)
        svc   <- makePsAt(TestClock.schoolDayAfternoon)
        snap  <- svc.snapshot
        rules = snap.profiles(kid).rules
        ea    = rules.extraAllowed.map(_.value).toSet
      } yield assertTrue(rules.blocked) &&
        assertTrue(rules.blockReason.contains(MacBlockReason.TimeLimit)) &&
        assertTrue(ea.contains("mathacademy.com"))
    },
    test("#1105: same app assigned to two profiles with different exempt flags → independent") {
      for {
        _     <- cleanDb
        pr    <- ZIO.service[ProfileRepo]
        ar    <- ZIO.service[AppRepo]
        kid   <- TestLayers.seedKidsProfile(pr)
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
