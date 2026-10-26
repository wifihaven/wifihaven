package wifihaven.api.policy

import wifihaven.shared.HouseholdSettings

import java.time.LocalDate

/**
 * #2932 (design `docs/design/weekly-limits-and-app-groups.md` §6.5, D10): the household calendar
 * week a weekly view shows. `from`..`to` are its seven household-local dates; `asOf` is the last of
 * them with usage so far (`to` for a past week, today for the current one), or `None` for a week
 * that has not started.
 */
final case class CalendarWeek(from: LocalDate, to: LocalDate, asOf: Option[LocalDate])

object CalendarWeek {

  /** The week containing household-local `date`, as of household-local `today`. */
  def containing(date: LocalDate, today: LocalDate, settings: HouseholdSettings): CalendarWeek = {
    val from = PolicyService.weekStartOf(date, settings)
    val to   = from.plusDays(6)
    val asOf =
      if today.isBefore(from) then None else Some(if to.isBefore(today) then to else today)
    CalendarWeek(from, to, asOf)
  }
}
