package wifihaven.api.policy

import wifihaven.api.db.*
import wifihaven.api.presence.Presence
import wifihaven.shared.{Schedule as DbSchedule, *}
import wifihaven.shared.types.*
import zio.{Clock as _, *}

import java.security.MessageDigest
import java.time.{DayOfWeek, Instant}

trait PolicyService {
  def snapshot: Task[PolicySnapshot]
  def renderBlocklist(id: BlocklistId): Task[Option[(ETag, String)]]
  def decide(mac: String, hostname: String): Task[RouterDecisionResponse]
}

/**
 * Inputs needed to evaluate a single profile's effective BlockRules. Pulled from the per-profile
 * repos by `snapshot` and fed into the pure `computeBlockRules` function below.
 */
private[policy] case class ProfileInputs(
    profile: Profile,
    schedules: List[DbSchedule],
    dailyMinutes: Option[Int],
    siteLimits: List[SiteTimeLimit],
    /**
     * total active minutes today summed across all devices in the profile, excluding minutes spent
     * on exempt site-limited domains.
     */
    totalMinutesUsed: Int,
    /** active minutes per site-limit domain, summed across all devices in the profile. */
    minutesByDomain: Map[String, Int],
    extensionsMinutes: Int,
)

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
    clock: Clock,
) extends PolicyService {

  def snapshot: Task[PolicySnapshot] =
    for {
      settings <- householdSettingsRepo.get
      now      <- clock.instant
      today = now.atZone(settings.dailyResetTz).toLocalDate
      profiles <- profileRepo.listAll
      devices  <- deviceRepo.listAll
      scheds   <- ZIO.foreach(profiles)(p => scheduleRepo.listForProfile(p.id).map(p.id -> _))
      tlims    <- ZIO.foreach(profiles)(p => timeLimitRepo.findForProfile(p.id).map(p.id -> _))
      stlims   <- ZIO.foreach(profiles)(p => siteTimeLimitRepo.listForProfile(p.id).map(p.id -> _))
      presence <- trafficRepo.listPresenceRows(devices.map(_.mac), today)
      exts     <- extRepo.snapshotAllByProfile(today)
      cats     <- blocklistRepo.listCategories
      catDomains <- ZIO.foreach(cats)(c => blocklistRepo.loadCategory(c).map(c -> _))
      schedMap = scheds.toMap
      tlMap    = tlims.toMap
      stlMap   = stlims.toMap
    } yield {
      val devsByProfile =
        devices.groupBy(_.profileId).collect { case (Some(pid), devs) => pid -> devs }

      val profilePolicies: Map[ProfileId, ProfilePolicy] = profiles.iterator.map { p =>
        val pSched     = schedMap.getOrElse(p.id, Nil)
        val pSiteLims  = stlMap.getOrElse(p.id, Nil)
        val devicesIn  = devsByProfile.getOrElse(p.id, Nil)
        val deviceMacs = devicesIn.map(_.mac).toSet
        val pPresence  = presence.filter(r => deviceMacs.contains(r.mac))
        val patterns   = pSiteLims.map(_.domainPattern)
        val perPat     = Presence.patternMinutesByMac(pPresence, patterns)
        val byDomain   = patterns.foldLeft(Map.empty[String, Int]) { (acc, pat) =>
          val mins = devicesIn.iterator.map(d => perPat.getOrElse((d.mac, pat), 0)).sum
          if mins == 0 then acc else acc.updated(pat, mins)
        }
        val exemptPats = pSiteLims.filter(_.exemptFromDaily).map(_.domainPattern)
        val perMacTot  = Presence.totalMinutesByMac(pPresence, exemptPats, settings.heartbeatFilter)
        val totalMins  = devicesIn.iterator.map(d => perMacTot.getOrElse(d.mac, 0)).sum
        val extMins    = exts.getOrElse(p.id, 0)

        val inputs = ProfileInputs(
          profile = p,
          schedules = pSched,
          dailyMinutes = tlMap.getOrElse(p.id, None).map(_.dailyMinutes),
          siteLimits = pSiteLims,
          totalMinutesUsed = totalMins,
          minutesByDomain = byDomain,
          extensionsMinutes = extMins,
        )
        val rules  = PolicyService.computeBlockRules(inputs, now)

        p.id -> ProfilePolicy(name = p.name, rules = rules, failureMode = p.failureMode)
      }.toMap

      val devicePolicies: Map[MacAddress, DevicePolicy] = devices.iterator.map { d =>
        d.mac -> DevicePolicy(profileId = d.profileId, name = d.name, rules = None)
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
   * category state needed to make a per-host decision. Precedence: paused > schedule > extraAllowed
   * > extraBlocked > site_time_limit > time_limit > category > allow.
   */
  def decide(mac: String, hostname: String): Task[RouterDecisionResponse] =
    for {
      settings <- householdSettingsRepo.get
      now      <- clock.instant
      today = now.atZone(settings.dailyResetTz).toLocalDate
      device <- deviceRepo.listAll.map(_.find(_.mac.value.equalsIgnoreCase(mac)))
      result <- device.flatMap(_.profileId) match {
        case None      =>
          ZIO.succeed(RouterDecisionResponse(ConnectionDecision.Allow, "no_profile", None))
        case Some(pid) =>
          for {
            pOpt   <- profileRepo.findById(pid)
            scheds <- scheduleRepo.listForProfile(pid)
            tl     <- timeLimitRepo.findForProfile(pid)
            stlims <- siteTimeLimitRepo.listForProfile(pid)
            // Reuse the same per-profile usage calc used by snapshot, scoped to this profile.
            devs   <- deviceRepo.listAll.map(_.filter(_.profileId.contains(pid)))
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
                      if matchesAny(h, p.extraAllowed) then
                        ZIO.succeed(
                          RouterDecisionResponse(ConnectionDecision.Allow, "extra_allowed", None),
                        )
                      else if matchesAny(h, p.extraBlocked) then
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
                        val totalMins  = devs.iterator.map(d => perMacTot.getOrElse(d.mac, 0)).sum
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
      matchesDomainPattern(hostname, sl.domainPattern) &&
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
          sl.exemptFromDaily && matchesDomainPattern(hostname, sl.domainPattern)
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
    patterns.exists(p => matchesDomainPattern(domain, p.value))

  // Pattern matching is FQDN-only by design (#391). The decision endpoint
  // receives `RouterDecisionRequest.hostname: Hostname`, which the type system
  // already constrains to FQDN-shape (Hostname.parse rejects IPv4 literals),
  // so an IP literal can't even reach this matcher.
  private def matchesDomainPattern(domain: String, pattern: String): Boolean =
    if pattern.startsWith("*.") then {
      val suffix = pattern.drop(1)
      domain.endsWith(suffix) || domain == pattern.drop(2)
    } else domain == pattern || domain.endsWith(s".$pattern")

  private def matchesDomainOrParent(domain: String, list: Set[String]): Boolean = {
    val parts = domain.split('.').toList
    (0 until parts.length - 1).exists(i => list.contains(parts.drop(i).mkString(".")))
  }

}

private case class SnapshotCore(
    devices: Map[MacAddress, DevicePolicy],
    profiles: Map[ProfileId, ProfilePolicy],
    blocklists: Map[BlocklistId, Blocklist],
)

object PolicyService {
  val layer: ZLayer[
    ProfileRepo & ScheduleRepo & HouseholdSettingsRepo & TimeLimitRepo & SiteTimeLimitRepo &
      DeviceRepo & BlocklistRepo & TrafficReportRepo & TimeExtensionRepo & Clock,
    Nothing,
    PolicyService,
  ] = ZLayer.fromFunction(PolicyServiceLive(_, _, _, _, _, _, _, _, _, _))

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
   * #354: pure function that collapses a profile's raw inputs into the effective BlockRules served
   * in the snapshot. Precedence for `blockReason` when multiple block conditions hold: Paused >
   * Schedule > TimeLimit.
   */
  private[policy] def computeBlockRules(
      in: ProfileInputs,
      now: Instant,
  ): BlockRules = {
    val p = in.profile

    // Per-site limits exhausted today → host appears in extraBlocked too.
    // domainPattern is a glob string, not a Hostname — we keep it as-is in the
    // extraBlocked list so the router agent can match it. We do NOT wrap with
    // Hostname here because glob patterns like *.youtube.com are not hostnames.
    // Instead, we pass them as raw strings and convert via Hostname.unsafe for
    // the typed list (the router treats these as patterns, so validation is relaxed).
    val siteLimitExtraBlocked: List[Hostname] = in.siteLimits.collect {
      case sl if in.minutesByDomain.getOrElse(sl.domainPattern, 0) >= sl.dailyMinutes =>
        Hostname.unsafe(sl.domainPattern)
    }

    val (blocked, reason) =
      if p.paused then (true, Some(MacBlockReason.Paused: MacBlockReason))
      else if in.schedules.exists(s => scheduleActiveAt(s, now)) then
        (true, Some(MacBlockReason.Schedule: MacBlockReason))
      else
        in.dailyMinutes match {
          case Some(limit) if in.totalMinutesUsed >= limit + in.extensionsMinutes =>
            (true, Some(MacBlockReason.TimeLimit: MacBlockReason))
          case _                                                                  =>
            (false, None)
        }

    BlockRules(
      blocked = blocked,
      blockReason = reason,
      extraBlocked = (p.extraBlocked ++ siteLimitExtraBlocked).distinct,
      extraAllowed = p.extraAllowed,
      blocklistIds = p.blockedCategories,
      blockIpOnly = p.blockIpOnly, // #424: per-profile toggle (router enforcement #353)
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
