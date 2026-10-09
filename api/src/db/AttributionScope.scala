package wifihaven.api.db

import wifihaven.api.presence.PresenceRow
import wifihaven.shared.{Device, HouseholdSettings}
import wifihaven.shared.types.{DeviceId, HouseholdId, MacAddress, ProfileId}
import zio.Task

import java.time.{Instant, LocalDate}

/**
 * #2844 (design `docs/design/shared-devices.md` §6.1): one `device_profile_assignments` interval of
 * one device.
 *
 * `from` / `until` are the interval's own bounds, half-open `[from, until)`. `None` means unbounded
 * on that side: `from = None` is the V90 backfill's open-ended start (`started_at IS NULL`), and
 * `until = None` is an interval that is still open. They are NOT clipped to the window the scope
 * was read over, so a span is correct for any presence read, whatever window that read uses: the
 * read's own window bounds its rows, the span only says which profile each row belongs to.
 */
final case class AttributionSpan(
    mac: MacAddress,
    deviceId: DeviceId,
    from: Option[Instant],
    until: Option[Instant],
) {

  /** The point-in-time match every reader uses (design §5.1): NULL start or end is unbounded. */
  def covers(periodStart: Instant): Boolean =
    from.forall(f => !periodStart.isBefore(f)) && until.forall(u => periodStart.isBefore(u))

  /** True for the V90 backfill shape (never reassigned): the span covers every instant. */
  def unbounded: Boolean = from.isEmpty && until.isEmpty
}

/**
 * #2844: which profile each device's usage belongs to — one profile maps to the spans during which
 * it held each device. Only `DeviceRepo.attributionScope` builds one (the constructor is
 * `private[db]`), from ONE household-bounded read of every interval overlapping a window.
 *
 * A presence row belongs to the profile whose span covers its `period_start` (design §6.1). Every
 * per-profile usage read goes through [[spansFor]] / [[allProfiles]]: the presence reads on
 * `TrafficReportRepo` accept a [[PresenceSpans]], which only this type can build, so per-profile
 * presence cannot be read from a bare MAC list (the "devices on the profile NOW" read that
 * re-attributed a moved device's past usage). Same move as `MacScope` in #2708: make the wrong
 * input unrepresentable instead of auditing every caller.
 *
 * The window the scope was read over decides only which intervals it CONTAINS: a presence read over
 * a wider window than its scope's finds no span for rows outside it and credits them to no profile.
 * Build the scope with [[AttributionScope.forDay]] / [[AttributionScope.forRange]] from the same
 * date(s) the presence read uses.
 */
final case class AttributionScope private[db] (
    household: HouseholdId,
    byProfile: Map[ProfileId, List[AttributionSpan]],
) {

  /** The spans `profile` held in the window; empty when it held no device. */
  def spansFor(profile: ProfileId): PresenceSpans =
    PresenceSpans(household, byProfile.getOrElse(profile, Nil))

  /**
   * Every profile's spans together, for a batched read that loads the household's presence once and
   * slices it per profile with [[spansFor]]. Spans of one device never overlap (one open row per
   * device, `uq_dpa_device_open`), so a row matches at most one profile.
   */
  def allProfiles: PresenceSpans = spansForProfiles(byProfile.keys)

  /** [[allProfiles]] restricted to `profiles` (e.g. the ones the caller may see). */
  def spansForProfiles(profiles: Iterable[ProfileId]): PresenceSpans =
    PresenceSpans(household, profiles.iterator.flatMap(byProfile.getOrElse(_, Nil)).toList)

  /**
   * The devices a profile's usage folds over (Sum mode, per-device summaries, `usedSecondsByMac`):
   * every device with a span in the window, including one the profile no longer holds. Returned in
   * `devices` order — the caller's `listAllForHousehold` order, `ORDER BY name` — which is the
   * order the Dedup marginal attribution in `TimeStatusService.usedSecondsByMac` credits devices
   * in, the same order the removed `DeviceRepo.listForProfile` gave.
   */
  def devicesFor(profile: ProfileId, devices: List[Device]): List[Device] = {
    val ids = byProfile.getOrElse(profile, Nil).iterator.map(_.deviceId).toSet
    devices.filter(d => ids.contains(d.id))
  }

  /**
   * #2875: the profile that held `mac` at `ts` — the profile whose span covers it, by the same
   * [[AttributionSpan.covers]] the per-profile filter applies (labels the traffic-usage rows). For
   * a row aggregated in SQL, `ts` is its bucket's start while the filter tested each raw row, so a
   * bucket straddling a reassignment is labelled by whoever held the device at its start (design
   * §6.3). `None` when no span in the scope covers `ts` (the device was on no profile then, or `ts`
   * is outside the window the scope was read over).
   */
  def profileAt(mac: MacAddress, ts: Instant): Option[ProfileId] =
    spansByMac.getOrElse(mac, Nil).collectFirst { case (p, s) if s.covers(ts) => p }

  private lazy val spansByMac: Map[MacAddress, List[(ProfileId, AttributionSpan)]] =
    byProfile.toList.flatMap((p, spans) => spans.map(s => s.mac -> (p, s))).groupMap(_._1)(_._2)
}

object AttributionScope {

  /** A scope with no spans: what a read that named no profile resolves against. */
  def empty(household: HouseholdId): AttributionScope = AttributionScope(household, Map.empty)

  /**
   * The instant window `[start, end)` of household-local `date`: from `dailyResetTime` on `date` to
   * `dailyResetTime` on the next day, in `dailyResetTz`. The inverse of
   * `PolicyService.householdLocalDate`, which is how ingest derives `traffic_reports.date`
   * (`RouterIngestService`), so the scope covers the same day the date-keyed presence read does.
   */
  def dayWindow(date: LocalDate, settings: HouseholdSettings): (Instant, Instant) =
    rangeWindow(date, date, settings)

  /** [[dayWindow]] for the inclusive day range `from`..`to`. */
  def rangeWindow(from: LocalDate, to: LocalDate, settings: HouseholdSettings): (Instant, Instant) =
    (
      from.atTime(settings.dailyResetTime).atZone(settings.dailyResetTz).toInstant,
      to.plusDays(1).atTime(settings.dailyResetTime).atZone(settings.dailyResetTz).toInstant,
    )

  /** The scope for household-local `date`, matching a date-keyed presence read of that day. */
  def forDay(
      deviceRepo: DeviceRepo,
      household: HouseholdId,
      date: LocalDate,
      settings: HouseholdSettings,
  ): Task[AttributionScope] = forRange(deviceRepo, household, date, date, settings)

  /** The scope for the inclusive household-local day range `from`..`to`. */
  def forRange(
      deviceRepo: DeviceRepo,
      household: HouseholdId,
      from: LocalDate,
      to: LocalDate,
      settings: HouseholdSettings,
  ): Task[AttributionScope] = {
    val (start, end) = rangeWindow(from, to, settings)
    deviceRepo.attributionScope(household, start, end)
  }
}

/**
 * #2844: the device intervals a presence read is restricted to. The only argument
 * `TrafficReportRepo`'s per-profile presence reads accept; only an [[AttributionScope]] builds one,
 * so per-profile presence always comes through a scope.
 *
 * A device's own presence regardless of which profile held it (the per-device time-status views,
 * the heartbeat explainer) is a different question and uses the `listDevicePresenceRows*` reads,
 * which take MACs.
 */
final case class PresenceSpans private (household: HouseholdId, spans: List[AttributionSpan]) {

  def isEmpty: Boolean = spans.isEmpty

  /** True when every span is unbounded, so a MAC match alone selects the rows. */
  def unbounded: Boolean = spans.forall(_.unbounded)

  /**
   * These spans with every bound at or beyond `[windowStart, windowEnd)` dropped: for a read whose
   * rows all lie in that window such a bound excludes nothing (see `SqlFragments.spanFilter`).
   */
  private[db] def within(windowStart: Instant, windowEnd: Instant): PresenceSpans =
    PresenceSpans(
      household,
      spans.map(s =>
        s.copy(
          from = s.from.filter(_.isAfter(windowStart)),
          until = s.until.filter(_.isBefore(windowEnd)),
        ),
      ),
    )

  /** These spans restricted to `macs` (a `?mac=` filter intersected with a profile filter). */
  def restrictTo(macs: Set[MacAddress]): PresenceSpans =
    PresenceSpans(household, spans.filter(s => macs.contains(s.mac)))

  /** The distinct MACs the spans cover. */
  def macs: List[MacAddress] = spans.map(_.mac).distinct

  /** Whether one of `mac`'s spans covers `ts` (a row's `period_start`, or a bucket's start). */
  def covers(mac: MacAddress, ts: Instant): Boolean =
    spans.exists(s => s.mac == mac && s.covers(ts))

  /**
   * Whether `row` falls in one of the spans: its MAC matches and a span covers its `period_start`.
   */
  def contains(row: PresenceRow): Boolean = covers(row.mac, row.periodStart)

  /** The rows of `rows` this set of spans attributes (a slice of a batched read). */
  def filter(rows: List[PresenceRow]): List[PresenceRow] = {
    val byMac = spans.groupBy(_.mac)
    rows.filter(r => byMac.get(r.mac).exists(_.exists(_.covers(r.periodStart))))
  }
}

object PresenceSpans {
  private[db] def apply(household: HouseholdId, spans: List[AttributionSpan]): PresenceSpans =
    new PresenceSpans(household, spans)
}
