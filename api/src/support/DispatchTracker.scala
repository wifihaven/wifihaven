package wifihaven.api.support

import wifihaven.api.SupportConfig
import wifihaven.api.metrics.AppMetrics
import wifihaven.api.observability.AgentTokenRejection
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
 * 50/day (`HttpRoutes` `dispatchGlobalLimiter`) and [[sweep]] removes every entry by
 * [[DispatchTracker.deadAfterFor]] at the latest, so the live map holds at most one dispatch budget
 * per retention window. At the default 24h TTL that is 50 entries; the bound scales linearly with
 * the operator-tunable `support.agentTokenTtlMinutes` rather than being fixed (a 7-day TTL would
 * make it ~350). Each entry is a threadId plus five small fields, so even a wildly-tuned TTL costs
 * kilobytes.
 *
 * #2668 changed WHEN an entry leaves, not the bound: [[calledBack]] marks it closed instead of
 * removing it (so [[turnOwner]] can still recognise the session that owned an answered turn), and
 * the sweep's `deadAfter` eviction — which always ran — is now the only exit. A thread holds at
 * most one entry either way.
 *
 * PII FIREWALL (the #2438 discipline): the only things logged are the Plain threadId, the household
 * id, the bounded transport label, and an age in seconds. Never the customer message, the reply,
 * the household name, or a sender address. Metric labels stay the already-allowed bounded
 * `{outcome, transport}` pair — plus `{channel}` on the #2477 watchdog series, itself a two-value
 * enum — never a thread id, household id, or email (docs/process/instrumentation.md §4). Note that
 * the correlation KEY is per-thread and, under #2517, will be a customer's email address; it is a
 * map key and a log field, and must never become a metric label.
 *
 * #2477 adds the WATCHDOG on top: the failure tiers above are counters that move only when
 * something is wrong, so [[sweep]] also publishes an always-on gauge + heartbeat that make the
 * healthy state, and the sweep's own liveness, positively observable. See [[sweep]].
 */
final class DispatchTracker private (
    pending: Ref[Map[String, DispatchTracker.Pending]],
    /**
     * The ERROR threshold — the configured agent-token TTL, NOT a literal of its own. See
     * [[DispatchTracker.deadAfterFor]] for why the TTL is the right (and only sourced) bound.
     */
    val deadAfter: Duration,
    /**
     * WHICH responder this tracker watches — the ONLY label on the #2477 watchdog series, and a
     * value from the existing bounded support|press vocabulary rather than a third private copy of
     * it (docs/process/single-source-of-truth.md). The tracker itself is channel-agnostic: its
     * correlation key is an opaque `String`, which is a Plain thread id for support and (per #2517)
     * a reply-target address for press.
     *
     * SCOPE, stated so the half-generalisation is not mistaken for a finished one: this label
     * drives the #2477 WATCHDOG series only. The two #2472 failure tiers below still emit onto
     * `support_dispatch_total` and still word their logs "support dispatch", because support is the
     * only channel that constructs a tracker today. #2517 — which pairs press dispatch with its
     * callback — is what routes those to `press_dispatch_total`; it must do so, since a press
     * dispatch reported onto the support series would be worse than not reported at all. Nothing
     * here mislabels in the meantime: no press tracker exists to run this code.
     */
    val channel: String,
) {
  import DispatchTracker.*

  /**
   * Record a dispatch that the transport ACCEPTED (`DispatchOutcome.Dispatched`). A second dispatch
   * on the same thread REPLACES the first: the thread is the unit the agent answers, and the newer
   * session is the one now owing a reply — an older outstanding entry for the same thread would
   * otherwise be reported as dead the moment the newer one answered. The replacement is logged so
   * the substitution is never silent.
   *
   * `sessionId` is the [[ConsentToken.newSessionId]] baked into the token this dispatch carries —
   * what makes "the earlier session no longer owes a reply" ENFORCEABLE at the callback boundary
   * ([[turnOwner]]) rather than only observable in this log (#2668). EMPTY is the explicit "this
   * channel has no per-dispatch session identity" value: press dispatches carry no agent session id
   * and press has no agent-callback guard to enforce, so [[turnOwner]] reports [[Turn.Unknown]] for
   * them and refuses nothing. It disables no guard that otherwise exists.
   */
  def dispatched(
      threadId: String,
      sessionId: String,
      household: HouseholdId,
      transport: String,
      now: Instant,
  ): UIO[Unit] =
    pending
      .modify { m =>
        (m.get(threadId), m.updated(threadId, Pending(household, transport, now, sessionId)))
      }
      .flatMap {
        // Only an OUTSTANDING prior entry is a supersede. #2668 keeps CLOSED entries in the map so
        // `turnOwner` can still recognise the session that owned an answered turn, which makes
        // `Some(prev)` the ordinary case for any follow-up message — and logging that as
        // "no longer owes a reply" would be false (it already replied) and would drain the one
        // line that identified the #2668 race in prod.
        case Some(prev) if !prev.closed =>
          ZIO.logInfo(
            s"support dispatch superseded thread=$threadId household=${household.value} " +
              s"transport=$transport priorAgeSeconds=${ageSeconds(prev, now)} — the earlier " +
              "session no longer owes a reply on this thread",
          )
        case _                          => ZIO.unit
      }

  /**
   * #2668 — does `sessionId` still own `threadId`'s turn? The ONE place that question is answered;
   * `SupportResponder.withClaimsE` consults it before letting a callback write anything the
   * customer sees.
   *
   * Structural, not timing-based: ownership is decided by WHICH dispatch was last recorded for the
   * thread, so two sessions racing 20 seconds apart (the prod #2668 shape) or 20 milliseconds apart
   * resolve identically, and in whichever order they land.
   *
   * [[Turn.Unknown]] — no record of the thread, or a pre-#2668 token with no session id — FAILS
   * OPEN. The state is in-memory and dropped on restart (see the class doc), so "nothing recorded"
   * is not evidence that this session was superseded, and an answer nobody receives is a worse
   * failure than an answer received twice.
   *
   * The entry survives [[calledBack]] precisely so this stays answerable afterwards: in prod the
   * SUPERSEDING session replied first, so by the time the superseded one called back the turn was
   * already closed. Eviction is [[sweep]]'s job, on the same [[deadAfter]] bound as before.
   */
  def turnOwner(threadId: String, sessionId: String, now: Instant): UIO[Turn] =
    if sessionId.isEmpty then ZIO.succeed(Turn.Unknown)
    else
      pending.get.map(_.get(threadId)).flatMap {
        case None                                => ZIO.succeed(Turn.Unknown)
        case Some(p) if p.sessionId.isEmpty      => ZIO.succeed(Turn.Unknown)
        case Some(p) if p.sessionId == sessionId => ZIO.succeed(Turn.Current)
        case Some(p)                             =>
          ZIO
            .logInfo(
              s"support callback from a SUPERSEDED session thread=$threadId " +
                s"household=${p.household.value} sessionAgeSeconds=${ageSeconds(p, now)} — a later " +
                "dispatch owns this turn and its context is a superset, so this session's " +
                "customer-visible write is dropped (#2668)",
            )
            .as(Turn.Superseded)
      }

  /**
   * #2667 — CLAIM this session's one customer-visible message for the turn, atomically.
   *
   * WHAT IT PROTECTS. The #2419 consent prompt is server-authored so that a prompt-injected agent
   * cannot craft a phishing message under our own `🤖 WifiHaven support assistant` attribution.
   * That only protects the customer if it is the WHOLE message they see at the consent moment: a
   * perfectly genuine, correctly-signed link with *"sign in with your password to verify your
   * identity"* posted beside it is a phishing primitive with every technical control intact. So the
   * two [[AgentAction.ThreadWrites]] are MUTUALLY EXCLUSIVE within a session — in either order,
   * because a hostile framing posted before the link works exactly as well as one posted after it.
   *
   * WHY A CLAIM AND NOT A CHECK. A check-then-write pair is racy by construction, and the caller
   * whose race it is, is the untrusted one: an injected agent can fire `reply` and
   * `request-consent` concurrently and slip both past a read. The claim is one atomic `modify`, so
   * exactly one of the two kinds can ever be held.
   *
   * REPEATS OF THE SAME KIND ARE [[ThreadWriteClaim.Held]], NOT refused. A session replying twice
   * is a separate question (#2668 owns "one turn, one reply" ACROSS sessions) and is deliberately
   * not changed here — this guard is about the two kinds not COMBINING.
   *
   * [[ThreadWriteClaim.Untracked]] — no record of the thread, a session that no longer owns it, or
   * a channel with no session identity — FAILS OPEN, the same call [[turnOwner]] makes and for the
   * same reason: the state is in-memory and dropped on restart, so "nothing recorded" is not
   * evidence that a consent prompt was posted, and a customer who gets no answer at all is the
   * worse failure. A superseded session is already refused by [[turnOwner]] before it reaches here.
   */
  def claimThreadWrite(
      threadId: String,
      sessionId: String,
      action: String,
  ): UIO[ThreadWriteClaim] =
    if sessionId.isEmpty then ZIO.succeed(ThreadWriteClaim.Untracked)
    else
      pending.modify { m =>
        m.get(threadId) match {
          case Some(p) if p.sessionId == sessionId =>
            p.threadWrites.find(_ != action) match {
              case Some(other) => (ThreadWriteClaim.Excluded(other), m)
              case None        =>
                if p.threadWrites.contains(action) then (ThreadWriteClaim.Held, m)
                else
                  (
                    ThreadWriteClaim.Claimed,
                    m.updated(threadId, p.copy(threadWrites = p.threadWrites + action)),
                  )
            }
          case _                                   => (ThreadWriteClaim.Untracked, m)
        }
      }

  /**
   * #2667 — give back a claim whose write did NOT land (Plain refused it, or the client is dark).
   *
   * Without this a failed consent post would silently spend the turn: the prompt never reached the
   * customer, yet the agent's "sorry, something went wrong" reply would be refused as if it had,
   * and the customer would get nothing at all. Releasing is safe precisely because nothing was put
   * in front of the customer — the exclusion has nothing to protect.
   *
   * Only the caller that took a fresh [[ThreadWriteClaim.Claimed]] may release; a
   * [[ThreadWriteClaim.Held]] repeat must not clear the claim its predecessor's SUCCESSFUL write
   * established.
   */
  def releaseThreadWrite(threadId: String, sessionId: String, action: String): UIO[Unit] =
    ZIO
      .unless(sessionId.isEmpty)(pending.update { m =>
        m.get(threadId) match {
          case Some(p) if p.sessionId == sessionId =>
            m.updated(threadId, p.copy(threadWrites = p.threadWrites - action))
          case _                                   => m
        }
      })
      .unit

  /**
   * Close the outstanding dispatch for `threadId`, if any: the agent came back. Called for TERMINAL
   * actions only ([[AgentAction.Terminal]]) — a household READ or an issue filing proves the
   * session is alive but produces nothing the customer sees, so it must not mark the turn served.
   *
   * This measures "did the session come back", NOT "did the reply land": it fires at token-verify
   * time, before the Plain write. A reply the agent posted and Plain then refused is already loud
   * on `support_agent_action_total{op="reply",outcome="error"}` — duplicating that judgement here
   * would be a second place computing the same thing (docs/process/single-source-of-truth.md).
   *
   * An UNTRACKED or already-closed thread is a no-op, not a warning: a second callback on the same
   * thread (reply after escalate is the instructed #2437 sequence), a dispatch outstanding across a
   * restart, or a callback arriving after the [[deadAfter]] entry was already reported all land
   * here legitimately — and each closes at most one dispatch, so `completed` still pairs 1:1 with
   * `dispatched`.
   *
   * #2668: the entry is MARKED closed rather than removed, so [[turnOwner]] can still tell a
   * superseded session from an unknown one after the turn has been answered. [[sweep]] evicts it on
   * the unchanged [[deadAfter]] bound and never reports a closed entry.
   */
  def calledBack(threadId: String, action: String, now: Instant): UIO[Unit] =
    pending
      .modify { m =>
        m.get(threadId) match {
          case Some(p) if !p.closed => (Some(p), m.updated(threadId, p.copy(closed = true)))
          case _                    => (None, m)
        }
      }
      .flatMap {
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
   * Report the dispatches nobody closed, in two tiers (see [[SlowAfter]] / [[deadAfter]] for why
   * one threshold cannot serve both):
   *
   *   - past [[SlowAfter]]: a WARN + `support_dispatch_total{outcome="callback_slow"}`, ONCE per
   *     dispatch (the entry stays — a suspended run legitimately resumes and answers);
   *   - past [[deadAfter]]: an attributable ERROR + `{outcome="no_callback"}`, and the entry is
   *     dropped so it is reported exactly once.
   *
   * The classification AND the state write happen in ONE atomic `modify`, with the reporting done
   * afterwards on the values it returned. A read-then-write pair would lose a dispatch recorded in
   * the gap: [[dispatched]] is itself atomic, so a fresh entry for a thread whose OLD entry the
   * sweep had already classified would be deleted by a `-- dead.keys` (leaving that session
   * untracked — the very silence this exists to close) or stamped `slowReported` (permanently
   * suppressing its WARN). The window was small and dispatch is capped at 50/day, but the class is
   * removable for free, so it is removed.
   *
   * #2477 — EVERY sweep also publishes its two watchdog series, before any of the conditional
   * reporting above and whether or not anything is wrong:
   *
   *   - [[UnrepliedGauge]] — how many dispatches are outstanding past [[SlowAfter]] RIGHT NOW,
   *     counted on the post-sweep map so a dispatch reported dead in this same tick is not also
   *     counted as still waiting. It is a LEVEL, not an event: the two tiers above are counters
   *     that move only on failure, so a healthy system emitted nothing at all and "nobody is
   *     waiting", "the responder was never enabled" and "the sweep fiber died" were one picture.
   *     The healthy value is an explicit 0.
   *   - [[SweepsCounter]] — the liveness anchor, without which the zero above would be worthless: a
   *     gauge keeps exporting its last value for as long as the process lives, so a sweep that
   *     stopped ticking would keep publishing a stale, reassuring 0 forever. A flat sweep counter
   *     is what distinguishes "no one is waiting" from "nothing is looking".
   *
   * That pairing is the whole point. #2546 records a detector (#2469's prompt-drift check) that has
   * never emitted a sample in ANY environment, and whose silence has read as health since the day
   * it shipped; this sweep must not become the next one.
   */
  def sweep(now: Instant): UIO[Unit] =
    pending
      .modify { m =>
        // #2668: a CLOSED entry is retained only so `turnOwner` can recognise a superseded
        // session; it is evicted on the same bound as before and is never reported — the agent
        // came back, which is the whole question these two tiers ask.
        val expired   = m.filter { case (_, p) => !ageBelow(p, now, deadAfter) }
        val dead      = expired.filter { case (_, p) => !p.closed }
        val slow      = m.filter { case (t, p) =>
          !expired.contains(t) && !p.closed && !p.slowReported && !ageBelow(p, now, SlowAfter)
        }
        val next      = (m -- expired.keys).map { case (t, p) =>
          t -> (if slow.contains(t) then p.copy(slowReported = true) else p)
        }
        // Counted over `next` (post-sweep) and NOT over `slow`: `slow` is the once-per-dispatch
        // report set and is empty on every later tick, whereas the gauge must keep reading 1 for
        // as long as that customer is actually still waiting.
        // #2668: `next` now retains CLOSED entries (so `turnOwner` can still place a superseded
        // session), and a closed dispatch is one the agent ANSWERED — counting it here would read
        // as a customer still waiting and would light the #2477 alert on a healthy thread.
        val unreplied = next.count { case (_, p) => !p.closed && !ageBelow(p, now, SlowAfter) }
        ((dead, slow, unreplied), next)
      }
      .flatMap { case (dead, slow, unreplied) =>
        AppMetrics.agentDispatchUnreplied(channel, unreplied) *>
          AppMetrics.agentDispatchSweep(channel) *>
          ZIO.foreachDiscard(dead) { case (threadId, p) =>
            AppMetrics.supportDispatch(Outcome.NoCallback, Some(p.transport)) *>
              ZIO.logError(
                s"support dispatch NEVER CALLED BACK thread=$threadId household=${p.household.value} " +
                  s"transport=${p.transport} afterSeconds=${ageSeconds(p, now)} — the cloud session " +
                  "accepted the trigger and no /api/support/agent/{reply,escalate,request-consent} " +
                  "call ever arrived within the agent-token TTL, so it can no longer answer even if " +
                  "it resumes (its token is expired — it would 401, #2473) and THIS CUSTOMER GOT NO " +
                  "ANSWER. Read the thread in Plain and reply by hand; nothing retries automatically " +
                  "(#2472)",
              )
          } *>
          ZIO.foreachDiscard(slow) { case (threadId, p) =>
            AppMetrics.supportDispatch(Outcome.CallbackSlow, Some(p.transport)) *>
              ZIO.logWarning(
                s"support dispatch still unanswered thread=$threadId " +
                  s"household=${p.household.value} transport=${p.transport} " +
                  s"afterSeconds=${ageSeconds(p, now)} — healthy replies land in 30-110s. A " +
                  s"${CloudAgentObservability.ClaudeCodeCloud} run suspended on subscription usage " +
                  "limits resumes and answers later — possibly the next morning (#2473) — so this " +
                  "is NOT yet an error and must not be hand-replied blind; a sustained rate is",
              )
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

  /**
   * One dispatch. `slowReported` makes the [[SlowAfter]] tier fire exactly once; `sessionId` +
   * `closed` are #2668's turn ownership — a closed entry is kept (never reported) until
   * [[deadAfter]] so a late callback from a SUPERSEDED session is still distinguishable from one on
   * a thread we simply have no record of.
   *
   * `threadWrites` is #2667's exclusion: which of the two [[AgentAction.ThreadWrites]] this session
   * has already put in front of the customer. It holds at most one value in practice — the second
   * KIND is what [[claimThreadWrite]] refuses — so it costs a few bytes and stays bounded by the
   * same eviction as everything else on the entry.
   */
  private[support] final case class Pending(
      household: HouseholdId,
      transport: String,
      dispatchedAt: Instant,
      sessionId: String,
      slowReported: Boolean = false,
      closed: Boolean = false,
      threadWrites: Set[String] = Set.empty,
  )

  /**
   * #2667 — the outcome of claiming a session's one customer-visible message. Bounded on purpose:
   * [[Excluded]] is the ONLY value that suppresses a write, and [[Untracked]] (the fail-open case)
   * must never be confused with it.
   */
  enum ThreadWriteClaim {

    /** Freshly taken by this call — the caller must [[releaseThreadWrite]] if its write fails. */
    case Claimed

    /** This session already wrote this KIND. Allowed, and NOT the caller's to release. */
    case Held

    /** The session already wrote the OTHER kind (`by`), so this one is suppressed. */
    case Excluded(by: String)

    /** No record, or no session identity — decide nothing, allow. */
    case Untracked
  }

  /**
   * #2668 — who owns a thread's current turn, from the point of view of a calling-back session.
   * Bounded on purpose: [[Unknown]] is the FAIL-OPEN case and must never be confused with
   * [[Superseded]], which is the only one that drops a customer-visible write.
   */
  enum Turn {

    /** This session is the latest dispatch on the thread — it owns the turn. */
    case Current

    /** A LATER dispatch owns the turn; this session's answer is a duplicate. */
    case Superseded

    /** No record (restart, evicted entry, or a pre-#2668 token) — decide nothing, allow. */
    case Unknown
  }

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

    /** Past [[deadAfterFor]] with no terminal callback — the customer got nothing. */
    val NoCallback: String = "no_callback"
  }

  /**
   * WARN tier. Observed HEALTHY replies during the #2335 go-live validation on staging (2026-07-26)
   * landed in 30–110s — the sample is written down and caveated at
   * https://github.com/wifihaven/wifihaven/issues/2472#issuecomment-5108763608, since a threshold
   * must not rest on a number that exists nowhere citable. 10 minutes is ~5.5× the slowest of those
   * — comfortably past normal variance while still being minutes, not hours, after the customer
   * sent their message.
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
   * ERROR tier — the configured agent-token TTL (`support.agentTokenTtlMinutes`, default 24h via
   * `AgentTokenTtl.DefaultMinutes`), read from config rather than re-hardcoded here.
   *
   * WHY THE TTL AND NOT A CHOSEN DURATION. The TTL is the one instant at which "no callback yet"
   * stops being ambiguous. Before it, silence is genuinely undecidable: `AgentTokenTtl`
   * (api/src/Config.scala) records that a `claude-code-cloud` run can be suspended on subscription
   * usage limits and that "a pause that starts in the evening resumes the next morning" — an
   * OVERNIGHT gap is documented, expected behaviour, and the run does answer. AFTER the TTL the
   * session's token is expired, so a resumed run's callback 401s and is silently lost (that IS
   * #2473) — the answer can no longer reach the customer no matter what the cloud does. So this is
   * the earliest point at which the ERROR is unconditionally true.
   *
   * It also makes the ERROR's instruction safe. The log tells the operator to reply by hand; if the
   * threshold sat BELOW the TTL, a run that resumed afterwards would post its own reply into the
   * same thread and the customer would get two — precisely the duplicate-reply cost this change
   * cites when declining auto-retry. Past the TTL that race is impossible by construction.
   *
   * An earlier draft used a literal 6h, reading `AgentTokenTtl`'s "anything under ~6h reproduces
   * that" as an upper bound on legitimate latency. It is the opposite — a LOWER bound on the TTL (a
   * 6h TTL still 401s the resumed run) — and the same paragraph names a >6h legitimate round trip.
   * The intermediate hours are covered by the [[SlowAfter]] WARN tier, which is what a
   * minutes-to-hours "still nothing" signal should be.
   */
  def deadAfterFor(cfg: SupportConfig): Duration = cfg.agentTokenTtl

  /**
   * #2477 — the watchdog gauge: dispatches outstanding past [[SlowAfter]], written on every sweep.
   * Named here rather than at the emit site so the dashboards, the alert rules and the spec all
   * quote ONE spelling.
   */
  val UnrepliedGauge: String = "agent_dispatch_unreplied"

  /** #2477 — the sweep's own heartbeat; see [[sweep]] for why the gauge is useless without it. */
  val SweepsCounter: String = "agent_dispatch_sweeps_total"

  /** How often [[loop]] sweeps. */
  val SweepInterval: Duration = 60.seconds

  private def ageSeconds(p: Pending, now: Instant): Long =
    Duration.between(p.dispatchedAt, now).getSeconds

  /**
   * `now` is strictly inside `limit` of the dispatch — clock-skew-safe (a negative age is inside).
   */
  private def ageBelow(p: Pending, now: Instant, limit: Duration): Boolean =
    Duration.between(p.dispatchedAt, now).compareTo(limit) < 0

  /**
   * `channel` is deliberately NOT defaulted. A default would let a future press tracker (#2517)
   * publish its unreplied journalists onto the support channel's series by omission — the kind of
   * silent mislabelling that is worse than no series at all.
   */
  def make(deadAfter: Duration, channel: String): UIO[DispatchTracker] =
    Ref.make(Map.empty[String, Pending]).map(new DispatchTracker(_, deadAfter, channel))

  val layer: ZLayer[SupportConfig, Nothing, DispatchTracker] =
    ZLayer.fromZIO(
      ZIO.serviceWithZIO[SupportConfig](cfg =>
        make(deadAfterFor(cfg), AgentTokenRejection.Channel.Support),
      ),
    )
}
