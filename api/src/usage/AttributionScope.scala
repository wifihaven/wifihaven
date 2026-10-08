package wifihaven.api.usage

import wifihaven.api.presence.PresenceRow
import wifihaven.shared.{Device, HouseholdSettings}
import wifihaven.shared.types.{DeviceId, HouseholdId, MacAddress, ProfileId}

import java.time.{Instant, LocalDate}

/**
 * #2844 (design `docs/design/shared-devices.md` §6.1): one `device_profile_assignments` interval of
 * one device, as seen by a read over some window.
 *
 * `from` / `until` are the interval's own bounds, half-open `[from, until)`. `None` means unbounded
 * on that side: `from = None` is the V90 backfill's open-ended start (`started_at IS NULL`), and
 * `until = None` is an interval that is still open. They are deliberately NOT clipped to the read's
 * window: the presence read already bounds its rows by the window (`date` / instant range), so a
 * clip would only add a second, slightly different window, and could drop rows whose stored `date`
 * was derived under an earlier household timezone. Clipping would also have to invent a finite
 * stand-in for an unbounded side, which is the `-infinity`-through-JDBC problem V90 avoided.
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
}

/**
 * #2844: which profile each device's usage belongs to over a window — one profile maps to the spans
 * during which it held each device. Built by ONE household-bounded read
 * (`DeviceRepo.attributionScope`), which returns every interval that overlaps the window.
 *
 * A presence row belongs to the profile whose span covers its `period_start` (design §6.1). Every
 * per-profile usage read goes through [[spansFor]] / [[allProfiles]]: the presence reads on
 * `TrafficReportRepo` accept a [[PresenceSpans]], which only this type can build, so a caller
 * cannot read per-profile presence from a bare MAC list (the "devices on the profile NOW" read that
 * re-attributed a moved device's past usage). Same move as [[MacScope]] in #2708: make the wrong
 * input unrepresentable instead of auditing every caller.
 */
final case class AttributionScope(
    household: HouseholdId,
    windowStart: Instant,
    windowEnd: Instant,
    byProfile: Map[ProfileId, List[AttributionSpan]],
) {

  // A bound at or beyond the window's edge excludes nothing the read's own window does not already
  // exclude, so it is dropped. In the steady state (no assignment change inside the window) every
  // span is then unbounded and the presence read is exactly the pre-#2844 MAC-list query.
  private def effective(s: AttributionSpan): AttributionSpan =
    s.copy(
      from = s.from.filter(_.isAfter(windowStart)),
      until = s.until.filter(_.isBefore(windowEnd)),
    )

  /** The spans `profile` held in the window; empty when it held no device. */
  def spansFor(profile: ProfileId): PresenceSpans =
    PresenceSpans(household, byProfile.getOrElse(profile, Nil).map(effective))

  /**
   * Every profile's spans together, for a batched read that loads the household's presence once and
   * slices it per profile with [[spansFor]]. Spans of one device never overlap (one open row per
   * device, `uq_dpa_device_open`), so a row matches at most one profile.
   */
  def allProfiles: PresenceSpans = spansForProfiles(byProfile.keys)

  /** [[allProfiles]] restricted to `profiles` (e.g. the ones the caller may see). */
  def spansForProfiles(profiles: Iterable[ProfileId]): PresenceSpans =
    PresenceSpans(
      household,
      profiles.iterator.flatMap(byProfile.getOrElse(_, Nil)).map(effective).toList,
    )

  /**
   * The devices a profile's usage folds over (Sum mode, per-device summaries, `usedSecondsByMac`):
   * every device with a span in the window, including one the profile no longer holds. Returned in
   * `devices` order — the caller's `listAllForHousehold` order, `ORDER BY name` — which is the
   * order the Dedup marginal attribution in `TimeStatusService.usedSecondsByMac` credits devices
   * in, the same order `listForProfile` used to give.
   */
  def devicesFor(profile: ProfileId, devices: List[Device]): List[Device] = {
    val ids = byProfile.getOrElse(profile, Nil).iterator.map(_.deviceId).toSet
    devices.filter(d => ids.contains(d.id))
  }
}

object AttributionScope {

  /** A scope with no spans: what a read that named no profile resolves against. */
  def empty(household: HouseholdId, windowStart: Instant, windowEnd: Instant): AttributionScope =
    AttributionScope(household, windowStart, windowEnd, Map.empty)

  /**
   * The instant window `[start, end)` of household-local `date`: from `dailyResetTime` on `date` to
   * `dailyResetTime` on the next day, in `dailyResetTz`. The inverse of
   * `PolicyService.householdLocalDate`, which is how ingest derives `traffic_reports.date`
   * (`RouterIngestService`), so the scope read covers the same day the presence read does.
   */
  def dayWindow(date: LocalDate, settings: HouseholdSettings): (Instant, Instant) =
    rangeWindow(date, date, settings)

  /** [[dayWindow]] for the inclusive day range `from`..`to`. */
  def rangeWindow(from: LocalDate, to: LocalDate, settings: HouseholdSettings): (Instant, Instant) =
    (
      from.atTime(settings.dailyResetTime).atZone(settings.dailyResetTz).toInstant,
      to.plusDays(1).atTime(settings.dailyResetTime).atZone(settings.dailyResetTz).toInstant,
    )
}

/**
 * #2844: the device intervals a presence read is restricted to. The only argument
 * `TrafficReportRepo`'s per-profile presence reads accept, and only an [[AttributionScope]] can
 * build one, so per-profile presence always comes through a scope.
 *
 * A device's own presence regardless of which profile held it (the per-device time-status views,
 * the heartbeat explainer) is a different question and uses the `listDevicePresenceRows*` reads,
 * which take MACs.
 */
final case class PresenceSpans private (household: HouseholdId, spans: List[AttributionSpan]) {

  def isEmpty: Boolean = spans.isEmpty

  /** These spans restricted to `macs` (a `?mac=` filter intersected with a profile filter). */
  def restrictTo(macs: Set[MacAddress]): PresenceSpans =
    PresenceSpans(household, spans.filter(s => macs.contains(s.mac)))

  /** The distinct MACs the spans cover. */
  def macs: List[MacAddress] = spans.map(_.mac).distinct

  /** True when no span has a bound inside the window, so a MAC match alone selects the rows. */
  def unbounded: Boolean = spans.forall(s => s.from.isEmpty && s.until.isEmpty)

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
  private[usage] def apply(household: HouseholdId, spans: List[AttributionSpan]): PresenceSpans =
    new PresenceSpans(household, spans)
}
