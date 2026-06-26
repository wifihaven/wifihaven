package wifihaven.api.routes

import wifihaven.api.metrics.AppMetrics
import wifihaven.shared.UserRole
import zio.*
import zio.http.WebSocketChannel
import zio.json.ast.Json

import java.time.Instant

/**
 * #1968 (S1, SPA-websocket design `docs/design/spa-websocket.md` §1.4): the browser-facing topics a
 * connection can subscribe to. Every topic maps to an existing REST read model (§0.3) — `SpaTopic`
 * is just the discriminator the subscription set and fan-out key on, never a new data shape. The
 * wire names match the `op` the server→SPA push will carry (S3/S4).
 */
enum SpaTopic {
  case TrafficUsage, Now, ConnectionEvents, TimeStatus, AppUsage, Stale
}

object SpaTopic {

  val all: List[SpaTopic] =
    List(TrafficUsage, Now, ConnectionEvents, TimeStatus, AppUsage, Stale)

  def wire(t: SpaTopic): String = t match {
    case TrafficUsage     => "trafficUsage"
    case Now              => "now"
    case ConnectionEvents => "connectionEvents"
    case TimeStatus       => "timeStatus"
    case AppUsage         => "appUsage"
    case Stale            => "stale"
  }

  def parse(s: String): Option[SpaTopic] = s match {
    case "trafficUsage"     => Some(TrafficUsage)
    case "now"              => Some(Now)
    case "connectionEvents" => Some(ConnectionEvents)
    case "timeStatus"       => Some(TimeStatus)
    case "appUsage"         => Some(AppUsage)
    case "stale"            => Some(Stale)
    case _                  => None
  }

  /**
   * S1 placeholder role-authorization (design §4.4 — the fan-out's authz gate, refined by S2's real
   * cookie auth #1969). Per-profile time topics (`timeStatus`/`appUsage`) are visible to every
   * authenticated role; the household-wide moderation/bandwidth surfaces require an adult/admin
   * role. This is intentionally minimal — S1 only needs a real reject path to exercise the
   * subscription machinery; S2 owns the authoritative per-role visibility once a verified JWT is
   * available at upgrade.
   */
  def visibleTo(t: SpaTopic, role: UserRole): Boolean = role match {
    case UserRole.Admin | UserRole.Adult => true
    case UserRole.Child                  => t == TimeStatus || t == AppUsage
  }
}

/**
 * A monotonically-assigned per-connection id — a user may hold N tabs, so the key is the socket.
 */
final case class SpaConnId(value: Long) extends AnyVal

/**
 * Per-connection registry state (design §5.1): the live channel, the resolved [[UserRole]] (the
 * fan-out authz key, §4.4), the per-connection subscription set `{topic → params}` (§1.4 — the
 * data-minimization mechanism), and the JWT expiry deadline (captured at upgrade for the §4.3
 * mid-connection close; populated by S2). `params` is the verbatim subscribe payload's `params`
 * object — an existing endpoint's query params (§0.3), opaque to S1.
 */
final case class SpaConnState(
    channel: WebSocketChannel,
    role: UserRole,
    subscriptions: Map[SpaTopic, Json],
    jwtExp: Option[Instant],
)

/**
 * #1968: the per-connection websocket registry for the browser-facing `/api/ws` endpoint. A FORK of
 * the [[RouterWsRegistry]] pattern, NOT a generalization of it (design §5.1): the two differ on the
 * three axes a registry is — key (`SpaConnId` + role vs `RouterId`), per-channel state (a
 * subscription set + `jwtExp` vs just the channel), and fan-out vocabulary (the §1.2 op catalog,
 * subscription-gated AND role-filtered, vs one full `PolicySnapshot`). Sharing the *pattern* (a
 * `Ref[Map[K, …]]` of channels) without coupling the two payload vocabularies.
 *
 * Single-process, in-memory — the right shape for the one-API-process household model; cross-
 * instance fan-out is out of scope (design §5 note, filed as #1952).
 *
 * Every mutation refreshes the `spa_ws_connections_active{role}` and `spa_ws_subscriptions_active
 * {topic}` gauges to their recomputed live totals (across the bounded role/topic enums, so a label
 * whose count drops to zero is written 0 rather than left stale on disconnect/unsubscribe — §7).
 *
 * S1 carries no push/fan-out method yet (`now`/`connectionEvents`/`trafficUsage` pushes are S3/S4);
 * the registry holds exactly the state those later steps read. Auth is stubbed at upgrade (S2 plugs
 * the cookie verify), so `register` takes the resolved role + optional `jwtExp` directly.
 */
trait SpaWsRegistry {

  /** Record a freshly-upgraded channel with its resolved role; returns its connection id. */
  def register(channel: WebSocketChannel, role: UserRole, jwtExp: Option[Instant]): UIO[SpaConnId]

  /** Drop a closed/closing connection. Idempotent. Refreshes the gauges. */
  def deregister(id: SpaConnId): UIO[Unit]

  /**
   * Set the connection's role. The S1 stub for the `hello` test-handshake (§1.4); S2 resolves the
   * role from the verified cookie at upgrade and this becomes a no-op for the auth path.
   */
  def setRole(id: SpaConnId, role: UserRole): UIO[Unit]

  /** The connection's resolved role, if it is still registered. */
  def roleFor(id: SpaConnId): UIO[Option[UserRole]]

  /** Add/replace a topic subscription with its params (§1.4 — re-subscribe replaces the prior). */
  def subscribe(id: SpaConnId, topic: SpaTopic, params: Json): UIO[Unit]

  /** Drop a topic subscription. */
  def unsubscribe(id: SpaConnId, topic: SpaTopic): UIO[Unit]

  /**
   * The connection's current subscription set (empty if absent) — the future fan-out gate (§1.4).
   */
  def subscriptionsFor(id: SpaConnId): UIO[Map[SpaTopic, Json]]

  /** Total open channels across all connections — backs `spa_ws_connections_active`. */
  def activeCount: UIO[Int]
}

object SpaWsRegistry {

  def make: UIO[SpaWsRegistry] =
    for {
      state <- Ref.make(Map.empty[SpaConnId, SpaConnState])
      seq   <- Ref.make(0L)
    } yield new SpaWsRegistryLive(state, seq)
}

final class SpaWsRegistryLive(
    state: Ref[Map[SpaConnId, SpaConnState]],
    seq: Ref[Long],
) extends SpaWsRegistry {

  // Recompute both labelled gauges from the live map after every mutation. Both label spaces are
  // bounded enums (role: 3, topic: 6), so we write every label every time — a role/topic whose
  // count fell to zero is set 0 rather than left at a stale high-water value.
  private def publishGauges(m: Map[SpaConnId, SpaConnState]): UIO[Unit] = {
    val byRole  = m.values.groupBy(_.role).view.mapValues(_.size).toMap
    val byTopic = m.values
      .flatMap(_.subscriptions.keysIterator)
      .foldLeft(Map.empty[SpaTopic, Int])((acc, t) => acc.updated(t, acc.getOrElse(t, 0) + 1))
    ZIO.foreachDiscard(UserRole.values.toList)(r =>
      AppMetrics.setSpaWsConnectionsActive(UserRole.asString(r), byRole.getOrElse(r, 0)),
    ) *>
      ZIO.foreachDiscard(SpaTopic.all)(t =>
        AppMetrics.setSpaWsSubscriptionsActive(SpaTopic.wire(t), byTopic.getOrElse(t, 0)),
      )
  }

  def register(channel: WebSocketChannel, role: UserRole, jwtExp: Option[Instant]): UIO[SpaConnId] =
    for {
      n <- seq.updateAndGet(_ + 1)
      id = SpaConnId(n)
      m <- state.updateAndGet(_.updated(id, SpaConnState(channel, role, Map.empty, jwtExp)))
      _ <- publishGauges(m)
    } yield id

  def deregister(id: SpaConnId): UIO[Unit] =
    state.updateAndGet(_.removed(id)).flatMap(publishGauges)

  def setRole(id: SpaConnId, role: UserRole): UIO[Unit] =
    state
      .updateAndGet(m => m.get(id).fold(m)(s => m.updated(id, s.copy(role = role))))
      .flatMap(publishGauges)

  def roleFor(id: SpaConnId): UIO[Option[UserRole]] =
    state.get.map(_.get(id).map(_.role))

  def subscribe(id: SpaConnId, topic: SpaTopic, params: Json): UIO[Unit] =
    state
      .updateAndGet(m =>
        m.get(id)
          .fold(m)(s =>
            m.updated(id, s.copy(subscriptions = s.subscriptions.updated(topic, params))),
          ),
      )
      .flatMap(publishGauges)

  def unsubscribe(id: SpaConnId, topic: SpaTopic): UIO[Unit] =
    state
      .updateAndGet(m =>
        m.get(id)
          .fold(m)(s => m.updated(id, s.copy(subscriptions = s.subscriptions.removed(topic)))),
      )
      .flatMap(publishGauges)

  def subscriptionsFor(id: SpaConnId): UIO[Map[SpaTopic, Json]] =
    state.get.map(_.get(id).map(_.subscriptions).getOrElse(Map.empty))

  def activeCount: UIO[Int] =
    state.get.map(_.size)
}
