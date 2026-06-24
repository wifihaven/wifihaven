package wifihaven.api.policy

import wifihaven.api.db.*
import wifihaven.api.presence.{Presence, PresenceRow}
import wifihaven.api.usage.{AppUsedRollupService, NoopAppUsedRollupService}
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
    perApp: List[AppDayState],
)

/**
 * Per-site (site_time_limits) usage breakdown for a profile-day. `usedMinutes` is the
 * bucket-counted active minutes against this site's domain pattern, summed across the profile's
 * devices, computed the same way regardless of whether the consumer is the snapshot (deciding which
 * sites land in extraBlocked) or a route (rendering the per-site bars in the UI).
 */
final case class AppDayState(
    label: String,
    domainPattern: String,
    // #1627: `None` means "no per-app limit configured" — distinct from a
    // 0-minute cap. The per-app cap-exhaustion test (`appCapExhaustedHosts`)
    // and the exempt carve-out gate (`exemptUnderCapHosts`) both read this
    // field as Option-aware: no limit ⇒ never exhausted, exempt-with-no-limit
    // ⇒ always carve.
    dailyLimitMinutes: Option[Int],
    usedMinutes: Int,
    exemptFromDaily: Boolean,
    // #1505: the app's full host-set for this limit. `domainPattern` stays as a single
    // representative (the apex) for the wire/UI, but enforcement and the daily-cap exemption use
    // every host so off-domain asset/CDN traffic ticks the *same* app limit and is exempted from the
    // daily total just like the apex. For a single-host app this is `List(domainPattern)`.
    hosts: List[String] = Nil,
    // #1564: the typed FK to apps(id) the cap/rollup surface keys on internally. `label` and
    // `domainPattern` stay as display text; `appId` is the canonical identity that joins to
    // `app_used_daily.app_id` directly — no slug round-trip.
    appId: AppId = AppId(0L),
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
    timeLimitRepo: TimeLimitRepo,
    appTimeLimitRepo: AppTimeLimitRepo,
    deviceRepo: DeviceRepo,
    trafficRepo: TrafficReportRepo,
    extRepo: TimeExtensionRepo,
    // Defaulting to the noop lets the many call sites in TimeApiSpec / snapshot specs keep their
    // 7-arg constructions — they exercise the all-live path either way.
    rollupRepo: TimeUsedRollupRepo = NoopTimeUsedRollupRepo,
    // #1069/#1482: a profile's downtime schedules come from the windows of every named schedule
    // attached to it as a block schedule (via `profile_schedule_rules`, mode=blocked_during).
    // Defaults to the noop so existing direct constructions keep their arity.
    namedScheduleRepo: NamedScheduleRepo = NoopNamedScheduleRepo,
    // #1515: the #1510 per-app rollup read accessor, used ONLY on the today-rollup read path to fill
    // each profile's per-app `perApp.usedMinutes` from `app_used_daily` + a live tail — so the
    // per-app cap enforces once a profile has rolled (the prod steady state), not just on the
    // all-live path. Defaults to the noop: the all-live path (cache miss / past date) never consults
    // it, and the many test constructions over `NoopTimeUsedRollupRepo` stay all-live.
    appUsedRollupService: AppUsedRollupService = NoopAppUsedRollupService,
) extends TimeStatusService {

  // #1069/#1482: a profile's effective downtime schedules = the windows of every named schedule
  // attached to it as a block schedule (via `profile_schedule_rules`, mode=blocked_during). Named
  // windows become synthetic DbSchedules so the existing `scheduleActiveAt` math applies unchanged
  // (it reads only days/start/end/tz — id/profileId/name are irrelevant).
  private def syntheticWindows(pid: ProfileId, ws: List[ScheduleWindow]): List[DbSchedule] =
    ws.map(w =>
      DbSchedule(ScheduleId(0L), pid, "named-schedule", w.days, w.startLocal, w.endLocal, w.tz),
    )

  private def schedulesFor(pid: ProfileId): Task[List[DbSchedule]] =
    namedScheduleRepo.windowsForProfile(pid).map(syntheticWindows(pid, _))

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
          atls      <- appTimeLimitRepo.listForProfile(profileId)
          devices   <- deviceRepo.listAll.map(_.filter(_.profileId.contains(profileId)))
          presence  <- trafficRepo.listPresenceRows(devices.map(_.mac), date)
          extMins   <- extRepo.getProfileTotalExtension(profileId, date)
        } yield Some(
          TimeStatusService.fold(
            profile = p,
            schedules = schedules,
            devices = devices,
            dailyLimit = tl.map(_.dailyMinutes),
            appLimits = atls,
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
      namedP   <- namedScheduleRepo.windowsForAllProfiles
      tlsP     <- ZIO.foreach(profiles)(p => timeLimitRepo.findForProfile(p.id).map(p.id -> _))
      atlsP    <- ZIO.foreach(profiles)(p => appTimeLimitRepo.listForProfile(p.id).map(p.id -> _))
      presence <- trafficRepo.listPresenceRows(devices.map(_.mac), date)
      exts     <- extRepo.snapshotAllByProfile(date)
    } yield {
      val schedMap =
        profiles.map(p => p.id -> syntheticWindows(p.id, namedP.getOrElse(p.id, Nil))).toMap
      val tlMap    = tlsP.toMap
      val atlMap   = atlsP.toMap
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
          appLimits = atlMap.getOrElse(p.id, Nil),
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
          atls      <- appTimeLimitRepo.listForProfile(profileId)
          devices   <- deviceRepo.listAll.map(_.filter(_.profileId.contains(profileId)))
          tail <- trafficRepo.listPresenceRowsSince(devices.map(_.mac), date, rolled.rolledThrough)
          extMins    <- extRepo.getProfileTotalExtension(profileId, date)
          // #1515: per-app cap usage from the #1510 per-app rollup + live tail, so the per-app cap
          // enforces on the rollup path identically to the all-live path.
          perAppMins <- appUsedRollupService.appCapMinutesByAppId(now, date, settings, profileId)
        } yield {
          val tailSeconds =
            TimeStatusService.usedSecondsForProfile(p, devices, atls, tail, settings)
          val totalUsed   = ((rolled.usedSeconds + tailSeconds) / 60L).toInt
          Some(
            TimeStatusService.assemble(
              profile = p,
              schedules = schedules,
              dailyLimit = tl.map(_.dailyMinutes),
              appLimits = atls,
              usedMinutes = totalUsed,
              extensionMinutes = extMins,
              date = date,
              now = now,
              perAppOverride = Some(TimeStatusService.appDayStatesFromMinutes(atls, perAppMins)),
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
      devices    <- deviceRepo.listAll
      namedP     <- namedScheduleRepo.windowsForAllProfiles
      tlsP       <- ZIO.foreach(profiles)(p => timeLimitRepo.findForProfile(p.id).map(p.id -> _))
      atlsP      <- ZIO.foreach(profiles)(p => appTimeLimitRepo.listForProfile(p.id).map(p.id -> _))
      tail       <- trafficRepo.listPresenceRowsSince(devices.map(_.mac), date, watermark)
      exts       <- extRepo.snapshotAllByProfile(date)
      // #1515: per-app cap usage per profile from the #1510 per-app rollup + live tail. Keyed by the
      // `app:<slug>` cap-group label so it joins to each profile's per-app cap groups below.
      perAppMins <- ZIO
        .foreach(profiles)(p =>
          appUsedRollupService.appCapMinutesByAppId(now, date, settings, p.id).map(p.id -> _),
        )
        .map(_.toMap)
    } yield {
      val schedMap =
        profiles.map(p => p.id -> syntheticWindows(p.id, namedP.getOrElse(p.id, Nil))).toMap
      val tlMap    = tlsP.toMap
      val atlMap   = atlsP.toMap
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
            atlMap.getOrElse(p.id, Nil),
            pTail,
            settings,
          )
        val totalUsed   = ((pRolled.usedSeconds + tailSeconds) / 60L).toInt
        p.id -> TimeStatusService.assemble(
          profile = p,
          schedules = schedMap.getOrElse(p.id, Nil),
          dailyLimit = tlMap.getOrElse(p.id, None).map(_.dailyMinutes),
          appLimits = atlMap.getOrElse(p.id, Nil),
          usedMinutes = totalUsed,
          extensionMinutes = exts.getOrElse(p.id, 0),
          date = date,
          now = now,
          perAppOverride = Some(
            TimeStatusService.appDayStatesFromMinutes(
              atlMap.getOrElse(p.id, Nil),
              perAppMins.getOrElse(p.id, Map.empty),
            ),
          ),
        )
      }.toMap
    }
  }
}

object TimeStatusService {

  val layer: ZLayer[
    ProfileRepo & TimeLimitRepo & AppTimeLimitRepo & DeviceRepo & TrafficReportRepo &
      TimeExtensionRepo & TimeUsedRollupRepo & NamedScheduleRepo & AppUsedRollupService,
    Nothing,
    TimeStatusService,
  ] = ZLayer.fromFunction {
    (
        pr: ProfileRepo,
        tlr: TimeLimitRepo,
        atlr: AppTimeLimitRepo,
        dr: DeviceRepo,
        trr: TrafficReportRepo,
        er: TimeExtensionRepo,
        ru: TimeUsedRollupRepo,
        nsr: NamedScheduleRepo,
        aur: AppUsedRollupService,
    ) => new TimeStatusServiceLive(pr, tlr, atlr, dr, trr, er, ru, nsr, aur)
  }

  /**
   * #1505 + #1564 + #1630: the per-app cap groups for this profile — one entry per
   * `mode=TimeLimited` assignment, in the tuple shape downstream consumers still expect: `(appId,
   * label, dailyMinutes, exemptFromDaily, hosts, representativeHost)`.
   *
   * Post-#1630 this is a thin adapter over the single fold [[ProfileAppDispositions.from]]: the
   * case-on-mode that filters this list down to TimeLimited assignments lives in
   * `ProfileAppDispositions.capGroups`. Keeping the tuple wrapper avoids churning every downstream
   * callsite while removing the duplicated per-app collapse that — in its previous standalone form
   * — drifted from `PolicyService.expandAppDispositions` (the defect behind #1630). Stable order by
   * `label`.
   */
  private[policy] def groupAppLimits(
      appLimits: List[AppTimeLimit],
  ): List[(AppId, String, Option[Int], Boolean, List[String], String)] =
    ProfileAppDispositions.from(appLimits).capGroups.map { d =>
      val rep = d.hosts.minByOption(_.length).getOrElse(d.label)
      (d.appId, d.label, d.dailyMinutes, d.exemptFromDaily, d.hosts, rep)
    }

  /**
   * #1505 + #1504: per-app [[AppDayState]] list — one entry per app, with `usedMinutes` aggregated
   * across the app's whole host-set and counted via the #1464 session-stitch primitive
   * ([[Presence.patternGroupMinutesForProfile]]), combined across the profile's devices per
   * `overlap`. `presence` must already be scoped to the profile's devices.
   */
  private[policy] def appDayStates(
      appLimits: List[AppTimeLimit],
      presence: List[PresenceRow],
      overlap: CrossDeviceOverlapMode,
      filter: HeartbeatFilter,
      continuationSeconds: Int,
  ): List[AppDayState] = {
    // #1564: feed Presence the cap-group label as its String key so the underlying span/union math
    // is unchanged, then re-key the result by appId via the (label -> appId) map groupAppLimits
    // emits directly. One canonical conversion, no slug parsing.
    // #1897: the stitch reads each app's DISTINCTIVE host-set only (`capGroupLabelDistinctiveHosts`)
    // — a shared host overlapping a distinctive span is already counted there, so excluding it is a
    // no-op that also guarantees it can never inflate or extend the app's engaged minutes.
    val groups       = groupAppLimits(appLimits)
    val labelToAppId = groups.map(g => g._2 -> g._1).toMap
    val perLabel     = Presence.patternGroupMinutesForProfile(
      presence,
      ProfileAppDispositions.from(appLimits).capGroupLabelDistinctiveHosts,
      overlap,
      filter,
      continuationSeconds,
    )
    val perAppId     = perLabel.iterator.flatMap { case (label, m) =>
      labelToAppId.get(label).map(_ -> m)
    }.toMap
    appDayStatesFromMinutes(appLimits, perAppId)
  }

  /**
   * #1515: build the per-app [[AppDayState]] list from a `label -> usedMinutes` map, where each key
   * is the `app:<slug>` cap-group label [[groupAppLimits]] emits. The single place the per-app cap
   * groups (label / host-set / limit / exempt) are zipped with their used-minutes, regardless of
   * where the minutes come from: the live presence aggregation ([[appDayStates]]), the rollup +
   * live-tail read path ([[TimeStatusServiceLive]], via
   * [[wifihaven.api.usage.AppUsedRollupService.appCapMinutesByAppId]]), or the zero-usage default
   * in [[assemble]]. Keeping one zip means the cap groups can't be shaped differently across those
   * paths (#1532). A label absent from the map contributes zero minutes.
   */
  private[policy] def appDayStatesFromMinutes(
      appLimits: List[AppTimeLimit],
      minutesByAppId: Map[AppId, Int],
  ): List[AppDayState] =
    groupAppLimits(appLimits).map { case (appId, label, daily, exempt, hosts, rep) =>
      AppDayState(
        label = label,
        domainPattern = rep,
        dailyLimitMinutes = daily,
        usedMinutes = minutesByAppId.getOrElse(appId, 0),
        exemptFromDaily = exempt,
        hosts = hosts,
        appId = appId,
      )
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
      appLimits: List[AppTimeLimit],
      presence: List[PresenceRow],
      extensionMinutes: Int,
      date: LocalDate,
      now: Instant,
      settings: HouseholdSettings,
  ): ProfileDayState = {
    val totalSecondsUsed = usedSecondsForProfile(profile, devices, appLimits, presence, settings)
    val totalMinutesUsed = (totalSecondsUsed / 60L).toInt

    // #1505 + #1504: one limit per app (label), aggregated across the app's full host-set, counted
    // with the #1464 session-stitch primitive (not bucket-max) and combined across the profile's
    // devices per its overlap mode. `appDayStates` groups the per-(assignment × host) rows into
    // one (label, host-set) per app and counts engaged wall-clock time once across the whole set.
    val perApp = appDayStates(
      appLimits,
      presence,
      profile.crossDeviceOverlapMode,
      settings.heartbeatFilter,
      settings.presenceContinuationSeconds,
    )

    assemble(
      profile = profile,
      schedules = schedules,
      dailyLimit = dailyLimit,
      appLimits = appLimits,
      usedMinutes = totalMinutesUsed,
      extensionMinutes = extensionMinutes,
      date = date,
      now = now,
      perAppOverride = Some(perApp),
    )
  }

  /**
   * Pure: per-profile total active seconds from a presence batch, applying the heartbeat filter and
   * the profile's `crossDeviceOverlapMode` (Sum vs Dedup). Shared by [[fold]] (live) and the
   * rollup+tail read path so the cached and live computations stay structurally identical (#1160
   * source-of-truth invariant). Returning seconds — not minutes — lets the decomposition rolled +
   * tail stay exact across the watermark boundary.
   */
  /**
   * #1531 + #1630: the WHOLE host-set of every `exemptFromDaily=true` assignment — the patterns
   * excluded from the daily total. Post-#1630 this is a thin accessor on the single fold
   * [[ProfileAppDispositions.from]]: the previous version filtered `groupAppLimits` for exempt
   * rows, but `groupAppLimits` only saw `mode=TimeLimited` rows (the repo filter), so a
   * `mode=Allowed, exemptFromDaily=true` assignment never reached this list and its traffic counted
   * against the daily total. The collapse fixes that by making `ProfileAppDispositions` the single
   * point of mode-case-analysis.
   */
  private[policy] def exemptPatterns(appLimits: List[AppTimeLimit]): List[String] =
    ProfileAppDispositions.from(appLimits).exemptPatterns

  /**
   * #1516 + #1564: per-app engaged seconds for a profile, keyed by `apps.id`, derived from the
   * SINGLE per-app presence primitive [[Presence.appSecondsForProfile]] (#1514/#1532) — the same
   * gap-bridged, cross-host union the per-app cap ([[appDayStates]] /
   * [[Presence.patternGroupMinutesForProfile]]) ticks on. The cap-group label ([[groupAppLimits]])
   * is used as Presence's group key; the (label -> appId) map emitted by the same call re-keys the
   * result back to the typed FK — no slug parsing, no slugToAppId lookup table. This is the value
   * the `app_used_daily` rollup persists and the per-app series reads, so the rollup ⇄ cap ⇄ series
   * identities hold by construction (there is exactly one per-app time computation). `presence`
   * must already be scoped to the profile's devices. Seconds (not minutes) so the rolled + tail
   * decomposition stays exact across the watermark boundary; floor-to-minutes happens once at read
   * time.
   */
  def appSecondsByApp(
      profile: Profile,
      appLimits: List[AppTimeLimit],
      presence: List[PresenceRow],
      settings: HouseholdSettings,
  ): Map[AppId, Long] =
    appSecondsByAppWithDropCount(profile, appLimits, presence, settings)._1

  /**
   * #1676: sibling that also returns the count of per-(mac, app) sessions silently dropped by the
   * #1666 phantom-suppression anchor-row guard inside Presence.appSpansForProfile. The pure
   * [[appSecondsByApp]] alias above projects to the map only; consumers that want the observability
   * counter call this and feed the count into [[AppMetrics.recordAppSessionsDropped]].
   */
  def appSecondsByAppWithDropCount(
      profile: Profile,
      appLimits: List[AppTimeLimit],
      presence: List[PresenceRow],
      settings: HouseholdSettings,
  ): (Map[AppId, Long], Int) = {
    // #1897: distinctive host-set only — see `appDayStates` / `capGroupLabelDistinctiveHosts`.
    val groups                 = groupAppLimits(appLimits)
    val labelToAppId           = groups.map(g => g._2 -> g._1).toMap
    val (secsByLabel, dropped) = Presence.appSecondsForProfileWithDropCount(
      presence,
      ProfileAppDispositions.from(appLimits).capGroupLabelDistinctiveHosts,
      profile.crossDeviceOverlapMode,
      settings.heartbeatFilter,
      settings.presenceContinuationSeconds,
    )
    val byAppId                = secsByLabel.iterator.flatMap { case (label, secs) =>
      labelToAppId.get(label).map(_ -> secs)
    }.toMap
    (byAppId, dropped)
  }

  /**
   * #1897 (shared-hosts S2): per-app DISTINCTIVE-host engaged spans, keyed by `apps.id` — the
   * gap-bridged union of each app's `shared = false` hosts, the exact spans the per-app cap
   * ([[appSecondsByApp]]) sums. Built on the SAME stitch primitive
   * ([[Presence.appSpansForProfile]]) over the SAME distinctive host-set
   * ([[ProfileAppDispositions.capGroupLabelDistinctiveHosts]]).
   *
   * This is the reusable seam S3's shared-host co-presence allocation consumes: a shared-host
   * presence row attributes to app A iff it overlaps one of A's distinctive spans. S3 MUST read
   * these spans rather than re-deriving the distinctive partition, so the distinctive-span
   * computation lives in exactly one place (AGENTS.md §single-source-of-truth). Spans are combined
   * across the profile's devices per `profile.crossDeviceOverlapMode`, matching the cap aggregate.
   */
  def distinctiveSpansByApp(
      profile: Profile,
      appLimits: List[AppTimeLimit],
      presence: List[PresenceRow],
      settings: HouseholdSettings,
  ): Map[AppId, List[Presence.Span]] = {
    val dispositions = ProfileAppDispositions.from(appLimits)
    val labelToAppId = dispositions.capGroups.map(d => d.label -> d.appId).toMap
    Presence
      .appSpansForProfile(
        presence,
        dispositions.capGroupLabelDistinctiveHosts,
        profile.crossDeviceOverlapMode,
        settings.heartbeatFilter,
        settings.presenceContinuationSeconds,
      )
      .iterator
      .flatMap { case (label, spans) => labelToAppId.get(label).map(_ -> spans) }
      .toMap
  }

  /**
   * #1506: the union of EVERY active app's host-set for this profile — the app-attribution set fed
   * to [[Presence.isHeartbeat]] so a host an active app genuinely depends on is never dropped as
   * background infra (attribution beats suppression). Derived from the same per-app collapse
   * (`groupAppLimits`) the per-app bars and `exemptPatterns` use, so the daily total and the
   * per-app surfaces agree on what counts as an "active app host". Includes exempt apps' hosts too:
   * exclusion from the daily total is handled separately by [[exemptPatterns]] in
   * [[Presence.countedRows]] (attribution only prevents suppression, not exemption), so an exempt
   * app's host is still excluded — it is simply no longer mis-classified as a heartbeat first.
   */
  private[policy] def appHostPatterns(appLimits: List[AppTimeLimit]): List[String] =
    ProfileAppDispositions.from(appLimits).appHostPatterns

  def usedSecondsForProfile(
      profile: Profile,
      devices: List[Device],
      appLimits: List[AppTimeLimit],
      presence: List[PresenceRow],
      settings: HouseholdSettings,
  ): Long = {
    val exemptPats = exemptPatterns(appLimits)
    val appPats    = appHostPatterns(appLimits)
    profile.crossDeviceOverlapMode match {
      case CrossDeviceOverlapMode.Sum   =>
        val perMac = Presence.totalSecondsByMac(
          presence,
          exemptPats,
          settings.heartbeatFilter,
          settings.presenceContinuationSeconds,
          appPats,
        )
        devices.iterator.map(d => perMac.getOrElse(d.mac, 0L)).sum
      case CrossDeviceOverlapMode.Dedup =>
        Presence.dedupedTotalSeconds(
          presence,
          exemptPats,
          settings.heartbeatFilter,
          settings.presenceContinuationSeconds,
          appPats,
        )
    }
  }

  /**
   * #1546: the per-mac decomposition of [[usedSecondsForProfile]]. Uses the IDENTICAL
   * exempt-pattern derivation ([[exemptPatterns]]) and the profile's `crossDeviceOverlapMode`, so
   * the per-device summaries the `/api/time/status` routes render can no longer drift from the
   * canonical headline `usedMinutes` (the #1531 class of divergence at per-device granularity). The
   * collapse invariant, in BOTH modes, is `usedSecondsByMac(...).values.sum ==
   * usedSecondsForProfile(...)`:
   *
   *   - '''Sum''': each mac is credited its own engaged seconds (its per-`(device, app)` session
   *     union). The profile total is the per-device sum, so this is the natural decomposition and
   *     the equality is exact.
   *   - '''Dedup''': the profile total is the cross-device UNION (one human on two screens counts
   *     once), which is `<=` the sum of per-device engaged seconds. A flat per-device split would
   *     therefore over-count (the prod divergence: two devices overlapping in the same window each
   *     showed their full engaged time, totalling 2x the headline). Instead each device is credited
   *     only the seconds it adds to the union that an earlier device — in `devices` order — has not
   *     already covered. This disjoint marginal attribution telescopes to exactly the union, and a
   *     device's own engaged seconds are an upper bound on its share, so no single device can
   *     exceed the headline and the summaries can never total `>100%`.
   *
   * `presence` must already be scoped to the profile's devices (as the live read paths and the
   * routes both do).
   */
  def usedSecondsByMac(
      profile: Profile,
      devices: List[Device],
      appLimits: List[AppTimeLimit],
      presence: List[PresenceRow],
      settings: HouseholdSettings,
  ): Map[MacAddress, Long] = {
    val exemptPats = exemptPatterns(appLimits)
    val appPats    = appHostPatterns(appLimits)
    profile.crossDeviceOverlapMode match {
      case CrossDeviceOverlapMode.Sum   =>
        val perMac = Presence.totalSecondsByMac(
          presence,
          exemptPats,
          settings.heartbeatFilter,
          settings.presenceContinuationSeconds,
          appPats,
        )
        devices.iterator.map(d => d.mac -> perMac.getOrElse(d.mac, 0L)).toMap
      case CrossDeviceOverlapMode.Dedup =>
        val spansByMac = Presence.deviceSessionSpans(
          presence,
          exemptPats,
          settings.heartbeatFilter,
          settings.presenceContinuationSeconds,
          appPats,
        )
        // Disjoint marginal attribution in `devices` order: each mac gets the seconds it adds to the
        // running union. Σ marginals == unionSeconds(all device spans) == dedupedTotalSeconds.
        val (out, _)   =
          devices.foldLeft((Map.empty[MacAddress, Long], List.empty[Presence.Span])) {
            case ((acc, covered), d) =>
              val macSpans = spansByMac.getOrElse(d.mac, Nil)
              val marginal =
                Presence.unionSeconds(covered ++ macSpans) - Presence.unionSeconds(covered)
              (acc.updated(d.mac, marginal), Presence.mergeSpans(covered ++ macSpans))
          }
        out
    }
  }

  /**
   * Shared assembly used by both [[fold]] (live presence aggregation) and the rollup+tail read
   * path, once `usedMinutes` is known. Computes `blocked` / `blockReason` / `remaining` so the
   * cached-read path produces the same precedence Paused > Schedule > TimeLimit as the live path —
   * the source-of-truth invariant is enforced here by construction.
   *
   * `perAppOverride = None` means the caller did not load per-site presence; per-site usage is
   * emitted as zero against the configured site limits (the v1 rollup is profile-total only).
   */
  def assemble(
      profile: Profile,
      schedules: List[DbSchedule],
      dailyLimit: Option[Int],
      appLimits: List[AppTimeLimit],
      usedMinutes: Int,
      extensionMinutes: Int,
      date: LocalDate,
      now: Instant,
      perAppOverride: Option[List[AppDayState]] = None,
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

    val perApp = perAppOverride.getOrElse(
      // #1505/#1515: same per-app grouping as the live path, but with zero usage. Reached only when
      // a caller does NOT supply per-app usage; the rollup read paths now pass `perAppOverride`
      // built from the #1510 per-app rollup (so the per-app cap enforces on the rollup path too),
      // and the live `fold` passes the presence-derived one. A bare `assemble` with no override
      // degrades to "no per-app usage yet".
      appDayStatesFromMinutes(appLimits, Map.empty),
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
      perApp = perApp,
    )
  }
}
