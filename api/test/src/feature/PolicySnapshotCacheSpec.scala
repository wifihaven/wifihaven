package wifihaven.api.feature

import wifihaven.api.db.*
import wifihaven.api.policy.*
import wifihaven.shared.*
import wifihaven.shared.Clock.TestClock
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.metrics.Metric
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
   * A probe publisher that records every pushed snapshot WITH the household it was published for,
   * so a test can assert push count, bytes, and (since #2630) scope.
   *
   * `targets` is what the sink reports as having a live recipient — the production registry answers
   * this with the households that have a connected router, and it is what `reevaluate` rebuilds
   * for. Defaults to empty, which reproduces the pre-#2630 shape (Default alone) for the tests that
   * predate household scoping.
   *
   * It reads the payload exactly the way the production registry does: try each household it could
   * plausibly be delivering to, and record the one that unwraps. It does NOT ask the wrapper who it
   * belongs to and then unwrap against that answer — that would launder the payload out with no
   * recipient named, which is the read [[HouseholdScoped]] exists to prevent, and a probe that can
   * see more than the production sink is not a probe.
   *
   * `alsoTry` widens the set of households the probe attempts to unwrap against beyond its own
   * targets, so a test can prove a push did NOT arrive for a household — an unattempted household
   * would read as "not pushed" no matter what happened.
   */
  private final class ProbePublisher(
      ref: Ref[List[(HouseholdId, PolicySnapshot)]],
      targets: Set[HouseholdId] = Set.empty,
      alsoTry: Set[HouseholdId] = Set.empty,
  ) extends PolicySnapshotPublisher {
    private val candidates: Set[HouseholdId] = targets ++ alsoTry + HouseholdId.Default

    def publish(scoped: HouseholdScoped[PolicySnapshot]): UIO[Unit] =
      ZIO.foreachDiscard(candidates.toList) { hh =>
        ZIO.foreachDiscard(scoped.forHousehold(hh))(snap => ref.update(_ :+ (hh, snap)))
      }
    def targetHouseholds: UIO[Set[HouseholdId]]                     = ZIO.succeed(targets)
  }

  /**
   * Build a cache-ENABLED PolicyService over a clock backed by `ref` (so a test can advance time to
   * cross a schedule boundary), with a probe publisher attached. Returns the service, the clock
   * ref, and the probe's record.
   */
  private def makeCachedSvc(
      startAt: LocalDateTime,
      buildBarrier: UIO[Unit] = ZIO.unit,
      pushTargets: Set[HouseholdId] = Set.empty,
      probeAlsoTry: Set[HouseholdId] = Set.empty,
  ) =
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
        buildBarrier = buildBarrier,
      )
      pushed <- Ref.make(List.empty[(HouseholdId, PolicySnapshot)])
      _      <- svc.setPublisher(new ProbePublisher(pushed, pushTargets, probeAlsoTry))
    } yield (svc, ref, pushed)

  /**
   * The cumulative `policy_snapshot_build_total{result=...}` count, read straight off the ZIO
   * metric registry with the key `MetricGuard.counter` writes. Asserted as a DELTA around the
   * action: the registry is JVM-global and this counter is additive, so an absolute read would
   * couple this spec to whatever else in the suite built a snapshot. Exact rather than a lower
   * bound because this suite is `TestAspect.sequential` and mill runs one spec class at a time.
   */
  private def snapshotBuildTotal(result: String): UIO[Double] =
    Metric.counter("policy_snapshot_build_total").tagged("result", result).value.map(_.count)

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
      existing <- hsr.getForHousehold(HouseholdId.Default)
      _        <- hsr.update(HouseholdId.Default, existing.copy(blockEncryptedDns = v))
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
    test(
      "reevaluate pushes once per ETag change — the first establishes the baseline, an unchanged re-eval does not re-push",
    ) {
      for {
        _      <- cleanDb
        triple <- makeCachedSvc(TestClock.schoolDayAfternoon)
        (svc, _, pushed) = triple
        _             <- svc.reevaluate // first reevaluate establishes + pushes the baseline
        afterBaseline <- pushed.get
        _             <- svc.reevaluate // unchanged state → no new push
        afterNoop     <- pushed.get
        _             <- setBlockEncryptedDns(true)
        _             <- svc.reevaluate // ETag moved → exactly one more push
        afterChange   <- pushed.get
      } yield assertTrue(afterBaseline.size == 1) &&
        assertTrue(afterNoop.size == 1) && // no re-push on the unchanged re-eval
        assertTrue(afterChange.size == 2) &&
        assertTrue(!afterBaseline.head._2.blockEncryptedDns) &&
        assertTrue(afterChange.last._2.blockEncryptedDns)
    },
    test("#2630: reevaluate rebuilds and pushes ONE snapshot per household with a router") {
      // The other half of #2630. Scoping the registry fan-out alone would have left a second
      // household's routers receiving nothing at all on change — `reevaluate` only ever rebuilt
      // `HouseholdId.Default` and relied on the broadcast to reach everyone else, and the HTTP poll
      // that used to repair that goes dormant on a healthy ws link (#2037). So the rebuild is now
      // per household, driven by which households actually have a connected router.
      for {
        _          <- cleanDb
        hRepo      <- ZIO.service[HouseholdRepo]
        hhB        <- hRepo.create("Other household", "other-household")
        // Household C exists in the `households` table but has NO connected router. It is what
        // separates "rebuild the connected fleet" from "rebuild every row in the table" — with only
        // two households the push count cannot tell those apart, and the cost argument for this
        // change rests entirely on it being the former. The probe attempts C explicitly
        // (`probeAlsoTry`), so "no push for C" is an observation rather than an omission.
        hhC        <- hRepo.create("Routerless household", "routerless-household")
        triple     <- makeCachedSvc(
          TestClock.schoolDayAfternoon,
          pushTargets = Set(hhB), // one connected router, in household B
          probeAlsoTry = Set(hhC),
        )
        (svc, _, pushed) = triple
        buildsPre  <- snapshotBuildTotal("computed")
        _          <- svc.reevaluate
        buildsPost <- snapshotBuildTotal("computed")
        pushes     <- pushed.get
        // Both households were rebuilt and pushed: B because it has a router, Default because it is
        // always included (single-household installs, and the SPA change-bus sink, depend on it).
        households = pushes.map(_._1).toSet
        // And each push is readable ONLY by its own household — the property the registry routes
        // on. `forHousehold(other)` is None, so no sink could deliver B's snapshot to a Default
        // router even if it tried.
        scopedB    = HouseholdScoped(hhB, pushes.find(_._1 == hhB).get._2)
      } yield assertTrue(pushes.size == 2) &&
        assertTrue(households == Set(HouseholdId.Default, hhB)) &&
        assertTrue(!households.contains(hhC)) &&
        // Two BUILDS, not three: C was never rebuilt either, which is the cost claim. Without this
        // the push assertions alone would also hold for "rebuild every household, push some".
        assertTrue(buildsPost - buildsPre == 2.0) &&
        assertTrue(scopedB.forHousehold(hhB).isDefined) &&
        assertTrue(scopedB.forHousehold(HouseholdId.Default).isEmpty)
    },
    test("#2630: an unchanged household does not suppress another household's push") {
      // `lastPublishedEtag` was a single slot, correct only while one household was ever rebuilt.
      // Per-household rebuilds through a shared slot would have two tenants overwriting each
      // other's "last pushed" value, so an alternating pair of changes would each look unchanged
      // and neither household would be pushed — a silent policy freeze, which on this path means
      // routers enforcing stale policy indefinitely.
      for {
        _      <- cleanDb
        hRepo  <- ZIO.service[HouseholdRepo]
        hhB    <- hRepo.create("Other household", "other-household")
        triple <- makeCachedSvc(TestClock.schoolDayAfternoon, pushTargets = Set(hhB))
        (svc, _, pushed) = triple
        _         <- svc.reevaluate // baseline: one push per household
        baseline  <- pushed.get
        // Change DEFAULT's policy only. B is untouched, so B must not re-push and — the part a
        // shared slot broke — Default must.
        _         <- setBlockEncryptedDns(true)
        _         <- svc.reevaluate
        afterEdit <- pushed.get
        newPushes = afterEdit.drop(baseline.size)
      } yield assertTrue(baseline.size == 2) &&
        assertTrue(newPushes.size == 1) &&
        assertTrue(newPushes.head._1 == HouseholdId.Default) &&
        assertTrue(newPushes.head._2.blockEncryptedDns)
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
        assertTrue(blockedMacs(pushes.head._2) == List("aa:bb:cc:11:22:33"))
    },
    // #1954: the create-then-read invariant Gate 1 checks — a profile created + invalidated is in the
    // VERY NEXT read. (Passes pre-#1954 too; pinned so the wiring can't silently regress.)
    test("a newly created + invalidated profile is in the very next snapshot read") {
      for {
        _      <- cleanDb
        triple <- makeCachedSvc(TestClock.schoolDayAfternoon)
        (svc, _, _) = triple
        _    <- svc.snapshot // warm the cache (no profiles yet)
        pr   <- ZIO.service[ProfileRepo]
        dr   <- ZIO.service[DeviceRepo]
        pid  <- pr.create("e2e-router", Nil)
        _    <- TestLayers.seedDevice(dr, "e2:e2:e2:8e:79:5b", "e2e-dev", pid)
        _    <- svc.invalidate
        snap <- svc.snapshot
      } yield assertTrue(snap.profiles.contains(pid)) &&
        assertTrue(
          snap.devices
            .get(MacAddress.unsafe("e2:e2:e2:8e:79:5b"))
            .flatMap(_.profileId)
            .contains(pid),
        )
    },
    // #1954: deletion is equally read-after-write — a deleted profile/device is gone on the next read.
    test("a deleted + invalidated profile disappears from the very next snapshot read") {
      for {
        _      <- cleanDb
        triple <- makeCachedSvc(TestClock.schoolDayAfternoon)
        (svc, _, _) = triple
        pr      <- ZIO.service[ProfileRepo]
        pid     <- pr.create("temp", Nil)
        _       <- svc.invalidate
        present <- svc.snapshot
        _       <- pr.delete(pid)
        _       <- svc.invalidate
        gone    <- svc.snapshot
      } yield assertTrue(present.profiles.contains(pid)) &&
        assertTrue(!gone.profiles.contains(pid))
    },
    // #1954 REGRESSION PIN: the Gate-1 failure. A `buildSnapshot` that started reading the DB BEFORE a
    // mutation must NOT be able to finish AFTER the mutation's `invalidate` and clobber the cache with
    // pre-mutation bytes — which would make the next read a cache HIT on stale data (a just-created
    // profile missing from the snapshot). We suspend an in-flight build with `buildBarrier`, land a
    // create + invalidate + fresh rebuild while it is parked, then release it so its (stale) result
    // tries to install LAST. The next read must still be fresh.
    test(
      "a stale in-flight build cannot clobber a fresher cache entry (read-after-write stays fresh)",
    ) {
      for {
        _       <- cleanDb
        pr      <- ZIO.service[ProfileRepo]
        pidA    <- pr.create("A", Nil)
        pidB    <- pr.create("B", Nil)
        entered <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        armed   <- Ref.make(true)
        // Gate the FIRST build only: it signals `entered` (with {A,B} already read) and parks on
        // `release`; once disarmed, every later build passes straight through.
        barrier     = armed.get.flatMap {
          case true  => entered.succeed(()) *> release.await
          case false => ZIO.unit
        }
        triple <- makeCachedSvc(TestClock.schoolDayAfternoon, buildBarrier = barrier)
        (svc, _, _) = triple
        // The stale in-flight build: reads {A,B} (C not yet created), then parks in the barrier.
        staleFib <- svc.reevaluate.fork
        _        <- entered.await
        _        <- armed.set(false) // disarm so the fresh rebuild below is not gated
        // A mutation lands while the stale build is parked: create C + invalidate (version bump) +
        // a fresh rebuild that installs {A,B,C}.
        pidC     <- pr.create("C", Nil)
        _        <- svc.invalidate
        _        <- svc.reevaluate   // fresh build installs {A,B,C} under the new version
        // Now release the stale build so its pre-mutation {A,B} result tries to install LAST.
        _        <- release.succeed(())
        _        <- staleFib.join
        snap     <- svc.snapshot
      } yield assertTrue(snap.profiles.contains(pidA)) &&
        assertTrue(snap.profiles.contains(pidB)) &&
        assertTrue(snap.profiles.contains(pidC)) // the stale build did NOT clobber C away
    },
  ) @@ TestAspect.sequential
}
