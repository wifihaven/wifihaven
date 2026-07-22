package wifihaven.api.billing

import wifihaven.api.FlipConfig
import wifihaven.api.db.{
  BetaCohort,
  BetaCohortRepo,
  HouseholdBilling,
  HouseholdBillingRepo,
  HouseholdRepo,
}
import wifihaven.api.metrics.AppMetrics
import wifihaven.api.notify.Notifier
import wifihaven.api.policy.PolicyService
import wifihaven.shared.Clock
import wifihaven.shared.types.HouseholdId
import zio.*

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * #2137 (multi-tenant P5-6, epic #622): the beta→paid flip lifecycle (design
 * docs/design/multi-tenant-launch.md §5.4, EVENT-TRIGGERED model).
 *
 * ONE service owns the whole cohort clock so the SPA read ([[currentWindow]]) and the scheduled job
 * ([[tick]]) compute window state from the SAME logic (single-source-of-truth). It reuses the ONE
 * #2135 billing state machine — the flip's only status transition is `beta → lapsed` via the
 * existing [[HouseholdBillingRepo.markLapsed]]; it invents no states.
 *
 * The flip is event-triggered, not a hand-set date:
 *   - The cohort clock STARTS the first tick the count of active beta households reaches
 *     `cfg.thresholdHouseholds`. The start instant is persisted (`beta_cohort.clock_started_at`)
 *     and LATCHED — a later dip in the active count never resets it. The window end is
 *     `clock_started_at + cfg.window`, never recomputed from a fresh "now".
 *   - T-30 / T-7 conversion notices fire for unconverted (`status='beta'`) households as the window
 *     closes, routed through the [[Notifier]] one-method pattern.
 *   - At window end, unconverted households flip to `lapsed` → enforcement STOPS via a permissive
 *     snapshot ([[PolicyService]] reads billing status; §5.3). Never brick the network. The
 *     beta→lapsed sweep is latched (`beta_cohort.flipped_at`) so it runs exactly once.
 *
 * `Clock` is injected so the whole lifecycle is TestClock-driven. Notice idempotency ("don't
 * double-send within a window") is an in-memory [[Ref]] of already-sent `(household, window)`
 * marks; prod runs a single API instance, so this is sufficient — a process restart mid-window
 * could re-send a notice (a fail-open nudge, not a correctness issue), and the DB `IS NULL` latches
 * make the clock-start and the flip themselves genuinely idempotent regardless.
 */
final case class FlipService(
    cohortRepo: BetaCohortRepo,
    billingRepo: HouseholdBillingRepo,
    householdRepo: HouseholdRepo,
    notifier: Notifier,
    policyService: PolicyService,
    clock: Clock,
    cfg: FlipConfig,
    // in-memory per-(household, window) notice dedup — see class doc.
    sentNotices: Ref[Set[(HouseholdId, FlipService.FlipNoticeWindow)]],
) {
  import FlipService.*

  /**
   * SPA-facing window state for `GET /api/billing` (design §5.4, A1): the Subscribe CTA +
   * conversion banner show only while the window is open (clock started, not yet flipped).
   * `flipDate` is the window end, surfaced once the clock has started.
   */
  def currentWindow: Task[FlipWindow] =
    cohortRepo.get.map(windowOf)

  private def windowOf(cohort: BetaCohort): FlipWindow =
    cohort.clockStartedAt match {
      case None            => FlipWindow(open = false, flipDate = None)
      case Some(startedAt) =>
        // Open = clock started AND not yet flipped. After the flip we still surface the date so the
        // SPA can explain a lapse, but the CTA/banner (keyed on `open`) turns off.
        FlipWindow(open = cohort.flippedAt.isEmpty, flipDate = Some(windowEndOf(startedAt)))
    }

  // The window end is the persisted clock-start plus the configured window — the ONE place this is
  // computed, so the SPA read (`windowOf`) and the tick (`runWindow`) can never drift. Never
  // recomputed from a fresh "now" (the latch, design §5.4).
  private def windowEndOf(startedAt: Instant): Instant =
    startedAt.plus(cfg.window.toMillis, ChronoUnit.MILLIS)

  /**
   * One lifecycle tick (the scheduled job calls this; tests call it directly with a walked
   * TestClock). Idempotent and safe to call on any cadence:
   *   1. If the clock hasn't started, start it once the active-beta count reaches the threshold. 2.
   *      If the clock has started and hasn't flipped, emit any due T-30/T-7 notices, then flip at
   *      window end.
   * Always re-publishes the households-by-billing-status gauge at the end.
   */
  def tick: Task[Unit] =
    for {
      now    <- clock.instant
      cohort <- cohortRepo.get
      _      <- cohort.clockStartedAt match {
        case None            => maybeStartClock(now)
        case Some(startedAt) =>
          ZIO.when(cohort.flippedAt.isEmpty)(runWindow(now, windowEndOf(startedAt)))
      }
      _      <- publishStatusGauge
    } yield ()

  // Start the clock the first tick the active-beta count reaches the threshold. `startClock` is
  // guarded on `clock_started_at IS NULL`, so a concurrent/duplicate tick can't re-stamp it.
  private def maybeStartClock(now: Instant): Task[Unit] =
    for {
      since <- ZIO.succeed(now.minus(cfg.activeLookback.toMillis, ChronoUnit.MILLIS))
      count <- cohortRepo.countActiveBetaHouseholds(since)
      _     <- ZIO.when(count >= cfg.thresholdClamped) {
        cohortRepo.startClock(now).flatMap {
          case true  =>
            ZIO.logInfo(
              s"beta flip clock started: activeBeta=$count reached threshold=${cfg.thresholdClamped}, " +
                s"window ends ${now.plus(cfg.window.toMillis, ChronoUnit.MILLIS)}",
            )
          case false => ZIO.unit // another tick started it first
        }
      }
    } yield ()

  private def runWindow(now: Instant, windowEnd: Instant): Task[Unit] =
    emitDueNotices(now, windowEnd) *> ZIO.when(!now.isBefore(windowEnd))(flip(now)).unit

  // Fire the T-30 / T-7 notice for every unconverted household the first time we observe `now`
  // inside each window. Both windows are checked each tick; the (household, window) Ref makes each
  // fire exactly once. A household that converts drops out of `betaHouseholdIds`, so it stops
  // getting notices automatically.
  private def emitDueNotices(now: Instant, windowEnd: Instant): Task[Unit] =
    ZIO.foreachDiscard(FlipNoticeWindow.values.toList) { window =>
      val opensAt = windowEnd.minus(window.daysBefore.toLong, ChronoUnit.DAYS)
      ZIO
        .when(!now.isBefore(opensAt) && now.isBefore(windowEnd)) {
          cohortRepo.betaHouseholdIds.flatMap { ids =>
            ZIO.foreachDiscard(ids)(hid => maybeNotify(hid, window, now, windowEnd))
          }
        }
        .unit
    }

  private def maybeNotify(
      hid: HouseholdId,
      window: FlipNoticeWindow,
      now: Instant,
      windowEnd: Instant,
  ): Task[Unit] =
    sentNotices
      .modify { sent =>
        val mark = (hid, window)
        if sent.contains(mark) then (false, sent) else (true, sent + mark)
      }
      .flatMap {
        case false => ZIO.unit
        case true  =>
          for {
            slug <- householdRepo
              .findById(hid)
              .map(_.flatMap(_.slug).getOrElse(s"household-${hid.value}"))
            // Round the remaining span UP to whole days so a notice fired a few hours into the window
            // still reads a friendly "~30 days" rather than 29.
            days = math.max(
              1,
              math.ceil(ChronoUnit.MINUTES.between(now, windowEnd).toDouble / (24 * 60)).toInt,
            )
            _ <- notifier.betaFlipNotice(hid, slug, window.label, windowEnd, days)
            _ <- AppMetrics.recordFlipNotice(window.label)
          } yield ()
      }

  // Flip unconverted households to lapsed at window end. The sweep runs BEFORE the `flipped_at`
  // latch so a partial failure self-heals: if `markLapsed` fails mid-loop, `flipped_at` stays NULL,
  // so the next tick re-enters `runWindow` and re-sweeps — idempotent, since `betaHouseholdIds`
  // returns only still-`beta` households and `markLapsed` on an already-lapsed row is a no-op.
  // `markFlipped` (guarded on `flipped_at IS NULL`) latches only once the sweep has succeeded, so a
  // concurrent instance re-running the (idempotent) sweep is harmless and only the winner logs.
  // Enforcement stops because PolicyService reads billing status and serves a permissive snapshot
  // for a lapsed household — `invalidate` rebuilds so the change lands immediately.
  private def flip(now: Instant): Task[Unit] =
    for {
      ids     <- cohortRepo.betaHouseholdIds
      _       <- ZIO.foreachDiscard(ids)(hid => billingRepo.markLapsed(hid, now))
      _       <- ZIO.when(ids.nonEmpty)(policyService.invalidate)
      latched <- cohortRepo.markFlipped(now)
      _       <- ZIO.when(latched)(
        ZIO.logInfo(
          s"beta flip executed at $now: ${ids.size} unconverted household(s) → lapsed " +
            "(enforcement stops permissively; router wire un-gated)",
        ),
      )
    } yield ()

  private def publishStatusGauge: Task[Unit] =
    billingRepo.countByStatus.flatMap { counts =>
      AppMetrics.setBillingStatusCounts(
        beta = counts.getOrElse("beta", 0),
        active = counts.getOrElse("active", 0),
        lapsed = counts.getOrElse("lapsed", 0),
        // #2356: publish the free_forever count too (0 when none) so the status is never invisible.
        freeForever = counts.getOrElse(HouseholdBilling.FreeForever, 0),
      )
    }
}

object FlipService {

  /** Bounded conversion-notice windows (design §5.4). `label` is the metric value. */
  enum FlipNoticeWindow(val label: String, val daysBefore: Int) {
    case T30 extends FlipNoticeWindow("t30", 30)
    case T7  extends FlipNoticeWindow("t7", 7)
  }

  /** SPA-facing flip-window state (design §5.4, A1). */
  final case class FlipWindow(open: Boolean, flipDate: Option[Instant])

  def make(
      cohortRepo: BetaCohortRepo,
      billingRepo: HouseholdBillingRepo,
      householdRepo: HouseholdRepo,
      notifier: Notifier,
      policyService: PolicyService,
      clock: Clock,
      cfg: FlipConfig,
  ): UIO[FlipService] =
    Ref
      .make(Set.empty[(HouseholdId, FlipNoticeWindow)])
      .map(
        FlipService(cohortRepo, billingRepo, householdRepo, notifier, policyService, clock, cfg, _),
      )

  // Singleton so the route reads and the scheduled job share one instance (and its notice-dedup
  // Ref). Depends on the ONE billing state machine's repos + the Notifier + PolicyService.
  val layer: ZLayer[
    BetaCohortRepo & HouseholdBillingRepo & HouseholdRepo & Notifier & PolicyService & Clock &
      wifihaven.api.AppConfig,
    Nothing,
    FlipService,
  ] =
    ZLayer.fromZIO {
      for {
        cohortRepo    <- ZIO.service[BetaCohortRepo]
        billingRepo   <- ZIO.service[HouseholdBillingRepo]
        householdRepo <- ZIO.service[HouseholdRepo]
        notifier      <- ZIO.service[Notifier]
        policyService <- ZIO.service[PolicyService]
        clock         <- ZIO.service[Clock]
        cfg           <- ZIO.service[wifihaven.api.AppConfig]
        svc           <- make(
          cohortRepo,
          billingRepo,
          householdRepo,
          notifier,
          policyService,
          clock,
          cfg.flip,
        )
      } yield svc
    }

  /**
   * Fork a daemon fiber that ticks the lifecycle hourly. Hourly (not daily) so the clock start and
   * the flip land within an hour of the threshold-reach / window-end rather than up to a day late,
   * while staying far cheaper than the reevaluate cadence. Idempotent — a tick that finds nothing
   * due is a couple of small reads.
   */
  def start(flip: FlipService): UIO[Fiber.Runtime[Nothing, Long]] =
    ZIO
      .logInfo("BetaFlipJob scheduled: first tick now, then every 1h")
      .zipRight(
        flip.tick
          .catchAllCause(c => ZIO.logErrorCause("BetaFlipJob: tick failed", c))
          .repeat(Schedule.fixed(1.hour))
          .forkDaemon,
      )
}
