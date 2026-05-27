package wifihaven.api.policy

import wifihaven.api.AppConfig
import wifihaven.api.db.*
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
  ): PolicyServiceLive = {
    val tss = new TimeStatusServiceLive(
      profileRepo,
      scheduleRepo,
      timeLimitRepo,
      siteTimeLimitRepo,
      deviceRepo,
      trafficRepo,
      extRepo,
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
    )
  }
}

class PolicyServiceLive(
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
    timeStatusService: TimeStatusService,
    clock: Clock,
    uiAllowedHosts: List[Hostname] = Nil,
) extends PolicyService {

  def snapshot: Task[PolicySnapshot] =
    for {
      settings <- householdSettingsRepo.get
      now      <- clock.instant
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
      appHostsMap = appHostsRaw.toMap
      appAssignsMap = appAssigns.toMap
      stlMap        = stlims.toMap
    } yield {
      val profilePolicies: Map[ProfileId, ProfilePolicy] = profiles.iterator.map { p =>
        val pSiteLims = stlMap.getOrElse(p.id, Nil)

        // #763/#764: expand this profile's app assignments into wire-shape
        // buckets. Post-#764, time_limited apps are surfaced via
        // SiteTimeLimitRepo (which itself synthesizes from app tables), so
        // here we only handle allowed/blocked modes.
        val pAssigns                        = appAssignsMap.getOrElse(p.id, Nil)
        val appAllowedHosts: List[Hostname] = pAssigns
          .collect {
            case a if a.mode == AppMode.Allowed =>
              appHostsMap.getOrElse(a.appId, Nil)
          }
          .flatten
          .distinct
        val appBlockedHosts: List[Hostname] = pAssigns
          .collect {
            case a if a.mode == AppMode.Blocked =>
              appHostsMap.getOrElse(a.appId, Nil)
          }
          .flatten
          .distinct

        // #1104: cap/block state comes from TimeStatusService — the same value the UI reads.
        val state = dayStates.getOrElse(
          p.id,
          ProfileDayState(p.id, today, None, 0, 0, None, blocked = false, None, Nil),
        )
        val rules = PolicyService.computeBlockRules(
          profile = p,
          state = state,
          siteLimits = pSiteLims,
          appExtraAllowed = appAllowedHosts,
          appExtraBlocked = appBlockedHosts,
          uiAllowedHosts = uiAllowedHosts,
        )

        p.id -> ProfilePolicy(name = p.name, rules = rules, failureMode = p.failureMode)
      }.toMap

      // #961: unmanaged-MAC enforcement is applied here at snapshot-build time,
      // not via a new wire field. For devices with no profile assignment we
      // emit explicit per-MAC `rules` keyed off the household policy:
      //   - policy = "block": Manual-blocked with `uiAllowedHosts` in
      //     extraAllowed so the SPA hostnames remain reachable from the
      //     unmanaged device (otherwise the block-page redirect can't load).
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
              blockReason = Some(MacBlockReason.Manual),
              extraBlocked = Nil,
              extraAllowed = uiAllowedHosts,
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

      val core = SnapshotCore(devicePolicies, profilePolicies, pBlocklists)
      val etag = PolicyService.computeEtag(core)
      PolicySnapshot(
        etag = etag,
        generatedAt = now.toString,
        devices = devicePolicies,
        profiles = profilePolicies,
        blocklists = pBlocklists,
      )
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
   * category state needed to make a per-host decision. Precedence: paused > schedule > allowed-app
   * > blocked-app > site_time_limit > time_limit > category > allow.
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
            pOpt          <- profileRepo.findById(pid)
            scheds        <- scheduleRepo.listForProfile(pid)
            tl            <- timeLimitRepo.findForProfile(pid)
            stlims        <- siteTimeLimitRepo.listForProfile(pid)
            // #764: post-migration, extraAllowed/extraBlocked are sourced
            // exclusively from app_policy_assignments. Mirror the snapshot
            // expansion (allowed/blocked modes) here so the per-host
            // fallback agrees with the snapshot's precedence.
            appAssigns    <- appRepo.listAssignmentsForProfile(pid)
            appHostsByApp <- ZIO
              .foreach(appAssigns.map(_.appId).distinct)(aid => appRepo.getHosts(aid).map(aid -> _))
              .map(_.toMap)
            appAllowed = appAssigns
              .collect {
                case a if a.mode == AppMode.Allowed => appHostsByApp.getOrElse(a.appId, Nil)
              }
              .flatten
              .distinct
            appBlocked = appAssigns
              .collect {
                case a if a.mode == AppMode.Blocked => appHostsByApp.getOrElse(a.appId, Nil)
              }
              .flatten
              .distinct
            // Reuse the same per-profile usage calc used by snapshot, scoped to this profile.
            devs          <- deviceRepo.listAll.map(_.filter(_.profileId.contains(pid)))
            macs = devs.map(_.mac).toSet
            pres <- trafficRepo.listPresenceRows(devs.map(_.mac), today)
            exts <- extRepo.snapshotAllByProfile(today).map(_.getOrElse(pid, 0))
            res  <- pOpt match {
              case None    =>
                ZIO.succeed(RouterDecisionResponse(ConnectionDecision.Allow, "no_profile", None))
              case Some(p) =>
                val h = hostname.toLowerCase.stripSuffix(".")
                if p.paused then
                  ZIO.succeed(RouterDecisionResponse(ConnectionDecision.Block, "paused", None))
                else
                  scheduleBlock(scheds, now) match {
                    case Some(r) => ZIO.succeed(r)
                    case None    =>
                      if matchesAny(h, appAllowed) then
                        ZIO.succeed(
                          RouterDecisionResponse(ConnectionDecision.Allow, "extra_allowed", None),
                        )
                      else if matchesAny(h, appBlocked) then
                        ZIO.succeed(
                          RouterDecisionResponse(ConnectionDecision.Block, "extra_blocked", None),
                        )
                      else {
                        val pPres      = pres.filter(r => macs.contains(r.mac))
                        val patterns   = stlims.map(_.domainPattern)
                        val perPat     = Presence.patternMinutesByMac(pPres, patterns)
                        val byDomain   = patterns.foldLeft(Map.empty[String, Int]) { (acc, pat) =>
                          val mins = devs.iterator.map(d => perPat.getOrElse((d.mac, pat), 0)).sum
                          if mins == 0 then acc else acc.updated(pat, mins)
                        }
                        val exemptPats =
                          stlims.filter(_.exemptFromDaily).map(_.domainPattern)
                        val perMacTot  =
                          Presence.totalMinutesByMac(pPres, exemptPats, settings.heartbeatFilter)
                        // #751: same branch as snapshot — keeps decide()
                        // consistent with the snapshot's cap evaluation.
                        val totalMins  = p.crossDeviceOverlapMode match {
                          case CrossDeviceOverlapMode.Sum   =>
                            devs.iterator.map(d => perMacTot.getOrElse(d.mac, 0)).sum
                          case CrossDeviceOverlapMode.Dedup =>
                            Presence.dedupedTotalMinutes(
                              pPres,
                              exemptPats,
                              settings.heartbeatFilter,
                            )
                        }
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
    devices: Map[MacAddress, DevicePolicy],
    profiles: Map[ProfileId, ProfilePolicy],
    blocklists: Map[BlocklistId, Blocklist],
)

object PolicyService {
  val layer: ZLayer[
    AppConfig & ProfileRepo & ScheduleRepo & HouseholdSettingsRepo & TimeLimitRepo &
      SiteTimeLimitRepo & DeviceRepo & BlocklistRepo & TrafficReportRepo & TimeExtensionRepo &
      AppRepo & TimeStatusService & Clock,
    Nothing,
    PolicyService,
  ] = ZLayer.fromFunction {
    (
        cfg: AppConfig,
        pr: ProfileRepo,
        sr: ScheduleRepo,
        hsr: HouseholdSettingsRepo,
        tlr: TimeLimitRepo,
        stlr: SiteTimeLimitRepo,
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
      )
  }

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
   * > TimeLimit). This function only adds the app/site-limit/UI-host wiring around it.
   */
  private[policy] def computeBlockRules(
      profile: Profile,
      state: ProfileDayState,
      siteLimits: List[SiteTimeLimit],
      appExtraAllowed: List[Hostname] = Nil,
      appExtraBlocked: List[Hostname] = Nil,
      uiAllowedHosts: List[Hostname] = Nil,
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

    // #763: app expansion is additive. A host in both an allowed-mode app and
    // a blocked-mode app will appear in both lists; the router's
    // extraAllowed-beats-extraBlocked precedence then makes "allow wins" — same
    // semantics it already applies to the per-profile own lists (see
    // feedback_extraallowed_beats_blocked).
    BlockRules(
      blocked = state.blocked,
      blockReason = state.blockReason,
      extraBlocked = (appExtraBlocked ++ siteLimitExtraBlocked).distinct,
      // #944: union the deployment's UI hosts into per-profile extraAllowed so
      // a household device can always reach the admin UI even when this
      // profile is paused or lists one of these hosts in a blocked-mode app
      // (allow beats block at the router). Configured via wifihaven.policy
      // .uiAllowedHosts per-deployment so prod doesn't allow staging through
      // and vice versa. Will become DB-backed per #937.
      extraAllowed = (appExtraAllowed ++ uiAllowedHosts).distinct,
      blocklistIds = profile.blockedCategories,
      blockIpOnly = profile.blockIpOnly,
    )
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
