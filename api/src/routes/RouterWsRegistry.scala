package wifihaven.api.routes

import wifihaven.api.metrics.AppMetrics
import wifihaven.api.policy.{HouseholdScoped, PolicySnapshotPublisher}
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
 * **#2619/#2630: each entry also carries the router's [[HouseholdId]]**, captured at register time
 * from the already-authenticated [[wifihaven.shared.Router]]. #2619 used it to refuse a
 * `routers.last_etag` write from another household's snapshot; #2630 makes it ROUTE. A push carries
 * a [[HouseholdScoped]] snapshot, and a channel is a recipient only if its entry's household can
 * unwrap it — which is not a filter the fan-out can skip, because the snapshot is unreadable
 * without presenting a recipient household (see [[HouseholdScoped]]).
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
   * #2630: the households with at least one connected router. [[PolicyService.reevaluate]] rebuilds
   * and pushes one snapshot per household in this set, instead of rebuilding household 1's and
   * broadcasting it to everyone. Derived from live connections, not from the households table: a
   * rebuild is only worth its cost if there is a socket to push it down.
   */
  def targetHouseholds: UIO[Set[HouseholdId]]

  /**
   * #1849: fan a freshly-changed snapshot out as one `policy` frame to every connected channel. The
   * server-side end of push-on-change: [[wifihaven.api.policy.PolicyService.reevaluate]] calls this
   * (via the [[PolicySnapshotPublisher]] seam) only when the snapshot's ETag actually moved, so a
   * change is computed once and pushed, not recomputed per poll per router (#1512). A send failure
   * (a racing disconnect) deregisters that channel and is metered `channel_closed`.
   *
   * **#2630: the fan-out is household-scoped.** `scoped` carries the household the snapshot was
   * BUILT for, and only channels registered under that household are recipients — every other
   * connected router sees nothing at all. Before this, one flat `Map[RouterId, …]` was iterated and
   * every connected channel got the frame, so a router in household B was handed (and applied)
   * household A's device names, MACs, profile names and blocked hosts. That was live on prod
   * hardware, not a code reading.
   *
   * The scoping is not an `if` inside this method: the snapshot cannot be read out of `scoped`
   * without naming a recipient household, so an unscoped fan-out does not compile.
   */
  def publishPolicy(scoped: HouseholdScoped[PolicySnapshot]): UIO[Unit]

  /**
   * #1849: push the current snapshot to a single freshly-connected channel (design §6.1 first-
   * policy-on-connect), so a router that just opened the socket gets policy immediately rather than
   * at the next change. A send failure is metered `channel_closed` (the caller's receive loop will
   * deregister on close). `household` is the household `snap` was built for; on this path that is
   * by construction the router's own (the caller reads `policy.snapshot(router.householdId)`).
   *
   * **Precondition (#2619/#2630): `id` must already be registered.** The recipient household is
   * read from the registry entry, never from the caller — that is what makes it impossible to hand
   * this the wrong household's snapshot, rather than merely inadvisable. So a push for an
   * unregistered id sends NOTHING (metered `router_ws_policy_push_total{result="unregistered"}`):
   * with no entry there is no household to unwrap against, and an unverifiable delivery is exactly
   * what #2630 was. The production caller registers first — `RouterWsRoutes` does `register *> …
   * pushPolicyTo` — and any new caller must too.
   *
   * A snapshot built for a household other than the router's own is likewise refused and metered
   * `household_mismatch`; on this path that is a caller bug, not a steady state (the caller reads
   * `policy.snapshot(router.householdId)`).
   */
  def pushPolicyTo(
      id: RouterId,
      channel: WebSocketChannel,
      scoped: HouseholdScoped[PolicySnapshot],
  ): UIO[Unit]
}

object RouterWsRegistry {

  /** One router's live connection state: the household it authenticated as, and its channel(s). */
  private[routes] final case class Conn(household: HouseholdId, channels: Set[WebSocketChannel])

  /**
   * `onDelivered` is the #2619 delivery sink: called with `(routerId, etag)` after a `policy` frame
   * has actually been sent to that router, and only when the snapshot's household matches the
   * router's own. Production wires it to `routerRepo.touchEtag` — `last_etag` ALONE, deliberately
   * NOT `touch`, which also refreshes the router-driven `last_seen_at` liveness signal (see the
   * repo method's scaladoc and [[RouterWsRegistryLive.stampEtag]]). With that, `routers.last_etag`
   * means the same thing on both transports.
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
  def publish(scoped: HouseholdScoped[PolicySnapshot]): UIO[Unit] =
    publishPolicy(scoped)

  // #2630: the households with at least one connected router — the set `PolicyService.reevaluate`
  // rebuilds a snapshot for. It is deliberately derived from live connections rather than from the
  // households table: a rebuild is only worth its cost (a snapshot build is put at ~2.5s by the
  // note at `Main.scala`, unmeasured at multi-household scale — #2635) if there is a socket to push
  // it down, and an empty registry must cost no builds at all.
  def targetHouseholds: UIO[Set[HouseholdId]] =
    state.get.map(_.valuesIterator.filter(_.channels.nonEmpty).map(_.household).toSet)

  def publishPolicy(scoped: HouseholdScoped[PolicySnapshot]): UIO[Unit] =
    state.get.flatMap { m =>
      // Recipients FIRST, payload second. `forHousehold` is the only way to a snapshot, so a router
      // in another household is not "filtered out" here — it is unreachable from this method, and
      // the unscoped version of this loop does not typecheck (#2630).
      val recipients = m.toList.flatMap { case (id, conn) =>
        scoped.forHousehold(conn.household).map(snap => (id, conn.channels, snap))
      }
      // Serialise once for the whole household, as the pre-#2630 broadcast did — and not at all
      // when this household has no connected router, which with many tenants is now the common case
      // for any given push.
      recipients.headOption.fold(ZIO.unit) { case (_, _, snap) =>
        val frame = RouterWsRegistry.policyFrameText(snap)
        ZIO.foreachDiscard(recipients) { case (id, channels, _) =>
          ZIO.foreachDiscard(channels)(ch =>
            sendPolicyFrame(id, ch, frame, snap, deregisterOnFailure = true),
          )
        }
      }
    }

  def pushPolicyTo(
      id: RouterId,
      channel: WebSocketChannel,
      scoped: HouseholdScoped[PolicySnapshot],
  ): UIO[Unit] =
    // The recipient household is read from the REGISTRY, never taken from the caller — a caller
    // that could assert its own household could assert the wrong one, which is the bug (#2630).
    state.get.map(_.get(id).map(_.household)).flatMap {
      case None     =>
        AppMetrics.recordWsPolicyPush("unregistered") *>
          ZIO.logWarning(
            s"router ws: refusing first-policy push to unregistered router=$id - no registry " +
              "entry, so the recipient household cannot be established",
          )
      case Some(hh) =>
        scoped.forHousehold(hh) match {
          case None       =>
            // A caller bug on this path, not a steady state: RouterWsRoutes reads
            // `policy.snapshot(router.householdId)`. Loud, and nothing is sent.
            AppMetrics.recordWsPolicyPush("household_mismatch") *>
              ZIO.logWarning(
                s"router ws: refusing policy push to router=$id - snapshot " +
                  s"${scoped.ownerLabel} but router household=$hh",
              )
          case Some(snap) =>
            // `deregisterOnFailure = false` preserves this path's pre-existing behaviour: the
            // caller (RouterWsRoutes' HandshakeComplete branch) has its own `ensuring` teardown,
            // and the channel was registered microseconds ago.
            sendPolicyFrame(
              id,
              channel,
              RouterWsRegistry.policyFrameText(snap),
              snap,
              deregisterOnFailure = false,
            )
        }
    }

  /**
   * #2619: stamp `routers.last_etag` with the etag this push DELIVERED.
   *
   * What the column promises: **the newest policy version the server has sent to this router**, on
   * whichever transport sent it. That is a send-time fact, not an applied-time one — identical in
   * kind to what the REST poll already recorded (it stamps when it SERVES the snapshot, before the
   * agent has parsed, let alone applied, it). Neither transport's value means "this router is
   * enforcing this policy"; the router's own applied etag is what the e2e barrier reads off disk
   * (`scripts/e2e/lib/wait.py`). The promise is documented on the read surface,
   * `RouterSummary.lastEtag`.
   *
   * The write is `onDelivered`, wired in production to `RouterRepo.touchEtag` — `last_etag` ALONE.
   * Deliberately not `touch`, which also refreshes `last_seen_at`: that column is the ROUTER-driven
   * liveness signal behind `agent_connected_routers`, and a server-initiated push must not be able
   * to hold it green for a router whose socket has gone half-open.
   *
   * **No household check here any more (#2630).** #2619 re-read the router's household at stamp
   * time and refused a mismatched write, because the fan-out above it was unscoped and would
   * otherwise have persisted household 1's etag onto another household's row. The fan-out is now
   * scoped: a frame reaches this router only by having been unwrapped against THIS router's
   * household, so by the time we get here the match is established by construction rather than
   * re-asserted. Keeping a second copy of the check would leave an unreachable branch and a metric
   * label that can never fire — the guard moved, it was not dropped
   * (`AGENTS.md#single-source-of-truth`).
   *
   * Best-effort: a DB hiccup must never tear down a push, so failures are caught — `foldCauseZIO`,
   * not `foldZIO`, because a DEFECT in the sink would otherwise kill the fiber and
   * `publishPolicy`'s `foreachDiscard` would abandon every router it had not reached yet. Caught is
   * not silent: `router_ws_etag_stamp_total{outcome}` is dashboarded
   * (deploy/grafana/dashboards/router-ws-transport.json), per `docs/process/no-dark-by-default.md`.
   */
  private def stampEtag(id: RouterId, snap: PolicySnapshot): UIO[Unit] =
    // `suspendSucceed` so a sink that throws while BUILDING its effect is caught by the same fold
    // as one that returns a failed/dying effect — otherwise it escapes back into `publishPolicy`'s
    // `foreachDiscard`, which is the failure mode this fold exists for.
    ZIO
      .suspendSucceed(onDelivered(id, snap.etag))
      .foldCauseZIO(
        c =>
          AppMetrics.recordWsEtagStamp("error") *>
            // logError, not logWarning: this is the ONLY writer of `last_etag` for a router on a
            // healthy ws link (the poll is dormant, #2037), so a failure here is the operator's
            // "who is on current policy?" view silently going stale.
            ZIO.logErrorCause(s"router ws: last_etag stamp failed for router=$id", c),
        _ => AppMetrics.recordWsEtagStamp("ok"),
      )

  private def sendPolicyFrame(
      id: RouterId,
      channel: WebSocketChannel,
      frame: String,
      snap: PolicySnapshot,
      deregisterOnFailure: Boolean,
  ): UIO[Unit] =
    timedSend(channel, frame).flatMap {
      case true  =>
        AppMetrics.recordWsFrame("policy", "out", "ok") *>
          AppMetrics.recordWsPolicyPush("ok") *>
          stampEtag(id, snap)
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
