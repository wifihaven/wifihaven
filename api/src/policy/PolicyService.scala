package familydns.api.policy

import familydns.api.db.*
import familydns.api.presence.Presence
import familydns.shared.*
import zio.{Clock as _, *}

import java.security.MessageDigest
import java.time.{DayOfWeek, LocalDate, LocalTime, OffsetDateTime, ZoneOffset}

trait PolicyService {
  def snapshot: Task[PolicySnapshot]
  def renderRpz(category: String): Task[Option[(String, String)]]
  def decide(mac: String, hostname: String): Task[RouterDecisionResponse]
}

class PolicyServiceLive(
    profileRepo: ProfileRepo,
    scheduleRepo: ScheduleRepo,
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
      today    <- clock.today
      now      <- clock.now
      profiles <- profileRepo.listAll
      devices  <- deviceRepo.listAll
      scheds   <- ZIO.foreach(profiles)(p => scheduleRepo.listForProfile(p.id).map(p.id -> _))
      tlims    <- ZIO.foreach(profiles)(p => timeLimitRepo.findForProfile(p.id).map(p.id -> _))
      stlims   <- ZIO.foreach(profiles)(p => siteTimeLimitRepo.listForProfile(p.id).map(p.id -> _))
      // Presence rows are bucket-deduped downstream so a device active on
      // multiple hostnames in the same 5-min window only counts once toward
      // total screen time (see Presence). Per-site sub-caps stay independent.
      presence <- trafficRepo.listPresenceRows(devices.map(_.mac), today)
      exts     <- extRepo.snapshotAllByProfile(today)
      cats     <- blocklistRepo.listCategories
      schedMap = scheds.toMap
      tlMap    = tlims.toMap
      stlMap   = stlims.toMap
    } yield {
      val devsByProfile =
        devices.groupBy(_.profileId).collect { case (Some(pid), devs) => pid -> devs }
      val pProfiles     = profiles.map { p =>
        val pSched     = schedMap
          .getOrElse(p.id, Nil)
          .map(s => PolicySchedule(s.days, s.blockFrom, s.blockUntil))
        val pSiteLims  = stlMap
          .getOrElse(p.id, Nil)
          .map(s => PolicySiteLimit(s.domainPattern, s.dailyMinutes, s.label, s.exemptFromDaily))
        val devicesIn  = devsByProfile.getOrElse(p.id, Nil)
        val deviceMacs = devicesIn.map(_.mac).toSet
        val pPresence  = presence.filter(r => deviceMacs.contains(r.mac))
        val patterns   = stlMap.getOrElse(p.id, Nil).map(_.domainPattern)
        val perPat     = Presence.patternMinutesByMac(pPresence, patterns)
        val byDomain   = patterns.foldLeft(Map.empty[String, Int]) { (acc, pat) =>
          val mins = devicesIn.iterator.map(d => perPat.getOrElse((d.mac, pat), 0)).sum
          if mins == 0 then acc else acc.updated(pat, mins)
        }
        // Only exempt site domains are excluded from the daily total.
        // Included sites (exemptFromDaily=false) count against the daily cap.
        val exemptPats =
          stlMap.getOrElse(p.id, Nil).filter(_.exemptFromDaily).map(_.domainPattern)
        val perMacTot  = Presence.totalMinutesByMac(pPresence, exemptPats)
        val totalMins  = devicesIn.iterator.map(d => perMacTot.getOrElse(d.mac, 0)).sum
        val extMins    = exts.getOrElse(p.id, 0)
        PolicyProfile(
          id = p.id,
          name = p.name,
          paused = p.paused,
          blockedCategories = p.blockedCategories,
          extraBlocked = p.extraBlocked,
          extraAllowed = p.extraAllowed,
          schedules = pSched,
          dailyMinutes = tlMap.getOrElse(p.id, None).map(_.dailyMinutes),
          siteLimits = pSiteLims,
          timeUsedToday = PolicyTimeUsedToday(totalMins, byDomain),
          extensionsTodayMinutes = extMins,
        )
      }
      val pDevices      = devices.map(d => PolicyDevice(d.mac, d.profileId, d.name))
      val pBlocklists   = cats.map { c =>
        c -> PolicyBlocklist(version = today.toString, url = s"/api/blocklists/$c.rpz")
      }.toMap
      val defaultId     = profiles.map(_.id).minOption
      // #305: precompute the set of MACs that should be blocked right now from
      // pause / daily time limit / active schedule window. The OpenWRT agent
      // used to derive this itself but never implemented schedule windows;
      // doing it server-side keeps the router-side enforcement dumb.
      val blockedMacs   =
        PolicyService.computeBlockedMacs(pProfiles, pDevices, now.toLocalTime, today)
      val core          = SnapshotCore(defaultId, pDevices, pProfiles, blockedMacs, pBlocklists)
      val etag          = PolicyService.computeEtag(core)
      PolicySnapshot(
        etag = etag,
        generatedAt = now.toString,
        defaultProfileId = defaultId,
        devices = pDevices,
        profiles = pProfiles,
        blockedMacs = blockedMacs,
        blocklists = pBlocklists,
      )
    }

  def renderRpz(category: String): Task[Option[(String, String)]] =
    for {
      today   <- clock.today
      domains <- blocklistRepo.loadCategory(category)
    } yield
      if domains.isEmpty then None
      else {
        val origin = s"$category.rpz."
        val serial = today.toString.replace("-", "")
        val sb     = new StringBuilder
        sb.append(s"$$ORIGIN $origin\n")
        sb.append("$TTL 300\n")
        sb.append(s"@ SOA localhost. admin.localhost. $serial 3600 600 86400 60\n")
        sb.append("@ NS localhost.\n")
        domains.toList.sorted.foreach(d => sb.append(s"$d CNAME .\n"))
        val body   = sb.toString
        val etag   = s"\"${PolicyService.sha256Hex(body).take(16)}-$serial\""
        Some((etag, body))
      }

  def decide(mac: String, hostname: String): Task[RouterDecisionResponse] =
    for {
      snap  <- snapshot
      now   <- clock.now
      today <- clock.today
      device  = snap.devices.find(_.mac.equalsIgnoreCase(mac))
      profile = device.flatMap(d => d.profileId.flatMap(pid => snap.profiles.find(_.id == pid)))
      result <- profile match {
        case None    => ZIO.succeed(RouterDecisionResponse("allow", "no_profile", None))
        case Some(p) =>
          val h = hostname.toLowerCase.stripSuffix(".")
          if p.paused then ZIO.succeed(RouterDecisionResponse("block", "paused", None))
          else
            scheduleBlock(p.schedules, now.toLocalTime, today).flatMap {
              case Some(r) => ZIO.succeed(r)
              case None    =>
                if matchesAny(h, p.extraAllowed) then
                  ZIO.succeed(RouterDecisionResponse("allow", "extra_allowed", None))
                else if matchesAny(h, p.extraBlocked) then
                  ZIO.succeed(RouterDecisionResponse("block", "extra_blocked", None))
                else
                  timeLimitBlock(p, h, today) match {
                    case Some(r) => ZIO.succeed(r)
                    case None    =>
                      categoryBlock(p.blockedCategories, h).map {
                        case Some(cat) => RouterDecisionResponse("block", s"category:$cat", None)
                        case None      => RouterDecisionResponse("allow", "allowed", None)
                      }
                  }
            }
      }
    } yield result

  private def scheduleBlock(
      schedules: List[PolicySchedule],
      nowTime: LocalTime,
      today: LocalDate,
  ): Task[Option[RouterDecisionResponse]] = {
    val todayName = dayName(today)
    val prevName  = dayName(today.minusDays(1))
    val active    = schedules.find { s =>
      val from  = parseTime(s.blockFrom)
      val until = parseTime(s.blockUntil)
      if from.isAfter(until) then
        // Overnight: active if today is in days AND (now >= from OR now < until)
        (s.days.contains(todayName) && !nowTime.isBefore(from)) ||
        (s.days.contains(prevName) && nowTime.isBefore(until))
      else s.days.contains(todayName) && !nowTime.isBefore(from) && nowTime.isBefore(until)
    }
    ZIO.succeed(active.map { s =>
      val from        = parseTime(s.blockFrom)
      val until       = parseTime(s.blockUntil)
      val isOvernight = from.isAfter(until)
      val expiresAt   =
        if isOvernight && !nowTime.isBefore(from) then
          // Started today, ends tomorrow
          utcString(today.plusDays(1), until)
        else
          // Ends today (same-day schedule, or overnight tail)
          utcString(today, until)
      RouterDecisionResponse("block", "schedule", Some(expiresAt))
    })
  }

  private def timeLimitBlock(
      p: PolicyProfile,
      hostname: String,
      today: LocalDate,
  ): Option[RouterDecisionResponse] = {
    val midnight     = utcString(today.plusDays(1), LocalTime.MIDNIGHT)
    // Check per-site sub-cap first (applies to both exempt and included sites)
    val siteLimitHit = p.siteLimits.find { sl =>
      matchesDomainPattern(hostname, sl.domain) &&
      p.timeUsedToday.byDomain.getOrElse(sl.domain, 0) >= sl.minutes
    }
    siteLimitHit
      .map(sl => RouterDecisionResponse("block", s"site_time_limit:${sl.label}", Some(midnight)))
      .orElse {
        // Daily total cap: skip entirely if this hostname belongs to an exempt site limit.
        // Included sites (exemptFromDaily=false) already appear in totalMinutes via the
        // snapshot calculation, so the check below naturally applies to them.
        val isExemptSite =
          p.siteLimits.exists(sl => sl.exemptFromDaily && matchesDomainPattern(hostname, sl.domain))
        if isExemptSite then None
        else
          p.dailyMinutes.flatMap { limit =>
            val used = p.timeUsedToday.totalMinutes
            val ext  = p.extensionsTodayMinutes
            Option.when(used >= limit + ext)(
              RouterDecisionResponse("block", "time_limit", Some(midnight)),
            )
          }
      }
  }

  private def categoryBlock(
      cats: List[String],
      hostname: String,
  ): Task[Option[String]] =
    blocklistRepo.loadAll.map { allLists =>
      cats.find { cat =>
        val list = allLists.getOrElse(cat, Set.empty)
        matchesDomainOrParent(hostname, list)
      }
    }

  private def matchesAny(domain: String, patterns: List[String]): Boolean =
    patterns.exists(p => matchesDomainPattern(domain, p))

  private def matchesDomainPattern(domain: String, pattern: String): Boolean =
    if pattern.startsWith("*.") then {
      val suffix = pattern.drop(1)
      domain.endsWith(suffix) || domain == pattern.drop(2)
    } else domain == pattern || domain.endsWith(s".$pattern")

  private def matchesDomainOrParent(domain: String, list: Set[String]): Boolean = {
    val parts = domain.split('.').toList
    (0 until parts.length - 1).exists(i => list.contains(parts.drop(i).mkString(".")))
  }

  private def parseTime(s: String): LocalTime = {
    val Array(h, m) = s.split(':')
    LocalTime.of(h.toInt, m.toInt)
  }

  private val dayNames: Map[DayOfWeek, String] = Map(
    DayOfWeek.MONDAY    -> "mon",
    DayOfWeek.TUESDAY   -> "tue",
    DayOfWeek.WEDNESDAY -> "wed",
    DayOfWeek.THURSDAY  -> "thu",
    DayOfWeek.FRIDAY    -> "fri",
    DayOfWeek.SATURDAY  -> "sat",
    DayOfWeek.SUNDAY    -> "sun",
  )

  private def dayName(d: LocalDate): String = dayNames(d.getDayOfWeek)

  private def utcString(date: LocalDate, time: LocalTime): String =
    OffsetDateTime.of(date, time, ZoneOffset.UTC).toInstant.toString
}

private case class SnapshotCore(
    defaultProfileId: Option[Long],
    devices: List[PolicyDevice],
    profiles: List[PolicyProfile],
    blockedMacs: List[BlockedMac],
    blocklists: Map[String, PolicyBlocklist],
)

object PolicyService {
  val layer: ZLayer[
    ProfileRepo & ScheduleRepo & TimeLimitRepo & SiteTimeLimitRepo & DeviceRepo & BlocklistRepo &
      TrafficReportRepo & TimeExtensionRepo & Clock,
    Nothing,
    PolicyService,
  ] = ZLayer.fromFunction(PolicyServiceLive(_, _, _, _, _, _, _, _, _))

  def sha256Hex(s: String): String = {
    val md = MessageDigest.getInstance("SHA-256")
    md.digest(s.getBytes("UTF-8")).map("%02x".format(_)).mkString
  }

  /** Hex SHA-256 of a router/enrollment token, used as the storage key. */
  def hashToken(raw: String): String = sha256Hex(raw)

  /** Deterministic ETag over snapshot logical content. */
  private[policy] def computeEtag(core: SnapshotCore): String = {
    val parts = scala.collection.mutable.ArrayBuffer.empty[String]
    parts += s"d=${core.defaultProfileId.getOrElse("-")}"
    core.devices
      .sortBy(_.mac)
      .foreach(d => parts += s"dev:${d.mac}|${d.profileId.getOrElse("-")}|${d.name}")
    core.profiles.sortBy(_.id).foreach { p =>
      parts += s"p:${p.id}|${p.name}|${p.paused}|${p.dailyMinutes.getOrElse(-1)}|${p.extensionsTodayMinutes}"
      parts += s"  bc:${p.blockedCategories.sorted.mkString(",")}"
      parts += s"  eb:${p.extraBlocked.sorted.mkString(",")}"
      parts += s"  ea:${p.extraAllowed.sorted.mkString(",")}"
      p.schedules.sortBy(s => (s.blockFrom, s.blockUntil)).foreach { s =>
        parts += s"  s:${s.days.mkString(",")}|${s.blockFrom}|${s.blockUntil}"
      }
      p.siteLimits.sortBy(_.domain).foreach { sl =>
        parts += s"  sl:${sl.domain}|${sl.minutes}|${sl.label}|${sl.exemptFromDaily}"
      }
      parts += s"  u:${p.timeUsedToday.totalMinutes}"
      p.timeUsedToday.byDomain.toList.sortBy(_._1).foreach((k, v) => parts += s"    ud:$k=$v")
    }
    // #305: blockedMacs participates in the etag so an unchanged-row policy
    // still flips the etag when the wall clock crosses a schedule window edge.
    core.blockedMacs.sortBy(_.mac).foreach(b => parts += s"bm:${b.mac}|${b.reason}")
    core.blocklists.toList.sortBy(_._1).foreach((k, v) => parts += s"bl:$k=${v.version}")
    "\"sha256:" + sha256Hex(parts.mkString("\n")) + "\""
  }

  /**
   * #305: compute the currently-blocked MACs from pause / daily time limit / active schedule
   * window. Precedence is pause > time_limit > schedule — matches the legacy /api/router/decision
   * ordering in [[PolicyServiceLive.decide]].
   */
  private[policy] def computeBlockedMacs(
      profiles: List[PolicyProfile],
      devices: List[PolicyDevice],
      nowTime: java.time.LocalTime,
      today: java.time.LocalDate,
  ): List[BlockedMac] = {
    val todayName                                  = today.getDayOfWeek match {
      case java.time.DayOfWeek.MONDAY    => "mon"
      case java.time.DayOfWeek.TUESDAY   => "tue"
      case java.time.DayOfWeek.WEDNESDAY => "wed"
      case java.time.DayOfWeek.THURSDAY  => "thu"
      case java.time.DayOfWeek.FRIDAY    => "fri"
      case java.time.DayOfWeek.SATURDAY  => "sat"
      case java.time.DayOfWeek.SUNDAY    => "sun"
    }
    val prevName                                   = today.minusDays(1).getDayOfWeek match {
      case java.time.DayOfWeek.MONDAY    => "mon"
      case java.time.DayOfWeek.TUESDAY   => "tue"
      case java.time.DayOfWeek.WEDNESDAY => "wed"
      case java.time.DayOfWeek.THURSDAY  => "thu"
      case java.time.DayOfWeek.FRIDAY    => "fri"
      case java.time.DayOfWeek.SATURDAY  => "sat"
      case java.time.DayOfWeek.SUNDAY    => "sun"
    }
    def parseTime(s: String): java.time.LocalTime  = {
      val Array(h, m) = s.split(':')
      java.time.LocalTime.of(h.toInt, m.toInt)
    }
    def scheduleActive(s: PolicySchedule): Boolean = {
      val from  = parseTime(s.blockFrom)
      val until = parseTime(s.blockUntil)
      if from.isAfter(until) then
        (s.days.contains(todayName) && !nowTime.isBefore(from)) ||
        (s.days.contains(prevName) && nowTime.isBefore(until))
      else s.days.contains(todayName) && !nowTime.isBefore(from) && nowTime.isBefore(until)
    }
    val byProfile                                  = profiles.iterator.map { p =>
      val reason =
        if p.paused then Some("paused")
        else
          p.dailyMinutes match {
            case Some(limit) if p.timeUsedToday.totalMinutes >= limit + p.extensionsTodayMinutes =>
              Some("time_limit")
            case _                                                                               =>
              if p.schedules.exists(scheduleActive) then Some("schedule") else None
          }
      p.id -> reason
    }.toMap

    devices.flatMap { d =>
      for {
        pid    <- d.profileId
        reason <- byProfile.getOrElse(pid, None)
      } yield BlockedMac(d.mac, reason)
    }
  }
}
