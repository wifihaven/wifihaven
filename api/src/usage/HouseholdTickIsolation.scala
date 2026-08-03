package wifihaven.api.usage

import wifihaven.api.metrics.AppMetrics
import wifihaven.shared.types.HouseholdId
import zio.*

/**
 * #2553: per-household failure isolation for the all-tenant rollup batches.
 *
 * Both [[TimeUsedRollupJob]] and [[AmbientLearnJob]] now read each household's OWN settings inside
 * their per-household loop. `HouseholdSettingsRepo.getForHousehold` FAILS LOUD for a household that
 * owns no settings row (#2386) — correctly, since a rowless household is a provisioning bug — but
 * inside an all-tenant loop an unhandled failure would abort the ENTIRE tick, so ONE unprovisioned
 * tenant would silently stop screen-time accounting for every other tenant.
 *
 * The resolution against AGENTS.md §no-dark-by-default (rule 5, "split by cause"):
 *
 *   - The provisioning-time gate is the PRIMARY defence, and it already exists — the rule's
 *     preferred "fail at provisioning/startup" form. Every household's settings row is seeded
 *     atomically with the household ([[wifihaven.api.db.HouseholdSeed.insertHousehold]]) and
 *     backfilled for pre-existing households at boot
 *     ([[wifihaven.api.db.HouseholdSeed.backfillMissingSettings]]). So this path is not the place
 *     the misconfiguration is supposed to be caught.
 *   - What is left at tick time is therefore failure ISOLATION across independent tenants, not a
 *     decision about whether to degrade. The skipped household is NOT silently dropped: it is
 *     logged at ERROR with its cause and metered with an attributable, bounded `{rollup_job,
 *     reason}` outcome, which is exactly what rule 5 demands of a skip. A dashboard panel and a
 *     non-zero counter make "this tenant has stopped rolling up" visible without user reports.
 *   - Failing the whole tick would ALSO be a form of data loss (every tenant goes stale), and it
 *     would be recorded only as one opaque `rollup_runs` error with no attribution to the household
 *     that caused it. Skipping loudly strictly dominates.
 *
 * Pool-closed failures are deliberately NOT isolated: they are the benign teardown artifact
 * [[RollupShutdown]] exists to recognise, and swallowing them per household would turn a shutdown
 * into a "successful" tick that rolled up nothing.
 */
object HouseholdTickIsolation {

  /**
   * Bounded `reason` values for `wifihaven_rollup_household_skipped_total`.
   *
   * These name WHERE the tick gave up, not why. `settings_read` is the household's
   * `getForHousehold` — overwhelmingly a missing settings row (#2386, a provisioning bug), but a
   * connection reset or statement timeout during that one read lands here too. Naming it
   * `settings_missing` would have asserted a cause the label cannot actually distinguish and would
   * send an operator hunting a provisioning bug during a database blip; the accompanying ERROR log
   * carries the real exception.
   */
  val ReasonSettingsRead: String = "settings_read"
  val ReasonError: String        = "error"

  /**
   * Run one household's slice of an all-tenant tick. On failure, log + meter and fall back to
   * `zero` so the remaining households still run; on a pool-closed failure, re-raise so the
   * caller's `runOnce` recognises the shutdown.
   *
   * `reason` is chosen structurally by the call site (the settings read is wrapped separately from
   * the rest of the body) rather than sniffed out of an exception message — see the caveat on the
   * constants above about what that does and does not tell you.
   *
   * Scope: this isolates typed failures (`catchAll`) only. A DEFECT — `ZIO.die`, e.g. an unexpected
   * `NoSuchElementException` — still aborts the whole tick for every tenant. That is deliberate: a
   * defect means a bug in our own logic rather than one tenant's bad state, and it should surface
   * as a failed run rather than be quietly absorbed per household.
   */
  def isolate[A](job: String, household: HouseholdId, reason: String, zero: A)(
      body: Task[A],
  ): Task[A] =
    body.catchAll { e =>
      if (RollupShutdown.isPoolClosed(e)) ZIO.fail(e)
      else
        ZIO.logErrorCause(
          s"$job tick skipped household ${household.value} (reason=$reason)",
          Cause.fail(e),
        ) *> AppMetrics.recordRollupHouseholdSkipped(job, reason).as(zero)
    }
}
