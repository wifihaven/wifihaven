package wifihaven.api.routes

import wifihaven.api.metrics.AppMetrics
import wifihaven.api.policy.PolicySnapshotPublisher
import wifihaven.shared.PolicySnapshot
import wifihaven.shared.types.{ETag, HouseholdId, RouterId}
import zio.*
import zio.http.{ChannelEvent, WebSocketChannel, WebSocketFrame}
import zio.json.*

/**
 * #1846: the per-router websocket connection registry. Tracks the live [[WebSocketChannel]]s open
 * for each [[RouterId]] so a later push path (#1849 — policy pushed on change) can look up a
 * router's socket(s) and fan a `policy` frame out to them, and so the server can expose a "is this
 * router connected right now?" signal (§5.5).
 *
 * Single-process, in-memory (`Ref[Map[RouterId, Conn]]`). This is the right shape for one API
 * process; pub/sub fan-out across instances is explicitly out of scope (#1023, design §6.1).
 *
 * **At most one live channel per router (#2561).** A router runs one agent holding one socket, so a
 * second `register` for an id that is already present means the OLD socket is dead and the server
 * simply has not noticed — a half-open TCP connection whose `receiveAll` never completes, so the
 * teardown `ensuring` in [[RouterWsRoutes]] never runs and its entry would never be dropped. That
 * is exactly what leaked the gauge to 2 with one router connected: two consecutive `router ws:
 * connected` for the same id with no `disconnected` between them. So a re-connect SUPERSEDES: the
 * stale channels are evicted and shut down, which both bounds the registry and unwedges the dead
 * socket's fiber. The per-id `Set` is retained because eviction is still channel-identity-scoped —
 * a superseded socket's late `deregister` must not remove its replacement.
 *
 * Every mutation refreshes the `router_ws_connections_active` gauge to the live total channel
 * count, so the gauge is a pure function of registry state and ages out cleanly on disconnect (§7).
 *
 * **#2619: each entry also carries the router's [[HouseholdId]]**, captured at register time from
 * the already-authenticated [[wifihaven.shared.Router]]. It does not route frames — delivery is
 * unchanged. It exists so the delivery-time `routers.last_etag` stamp can REFUSE to write an etag
 * from a snapshot built for a different household than the router receiving it.
 */
trait RouterWsRegistry {

  /**
   * Record a freshly-upgraded channel for `id`, superseding any channel already held for that
   * router (#2561 — a reconnect means the old socket is dead; it is evicted and shut down).
   * Refreshes the active-connections gauge. `household` is the authenticated router's household
   * (#2619), retained so a delivery can be checked against the snapshot's household.
   */
  def register(id: RouterId, household: HouseholdId, channel: WebSocketChannel): UIO[Unit]

  /** Drop a closed/closing channel for `id`. Refreshes the active-connections gauge. */
  def deregister(id: RouterId, channel: WebSocketChannel): UIO[Unit]

  /** The live channels for `id` (empty if the router is not connected) — the #1849 push lookup. */
  def channelsFor(id: RouterId): UIO[Set[WebSocketChannel]]

  /** True iff `id` has at least one live channel right now (§5.5 link-up signal). */
  def isConnected(id: RouterId): UIO[Boolean]

  /** Total open channels across all routers — backs `router_ws_connections_active`. */
  def activeCount: UIO[Int]

  /**
   * #1849: fan a freshly-changed snapshot out as one `policy` frame to every connected channel. The
   * server-side end of push-on-change: [[wifihaven.api.policy.PolicyService.reevaluate]] calls this
   * (via the [[PolicySnapshotPublisher]] seam) only when the snapshot's ETag actually moved, so a
   * change is computed once and pushed, not recomputed per poll per router (#1512). A send failure
   * (a racing disconnect) deregisters that channel and is metered `channel_closed`.
   *
   * `household` is the household `snap` was BUILT for (#2619). Frame DELIVERY is unchanged — the
   * snapshot still goes to every connected channel, which is the open #2626 fan-out gap this change
   * deliberately does not touch — but only channels whose own household matches get their
   * `routers.last_etag` stamped; the rest are metered `household_mismatch`.
   */
  def publishPolicy(household: HouseholdId, snap: PolicySnapshot): UIO[Unit]

  /**
   * #1849: push the current snapshot to a single freshly-connected channel (design §6.1 first-
   * policy-on-connect), so a router that just opened the socket gets policy immediately rather than
   * at the next change. A send failure is metered `channel_closed` (the caller's receive loop will
   * deregister on close). `household` is the household `snap` was built for; on this path that is
   * by construction the router's own (the caller reads `policy.snapshot(router.householdId)`).
   */
  def pushPolicyTo(
      id: RouterId,
      household: HouseholdId,
      channel: WebSocketChannel,
      snap: PolicySnapshot,
  ): UIO[Unit]
}

object RouterWsRegistry {

  /** One router's live connection state: the household it authenticated as, and its channel(s). */
  private[routes] final case class Conn(household: HouseholdId, channels: Set[WebSocketChannel])

  /**
   * `onDelivered` is the #2619 delivery sink: called with `(routerId, etag)` after a `policy` frame
   * has actually been sent to that router, and only when the snapshot's household matches the
   * router's own. Production wires it to `routerRepo.touch(id, Some(etag), None)` so
   * `routers.last_etag` means the same thing on both transports.
   *
   * It is a REQUIRED parameter, not a defaulted one — a silently-absent sink is exactly the
   * dark-by-default shape `docs/process/no-dark-by-default.md` forbids, and an absent writer here
   * is what froze `last_etag` fleet-wide when #2608 made ws the router default. A registry-only
   * unit test that asserts nothing about delivery passes a no-op explicitly; the real DB write is
   * pinned end-to-end, against a real repo on embedded Postgres, in `RouterWsSpec`.
   */
  def make(onDelivered: (RouterId, ETag) => Task[Unit]): UIO[RouterWsRegistry] =
    Ref.make(Map.empty[RouterId, Conn]).map(new RouterWsRegistryLive(_, onDelivered))

  /** The `{op:"policy", payload:<snapshot>}` envelope a pushed snapshot rides (design §1.2). */
  private[routes] def policyFrameText(snap: PolicySnapshot): String =
    s"""{"op":"policy","payload":${snap.toJson}}"""
}

final class RouterWsRegistryLive(
    state: Ref[Map[RouterId, RouterWsRegistry.Conn]],
    onDelivered: (RouterId, ETag) => Task[Unit],
) extends RouterWsRegistry
    with PolicySnapshotPublisher {

  import RouterWsRegistry.Conn

  // Recompute the live total and publish it. Called after every mutation so a deregister on
  // disconnect drives the gauge back down rather than leaving a stale high-water value.
  private def publishActive(m: Map[RouterId, Conn]): UIO[Unit] =
    AppMetrics.setWsConnectionsActive(m.valuesIterator.map(_.channels.size).sum)

  // #2561: a re-connect for a router that is already present REPLACES its channel set rather than
  // adding alongside it. The superseded channels are shut down (best-effort — a half-open socket may
  // never respond) so their server-side fiber unwinds and the `ensuring` teardown in RouterWsRoutes
  // runs; that late deregister is channel-identity-scoped, so it cannot remove the replacement.
  def register(id: RouterId, household: HouseholdId, channel: WebSocketChannel): UIO[Unit] =
    state
      .modify { m =>
        val superseded = m.get(id).map(_.channels).getOrElse(Set.empty) - channel
        val next       = m.updated(id, Conn(household, Set(channel)))
        ((superseded, next), next)
      }
      .flatMap { case (superseded, next) =>
        publishActive(next) *>
          ZIO.foreachDiscard(superseded) { stale =>
            AppMetrics.recordWsConnectionSuperseded *>
              ZIO.logWarning(
                s"router ws: superseded a stale channel on reconnect router=$id " +
                  "(previous socket never tore down)",
              ) *>
              stale.shutdown
          }
      }

  def deregister(id: RouterId, channel: WebSocketChannel): UIO[Unit] =
    state
      .updateAndGet { m =>
        m.get(id) match {
          case None       => m
          case Some(conn) =>
            val remaining = conn.channels - channel
            if remaining.isEmpty then m.removed(id)
            else m.updated(id, conn.copy(channels = remaining))
        }
      }
      .flatMap(publishActive)

  def channelsFor(id: RouterId): UIO[Set[WebSocketChannel]] =
    state.get.map(_.get(id).map(_.channels).getOrElse(Set.empty))

  def isConnected(id: RouterId): UIO[Boolean] =
    state.get.map(_.get(id).exists(_.channels.nonEmpty))

  def activeCount: UIO[Int] =
    state.get.map(_.valuesIterator.map(_.channels.size).sum)

  // The `PolicySnapshotPublisher` sink PolicyService.reevaluate pushes changed snapshots to.
  def publish(household: HouseholdId, snap: PolicySnapshot): UIO[Unit] =
    publishPolicy(household, snap)

  def publishPolicy(household: HouseholdId, snap: PolicySnapshot): UIO[Unit] =
    state.get.flatMap { m =>
      val frame = RouterWsRegistry.policyFrameText(snap)
      ZIO.foreachDiscard(m.toList) { case (id, conn) =>
        ZIO.foreachDiscard(conn.channels)(ch =>
          sendPolicyFrame(
            id,
            conn.household,
            household,
            ch,
            frame,
            snap,
            deregisterOnFailure = true,
          ),
        )
      }
    }

  def pushPolicyTo(
      id: RouterId,
      household: HouseholdId,
      channel: WebSocketChannel,
      snap: PolicySnapshot,
  ): UIO[Unit] =
    // `deregisterOnFailure = false` preserves this path's pre-existing behaviour: the caller
    // (RouterWsRoutes' HandshakeComplete branch) has its own `ensuring` teardown, and the channel
    // was registered microseconds ago. The two household arguments are the same value on this path
    // by construction — the caller built the snapshot from `router.householdId`.
    sendPolicyFrame(
      id,
      household,
      household,
      channel,
      RouterWsRegistry.policyFrameText(snap),
      snap,
      deregisterOnFailure = false,
    )

  /**
   * #2619: stamp `routers.last_etag` with the etag this push DELIVERED.
   *
   * What the column promises after this change: **the newest policy version the server has sent to
   * this router**, on whichever transport sent it. That is a send-time fact, not an applied-time
   * one — identical in kind to what the REST poll already recorded (it stamps when it SERVES the
   * snapshot, before the agent has parsed, let alone applied, it). Neither transport's value means
   * "this router is enforcing this policy"; the router's own applied etag is what the e2e barrier
   * reads off disk (`scripts/e2e/lib/wait.py`). The promise is documented on the read surface,
   * `RouterSummary.lastEtag`.
   *
   * **Household guard.** The etag is only written when the snapshot was built for the SAME
   * household the router authenticated as. `PolicyService.reevaluate` still rebuilds the
   * `HouseholdId.Default` snapshot and fans it out to every connected router regardless of
   * household (`api/src/policy/PolicyService.scala`, the open #2626 gap), so an unguarded stamp
   * would persist household 1's etag onto another household's row — turning a transient
   * wrong-content push into a durable wrong value in the very column this is making authoritative.
   * A mismatch is metered `household_mismatch` and logged, which is also the first observable
   * signal that the fan-out is crossing a tenant boundary at all.
   *
   * Best-effort: a DB hiccup must never tear down a push, so failures are caught. They are NOT
   * silent — `router_ws_etag_stamp_total{outcome}` is dashboarded (deploy/grafana/dashboards/
   * router-ws-transport.json), per `docs/process/no-dark-by-default.md`.
   */
  private def stampEtag(
      id: RouterId,
      connHousehold: HouseholdId,
      snapHousehold: HouseholdId,
      snap: PolicySnapshot,
  ): UIO[Unit] =
    if (connHousehold != snapHousehold)
      AppMetrics.recordWsEtagStamp("household_mismatch") *>
        ZIO.logWarning(
          s"router ws: not stamping last_etag for router=$id — snapshot household=$snapHousehold " +
            s"but router household=$connHousehold (#2626 fan-out is not household-scoped)",
        )
    else
      onDelivered(id, snap.etag).foldZIO(
        e =>
          AppMetrics.recordWsEtagStamp("error") *>
            ZIO.logWarning(s"router ws: last_etag stamp failed for router=$id: $e"),
        _ => AppMetrics.recordWsEtagStamp("ok"),
      )

  private def sendPolicyFrame(
      id: RouterId,
      connHousehold: HouseholdId,
      snapHousehold: HouseholdId,
      channel: WebSocketChannel,
      frame: String,
      snap: PolicySnapshot,
      deregisterOnFailure: Boolean,
  ): UIO[Unit] =
    timedSend(channel, frame).flatMap {
      case true  =>
        AppMetrics.recordWsFrame("policy", "out", "ok") *>
          AppMetrics.recordWsPolicyPush("ok") *>
          stampEtag(id, connHousehold, snapHousehold, snap)
      case false =>
        // A racing disconnect: meter the failed push and drop the dead channel. The receive loop's
        // `ensuring` also deregisters, but doing it here too keeps the gauge honest if the push
        // raced ahead of the close event.
        AppMetrics.recordWsPolicyPush("channel_closed") *>
          deregister(id, channel).when(deregisterOnFailure).unit
    }

  /**
   * #2168: time the outbound policy-push send as `router_ws_message_duration_seconds{op=policy,
   * direction=out}` — the outbound half of the per-ws-op latency. `ensuring` fires the observation
   * on every exit (ok or racing-disconnect), same as the inbound timing in [[RouterWsRoutes]].
   *
   * The timed region is exactly `channel.send` and nothing else. #2619's `last_etag` stamp is a DB
   * round-trip and deliberately sits OUTSIDE it, in [[sendPolicyFrame]] — folding it in would have
   * silently redefined this histogram from "how long the send took" to "send plus a database
   * write", which is not what any panel or alert reading it is asking.
   */
  private def timedSend(channel: WebSocketChannel, frame: String): UIO[Boolean] =
    Clock.nanoTime.flatMap { start =>
      channel
        .send(ChannelEvent.read(WebSocketFrame.text(frame)))
        .fold(_ => false, _ => true)
        .ensuring(
          Clock.nanoTime.flatMap(end =>
            AppMetrics.recordWsMessageDuration("policy", "out", (end - start) / 1e9d),
          ),
        )
    }
}
