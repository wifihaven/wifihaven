package wifihaven.api.policy

import wifihaven.api.db.*
import wifihaven.api.presence.{Presence, PresenceRow}
import wifihaven.shared.{Schedule as DbSchedule, *}
import wifihaven.shared.types.*
import zio.{Clock as _, *}

import java.time.{Instant, LocalDate}

/**
 * Canonical per-profile cap/block state for a single household-local date. Returned by
 * [[TimeStatusService.todaysState]] / [[TimeStatusService.dayState]] and consumed by both the
 * policy snapshot ([[PolicyService.snapshot]]) and the seven `/api/time/status/...` endpoints.
 * Having one function emit this means the snapshot's `blocked` / `blockReason` cannot drift from
 * the UI's `usedMinutes` / `extensionMinutes` — they read the same fields off the same case class
 * (#1104).
 */
final case class ProfileDayState(
    profileId: ProfileId,
    date: LocalDate,
    dailyLimitMinutes: Option[Int],
    usedMinutes: Int,
    extensionMinutes: Int,
    remainingMinutes: Option[Int],
    blocked: Boolean,
    blockReason: Option[MacBlockReason],
    perSite: List[SiteDayState],
)

/**
 * Per-site (site_time_limits) usage breakdown for a profile-day. `usedMinutes` is the
 * bucket-counted active minutes against this site's domain pattern, summed across the profile's
 * devices, computed the same way regardless of whether the consumer is the snapshot (deciding which
 * sites land in extraBlocked) or a route (rendering the per-site bars in the UI).
 */
final case class SiteDayState(
    label: String,
    domainPattern: String,
    dailyLimitMinutes: Int,
    usedMinutes: Int,
    exemptFromDaily: Boolean,
)

trait TimeStatusService {

  /**
   * The canonical per-profile day state for the household-local date containing `now`. Returns
   * `None` if no such profile exists. Use this on the snapshot/grant/ingestion side and for
   * `/api/time/status/...` reads against today.
   */
  def todaysState(
      now: Instant,
      settings: HouseholdSettings,
      profileId: ProfileId,
  ): Task[Option[ProfileDayState]]

  /**
   * Same as [[todaysState]] but for an explicit date. Used by the `?date=` query-param override on
   * the seven `/api/time/status/...` endpoints so the UI can scroll back to a historical day; the
   * date is treated as the canonical bucket date (no tz reprojection — `time_usage` /
   * `traffic_reports` are already bucketed under household-local dates).
   *
   * For past dates this reads `usedMinutes` from the `time_used_daily` rollup (#1160) when
   * available, falling back to a live aggregation on miss. Today is always live so enforcement
   * (which threads through [[dayStateAllLive]] on the snapshot path) and the UI never disagree on
   * the current cap.
   *
   * `now` is still needed so the schedule/paused arm of the block evaluation has a wall-clock
   * instant to test windows against. For historical dates the schedule arm will generally not be
   * active (today's wall clock is not in yesterday's schedule window), but pinning `now` here keeps
   * the function total.
   */
  def dayState(
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
      profileId: ProfileId,
  ): Task[Option[ProfileDayState]]

  /**
   * Batched form for [[PolicyService.snapshot]] and the `/api/time/status/summary` route. Emits one
   * `ProfileDayState` per profile for `date`. For past dates this is served from the
   * `time_used_daily` rollup; today is computed live.
   */
  def dayStateAll(
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
  ): Task[Map[ProfileId, ProfileDayState]]

  /**
   * Always-live variant of [[dayState]] — recomputes from raw `traffic_reports` regardless of cache
   * state. The rollup fiber writes through this so the cache cannot drift from the live computation
   * (#1160's source-of-truth invariant).
   */
  def dayStateLive(
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
      profileId: ProfileId,
  ): Task[Option[ProfileDayState]]

  /** Always-live batched variant of [[dayStateAll]] — see [[dayStateLive]]. */
  def dayStateAllLive(
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
  ): Task[Map[ProfileId, ProfileDayState]]
}

class TimeStatusServiceLive(
    profileRepo: ProfileRepo,
    scheduleRepo: ScheduleRepo,
    timeLimitRepo: TimeLimitRepo,
    siteTimeLimitRepo: SiteTimeLimitRepo,
    deviceRepo: DeviceRepo,
    trafficRepo: TrafficReportRepo,
    extRepo: TimeExtensionRepo,
    // Defaulting to the noop lets the many call sites in TimeApiSpec / snapshot specs keep their
    // 7-arg constructions — they exercise today, which always takes the live path anyway.
    rollupRepo: TimeUsedRollupRepo = NoopTimeUsedRollupRepo,
) extends TimeStatusService {

  def todaysState(
      now: Instant,
      settings: HouseholdSettings,
      profileId: ProfileId,
  ): Task[Option[ProfileDayState]] =
    dayState(now, PolicyService.householdLocalDate(now, settings), settings, profileId)

  def dayState(
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
      profileId: ProfileId,
  ): Task[Option[ProfileDayState]] = {
    val today = PolicyService.householdLocalDate(now, settings)
    if (date.isBefore(today))
      rollupRepo.getDayForProfile(profileId, date).flatMap {
        case Some(used) => dayStateFromRolled(now, date, profileId, used)
        case None       => dayStateLive(now, date, settings, profileId)
      }
    else dayStateLive(now, date, settings, profileId)
  }

  def dayStateLive(
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
      profileId: ProfileId,
  ): Task[Option[ProfileDayState]] =
    profileRepo.findById(profileId).flatMap {
      case None    => ZIO.succeed(None)
      case Some(p) =>
        for {
          schedules <- scheduleRepo.listForProfile(profileId)
          tl        <- timeLimitRepo.findForProfile(profileId)
          stls      <- siteTimeLimitRepo.listForProfile(profileId)
          devices   <- deviceRepo.listAll.map(_.filter(_.profileId.contains(profileId)))
          presence  <- trafficRepo.listPresenceRows(devices.map(_.mac), date)
          extMins   <- extRepo.getProfileTotalExtension(profileId, date)
        } yield Some(
          TimeStatusService.fold(
            profile = p,
            schedules = schedules,
            devices = devices,
            dailyLimit = tl.map(_.dailyMinutes),
            siteLimits = stls,
            presence = presence,
            extensionMinutes = extMins,
            date = date,
            now = now,
            settings = settings,
          ),
        )
    }

  def dayStateAll(
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
  ): Task[Map[ProfileId, ProfileDayState]] = {
    val today = PolicyService.householdLocalDate(now, settings)
    if (date.isBefore(today)) dayStateAllFromRollup(now, date, settings)
    else dayStateAllLive(now, date, settings)
  }

  def dayStateAllLive(
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
  ): Task[Map[ProfileId, ProfileDayState]] =
    for {
      profiles <- profileRepo.listAll
      devices  <- deviceRepo.listAll
      schedsP  <- ZIO.foreach(profiles)(p => scheduleRepo.listForProfile(p.id).map(p.id -> _))
      tlsP     <- ZIO.foreach(profiles)(p => timeLimitRepo.findForProfile(p.id).map(p.id -> _))
      stlsP    <- ZIO.foreach(profiles)(p => siteTimeLimitRepo.listForProfile(p.id).map(p.id -> _))
      presence <- trafficRepo.listPresenceRows(devices.map(_.mac), date)
      exts     <- extRepo.snapshotAllByProfile(date)
    } yield {
      val schedMap = schedsP.toMap
      val tlMap    = tlsP.toMap
      val stlMap   = stlsP.toMap
      val devsByP  =
        devices.groupBy(_.profileId).collect { case (Some(pid), devs) => pid -> devs }
      profiles.iterator.map { p =>
        val devs    = devsByP.getOrElse(p.id, Nil)
        val macSet  = devs.map(_.mac).toSet
        val pPres   = presence.filter(r => macSet.contains(r.mac))
        val extMins = exts.getOrElse(p.id, 0)
        p.id -> TimeStatusService.fold(
          profile = p,
          schedules = schedMap.getOrElse(p.id, Nil),
          devices = devs,
          dailyLimit = tlMap.getOrElse(p.id, None).map(_.dailyMinutes),
          siteLimits = stlMap.getOrElse(p.id, Nil),
          presence = pPres,
          extensionMinutes = extMins,
          date = date,
          now = now,
          settings = settings,
        )
      }.toMap
    }

  // Build a ProfileDayState for a past date from a precomputed `usedMinutes`,
  // skipping the heavy presence load. Schedules / paused / extensions / daily
  // limit / site limits are still loaded live so the block-precedence math
  // matches the live folder. Per-site `usedMinutes` is reported as zero — the
  // v1 rollup is profile-total only; per-site is a tracked follow-up.
  private def dayStateFromRolled(
      now: Instant,
      date: LocalDate,
      profileId: ProfileId,
      usedMinutes: Int,
  ): Task[Option[ProfileDayState]] =
    profileRepo.findById(profileId).flatMap {
      case None    => ZIO.succeed(None)
      case Some(p) =>
        for {
          schedules <- scheduleRepo.listForProfile(profileId)
          tl        <- timeLimitRepo.findForProfile(profileId)
          stls      <- siteTimeLimitRepo.listForProfile(profileId)
          extMins   <- extRepo.getProfileTotalExtension(profileId, date)
        } yield Some(
          TimeStatusService.assemble(
            profile = p,
            schedules = schedules,
            dailyLimit = tl.map(_.dailyMinutes),
            siteLimits = stls,
            usedMinutes = usedMinutes,
            extensionMinutes = extMins,
            date = date,
            now = now,
          ),
        )
    }

  // Batched variant. On a partial cache miss the missing profiles fall back
  // to the live aggregation in one go — cheaper than recursing per-profile.
  private def dayStateAllFromRollup(
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
  ): Task[Map[ProfileId, ProfileDayState]] =
    for {
      profiles <- profileRepo.listAll
      rolled   <- rollupRepo.getDayMap(date)
      hit  = profiles.filter(p => rolled.contains(p.id))
      miss = profiles.filterNot(p => rolled.contains(p.id))
      schedsP <- ZIO.foreach(hit)(p => scheduleRepo.listForProfile(p.id).map(p.id -> _))
      tlsP    <- ZIO.foreach(hit)(p => timeLimitRepo.findForProfile(p.id).map(p.id -> _))
      stlsP   <- ZIO.foreach(hit)(p => siteTimeLimitRepo.listForProfile(p.id).map(p.id -> _))
      exts    <- extRepo.snapshotAllByProfile(date)
      live    <-
        if miss.isEmpty then ZIO.succeed(Map.empty[ProfileId, ProfileDayState])
        else
          dayStateAllLive(now, date, settings).map(_.filter { case (pid, _) =>
            miss.exists(_.id == pid)
          })
    } yield {
      val schedMap = schedsP.toMap
      val tlMap    = tlsP.toMap
      val stlMap   = stlsP.toMap
      val cached   = hit.iterator.map { p =>
        p.id -> TimeStatusService.assemble(
          profile = p,
          schedules = schedMap.getOrElse(p.id, Nil),
          dailyLimit = tlMap.getOrElse(p.id, None).map(_.dailyMinutes),
          siteLimits = stlMap.getOrElse(p.id, Nil),
          usedMinutes = rolled(p.id),
          extensionMinutes = exts.getOrElse(p.id, 0),
          date = date,
          now = now,
        )
      }.toMap
      cached ++ live
    }
}

object TimeStatusService {

  val layer: ZLayer[
    ProfileRepo & ScheduleRepo & TimeLimitRepo & SiteTimeLimitRepo & DeviceRepo &
      TrafficReportRepo & TimeExtensionRepo & TimeUsedRollupRepo,
    Nothing,
    TimeStatusService,
  ] = ZLayer.fromFunction {
    (
        pr: ProfileRepo,
        sr: ScheduleRepo,
        tlr: TimeLimitRepo,
        stlr: SiteTimeLimitRepo,
        dr: DeviceRepo,
        trr: TrafficReportRepo,
        er: TimeExtensionRepo,
        ru: TimeUsedRollupRepo,
    ) => new TimeStatusServiceLive(pr, sr, tlr, stlr, dr, trr, er, ru)
  }

  /**
   * Pure folder: collapse the per-profile inputs into the canonical day state. Same math the
   * snapshot and the routes used to do independently — the consolidation contract (#1104) is that
   * every consumer reads through this function.
   *
   * Block precedence (mirrors `PolicyService.computeBlockRules`): `Paused` > `Schedule` >
   * `TimeLimit`.
   */
  def fold(
      profile: Profile,
      schedules: List[DbSchedule],
      devices: List[Device],
      dailyLimit: Option[Int],
      siteLimits: List[SiteTimeLimit],
      presence: List[PresenceRow],
      extensionMinutes: Int,
      date: LocalDate,
      now: Instant,
      settings: HouseholdSettings,
  ): ProfileDayState = {
    val patterns   = siteLimits.map(_.domainPattern)
    val exemptPats = siteLimits.filter(_.exemptFromDaily).map(_.domainPattern)
    val perPat     = Presence.patternMinutesByMac(presence, patterns)
    val perMacTot  = Presence.totalMinutesByMac(presence, exemptPats, settings.heartbeatFilter)

    // #751: cap-enforcement honours the profile's overlap mode (Sum vs Dedup) — the same branch the
    // routes used to apply independently.
    val totalMinutesUsed = profile.crossDeviceOverlapMode match {
      case CrossDeviceOverlapMode.Sum   =>
        devices.iterator.map(d => perMacTot.getOrElse(d.mac, 0)).sum
      case CrossDeviceOverlapMode.Dedup =>
        Presence.dedupedTotalMinutes(presence, exemptPats, settings.heartbeatFilter)
    }

    val byDomain: Map[String, Int] = patterns.foldLeft(Map.empty[String, Int]) { (acc, pat) =>
      val mins = devices.iterator.map(d => perPat.getOrElse((d.mac, pat), 0)).sum
      if mins == 0 then acc else acc.updated(pat, mins)
    }

    val perSite = siteLimits.map { sl =>
      SiteDayState(
        label = sl.label,
        domainPattern = sl.domainPattern,
        dailyLimitMinutes = sl.dailyMinutes,
        usedMinutes = byDomain.getOrElse(sl.domainPattern, 0),
        exemptFromDaily = sl.exemptFromDaily,
      )
    }

    assemble(
      profile = profile,
      schedules = schedules,
      dailyLimit = dailyLimit,
      siteLimits = siteLimits,
      usedMinutes = totalMinutesUsed,
      extensionMinutes = extensionMinutes,
      date = date,
      now = now,
      perSiteOverride = Some(perSite),
    )
  }

  /**
   * Shared assembly used by both [[fold]] (live presence aggregation) and the rollup read path
   * (#1160), once `usedMinutes` is known. Computes `blocked` / `blockReason` / `remaining` so the
   * cached-read path produces the same precedence Paused > Schedule > TimeLimit as the live path —
   * the source-of-truth invariant is enforced here by construction.
   *
   * `perSiteOverride = None` means the caller did not load per-site presence; per-site usage is
   * emitted as zero against the configured site limits (the v1 rollup is profile-total only).
   */
  def assemble(
      profile: Profile,
      schedules: List[DbSchedule],
      dailyLimit: Option[Int],
      siteLimits: List[SiteTimeLimit],
      usedMinutes: Int,
      extensionMinutes: Int,
      date: LocalDate,
      now: Instant,
      perSiteOverride: Option[List[SiteDayState]] = None,
  ): ProfileDayState = {
    val (blocked, reason) =
      if profile.paused then (true, Some(MacBlockReason.Paused: MacBlockReason))
      else if schedules.exists(s => PolicyService.scheduleActiveAt(s, now)) then
        (true, Some(MacBlockReason.Schedule: MacBlockReason))
      else
        dailyLimit match {
          case Some(limit) if usedMinutes >= limit + extensionMinutes =>
            (true, Some(MacBlockReason.TimeLimit: MacBlockReason))
          case _                                                      =>
            (false, None)
        }

    val remaining = dailyLimit.map(l => (l + extensionMinutes - usedMinutes).max(0))

    val perSite = perSiteOverride.getOrElse(
      siteLimits.map { sl =>
        SiteDayState(
          label = sl.label,
          domainPattern = sl.domainPattern,
          dailyLimitMinutes = sl.dailyMinutes,
          usedMinutes = 0,
          exemptFromDaily = sl.exemptFromDaily,
        )
      },
    )

    ProfileDayState(
      profileId = profile.id,
      date = date,
      dailyLimitMinutes = dailyLimit,
      usedMinutes = usedMinutes,
      extensionMinutes = extensionMinutes,
      remainingMinutes = remaining,
      blocked = blocked,
      blockReason = reason,
      perSite = perSite,
    )
  }
}
