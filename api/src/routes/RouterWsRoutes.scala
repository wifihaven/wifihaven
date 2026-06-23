package wifihaven.api.routes

import wifihaven.api.db.RouterRepo
import wifihaven.api.metrics.{AppMetrics, RouterMetricsService}
import wifihaven.api.observability.LogContext
import wifihaven.shared.{Router, RouterMetricsBatch}
import zio.*
import zio.http.*
import zio.http.ChannelEvent.UserEvent
import zio.json.*
import zio.json.ast.Json

/**
 * #1846 + #1847: the persistent-websocket router transport, `GET /api/router/ws`. Sub-issue A
 * (#1846) is the SERVER endpoint + `{op, payload}` envelope demux + per-router connection registry;
 * sub-issue B (#1847) adds the `hello`→`ready` capability handshake + `snapshotVersion` negotiation
 * layered on top
 * ([`docs/design/websocket-transport.md`](../../../docs/design/websocket-transport.md) §2, see
 * [[handleHello]]). It is purely additive: the REST ingest/poll/metrics endpoints stay fully live
 * (design §3), and a router may use either transport. The agent ws client (#1848) and policy
 * push-on-change / first-policy-on-connect (#1849) build on this and are out of scope here.
 *
 * Auth (design §4.1): the upgrade request carries the existing per-router bearer token; we call the
 * SAME [[RouterAuth.authenticate]] the REST routes use, BEFORE completing the upgrade. A
 * bad/missing token → the upgrade is rejected with the usual 401 Response (no 101) — no second auth
 * surface (`AGENTS.md#single-source-of-truth`). The resolved [[Router]] is captured once at upgrade
 * and carried for the connection's lifetime; every ingest is dispatched under it.
 *
 * Demux (design §1.2/§1.3): each frame is one JSON text message `{op, payload, seq?}`. `op` selects
 * the logical channel and we dispatch into the SAME [[RouterIngestService]] /
 * [[RouterMetricsService]] the REST routes call — there is no second copy of ingest logic. An
 * unrecognized `op` is ignored + metered (`unknown_op`), the forward-compat rule that lets a future
 * op ship without a flag day.
 *
 * Handshake (design §2, #1847): the agent sends `hello` (its capability set + max-known
 * `snapshotVersion`) and the server replies `ready` (its capability set + the negotiated
 * `snapshotVersion = min(agent.maxKnown, server.maxKnown)`). Refusal paths: no `hello` within
 * `helloTimeout` → close `4002 hello-required`; a `hello` below the server's version floor → close
 * `4003 version-exceeded`. Still out of scope here: the first-policy-on-connect push and
 * push-on-change (#1849), and the agent ws client (the Lua client is #1848). The endpoint is
 * exercisable by a test ws client today.
 */
object RouterWsRoutes {

  // #1847 capability-handshake constants (design §2). The server's max-known and minimum-understood
  // `PolicySnapshot` shape versions: both 1 today (the only shape that has ever shipped). The
  // negotiated version is `min(agent.maxKnown, ServerSnapshotVersion)`; if that drops below
  // `ServerMinSnapshotVersion` there is no shape both ends speak and we refuse (close 4003). These
  // bump only on a breaking snapshot-shape change (sub-issue F / #376) — not in this issue.
  private val ServerSnapshotVersion    = 1
  private val ServerMinSnapshotVersion = 1

  // Server-advertised capability set echoed in `ready`. v1 names only the features the server
  // actually implements today: the transport itself and the per-data-frame `ack`s (#1846). The set
  // is the EXTENSION POINT (design §2.2) — `policy-push` (#1849), diffs, compression are added here
  // when those features land, never inferred. Agents intersect it on their side; the server emits
  // its own full set.
  private val ServerCapabilities: List[String] = List("ws-transport-v1", "ack-frames")

  // Default time the server waits after upgrade for the agent's `hello` before closing 4002 (§2.2).
  // Overridable on `routes` so tests can drive the timeout path without a real 5 s sleep.
  val DefaultHelloTimeout: Duration = 5.seconds

  def routes(
      auth: RouterAuth,
      registry: RouterWsRegistry,
      ingest: RouterIngestService,
      metricsSvc: RouterMetricsService,
      routerRepo: RouterRepo,
      helloTimeout: Duration = DefaultHelloTimeout,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "router" / "ws" ->
        handler { (req: Request) =>
          // Auth at upgrade time. On failure, return the SAME 401 Response the REST routes produce
          // (no 101) — the agent treats a ws-401 exactly like a REST-401 (drop, do not hammer).
          auth
            .authenticate(req)
            .foldZIO(
              // A rejected upgrade is the `auth_fail` handshake outcome (#1847, §7): meter it, then
              // return the SAME 401 the REST routes produce.
              err => AppMetrics.recordWsHandshake("auth_fail").as(ErrorMapper.errorToResponse(err)),
              router =>
                socketApp(
                  router,
                  registry,
                  ingest,
                  metricsSvc,
                  routerRepo,
                  helloTimeout,
                ).toResponse,
            )
        },
    )

  private def socketApp(
      router: Router,
      registry: RouterWsRegistry,
      ingest: RouterIngestService,
      metricsSvc: RouterMetricsService,
      routerRepo: RouterRepo,
      helloTimeout: Duration,
  ): WebSocketApp[Any] =
    Handler.webSocket { channel =>
      // Per-connection handshake state: flipped true once a `hello` is received (design §2.2), so
      // the hello-timeout watcher can stand down. Re-deliveries of `hello` on the same connection
      // are harmless idempotent re-acks.
      Ref.make(false).flatMap { helloDone =>
        // Hello-timeout watcher: if no `hello` arrives within HelloTimeout, close 4002 hello-required
        // and meter the outcome (§2.2). Forked daemon so it runs alongside the receive loop;
        // interrupted in `ensuring` on connection close so it can never leak past the socket.
        val startWatcher =
          (ZIO.sleep(helloTimeout) *> ZIO
            .unlessZIO(helloDone.get) {
              AppMetrics.recordWsHandshake("hello_timeout") *>
                ZIO.logWarning(
                  s"router ws: no hello within $helloTimeout router=${router.id}, closing 4002",
                ) *>
                closeWith(channel, 4002, "hello-required")
            }
            .unit).forkDaemon

        startWatcher.flatMap { watcher =>
          channel
            .receiveAll {
              case ChannelEvent.UserEventTriggered(UserEvent.HandshakeComplete) =>
                registry.register(router.id, channel) *>
                  ZIO.logInfo(s"router ws: connected router=${router.id}")
              case ChannelEvent.Read(WebSocketFrame.Text(text))                 =>
                // A dispatch failure (e.g. a send error) tears this frame's handling down; log the
                // cause so a transport-level fault is debuggable, then let it surface (the socket
                // closes and the agent reconnects per the design's reconnect-is-the-throttle rule).
                dispatch(router, channel, text, helloDone, ingest, metricsSvc, routerRepo)
                  .tapErrorCause(c =>
                    ZIO.logErrorCause(s"router ws: dispatch failed router=${router.id}", c),
                  )
              case ChannelEvent.Unregistered                                    =>
                registry.deregister(router.id, channel)
              case _                                                            =>
                ZIO.unit
            }
            .ensuring(
              watcher.interrupt *>
                registry.deregister(router.id, channel) *>
                ZIO.logInfo(s"router ws: disconnected router=${router.id}"),
            )
        }
      }
    }

  /**
   * Demux one inbound text frame. A frame that does not parse as the envelope, or carries an
   * unrecognized `op`, is metered + logged and otherwise ignored (forward-compat, design §1.3).
   * Data ops (`usage`/`events`/`metrics`) run through the shared ingest services and reply with an
   * `ack` frame (`ok`/`reject`). `ping` is answered with `pong`. `hello` runs the #1847 capability
   * handshake ([[handleHello]]) — reply `ready` or refuse. Every recognized frame touches
   * `routers.last_seen_at` for uniform liveness with the REST path (design §3.1/§5.5).
   */
  private def dispatch(
      router: Router,
      channel: WebSocketChannel,
      text: String,
      helloDone: Ref[Boolean],
      ingest: RouterIngestService,
      metricsSvc: RouterMetricsService,
      routerRepo: RouterRepo,
  ): ZIO[Any, Throwable, Unit] =
    text.fromJson[WsFrame] match {
      case Left(err)    =>
        AppMetrics.recordWsFrame("unknown", "in", "reject") *>
          ZIO.logWarning(s"router ws: undecodable frame from router=${router.id}: $err")
      case Right(frame) =>
        LogContext.annotate(LogContext.RouterId, router.id.toString) {
          frame.op match {
            case "usage"   =>
              ingestFrame("usage", frame, channel, payload => ingest.ingestUsage(router, payload))
            case "events"  =>
              ingestFrame("events", frame, channel, payload => ingest.ingestEvents(router, payload))
            case "metrics" =>
              ingestFrame(
                "metrics",
                frame,
                channel,
                payload => ingestMetrics(router, payload, metricsSvc),
              )
            case "ping"    =>
              touch(routerRepo, router) *>
                AppMetrics.recordWsFrame("ping", "in", "ok") *>
                send(channel, WsFrame("pong", Some(Json.Obj()))) *>
                AppMetrics.recordWsFrame("pong", "out", "ok")
            case "pong"    =>
              touch(routerRepo, router) *> AppMetrics.recordWsFrame("pong", "in", "ok")
            case "hello"   =>
              handleHello(router, channel, frame, helloDone, routerRepo)
            case other     =>
              // Forward-compat: ignore + meter an unrecognized op (design §1.3) so a future op
              // (e.g. policy_diff) can ship without a flag day. The arbitrary op string is collapsed
              // to the bounded literal `unknown` for the metric label (an attacker-supplied op must
              // never become a Prometheus label value — cardinality firewall); the real op goes to
              // the log only.
              AppMetrics.recordWsFrame("unknown", "in", "unknown_op") *>
                ZIO.logDebug(s"router ws: ignoring unknown op '$other' from router=${router.id}")
          }
        }
    }

  /**
   * #1847 capability handshake (design §2.2). The agent's `hello` advertises its capability set,
   * its max-known `snapshotVersion`, and (observability only) its agent version. The server replies
   * `ready` with ITS capability set and the negotiated `snapshotVersion = min(agent.maxKnown,
   * ServerSnapshotVersion)` — so a future agent that knows a higher version is handed today's v1
   * shape, and the next breaking shape change is a version bump rather than a flag day (the durable
   * #376 slice). If the negotiated version falls below the server's floor (an agent that doesn't
   * even understand v1 — `snapshotVersion < 1`, or a malformed/absent payload that decodes to
   * version 0) there is no common shape: we close `4003 version-exceeded` so the agent falls back
   * to its last-good cached snapshot (`docs/resilience.md §1`). Either outcome marks `helloDone` so
   * the hello-timeout watcher (§2.2) stands down — a `hello` DID arrive.
   *
   * The capability sets are string sets, intersected on each side to gate optional behavior; v1 has
   * no behavior gated on it yet (it is purely the extension point), so we only log the negotiated
   * intersection for observability. Unknown fields in the payload are ignored (forward-compat) —
   * the `WsHello` decoder simply doesn't bind them.
   */
  private def handleHello(
      router: Router,
      channel: WebSocketChannel,
      frame: WsFrame,
      helloDone: Ref[Boolean],
      routerRepo: RouterRepo,
  ): ZIO[Any, Throwable, Unit] = {
    // A `hello` whose payload is absent or undecodable (missing the required `snapshotVersion`)
    // decodes to version 0, which is below the floor → the version-exceeded path closes it cleanly
    // rather than the server guessing a shape.
    val hello        = frame.payload.flatMap(_.toString.fromJson[WsHello].toOption)
    val agentVersion = hello.map(_.snapshotVersion).getOrElse(0)
    val agentCaps    = hello.map(_.agentCapabilities.toSet).getOrElse(Set.empty[String])
    val negotiated   = math.min(agentVersion, ServerSnapshotVersion)

    helloDone.set(true) *>
      touch(routerRepo, router) *>
      AppMetrics.recordWsFrame("hello", "in", "ok") *> {
        if negotiated < ServerMinSnapshotVersion then
          AppMetrics.recordWsHandshake("version_exceeded") *>
            ZIO.logWarning(
              s"router ws: hello router=${router.id} snapshotVersion=$agentVersion below floor " +
                s"$ServerMinSnapshotVersion, closing 4003",
            ) *>
            closeWith(channel, 4003, "version-exceeded")
        else
          send(
            channel,
            WsFrame(
              "ready",
              Some(WsReady(ServerCapabilities, negotiated).toJsonAST.getOrElse(Json.Obj())),
            ),
          ) *>
            AppMetrics.recordWsFrame("ready", "out", "ok") *>
            AppMetrics.recordWsHandshake("ok") *>
            ZIO.logInfo(
              s"router ws: ready router=${router.id} snapshotVersion=$negotiated " +
                s"caps=${agentCaps.intersect(ServerCapabilities.toSet)}",
            )
      }
  }

  /**
   * Send a Close frame with an application close code (§2.2: 4002 hello-required, 4003
   * version-exceeded).
   */
  private def closeWith(
      channel: WebSocketChannel,
      code: Int,
      reason: String,
  ): ZIO[Any, Throwable, Unit] =
    channel.send(ChannelEvent.read(WebSocketFrame.close(code, Some(reason))))

  /**
   * Run a data-frame payload through `ingestF` (one of the shared ingest services) and reply with
   * an `ack` frame. On a typed [[ApiError]] we ack `reject` with a short reason (mirroring the REST
   * 4xx/5xx the same body would have produced) rather than tearing the socket down — the agent
   * drops the frame on a reject exactly as it drops a 4xx POST.
   */
  private def ingestFrame(
      op: String,
      frame: WsFrame,
      channel: WebSocketChannel,
      ingestF: String => IO[ApiError, Unit],
  ): ZIO[Any, Throwable, Unit] = {
    val payload = frame.payload.map(_.toString).getOrElse("")
    ingestF(payload).foldZIO(
      err =>
        AppMetrics.recordWsFrame(op, "in", "reject") *>
          ZIO.logWarning(s"router ws: $op frame rejected: ${rejectReason(err)}") *>
          sendAck(channel, op, frame.seq, "reject", Some(rejectReason(err))),
      _ =>
        AppMetrics.recordWsFrame(op, "in", "ok") *>
          sendAck(channel, op, frame.seq, "ok", None),
    )
  }

  /**
   * Decode + ingest a `metrics` frame payload. Mirrors `POST /api/router/metrics`
   * ([[RouterMetricsRoutes]]): a malformed batch or a `routerId` that doesn't match the token's
   * router is a typed error (so the ws path acks `reject`); a good batch folds through the shared
   * [[RouterMetricsService]].
   */
  private def ingestMetrics(
      router: Router,
      payload: String,
      metricsSvc: RouterMetricsService,
  ): IO[ApiError, Unit] =
    for {
      batch <- ZIO
        .fromEither(payload.fromJson[RouterMetricsBatch])
        .mapError(ApiError.DecodeFailure(_))
        .tapError(_ => AppMetrics.recordRouterMetricsBatch("malformed"))
      _     <- (AppMetrics.recordRouterMetricsBatch("router_mismatch") *>
        ZIO.fail(ApiError.BadRequest("router_id mismatch")))
        .when(batch.routerId != router.id)
      _     <- metricsSvc.ingest(batch)
      _     <- AppMetrics.recordRouterMetricsBatch("ok")
    } yield ()

  private def touch(routerRepo: RouterRepo, router: Router): UIO[Unit] =
    routerRepo
      .touch(router.id, None, None)
      .catchAll(e =>
        ZIO.logWarning(s"router ws: last_seen touch failed for router=${router.id}: $e"),
      )
      .unit

  private def send(channel: WebSocketChannel, frame: WsFrame): ZIO[Any, Throwable, Unit] =
    channel.send(ChannelEvent.read(WebSocketFrame.text(frame.toJson)))

  private def sendAck(
      channel: WebSocketChannel,
      op: String,
      seq: Option[Int],
      status: String,
      reason: Option[String],
  ): ZIO[Any, Throwable, Unit] =
    send(
      channel,
      WsFrame("ack", Some(WsAck(op, seq, status, reason).toJsonAST.getOrElse(Json.Obj()))),
    ) *>
      AppMetrics.recordWsFrame("ack", "out", status match { case "ok" => "ok"; case _ => "reject" })

  /** Short, bounded reason string for an ack reject — never the raw (potentially large) body. */
  private def rejectReason(err: ApiError): String =
    err match {
      case ApiError.BadRequest(m)    => s"bad_request: $m".take(200)
      case ApiError.DecodeFailure(m) => s"decode_error: $m".take(200)
      case ApiError.Unauthorized(_)  => "unauthorized"
      case ApiError.Forbidden(_)     => "forbidden"
      case ApiError.NotFound(_)      => "not_found"
      case ApiError.Db(_)            => "db_error"
      case ApiError.Internal(_)      => "internal"
      case ApiError.Wrapped(_)       => "rejected"
    }

  /**
   * The `{op, payload, seq}` frame envelope (design §1.2). `payload`/`seq` optional (e.g. ping).
   */
  private case class WsFrame(op: String, payload: Option[Json] = None, seq: Option[Int] = None)
      derives JsonCodec

  /**
   * The `ack` frame payload (design §1.3): which data frame, its seq, ok/reject, optional reason.
   */
  private case class WsAck(op: String, seq: Option[Int], status: String, reason: Option[String])
      derives JsonCodec

  /**
   * The `hello` frame payload (design §2.2), agent→server. `snapshotVersion` is the agent's
   * max-known `PolicySnapshot` shape version (required). `agentCapabilities` defaults to empty and
   * `agentVersion` is optional (observability only) so a terse/older `hello` still decodes. Unknown
   * fields are ignored (forward-compat — zio-json drops extras).
   */
  private case class WsHello(
      snapshotVersion: Int,
      agentCapabilities: List[String] = Nil,
      agentVersion: Option[String] = None,
  ) derives JsonCodec

  /**
   * The `ready` frame payload (design §2.2), server→agent. Carries the server's own capability set
   * and the negotiated `snapshotVersion = min(agent.maxKnown, ServerSnapshotVersion)`.
   */
  private case class WsReady(serverCapabilities: List[String], snapshotVersion: Int)
      derives JsonCodec
}
