package wifihaven.api.routes

import wifihaven.api.metrics.AppMetrics
import wifihaven.shared.{QueryLog, UserRole}
import wifihaven.shared.types.ProfileId
import zio.*
import zio.http.{ChannelEvent, WebSocketChannel, WebSocketFrame}
import zio.json.*
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

  /**
   * #1970 (S3): fan a class-(2) thick push (`now`) out to every connection that BOTH subscribed to
   * `topic` (§1.4) AND whose role may see it (§4.4 — `SpaTopic.visibleTo`). The body is built ONCE
   * by the caller ([[SpaPush]], via the shared `DashboardNowRoutes.computeNow` builder) and sent
   * verbatim; the role gate is the per-role filter (the topics pushed this way — `now` — are
   * visible only to roles the matching GET shows everything to, so one body is correct for all
   * recipients). A send failure (racing disconnect) is metered
   * `spa_ws_push_total{result="channel_closed"}` and deregisters the dead channel; a success is
   * metered `result="ok"`.
   */
  def fanOut(topic: SpaTopic, payload: Json): UIO[Unit]

  /**
   * #1970 (S3): append new `connectionEvents` head rows (class-(1) live edge). For each connection
   * subscribed to `ConnectionEvents` and authorized, the rows are filtered by THAT connection's
   * subscription params (the verbatim `/api/logs` filter — `blocked`/`macs`/`profileIds`/`domain`,
   * §1.4); a frame is sent only if ≥1 row matches, so an ingest that doesn't match a subscriber's
   * filter pushes nothing to it. Metered per send like [[fanOut]].
   */
  def fanOutConnectionEvents(rows: List[QueryLog]): UIO[Unit]

  /**
   * #1970 (S3): fan a contentless class-(3) `stale{topic, scope?}` nudge to every connection
   * subscribed to `Stale` and authorized (§3.2 — the client invalidates the mapped query). Metered
   * `spa_ws_push_total{op="stale", ...}`.
   */
  def fanOutStale(topic: StaleTopic, scope: Option[String]): UIO[Unit]
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

  // ── #1970 (S3) change-source fan-out ────────────────────────────────────────────────────────
  // Each fan-out snapshots the connection map once and sends to the gated subset. The two gates are
  // independent (design §5.1): a connection receives a topic only if it SUBSCRIBED to it (§1.4) AND
  // its role is AUTHORIZED for it (§4.4 — `SpaTopic.visibleTo`). A send failure means the channel
  // raced a disconnect: meter `channel_closed` and drop it (the receive loop's `ensuring` also
  // deregisters; doing it here keeps the gauges honest if the push won the race).

  def fanOut(topic: SpaTopic, payload: Json): UIO[Unit] =
    state.get.flatMap { m =>
      val op    = SpaTopic.wire(topic)
      val frame = frameText(op, payload.toJson)
      ZIO.foreachDiscard(m.toList) { case (id, s) =>
        ZIO.when(s.subscriptions.contains(topic) && SpaTopic.visibleTo(topic, s.role))(
          sendPush(id, s.channel, op, frame),
        )
      }
    }

  def fanOutConnectionEvents(rows: List[QueryLog]): UIO[Unit] =
    state.get.flatMap { m =>
      val op = SpaTopic.wire(SpaTopic.ConnectionEvents)
      ZIO.foreachDiscard(m.toList) { case (id, s) =>
        val gated =
          s.subscriptions.get(SpaTopic.ConnectionEvents).filter { _ =>
            SpaTopic.visibleTo(SpaTopic.ConnectionEvents, s.role)
          }
        gated match {
          case None         => ZIO.unit
          case Some(params) =>
            val filter  = LogSubParams.decode(params)
            val matched = rows.filter(filter.matches)
            ZIO.when(matched.nonEmpty)(
              sendPush(id, s.channel, op, frameText(op, matched.toJson)),
            )
        }
      }
    }

  def fanOutStale(topic: StaleTopic, scope: Option[String]): UIO[Unit] =
    state.get.flatMap { m =>
      val op      = SpaTopic.wire(SpaTopic.Stale)
      val payload = scope match {
        case Some(sc) => s"""{"topic":"${StaleTopic.wire(topic)}","scope":${Json.Str(sc).toJson}}"""
        case None     => s"""{"topic":"${StaleTopic.wire(topic)}"}"""
      }
      val frame   = frameText(op, payload)
      ZIO.foreachDiscard(m.toList) { case (id, s) =>
        ZIO.when(
          s.subscriptions.contains(SpaTopic.Stale) && SpaTopic.visibleTo(SpaTopic.Stale, s.role),
        )(
          sendPush(id, s.channel, op, frame),
        )
      }
    }

  /** The `{op, payload}` push envelope (design §2.2), payload already serialized. */
  private def frameText(op: String, payloadJson: String): String =
    s"""{"op":"$op","payload":$payloadJson}"""

  private def sendPush(
      id: SpaConnId,
      channel: WebSocketChannel,
      op: String,
      frame: String,
  ): UIO[Unit] =
    channel
      .send(ChannelEvent.read(WebSocketFrame.text(frame)))
      .foldZIO(
        _ => AppMetrics.recordSpaWsPush(op, "channel_closed") *> deregister(id),
        _ =>
          AppMetrics.recordSpaWsPush(op, "ok") *>
            AppMetrics.recordSpaWsFrame(op, "out", "ok"),
      )
}

/**
 * #1970 (S3): the decoded `connectionEvents` subscription params — the verbatim `/api/logs` filter
 * vocabulary (§1.4 / §0.3). Each field is an OPTIONAL narrowing; an absent field matches
 * everything, so the dashboard's `{blocked:true}` keeps only blocked rows while the Connection
 * Events page's richer filter narrows by mac/profile/domain too. Unknown params are ignored
 * (forward-compat).
 */
private final case class LogSubParams(
    blocked: Option[Boolean] = None,
    macs: Option[List[String]] = None,
    profileIds: Option[List[ProfileId]] = None,
    domain: Option[String] = None,
) derives JsonDecoder {

  /**
   * True iff `row` passes every present narrowing (an absent field matches everything). This is the
   * in-memory twin of the `/api/logs` SQL filter in `ConnectionEventRepo.query` — the fan-out can't
   * round-trip SQL per subscriber, so the predicate is duplicated, an ACCEPT-divergence (no single
   * collapse point). It MUST stay byte-aligned with that SQL's clauses:
   *   - `blocked` ↔ `ce.allowed = !b` (QueryLog.blocked = NOT allowed)
   *   - `macs` ↔ `ce.mac IN (...)` (exact match on the mac string)
   *   - `profileIds` ↔ `d.profile_id IN (...)` (exact match on the joined profile)
   *   - `domain` ↔ `COALESCE(resolved_host_value, host_value) ILIKE '%d%'` (case-insensitive
   *     substring; `QueryLog.host` is already the coalesced resolved-or-host value, so matching on
   *     `row.host.value` is equivalent).
   * If the SQL filter grows a clause, mirror it here (and extend `SpaWsS3Spec`'s coverage).
   */
  def matches(row: QueryLog): Boolean =
    blocked.forall(_ == row.blocked) &&
      macs.forall(ms => ms.isEmpty || row.mac.exists(m => ms.contains(m.value))) &&
      profileIds.forall(ps => ps.isEmpty || row.profileId.exists(ps.contains)) &&
      domain.forall(d => row.host.value.toLowerCase.contains(d.toLowerCase))
}

private object LogSubParams {

  /** Decode a subscribe-params blob; a missing/garbage blob is the match-everything default. */
  def decode(params: Json): LogSubParams =
    params.toJson.fromJson[LogSubParams].getOrElse(LogSubParams())
}
