package wifihaven.api.support

import wifihaven.api.{PressConfig, SupportConfig}
import wifihaven.api.metrics.AppMetrics
import wifihaven.api.observability.AgentTokenRejection
import wifihaven.shared.Clock
import wifihaven.shared.types.HouseholdId
import zio.*

import java.time.{Duration, Instant}

/**
 * #2472 (support) / #2517 (press) — dispatch→completion tracking for BOTH cloud-agent responders.
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
 * PII FIREWALL (the #2438 discipline): the only things logged are the correlation key, the
 * channel's own subject field, the bounded transport label, and an age in seconds. Never the
 * inbound message, the reply, or the household name. The subject field is channel-chosen and
 * channel-reviewed: support logs the household id, press logs the reply-to ADDRESS — press must,
 * because a press report is only actionable if it names the journalist who got no answer, and that
 * address is already logged at INFO twice on the same path.
 *
 * The correlation KEY is a map key and a log field and must NEVER become a metric label — the press
 * fallback key embeds a journalist's email address. Metric labels stay the bounded `{outcome,
 * transport}` pair, plus `{channel}` on the #2477 watchdog series, itself the same two-value enum
 * as [[DispatchTracker.Channel]] (docs/process/instrumentation.md §4).
 *
 * #2477 adds the WATCHDOG on top: the failure tiers above are counters that move only when
 * something is wrong, so [[sweep]] also publishes an always-on gauge + heartbeat that make the
 * healthy state, and the sweep's own liveness, positively observable. See [[sweep]].
 */
final class DispatchTracker private (
    /**
     * WHICH responder this tracker watches: its metric sink, its log vocabulary, and the operator
     * advice on a dead dispatch. [[DispatchTracker.Channel.name]] is also the ONLY label on the
     * #2477 watchdog series, and it is a value from the existing bounded support|press vocabulary
     * (`AgentTokenRejection.Channel`) rather than a third private copy of it
     * (docs/process/single-source-of-truth.md).
     *
     * The tracker is key-agnostic: the correlation key is an opaque `String`, a Plain thread id for
     * support and a recorded-row-id-or-reply-address for press (`PressResponder.dispatchKey`).
     */
    val channel: DispatchTracker.Channel,
    pending: Ref[Map[String, DispatchTracker.Pending]],
    /**
     * The ERROR threshold — the configured agent-token TTL, NOT a literal of its own. See
     * [[DispatchTracker.deadAfterFor]] for why the TTL is the right (and only sourced) bound.
     */
    val deadAfter: Duration,
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
      key: String,
      sessionId: String,
      subject: Subject,
      transport: String,
      now: Instant,
  ): UIO[Unit] =
    pending
      .modify { m => (m.get(key), m.updated(key, Pending(subject, transport, now, sessionId))) }
      .flatMap {
        // Only an OUTSTANDING prior entry is a supersede. #2668 keeps CLOSED entries in the map so
        // `turnOwner` can still recognise the session that owned an answered turn, which makes
        // `Some(prev)` the ordinary case for any follow-up message — and logging that as
        // "no longer owes a reply" would be false (it already replied) and would drain the one
        // line that identified the #2668 race in prod.
        case Some(prev) if !prev.closed =>
          ZIO.logInfo(
            s"${channel.name} dispatch superseded ${channel.keyLabel}=$key " +
              s"${subject.label}=${subject.value} transport=$transport " +
              s"priorAgeSeconds=${ageSeconds(prev, now)} — the earlier session no longer owes a " +
              "reply on this key",
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
  def turnOwner(key: String, sessionId: String, now: Instant): UIO[Turn] =
    if sessionId.isEmpty then ZIO.succeed(Turn.Unknown)
    else
      pending.get.map(_.get(key)).flatMap {
        case None                                => ZIO.succeed(Turn.Unknown)
        case Some(p) if p.sessionId.isEmpty      => ZIO.succeed(Turn.Unknown)
        case Some(p) if p.sessionId == sessionId => ZIO.succeed(Turn.Current)
        case Some(p)                             =>
          ZIO
            .logInfo(
              s"${channel.name} callback from a SUPERSEDED session ${channel.keyLabel}=$key " +
                s"${p.subject.label}=${p.subject.value} " +
                s"sessionAgeSeconds=${ageSeconds(p, now)} — a later " +
                "dispatch owns this turn and its context is a superset, so this session's " +
                "customer-visible write is dropped (#2668)",
            )
            .as(Turn.Superseded)
      }

  /**
   * Close the outstanding dispatch for `threadId`, if any: the agent came back. Called for TERMINAL
   * actions only ([[AgentAction.Terminal]]) — a household READ or an issue filing proves the
   * session is alive but produces nothing the customer sees, so it must not mark the turn served.
   *
   * This measures "did the session come back", NOT "did the reply land". A reply the agent posted
   * and the transport then refused is already loud on
   * `{support,press}_agent_action_total{op="reply",outcome="error"}` — duplicating that judgement
   * here would be a second place computing the same thing (docs/process/single-source-of-truth.md).
   *
   * WHEN it fires relative to the action is the CHANNEL'S call, and the two differ today. Support
   * closes at token-verify time, before the Plain write. Press closes AFTER the action and only for
   * outcomes that did something (`PressResponder.closesDispatch`), so a callback the #2437
   * escalation cap REFUSED is not counted served — nothing reached the journalist, and the entry
   * has to stay in the sweep. Support still carries the pre-#2517 shape and so still counts a
   * rate-limited `escalate` / `request-consent` as completed; that is tracked as
   * https://github.com/wifihaven/wifihaven/issues/2694, deliberately not changed here because it is
   * a behaviour change to a shipped path and wants its own red test.
   *
   * An UNTRACKED or already-closed key is a no-op, not a warning: a second callback on the same key
   * (reply after escalate is the instructed #2437 sequence), a dispatch outstanding across a
   * restart, or a callback arriving after the [[deadAfter]] entry was already reported all land
   * here legitimately — and each closes at most one dispatch, so `completed` still pairs 1:1 with
   * `dispatched`. So does a callback on the OTHER channel's key — each channel holds its own
   * instance, so one can never close the other's dispatch.
   *
   * #2668: the entry is MARKED closed rather than removed, so [[turnOwner]] can still tell a
   * superseded session from an unknown one after the turn has been answered. [[sweep]] evicts it on
   * the unchanged [[deadAfter]] bound and never reports a closed entry.
   */
  def calledBack(key: String, action: String, now: Instant): UIO[Unit] =
    pending
      .modify { m =>
        m.get(key) match {
          case Some(p) if !p.closed => (Some(p), m.updated(key, p.copy(closed = true)))
          case _                    => (None, m)
        }
      }
      .flatMap {
        case None    => ZIO.unit
        case Some(p) =>
          channel.record(Outcome.Completed, Some(p.transport)) *>
            ZIO.logInfo(
              s"${channel.name} dispatch completed action=$action ${describe(key, p)} " +
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
        AppMetrics.agentDispatchUnreplied(channel.name, unreplied) *>
          AppMetrics.agentDispatchSweep(channel.name) *>
          ZIO.foreachDiscard(dead) { case (key, p) =>
            channel.record(Outcome.NoCallback, Some(p.transport)) *>
              ZIO.logError(
                s"${channel.name} dispatch NEVER CALLED BACK ${describe(key, p)} " +
                  s"afterSeconds=${ageSeconds(p, now)} — ${channel.deadAdvice}",
              )
          } *>
          ZIO.foreachDiscard(slow) { case (key, p) =>
            channel.record(Outcome.CallbackSlow, Some(p.transport)) *>
              ZIO.logWarning(
                s"${channel.name} dispatch still unanswered ${describe(key, p)} " +
                  s"afterSeconds=${ageSeconds(p, now)} — healthy replies land in 30-110s. A " +
                  s"${CloudAgentObservability.ClaudeCodeCloud} run suspended on subscription usage " +
                  "limits resumes and answers later — possibly the next morning (#2473) — so this " +
                  "is NOT yet an error and must not be hand-replied blind. What matters is the " +
                  "SHAPE: a sustained rate, or one that tracks the no_callback tier, means " +
                  "sessions are STALLING rather than pausing.",
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

  /**
   * The channel's identifying fragment for one entry: `<keyLabel>=<key> <subjectLabel>=<value>`.
   */
  private def describe(key: String, p: Pending): String =
    s"${channel.keyLabel}=$key ${p.subject.label}=${p.subject.value} transport=${p.transport}"
}

object DispatchTracker {

  /**
   * The channel-specific half of the tracker: where completions are metered, what the log calls
   * things, and what an operator should DO about a dead dispatch. Everything else — thresholds,
   * exactly-once reporting, the #2477 watchdog, the #2668 turn ownership, the no-retry decision —
   * is shared, which is the point of #2517 generalizing over the KEY rather than forking the file.
   *
   * A SEALED trait with the metric sink fixed per case, NOT a case class carrying a function field:
   * a constructible `Channel` would let a call site wire `Channel.Press` to
   * `AppMetrics.supportDispatch` — it compiles, and press completions would silently land on the
   * support series.
   */
  sealed trait Channel {

    /** Log prefix, the operator's grep handle, and the `{channel}` label on the #2477 series. */
    def name: String

    /** What the correlation key is called in the log. */
    def keyLabel: String

    /** The ERROR-tier instruction: who got nothing, and how a human recovers it. */
    def deadAdvice: String

    /** The channel's `{outcome, transport}` counter. */
    def record(outcome: String, transport: Option[String]): UIO[Unit]
  }

  object Channel {

    case object Support extends Channel {
      // #2477's watchdog series is labelled with this, so it is the SHARED support|press
      // vocabulary rather than a third private copy of the same two strings.
      val name: String       = AgentTokenRejection.Channel.Support
      val keyLabel: String   = "thread"
      val deadAdvice: String =
        "the cloud session accepted the trigger and no /api/support/agent/{reply,escalate," +
          "request-consent} call ever arrived within the agent-token TTL, so it can no longer " +
          "answer even if it resumes (its token is expired — it would 401, #2473) and THIS " +
          "CUSTOMER GOT NO ANSWER. Read the thread in Plain and reply by hand; nothing retries " +
          "automatically (#2472)"

      def record(outcome: String, transport: Option[String]): UIO[Unit] =
        AppMetrics.supportDispatch(outcome, transport)
    }

    /**
     * #2517. The press twin. The advice differs in BOTH halves that matter: the endpoints are the
     * press pair (there is no consent request — the press token carries no household and no data
     * scope by construction), and the recovery is an email rather than a Plain thread, because
     * press has no inbox of ours to reply from — `press@` is a Cloudflare Email Worker, and the
     * correspondence log at `/press` is a pull surface nothing points a human at.
     */
    case object Press extends Channel {
      val name: String       = AgentTokenRejection.Channel.Press
      val keyLabel: String   = "key"
      val deadAdvice: String =
        "the cloud session accepted the trigger and no /api/press/agent/{reply,escalate} call " +
          "ever arrived within the agent-token TTL, so it can no longer answer even if it resumes " +
          "(its token is expired — it would 401, #2473) and THIS JOURNALIST GOT NO ANSWER. Read " +
          "the inquiry at /press (the #2296 correspondence log) and reply to the address above by " +
          "hand; nothing retries automatically (#2517)"

      def record(outcome: String, transport: Option[String]): UIO[Unit] =
        AppMetrics.pressDispatch(outcome, transport)
    }
  }

  /**
   * The channel's identifying field for one dispatch, as a LABEL/VALUE pair so a call site cannot
   * quietly widen what gets logged: support passes the household id, press the reply-to address.
   * Never the message text (see the PII note on [[DispatchTracker]]).
   */
  final case class Subject(label: String, value: String)

  object Subject {
    def household(id: HouseholdId): Subject = Subject("household", id.value.toString)
    def replyTo(address: String): Subject   = Subject("replyTo", address)
  }

  /**
   * One dispatch. `slowReported` makes the [[SlowAfter]] tier fire exactly once; `sessionId` +
   * `closed` are #2668's turn ownership — a closed entry is kept (never reported) until
   * [[deadAfter]] so a late callback from a SUPERSEDED session is still distinguishable from one on
   * a thread we simply have no record of.
   */
  private[support] final case class Pending(
      subject: Subject,
      transport: String,
      dispatchedAt: Instant,
      sessionId: String,
      slowReported: Boolean = false,
      closed: Boolean = false,
  )

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

  /** #2517 — the press twin, read from `press.agentTokenTtlMinutes` for the identical reason. */
  def deadAfterFor(cfg: PressConfig): Duration = cfg.agentTokenTtl

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
   * `channel` is deliberately NOT defaulted (#2477). A default would let the press tracker publish
   * its unreplied journalists onto the support channel's series by omission — the kind of silent
   * mislabelling that is worse than no series at all. #2517 makes that stronger than a convention:
   * [[Channel]] is a sealed trait carrying the metric SINK as well as the label, so a miswired
   * channel cannot compile, let alone emit.
   */
  def make(deadAfter: Duration, channel: Channel): UIO[DispatchTracker] =
    Ref.make(Map.empty[String, Pending]).map(new DispatchTracker(channel, _, deadAfter))

  /**
   * #2517 — the two channels hold SEPARATE instances (separate pending maps, separate metric sinks,
   * separate #2477 watchdog series), so they need distinct types in the ZIO environment. These thin
   * wrappers provide that without giving either channel a privileged unwrapped position: `Main` and
   * `HttpRoutes` unwrap at the seam, and everything downstream takes a plain [[DispatchTracker]].
   */
  final case class ForSupport(tracker: DispatchTracker)
  final case class ForPress(tracker: DispatchTracker)

  val supportLayer: ZLayer[SupportConfig, Nothing, ForSupport] =
    ZLayer.fromZIO(
      ZIO.serviceWithZIO[SupportConfig](cfg =>
        make(deadAfterFor(cfg), Channel.Support).map(ForSupport.apply),
      ),
    )

  val pressLayer: ZLayer[PressConfig, Nothing, ForPress] =
    ZLayer.fromZIO(
      ZIO.serviceWithZIO[PressConfig](cfg =>
        make(deadAfterFor(cfg), Channel.Press).map(ForPress.apply),
      ),
    )
}
