package familydns.api.policy

import familydns.api.db.*
import familydns.shared.*
import zio.{Clock as _, *}

import java.security.MessageDigest
import java.time.{DayOfWeek, LocalDate, LocalTime, OffsetDateTime, ZoneOffset}

trait PolicyService:
  def snapshot: Task[PolicySnapshot]
  def renderRpz(category: String): Task[Option[(String, String)]]
  def decide(mac: String, hostname: String): Task[RouterDecisionResponse]

class PolicyServiceLive(
    profileRepo: ProfileRepo,
    scheduleRepo: ScheduleRepo,
    timeLimitRepo: TimeLimitRepo,
    siteTimeLimitRepo: SiteTimeLimitRepo,
    deviceRepo: DeviceRepo,
    blocklistRepo: BlocklistRepo,
    usageRepo: TimeUsageRepo,
    extRepo: TimeExtensionRepo,
    clock: Clock,
) extends PolicyService:

  def snapshot: Task[PolicySnapshot] =
    for
      today    <- clock.today
      now      <- clock.now
      profiles <- profileRepo.listAll
      devices  <- deviceRepo.listAll
      scheds   <- ZIO.foreach(profiles)(p => scheduleRepo.listForProfile(p.id).map(p.id -> _))
      tlims    <- ZIO.foreach(profiles)(p => timeLimitRepo.findForProfile(p.id).map(p.id -> _))
      stlims   <- ZIO.foreach(profiles)(p => siteTimeLimitRepo.listForProfile(p.id).map(p.id -> _))
      usages   <- usageRepo.snapshotAll(today)
      exts     <- extRepo.snapshotAllByProfile(today)
      cats     <- blocklistRepo.listCategories
      schedMap = scheds.toMap
      tlMap    = tlims.toMap
      stlMap   = stlims.toMap
    yield
      val devsByProfile =
        devices.groupBy(_.profileId).collect { case (Some(pid), devs) => pid -> devs }
      val pProfiles     = profiles.map { p =>
        val pSched    = schedMap
          .getOrElse(p.id, Nil)
          .map(s => PolicySchedule(s.days, s.blockFrom, s.blockUntil))
        val pSiteLims = stlMap
          .getOrElse(p.id, Nil)
          .map(s => PolicySiteLimit(s.domainPattern, s.dailyMinutes, s.label))
        val devicesIn = devsByProfile.getOrElse(p.id, Nil)
        val byDomain  = stlMap
          .getOrElse(p.id, Nil)
          .foldLeft(Map.empty[String, Int]) { (acc, stl) =>
            val mins =
              devicesIn.iterator.map(d => usages.getOrElse((d.mac, stl.domainPattern), 0)).sum
            if mins == 0 then acc else acc.updated(stl.domainPattern, mins)
          }
        val siteDoms  = stlMap.getOrElse(p.id, Nil).map(_.domainPattern).toSet
        val totalMins = usages.iterator.collect {
          case ((mac, dom), m) if devicesIn.exists(_.mac == mac) && !siteDoms.contains(dom) => m
        }.sum
        val extMins   = exts.getOrElse(p.id, 0)
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
      val core          = SnapshotCore(defaultId, pDevices, pProfiles, pBlocklists)
      val etag          = PolicyService.computeEtag(core)
      PolicySnapshot(
        etag = etag,
        generatedAt = now.toString,
        defaultProfileId = defaultId,
        devices = pDevices,
        profiles = pProfiles,
        blocklists = pBlocklists,
      )

  def renderRpz(category: String): Task[Option[(String, String)]] =
    for
      today   <- clock.today
      domains <- blocklistRepo.loadCategory(category)
    yield
      if domains.isEmpty then None
      else
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

  def decide(mac: String, hostname: String): Task[RouterDecisionResponse] =
    for
      snap  <- snapshot
      now   <- clock.now
      today <- clock.today
      device  = snap.devices.find(_.mac.equalsIgnoreCase(mac))
      profile = device.flatMap(d => d.profileId.flatMap(pid => snap.profiles.find(_.id == pid)))
      result <- profile match
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
                  timeLimitBlock(p, h, today) match
                    case Some(r) => ZIO.succeed(r)
                    case None    =>
                      categoryBlock(p.blockedCategories, h).map {
                        case Some(cat) => RouterDecisionResponse("block", s"category:$cat", None)
                        case None      => RouterDecisionResponse("allow", "allowed", None)
                      }
            }
    yield result

  private def scheduleBlock(
      schedules: List[PolicySchedule],
      nowTime: LocalTime,
      today: LocalDate,
  ): Task[Option[RouterDecisionResponse]] =
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

  private def timeLimitBlock(
      p: PolicyProfile,
      hostname: String,
      today: LocalDate,
  ): Option[RouterDecisionResponse] =
    val midnight     = utcString(today.plusDays(1), LocalTime.MIDNIGHT)
    val siteLimitHit = p.siteLimits.find { sl =>
      matchesDomainPattern(hostname, sl.domain) &&
      p.timeUsedToday.byDomain.getOrElse(sl.domain, 0) >= sl.minutes
    }
    siteLimitHit
      .map(sl => RouterDecisionResponse("block", s"site_time_limit:${sl.label}", Some(midnight)))
      .orElse {
        p.dailyMinutes.flatMap { limit =>
          val used = p.timeUsedToday.totalMinutes
          val ext  = p.extensionsTodayMinutes
          Option.when(used >= limit + ext)(
            RouterDecisionResponse("block", "time_limit", Some(midnight)),
          )
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
    if pattern.startsWith("*.") then
      val suffix = pattern.drop(1)
      domain.endsWith(suffix) || domain == pattern.drop(2)
    else domain == pattern || domain.endsWith(s".$pattern")

  private def matchesDomainOrParent(domain: String, list: Set[String]): Boolean =
    val parts = domain.split('.').toList
    (0 until parts.length - 1).exists(i => list.contains(parts.drop(i).mkString(".")))

  private def parseTime(s: String): LocalTime =
    val Array(h, m) = s.split(':')
    LocalTime.of(h.toInt, m.toInt)

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

private case class SnapshotCore(
    defaultProfileId: Option[Long],
    devices: List[PolicyDevice],
    profiles: List[PolicyProfile],
    blocklists: Map[String, PolicyBlocklist],
)

object PolicyService:
  val layer: ZLayer[
    ProfileRepo & ScheduleRepo & TimeLimitRepo & SiteTimeLimitRepo & DeviceRepo & BlocklistRepo &
      TimeUsageRepo & TimeExtensionRepo & Clock,
    Nothing,
    PolicyService,
  ] = ZLayer.fromFunction(PolicyServiceLive(_, _, _, _, _, _, _, _, _))

  def sha256Hex(s: String): String =
    val md = MessageDigest.getInstance("SHA-256")
    md.digest(s.getBytes("UTF-8")).map("%02x".format(_)).mkString

  /** Hex SHA-256 of a router/enrollment token, used as the storage key. */
  def hashToken(raw: String): String = sha256Hex(raw)

  /** Deterministic ETag over snapshot logical content. */
  private[policy] def computeEtag(core: SnapshotCore): String =
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
        parts += s"  sl:${sl.domain}|${sl.minutes}|${sl.label}"
      }
      parts += s"  u:${p.timeUsedToday.totalMinutes}"
      p.timeUsedToday.byDomain.toList.sortBy(_._1).foreach((k, v) => parts += s"    ud:$k=$v")
    }
    core.blocklists.toList.sortBy(_._1).foreach((k, v) => parts += s"bl:$k=${v.version}")
    "\"sha256:" + sha256Hex(parts.mkString("\n")) + "\""
