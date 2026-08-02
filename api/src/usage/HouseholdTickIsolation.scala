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

  /** Bounded `reason` values for `rollup_household_skipped_total`. */
  val ReasonSettingsMissing: String = "settings_missing"
  val ReasonError: String           = "error"

  /**
   * Run one household's slice of an all-tenant tick. On failure, log + meter and fall back to
   * `zero` so the remaining households still run; on a pool-closed failure, re-raise so the
   * caller's `runOnce` recognises the shutdown.
   *
   * `reason` is the CAUSE attribution, chosen structurally by the call site (the settings read is
   * wrapped separately from the rest of the body) rather than sniffed out of an exception message.
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
