package wifihaven.api.routes

import wifihaven.api.metrics.AppMetrics
import wifihaven.shared.{QueryLog, UserRole}
import wifihaven.shared.types.{HouseholdId, ProfileId}
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
    // #1974 (S6a): the authenticated username (JWT `sub`), captured at upgrade. Needed to reuse the
    // GET's per-child profile filter for the class-(2) `timeStatus`/`appUsage` thick pushes (design
    // §4.4 — "the body is filtered per-role before send, exactly as the matching GET filters"): an
    // admin/adult sees all profiles, a child only the profiles they're linked to
    // (`UserProfileRepo.listProfilesForUsername`). `None` only for pre-auth/test registrations.
    username: Option[String] = None,
    // #2251 (multi-tenant, epic #622): the connection's household (JWT `hh` claim), captured at
    // upgrade so the class-(2) `now` push can be built + delivered PER HOUSEHOLD — an admin in
    // household B must never receive household A's devices/profiles in the live NOW push (the sibling
    // of the GET leak, closes #2120). Defaults to the single backfill household so pre-multi-tenant /
    // test registrations stay tenant-safe.
    household: HouseholdId = HouseholdId.Default,
)

/**
 * #1974 (S6a): a connection eligible to receive a class-(2) `timeStatus`/`appUsage` push — it
 * SUBSCRIBED to the topic AND its role may see it (§4.4). Carries the identity the per-recipient
 * GET filter needs (`role` + `username`) and the subscription `params` (e.g. `appUsage`'s
 * `profileId`). [[SpaPush]] resolves each recipient's visible-profile set and delivers the filtered
 * body.
 */
final case class SpaRecipient(
    id: SpaConnId,
    role: UserRole,
    username: Option[String],
    params: Json,
    // #2251: the recipient's household, so the `now` push can group recipients by household and
    // build one household-scoped body per household (never the global set).
    household: HouseholdId = HouseholdId.Default,
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

  /**
   * Record a freshly-upgraded channel with its resolved role + (S6a) authenticated username;
   * returns its connection id. The username is the JWT `sub`, captured so the class-(2)
   * `timeStatus`/ `appUsage` pushes can reuse the GET's per-child profile filter (design §4.4).
   */
  def register(
      channel: WebSocketChannel,
      role: UserRole,
      jwtExp: Option[Instant],
      username: Option[String] = None,
      household: HouseholdId = HouseholdId.Default,
  ): UIO[SpaConnId]

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
   * #1970 (S3): append new `connectionEvents` head rows (class-(1) live edge). For each connection
   * subscribed to `ConnectionEvents` and authorized, the rows are filtered by THAT connection's
   * subscription params (the verbatim `/api/logs` filter — `blocked`/`macs`/`profileIds`/`domain`,
   * §1.4); a frame is sent only if ≥1 row matches, so an ingest that doesn't match a subscriber's
   * filter pushes nothing to it. Metered per send like [[deliver]].
   */
  def fanOutConnectionEvents(rows: List[QueryLog]): UIO[Unit]

  /**
   * #1970 (S3): fan a contentless class-(3) `stale{topic, scope?}` nudge to every connection
   * subscribed to `Stale` and authorized (§3.2 — the client invalidates the mapped query). Metered
   * `spa_ws_push_total{op="stale", ...}`.
   */
  def fanOutStale(topic: StaleTopic, scope: Option[String]): UIO[Unit]

  /**
   * #1971 (S4): the DISTINCT `trafficUsage` subscription param-sets across every connection that
   * BOTH subscribed to `TrafficUsage` AND whose role may see it (§4.4 — `SpaTopic.visibleTo`). The
   * [[SpaPush]] aggregator recomputes the head bucket ONCE per distinct param-set (design §5.3); an
   * empty result means no subscriber, so no query runs ("don't compute what nobody watches"). The
   * params are the verbatim `GET /api/usage/traffic` query params (§1.4 / §0.3), opaque here.
   */
  def trafficUsageParamSets: UIO[Set[Json]]

  /**
   * #1971 (S4): push the live-edge `TrafficUsageResponse` computed for `params` to every connection
   * whose `TrafficUsage` subscription params EQUAL `params` and whose role may see it (§4.4). The
   * body is built ONCE by the caller ([[SpaPush]], via the shared `UsageTrafficQuery.aggregate` —
   * the same query the `GET` runs, so the stream and the page can't disagree); `TrafficUsage` is
   * visible only to admin/adult (full-visibility roles, exactly as the GET treats them), so one
   * body is correct for every recipient and the role gate is the per-role filter. Latest-wins per
   * param-set (design §6.3): a re-push carries the freshest head, never a backlog. Metered per send
   * like [[deliver]].
   *
   * NOTE (#2251): unlike the `now` push, this body is NOT yet household-scoped — a follow-up must
   * group `trafficUsage`/`timeStatus`/`appUsage` recipients by household the same way
   * `nowRecipients` does — tracked by #2257. `now` was the reported leak (#2120) and is fixed here.
   */
  def fanOutTrafficUsage(params: Json, payload: Json): UIO[Unit]

  /**
   * #1974 (S6a): the connections eligible for a `timeStatus` push — subscribed to `TimeStatus` AND
   * role-visible (§4.4). Each carries the `(role, username)` the per-child GET filter needs;
   * [[SpaPush]] builds the full `ProfileTimeStatus[]` once, then delivers each recipient its
   * role-visible subset (admin/adult: all; child: their linked profiles). Empty ⇒ no subscriber ⇒
   * no `dayStateAll` query ("don't compute what nobody watches").
   */
  def timeStatusRecipients: UIO[List[SpaRecipient]]

  /**
   * #2251 (multi-tenant, epic #622): the connections eligible for a `now` push — subscribed to
   * `Now` AND role-visible (§4.4; `now` is admin/adult-only). Each carries its `household` so
   * [[SpaPush]] can build ONE household-scoped `DashboardNow` per DISTINCT household and deliver it
   * only to that household's recipients — the live NOW push must never carry another household's
   * entities (closes #2120). Replaces the old global `fanOut(Now, …)` broadcast, which built one
   * body over EVERY household's devices/profiles and sent it to all subscribers.
   */
  def nowRecipients: UIO[List[SpaRecipient]]

  /**
   * #1974 (S6a): the connections eligible for an `appUsage` push — subscribed to `AppUsage` AND
   * role-visible (§4.4). Each recipient's `params` carries the subscribed `{profileId}` (the
   * expanded card). [[SpaPush]] builds the per-app body once per DISTINCT entitled `profileId` and
   * delivers it to the matching recipients. Empty ⇒ no subscriber ⇒ no query.
   */
  def appUsageRecipients: UIO[List[SpaRecipient]]

  /**
   * #1974 (S6a): deliver a pre-built class-(2) frame to ONE connection (the per-recipient body the
   * `timeStatus`/`appUsage` thick pushes need — each recipient may receive a differently-filtered
   * body, so the fan-out can't share one frame). Looks up the live channel; a no-longer-registered
   * id is a no-op. Metered exactly like the other pushes (`spa_ws_push_total{op,result}` + frames-
   * out on success); a send failure (racing disconnect) meters `channel_closed` and deregisters.
   * The caller is responsible for the subscription + role gate (it got `id` from
   * [[timeStatusRecipients]]/[[appUsageRecipients]]).
   */
  def deliver(id: SpaConnId, op: String, payload: Json): UIO[Unit]
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

  def register(
      channel: WebSocketChannel,
      role: UserRole,
      jwtExp: Option[Instant],
      username: Option[String] = None,
      household: HouseholdId = HouseholdId.Default,
  ): UIO[SpaConnId] =
    for {
      n <- seq.updateAndGet(_ + 1)
      id = SpaConnId(n)
      m <- state.updateAndGet(
        _.updated(id, SpaConnState(channel, role, Map.empty, jwtExp, username, household)),
      )
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

  def trafficUsageParamSets: UIO[Set[Json]] =
    state.get.map(
      _.values.iterator
        .collect {
          case s
              if s.subscriptions.contains(SpaTopic.TrafficUsage) &&
                SpaTopic.visibleTo(SpaTopic.TrafficUsage, s.role) =>
            s.subscriptions(SpaTopic.TrafficUsage)
        }
        .toSet,
    )

  def fanOutTrafficUsage(params: Json, payload: Json): UIO[Unit] =
    state.get.flatMap { m =>
      val op    = SpaTopic.wire(SpaTopic.TrafficUsage)
      val frame = frameText(op, payload.toJson)
      ZIO.foreachDiscard(m.toList) { case (id, s) =>
        ZIO.when(
          s.subscriptions.get(SpaTopic.TrafficUsage).contains(params) &&
            SpaTopic.visibleTo(SpaTopic.TrafficUsage, s.role),
        )(sendPush(id, s.channel, op, frame))
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

  // ── #1974 (S6a) timeStatus / appUsage recipients + per-recipient delivery ──────────────────────
  // These class-(2) thick pushes filter the body PER recipient (admin/adult see all profiles, a
  // child only their linked ones — design §4.4), so unlike `now`/`trafficUsage` the fan-out can't
  // share one frame. The registry exposes the gated recipient list (subscription AND role) plus a
  // per-connection `deliver`; SpaPush owns the body build + the userProfileRepo-backed filter.

  def timeStatusRecipients: UIO[List[SpaRecipient]] =
    recipientsFor(SpaTopic.TimeStatus)

  def nowRecipients: UIO[List[SpaRecipient]] =
    recipientsFor(SpaTopic.Now)

  def appUsageRecipients: UIO[List[SpaRecipient]] =
    recipientsFor(SpaTopic.AppUsage)

  private def recipientsFor(topic: SpaTopic): UIO[List[SpaRecipient]] =
    state.get.map(
      _.iterator
        .collect {
          case (id, s) if s.subscriptions.contains(topic) && SpaTopic.visibleTo(topic, s.role) =>
            SpaRecipient(id, s.role, s.username, s.subscriptions(topic), s.household)
        }
        .toList,
    )

  def deliver(id: SpaConnId, op: String, payload: Json): UIO[Unit] =
    state.get.flatMap { m =>
      m.get(id) match {
        case Some(s) => sendPush(id, s.channel, op, frameText(op, payload.toJson))
        case None    => ZIO.unit // raced a disconnect — nothing to deliver to
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
    // #2168: time the outbound change-source push send as spa_ws_message_duration_seconds{op,
    // direction=out} — the outbound half of the SPA per-ws-op latency. `ensuring` fires the
    // observation on every exit (ok or racing-disconnect), same as the inbound timing in SpaWsRoutes.
    Clock.nanoTime.flatMap { start =>
      channel
        .send(ChannelEvent.read(WebSocketFrame.text(frame)))
        .foldZIO(
          _ => AppMetrics.recordSpaWsPush(op, "channel_closed") *> deregister(id),
          _ =>
            AppMetrics.recordSpaWsPush(op, "ok") *>
              AppMetrics.recordSpaWsFrame(op, "out", "ok"),
        )
        .ensuring(
          Clock.nanoTime.flatMap(end =>
            AppMetrics.recordSpaWsMessageDuration(op, "out", (end - start) / 1e9d),
          ),
        )
    }
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
