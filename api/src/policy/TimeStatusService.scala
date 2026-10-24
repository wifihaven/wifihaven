package wifihaven.api.policy

import wifihaven.api.db.*
import wifihaven.api.metrics.AppMetrics
import wifihaven.api.presence.{AmbientGate, Presence, PresenceRow}
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
    // #2932 (design §6.5): one entry per attached group. Counted, not enforced (#2933 enforces).
    perGroup: List[BudgetState] = Nil,
    // #2932: the exact seconds behind `usedMinutes`, so a week can be floored once (design §6.3).
    usedSeconds: Long = 0L,
    // #2932: the profile's exempt-filtered week-to-date total (the weekly views' total, §6.5).
    week: Option[WeekUsage] = None,
)

/**
 * #2932 (design §6.3-§6.5): week-to-date usage of one budget (or of the profile total) for the
 * household week containing the state's date: `start` is the household-local date the week began,
 * `resetsAt` the instant the next one begins, and `usedMinutes` is `floor(Σ day seconds / 60)` over
 * the week's days up to and including the state's date.
 */
final case class WeekUsage(start: LocalDate, resetsAt: Instant, usedMinutes: Int)

/**
 * #2932 (design §6.1, §6.5): a group budget's state on a profile-day. The group counterpart of
 * [[AppDayState]]: `usedMinutes` counts the members' distinctive hosts once (design §6.2).
 */
final case class BudgetState(
    groupId: AppGroupId,
    label: String,
    name: String,
    memberAppIds: List[AppId],
    dailyLimitMinutes: Option[Int],
    weeklyLimitMinutes: Option[Int],
    usedMinutes: Int,
    usedSeconds: Long,
    exemptFromDaily: Boolean,
    // Every member's full host-set (allow side) and the counted distinctive set (block side, #1899).
    hosts: List[String],
    distinctiveHosts: List[String],
    week: Option[WeekUsage] = None,
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
    // #1899 (shared-hosts S4): the subset of `hosts` whose `app_hosts.shared` flag is false — the
    // app's DISTINCTIVE host-set. The BLOCK side of per-app cap enforcement
    // (`PolicyService.appCapExhaustedHosts`, `timeLimitBlockFromState`) reads THIS, never `hosts`,
    // so a shared backend is never dropped when one app's cap exhausts (the #1636 collateral
    // failure). The exempt-carve ALLOW side (`exemptUnderCapHosts`) keeps reading `hosts` — allow
    // wins on shared hosts. An app with no shared hosts has `distinctiveHosts == hosts`.
    distinctiveHosts: List[String] = Nil,
    // #1564: the typed FK to apps(id) the cap/rollup surface keys on internally. `label` and
    // `domainPattern` stay as display text; `appId` is the canonical identity that joins to
    // `app_used_daily.app_id` directly — no slug round-trip.
    appId: AppId = AppId(0L),
    // #2932: the exact seconds behind `usedMinutes`, so a week can be floored once (design §6.3).
    usedSeconds: Long = 0L,
    // #2932 (design §5.2): the app's own weekly limit; `None` = no weekly limit.
    weeklyLimitMinutes: Option[Int] = None,
    // #2932 (design §6.5): week-to-date usage. `None` only on a state no week was read for.
    week: Option[WeekUsage] = None,
    // #2932: the attached group covering this app, if any (design §6.1).
    groupId: Option[AppGroupId] = None,
)

trait TimeStatusService {

  /**
   * The canonical per-profile day state for the household-local date containing `now`. Returns
   * `None` if no such profile exists. Use this on the snapshot/grant/ingestion side and for
   * `/api/time/status/...` reads against today.
   */
  // #2313: `household` scopes the `traffic_reports` presence reads — a MAC can exist in >1 household
  // post-V74, so a bare-MAC read would inflate used-minutes with another tenant's traffic.
  def todaysState(
      household: HouseholdId,
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
      household: HouseholdId,
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
      profileId: ProfileId,
  ): Task[Option[ProfileDayState]]

  /**
   * Batched form for [[PolicyService.snapshot]] and the `/api/time/status/summary` route. Emits one
   * `ProfileDayState` per profile IN `household` for `date`. For today this is served from the
   * rollup + a live tail; for past dates it's all-live.
   *
   * #2257: scoped to `household` — every caller already has one
   * (`PolicyService.snapshot(household)`, the timeStatus GET's `claims.hh`, the push's
   * per-recipient household), so this reads only that household's profiles/devices instead of every
   * tenant's. There is no all-tenant batch variant.
   */
  def dayStateAll(
      household: HouseholdId,
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
      household: HouseholdId,
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
      profileId: ProfileId,
  ): Task[Option[ProfileDayState]]

  /** Always-live batched variant of [[dayStateAll]] — see [[dayStateLive]]. */
  def dayStateAllLive(
      household: HouseholdId,
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
  ): Task[Map[ProfileId, ProfileDayState]]

  /**
   * #2849 (design `docs/design/shared-devices.md` §7.3): when the last engaged presence on `mac`
   * ended, among the rows in `[since, now)` that `holder` is credited with; `None` if there were
   * none. The shared-device idle auto-checkout reads this. "Engaged" is the screen-time definition,
   * so idle has no second definition of activity: see [[TimeStatusService.lastEngagedAt]].
   */
  def lastEngagedAt(
      household: HouseholdId,
      now: Instant,
      settings: HouseholdSettings,
      holder: ProfileId,
      mac: MacAddress,
      since: Instant,
  ): Task[Option[Instant]]

  /**
   * #2932 (design §6.4): settle every settleable, unsettled past day of `household`'s current week:
   * compute it live through the same per-day path the day states use and write it back with
   * `rolled_through` at the day's end. The rollup tick calls this, so in the steady state a week
   * read finds every past day settled. Returns the number of days written.
   */
  def settleWeek(household: HouseholdId, now: Instant, settings: HouseholdSettings): Task[Int]
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
    // #2077: the isolation-learned ambient baseline behind the engagement-anchor
    // gate. Defaults to the noop (gate Off) so existing direct constructions and
    // tests that don't exercise the gate keep their arity and semantics.
    ambientRepo: AmbientHostsRepo = NoopAmbientHostsRepo,
    // #2932: settled past days for week-to-date figures. The noop finds nothing settled, so every
    // past day is computed live: the same numbers, uncached.
    settledRepo: SettledUsageRepo = NoopSettledUsageRepo,
) extends TimeStatusService {

  // ── #2932: week-to-date (design §6.3-§6.4) ────────────────────────────────────────────────
  //
  // Every public day-state read below is a RAW one-day read (the existing rolled-plus-tail or
  // live path, unchanged) followed by [[withWeek]], which adds week-to-date figures to each budget
  // and to the profile total: the state's own day plus the week's earlier days, each day counted
  // exactly as its own day state counts it. Settleable earlier days come from settled rows; the
  // one earlier day that may not be settleable yet (yesterday, for `SettleDelay`) is read as a raw
  // day state, which reads it rolled plus tail.

  // Single-flight per (household, date) for the write-through refill (#2012's shape): concurrent
  // reads of a cold day share one live computation and one write.
  private val refills: Ref.Synchronized[
    Map[(HouseholdId, LocalDate), Promise[Throwable, Map[ProfileId, DayUsage]]],
  ] =
    Unsafe.unsafe(implicit u => Ref.Synchronized.unsafe.make(Map.empty))

  def settleWeek(household: HouseholdId, now: Instant, settings: HouseholdSettings): Task[Int] = {
    val today = PolicyService.householdLocalDate(now, settings)
    rawDayStateAll(household, now, today, settings).flatMap(states =>
      priorDays(household, now, settings, states).map(_._2),
    )
  }

  private def withWeek(
      household: HouseholdId,
      now: Instant,
      settings: HouseholdSettings,
      states: Map[ProfileId, ProfileDayState],
  ): Task[Map[ProfileId, ProfileDayState]] =
    states.headOption match {
      case None         => ZIO.succeed(states)
      case Some((_, s)) =>
        val start    = PolicyService.weekStartOf(s.date, settings)
        val resetsAt = AttributionScope.rangeWindow(start, start.plusDays(6), settings)._2
        priorDays(household, now, settings, states).timed.flatMap { case (took, (prior, _)) =>
          AppMetrics
            .recordBudgetWeekRead(took.toNanos / 1e9)
            .as(states.map { case (pid, st) =>
              pid -> TimeStatusService.withWeek(st, prior.getOrElse(pid, Nil), start, resetsAt)
            })
        }
    }

  private def withWeekOne(
      household: HouseholdId,
      now: Instant,
      settings: HouseholdSettings,
      state: Option[ProfileDayState],
  ): Task[Option[ProfileDayState]] =
    ZIO.foreach(state)(st =>
      withWeek(household, now, settings, Map(st.profileId -> st)).map(_(st.profileId)),
    )

  // The usage of each day of the week before the states' date, per profile, plus the number of
  // days refilled. `states` are the profiles' states for that date: their budgets are the keys a
  // settled day must hold.
  private def priorDays(
      household: HouseholdId,
      now: Instant,
      settings: HouseholdSettings,
      states: Map[ProfileId, ProfileDayState],
  ): Task[(Map[ProfileId, List[DayUsage]], Int)] = {
    val date  = states.head._2.date
    val start = PolicyService.weekStartOf(date, settings)
    val days  = Iterator.iterate(start)(_.plusDays(1)).takeWhile(_.isBefore(date)).toList
    val (settled, unsettled) = days.partition(SettledUsage.settleable(_, now, settings))
    val profiles             = states.keys.toList
    for {
      rolled   <-
        if settled.isEmpty then ZIO.succeed(Map.empty[(ProfileId, LocalDate), RolledProfileDay])
        else settledRepo.readRange(household, profiles, settled.min, settled.max)
      fromRows <- ZIO.foreach(settled) { d =>
        val end  = SettledUsage.dayEnd(d, settings)
        val rows = states.map { case (pid, st) =>
          pid -> TimeStatusService.settledUsage(
            rolled.getOrElse((pid, d), RolledProfileDay.empty),
            st,
            end,
          )
        }
        if rows.values.forall(_.isDefined) then
          AppMetrics.recordBudgetSettle("hit").as((rows.map((p, u) => p -> u.get), 0))
        else
          refill(household, now, d, settings)
            .tapBoth(
              _ => AppMetrics.recordBudgetSettle("error"),
              _ => AppMetrics.recordBudgetSettle("refill"),
            )
            .map(all => (profiles.map(p => p -> all.getOrElse(p, DayUsage.zero)).toMap, 1))
      }
      fromLive <- ZIO.foreach(unsettled)(d =>
        rawDayStateAll(household, now, d, settings).map(ss =>
          profiles.map(p => p -> ss.get(p).fold(DayUsage.zero)(TimeStatusService.dayUsage)).toMap,
        ),
      )
    } yield {
      val perDay = fromRows.map(_._1) ++ fromLive
      (
        profiles.map(p => p -> perDay.map(_.getOrElse(p, DayUsage.zero))).toMap,
        fromRows.map(_._2).sum,
      )
    }
  }

  // Compute household-day `date` live through the same per-day path every day state uses and write
  // it back as settled, once per (household, date) however many reads ask at the same time.
  private def refill(
      household: HouseholdId,
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
  ): Task[Map[ProfileId, DayUsage]] =
    Promise.make[Throwable, Map[ProfileId, DayUsage]].flatMap { mine =>
      refills
        .modify { inFlight =>
          inFlight.get((household, date)) match {
            case Some(p) => (Left(p), inFlight)
            case None    => (Right(mine), inFlight.updated((household, date), mine))
          }
        }
        .flatMap {
          case Left(theirs) => theirs.await
          case Right(p)     =>
            rawDayStateAllLive(household, now, date, settings)
              .map(_.map((pid, st) => pid -> TimeStatusService.dayUsage(st)))
              .tap(rows =>
                settledRepo
                  .writeSettled(household, date, rows, SettledUsage.dayEnd(date, settings)),
              )
              .exit
              .flatMap(ex => p.done(ex) *> refills.update(_ - ((household, date))) *> ZIO.done(ex))
        }
    }

  // #2077: the AmbientGate for a request — Off (no DB touch) unless the master
  // switch is on. The learned window is always evaluated as of the household-local
  // TODAY, even for historical-date reads: the ambient set is a rolling property
  // of the fleet's background behavior, not a per-day snapshot.
  private def ambientGateFor(now: Instant, settings: HouseholdSettings): Task[AmbientGate] =
    ambientRepo.gateFor(settings, PolicyService.householdLocalDate(now, settings))

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

  // #2844 (design `docs/design/shared-devices.md` §6.1): which profile held which device during
  // household-local `date`. Every presence read below goes through it, so a device's usage is
  // credited to the profile that held it at each row's `period_start`, and a profile's device list
  // is every device it held that day, including one it no longer holds.
  private def scopeForDay(
      household: HouseholdId,
      date: LocalDate,
      settings: HouseholdSettings,
  ): Task[AttributionScope] = AttributionScope.forDay(deviceRepo, household, date, settings)

  // #2313: `household` scopes the `traffic_reports` presence reads to the caller's tenant — a MAC can
  // exist in more than one household (post-V74), so without it a profile's used-minutes would be
  // inflated by another household's traffic on the same MAC. Every caller already has the household:
  // `PolicyService.decide`/snapshot pass the router/claims household, the block page passes Default.
  def todaysState(
      household: HouseholdId,
      now: Instant,
      settings: HouseholdSettings,
      profileId: ProfileId,
  ): Task[Option[ProfileDayState]] =
    dayState(household, now, PolicyService.householdLocalDate(now, settings), settings, profileId)

  def dayState(
      household: HouseholdId,
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
      profileId: ProfileId,
  ): Task[Option[ProfileDayState]] =
    rawDayState(household, now, date, settings, profileId)
      .flatMap(withWeekOne(household, now, settings, _))

  def dayStateLive(
      household: HouseholdId,
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
      profileId: ProfileId,
  ): Task[Option[ProfileDayState]] =
    rawDayStateLive(household, now, date, settings, profileId)
      .flatMap(withWeekOne(household, now, settings, _))

  def dayStateAll(
      household: HouseholdId,
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
  ): Task[Map[ProfileId, ProfileDayState]] =
    rawDayStateAll(household, now, date, settings).flatMap(withWeek(household, now, settings, _))

  def dayStateAllLive(
      household: HouseholdId,
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
  ): Task[Map[ProfileId, ProfileDayState]] =
    rawDayStateAllLive(household, now, date, settings)
      .flatMap(withWeek(household, now, settings, _))

  // ── raw one-day reads ─────────────────────────────────────────────────────────────────────

  // #2932: a day that is not yet settleable (today, and yesterday for `SettleDelay`) is read from
  // its rolled row plus a live tail; a settleable past day is read live. The same predicate gates
  // the per-app/group rollup read in `AppUsedRollupService`, so both halves of a day state agree.
  private def rawDayState(
      household: HouseholdId,
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
      profileId: ProfileId,
  ): Task[Option[ProfileDayState]] =
    if (!SettledUsage.settleable(date, now, settings))
      rollupRepo.getDayForProfile(profileId, date).flatMap {
        case Some(rolled) =>
          dayStateFromRollupAndTail(household, now, date, settings, profileId, rolled)
        case None         => rawDayStateLive(household, now, date, settings, profileId)
      }
    else rawDayStateLive(household, now, date, settings, profileId)

  private def rawDayStateLive(
      household: HouseholdId,
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
          scope     <- scopeForDay(household, date, settings)
          allDevs   <- deviceRepo.listAllForHousehold(household)
          devices = scope.devicesFor(profileId, allDevs)
          presence <- trafficRepo.listPresenceRows(scope.spansFor(profileId), date)
          ambient  <- ambientGateFor(now, settings)
          extMins  <- extRepo.getProfileTotalExtension(profileId, date)
        } yield Some(
          TimeStatusService.fold(
            profile = p,
            schedules = schedules,
            devices = devices,
            dailyLimit = tl.map(_.dailyMinutes),
            appLimits = atls,
            presence = TimeStatusService.gatedPresence(atls, presence, settings, ambient),
            extensionMinutes = extMins,
            date = date,
            now = now,
            settings = settings,
          ),
        )
    }

  private def rawDayStateAll(
      household: HouseholdId,
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
  ): Task[Map[ProfileId, ProfileDayState]] =
    if (!SettledUsage.settleable(date, now, settings))
      dayStateAllFromRollup(household, now, date, settings)
    else rawDayStateAllLive(household, now, date, settings)

  private def rawDayStateAllLive(
      household: HouseholdId,
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
  ): Task[Map[ProfileId, ProfileDayState]] =
    for {
      profiles <- profileRepo.listAllForHousehold(household)
      devices  <- deviceRepo.listAllForHousehold(household)
      namedP   <- namedScheduleRepo.windowsForHouseholdProfiles(household)
      tlsP     <- ZIO.foreach(profiles)(p => timeLimitRepo.findForProfile(p.id).map(p.id -> _))
      atlsP    <- ZIO.foreach(profiles)(p => appTimeLimitRepo.listForProfile(p.id).map(p.id -> _))
      scope    <- scopeForDay(household, date, settings)
      presence <- trafficRepo.listPresenceRows(scope.allProfiles, date)
      ambient  <- ambientGateFor(now, settings)
      exts     <- extRepo.snapshotByProfileForHousehold(household, date)
    } yield {
      val schedMap =
        profiles.map(p => p.id -> syntheticWindows(p.id, namedP.getOrElse(p.id, Nil))).toMap
      val tlMap    = tlsP.toMap
      val atlMap   = atlsP.toMap
      profiles.iterator.map { p =>
        val devs    = scope.devicesFor(p.id, devices)
        val atls    = atlMap.getOrElse(p.id, Nil)
        val pPres   = TimeStatusService.gatedPresence(
          atls,
          scope.spansFor(p.id).filter(presence),
          settings,
          ambient,
        )
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

  // #2849: the presence read is the holder's attribution scope for today, narrowed to the one
  // device, over `[since, now)`: the window `listPresenceRowsInWindow` prunes partitions on.
  def lastEngagedAt(
      household: HouseholdId,
      now: Instant,
      settings: HouseholdSettings,
      holder: ProfileId,
      mac: MacAddress,
      since: Instant,
  ): Task[Option[Instant]] =
    for {
      atls    <- appTimeLimitRepo.listForProfile(holder)
      scope   <- scopeForDay(household, PolicyService.householdLocalDate(now, settings), settings)
      rows    <- trafficRepo.listPresenceRowsInWindow(
        scope.spansFor(holder).restrictTo(Set(mac)),
        since,
        now,
      )
      ambient <- ambientGateFor(now, settings)
    } yield TimeStatusService.lastEngagedAt(atls, rows, settings, ambient)

  // Today single-profile read: rolled seconds + live aggregation of buckets the rollup hasn't
  // absorbed yet (period_start >= rolledThrough). Truncation to minutes happens once at the end so
  // the result is byte-identical to a full live aggregation over the whole day.
  private def dayStateFromRollupAndTail(
      household: HouseholdId,
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
          scope     <- scopeForDay(household, date, settings)
          allDevs   <- deviceRepo.listAllForHousehold(household)
          devices = scope.devicesFor(profileId, allDevs)
          tail       <- trafficRepo
            .listPresenceRowsSince(scope.spansFor(profileId), date, rolled.rolledThrough)
          ambient    <- ambientGateFor(now, settings)
          extMins    <- extRepo.getProfileTotalExtension(profileId, date)
          // #1515: per-app cap usage from the #1510 per-app rollup + live tail, so the per-app cap
          // enforces on the rollup path identically to the all-live path. #2932: group budgets too.
          budgetSecs <- appUsedRollupService
            .budgetSeconds(household, now, date, settings, profileId)
        } yield {
          // #2077: the rolled part was gated at rollup-write time; gate the live tail the same
          // way. Gating only the tail slice can transiently drop an ambient-only tail of a real
          // session whose anchor row is already rolled — bounded (≤ one rollup interval,
          // self-heals when the next tick re-gates the whole day) and it only ever REMOVES
          // minutes, mirroring the #1666 per-slice anchor behavior.
          val gatedTail   = TimeStatusService.gatedPresence(atls, tail, settings, ambient)
          val tailSeconds =
            TimeStatusService.usedSecondsForProfile(p, devices, atls, gatedTail, settings)
          Some(
            TimeStatusService.assemble(
              profile = p,
              schedules = schedules,
              dailyLimit = tl.map(_.dailyMinutes),
              appLimits = atls,
              usedSeconds = rolled.usedSeconds + tailSeconds,
              extensionMinutes = extMins,
              date = date,
              now = now,
              budgetSeconds = budgetSecs,
            ),
          )
        }
    }

  // Batched today read. On any cache miss (some profile lacks a rollup row) we fall through to the
  // all-live path for the entire batch — cheaper than mixing two code paths and the next fiber tick
  // refills the missing rows. After the fiber settles, every batch is rollup+tail.
  private def dayStateAllFromRollup(
      household: HouseholdId,
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
  ): Task[Map[ProfileId, ProfileDayState]] =
    for {
      profiles <- profileRepo.listAllForHousehold(household)
      rolled   <- rollupRepo.getDayMapForHousehold(household, date)
      result   <-
        // #2257: a household with no profiles has no day-states — short-circuit. This also guards
        // `dayStateAllFromRollupHits`' `rolled.values.min` from an empty-map throw now that the
        // profile set is household-scoped (a freshly-provisioned household legitimately has none).
        if profiles.isEmpty then ZIO.succeed(Map.empty[ProfileId, ProfileDayState])
        else if profiles.exists(p => !rolled.contains(p.id)) then
          rawDayStateAllLive(household, now, date, settings)
        else dayStateAllFromRollupHits(household, now, date, settings, profiles, rolled)
    } yield result

  private def dayStateAllFromRollupHits(
      household: HouseholdId,
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
      profiles: List[Profile],
      rolled: Map[ProfileId, RolledDay],
  ): Task[Map[ProfileId, ProfileDayState]] = {
    // All rolled_through watermarks come from the same fiber tick in steady state, but a new
    // profile + fresh tick can land just before the read — so use the earliest watermark and let
    // per-profile filtering handle any per-row over-fetch.
    //
    // #2264: `rolled` is now household-scoped, so this minimum ranges over THIS household's
    // watermarks instead of every tenant's. In a multi-household install that makes the tail window
    // narrower whenever another household's rollup fiber lagged. The results are unchanged: the
    // window is only ever used as a lower bound for the `listPresenceRowsSince` fetch below, and
    // every row is re-filtered to its own profile's `rolledThrough` at the fold (see the per-profile
    // filter further down), so a wider window was pure over-fetch, never extra minutes. Pinned by
    // `DayStateAllScopeSpec`'s lagging-household case.
    val watermark = rolled.values.iterator.map(_.rolledThrough).min
    for {
      devices    <- deviceRepo.listAllForHousehold(household)
      namedP     <- namedScheduleRepo.windowsForHouseholdProfiles(household)
      tlsP       <- ZIO.foreach(profiles)(p => timeLimitRepo.findForProfile(p.id).map(p.id -> _))
      atlsP      <- ZIO.foreach(profiles)(p => appTimeLimitRepo.listForProfile(p.id).map(p.id -> _))
      scope      <- scopeForDay(household, date, settings)
      tail       <- trafficRepo.listPresenceRowsSince(scope.allProfiles, date, watermark)
      ambient    <- ambientGateFor(now, settings)
      exts       <- extRepo.snapshotByProfileForHousehold(household, date)
      // #1515: per-app cap usage per profile from the #1510 per-app rollup + live tail. #2932: and
      // each attached group's, keyed by budget so it joins to each profile's budgets below.
      budgetSecs <- ZIO
        .foreach(profiles)(p =>
          appUsedRollupService
            .budgetSeconds(household, now, date, settings, p.id)
            .map(p.id -> _),
        )
        .map(_.toMap)
    } yield {
      val schedMap =
        profiles.map(p => p.id -> syntheticWindows(p.id, namedP.getOrElse(p.id, Nil))).toMap
      val tlMap    = tlsP.toMap
      val atlMap   = atlsP.toMap
      profiles.iterator.map { p =>
        val devs        = scope.devicesFor(p.id, devices)
        val pRolled     = rolled(p.id)
        // Per-profile watermark may exceed the batch min (e.g. a newer tick partially completed)
        // — filter the over-fetched tail rows back to the row's own boundary.
        val pTail       = TimeStatusService.gatedPresence(
          atlMap.getOrElse(p.id, Nil),
          scope
            .spansFor(p.id)
            .filter(tail)
            .filter(r => !r.periodStart.isBefore(pRolled.rolledThrough)),
          settings,
          ambient,
        )
        val tailSeconds =
          TimeStatusService.usedSecondsForProfile(
            p,
            devs,
            atlMap.getOrElse(p.id, Nil),
            pTail,
            settings,
          )
        p.id -> TimeStatusService.assemble(
          profile = p,
          schedules = schedMap.getOrElse(p.id, Nil),
          dailyLimit = tlMap.getOrElse(p.id, None).map(_.dailyMinutes),
          appLimits = atlMap.getOrElse(p.id, Nil),
          usedSeconds = pRolled.usedSeconds + tailSeconds,
          extensionMinutes = exts.getOrElse(p.id, 0),
          date = date,
          now = now,
          budgetSeconds = budgetSecs.getOrElse(p.id, Map.empty),
        )
      }.toMap
    }
  }
}

object TimeStatusService {

  val layer: ZLayer[
    ProfileRepo & TimeLimitRepo & AppTimeLimitRepo & DeviceRepo & TrafficReportRepo &
      TimeExtensionRepo & TimeUsedRollupRepo & NamedScheduleRepo & AppUsedRollupService &
      AmbientHostsRepo & SettledUsageRepo,
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
        ahr: AmbientHostsRepo,
        sur: SettledUsageRepo,
    ) => new TimeStatusServiceLive(pr, tlr, atlr, dr, trr, er, ru, nsr, aur, ahr, sur)
  }

  /**
   * #2932 (design §6.1, §6.5): THE zip of a profile's budgets with their used seconds, for every
   * day-state path (live, rolled + tail, zero usage), so the per-app and per-group states cannot be
   * shaped differently across them (#1532). One [[AppDayState]] per
   * [[ProfileAppDispositions.capGroups]] entry (every TimeLimited assignment, and every covered app
   * with no assignment) and one [[BudgetState]] per attached group. A key absent from `seconds`
   * counts zero.
   */
  private[policy] def budgetDayStates(
      appLimits: List[AppTimeLimit],
      seconds: Map[BudgetKey, Long],
  ): (List[AppDayState], List[BudgetState]) = {
    val d      = ProfileAppDispositions.from(appLimits)
    val apps   = d.capGroups.map { a =>
      val secs = seconds.getOrElse(BudgetKey.App(a.appId), 0L)
      AppDayState(
        label = a.label,
        domainPattern = a.hosts.minByOption(_.length).getOrElse(a.label),
        dailyLimitMinutes = a.dailyMinutes,
        usedMinutes = (secs / 60L).toInt,
        exemptFromDaily = a.exemptFromDaily,
        hosts = a.hosts,
        // #1899: the BLOCK side of the per-app cap (`PolicyService.appCapExhaustedHosts`) omits
        // shared hosts from extraBlocked.
        distinctiveHosts = a.distinctiveHosts,
        appId = a.appId,
        usedSeconds = secs,
        weeklyLimitMinutes = a.weeklyMinutes,
        groupId = a.coverage.map(_.groupId),
      )
    }
    val groups = d.groupBudgets.collect {
      case b @ CapBudget(BudgetKey.Group(gid), _, _, _, _, _, _, _) =>
        val secs = seconds.getOrElse(b.key, 0L)
        BudgetState(
          groupId = gid,
          label = b.label,
          name = b.name,
          memberAppIds = b.members.map(_.appId),
          dailyLimitMinutes = b.dailyLimit,
          weeklyLimitMinutes = b.weeklyLimit,
          usedMinutes = (secs / 60L).toInt,
          usedSeconds = secs,
          exemptFromDaily = b.exemptFromDaily,
          hosts = b.members.flatMap(_.hosts).distinct,
          distinctiveHosts = b.countHosts,
        )
    }
    (apps, groups)
  }

  /** #2932: one profile-day's usage in seconds, as a state carries it (the settle pass's row). */
  def dayUsage(st: ProfileDayState): DayUsage =
    DayUsage(
      st.usedSeconds,
      st.perApp.map(a => a.appId -> a.usedSeconds).toMap,
      st.perGroup.map(g => g.groupId -> g.usedSeconds).toMap,
    )

  /**
   * #2932 (design §6.4): a settled day's usage for the budgets `current` has, or `None` when the
   * day is not settled for them: the total and every budget must have a row whose `rolled_through`
   * reaches the day's end. A budget with no row means the row set predates it, so the day is
   * recomputed rather than read as zero.
   */
  def settledUsage(
      rolled: RolledProfileDay,
      current: ProfileDayState,
      dayEnd: Instant,
  ): Option[DayUsage] = {
    def settledRow[K](rows: Map[K, RolledAppDay], k: K): Option[(K, Long)] =
      rows.get(k).filter(r => !r.rolledThrough.isBefore(dayEnd)).map(k -> _.engagedSeconds)
    val apps   = current.perApp.flatMap(a => settledRow(rolled.apps, a.appId))
    val groups = current.perGroup.flatMap(g => settledRow(rolled.groups, g.groupId))
    rolled.total
      .filter(r => !r.rolledThrough.isBefore(dayEnd))
      .filter(_ => apps.size == current.perApp.size && groups.size == current.perGroup.size)
      .map(t => DayUsage(t.usedSeconds, apps.toMap, groups.toMap))
  }

  /**
   * #2932 (design §6.3): `st` with its week-to-date figures: each budget's and the total's seconds
   * over the week's earlier days (`prior`) plus the state's own day, floored to minutes once.
   */
  def withWeek(
      st: ProfileDayState,
      prior: List[DayUsage],
      start: LocalDate,
      resetsAt: Instant,
  ): ProfileDayState = {
    def week(secs: Long) = Some(WeekUsage(start, resetsAt, (secs / 60L).toInt))
    st.copy(
      perApp = st.perApp.map(a =>
        a.copy(week = week(a.usedSeconds + prior.iterator.map(_.apps.getOrElse(a.appId, 0L)).sum)),
      ),
      perGroup = st.perGroup.map(g =>
        g.copy(week =
          week(g.usedSeconds + prior.iterator.map(_.groups.getOrElse(g.groupId, 0L)).sum),
        ),
      ),
      week = week(st.usedSeconds + prior.iterator.map(_.totalSeconds).sum),
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
    // #1505 + #1504: one limit per app (label), aggregated across the app's full host-set, counted
    // with the #1464 session-stitch primitive (not bucket-max) and combined across the profile's
    // devices per its overlap mode. #2932: each attached group is counted the same way over the
    // union of its members' distinctive hosts, in the same call (design §6.2).
    assemble(
      profile = profile,
      schedules = schedules,
      dailyLimit = dailyLimit,
      appLimits = appLimits,
      usedSeconds = usedSecondsForProfile(profile, devices, appLimits, presence, settings),
      extensionMinutes = extensionMinutes,
      date = date,
      now = now,
      budgetSeconds = budgetSeconds(profile, appLimits, presence, settings),
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
   * gap-bridged, cross-host union the per-app cap ([[budgetDayStates]] /
   * [[Presence.patternGroupMinutesForProfile]]) ticks on. The cap-group label
   * ([[ProfileAppDispositions.capGroups]]) is used as Presence's group key; the (label -> appId)
   * map emitted by the same call re-keys the result back to the typed FK — no slug parsing, no
   * slugToAppId lookup table. This is the value the `app_used_daily` rollup persists and the
   * per-app series reads, so the rollup ⇄ cap ⇄ series identities hold by construction (there is
   * exactly one per-app time computation). `presence` must already be scoped to the profile's
   * devices. Seconds (not minutes) so the rolled + tail decomposition stays exact across the
   * watermark boundary; floor-to-minutes happens once at read time.
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
    val (secs, dropped) = budgetSecondsWithDropCount(profile, appLimits, presence, settings)
    (secs.collect { case (BudgetKey.App(id), s) => id -> s }, dropped)
  }

  /**
   * #2932 (design §6.2): engaged seconds per budget: every [[ProfileAppDispositions.capGroups]] app
   * and every attached group, from ONE [[Presence.appSecondsForProfile]] call over
   * [[ProfileAppDispositions.budgetLabelHosts]]. A group entry's hosts are all in its members' app
   * entries, so the call's attribution set, and with it every app's seconds, is what it would be
   * without the group (finding 8). The per-app seconds are the ones the #1516 rollup persists.
   */
  def budgetSeconds(
      profile: Profile,
      appLimits: List[AppTimeLimit],
      presence: List[PresenceRow],
      settings: HouseholdSettings,
  ): Map[BudgetKey, Long] =
    budgetSecondsWithDropCount(profile, appLimits, presence, settings)._1

  /**
   * [[budgetSeconds]] plus the #1666 dropped-session count of its APP keys only. A group
   * re-stitches hosts its members' entries already stitched, so counting its drops too would count
   * one dropped session twice in `presence_app_sessions_dropped_total`, which is per (mac, app).
   */
  def budgetSecondsWithDropCount(
      profile: Profile,
      appLimits: List[AppTimeLimit],
      presence: List[PresenceRow],
      settings: HouseholdSettings,
  ): (Map[BudgetKey, Long], Int) = {
    // #1897: distinctive host-set only — see `capGroupLabelDistinctiveHosts`. The label→key map and
    // the host-sets come off ONE `ProfileAppDispositions.from`.
    val dispositions           = ProfileAppDispositions.from(appLimits)
    val keyOf                  = dispositions.labelToBudgetKey
    val (secsByLabel, dropped) = Presence.appSecondsForProfileWithDropCounts(
      presence,
      dispositions.budgetLabelHosts,
      profile.crossDeviceOverlapMode,
      settings.heartbeatFilter,
      settings.presenceContinuationSeconds,
    )
    val byKey                  = secsByLabel.iterator.flatMap { case (label, secs) =>
      keyOf.get(label).map(_ -> secs)
    }.toMap
    val appDrops               = dropped.iterator.collect {
      case (label, n) if keyOf.get(label).exists(_.isInstanceOf[BudgetKey.App]) => n
    }.sum
    (byKey, appDrops)
  }

  /**
   * #1897 (shared-hosts S2) / #1898 (S3): per-app DISTINCTIVE-host engaged spans, keyed by
   * `apps.id` — the gap-bridged union of each app's `shared = false` hosts. Built on the SAME
   * stitch primitive ([[Presence.appSpansForProfile]]) over the SAME distinctive host-set the
   * per-app cap reads, so for a TimeLimited app these are exactly the spans [[appSecondsByApp]]
   * sums. Unlike the cap, this is mode-agnostic
   * ([[ProfileAppDispositions.appLabelDistinctiveHosts]], not the TimeLimited-only
   * `capGroupLabelDistinctiveHosts`): an Allowed-mode app earns the co-presence attribution of its
   * shared backends too — attribution is structural, not enforcement.
   *
   * This is the reusable seam S3's shared-host co-presence allocation consumes: a shared-host
   * presence row attributes to app A iff it overlaps one of A's distinctive spans. S3 MUST read
   * these spans rather than re-deriving the distinctive partition, so the distinctive-span
   * computation lives in exactly one place (AGENTS.md §single-source-of-truth). Spans are combined
   * across the profile's devices per `profile.crossDeviceOverlapMode`.
   */
  def distinctiveSpansByApp(
      profile: Profile,
      appLimits: List[AppTimeLimit],
      presence: List[PresenceRow],
      settings: HouseholdSettings,
  ): Map[AppId, List[Presence.Span]] = {
    // #1898: mode-agnostic — `appLabelDistinctiveHosts` / `appLabelToAppId` cover EVERY assigned app,
    // not just the TimeLimited cap subset, so an Allowed-mode app's distinctive session still gates
    // the co-presence allocation of its shared backends. The span math is the SAME stitch primitive
    // ([[Presence.appSpansForProfile]]) over the SAME distinctive host-set the cap reads.
    val dispositions = ProfileAppDispositions.from(appLimits)
    val labelToAppId = dispositions.appLabelToAppId
    Presence
      .appSpansForProfile(
        presence,
        dispositions.appLabelDistinctiveHosts,
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
   * to [[Presence.isHeartbeat]] so a host an active app genuinely depends on is not dropped as
   * background infra (attribution beats suppression). Since #2815 that rescue is conditional on
   * SPECIFICITY: it holds when the app's pattern is at least as specific as the background entry
   * that also matched, so an app naming its infra dependency exactly still wins, while a brand apex
   * no longer rescues a background lane [[InfraHosts]] enumerates by exact host. Derived from the
   * same per-app collapse (`ProfileAppDispositions`) the per-app bars and `exemptPatterns` use, so
   * the daily total and the per-app surfaces agree on what counts as an "active app host". Includes
   * exempt apps' hosts too: exclusion from the daily total is handled separately by
   * [[exemptPatterns]] in [[Presence.countedRows]] (attribution only prevents suppression, not
   * exemption), so an exempt app's host is still excluded — it is simply no longer mis-classified
   * as a heartbeat first.
   */
  // #2077: widened from private[policy] — the ambient learner (AmbientLearnJob) derives its
  // app-attribution context through this same seam so learning and gating cannot diverge.
  def appHostPatterns(appLimits: List[AppTimeLimit]): List[String] =
    ProfileAppDispositions.from(appLimits).appHostPatterns

  /**
   * #2077: apply the engagement-anchor gate ([[Presence.ambientGatedRowsWithDropCount]]) to a
   * profile-scoped presence batch, deriving the app-attribution patterns from the profile's
   * assignments via the same [[appHostPatterns]] seam every counting surface uses. This is the ONE
   * assembly point between "rows loaded from traffic_reports" and "rows fed to counting" — every
   * production load site (the Live day-state paths, the rollup jobs, the time-status routes, the
   * usage-series builders, the SPA ws push) gates through here, so the gate cannot be applied with
   * two different attribution derivations (§single-source-of-truth). Gate `Off` is the identity.
   *
   * The Int is the count of dropped (unanchored) spans — the over-suppression watchdog the rollup
   * tick emits as `presence_ambient_spans_dropped_total` (mirroring the #1676 pattern).
   */
  def gatedPresenceWithDropCount(
      appLimits: List[AppTimeLimit],
      presence: List[PresenceRow],
      settings: HouseholdSettings,
      ambient: AmbientGate,
  ): (List[PresenceRow], Int) =
    Presence.ambientGatedRowsWithDropCount(
      presence,
      ambient,
      settings.heartbeatFilter,
      settings.presenceContinuationSeconds,
      appHostPatterns(appLimits),
    )

  /**
   * #2849: the end of the latest engaged row in `presence`, or `None`. Engaged means the rows
   * screen time counts: past the ambient gate ([[gatedPresence]], with the holder's app limits) and
   * not a heartbeat ([[Presence.isHeartbeat]], the same predicate every counting path drops first),
   * so background OS traffic does not keep a shared device's check-in alive (design §15 Q3a).
   * Exempt-from-daily rows still count: exemption is about the budget, not engagement. A row's end
   * is its activity envelope's end ([[Presence.spanOf]]).
   */
  def lastEngagedAt(
      appLimits: List[AppTimeLimit],
      presence: List[PresenceRow],
      settings: HouseholdSettings,
      ambient: AmbientGate,
  ): Option[Instant] = {
    val appPats = appHostPatterns(appLimits)
    gatedPresence(appLimits, presence, settings, ambient).iterator
      .filterNot(r => Presence.isHeartbeat(r, settings.heartbeatFilter, appPats))
      .map(r => Presence.spanOf(r).endEpoch)
      .maxOption
      .map(Instant.ofEpochSecond)
  }

  /** Rows-only projection of [[gatedPresenceWithDropCount]] for read paths without a metric. */
  def gatedPresence(
      appLimits: List[AppTimeLimit],
      presence: List[PresenceRow],
      settings: HouseholdSettings,
      ambient: AmbientGate,
  ): List[PresenceRow] =
    gatedPresenceWithDropCount(appLimits, presence, settings, ambient)._1

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
  /**
   * #1974 (SPA-ws S6a): the SINGLE place that assembles the `/api/time/status` per-profile wire
   * shape ([[ProfileTimeStatus]]) from a canonical [[ProfileDayState]] plus the day's presence
   * rows. Both the REST `GET /api/time/status` builder (`TimeRoutes.buildProfileTimeStatus`) and
   * the live `timeStatus` ws push ([[wifihaven.api.routes.SpaPush]]) call this, so the streamed
   * body is byte-identical to what the GET returns (AGENTS.md §single-source-of-truth). The
   * cap/used/ remaining/extension numbers come verbatim off `state` — this NEVER recomputes
   * minutes; only the presence-derived per-device and top-N host views (which the snapshot doesn't
   * carry) are folded in here, exactly as before. `presence` must already be scoped to the
   * profile's devices.
   */
  def assembleProfileTimeStatus(
      profile: Profile,
      devices: List[Device],
      state: ProfileDayState,
      presence: List[PresenceRow],
      appLimits: List[AppTimeLimit],
      settings: HouseholdSettings,
  ): ProfileTimeStatus = {
    // #1546: per-device minutes come from the canonical per-mac decomposition of the headline total,
    // so they share one exempt-pattern + overlap definition with `state.usedMinutes` and cannot drift
    // from it.
    val perMacSeconds   = usedSecondsByMac(profile, devices, appLimits, presence, settings)
    // #2932: a covered app with no assignment of its own is shown only through its group (D6).
    val ownApps         = ProfileAppDispositions.from(appLimits).perApp.filterNot(_.coveredOnly)
    val ownAppIds       = ownApps.map(_.appId).toSet
    val appUsage        = state.perApp.filter(a => ownAppIds.contains(a.appId)).map { s =>
      AppUsage(
        s.label,
        s.domainPattern,
        s.dailyLimitMinutes,
        s.usedMinutes,
        s.dailyLimitMinutes.map(lim => (lim - s.usedMinutes).max(0)),
        weeklyLimitMins = s.weeklyLimitMinutes,
        weekUsedMins = s.week.map(_.usedMinutes),
        weekStart = s.week.map(_.start.toString),
        weekResetsAt = s.week.map(_.resetsAt.toString),
      )
    }
    val groupUsage      = state.perGroup.map { g =>
      GroupUsage(
        groupId = g.groupId,
        name = g.name,
        memberAppIds = g.memberAppIds,
        limitMins = g.dailyLimitMinutes,
        usedMins = g.usedMinutes,
        remainingMins = g.dailyLimitMinutes.map(lim => (lim - g.usedMinutes).max(0)),
        weeklyLimitMins = g.weeklyLimitMinutes,
        weekUsedMins = g.week.map(_.usedMinutes),
        weekStart = g.week.map(_.start.toString),
        weekResetsAt = g.week.map(_.resetsAt.toString),
        exemptFromDaily = g.exemptFromDaily,
      )
    }
    val deviceSummaries = devices.map { d =>
      DeviceUsageSummary(d.mac, d.name, (perMacSeconds.getOrElse(d.mac, 0L) / 60L).toInt)
    }
    // #262 — top-N host attribution across all profile devices for the day. `usedMins` is bucket-
    // presence and `proportionalMins` is the #715 byte-share-weighted attribution; UI defaults to the
    // latter. Hosts with zero presence are dropped so the top-10 isn't padded by buckets the host
    // merely touched.
    val hostUsage       = {
      val presenceMins = Presence.hostMinutes(presence, settings.heartbeatFilter)
      val proportional = Presence.proportionalHostMinutes(
        presence,
        profile.crossDeviceOverlapMode,
        settings.heartbeatFilter,
        settings.presenceContinuationSeconds,
      )
      presenceMins.iterator
        .filter(_._2 > 0)
        .map { case (h, m) => HostUsage(h, m, proportional.getOrElse(h, 0)) }
        .toList
        .sortBy(hu => (-hu.proportionalMins, -hu.usedMins, hu.host.value))
        .take(10)
    }
    ProfileTimeStatus(
      profile.id,
      profile.name,
      state.date.toString,
      state.dailyLimitMinutes,
      state.usedMinutes,
      state.extensionMinutes,
      state.remainingMinutes,
      appUsage,
      deviceSummaries,
      hostUsage,
      groupUsage,
    )
  }

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
   * path, once the used seconds are known. Computes `blocked` / `blockReason` / `remaining` so the
   * cached-read path produces the same precedence Paused > Schedule > TimeLimit as the live path —
   * the source-of-truth invariant is enforced here by construction. `budgetSeconds` is the per-app
   * / per-group seconds from either path ([[budgetSeconds]] live, or
   * `AppUsedRollupService.budgetSeconds` rolled + tail); a budget absent from it counts zero.
   */
  def assemble(
      profile: Profile,
      schedules: List[DbSchedule],
      dailyLimit: Option[Int],
      appLimits: List[AppTimeLimit],
      usedSeconds: Long,
      extensionMinutes: Int,
      date: LocalDate,
      now: Instant,
      budgetSeconds: Map[BudgetKey, Long],
  ): ProfileDayState = {
    val usedMinutes       = (usedSeconds / 60L).toInt
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

    // #1505/#1515/#2932: the per-app and per-group states, from whichever path counted the seconds.
    val (perApp, perGroup) = budgetDayStates(appLimits, budgetSeconds)

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
      perGroup = perGroup,
      usedSeconds = usedSeconds,
    )
  }

}
