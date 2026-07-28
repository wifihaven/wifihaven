package wifihaven.api.support

import wifihaven.api.metrics.AppMetrics
import wifihaven.shared.Clock
import wifihaven.shared.types.HouseholdId
import zio.*

import java.time.{Duration, Instant}

/**
 * #2472 — dispatch→completion tracking for the #2200 support responder.
 *
 * WHY IT EXISTS. Dispatch was fire-and-forget. #2444 instruments the dispatch CALL and #2416 makes
 * a 4xx on it fail loud — both are about the HANDOFF. Neither observes whether the cloud agent ever
 * came back. Observed live on 2026-07-26 during #2335 validation: a dispatch fired cleanly (token
 * minted, `support agent dispatched transport=claude-code-cloud`, webhook
 * `outcome=email_registered_dispatched`) and then NOTHING — no `/api/support/agent/…` request ever
 * arrived, then or hours later. The customer emailed support and got no reply, while
 * `support_dispatch_total{outcome="dispatched"}` counted it a success. A routine that 202s and then
 * crashes, times out, exhausts quota, or produces no tool call was INDISTINGUISHABLE from one that
 * answered perfectly (docs/process/no-dark-by-default.md: a dead session must not read as a served
 * customer).
 *
 * WHAT IT DOES. A dispatch is recorded when it fires ([[dispatched]]) and closed when a
 * token-authenticated TERMINAL agent action arrives for the same thread ([[calledBack]] — driven
 * from `SupportResponder.withClaimsE`, the ONE choke point every agent callback passes through).
 * The correlation key needs no new plumbing: the #2241 agent token already carries the `threadId`.
 * A periodic [[sweep]] then reports the dispatches nobody ever closed.
 *
 * WHAT IT DOES **NOT** DO — no retry, deliberately. Re-firing a dispatch costs a second billed
 * session and risks a DUPLICATE reply to the customer: a session that was merely slow (see
 * [[SlowAfter]]) would answer after the retry had already answered, and the #2403 loop guard only
 * suppresses re-DISPATCH on our own outbound events — it cannot un-send a second reply. Visibility
 * first is the safe increment; an operator who sees `no_callback` can re-ask the customer or reply
 * by hand. Auto-recovery is a separate decision with its own cost/duplicate-risk analysis.
 *
 * STATE IS IN-MEMORY, on purpose. The alternative is a table, and a migration must ship as its own
 * schema-only PR (docs/process/migrations.md), which would gate the visibility fix behind a second
 * deploy. The cost is bounded and stated: a process restart drops the in-flight set, so dispatches
 * outstanding across a restart are neither completed nor reported. That is a KNOWN gap, not a
 * silent one — a restart is itself a loud, timestamped event, and the sweep resumes immediately for
 * everything dispatched after it.
 *
 * MEMORY IS BOUNDED BY CONSTRUCTION, not by an eviction policy: dispatch is globally capped at
 * 50/day (`HttpRoutes` `dispatchGlobalLimiter`) and every entry is removed at [[DeadAfter]] (6h) at
 * the latest, so the live map cannot exceed the day's dispatch budget — tens of entries, each a
 * threadId + three small fields.
 *
 * PII FIREWALL (the #2438 discipline): the only things logged are the Plain threadId, the household
 * id, the bounded transport label, and an age in seconds. Never the customer message, the reply,
 * the household name, or a sender address. Metric labels stay the already-allowed bounded
 * `{outcome, transport}` pair — never a thread id, household id, or email
 * (docs/process/instrumentation.md §4).
 */
final class DispatchTracker private (pending: Ref[Map[String, DispatchTracker.Pending]]) {
  import DispatchTracker.*

  /**
   * Record a dispatch that the transport ACCEPTED (`DispatchOutcome.Dispatched`). A second dispatch
   * on the same thread REPLACES the first: the thread is the unit the agent answers, and the newer
   * session is the one now owing a reply — an older outstanding entry for the same thread would
   * otherwise be reported as dead the moment the newer one answered. The replacement is logged so
   * the substitution is never silent.
   */
  def dispatched(
      threadId: String,
      household: HouseholdId,
      transport: String,
      now: Instant,
  ): UIO[Unit] =
    pending
      .modify { m => (m.get(threadId), m.updated(threadId, Pending(household, transport, now))) }
      .flatMap {
        case None       => ZIO.unit
        case Some(prev) =>
          ZIO.logInfo(
            s"support dispatch superseded thread=$threadId household=${household.value} " +
              s"transport=$transport priorAgeSeconds=${ageSeconds(prev, now)} — the earlier " +
              "session no longer owes a reply on this thread",
          )
      }

  /**
   * Close the outstanding dispatch for `threadId`, if any: the agent came back. Called for TERMINAL
   * actions only ([[TerminalActions]]) — a household READ or an issue filing proves the session is
   * alive but produces nothing the customer sees, so it must not mark the turn served.
   *
   * This measures "did the session come back", NOT "did the reply land": it fires at token-verify
   * time, before the Plain write. A reply the agent posted and Plain then refused is already loud
   * on `support_agent_action_total{op="reply",outcome="error"}` — duplicating that judgement here
   * would be a second place computing the same thing (docs/process/single-source-of-truth.md).
   *
   * An UNTRACKED thread is a no-op, not a warning: a second callback on the same thread (reply
   * after escalate is the instructed #2437 sequence), a dispatch outstanding across a restart, or a
   * callback arriving after the [[DeadAfter]] entry was already reported all land here
   * legitimately.
   */
  def calledBack(threadId: String, action: String, now: Instant): UIO[Unit] =
    pending.modify(m => (m.get(threadId), m - threadId)).flatMap {
      case None    => ZIO.unit
      case Some(p) =>
        AppMetrics.supportDispatch(Outcome.Completed, Some(p.transport)) *>
          ZIO.logInfo(
            s"support dispatch completed action=$action thread=$threadId " +
              s"household=${p.household.value} transport=${p.transport} " +
              s"afterSeconds=${ageSeconds(p, now)}",
          )
    }

  /**
   * Report the dispatches nobody closed, in two tiers (see [[SlowAfter]] / [[DeadAfter]] for why
   * one threshold cannot serve both):
   *
   *   - past [[SlowAfter]]: a WARN + `support_dispatch_total{outcome="callback_slow"}`, ONCE per
   *     dispatch (the entry stays — a suspended run legitimately resumes and answers);
   *   - past [[DeadAfter]]: an attributable ERROR + `{outcome="no_callback"}`, and the entry is
   *     dropped so it is reported exactly once.
   */
  def sweep(now: Instant): UIO[Unit] =
    pending.get.flatMap { m =>
      val dead = m.filter { case (_, p) => !ageBelow(p, now, DeadAfter) }
      val slow = m.filter { case (t, p) =>
        !dead.contains(t) && !p.slowReported && !ageBelow(p, now, SlowAfter)
      }
      ZIO.foreachDiscard(dead) { case (threadId, p) =>
        AppMetrics.supportDispatch(Outcome.NoCallback, Some(p.transport)) *>
          ZIO.logError(
            s"support dispatch NEVER CALLED BACK thread=$threadId household=${p.household.value} " +
              s"transport=${p.transport} afterSeconds=${ageSeconds(p, now)} — the cloud session " +
              "accepted the trigger and no /api/support/agent/{reply,escalate,request-consent} " +
              "call ever arrived, so THIS CUSTOMER GOT NO ANSWER. Read the thread in Plain and " +
              "reply by hand; nothing retries automatically (#2472)",
          )
      } *>
        ZIO.foreachDiscard(slow) { case (threadId, p) =>
          AppMetrics.supportDispatch(Outcome.CallbackSlow, Some(p.transport)) *>
            ZIO.logWarning(
              s"support dispatch still unanswered thread=$threadId household=${p.household.value} " +
                s"transport=${p.transport} afterSeconds=${ageSeconds(p, now)} — healthy replies " +
                s"land in 30-110s. A ${CloudAgentObservability.ClaudeCodeCloud} run suspended on " +
                "subscription usage limits resumes and answers later (#2473), so this is not yet " +
                "an error; a sustained rate is",
            )
        } *>
        pending.update { m0 =>
          (m0 -- dead.keys).map { case (t, p) =>
            t -> (if slow.contains(t) then p.copy(slowReported = true) else p)
          }
        }
    }

  /**
   * The background sweep fiber. One tick per [[SweepInterval]]; the interval only bounds how late a
   * report is, so it is deliberately much shorter than either threshold and the tick itself is
   * pure-memory work over at most tens of entries.
   */
  def loop(clock: Clock): UIO[Nothing] =
    (clock.instant.flatMap(sweep) *> ZIO.sleep(SweepInterval)).forever
}

object DispatchTracker {

  /** One outstanding dispatch. `slowReported` makes the [[SlowAfter]] tier fire exactly once. */
  private[support] final case class Pending(
      household: HouseholdId,
      transport: String,
      dispatchedAt: Instant,
      slowReported: Boolean = false,
  )

  /**
   * The `outcome` values this adds to the existing `support_dispatch_total` series (#2438). It
   * stays on that series rather than getting its own: these ARE dispatch outcomes, and putting the
   * accepted/completed/unanswered counts on one series is what lets an operator read them against
   * each other (`dispatched - completed` is the in-flight+lost population). No new label KEY —
   * `{outcome, transport}` are both already allowed, both bounded.
   */
  object Outcome {

    /** A terminal agent callback arrived for a tracked dispatch. */
    val Completed: String = "completed"

    /** Past [[SlowAfter]] with no terminal callback — may still answer. */
    val CallbackSlow: String = "callback_slow"

    /** Past [[DeadAfter]] with no terminal callback — the customer got nothing. */
    val NoCallback: String = "no_callback"
  }

  /**
   * The agent callbacks that CLOSE a dispatch: the ones that put something in front of the customer
   * or in front of a human. `reply` answers them; `escalate` (#2437) hands the thread to the
   * operator; `consent_request` (#2419) makes the server post a permission prompt into the thread.
   *
   * Deliberately EXCLUDES `household_read` and `issue`: both prove the session is alive, neither
   * produces anything the customer sees — a session that read the household and then died is
   * exactly the failure this tracker exists to catch. The strings are the `action` labels
   * `SupportResponder`'s callbacks already pass to `withClaimsE`, so they cannot drift from the
   * `support_agent_action_total{op}` vocabulary.
   */
  val TerminalActions: Set[String] = Set("reply", "escalate", "consent_request")

  /**
   * WARN tier. Observed HEALTHY replies during #2335/#2472 validation landed in 30–110s, so 10
   * minutes is ~5.5× the slowest healthy round trip — comfortably past normal variance while still
   * being minutes, not hours, after the customer sent their message.
   *
   * This tier is a WARN and not an ERROR because a legitimate, documented pause reaches it: a
   * `claude-code-cloud` routine run can be SUSPENDED on subscription usage limits and resumed later
   * — the very observation that forced the agent-token TTL to 24h (#2473, `AgentTokenTtl`, where a
   * resumed run posted its reply 2.5h after mint). An ERROR at 10 minutes would therefore fire on a
   * transport behaviour we know to be recoverable, and an ERROR that is routinely wrong stops being
   * read.
   */
  val SlowAfter: Duration = Duration.ofMinutes(10)

  /**
   * ERROR tier. Sized against the SAME #2473 evidence that sets the token TTL: a resumed run was
   * observed answering 2.5h after mint, and `AgentTokenTtl` records that "anything under ~6h
   * reproduces" the expiry failure — i.e. 6h is the repo's already-established upper bound on a
   * legitimate suspend-and-resume round trip. Past it, no known transport behaviour explains the
   * silence, so the dispatch is dead and the customer has been waiting hours: ERROR.
   *
   * It also sits well inside the 24h token TTL, so a session that DOES come back after being
   * reported can still post its reply — being reported dead never makes the answer un-deliverable.
   */
  val DeadAfter: Duration = Duration.ofHours(6)

  /** How often [[loop]] sweeps. */
  val SweepInterval: Duration = 60.seconds

  private def ageSeconds(p: Pending, now: Instant): Long =
    Duration.between(p.dispatchedAt, now).getSeconds

  /**
   * `now` is strictly inside `limit` of the dispatch — clock-skew-safe (a negative age is inside).
   */
  private def ageBelow(p: Pending, now: Instant, limit: Duration): Boolean =
    Duration.between(p.dispatchedAt, now).compareTo(limit) < 0

  def make: UIO[DispatchTracker] =
    Ref.make(Map.empty[String, Pending]).map(new DispatchTracker(_))

  val layer: ULayer[DispatchTracker] = ZLayer.fromZIO(make)
}
