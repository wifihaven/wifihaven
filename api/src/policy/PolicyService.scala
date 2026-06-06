package wifihaven.api.policy

import wifihaven.api.AppConfig
import wifihaven.api.db.*
import wifihaven.api.metrics.AppMetrics
import wifihaven.api.presence.Presence
import wifihaven.shared.{Schedule as DbSchedule, *}
import wifihaven.shared.types.*
import zio.{Clock as _, *}

import java.security.MessageDigest
import java.time.{DayOfWeek, Instant, LocalDate}

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
      scheduleRepo: ScheduleRepo,
      householdSettingsRepo: HouseholdSettingsRepo,
      timeLimitRepo: TimeLimitRepo,
      siteTimeLimitRepo: SiteTimeLimitRepo,
      deviceRepo: DeviceRepo,
      blocklistRepo: BlocklistRepo,
      trafficRepo: TrafficReportRepo,
      extRepo: TimeExtensionRepo,
      appRepo: AppRepo,
      clock: Clock,
      uiAllowedHosts: List[Hostname] = Nil,
      globalPolicyRepo: GlobalPolicyRepo = NoopGlobalPolicyRepo,
      // #1482: schedule downtime is read from the named-schedule model, so specs that assert
      // schedule blocking pass the real repo here; it threads into both the snapshot
      // (TimeStatusService) and the per-host /decision path. Defaults to the noop so the many specs
      // that don't exercise schedules keep their positional constructions unchanged.
      namedScheduleRepo: NamedScheduleRepo = NoopNamedScheduleRepo,
  ): PolicyServiceLive = {
    val tss = new TimeStatusServiceLive(
      profileRepo,
      scheduleRepo,
      timeLimitRepo,
      siteTimeLimitRepo,
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
      scheduleRepo,
      householdSettingsRepo,
      timeLimitRepo,
      siteTimeLimitRepo,
      deviceRepo,
      blocklistRepo,
      trafficRepo,
      extRepo,
      appRepo,
      tss,
      clock,
      uiAllowedHosts,
      globalPolicyRepo,
      namedScheduleRepo,
    )
  }
}

class PolicyServiceLive(
    profileRepo: ProfileRepo,
    // #1482: the legacy per-profile `schedules` table is no longer an enforcement source — schedule
    // downtime is read exclusively from `named_schedules` / `profile_schedule_rules` (both the
    // snapshot, via TimeStatusService, and the per-host /decision fallback below). Retained
    // injected-but-unused to keep the constructor arity ~40 test constructions depend on; removed
    // when the legacy table is dropped (the future two-phase destructive PR).
    @scala.annotation.unused scheduleRepo: ScheduleRepo,
    householdSettingsRepo: HouseholdSettingsRepo,
    timeLimitRepo: TimeLimitRepo,
    siteTimeLimitRepo: SiteTimeLimitRepo,
    deviceRepo: DeviceRepo,
    blocklistRepo: BlocklistRepo,
    trafficRepo: TrafficReportRepo,
    extRepo: TimeExtensionRepo,
    appRepo: AppRepo,
    timeStatusService: TimeStatusService,
    clock: Clock,
    uiAllowedHosts: List[Hostname] = Nil,
    globalPolicyRepo: GlobalPolicyRepo = NoopGlobalPolicyRepo,
    // #1069: defaulted to the noop so the many direct test constructions keep their arity. The
    // snapshot path gets named schedules via TimeStatusService; PolicyServiceLive needs this repo
    // directly only for the per-host /decision fallback, where the production layer wires the real
    // one.
    namedScheduleRepo: NamedScheduleRepo = NoopNamedScheduleRepo,
) extends PolicyService {

  // #1318: the WifiHaven UI / block-page hosts are fleet-wide always-reachable
  // hosts, so they live in `global.extraAllowed` (carving out every block at the
  // router) rather than being copied into every profile's `extraAllowed`. The
  // DB-backed `global_allow` set is unioned with these per-deployment config
  // hosts when assembling the global section. (#1321 moved the curated
  // `infraAllowHosts` onto the same global path, retiring its per-profile copy.)
  private val uiGlobalAllow: List[Hostname] = uiAllowedHosts

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
      settings  <- householdSettingsRepo.get
      now       <- clock.instant
      // #1318: the fleet-wide global BlockRules, assembled once from the global-policy tables.
      // `uiGlobalAllow` (the per-deployment UI / block-page hosts) is unioned into its
      // `extraAllowed` so those hosts are always reachable for every MAC via `global.extraAllowed`
      // rather than copied into each profile.
      globalRaw <- globalPolicyRepo.get
      today = PolicyService.householdLocalDate(now, settings)
      // #1104: today's cap/block state for every profile in one batched read. Same call the
      // /api/time/status/... endpoints use — keeps the snapshot and the UI in lockstep.
      dayStates <- timeStatusService.dayStateAll(now, today, settings)
      profiles  <- profileRepo.listAll
      devices   <- deviceRepo.listAll
      stlims    <- ZIO.foreach(profiles)(p => siteTimeLimitRepo.listForProfile(p.id).map(p.id -> _))
      cats      <- blocklistRepo.listCategories
      catDomains  <- ZIO.foreach(cats)(c => blocklistRepo.loadCategory(c).map(c -> _))
      // #763: load apps + per-app hosts + per-profile assignments so we can
      // expand app modes into the per-profile BlockRules buckets below.
      apps        <- appRepo.listAll
      appHostsRaw <- ZIO.foreach(apps)(a => appRepo.getHosts(a.id).map(a.id -> _))
      appAssigns  <- ZIO.foreach(profiles)(p =>
        appRepo.listAssignmentsForProfile(p.id).map(p.id -> _),
      )
      // #1379: per-app schedule rules, resolved to (mode, window) pairs per assignment.
      // Folded into the per-app effective disposition below, before app-mode bucketing.
      appSched    <- ZIO.foreach(profiles)(p =>
        appRepo.appScheduleWindowsForProfile(p.id).map(p.id -> _),
      )
      appHostsMap = appHostsRaw.toMap
      appAssignsMap = appAssigns.toMap
      appSchedMap   = appSched.toMap
      stlMap        = stlims.toMap
    } yield {
      val profilePolicies: Map[ProfileId, ProfilePolicy] = profiles.iterator.map { p =>
        val pSiteLims = stlMap.getOrElse(p.id, Nil)

        // #1104: cap/block state comes from TimeStatusService — the same value the UI reads.
        // Computed before app bucketing because the #1379 carve gate keys off the raw daily-cap
        // condition (used/limit/extensions), independent of the collapsed blockReason.
        val state = dayStates.getOrElse(
          p.id,
          ProfileDayState(p.id, today, None, 0, 0, None, blocked = false, None, Nil),
        )

        // #763/#764/#1379: expand this profile's app assignments into wire-shape buckets.
        // Each assignment's effective disposition at `now` folds its per-app schedule windows
        // over its base mode (an active window overrides the base), then the AllowedDuring carve
        // is gated by the daily cap unless the app is exemptFromDaily (design §4.1, §5). Post-#764,
        // time_limited apps are surfaced via SiteTimeLimitRepo, so they contribute nothing here.
        val pAssigns                           = appAssignsMap.getOrElse(p.id, Nil)
        val (appAllowedHosts, appBlockedHosts) =
          PolicyService.expandAppDispositions(
            assigns = pAssigns,
            hostsByApp = appHostsMap,
            schedWindows = appSchedMap.getOrElse(p.id, Map.empty),
            capExhausted = PolicyService.dailyCapExhausted(state),
            now = now,
          )

        val rules = PolicyService.computeBlockRules(
          profile = p,
          state = state,
          siteLimits = pSiteLims,
          appExtraAllowed = appAllowedHosts,
          appExtraBlocked = appBlockedHosts,
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

      // #1318/#1321: union the fleet-wide always-reachable hosts into the
      // DB-backed global allow set. Two relocated sources join `global_allow`:
      //   - `uiGlobalAllow` — the per-deployment UI / block-page hosts (#1318).
      //   - `infraAllowHosts` — the curated connectivity-check / OCSP / PKI /
      //     captive-portal / gvt2 device-level deps (#1307), which #1321 stops
      //     copying into every profile's `extraAllowed` and ships here ONCE.
      // The router applies `global.extraAllowed` as the top of the precedence
      // ladder (@global_allow, #1319), carving every drop out for every MAC — so
      // these hosts beat every block path identically to the old per-(MAC) ea_
      // copies, with no per-profile duplication and a single ETag-moving source.
      val globalRules = globalRaw.copy(
        extraAllowed =
          (globalRaw.extraAllowed ++ uiGlobalAllow ++ PolicyService.infraAllowHosts).distinct,
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
        .as(snap)
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
          ZIO.succeed(RouterDecisionResponse(ConnectionDecision.Allow, "no_profile", None))
        case Some(pid) =>
          for {
            pOpt            <- profileRepo.findById(pid)
            scheds          <- schedulesFor(pid)
            tl              <- timeLimitRepo.findForProfile(pid)
            stlims          <- siteTimeLimitRepo.listForProfile(pid)
            // #764: post-migration, extraAllowed/extraBlocked are sourced
            // exclusively from app_policy_assignments. Mirror the snapshot
            // expansion (allowed/blocked modes) here so the per-host
            // fallback agrees with the snapshot's precedence.
            appAssigns      <- appRepo.listAssignmentsForProfile(pid)
            appHostsByApp   <- ZIO
              .foreach(appAssigns.map(_.appId).distinct)(aid => appRepo.getHosts(aid).map(aid -> _))
              .map(_.toMap)
            // #1379: per-app schedule windows for this profile's assignments, used to fold each
            // app's effective disposition + carve gate exactly as the snapshot does.
            appSchedWindows <- appRepo.appScheduleWindowsForProfile(pid)
            // Reuse the same per-profile usage calc used by snapshot, scoped to this profile.
            devs            <- deviceRepo.listAll.map(_.filter(_.profileId.contains(pid)))
            macs = devs.map(_.mac).toSet
            pres <- trafficRepo.listPresenceRows(devs.map(_.mac), today)
            exts <- extRepo.snapshotAllByProfile(today).map(_.getOrElse(pid, 0))
            res  <- pOpt match {
              case None    =>
                ZIO.succeed(RouterDecisionResponse(ConnectionDecision.Allow, "no_profile", None))
              case Some(p) =>
                val h = hostname.toLowerCase.stripSuffix(".")

                // Per-profile daily usage — computed up front because the #1379 carve gate keys off
                // whether the daily cap is exhausted, independent of the precedence chain below.
                val pPres    = pres.filter(r => macs.contains(r.mac))
                val patterns = stlims.map(_.domainPattern)
                val perPat = Presence.patternMinutesByMac(pPres, patterns, settings.heartbeatFilter)
                val byDomain     = patterns.foldLeft(Map.empty[String, Int]) { (acc, pat) =>
                  val mins = devs.iterator.map(d => perPat.getOrElse((d.mac, pat), 0)).sum
                  if mins == 0 then acc else acc.updated(pat, mins)
                }
                val exemptPats   =
                  stlims.filter(_.exemptFromDaily).map(_.domainPattern)
                val perMacTot    =
                  Presence.totalMinutesByMac(
                    pPres,
                    exemptPats,
                    settings.heartbeatFilter,
                    settings.presenceContinuationSeconds,
                  )
                // #751: same branch as snapshot — keeps decide() consistent with the snapshot's
                // cap evaluation.
                val totalMins    = p.crossDeviceOverlapMode match {
                  case CrossDeviceOverlapMode.Sum   =>
                    devs.iterator.map(d => perMacTot.getOrElse(d.mac, 0)).sum
                  case CrossDeviceOverlapMode.Dedup =>
                    Presence.dedupedTotalMinutes(
                      pPres,
                      exemptPats,
                      settings.heartbeatFilter,
                      settings.presenceContinuationSeconds,
                    )
                }
                // #1379: the daily-cap-exhausted condition the AllowedDuring carve gate keys off —
                // the per-host mirror of `PolicyService.dailyCapExhausted(state)` in the snapshot.
                val capExhausted =
                  tl.map(_.dailyMinutes).exists(limit => totalMins >= limit + exts)

                // #1379: fold each app's effective disposition (schedule windows over base mode) +
                // the carve gate, exactly as the snapshot does. A non-exempt allowed_during app
                // whose cap is exhausted is NOT carved, so it correctly falls through to the
                // time_limit block below; an exempt one (or one under cap) is carved and beats it.
                val (appAllowed, appBlocked) = PolicyService.expandAppDispositions(
                  assigns = appAssigns,
                  hostsByApp = appHostsByApp,
                  schedWindows = appSchedWindows,
                  capExhausted = capExhausted,
                  now = now,
                )

                // #1413/#421: extraAllowed beats EVERY whole-MAC block — incl.
                // Paused/Schedule — so check the allowed-app list first, before
                // the pause/schedule short-circuits. This matches the snapshot/
                // router `ip daddr != @ea_<m>_<a>` carve-out.
                if matchesAny(h, appAllowed) then
                  ZIO.succeed(
                    RouterDecisionResponse(ConnectionDecision.Allow, "extra_allowed", None),
                  )
                else if p.paused then
                  ZIO.succeed(RouterDecisionResponse(ConnectionDecision.Block, "paused", None))
                else
                  scheduleBlock(scheds, now) match {
                    case Some(r) => ZIO.succeed(r)
                    case None    =>
                      if matchesAny(h, appBlocked) then
                        ZIO.succeed(
                          RouterDecisionResponse(ConnectionDecision.Block, "extra_blocked", None),
                        )
                      else
                        timeLimitBlockFromDb(
                          h,
                          now,
                          settings,
                          tl.map(_.dailyMinutes),
                          stlims,
                          byDomain,
                          totalMins,
                          exts,
                        ) match {
                          case Some(r) => ZIO.succeed(r)
                          case None    =>
                            categoryBlock(p.blockedCategories, h).map {
                              case Some(cat) =>
                                RouterDecisionResponse(
                                  ConnectionDecision.Block,
                                  s"category:${cat.value}",
                                  None,
                                )
                              case None      =>
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
      RouterDecisionResponse(ConnectionDecision.Block, "schedule", Some(expiresAt.toString))
    }
  }

  private def timeLimitBlockFromDb(
      hostname: String,
      now: Instant,
      settings: HouseholdSettings,
      dailyMinutes: Option[Int],
      siteLimits: List[SiteTimeLimit],
      minutesByDomain: Map[String, Int],
      totalMinutesUsed: Int,
      extensionsMinutes: Int,
  ): Option[RouterDecisionResponse] = {
    // Time-limit blocks expire at the next household daily-reset Instant.
    val resetAt      = PolicyService.nextDailyResetAfter(settings, now).toString
    val siteLimitHit = siteLimits.find { sl =>
      HostMatch.matchesPattern(hostname, sl.domainPattern) &&
      minutesByDomain.getOrElse(sl.domainPattern, 0) >= sl.dailyMinutes
    }
    siteLimitHit
      .map(sl =>
        RouterDecisionResponse(
          ConnectionDecision.Block,
          s"site_time_limit:${sl.label}",
          Some(resetAt),
        ),
      )
      .orElse {
        val isExemptSite = siteLimits.exists { sl =>
          sl.exemptFromDaily && HostMatch.matchesPattern(hostname, sl.domainPattern)
        }
        if isExemptSite then None
        else
          dailyMinutes.flatMap { limit =>
            Option.when(totalMinutesUsed >= limit + extensionsMinutes)(
              RouterDecisionResponse(ConnectionDecision.Block, "time_limit", Some(resetAt)),
            )
          }
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
    AppConfig & ProfileRepo & ScheduleRepo & NamedScheduleRepo & HouseholdSettingsRepo &
      TimeLimitRepo & SiteTimeLimitRepo & DeviceRepo & BlocklistRepo & TrafficReportRepo &
      TimeExtensionRepo & AppRepo & GlobalPolicyRepo & TimeStatusService & Clock,
    Nothing,
    PolicyService,
  ] = ZLayer.fromFunction {
    (
        cfg: AppConfig,
        pr: ProfileRepo,
        sr: ScheduleRepo,
        nsr: NamedScheduleRepo,
        hsr: HouseholdSettingsRepo,
        tlr: TimeLimitRepo,
        stlr: SiteTimeLimitRepo,
        dr: DeviceRepo,
        blr: BlocklistRepo,
        trr: TrafficReportRepo,
        er: TimeExtensionRepo,
        ar: AppRepo,
        gpr: GlobalPolicyRepo,
        tss: TimeStatusService,
        clk: Clock,
    ) =>
      new PolicyServiceLive(
        pr,
        sr,
        hsr,
        tlr,
        stlr,
        dr,
        blr,
        trr,
        er,
        ar,
        tss,
        clk,
        cfg.policy.uiAllowedHostsParsed,
        gpr,
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
      siteLimits: List[SiteTimeLimit],
      appExtraAllowed: List[Hostname] = Nil,
      appExtraBlocked: List[Hostname] = Nil,
  ): BlockRules = {
    // Per-site limits exhausted today → host appears in extraBlocked too.
    // domainPattern is a glob string, not a Hostname — we keep it as-is in the
    // extraBlocked list so the router agent can match it. We do NOT wrap with
    // Hostname here because glob patterns like *.youtube.com are not hostnames.
    // Instead, we pass them as raw strings and convert via Hostname.unsafe for
    // the typed list (the router treats these as patterns, so validation is relaxed).
    val siteUsedByPattern: Map[String, Int]   =
      state.perSite.iterator.map(s => s.domainPattern -> s.usedMinutes).toMap
    val siteLimitExtraBlocked: List[Hostname] = siteLimits.collect {
      case sl if siteUsedByPattern.getOrElse(sl.domainPattern, 0) >= sl.dailyMinutes =>
        Hostname.unsafe(sl.domainPattern)
    }

    // #1105: time_limited app hosts with exemptFromDaily=true carve around the
    // MAC-level @blocked_macs drop while they still have per-host budget. The
    // exempt flag's original role was just to exclude the host from the daily
    // tally; without this carve-out, hitting the profile cap silently dropped
    // the exempt app too, violating the "Khan doesn't count" contract.
    // Naturally transitions allow → block as the per-host budget exhausts
    // (siteLimitExtraBlocked above takes over and extraAllowed-beats-extraBlocked
    // at the router; see feedback_extraallowed_beats_blocked).
    val appExemptAllowedHosts: List[Hostname] = state.perSite.collect {
      case sd if sd.exemptFromDaily && sd.usedMinutes < sd.dailyLimitMinutes =>
        Hostname.unsafe(sd.domainPattern)
    }

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
        extraBlocked = (appExtraBlocked ++ siteLimitExtraBlocked).distinct,
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

  /**
   * #1379: collapse each app assignment into the `(extraAllowed, extraBlocked)` host buckets,
   * folding its per-app schedule windows over its base mode (design §4.1):
   *
   *   - any `allowed_during` window active → AllowedDuring candidate (carve gate below);
   *   - else any `blocked_during` window active → Blocked;
   *   - else the assignment's base [[AppMode]].
   *
   * `allowed_during` beats `blocked_during` on simultaneous overlap (consistent with #421
   * allow-beats-block). The AllowedDuring carve into `extraAllowed` is gated by the daily cap:
   * `carve = NOT (capExhausted AND NOT exemptFromDaily)`. A non-exempt app whose cap is exhausted
   * is therefore NOT carved — it stays subject to the whole-MAC block, so the daily limit still
   * bites without any wire/router change (design §5 rows 9a–9c). An AllowedDuring candidate that
   * fails the gate is added to neither bucket. `TimeLimited` base apps contribute nothing here
   * (surfaced via the site-limit path, #764). Shared by `snapshot` and `decide` so they agree.
   */
  private[policy] def expandAppDispositions(
      assigns: List[AppPolicyAssignment],
      hostsByApp: Map[AppId, List[Hostname]],
      schedWindows: Map[AppPolicyAssignmentId, List[(AppScheduleMode, ScheduleWindow)]],
      capExhausted: Boolean,
      now: Instant,
  ): (List[Hostname], List[Hostname]) = {
    val allowed = scala.collection.mutable.ListBuffer.empty[Hostname]
    val blocked = scala.collection.mutable.ListBuffer.empty[Hostname]
    assigns.foreach { a =>
      val hosts         = hostsByApp.getOrElse(a.appId, Nil)
      val pairs         = schedWindows.getOrElse(a.id, Nil)
      val allowedActive = pairs.exists { case (m, w) =>
        m == AppScheduleMode.AllowedDuring && windowActiveAt(w, now)
      }
      val blockedActive = pairs.exists { case (m, w) =>
        m == AppScheduleMode.BlockedDuring && windowActiveAt(w, now)
      }
      if (allowedActive) {
        // Carve gate: withhold the carve for a non-exempt app whose cap is exhausted.
        if (!(capExhausted && !a.exemptFromDaily)) allowed ++= hosts
      } else if (blockedActive) {
        blocked ++= hosts
      } else
        a.mode match {
          case AppMode.Allowed     => allowed ++= hosts
          case AppMode.Blocked     => blocked ++= hosts
          case AppMode.TimeLimited => () // site-limit path (#764)
        }
    }
    (allowed.toList.distinct, blocked.toList.distinct)
  }

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
