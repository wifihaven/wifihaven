package wifihaven.api.unit

import wifihaven.api.support.{AgentAction, DispatchTracker}
import wifihaven.shared.types.HouseholdId
import zio.*
import zio.test.*

import java.time.{Duration, Instant}

/**
 * #2667 — the claim state machine behind "at the consent moment the customer sees only
 * server-authored text", exercised directly.
 *
 * WHY A UNIT SPEC AND NOT ONLY THE FEATURE SUITE. `SupportConsentExclusiveSpec` drives the property
 * through the full stack, which is the right level for the property — but it can only drive the
 * callbacks SEQUENTIALLY, and the hole this pins is a concurrency one: an agent controls when it
 * fires its tool calls, and firing two at once is ordinary agent behaviour, not an exotic race.
 * Reviewing the first cut of this change surfaced a real sequence the feature suite could not reach
 * — two concurrent `reply` calls, one landing and one failing, where a release keyed only by action
 * name gave back the protection the LANDED write had earned, after which a consent prompt could
 * post underneath agent text already in front of the customer. The tracker is a `Ref` state
 * machine, so testing it here is testing the thing itself rather than a mock of it.
 */
object DispatchTrackerClaimSpec extends ZIOSpecDefault {

  // #2517 generalised the tracker over its subject: a support thread reports the household,
  // a press dispatch a reply-to address. Same bounded label vocabulary, one type.
  private val Household = DispatchTracker.Subject.household(HouseholdId(1L))
  private val Now       = Instant.parse("2026-08-14T12:00:00Z")
  private val Thread    = "th_claim"
  private val Session   = "sess_a"

  private def tracker =
    DispatchTracker
      .make(Duration.ofHours(24), DispatchTracker.Channel.Support)
      .tap(_.dispatched(Thread, Session, Household, "managed-agents", Now))

  private def claim(t: DispatchTracker, action: String) =
    t.claimThreadWrite(Thread, Session, action)

  private def settle(t: DispatchTracker, action: String, landed: Boolean) =
    t.settleThreadWrite(Thread, Session, action, landed)

  import DispatchTracker.ThreadWriteClaim.*

  def spec = suite("#2667 thread-write claim")(
    test("a LANDED consent prompt excludes a later reply") {
      for {
        t <- tracker
        a <- claim(t, AgentAction.ConsentRequest)
        _ <- settle(t, AgentAction.ConsentRequest, landed = true)
        b <- claim(t, AgentAction.Reply)
      } yield assertTrue(a == Claimed, b == Excluded(AgentAction.ConsentRequest))
    },
    test("a LANDED reply excludes a later consent prompt — the other direction") {
      for {
        t <- tracker
        a <- claim(t, AgentAction.Reply)
        _ <- settle(t, AgentAction.Reply, landed = true)
        b <- claim(t, AgentAction.ConsentRequest)
      } yield assertTrue(a == Claimed, b == Excluded(AgentAction.Reply))
    },
    test("an IN-FLIGHT write excludes the other kind before it has landed") {
      // The decisive case for concurrency: if only landed writes blocked, two callbacks fired
      // together would each see the other as absent and BOTH would post.
      for {
        t <- tracker
        a <- claim(t, AgentAction.ConsentRequest)
        b <- claim(t, AgentAction.Reply)
      } yield assertTrue(a == Claimed, b == Excluded(AgentAction.ConsentRequest))
    },
    test("a write that did NOT land gives the turn back") {
      for {
        t <- tracker
        _ <- claim(t, AgentAction.ConsentRequest)
        _ <- settle(t, AgentAction.ConsentRequest, landed = false)
        b <- claim(t, AgentAction.Reply)
      } yield assertTrue(b == Claimed)
    },
    test("a FAILED sibling does not give back what a LANDED concurrent write earned") {
      // The reviewed hole, pinned. Two replies in flight at once; one lands, one fails. The failure
      // must not clear the reply key, or a consent prompt would post underneath text the customer
      // can already see — a genuine signed link with agent-authored framing above it.
      for {
        t <- tracker
        a <- claim(t, AgentAction.Reply)
        b <- claim(t, AgentAction.Reply)
        _ <- settle(t, AgentAction.Reply, landed = true)
        _ <- settle(t, AgentAction.Reply, landed = false)
        c <- claim(t, AgentAction.ConsentRequest)
      } yield assertTrue(a == Claimed, b == Claimed, c == Excluded(AgentAction.Reply))
    },
    test("two writes of the same kind that BOTH fail leave the turn open") {
      for {
        t <- tracker
        _ <- claim(t, AgentAction.Reply)
        _ <- claim(t, AgentAction.Reply)
        _ <- settle(t, AgentAction.Reply, landed = false)
        _ <- settle(t, AgentAction.Reply, landed = false)
        c <- claim(t, AgentAction.ConsentRequest)
      } yield assertTrue(c == Claimed)
    },
    test("repeats of the SAME kind are never refused — #2668 owns one-reply-per-turn") {
      for {
        t <- tracker
        a <- claim(t, AgentAction.Reply)
        _ <- settle(t, AgentAction.Reply, landed = true)
        b <- claim(t, AgentAction.Reply)
      } yield assertTrue(a == Claimed, b == Claimed)
    },
    test("claiming the two kinds from parallel fibers admits exactly one, either way round") {
      // HONEST ABOUT WHAT THIS BUYS: it is a smoke test, not the concurrency pin. Two `Ref.modify`s
      // on an uncontended path will rarely interleave, so a check-then-write implementation would
      // probably pass it too. The property is carried by the DETERMINISTIC tests above — an
      // in-flight write already excludes the other kind, and a failed sibling cannot give back what
      // a landed one earned — which is where a racy implementation actually fails. This one only
      // adds that the outcome does not depend on which fiber wins.
      ZIO
        .foreach(1 to 50) { _ =>
          for {
            t  <- tracker
            rs <- ZIO.collectAllPar(
              Chunk(claim(t, AgentAction.ConsentRequest), claim(t, AgentAction.Reply)),
            )
          } yield rs.count(_ == Claimed)
        }
        .map(admitted => assertTrue(admitted.forall(_ == 1)))
    },
    test("a DIFFERENT session claims nothing — the entry belongs to the dispatched session") {
      for {
        t <- tracker
        _ <- claim(t, AgentAction.ConsentRequest)
        _ <- settle(t, AgentAction.ConsentRequest, landed = true)
        // A superseded session is refused by `turnOwner` upstream; if one ever reached here it must
        // not be able to settle or steal another session's claim.
        b <- t.claimThreadWrite(Thread, "sess_other", AgentAction.Reply)
      } yield assertTrue(b == Untracked)
    },
    test("an UNTRACKED thread fails open, and an empty session id decides nothing") {
      for {
        t <- tracker
        a <- t.claimThreadWrite("th_unknown", Session, AgentAction.Reply)
        b <- t.claimThreadWrite(Thread, "", AgentAction.Reply)
      } yield assertTrue(a == Untracked, b == Untracked)
    },
  )
}
