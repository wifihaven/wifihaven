package wifihaven.api.support

import wifihaven.shared.Clock
import wifihaven.shared.types.HouseholdId
import zio.*

import java.time.{Duration, Instant}

/**
 * #2472 — dispatch→completion tracking for the #2200 support responder.
 *
 * STUBBED ON PURPOSE in this commit (docs/process/tdd.md): the shape, the wiring, and the
 * `SupportDispatchCompletionSpec` pins land first so the red→green progression is visible in the PR
 * history. The bodies arrive in the following commit.
 */
final class DispatchTracker private (pending: Ref[Map[String, DispatchTracker.Pending]]) {
  import DispatchTracker.*

  /** TODO(#2472): record a dispatch the transport ACCEPTED. */
  def dispatched(
      threadId: String,
      household: HouseholdId,
      transport: String,
      now: Instant,
  ): UIO[Unit] =
    pending.get.unit

  /** TODO(#2472): close the outstanding dispatch for `threadId` — the agent came back. */
  def calledBack(threadId: String, action: String, now: Instant): UIO[Unit] =
    pending.get.unit

  /** TODO(#2472): report the dispatches nobody closed. */
  def sweep(now: Instant): UIO[Unit] =
    pending.get.unit

  /** TODO(#2472): the background sweep fiber. */
  def loop(clock: Clock): UIO[Nothing] =
    (clock.instant.flatMap(sweep) *> ZIO.sleep(SweepInterval)).forever
}

object DispatchTracker {

  private[support] final case class Pending(
      household: HouseholdId,
      transport: String,
      dispatchedAt: Instant,
      slowReported: Boolean = false,
  )

  /** The `outcome` values this adds to the existing `support_dispatch_total` series (#2438). */
  object Outcome {
    val Completed: String    = "completed"
    val CallbackSlow: String = "callback_slow"
    val NoCallback: String   = "no_callback"
  }

  /** The agent callbacks that CLOSE a dispatch. */
  val TerminalActions: Set[String] = Set("reply", "escalate", "consent_request")

  val SlowAfter: Duration = Duration.ofMinutes(10)

  val DeadAfter: Duration = Duration.ofHours(6)

  val SweepInterval: Duration = 60.seconds

  def make: UIO[DispatchTracker] =
    Ref.make(Map.empty[String, Pending]).map(new DispatchTracker(_))

  val layer: ULayer[DispatchTracker] = ZLayer.fromZIO(make)
}
