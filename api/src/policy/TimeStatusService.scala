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
   * Same as [[todaysState]] but for an explicit date. The `?date=` query-param override on the
   * `/api/time/status/...` endpoints uses this so the UI can scroll back to a historical day; the
   * date is treated as the canonical bucket date (no tz reprojection — `time_usage` /
   * `traffic_reports` are already bucketed under household-local dates).
   *
   * For today, this reads the `time_used_daily` rollup (#1160) and adds a live aggregation of the
   * presence buckets the rollup hasn't yet absorbed (period_start >= rolled_through). On a cache
   * miss — or for past dates — it falls through to the all-live path so the result is identical to
   * what `dayStateLive` would have returned.
   */
  def dayState(
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
      profileId: ProfileId,
  ): Task[Option[ProfileDayState]]

  /**
   * Batched form for [[PolicyService.snapshot]] and the `/api/time/status/summary` route. Emits one
   * `ProfileDayState` per profile for `date`. For today this is served from the rollup + a live
   * tail; for past dates it's all-live.
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
    // 7-arg constructions — they exercise the all-live path either way.
    rollupRepo: TimeUsedRollupRepo = NoopTimeUsedRollupRepo,
    // #1069: a profile's attached block-mode named-schedule windows are unioned into the legacy
    // `schedules` for the blocked-flag decision. Defaults to the noop so existing direct
    // constructions keep their arity.
    namedScheduleRepo: NamedScheduleRepo = NoopNamedScheduleRepo,
) extends TimeStatusService {

  // #1069: a profile's effective downtime schedules = its legacy per-profile `schedules` rows
  // unioned with the windows of every named schedule attached to it as a block schedule (via
  // `profile_schedule_rules`, mode=blocked_during). Named windows become synthetic DbSchedules so
  // the existing `scheduleActiveAt` math applies unchanged (it reads only days/start/end/tz —
  // id/profileId/name are irrelevant).
  private def syntheticWindows(pid: ProfileId, ws: List[ScheduleWindow]): List[DbSchedule] =
    ws.map(w =>
      DbSchedule(ScheduleId(0L), pid, "named-schedule", w.days, w.startLocal, w.endLocal, w.tz),
    )

  private def schedulesFor(pid: ProfileId): Task[List[DbSchedule]] =
    for {
      v1    <- scheduleRepo.listForProfile(pid)
      named <- namedScheduleRepo.windowsForProfile(pid)
    } yield v1 ++ syntheticWindows(pid, named)

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
    if (date == today)
      rollupRepo.getDayForProfile(profileId, date).flatMap {
        case Some(rolled) => dayStateFromRollupAndTail(now, date, settings, profileId, rolled)
        case None         => dayStateLive(now, date, settings, profileId)
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
          schedules <- schedulesFor(profileId)
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
    if (date == today) dayStateAllFromRollup(now, date, settings)
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
      namedP   <- namedScheduleRepo.windowsForAllProfiles
      tlsP     <- ZIO.foreach(profiles)(p => timeLimitRepo.findForProfile(p.id).map(p.id -> _))
      stlsP    <- ZIO.foreach(profiles)(p => siteTimeLimitRepo.listForProfile(p.id).map(p.id -> _))
      presence <- trafficRepo.listPresenceRows(devices.map(_.mac), date)
      exts     <- extRepo.snapshotAllByProfile(date)
    } yield {
      val schedMap = schedsP.toMap.map { case (pid, v1) =>
        pid -> (v1 ++ syntheticWindows(pid, namedP.getOrElse(pid, Nil)))
      }
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

  // Today single-profile read: rolled seconds + live aggregation of buckets the rollup hasn't
  // absorbed yet (period_start >= rolledThrough). Truncation to minutes happens once at the end so
  // the result is byte-identical to a full live aggregation over the whole day.
  private def dayStateFromRollupAndTail(
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
      profileId: ProfileId,
      rolled: RolledDay,
  ): Task[Option[ProfileDayState]] =
    profileRepo.findById(profileId).flatMap {
      case None    => ZIO.succeed(None)
      case Some(p) =>
        for {
          schedules <- schedulesFor(profileId)
          tl        <- timeLimitRepo.findForProfile(profileId)
          stls      <- siteTimeLimitRepo.listForProfile(profileId)
          devices   <- deviceRepo.listAll.map(_.filter(_.profileId.contains(profileId)))
          tail <- trafficRepo.listPresenceRowsSince(devices.map(_.mac), date, rolled.rolledThrough)
          extMins <- extRepo.getProfileTotalExtension(profileId, date)
        } yield {
          val tailSeconds =
            TimeStatusService.usedSecondsForProfile(p, devices, stls, tail, settings)
          val totalUsed   = ((rolled.usedSeconds + tailSeconds) / 60L).toInt
          Some(
            TimeStatusService.assemble(
              profile = p,
              schedules = schedules,
              dailyLimit = tl.map(_.dailyMinutes),
              siteLimits = stls,
              usedMinutes = totalUsed,
              extensionMinutes = extMins,
              date = date,
              now = now,
            ),
          )
        }
    }

  // Batched today read. On any cache miss (some profile lacks a rollup row) we fall through to the
  // all-live path for the entire batch — cheaper than mixing two code paths and the next fiber tick
  // refills the missing rows. After the fiber settles, every batch is rollup+tail.
  private def dayStateAllFromRollup(
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
  ): Task[Map[ProfileId, ProfileDayState]] =
    for {
      profiles <- profileRepo.listAll
      rolled   <- rollupRepo.getDayMap(date)
      result   <-
        if profiles.exists(p => !rolled.contains(p.id)) then dayStateAllLive(now, date, settings)
        else dayStateAllFromRollupHits(now, date, settings, profiles, rolled)
    } yield result

  private def dayStateAllFromRollupHits(
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
      profiles: List[Profile],
      rolled: Map[ProfileId, RolledDay],
  ): Task[Map[ProfileId, ProfileDayState]] = {
    // All rolled_through watermarks come from the same fiber tick in steady state, but a new
    // profile + fresh tick can land just before the read — so use the earliest watermark and let
    // per-profile filtering handle any per-row over-fetch.
    val watermark = rolled.values.iterator.map(_.rolledThrough).min
    for {
      devices <- deviceRepo.listAll
      schedsP <- ZIO.foreach(profiles)(p => scheduleRepo.listForProfile(p.id).map(p.id -> _))
      namedP  <- namedScheduleRepo.windowsForAllProfiles
      tlsP    <- ZIO.foreach(profiles)(p => timeLimitRepo.findForProfile(p.id).map(p.id -> _))
      stlsP   <- ZIO.foreach(profiles)(p => siteTimeLimitRepo.listForProfile(p.id).map(p.id -> _))
      tail    <- trafficRepo.listPresenceRowsSince(devices.map(_.mac), date, watermark)
      exts    <- extRepo.snapshotAllByProfile(date)
    } yield {
      val schedMap = schedsP.toMap.map { case (pid, v1) =>
        pid -> (v1 ++ syntheticWindows(pid, namedP.getOrElse(pid, Nil)))
      }
      val tlMap    = tlsP.toMap
      val stlMap   = stlsP.toMap
      val devsByP  =
        devices.groupBy(_.profileId).collect { case (Some(pid), devs) => pid -> devs }
      profiles.iterator.map { p =>
        val devs        = devsByP.getOrElse(p.id, Nil)
        val pRolled     = rolled(p.id)
        // Per-profile watermark may exceed the batch min (e.g. a newer tick partially completed)
        // — filter the over-fetched tail rows back to the row's own boundary.
        val pTail       =
          tail.filter(r =>
            devs.exists(_.mac == r.mac) && !r.periodStart.isBefore(pRolled.rolledThrough),
          )
        val tailSeconds =
          TimeStatusService.usedSecondsForProfile(
            p,
            devs,
            stlMap.getOrElse(p.id, Nil),
            pTail,
            settings,
          )
        val totalUsed   = ((pRolled.usedSeconds + tailSeconds) / 60L).toInt
        p.id -> TimeStatusService.assemble(
          profile = p,
          schedules = schedMap.getOrElse(p.id, Nil),
          dailyLimit = tlMap.getOrElse(p.id, None).map(_.dailyMinutes),
          siteLimits = stlMap.getOrElse(p.id, Nil),
          usedMinutes = totalUsed,
          extensionMinutes = exts.getOrElse(p.id, 0),
          date = date,
          now = now,
        )
      }.toMap
    }
  }
}

object TimeStatusService {

  val layer: ZLayer[
    ProfileRepo & ScheduleRepo & TimeLimitRepo & SiteTimeLimitRepo & DeviceRepo &
      TrafficReportRepo & TimeExtensionRepo & TimeUsedRollupRepo & NamedScheduleRepo,
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
        nsr: NamedScheduleRepo,
    ) => new TimeStatusServiceLive(pr, sr, tlr, stlr, dr, trr, er, ru, nsr)
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
    val patterns = siteLimits.map(_.domainPattern)
    val perPat   = Presence.patternMinutesByMac(presence, patterns, settings.heartbeatFilter)
    val totalSecondsUsed = usedSecondsForProfile(profile, devices, siteLimits, presence, settings)
    val totalMinutesUsed = (totalSecondsUsed / 60L).toInt

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
   * Pure: per-profile total active seconds from a presence batch, applying the heartbeat filter and
   * the profile's `crossDeviceOverlapMode` (Sum vs Dedup). Shared by [[fold]] (live) and the
   * rollup+tail read path so the cached and live computations stay structurally identical (#1160
   * source-of-truth invariant). Returning seconds — not minutes — lets the decomposition rolled +
   * tail stay exact across the watermark boundary.
   */
  def usedSecondsForProfile(
      profile: Profile,
      devices: List[Device],
      siteLimits: List[SiteTimeLimit],
      presence: List[PresenceRow],
      settings: HouseholdSettings,
  ): Long = {
    val exemptPats = siteLimits.filter(_.exemptFromDaily).map(_.domainPattern)
    profile.crossDeviceOverlapMode match {
      case CrossDeviceOverlapMode.Sum   =>
        val perMac = Presence.totalSecondsByMac(presence, exemptPats, settings.heartbeatFilter)
        devices.iterator.map(d => perMac.getOrElse(d.mac, 0L)).sum
      case CrossDeviceOverlapMode.Dedup =>
        Presence.dedupedTotalSeconds(presence, exemptPats, settings.heartbeatFilter)
    }
  }

  /**
   * Shared assembly used by both [[fold]] (live presence aggregation) and the rollup+tail read
   * path, once `usedMinutes` is known. Computes `blocked` / `blockReason` / `remaining` so the
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
