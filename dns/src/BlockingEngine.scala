package familydns.dns

import familydns.shared.*
import familydns.shared.Schedule

import java.time.{DayOfWeek, LocalDate, LocalTime}

/** Pure blocking logic — no effects, fully testable. */
object BlockingEngine {

  private val dayNames: Map[DayOfWeek, String] = Map(
    DayOfWeek.MONDAY    -> "mon",
    DayOfWeek.TUESDAY   -> "tue",
    DayOfWeek.WEDNESDAY -> "wed",
    DayOfWeek.THURSDAY  -> "thu",
    DayOfWeek.FRIDAY    -> "fri",
    DayOfWeek.SATURDAY  -> "sat",
    DayOfWeek.SUNDAY    -> "sun",
  )

  sealed trait Decision
  object Decision {
    case object Allow                extends Decision
    case class Block(reason: String) extends Decision
  }

  /**
   * Full decision including blocklist lookup.
   *
   * Priority:
   *   1. Profile paused → block:paused 2. Schedule active → block:schedule 3. Domain in
   *      extra_allowed → allow (overrides everything below) 4. Domain in extra_blocked →
   *      block:extra_blocked 5. Daily total time limit hit → block:time_limit 6. Site-specific
   *      limit hit → block:site_time_limit:<label> 7. Category blocklist match →
   *      block:category:<cat> 8. Default → allow
   */
  def decide(
      domain: String,
      profile: CachedProfile,
      blocklists: Map[String, Set[String]],
      usage: TimeUsageSnapshot,
      mac: String,
      now: LocalTime,
      today: LocalDate,
  ): Decision = {
    val d = domain.toLowerCase.stripSuffix(".")

    if profile.profile.paused then Decision.Block("paused")
    else if isScheduleActive(profile.schedules, now, today) then Decision.Block("schedule")
    else if matchesAny(d, profile.profile.extraAllowed) then Decision.Allow
    else if matchesAny(d, profile.profile.extraBlocked) then Decision.Block("extra_blocked")
    else {
      // Total daily time limit — site-specific domains are exempt
      val timeLimitBlock: Option[Decision] = profile.timeLimit.flatMap { limitMins =>
        val isSiteDomain =
          profile.siteTimeLimits.exists(s => matchesDomainPattern(d, s.domainPattern))
        if !isSiteDomain then {
          val usedMins = usage.totalUsage.getOrElse((mac, today.toString), 0)
          val extMins  = usage.extensions.getOrElse((mac, today.toString), 0)
          Option.when(usedMins >= limitMins + extMins)(Decision.Block("time_limit"))
        } else None
      }

      // Per-site time limits
      val siteBlock: Option[Decision] = profile.siteTimeLimits.collectFirst {
        case stl if matchesDomainPattern(d, stl.domainPattern) =>
          val apex     = apexDomain(d)
          val usedMins = usage.domainUsage.getOrElse((mac, apex, today.toString), 0)
          Option.when(usedMins >= stl.dailyMinutes)(Decision.Block(s"site_time_limit:${stl.label}"))
      }.flatten

      // Category blocklists
      val categoryBlock: Option[Decision] = profile.profile.blockedCategories.collectFirst {
        case cat if matchesDomainOrParent(d, blocklists.getOrElse(cat, Set.empty)) =>
          Decision.Block(s"category:$cat")
      }

      timeLimitBlock.orElse(siteBlock).orElse(categoryBlock).getOrElse(Decision.Allow)
    }
  }

  def isScheduleActive(schedules: List[Schedule], now: LocalTime, today: LocalDate): Boolean = {
    val todayName = dayNames(today.getDayOfWeek)
    schedules.exists { s =>
      if !s.days.contains(todayName) then false
      else {
        val from  = parseTime(s.blockFrom)
        val until = parseTime(s.blockUntil)
        if from.isAfter(until) then
          // Overnight: e.g. 21:00 → 07:00 (inclusive at from, exclusive at until)
          !now.isBefore(from) || now.isBefore(until)
        else !now.isBefore(from) && now.isBefore(until)
      }
    }
  }

  def apexDomain(domain: String): String = {
    val parts = domain.split('.')
    if parts.length >= 2 then parts.takeRight(2).mkString(".") else domain
  }

  def matchesDomainPattern(domain: String, pattern: String): Boolean =
    if pattern.startsWith("*.") then {
      val suffix = pattern.drop(1)
      domain.endsWith(suffix) || domain == pattern.drop(2)
    } else domain == pattern || domain.endsWith(s".$pattern")

  private def matchesAny(domain: String, patterns: List[String]): Boolean =
    patterns.exists(p => matchesDomainPattern(domain, p))

  /** Check domain and all parent labels against blocklist set */
  private def matchesDomainOrParent(domain: String, list: Set[String]): Boolean = {
    val parts = domain.split('.').toList
    (0 until parts.length - 1).exists(i => list.contains(parts.drop(i).mkString(".")))
  }

  private def parseTime(s: String): LocalTime = {
    val Array(h, m) = s.split(':')
    LocalTime.of(h.toInt, m.toInt)
  }
}
