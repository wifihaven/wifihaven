package wifihaven.api.policy

import wifihaven.api.AppConfig
import wifihaven.api.db.*
import wifihaven.api.metrics.AppMetrics
import wifihaven.api.observability.LogContext
import wifihaven.shared.{Schedule as DbSchedule, *}
import wifihaven.shared.types.*
import zio.{Clock as _, *}
import zio.json.*

import java.security.MessageDigest
import java.time.{DayOfWeek, Instant, LocalDate}
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}

trait PolicyService {

  /**
   * The current policy snapshot. When the computed-snapshot cache is enabled (#1849, production
   * wiring) this returns the cached snapshot on a hit (no rebuild — the #1512 win for the REST poll
   * path) and only rebuilds on a cold/invalidated cache. With the cache disabled (the default for
   * the `PolicyServiceLive.apply` test factory) it always rebuilds, so the existing snapshot specs
   * that mutate the DB directly and re-read see fresh bytes.
   */
  // #2107 (multi-tenant, epic #622): the three router-facing reads take the household resolved from
  // the router token (via `RouterAuth.authenticate` → `Router.householdId`, #2106) and read ONLY that
  // household's rows. WIRE UNCHANGED — `household_id` never appears on the returned `PolicySnapshot`
  // / `DevicePolicy` / decision / blocklist payloads (design invariant 3). For a single-household
  // install (every row in `HouseholdId.Default`) the scoped snapshot is byte-identical to the old
  // global one. The blocklist CATALOG stays global (design §0.2); the household only scopes which
  // rows drive the snapshot, not the shared category content.
  def snapshot(household: HouseholdId): Task[PolicySnapshot]
  def renderBlocklist(household: HouseholdId, id: BlocklistId): Task[Option[(ETag, String)]]
  def decide(household: HouseholdId, mac: String, hostname: String): Task[RouterDecisionResponse]

  // #2107: convenience overloads defaulting to `HouseholdId.Default` (the single backfill
  // household). These are for the paths with no router token / no household context yet — the
  // unauthenticated block page, the admin debug snapshot, and the ~150 single-household feature
  // specs — NOT for enforcement paths. Every `/api/router/*` handler passes `router.householdId`
  // explicitly (that is the whole point of tenant isolation); nothing on the router enforcement path
  // should call these no-arg forms. Concrete (not abstract) so all implementations inherit them and
  // arity resolution routes `.snapshot`/`.decide(mac,host)`/`.renderBlocklist(id)` here.
  final def snapshot: Task[PolicySnapshot] = snapshot(HouseholdId.Default)
  final def decide(mac: String, hostname: String): Task[RouterDecisionResponse] =
    decide(HouseholdId.Default, mac, hostname)
  final def renderBlocklist(id: BlocklistId): Task[Option[(ETag, String)]]      =
    renderBlocklist(HouseholdId.Default, id)

  /**
   * #1849: drop the cached snapshot so the next [[snapshot]]/[[reevaluate]] rebuilds. Called by
   * policy-mutating routes (pause/unpause, manual block/allow, profile/device/schedule/blocklist
   * edits, time-extension grants) so an admin change propagates immediately rather than waiting for
   * the reconcile ticker. A no-op when the cache is disabled.
   */
  def invalidate: UIO[Unit]

  /**
   * #1849: rebuild the snapshot once and, if its ETag moved since the last cached value, store it
   * and push it to the configured [[PolicySnapshotPublisher]] (one `policy` frame per connected
   * router). This is the single rebuild-and-publish point driven both by the time-boundary
   * reconcile ticker (schedule edges, daily-limit/usage transitions) and immediately after an
   * [[invalidate]]. A no-op when the cache is disabled.
   */
  def reevaluate: UIO[Unit]

  /**
   * #1849: install the sink that [[reevaluate]] pushes changed snapshots to. Wired once at startup
   * (the websocket registry is constructed after the policy layer); until then the publisher is
   * [[PolicySnapshotPublisher.noop]].
   */
  def setPublisher(publisher: PolicySnapshotPublisher): UIO[Unit]
}

object PolicyServiceLive {

  /**
   * #1104: test-friendly factory that wires a default `TimeStatusServiceLive` over the same repos.
   * Lets the existing PolicySnapshot* specs and Router* specs continue passing the old positional
   * args; production wiring still goes through `PolicyService.layer`, which injects an explicit
   * `TimeStatusService` (so a per-deployment instance can be swapped in).
   */
  def apply(
      profileRepo: ProfileRepo,
      householdSettingsRepo: HouseholdSettingsRepo,
      timeLimitRepo: TimeLimitRepo,
      appTimeLimitRepo: AppTimeLimitRepo,
      deviceRepo: DeviceRepo,
      blocklistRepo: BlocklistRepo,
      trafficRepo: TrafficReportRepo,
      extRepo: TimeExtensionRepo,
      appRepo: AppRepo,
      clock: Clock,
      uiAllowedHosts: List[Hostname] = Nil,
      // #1482: schedule downtime is read from the named-schedule model, so specs that assert
      // schedule blocking pass the real repo here; it threads into both the snapshot
      // (TimeStatusService) and the per-host /decision path. Defaults to the noop so the many specs
      // that don't exercise schedules keep their positional constructions unchanged.
      namedScheduleRepo: NamedScheduleRepo = NoopNamedScheduleRepo,
      // #1849: caching is opt-in. Defaulted OFF here so the ~40 direct test constructions (which
      // mutate repos and re-read `snapshot` expecting fresh bytes) keep building every call; the
      // production layer turns it on so the REST poll path reads the cache (#1512).
      cacheEnabled: Boolean = false,
      // #2137: the billing-status gate. Defaulted to "always active" so the many positional test
      // constructions build the normal enforcing snapshot; the flip lifecycle spec passes a
      // repo-backed reader to exercise the lapsed→permissive path.
      billingStatusOf: HouseholdId => Task[Option[String]] = _ => ZIO.succeed(Some("active")),
  ): PolicyServiceLive = {
    val tss = new TimeStatusServiceLive(
      profileRepo,
      timeLimitRepo,
      appTimeLimitRepo,
      deviceRepo,
      trafficRepo,
      extRepo,
      // Snapshot specs only exercise today; today is always live. A real rollup repo would be
      // ignored on this path, so wire a noop and avoid threading the repo through every test.
      NoopTimeUsedRollupRepo,
      namedScheduleRepo,
    )
    new PolicyServiceLive(
      profileRepo,
      householdSettingsRepo,
      timeLimitRepo,
      appTimeLimitRepo,
      deviceRepo,
      blocklistRepo,
      trafficRepo,
      extRepo,
      appRepo,
      tss,
      clock,
      uiAllowedHosts,
      namedScheduleRepo,
      cacheEnabled,
      billingStatusOf = billingStatusOf,
    )
  }
}

class PolicyServiceLive(
    profileRepo: ProfileRepo,
    householdSettingsRepo: HouseholdSettingsRepo,
    // #1544: the per-host /decision aggregation now reads `TimeStatusService.todaysState` (the same
    // ProfileDayState the snapshot consumes) instead of re-folding from these repos directly. They
    // remain injected to feed the companion `apply` factory's TimeStatusServiceLive and to keep the
    // constructor arity ~40 test constructions depend on; the class body no longer reads them.
    @scala.annotation.unused timeLimitRepo: TimeLimitRepo,
    // #1630: post-collapse this repo IS read by `snapshot` and `decide` — it now returns rows for
    // every mode (`mode=Allowed`, `Blocked`, `TimeLimited`) so the [[ProfileAppDispositions]]
    // single fold drives both the per-app enforcement (extraAllowed/extraBlocked) and the
    // structural projections (exempt host-set / active-app host-set / cap groups) without a
    // separate read off `appRepo.listAssignmentsForProfile`.
    appTimeLimitRepo: AppTimeLimitRepo,
    deviceRepo: DeviceRepo,
    blocklistRepo: BlocklistRepo,
    @scala.annotation.unused trafficRepo: TrafficReportRepo,
    @scala.annotation.unused extRepo: TimeExtensionRepo,
    appRepo: AppRepo,
    timeStatusService: TimeStatusService,
    clock: Clock,
    uiAllowedHosts: List[Hostname] = Nil,
    // #1069: defaulted to the noop so the many direct test constructions keep their arity. The
    // snapshot path gets named schedules via TimeStatusService; PolicyServiceLive needs this repo
    // directly only for the per-host /decision fallback, where the production layer wires the real
    // one.
    namedScheduleRepo: NamedScheduleRepo = NoopNamedScheduleRepo,
    // #1849: when true, `snapshot` reads/writes the computed-snapshot cache and `reevaluate` is
    // active; when false (test default) every `snapshot` rebuilds and `invalidate`/`reevaluate` are
    // no-ops. See the trait docs.
    cacheEnabled: Boolean = false,
    // #1954: test-only barrier run at the END of every `buildSnapshot` (after the DB read, before the
    // result is returned to the caller that installs it in the cache). Defaults to a no-op for
    // production and the ~40 existing specs; PolicySnapshotCacheSpec wires a gate here to suspend an
    // in-flight build deterministically and prove a stale build cannot clobber a fresher cache entry.
    buildBarrier: UIO[Unit] = ZIO.unit,
    // #2137 (multi-tenant P5-6): the billing-status gate. A `lapsed` household gets a PERMISSIVE
    // snapshot — enforcement STOPS (design §5.3, never brick the network) — built through the
    // existing wire fields (no new field, no wire change; the router stays a dumb applier and never
    // learns why). The router-wire routes are NEVER gated on this; the permissive snapshot always
    // serves. Defaulted to "always active" so the ~40 direct test constructions and single-tenant
    // installs (only household 1, seeded `active`) build the normal enforcing snapshot unchanged;
    // the production layer wires the real `HouseholdBillingRepo` read.
    billingStatusOf: HouseholdId => Task[Option[String]] = _ => ZIO.succeed(Some("active")),
) extends PolicyService {

  // #1849: the cached snapshot. Process-local `AtomicReference` (matching the existing
  // `lastSnapshotEtag` style in this class) so the `apply` factory stays a pure constructor.
  // Refreshed by `reevaluate`.
  //
  // #2107 (multi-tenant, epic #622): the cache is keyed BY HOUSEHOLD so one household can never
  // serve another's cached snapshot. Each household's snapshot is scoped to that household's rows, so
  // a shared single slot would let household A's poll read household B's bytes (or vice versa) on a
  // cache hit. For a single-household install the map has exactly one entry (`HouseholdId.Default`),
  // so behavior is identical to the old single-slot cache.
  //
  // #1954: each entry is STAMPED with the `mutationVersion` observed at the START of its build (the
  // leading `Long` of the tuple — the `gen` captured in `buildVersioned`/`reevaluate`). A reader
  // trusts a cached entry only when its stamp is still the
  // current version; a stamp older than the current version means a mutation landed since the build
  // began, so the entry may predate that mutation and is treated as a miss (rebuild). This is what
  // closes the stale-read race that the old "clear-on-invalidate" alone could not: a `buildSnapshot`
  // that began reading the DB BEFORE a mutation could complete AFTER `invalidate` cleared the slot
  // and repopulate it with pre-mutation bytes, so the very next read got a cache HIT on stale data
  // (Gate-1: a just-created profile missing from `/api/router/policy`). With the version stamp, that
  // late write lands tagged with the old version and every reader rejects it — the next read rebuilds
  // synchronously and serves fresh bytes. See #1954.
  private val snapshotCache: AtomicReference[Map[HouseholdId, (Long, ETag, PolicySnapshot)]] =
    new AtomicReference(Map.empty[HouseholdId, (Long, ETag, PolicySnapshot)])

  // #1954: monotonic counter bumped once per policy mutation (every `invalidate`). A build stamps the
  // value it reads here BEFORE it touches the DB; a reader compares a cached stamp against the
  // current value. Because the bump happens-after the mutation's DB commit (routes call `invalidate`
  // after the write), any cached entry whose stamp equals the current version is guaranteed to
  // reflect every committed mutation.
  private val mutationVersion: AtomicLong = new AtomicLong(0L)

  // #1849: the push sink, installed at startup via `setPublisher`. Defaults to noop so a snapshot
  // rebuilt before the registry exists (or in a test) is simply not pushed.
  private val publisher: AtomicReference[PolicySnapshotPublisher] =
    new AtomicReference(PolicySnapshotPublisher.noop)

  // #1849: the last ETag we PUSHED, tracked separately from `snapshotCache` so the push-on-change
  // decision is independent of the REST cache slot. This is what makes `invalidate` safe to clear
  // the cache for immediate REST freshness: even if a racing REST poll rebuilds and repopulates
  // `snapshotCache` with the new ETag before `reevaluate`'s compare runs, `reevaluate` still sees
  // `lastPublishedEtag` at the OLD value and fires the push exactly once. The ticker then dedups
  // against this same value so an unchanged tick never re-pushes.
  private val lastPublishedEtag: AtomicReference[Option[ETag]] =
    new AtomicReference(Option.empty[ETag])

  // #1318: the WifiHaven UI / block-page hosts are fleet-wide always-reachable
  // hosts, so they live in `global.extraAllowed` (carving out every block at the
  // router) rather than being copied into every profile's `extraAllowed`. (#1321
  // moved the curated `infraAllowHosts` onto the same global path, retiring its
  // per-profile copy.) #1775: the legacy DB-backed `global_allow` / `global_blocks`
  // / `global_blocklists` reader was deleted as a no-op (prod tables verified
  // empty 2026-06-16); the curated UI + infra hosts continue to fill
  // `global.extraAllowed` so the wire shape is unchanged.
  private val uiGlobalAllow: List[Hostname] = uiAllowedHosts

  // #1641: process-local last-seen-etag for snapshot-change INFO logging. On every `snapshot`
  // call we atomically compare-and-set the latest etag here; only the winner of the swap emits
  // the `event=snapshot_changed` INFO log carrying the full snapshot JSON. One line per ETag
  // transition (NOT per poll) so volume is bounded by how often policy actually changes.
  // Render retains stdout for ~7 days, giving roughly a week of snapshot history for free —
  // long enough to investigate most incidents (the #1640 motivation). Not a metric (cardinality
  // fence; snapshot JSON is not metric data).
  private val lastSnapshotEtag: AtomicReference[Option[ETag]] =
    new AtomicReference(Option.empty[ETag])

  // #1069/#1482: per-host /decision fallback must see the same schedule downtime as the snapshot —
  // the windows of every block-mode named schedule attached to the profile (as synthetic
  // DbSchedules so `scheduleActiveAt` applies unchanged). Named schedules are the sole source.
  private def schedulesFor(pid: ProfileId): Task[List[DbSchedule]] =
    namedScheduleRepo
      .windowsForProfile(pid)
      .map(
        _.map(w =>
          DbSchedule(ScheduleId(0L), pid, "named-schedule", w.days, w.startLocal, w.endLocal, w.tz),
        ),
      )

  // #1849: cache-aware entry point. On a hit, return the cached snapshot and meter `cache_hit` — no
  // rebuild, which is the #1512 win for both ws and (un-migrated) REST poll agents. On a cold,
  // invalidated, or STALE-stamped cache, rebuild once, store, and serve it. With the cache disabled,
  // always rebuild.
  //
  // #1954: a cached entry is a hit only when its stamp is still the current `mutationVersion` — an
  // older stamp means a mutation landed after the build began, so the entry may be stale and we
  // rebuild. `buildVersioned` captures the version BEFORE reading the DB and stores the entry under
  // that stamp, so a build racing a concurrent mutation can never install bytes the next reader will
  // trust as current.
  def snapshot(household: HouseholdId): Task[PolicySnapshot] =
    if (!cacheEnabled) buildSnapshot(household)
    else
      ZIO.succeed(snapshotCache.get.get(household)).flatMap {
        case Some((builtAt, _, snap)) if builtAt == mutationVersion.get =>
          AppMetrics.recordSnapshotBuild("cache_hit").as(snap)
        case _                                                          => buildVersioned(household)
      }

  def invalidate: UIO[Unit] =
    if (!cacheEnabled) ZIO.unit
    else
      // Bump the version so every cache entry built before this mutation is now stale-stamped and the
      // next REST poll rebuilds immediately (fresh bytes), then reconcile in the background so any
      // connected ws router is pushed the change without blocking the mutating request on a ~500ms
      // rebuild. Bumping (rather than clearing) is what makes this race-safe: an in-flight build that
      // began before this call and completes after it lands stamped with the OLD version, so readers
      // reject it instead of getting a cache HIT on stale bytes (#1954). The push still fires exactly
      // once — `reevaluate` keys its push decision off `lastPublishedEtag`, not the cache slot. The
      // reconcile ticker is a backstop on the same bound.
      ZIO.succeed(mutationVersion.incrementAndGet()) *> reevaluate.forkDaemon.unit

  // #1954: rebuild and install under the version observed BEFORE the DB read. The install is a CAS
  // loop that only replaces an entry whose stamp is <= ours, so a slower build (older stamp) can
  // never overwrite a fresher one (newer stamp) — last-writer-wins is replaced by newest-version-
  // wins. Returns the freshly built snapshot to the caller regardless (it reflects every mutation
  // committed before our DB read began).
  private def buildVersioned(household: HouseholdId): Task[PolicySnapshot] =
    ZIO.succeed(mutationVersion.get).flatMap { gen =>
      buildSnapshot(household).tap(snap => ZIO.succeed(installSnapshot(household, gen, snap)))
    }

  // #2107: install under this household's slot in the per-household cache map. The CAS loop is
  // unchanged from the single-slot version (#1954) except it swaps the household's entry only:
  // a slower build (older stamp) can never overwrite a fresher one for the SAME household.
  private def installSnapshot(household: HouseholdId, gen: Long, snap: PolicySnapshot): Unit = {
    var swapped = false
    while (!swapped) {
      val cur = snapshotCache.get
      cur.get(household) match {
        case Some((builtAt, _, _)) if builtAt > gen =>
          swapped = true // a newer-version entry is already present — don't clobber it
        case _                                      =>
          swapped = snapshotCache.compareAndSet(cur, cur.updated(household, (gen, snap.etag, snap)))
      }
    }
  }

  def reevaluate: UIO[Unit] =
    if (!cacheEnabled) ZIO.unit
    else
      ZIO.succeed(mutationVersion.get).flatMap { gen =>
        // #2107: single-household today — `reevaluate` rebuilds the `HouseholdId.Default` snapshot
        // and pushes it to every connected router (all routers belong to household 1). Per-household
        // push fan-out (rebuild + push each household's snapshot to only its own routers) is tracked
        // in #2120 — deliberately out of this read-scoping change (the REST poll and the ws
        // first-policy push are already household-scoped; only this broadcast is not). `invalidate`
        // bumps the global `mutationVersion`, which stale-stamps EVERY household's cache entry, so a
        // non-default household's next REST poll rebuilds fresh.
        buildSnapshot(HouseholdId.Default).foldZIO(
          err =>
            // Keep the last good cache on a transient build failure (e.g. a DB blip) so the REST poll
            // keeps serving the previous snapshot rather than a cold rebuild storm; the next tick
            // retries.
            ZIO.logWarning(s"policy reevaluate: snapshot rebuild failed, keeping cache: $err"),
          snap => {
            installSnapshot(HouseholdId.Default, gen, snap)
            // Push only when the ETag actually moved since the last PUSH — the same "change is exactly
            // an ETag move" semantics the REST 200-vs-304 path uses (design §6.2), so we never fan out
            // a frame the routers would treat as unchanged. Keyed off `lastPublishedEtag` (not the
            // cache slot) so a racing REST poll repopulating the cache can't suppress the push.
            val prevPublished = lastPublishedEtag.getAndSet(Some(snap.etag))
            ZIO.when(!prevPublished.contains(snap.etag))(publisher.get.publish(snap)).unit
          },
        )
      }

  def setPublisher(p: PolicySnapshotPublisher): UIO[Unit] =
    ZIO.succeed(publisher.set(p))

  private def buildSnapshot(household: HouseholdId): Task[PolicySnapshot] =
    // #2137: a lapsed household gets a permissive snapshot — enforcement stops (§5.3). This is the
    // ONLY billing-aware branch in the enforcement path; the router wire is never gated, the
    // permissive snapshot serves through the existing fields exactly like a household with no policy.
    billingStatusOf(household).flatMap {
      case Some("lapsed") =>
        clock.instant.flatMap { now =>
          val snap = PolicyService.permissiveSnapshot(now)
          // Still meter the build so the computed:cache_hit ratio and the global-policy gauge stay
          // coherent; a permissive snapshot has an empty global section (0 allow hosts, 0 default-deny).
          AppMetrics
            .setGlobalPolicy(snap.global.extraAllowed.size, 0)
            .zipRight(logSnapshotChanged(snap))
            .zipRight(AppMetrics.recordSnapshotBuild("computed"))
            .zipLeft(buildBarrier)
            .as(snap)
        }
      case _              => buildEnforcingSnapshot(household)
    }

  private def buildEnforcingSnapshot(household: HouseholdId): Task[PolicySnapshot] =
    (for {
      // #2107: read only this household's settings/profiles/devices. The blocklist CATALOG
      // (`listCategories` / `loadCategory`) stays global — content is shared across households
      // (design §0.2); only which category ids ship is scoped, and that follows automatically from
      // the scoped profile set via `referencedBlocklistIds` below.
      settings <- householdSettingsRepo.getForHousehold(household)
      now      <- clock.instant
      today = PolicyService.householdLocalDate(now, settings)
      // #1104: today's cap/block state for every profile in one batched read. Same call the
      // /api/time/status/... endpoints use — keeps the snapshot and the UI in lockstep.
      dayStates   <- timeStatusService.dayStateAll(now, today, settings)
      // #1771: snapshot assembly needs the global sentinel too, so it can union the sentinel's
      // resolved extraAllowed/extraBlocked/blocklistIds into every other profile. `listAll`
      // is the user-facing listing and deliberately hides the sentinel.
      allProfiles <- profileRepo.listAllIncludingGlobalForHousehold(household)
      globalProfileOpt = allProfiles.find(_.isGlobal)
      profiles         = allProfiles.filterNot(_.isGlobal)
      devices            <- deviceRepo.listAllForHousehold(household)
      cats               <- blocklistRepo.listCategories
      catDomains         <- ZIO.foreach(cats)(c => blocklistRepo.loadCategory(c).map(c -> _))
      // #1630: every profile's per-app assignments — across all modes — as (assignment × host)
      // rows. Replaces the prior `apps + appHostsRaw + appAssigns` fan-out: that path went
      // through `appRepo.listAssignmentsForProfile` (all modes) AND `appTimeLimitRepo` (filtered
      // to time_limited), so the two readers saw different rows and the projections drifted.
      // Now both the per-app enforcement (extraAllowed/extraBlocked) and the structural
      // projections downstream of `TimeStatusService` read the SAME rows through the same fold.
      appLimitsByProfile <- ZIO.foreach(allProfiles)(p =>
        appTimeLimitRepo.listForProfile(p.id).map(p.id -> _),
      )
      // #1379: per-app schedule rules, resolved to (mode, window) pairs per assignment.
      // Folded into the per-app effective disposition below, before app-mode bucketing.
      appSched           <- ZIO.foreach(allProfiles)(p =>
        appRepo.appScheduleWindowsForProfile(p.id).map(p.id -> _),
      )
      appLimitsMap = appLimitsByProfile.toMap
      appSchedMap = appSched.toMap
    } yield {
      // #1771: resolve the global sentinel's rules via the SAME [[computeBlockRules]] path the
      // per-profile policies use (AGENTS.md §single-source-of-truth — no parallel global-rule
      // computation). The sentinel cannot meaningfully contribute `blocked`/schedules/time-limit/
      // paused/defaultDeny — those are write-rejected at the route layer; we additionally zero
      // them out defensively when reading so a dirty row (e.g. a manual SQL toggle) cannot leak a
      // household-wide block.
      def computeRulesFor(p: Profile): BlockRules = {
        val state           = dayStates.getOrElse(
          p.id,
          ProfileDayState(p.id, today, None, 0, 0, None, blocked = false, None, Nil),
        )
        val dispositions    = ProfileAppDispositions.from(appLimitsMap.getOrElse(p.id, Nil))
        val isScheduleBlock = state.blockReason.contains(MacBlockReason.Schedule)
        val (appAllowedHosts, appBlockedHosts) = dispositions.enforcement(
          schedWindows = appSchedMap.getOrElse(p.id, Map.empty),
          capExhausted = PolicyService.dailyCapExhausted(state),
          now = now,
          isScheduleBlock = isScheduleBlock,
        )
        PolicyService.computeBlockRules(
          profile = p,
          state = state,
          appExtraAllowed = appAllowedHosts,
          appExtraBlocked = appBlockedHosts,
        )
      }

      // #1771: the sentinel's contribution to every profile — just the carve-out / blocklist
      // fields. `blocked` / `blockReason` are deliberately not folded: the global cannot impose a
      // whole-MAC drop on the household (that is what the architecture's per-profile pause /
      // schedule / time-limit lanes are for; #1769).
      val globalRulesResolved: BlockRules = globalProfileOpt.map(computeRulesFor) match {
        case Some(r) =>
          r.copy(blocked = false, blockReason = None)
        case None    => BlockRules.allowAll
      }

      val profilePolicies: Map[ProfileId, ProfilePolicy] = profiles.iterator.map { p =>
        // #1104: cap/block state comes from TimeStatusService — the same value the UI reads.
        // Computed before app bucketing because the #1379 carve gate keys off the raw daily-cap
        // condition (used/limit/extensions), independent of the collapsed blockReason.
        // #763/#764/#1379/#1630: collapse this profile's app assignments into the per-MAC
        // BlockRules buckets via the SINGLE fold `ProfileAppDispositions.enforcement`. Schedule
        // windows fold over base mode (an active window overrides the base); the AllowedDuring
        // carve is gated by the daily cap unless the app is exemptFromDaily (design §4.1, §5).
        // `mode=TimeLimited` apps contribute nothing here — they surface via the per-app cap
        // path (`appCapExhaustedHosts` reads `state.perApp`).
        val ownRules = computeRulesFor(p)

        // #1771: union the global sentinel's resolved extraAllowed / extraBlocked / blocklistIds
        // into every (non-global) profile's rules. Set semantics via `.distinct` after concat —
        // the router's existing extraAllowed-beats-extraBlocked precedence
        // (`feedback_extraallowed_beats_blocked`) means a host that ends up in both lanes via
        // the union still resolves to allow, matching the prior global_* semantics.
        // Under `defaultDeny` the profile's own extraBlocked / blocklistIds are intentionally
        // empty (redundant under block-all per `computeBlockRules`); skip the global union on
        // those two lanes so the wire payload stays consistent with the design intent. The
        // router's whole-MAC drop dominates regardless, so behavior is unchanged either way.
        val rules = ownRules.copy(
          extraAllowed = (ownRules.extraAllowed ++ globalRulesResolved.extraAllowed).distinct,
          extraBlocked =
            if (p.defaultDeny) ownRules.extraBlocked
            else (ownRules.extraBlocked ++ globalRulesResolved.extraBlocked).distinct,
          blocklistIds =
            if (p.defaultDeny) ownRules.blocklistIds
            else (ownRules.blocklistIds ++ globalRulesResolved.blocklistIds).distinct,
        )

        p.id -> ProfilePolicy(name = p.name, rules = rules, failureMode = p.failureMode)
      }.toMap

      // #961: unmanaged-MAC enforcement is applied here at snapshot-build time,
      // not via a new wire field. For devices with no profile assignment we
      // emit explicit per-MAC `rules` keyed off the household policy:
      //   - policy = "block": Manual-blocked. The UI / block-page hosts are no
      //     longer copied here (#1318) — they live in `global.extraAllowed`,
      //     which carves out this block too (it carves out every drop, including
      //     a whole-MAC `blocked`), so the block-page redirect still loads.
      //   - policy = "allow": `rules = None`, same as today (router treats as
      //     unenrolled / allow-all).
      // The router's existing per-MAC override path enforces this without any
      // code change on the openwrt side — the contract fixture already
      // exercises a profileless+blocked device shape.
      val unmanagedRules: Option[BlockRules] =
        if (settings.unmanagedMacPolicy.policy == "block")
          Some(
            BlockRules(
              blocked = true,
              blockReason = Some(MacBlockReason.Unmanaged),
              extraBlocked = Nil,
              extraAllowed = Nil,
              blocklistIds = Nil,
              blockIpOnly = false,
            ),
          )
        else None

      val devicePolicies: Map[MacAddress, DevicePolicy] = devices.iterator.map { d =>
        val rules = if (d.profileId.isEmpty) unmanagedRules else None
        d.mac -> DevicePolicy(profileId = d.profileId, name = d.name, rules = rules)
      }.toMap

      val catDomainsMap                               = catDomains.toMap
      val pBlocklistsAll: Map[BlocklistId, Blocklist] = cats.map { c =>
        val domains = catDomainsMap.getOrElse(c, Set.empty[Hostname]).map(_.value)
        val version = BlocklistVersion.unsafe(PolicyService.blocklistContentVersion(domains))
        c -> Blocklist(version = version, url = BlocklistUrl.unsafe(s"/api/blocklists/${c.value}"))
      }.toMap

      // #1318/#1321: assemble the fleet-wide always-reachable host set from the
      // two in-code sources:
      //   - `uiGlobalAllow` — the per-deployment UI / block-page hosts (#1318).
      //   - `infraAllowHosts` — the curated connectivity-check / OCSP / PKI /
      //     captive-portal / gvt2 device-level deps (#1307), which #1321 stops
      //     copying into every profile's `extraAllowed` and ships here ONCE.
      // The router applies `global.extraAllowed` as the top of the precedence
      // ladder (@global_allow, #1319), carving every drop out for every MAC — so
      // these hosts beat every block path identically to the old per-(MAC) ea_
      // copies, with no per-profile duplication and a single ETag-moving source.
      // #1775: the prior DB-backed `global_allow` / `global_blocks` /
      // `global_blocklists` reader was removed (prod tables verified empty
      // 2026-06-16); only the in-code allow set survives. The sentinel-profile
      // path lands in #1771.
      // #1771: the wire `global.extraAllowed` is the union of the sentinel profile's resolved
      // extraAllowed plus the in-code UI + infra host sets. `extraBlocked` and `blocklistIds`
      // additionally carry the sentinel's contribution so a household-wide block list takes
      // effect on every device. `blocked` / `blockReason` stay zeroed — the global section
      // never imposes a whole-MAC drop (the architecture's per-profile lanes do that).
      val globalRules = BlockRules.allowAll.copy(
        extraAllowed =
          (globalRulesResolved.extraAllowed ++ uiGlobalAllow ++ PolicyService.infraAllowHosts).distinct,
        extraBlocked = globalRulesResolved.extraBlocked.distinct,
        blocklistIds = globalRulesResolved.blocklistIds.distinct,
      )

      // #1784: scope `blocklists` to only those referenced by some profile (or by the global
      // section / unmanaged-MAC rules). The router fetches and renders every id in this map, so
      // shipping unreferenced lists is wasted work — on prod the difference is hundreds of
      // thousands of directives vs hundreds (see #1412 investigation). Additive-safe on the wire:
      // the agent already iterates whatever ids appear here.
      // The per-device override lane (`devicePolicies → rules → blocklistIds`) is wired in for
      // future-proofing — today the only writer of `DevicePolicy.rules` is the unmanaged-MAC path
      // (above), which sets `blocklistIds = Nil`, so this branch currently contributes nothing.
      val referencedBlocklistIds: Set[BlocklistId] =
        (globalRules.blocklistIds.iterator ++
          profilePolicies.valuesIterator.flatMap(_.rules.blocklistIds) ++
          devicePolicies.valuesIterator.flatMap(_.rules.iterator.flatMap(_.blocklistIds))).toSet
      val pBlocklists: Map[BlocklistId, Blocklist] =
        pBlocklistsAll.view.filterKeys(referencedBlocklistIds).toMap

      val core =
        SnapshotCore(
          globalRules,
          devicePolicies,
          profilePolicies,
          pBlocklists,
          settings.blockEncryptedDns,
        )
      val etag = PolicyService.computeEtag(core)
      val snap = PolicySnapshot(
        etag = etag,
        generatedAt = now.toString,
        global = globalRules,
        devices = devicePolicies,
        profiles = profilePolicies,
        blocklists = pBlocklists,
        // #1912 / #1909: network-wide bypass-disable flag, straight from the
        // household setting. Curated host/IP lists are baked in the agent.
        blockEncryptedDns = settings.blockEncryptedDns,
      )
      (snap, profiles.count(_.defaultDeny))
    }).flatMap { case (snap, defaultDenyProfiles) =>
      // #1318: surface the global-section size + default-deny profile count for operators.
      // #1849: every actual build is a `computed` sample of `policy_snapshot_build_total` — the
      // cache-hit counterpart is metered in `snapshot`. The computed:cache_hit ratio is what proves
      // the cache is doing its job (cache_hit should dominate once the ticker keeps it warm).
      AppMetrics
        .setGlobalPolicy(snap.global.extraAllowed.size, defaultDenyProfiles)
        .zipRight(logSnapshotChanged(snap))
        .zipRight(AppMetrics.recordSnapshotBuild("computed"))
        // #1954: test-only suspension point (no-op in production); see `buildBarrier`.
        .zipLeft(buildBarrier)
        .as(snap)
    }

  /**
   * #1641: emit an INFO log line carrying the full snapshot JSON the first time we see a given ETag
   * in this process. Atomic compare-and-set against `lastSnapshotEtag` — only the caller who
   * swapped a new etag in logs, so concurrent pollers cannot duplicate the line. One line per ETag
   * transition (not per poll), bounded by how often policy actually changes.
   */
  private def logSnapshotChanged(snap: PolicySnapshot): UIO[Unit] = ZIO.suspendSucceed {
    val prev    = lastSnapshotEtag.get()
    val changed = !prev.contains(snap.etag) && lastSnapshotEtag.compareAndSet(prev, Some(snap.etag))
    if (!changed) ZIO.unit
    else {
      val json = snap.toJson
      // #602: structured context — etag/op as annotations so ops can filter the
      // snapshot_changed stream without parsing the message.
      LogContext.annotateAll(
        LogContext.Op   -> "snapshot_changed",
        LogContext.Etag -> snap.etag.value,
      ) {
        ZIO.logInfo(
          s"event=snapshot_changed etag=${snap.etag.value} " +
            s"prevEtag=${prev.map(_.value).getOrElse("none")} " +
            s"bytes=${json.length} snapshot=$json",
        )
      }
    }
  }

  // #2107: the household *authorizes* access to a shared category list — the list CONTENT is a
  // global, non-tenant resource (`blocklist_domains` has no `household_id`; the same curated ad/
  // tracker domains are served to every household, design §0.2). So the household is threaded for
  // API symmetry with `snapshot`/`decide` and to make the shared-catalog decision explicit and
  // test-pinnable (pin 5: two households fetch the same category and get byte-identical content),
  // but it deliberately does NOT filter the bytes — there is nothing household-private to scope. The
  // WHICH-lists-ship scoping already happens on the snapshot via the household's `referencedBlocklistIds`;
  // the router only ever fetches ids it saw there.
  def renderBlocklist(
      @scala.annotation.unused household: HouseholdId,
      id: BlocklistId,
  ): Task[Option[(ETag, String)]] =
    for {
      domains <- blocklistRepo.loadCategory(id)
    } yield
      if domains.isEmpty then None
      else {
        val sorted  = domains.toList.map(_.value).sorted
        val version = PolicyService.blocklistContentVersion(sorted)
        val sb      = new StringBuilder
        sb.append(s"# version: $version\n")
        sorted.foreach(d => sb.append(s"$d\n"))
        val body    = sb.toString
        val etag    = ETag.unsafe(s"\"${PolicyService.sha256Hex(body).take(16)}\"")
        Some((etag, body))
      }

  /**
   * Per-host fallback decision. Reads DB rows directly rather than going through the snapshot,
   * since the snapshot's collapsed BlockRules no longer carries the raw schedule / site-limit /
   * category state needed to make a per-host decision. Precedence: allowed-app > paused > schedule
   * > blocked-app > site_time_limit > time_limit > category > allow.
   *
   * #1413/#421: allowed-app (extraAllowed) is checked FIRST so it beats every whole-MAC block path
   * — including Paused and Schedule — exactly as the snapshot/router `@blocked_macs` carve-out does
   * (`ip daddr != @ea_<m>_<a>`). Keeping this endpoint's verdict consistent with what nftables
   * actually enforces is the whole point of the invariant; otherwise an explicitly-allowed app
   * would be reported (and, on agents that consult this endpoint, enforced) as blocked while
   * paused.
   */
  def decide(
      household: HouseholdId,
      mac: String,
      hostname: String,
  ): Task[RouterDecisionResponse] =
    for {
      // #2107: scope the settings + device lookup to the router's household. The device's
      // `profileId` (resolved below) is therefore already this household's profile, so the
      // downstream `profileRepo.findById(pid)` needs no separate predicate — a MAC that exists in
      // another household is simply not found here (degrades to allow-all NoProfile), never
      // resolving the other household's same-MAC row.
      settings <- householdSettingsRepo.getForHousehold(household)
      now      <- clock.instant
      today = PolicyService.householdLocalDate(now, settings)
      device <- deviceRepo
        .listAllForHousehold(household)
        .map(_.find(_.mac.value.equalsIgnoreCase(mac)))
      result <- device.flatMap(_.profileId) match {
        case None      =>
          ZIO.succeed(
            RouterDecisionResponse(
              ConnectionDecision.Allow,
              BlockReason.asWire(BlockReason.NoProfile),
              None,
            ),
          )
        case Some(pid) =>
          for {
            pOpt            <- profileRepo.findById(pid)
            scheds          <- schedulesFor(pid)
            // #1544: the per-profile aggregation (used/extension minutes, the daily-cap-exhausted
            // gate, per-site usage, and the paused→schedule→time-limit precedence) is read from the
            // SAME `ProfileDayState` the snapshot consumes — `TimeStatusService.todaysState` — rather
            // than re-folded by hand here. This collapses the largest hand-maintained mirror in the
            // policy code (the old `siteGroups`/`byApp`/`exemptPats`/`perMacTot`/`totalMins`/
            // `capExhausted` re-derivation) so the block-page reason and the snapshot's nftables
            // enforcement cannot drift. Only the genuinely per-HOST matching stays below.
            state           <- timeStatusService.todaysState(now, settings, pid)
            // #1630: read the unified `appTimeLimitRepo` rows (all modes) so the per-host
            // /decision fallback shares the same fold as the snapshot via
            // `ProfileAppDispositions.from`. Before #1630 this path fetched `appAssigns` +
            // `appHostsByApp` and called the legacy `expandAppDispositions`, while
            // `TimeStatusService` read a `mode='time_limited'`-filtered version of the same
            // table — the divergence behind #1630.
            appLimits       <- appTimeLimitRepo.listForProfile(pid)
            // #1379: per-app schedule windows for this profile's assignments, used to fold each
            // app's effective disposition + carve gate exactly as the snapshot does.
            appSchedWindows <- appRepo.appScheduleWindowsForProfile(pid)
            res             <- pOpt match {
              case None    =>
                ZIO.succeed(
                  RouterDecisionResponse(
                    ConnectionDecision.Allow,
                    BlockReason.asWire(BlockReason.NoProfile),
                    None,
                  ),
                )
              case Some(p) =>
                val h = hostname.toLowerCase.stripSuffix(".")

                // #1544: the canonical day state for this profile (same source as the snapshot).
                // `todaysState` returns `Some` for any existing profile; default defensively to an
                // empty state so a race that deletes the profile mid-decision degrades to allow-all
                // rather than failing.
                val dayState = state.getOrElse(
                  ProfileDayState(pid, today, None, 0, 0, None, blocked = false, None, Nil),
                )

                // #1379: the daily-cap-exhausted condition the AllowedDuring carve gate keys off —
                // read off the shared state (`dailyCapExhausted`) instead of re-folding the per-MAC
                // totals, so it agrees with the snapshot's gate by construction.
                val capExhausted = PolicyService.dailyCapExhausted(dayState)

                // #1379/#1630: fold each app's effective disposition (schedule windows over base
                // mode) + the carve gate via the SINGLE fold `ProfileAppDispositions.enforcement`
                // — exactly as the snapshot does. A non-exempt allowed_during app whose cap is
                // exhausted is NOT carved, so it correctly falls through to the time_limit block
                // below; an exempt one (or one under cap) is carved and beats it.
                // #1679/#1742: read the carve gate off the precedence-collapsed `dayState.blockReason`
                // — the SAME source the snapshot's own `isScheduleBlock` reads (via
                // `state.blockReason.contains(Schedule)`). This collapses the prior drift where this path re-derived schedule
                // activity from raw schedules (`scheduleBlock(scheds, now).nonEmpty`) and disagreed
                // with the snapshot on a Paused+schedule-active profile (Paused outranks Schedule in
                // the documented precedence — AGENTS.md §Architectural model — so the toggle must
                // NOT fire there; #1679 is schedule-scope only). `scheduleBlock(scheds, now)` is
                // still consulted below for the actual whole-MAC schedule short-circuit (after the
                // pause check, so its semantics match the precedence by construction).
                val isScheduleBlock          =
                  dayState.blockReason.contains(MacBlockReason.Schedule)
                val (appAllowed, appBlocked) =
                  ProfileAppDispositions
                    .from(appLimits)
                    .enforcement(appSchedWindows, capExhausted, now, isScheduleBlock)

                // #1515: the per-profile carve set the snapshot puts in `extraAllowed`, reproduced
                // here so /decision agrees with what nftables enforces. It is the allowed/allowed_during
                // app hosts PLUS the whole host-set of every exempt-from-daily app still under its own
                // per-app cap (`exemptUnderCapHosts`) — the latter is the #1513 fix at the /decision
                // layer: an exempt app under cap must beat a whole-MAC pause/schedule block, so it has
                // to be checked BEFORE the pause/schedule short-circuits below. Under a HARD pause the
                // snapshot empties the per-profile `extraAllowed` (only `global` survives), so we drop
                // the carve here too — keeping the two readings identical (#1532).
                val isHardPause    =
                  p.pauseMode == PauseMode.Hard &&
                    dayState.blockReason.contains(MacBlockReason.Paused)
                val profileAllowed =
                  if isHardPause then Nil
                  else (appAllowed ++ PolicyService.exemptUnderCapHosts(dayState)).distinct

                // #1413/#421: extraAllowed beats EVERY whole-MAC block — incl.
                // Paused/Schedule — so check the allowed-app list first, before
                // the pause/schedule short-circuits. This matches the snapshot/
                // router `ip daddr != @ea_<m>_<a>` carve-out.
                if matchesAny(h, profileAllowed) then
                  ZIO.succeed(
                    RouterDecisionResponse(
                      ConnectionDecision.Allow,
                      BlockReason.asWire(BlockReason.ExtraAllowed),
                      None,
                    ),
                  )
                else if p.paused then
                  ZIO.succeed(
                    RouterDecisionResponse(
                      ConnectionDecision.Block,
                      BlockReason.asWire(MacBlockReason.Paused),
                      None,
                    ),
                  )
                else
                  scheduleBlock(scheds, now) match {
                    case Some(r) => ZIO.succeed(r)
                    case None    =>
                      if matchesAny(h, appBlocked) then
                        ZIO.succeed(
                          RouterDecisionResponse(
                            ConnectionDecision.Block,
                            BlockReason.asWire(BlockReason.ExtraBlocked),
                            None,
                          ),
                        )
                      else
                        timeLimitBlockFromState(h, now, settings, dayState) match {
                          case Some(r) => ZIO.succeed(r)
                          // #1980: a TimeLimited app host still within its own budget beats a
                          // category-blocklist drop. Checked AFTER pause / schedule / blocked-app /
                          // time_limit (so it stays subordinate to every whole-MAC and per-app cap
                          // block — the ordering is the /decision analogue of the snapshot's
                          // `!state.blocked` gate) but BEFORE `categoryBlock`, mirroring the
                          // snapshot's `extraAllowed`-beats-`bl_` precedence. Shares the single
                          // `timeLimitedUnderCapHosts` definition with the snapshot (#1532).
                          case None
                              if matchesAny(
                                h,
                                PolicyService.timeLimitedUnderCapHosts(dayState),
                              ) =>
                            ZIO.succeed(
                              RouterDecisionResponse(
                                ConnectionDecision.Allow,
                                BlockReason.asWire(BlockReason.ExtraAllowed),
                                None,
                              ),
                            )
                          case None    =>
                            categoryBlock(p.blockedCategories, h).map {
                              case Some(cat) =>
                                RouterDecisionResponse(
                                  ConnectionDecision.Block,
                                  BlockReason.asWire(BlockReason.Category(cat)),
                                  None,
                                )
                              case None      =>
                                // #1545: BlockReason.Allow's canonical asWire is "allow"; this
                                // allow-path has historically emitted the "allowed" alias on the
                                // wire and the back-compat rules forbid changing it (fromWire
                                // accepts both → Allow), so this single case stays a literal.
                                RouterDecisionResponse(ConnectionDecision.Allow, "allowed", None)
                            }
                        }
                  }
            }
          } yield res
      }
    } yield result

  private def scheduleBlock(
      schedules: List[DbSchedule],
      now: Instant,
  ): Option[RouterDecisionResponse] = {
    schedules.find(s => PolicyService.scheduleActiveAt(s, now)).map { s =>
      // expiresAt for an active schedule = the next instant the window's `endLocal`
      // occurs in the schedule's tz. For overnight windows where we're past startLocal
      // it's tomorrow's endLocal; otherwise it's today's endLocal (which may be in the
      // past for the "tail" of a previous day's overnight window — handled below).
      val expiresAt = PolicyService.scheduleEndInstantAfter(s, now)
      RouterDecisionResponse(
        ConnectionDecision.Block,
        BlockReason.asWire(MacBlockReason.Schedule),
        Some(expiresAt.toString),
      )
    }
  }

  /**
   * #1544: the per-host site-limit / daily-limit verdict, read off the shared [[ProfileDayState]]
   * (`state.perApp` for per-app usage + limits, `state.usedMinutes`/`dailyLimitMinutes`/
   * `extensionMinutes` for the daily cap) rather than re-aggregating from raw rows. The per-HOST
   * part — matching `hostname` against an app's host-set — stays here; the AGGREGATION comes from
   * `TimeStatusService`, so this path can no longer drift from the snapshot's
   * `appLimitExtraBlocked` / `assemble` cap evaluation. `state.perApp` carries one entry per app,
   * with `usedMinutes` aggregated across the whole host-set and `dailyLimitMinutes` the app's site
   * cap — exactly the `sd.usedMinutes >= sd.dailyLimitMinutes` test the snapshot uses to fill
   * `extraBlocked`.
   */
  private def timeLimitBlockFromState(
      hostname: String,
      now: Instant,
      settings: HouseholdSettings,
      state: ProfileDayState,
  ): Option[RouterDecisionResponse] = {
    // Time-limit blocks expire at the next household daily-reset Instant.
    val resetAt     = PolicyService.nextDailyResetAfter(settings, now).toString
    val appLimitHit = state.perApp.find { sd =>
      // #1899 (shared-hosts S4): match only the app's DISTINCTIVE hosts — a shared backend is never
      // app-cap-blocked, so the /decision fallback agrees with the snapshot's `appCapExhaustedHosts`
      // (which also reads `distinctiveHosts`). Keeps the two paths from diverging (#1532).
      sd.distinctiveHosts.exists(hp => HostMatch.matchesPattern(hostname, hp)) &&
      // #1627: same Option-aware exhaustion check as `appCapExhaustedHosts`.
      sd.dailyLimitMinutes.exists(lim => sd.usedMinutes >= lim)
    }
    appLimitHit
      .map(sd =>
        RouterDecisionResponse(
          ConnectionDecision.Block,
          BlockReason.asWire(BlockReason.AppTimeLimit(sd.label)),
          Some(resetAt),
        ),
      )
      .orElse {
        // #1515: no exempt-app guard is needed here anymore. An exempt-from-daily app still under
        // its own cap is carved into `profileAllowed` and allowed upstream (before this runs), and
        // one over its cap is caught by `appLimitHit` above — so by the time we reach the daily cap
        // the host is never daily-exempt. The daily-cap predicate is the shared
        // [[dailyCapExhausted]] (the same one the snapshot's `state.blocked` / TimeLimit reason
        // folds), so the per-host /decision and the snapshot can't fold the daily cap differently
        // (#1532).
        Option.when(PolicyService.dailyCapExhausted(state))(
          RouterDecisionResponse(
            ConnectionDecision.Block,
            BlockReason.asWire(MacBlockReason.TimeLimit),
            Some(resetAt),
          ),
        )
      }
  }

  private def categoryBlock(
      cats: List[BlocklistId],
      hostname: String,
  ): Task[Option[BlocklistId]] =
    blocklistRepo.loadAll.map { allLists =>
      cats.find { cat =>
        val list = allLists.getOrElse(cat, Set.empty)
        matchesDomainOrParent(hostname, list.map(_.value))
      }
    }

  private def matchesAny(domain: String, patterns: List[Hostname]): Boolean =
    patterns.exists(p => HostMatch.matchesPattern(domain, p.value))

  // Pattern matching is FQDN-only by design (#391). The decision endpoint
  // receives `RouterDecisionRequest.hostname: Hostname`, which the type system
  // already constrains to FQDN-shape (Hostname.parse rejects IPv4 literals),
  // so an IP literal can't even reach this matcher. Shared with Presence and
  // UsageTraffic via HostMatch (#1085).
  private def matchesDomainOrParent(domain: String, list: Set[String]): Boolean =
    HostMatch.hasApexMatch(domain, list)

}

private case class SnapshotCore(
    global: BlockRules,
    devices: Map[MacAddress, DevicePolicy],
    profiles: Map[ProfileId, ProfilePolicy],
    blocklists: Map[BlocklistId, Blocklist],
    blockEncryptedDns: Boolean,
)

object PolicyService {
  val layer: ZLayer[
    AppConfig & ProfileRepo & NamedScheduleRepo & HouseholdSettingsRepo & TimeLimitRepo &
      AppTimeLimitRepo & DeviceRepo & BlocklistRepo & TrafficReportRepo & TimeExtensionRepo &
      AppRepo & TimeStatusService & Clock & wifihaven.api.db.HouseholdBillingRepo,
    Nothing,
    PolicyService,
  ] = ZLayer.fromFunction {
    (
        cfg: AppConfig,
        pr: ProfileRepo,
        nsr: NamedScheduleRepo,
        hsr: HouseholdSettingsRepo,
        tlr: TimeLimitRepo,
        atlr: AppTimeLimitRepo,
        dr: DeviceRepo,
        blr: BlocklistRepo,
        trr: TrafficReportRepo,
        er: TimeExtensionRepo,
        ar: AppRepo,
        tss: TimeStatusService,
        clk: Clock,
        hbr: wifihaven.api.db.HouseholdBillingRepo,
    ) =>
      new PolicyServiceLive(
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
        cfg.policy.uiAllowedHostsParsed,
        nsr,
        // #1849: production turns the computed-snapshot cache ON. The REST poll path then reads the
        // cache (#1512), and the reconcile ticker (Main) + `invalidate` (mutating routes) keep it
        // fresh and drive the push-on-change to connected ws routers.
        cacheEnabled = true,
        // #2137: the billing-status gate reads the household's status from HouseholdBillingRepo. A
        // read blip fails toward enforcing (None ⇒ not lapsed ⇒ normal snapshot) so a transient DB
        // hiccup never flips the whole fleet permissive.
        billingStatusOf = hid =>
          hbr
            .findByHousehold(hid)
            .map(_.map(_.status))
            .catchAll { e =>
              ZIO
                .logWarning(
                  s"policy: billing-status read failed for household ${hid.value}: ${e.getMessage}",
                )
                .as(None)
            },
      )
  }

  /**
   * #1307: curated infrastructure hosts that every device needs reachable for an allowed-mode app
   * to actually work — connectivity-check probes, CA OCSP/CRL responders, and the Apple/Google edge
   * CDNs that serve those. The whole-MAC `@blocked_macs` drop (paused / schedule / daily-limit)
   * only spares each profile's explicit `extraAllowed` hosts, so without these an allowed app's
   * apex host resolves while its transitive dependencies are dropped and the app *appears* blocked.
   *
   * #1321: these ship ONCE via `global.extraAllowed` (unioned in by `PolicyServiceLive.snapshot`),
   * not copied into every profile. The router applies the global section as the top of its
   * precedence ladder (@global_allow, #1319), carving every drop out for every MAC — so they still
   * beat the block exactly as the old per-(MAC) ea_ copies did, now from a single fleet-wide source
   * with no per-profile duplication. This stays deliberately functional, not a new snapshot field —
   * the router needs the hosts, not the reason they're allowed (#1311).
   *
   * #1503: the host list itself is now derived from the single canonical [[InfraHosts.canonical]]
   * list, shared with the `Presence` suppression set — "infra we must always allow" and "infra that
   * must not count as engagement" are the same device-level set, and maintaining two hand-curated
   * copies let them drift (the #1499 over-count leak). The device-infra-only boundary (no rotating
   * per-app CDN hosts such as `*.akamai.net` / `*.fastly.net`, which attribute to the app via its
   * branded domains) and the per-app-CDN rationale now live on [[InfraHosts]] and are unchanged.
   */
  val infraAllowHosts: List[Hostname] = InfraHosts.canonical.map(Hostname.unsafe)

  /** Content-derived version: first 16 hex chars of SHA-256 over sorted domain list. */
  def blocklistContentVersion(domains: Iterable[String]): String = {
    val body = domains.toList.sorted.mkString("\n")
    sha256Hex(body).take(16)
  }

  def sha256Hex(s: String): String = {
    val md = MessageDigest.getInstance("SHA-256")
    md.digest(s.getBytes("UTF-8")).map("%02x".format(_)).mkString
  }

  def hashToken(raw: String): Sha256Hex = Sha256Hex.unsafe(sha256Hex(raw))

  /**
   * #2137: the permissive (allow-all) snapshot served for a `lapsed` household — enforcement stops
   * (design §5.3). Empty everything (global inert, no devices/profiles/blocklists, encrypted-DNS
   * block off): functionally identical to a household with no policy, i.e. as if the router had
   * been removed. Built through the existing wire fields — no new field, no wire change. The ETag
   * is computed over the same [[SnapshotCore]] path so cache/publish semantics are unchanged.
   */
  def permissiveSnapshot(now: java.time.Instant): PolicySnapshot = {
    val core = SnapshotCore(
      global = BlockRules.allowAll,
      devices = Map.empty,
      profiles = Map.empty,
      blocklists = Map.empty,
      blockEncryptedDns = false,
    )
    PolicySnapshot(
      etag = computeEtag(core),
      generatedAt = now.toString,
      global = BlockRules.allowAll,
      devices = Map.empty,
      profiles = Map.empty,
      blocklists = Map.empty,
      blockEncryptedDns = false,
    )
  }

  /** Deterministic ETag over snapshot logical content. */
  private[policy] def computeEtag(core: SnapshotCore): ETag = {
    val parts = scala.collection.mutable.ArrayBuffer.empty[String]
    // #1318: the global section is part of the snapshot's logical content, so a change to it must
    // move the ETag (and re-poll the fleet) even when no device/profile changed.
    parts += s"global:${blockRulesSig(core.global)}"
    core.devices.toList.sortBy(_._1.value).foreach { case (mac, d) =>
      val ruleSig = d.rules.fold("-")(blockRulesSig)
      parts += s"dev:${mac.value}|${d.profileId.map(_.value).getOrElse("-")}|${d.name}|$ruleSig"
    }
    core.profiles.toList.sortBy(_._1.value).foreach { case (pid, pp) =>
      parts += s"p:${pid.value}|${pp.name}|fm:${FailureMode
          .asString(pp.failureMode)}|${blockRulesSig(pp.rules)}"
    }
    core.blocklists.toList
      .sortBy(_._1.value)
      .foreach((k, v) => parts += s"bl:${k.value}=${v.version.value}")
    // #1912: the network-wide bypass-disable flag is logical snapshot content,
    // so toggling it must move the ETag and re-poll the fleet.
    parts += s"bed:${core.blockEncryptedDns}"
    ETag.unsafe("\"sha256:" + sha256Hex(parts.mkString("\n")) + "\"")
  }

  private def blockRulesSig(r: BlockRules): String = {
    val reason = r.blockReason.map(MacBlockReason.asString).getOrElse("-")
    val eb     = r.extraBlocked.map(_.value).sorted.mkString(",")
    val ea     = r.extraAllowed.map(_.value).sorted.mkString(",")
    val bl     = r.blocklistIds.map(_.value).sorted.mkString(",")
    s"b=${r.blocked}|r=$reason|eb=$eb|ea=$ea|bl=$bl|ip=${r.blockIpOnly}"
  }

  /**
   * #354 / #1104: collapse a profile's `ProfileDayState` plus its app & site-limit context into the
   * effective `BlockRules` served in the snapshot. `state` carries the canonical `blocked` and
   * `blockReason` already evaluated by `TimeStatusService.fold` (same precedence Paused > Schedule
   * > TimeLimit). This function only adds the app/site-limit wiring around it.
   *
   * #1318: two changes.
   *   1. The UI / block-page hosts are NO LONGER unioned here — they moved to `global.extraAllowed`
   *      (see `PolicyServiceLive.uiGlobalAllow`), which carves out every block fleet-wide. 2.
   *      Default-deny (`profile.defaultDeny`): collapse to block-all (`blocked = true` +
   *      `DefaultDeny` reason, unless a stronger Paused/Schedule/TimeLimit reason already applies)
   *      with only the profile/device allow list reachable. `extraBlocked` / `blocklistIds` are
   *      omitted because they are redundant under block-all (design §4). `blockIpOnly` still
   *      applies — default-deny + `blockIpOnly` is the strictest combination.
   */
  private[policy] def computeBlockRules(
      profile: Profile,
      state: ProfileDayState,
      appExtraAllowed: List[Hostname] = Nil,
      appExtraBlocked: List[Hostname] = Nil,
  ): BlockRules = {
    // #1505/#1515: per-app cap exhaustion blocks the app's WHOLE host-set together — `state.perApp`
    // carries one entry per app with usage aggregated (gap-bridged) across the whole host-set, so
    // when that aggregate hits the limit ALL of the app's hosts go to extraBlocked, not just the one
    // whose traffic crossed. Shared with the /decision fallback via `appCapExhaustedHosts` so the
    // two cannot diverge (#1532).
    val appLimitExtraBlocked: List[Hostname] = appCapExhaustedHosts(state)

    // #1105/#1515: time_limited app hosts with exemptFromDaily=true carve around the MAC-level
    // @blocked_macs drop while the app's aggregate is still under its own cap — for the WHOLE
    // host-set. The exempt flag's original role was just to exclude the app from the daily tally;
    // without this carve-out, hitting the profile cap silently dropped the exempt app too, violating
    // the "Khan doesn't count" contract (#1513). Naturally transitions allow → block as the app's
    // aggregate exhausts (appLimitExtraBlocked above takes over and extraAllowed-beats-extraBlocked
    // at the router; see feedback_extraallowed_beats_blocked). Shared with /decision via
    // `exemptUnderCapHosts` (#1532).
    val appExemptAllowedHosts: List[Hostname] = exemptUnderCapHosts(state)

    // #1418: hard pause is a true off-switch. When this profile is paused AND
    // its pause_mode is `hard`, drop even the app/exempt/infra carve-outs — only
    // the deployment's UI hosts survive so the kid still sees the block page and
    // the admin SPA loads on the household LAN. We gate on the *effective* block
    // reason being Paused (not merely the flag) so the hard mode only bites when
    // the profile is actually paused; a hard-mode profile blocked for some other
    // reason (schedule, time limit) keeps the normal soft carve-outs. The router
    // stays oblivious: an empty `extraAllowed` simply means no `ea_` carve-out,
    // so the @blocked_macs drop is unconditional — no "hard vs soft" on the wire.
    val isHardPause =
      profile.pauseMode == PauseMode.Hard && state.blockReason.contains(MacBlockReason.Paused)

    // #763: app expansion is additive. A host in both an allowed-mode app and
    // a blocked-mode app will appear in both lists; the router's
    // extraAllowed-beats-extraBlocked precedence then makes "allow wins" — same
    // semantics it already applies to the per-profile own lists (see
    // feedback_extraallowed_beats_blocked).
    //
    // #1321: the curated infra allowlist (connectivity-check / OCSP / CDN deps
    // of an allowed app) is NO LONGER copied per-profile. It moved to
    // `global.extraAllowed` (see `PolicyServiceLive.snapshot`), which the router
    // carves out of every drop for every MAC (@global_allow, #1319) — so infra
    // hosts still beat every block path, now from a single fleet-wide source
    // instead of duplicated into each profile's `extraAllowed`. The deployment UI
    // hosts moved to the global section earlier (#1318) for the same reason.
    //
    // #1418: under a hard pause, drop even the app/exempt carve-outs — per-profile
    // `extraAllowed` goes empty so the router drops the MAC unconditionally (no
    // `ip daddr != @ea_…`). The always-reachable global hosts (UI / block page +
    // admin SPA, and now the infra allowlist) still survive because they live in
    // `global.extraAllowed`, the top of the router's precedence ladder (#1319) —
    // so a hard pause remains a true off-switch for every per-profile carve-out,
    // sparing only the fleet-wide always-reachable set.
    // #1980: a TimeLimited app still within its own per-app budget carves its FULL host-set (main +
    // shared) into extraAllowed so it beats category-blocklist membership at the router (#421). Gated
    // on the profile NOT being whole-MAC blocked: unlike the exempt carve above (which beats
    // pause/schedule/daily-limit), this allow only outranks the per-host blocklist drop and stays
    // subordinate to the whole-MAC block paths — the time budget governs *when* the host is
    // reachable, the blocklist never *whether*. When the app's own cap exhausts it drops out of
    // `timeLimitedUnderCapHosts` and `appLimitExtraBlocked` takes over (the redundant blocklist drop
    // is fine).
    val timeLimitedUnderCap =
      if (state.blocked) Nil else timeLimitedUnderCapHosts(state)

    val profileExtraAllowed =
      if (isHardPause) Nil
      else (appExtraAllowed ++ appExemptAllowedHosts ++ timeLimitedUnderCap).distinct

    if (profile.defaultDeny)
      // #1318: default-deny baseline. Block-all with only the profile/device
      // allow list reachable; `global.extraAllowed` carves out separately at the
      // router. `DefaultDeny` is the lowest-precedence reason, so a concurrent
      // Paused/Schedule/TimeLimit (already folded into `state`) wins the
      // block-page copy. extraBlocked/blocklistIds omitted — redundant under
      // block-all.
      BlockRules(
        blocked = true,
        blockReason = state.blockReason.orElse(Some(MacBlockReason.DefaultDeny)),
        extraBlocked = Nil,
        extraAllowed = profileExtraAllowed,
        blocklistIds = Nil,
        blockIpOnly = profile.blockIpOnly,
      )
    else
      BlockRules(
        blocked = state.blocked,
        blockReason = state.blockReason,
        extraBlocked = (appExtraBlocked ++ appLimitExtraBlocked).distinct,
        extraAllowed = profileExtraAllowed,
        blocklistIds = profile.blockedCategories,
        blockIpOnly = profile.blockIpOnly,
      )
  }

  // ── #1379: per-app schedules ──────────────────────────────────────────────

  /**
   * The daily-cap-exhausted condition the AllowedDuring carve gate (§4.1, §5) keys off — computed
   * directly from `ProfileDayState` (used / limit / extensions), independent of the collapsed
   * `blockReason`. This matters because when schedule downtime and cap exhaustion coincide,
   * `blockReason` reports `Schedule` (higher precedence) yet the budget is still exhausted, so the
   * gate must read the raw cap condition, not the reason.
   */
  def dailyCapExhausted(state: ProfileDayState): Boolean =
    state.dailyLimitMinutes.exists(lim => state.usedMinutes >= lim + state.extensionMinutes)

  /**
   * #1515: the whole host-set of every per-app cap that is exhausted today — `usedMinutes` (the
   * gap-bridged aggregate across the app's whole host-set, from `appSecondsForProfile`) has reached
   * the app's daily limit. These hosts go to `extraBlocked` together so the router drops the WHOLE
   * app, not just the one host whose traffic crossed. The single per-app cap-block computation,
   * shared by the snapshot ([[computeBlockRules]]) and the per-host /decision fallback so the two
   * cannot diverge (#1532). Per-app caps take no extensions — those are profile-level and apply to
   * the daily total ([[dailyCapExhausted]]), not the per-app budget.
   */
  private[policy] def appCapExhaustedHosts(state: ProfileDayState): List[Hostname] =
    state.perApp.collect {
      // #1627: `dailyLimitMinutes = None` means "no per-app cap configured" —
      // never exhausted. Only an app with `Some(lim)` and `usedMinutes >= lim`
      // contributes hosts to extraBlocked here.
      // #1899 (shared-hosts S4): only the app's DISTINCTIVE hosts are block-eligible. A shared
      // backend is NEVER dropped on cap exhaustion — dropping it because THIS app hit its cap would
      // drop it for every OTHER app on the device (the #1636 collateral failure). The cap is still
      // enforced through the distinctive hosts; `usedMinutes` already aggregates the whole host-set.
      case sd if sd.dailyLimitMinutes.exists(lim => sd.usedMinutes >= lim) =>
        sd.distinctiveHosts.map(Hostname.unsafe)
    }.flatten

  /**
   * #1515: the whole host-set of every exempt-from-daily app still UNDER its own per-app cap — the
   * complement of [[appCapExhaustedHosts]] for exempt apps. Carved into `extraAllowed` so it beats
   * every whole-MAC block (paused / schedule / daily-limit) at the router (#421). Shared by the
   * snapshot and the /decision fallback so the exempt carve cannot diverge (#1532, #1513).
   */
  private[policy] def exemptUnderCapHosts(state: ProfileDayState): List[Hostname] =
    state.perApp.collect {
      // #1627: `dailyLimitMinutes = None` means "no per-app cap configured" —
      // an exempt-from-daily app with no cap is treated as always-under, so it
      // always carves around whole-MAC blocks (Paused / Schedule / TimeLimit).
      // `forall` returns true for None, so the gate is "exempt AND (no cap OR
      // still under cap)" — same family as `appCapExhaustedHosts` above so the
      // two cannot disagree on what "exhausted" means (AGENTS.md §single-
      // source-of-truth).
      case sd if sd.exemptFromDaily && sd.dailyLimitMinutes.forall(sd.usedMinutes < _) =>
        sd.hosts.map(Hostname.unsafe)
    }.flatten

  /**
   * #1980: the full host-set (main + shared) of every TimeLimited app still UNDER its own per-app
   * cap — the allow-side complement of [[appCapExhaustedHosts]] across the same `state.perApp` (the
   * TimeLimited-only subset; see [[TimeStatusService.appDayStates]]). Carved into `extraAllowed` so
   * a within-budget app host beats category-blocklist membership (`bl_`) at the router (#421,
   * `feedback_extraallowed_beats_blocked`), which is the #1980 fix: a TimeLimited app contributes
   * nothing to `extraAllowed` on its own, so without this an app host that is also a blocklist
   * member (e.g. `gimkit.com` in the `games` list) is dropped during the app's allowed window.
   *
   * Unlike [[exemptUnderCapHosts]] (which carves UNCONDITIONALLY so an exempt app beats whole-MAC
   * blocks), this carve is gated by the caller on the profile NOT being whole-MAC blocked
   * (`!state.blocked` in [[computeBlockRules]]; the precedence ordering in `decide`). So it beats
   * the per-host blocklist drop but stays subordinate to paused / schedule / profile-daily-limit
   * blocks — the time budget governs *when* the host is reachable, the blocklist never *whether*
   * (the app assignment is an explicit, authoritative allow). The full host-set is used (not just
   * distinctive) because a shared backend belonging to an allowed app must stay reachable too, and
   * over-allowing a shared host is safe — `extraAllowed` beats every drop. Shared by the snapshot
   * and the /decision fallback so the two cannot diverge (#1532).
   */
  private[policy] def timeLimitedUnderCapHosts(state: ProfileDayState): List[Hostname] =
    state.perApp.collect {
      case sd if sd.dailyLimitMinutes.forall(lim => sd.usedMinutes < lim) =>
        sd.hosts.map(Hostname.unsafe)
    }.flatten

  /** True iff `w` (a #1069 named-schedule window) is active at `now`, via [[scheduleActiveAt]]. */
  def windowActiveAt(w: ScheduleWindow, now: Instant): Boolean =
    scheduleActiveAt(
      DbSchedule(
        ScheduleId(0L),
        ProfileId(0L),
        "app-window",
        w.days,
        w.startLocal,
        w.endLocal,
        w.tz,
      ),
      now,
    )

  // ── #334: timezone-aware time math ────────────────────────────────────────
  //
  // Schedules + daily-reset carry an IANA zone with the data. All evaluation
  // projects `Instant.now()` into that zone and compares wall-clock components.
  // DST is handled transparently by ZonedDateTime: the same wall-clock time
  // reliably resolves "9pm every day" regardless of standard/daylight time.

  /**
   * True iff `instant`, projected into `s.tz`, falls in the schedule's window. Same-day window:
   * `[startLocal, endLocal)` on a day in `s.days`. Cross-midnight (overnight) window when
   * `startLocal > endLocal`: `[startLocal, 24:00)` on a day in `s.days`, OR `[00:00, endLocal)` on
   * the day *after* a day in `s.days` (the tail).
   *
   * `startLocal == endLocal` is treated as a never-active empty window.
   */
  def scheduleActiveAt(s: DbSchedule, instant: Instant): Boolean = {
    if s.startLocal == s.endLocal then false
    else {
      val zdt         = instant.atZone(s.tz)
      val today       = zdt.toLocalDate
      val now         = zdt.toLocalTime
      val isOvernight = s.startLocal.isAfter(s.endLocal)
      if !isOvernight then
        s.days.contains(dowShort(today.getDayOfWeek)) &&
        !now.isBefore(s.startLocal) && now.isBefore(s.endLocal)
      else {
        val todayName = dowShort(today.getDayOfWeek)
        val prevName  = dowShort(today.minusDays(1).getDayOfWeek)
        (s.days.contains(todayName) && !now.isBefore(s.startLocal)) ||
        (s.days.contains(prevName) && now.isBefore(s.endLocal))
      }
    }
  }

  /**
   * The Instant at which the currently-active window for `s` ends. Caller must have established
   * that `scheduleActiveAt(s, now)` is true.
   */
  def scheduleEndInstantAfter(s: DbSchedule, now: Instant): Instant = {
    val zdt         = now.atZone(s.tz)
    val today       = zdt.toLocalDate
    val isOvernight = s.startLocal.isAfter(s.endLocal)
    val endDate     =
      if isOvernight && !zdt.toLocalTime.isBefore(s.startLocal) then today.plusDays(1)
      else today
    endDate.atTime(s.endLocal).atZone(s.tz).toInstant
  }

  /**
   * #1010: the "logical day" bucket for `instant` under the household's daily-reset configuration.
   * Projects into `dailyResetTz`; if the wall-clock time is before `dailyResetTime`, the bucket is
   * the previous calendar date (the prior day's reset is still in force). For the default
   * `daily_reset_time = '00:00'` this collapses to plain calendar date in the household zone.
   *
   * This is the canonical "what date does this Instant belong to" function — used both to write
   * `time_usage.date` and to read today's cap/usage on the policy snapshot path.
   */
  def householdLocalDate(instant: Instant, settings: HouseholdSettings): LocalDate = {
    val zdt = instant.atZone(settings.dailyResetTz)
    if (zdt.toLocalTime.isBefore(settings.dailyResetTime)) zdt.toLocalDate.minusDays(1)
    else zdt.toLocalDate
  }

  /**
   * The next Instant strictly after `now` at which the household's daily-reset wall-clock time
   * occurs in its zone. Used to populate `expiresAt` on time-limit blocks served to the router.
   */
  def nextDailyResetAfter(settings: HouseholdSettings, now: Instant): Instant = {
    val zdt       = now.atZone(settings.dailyResetTz)
    val candidate =
      zdt.toLocalDate.atTime(settings.dailyResetTime).atZone(settings.dailyResetTz).toInstant
    if candidate.isAfter(now) then candidate
    else
      zdt.toLocalDate
        .plusDays(1)
        .atTime(settings.dailyResetTime)
        .atZone(settings.dailyResetTz)
        .toInstant
  }

  private def dowShort(d: DayOfWeek): String = d match {
    case DayOfWeek.MONDAY    => "mon"
    case DayOfWeek.TUESDAY   => "tue"
    case DayOfWeek.WEDNESDAY => "wed"
    case DayOfWeek.THURSDAY  => "thu"
    case DayOfWeek.FRIDAY    => "fri"
    case DayOfWeek.SATURDAY  => "sat"
    case DayOfWeek.SUNDAY    => "sun"
  }
}
