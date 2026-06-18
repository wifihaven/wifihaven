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
import java.util.concurrent.atomic.AtomicReference

trait PolicyService {
  def snapshot: Task[PolicySnapshot]
  def renderBlocklist(id: BlocklistId): Task[Option[(ETag, String)]]
  def decide(mac: String, hostname: String): Task[RouterDecisionResponse]
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
) extends PolicyService {

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

  def snapshot: Task[PolicySnapshot] =
    (for {
      settings <- householdSettingsRepo.get
      now      <- clock.instant
      today = PolicyService.householdLocalDate(now, settings)
      // #1104: today's cap/block state for every profile in one batched read. Same call the
      // /api/time/status/... endpoints use — keeps the snapshot and the UI in lockstep.
      dayStates   <- timeStatusService.dayStateAll(now, today, settings)
      // #1771: snapshot assembly needs the global sentinel too, so it can union the sentinel's
      // resolved extraAllowed/extraBlocked/blocklistIds into every other profile. `listAll`
      // is the user-facing listing and deliberately hides the sentinel.
      allProfiles <- profileRepo.listAllIncludingGlobal
      globalProfileOpt = allProfiles.find(_.isGlobal)
      profiles         = allProfiles.filterNot(_.isGlobal)
      devices            <- deviceRepo.listAll
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

      val catDomainsMap                            = catDomains.toMap
      val pBlocklists: Map[BlocklistId, Blocklist] = cats.map { c =>
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

      val core = SnapshotCore(globalRules, devicePolicies, profilePolicies, pBlocklists)
      val etag = PolicyService.computeEtag(core)
      val snap = PolicySnapshot(
        etag = etag,
        generatedAt = now.toString,
        global = globalRules,
        devices = devicePolicies,
        profiles = profilePolicies,
        blocklists = pBlocklists,
      )
      (snap, profiles.count(_.defaultDeny))
    }).flatMap { case (snap, defaultDenyProfiles) =>
      // #1318: surface the global-section size + default-deny profile count for operators.
      AppMetrics
        .setGlobalPolicy(snap.global.extraAllowed.size, defaultDenyProfiles)
        .zipRight(logSnapshotChanged(snap))
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

  def renderBlocklist(id: BlocklistId): Task[Option[(ETag, String)]] =
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
  def decide(mac: String, hostname: String): Task[RouterDecisionResponse] =
    for {
      settings <- householdSettingsRepo.get
      now      <- clock.instant
      today = PolicyService.householdLocalDate(now, settings)
      device <- deviceRepo.listAll.map(_.find(_.mac.value.equalsIgnoreCase(mac)))
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
      sd.hosts.exists(hp => HostMatch.matchesPattern(hostname, hp)) &&
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
)

object PolicyService {
  val layer: ZLayer[
    AppConfig & ProfileRepo & NamedScheduleRepo & HouseholdSettingsRepo & TimeLimitRepo &
      AppTimeLimitRepo & DeviceRepo & BlocklistRepo & TrafficReportRepo & TimeExtensionRepo &
      AppRepo & TimeStatusService & Clock,
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
    val profileExtraAllowed =
      if (isHardPause) Nil
      else (appExtraAllowed ++ appExemptAllowedHosts).distinct

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
      case sd if sd.dailyLimitMinutes.exists(lim => sd.usedMinutes >= lim) =>
        sd.hosts.map(Hostname.unsafe)
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
