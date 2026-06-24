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

import java.time.{LocalDateTime, LocalTime, ZoneId}

/**
 * #1849: the computed-snapshot cache in PolicyService + push-on-change. Pins the four properties
 * the cache must have, all behavior-preserving (same snapshot bytes for a given state — #1512):
 *
 *   1. With the cache ON, a second `snapshot` is served from the cache (does NOT rebuild), so a DB
 *      change made WITHOUT invalidating is not yet visible — proving the read path is cached. 2.
 *      `invalidate` (what mutating routes call) drops the cache so the next `snapshot` reflects the
 *      change — proving invalidation works. 3. `reevaluate` (what the reconcile ticker +
 *      post-mutation reconcile call) rebuilds, and pushes the new snapshot to the publisher IFF the
 *      ETag moved — proving push-on-change (and that an unchanged state does NOT spam a push). 4. A
 *      time-dependent transition (a schedule boundary crossed with NO DB write) is caught by
 *      `reevaluate` — the ticker mechanism — and pushed, even though `invalidate` was never called.
 *
 * The default `apply` factory leaves the cache OFF (so the ~40 other snapshot specs keep building
 * every call); these tests construct with `cacheEnabled = true` explicitly.
 */
object PolicySnapshotCacheSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  /**
   * A probe publisher that records every pushed snapshot, so a test can assert push count + bytes.
   */
  private final class ProbePublisher(ref: Ref[List[PolicySnapshot]])
      extends PolicySnapshotPublisher {
    def publish(snap: PolicySnapshot): UIO[Unit] = ref.update(_ :+ snap)
  }

  /**
   * Build a cache-ENABLED PolicyService over a clock backed by `ref` (so a test can advance time to
   * cross a schedule boundary), with a probe publisher attached. Returns the service, the clock
   * ref, and the probe's record.
   */
  private def makeCachedSvc(startAt: LocalDateTime) =
    for {
      pr     <- ZIO.service[ProfileRepo]
      nsr    <- ZIO.service[NamedScheduleRepo]
      hsr    <- ZIO.service[HouseholdSettingsRepo]
      tlr    <- ZIO.service[TimeLimitRepo]
      atlr   <- ZIO.service[AppTimeLimitRepo]
      dr     <- ZIO.service[DeviceRepo]
      blr    <- ZIO.service[BlocklistRepo]
      trRepo <- ZIO.service[TrafficReportRepo]
      er     <- ZIO.service[TimeExtensionRepo]
      ar     <- ZIO.service[AppRepo]
      ref    <- Ref.make(startAt)
      clk = new Clock.TestClock(ref)
      tss = new TimeStatusServiceLive(pr, tlr, atlr, dr, trRepo, er, NoopTimeUsedRollupRepo, nsr)
      svc = new PolicyServiceLive(
        pr,
        hsr,
        tlr,
        atlr,
        dr,
        blr,
        trRepo,
        er,
        ar,
        tss,
        clk,
        namedScheduleRepo = nsr,
        cacheEnabled = true,
      )
      pushed <- Ref.make(List.empty[PolicySnapshot])
      _      <- svc.setPublisher(new ProbePublisher(pushed))
    } yield (svc, ref, pushed)

  private def blockedMacs(snap: PolicySnapshot): List[String] =
    snap.devices.toList.flatMap { case (mac, dev) =>
      val rules = dev.rules.orElse(dev.profileId.flatMap(snap.profiles.get).map(_.rules))
      rules.filter(_.blocked).map(_ => mac.value)
    }

  // A profile with NO legacy schedules, linked to a bedtime (21:00–07:00) named schedule + a device.
  private def seedBedtime =
    for {
      pr  <- ZIO.service[ProfileRepo]
      nsr <- ZIO.service[NamedScheduleRepo]
      dr  <- ZIO.service[DeviceRepo]
      pid <- pr.create("Kids", Nil)
      sid <- nsr.create(
        "Bedtime",
        Some("overnight"),
        List(
          ScheduleWindow(
            List("mon", "tue", "wed", "thu", "fri", "sat", "sun"),
            LocalTime.of(21, 0),
            LocalTime.of(7, 0),
            ZoneId.of("UTC"),
          ),
        ),
      )
      _   <- nsr.setProfileBlockSchedules(pid, List(sid))
      _   <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", pid)
    } yield pid

  private def setBlockEncryptedDns(v: Boolean) =
    for {
      hsr      <- ZIO.service[HouseholdSettingsRepo]
      existing <- hsr.get
      _        <- hsr.update(existing.copy(blockEncryptedDns = v))
    } yield ()

  def spec = suite("PolicySnapshot — computed-snapshot cache + push-on-change (#1849)")(
    test(
      "a second snapshot is served from the cache — a DB change made without invalidate is not yet visible",
    ) {
      for {
        _      <- cleanDb
        triple <- makeCachedSvc(TestClock.schoolDayAfternoon)
        (svc, _, _) = triple
        snap1 <- svc.snapshot
        // Mutate the DB directly, bypassing `invalidate` (as a stale poll would observe it).
        _     <- setBlockEncryptedDns(true)
        snap2 <- svc.snapshot
      } yield assertTrue(snap2.etag == snap1.etag) &&
        assertTrue(!snap2.blockEncryptedDns) // still the cached (pre-change) bytes
    },
    test(
      "invalidate drops the cache so the next snapshot reflects the change (same bytes a fresh build would give)",
    ) {
      for {
        _      <- cleanDb
        triple <- makeCachedSvc(TestClock.schoolDayAfternoon)
        (svc, _, _) = triple
        snap1 <- svc.snapshot
        _     <- setBlockEncryptedDns(true)
        _     <- svc.invalidate
        // `invalidate` clears the cache synchronously; the next read rebuilds. (It also forks a
        // background reconcile, but we don't depend on that fiber here.)
        snap2 <- svc.snapshot
      } yield assertTrue(snap2.blockEncryptedDns) && assertTrue(snap2.etag != snap1.etag)
    },
    test("reevaluate rebuilds and pushes to the publisher exactly when the ETag moves") {
      for {
        _      <- cleanDb
        triple <- makeCachedSvc(TestClock.schoolDayAfternoon)
        (svc, _, pushed) = triple
        _           <- svc.snapshot   // warm the cache
        _           <- svc.reevaluate // unchanged state → no push
        afterNoop   <- pushed.get
        _           <- setBlockEncryptedDns(true)
        _           <- svc.invalidate // clear cache so reevaluate sees the new etag vs prev
        // `invalidate` cleared the cache; reevaluate rebuilds, sees a different etag than the (now
        // empty) prev, and pushes once.
        _           <- svc.reevaluate
        afterChange <- pushed.get
      } yield assertTrue(afterNoop.isEmpty) &&
        assertTrue(afterChange.size == 1) &&
        assertTrue(afterChange.head.blockEncryptedDns)
    },
    test(
      "a schedule boundary crossed with no DB write is caught + pushed by reevaluate (the ticker mechanism)",
    ) {
      for {
        _      <- cleanDb
        _      <- seedBedtime
        triple <- makeCachedSvc(TestClock.schoolDayAfternoon)
        (svc, ref, pushed) = triple
        snapDay   <- svc.snapshot               // 14:00 — not in bedtime window
        // Cache holds the 14:00 bytes; advancing the clock alone does not change them.
        _         <- ref.set(TestClock.bedtime) // 21:30 — now inside the bedtime window
        snapStale <- svc.snapshot               // still the cached (un-blocked) snapshot
        _         <- svc.reevaluate             // the ticker re-eval catches the boundary
        snapFresh <- svc.snapshot
        pushes    <- pushed.get
      } yield assertTrue(blockedMacs(snapDay).isEmpty) &&
        assertTrue(blockedMacs(snapStale).isEmpty) && // cache held across the time move
        assertTrue(blockedMacs(snapFresh) == List("aa:bb:cc:11:22:33")) &&
        assertTrue(pushes.size == 1) &&               // pushed once, on the transition
        assertTrue(blockedMacs(pushes.head) == List("aa:bb:cc:11:22:33"))
    },
  ) @@ TestAspect.sequential
}
