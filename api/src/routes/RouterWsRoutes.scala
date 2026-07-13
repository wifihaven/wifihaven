package wifihaven.api.routes

import wifihaven.api.db.RouterRepo
import wifihaven.api.metrics.{AppMetrics, RouterMetricsService}
import wifihaven.api.observability.LogContext
import wifihaven.api.policy.PolicyService
import wifihaven.shared.{Router, RouterMetricsBatch}
import zio.*
import zio.http.*
import zio.http.ChannelEvent.UserEvent
import zio.json.*
import zio.json.ast.Json

/**
 * #1846: the persistent-websocket router transport, `GET /api/router/ws`. This is sub-issue A of
 * the websocket-transport epic
 * ([`docs/design/websocket-transport.md`](../../../docs/design/websocket-transport.md)) — the
 * SERVER endpoint + `{op, payload}` envelope demux + per-router connection registry. It is purely
 * additive: the REST ingest/poll/metrics endpoints stay fully live (design §3), and a router may
 * use either transport. Capability negotiation (`hello`/`ready`, #1847), the agent ws client
 * (#1848), and policy push-on-change (#1849) build on this and are explicitly out of scope here.
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
 * Out of scope for #1846, with a clear seam: the `hello` op is logged + metered but NOT negotiated
 * here — the `hello`→`ready` capability handshake and the first-policy-on-connect push land in
 * #1847 / #1849. The agent does not yet open this socket (the Lua client is #1848); the endpoint is
 * exercisable by a test ws client today.
 */
object RouterWsRoutes {

  def routes(
      auth: RouterAuth,
      registry: RouterWsRegistry,
      ingest: RouterIngestService,
      metricsSvc: RouterMetricsService,
      routerRepo: RouterRepo,
      policy: PolicyService,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "router" / "ws" ->
        handler { (req: Request) =>
          // Auth at upgrade time. On failure, return the SAME 401 Response the REST routes produce
          // (no 101) — the agent treats a ws-401 exactly like a REST-401 (drop, do not hammer).
          auth
            .authenticate(req)
            .foldZIO(
              err => ZIO.succeed(ErrorMapper.errorToResponse(err)),
              router =>
                socketApp(router, registry, ingest, metricsSvc, routerRepo, policy).toResponse,
            )
        },
    )

  private def socketApp(
      router: Router,
      registry: RouterWsRegistry,
      ingest: RouterIngestService,
      metricsSvc: RouterMetricsService,
      routerRepo: RouterRepo,
      policy: PolicyService,
  ): WebSocketApp[Any] =
    Handler.webSocket { channel =>
      // Register once the upgrade handshake completes; deregister unconditionally on exit (normal
      // close, error, or interruption) so the registry + active-connections gauge never leak a dead
      // channel. deregister is idempotent, so the explicit Unregistered handler below is harmless
      // belt-and-suspenders.
      channel
        .receiveAll {
          case ChannelEvent.UserEventTriggered(UserEvent.HandshakeComplete) =>
            // #1849: register, then push the current snapshot once so a freshly-connected router
            // has policy immediately (design §6.1). A snapshot-build failure must not tear the
            // socket down — log and carry on; the router still gets the next push-on-change and can
            // fall back to the REST poll.
            registry.register(router.id, channel) *>
              ZIO.logInfo(s"router ws: connected router=${router.id}") *>
              policy
                // #2107: first-policy push is scoped to the router's household (same scoping as the
                // REST /api/router/policy poll).
                .snapshot(router.householdId)
                .flatMap(registry.pushPolicyTo(channel, _))
                .catchAllCause(c =>
                  ZIO.logWarningCause(
                    s"router ws: first-policy push failed router=${router.id}",
                    c,
                  ),
                )
          case ChannelEvent.Read(WebSocketFrame.Text(text))                 =>
            // A dispatch failure (e.g. a send error) tears this frame's handling down; log the cause
            // so a transport-level fault is debuggable, then let it surface (the socket closes and
            // the agent reconnects per the design's reconnect-is-the-throttle rule).
            dispatch(router, channel, text, ingest, metricsSvc, routerRepo)
              .tapErrorCause(c =>
                ZIO.logErrorCause(s"router ws: dispatch failed router=${router.id}", c),
              )
          case ChannelEvent.Unregistered                                    =>
            registry.deregister(router.id, channel)
          case _                                                            =>
            ZIO.unit
        }
        .ensuring(
          registry.deregister(router.id, channel) *>
            ZIO.logInfo(s"router ws: disconnected router=${router.id}"),
        )
    }

  /**
   * Demux one inbound text frame. A frame that does not parse as the envelope, or carries an
   * unrecognized `op`, is metered + logged and otherwise ignored (forward-compat, design §1.3).
   * Data ops (`usage`/`events`/`metrics`) run through the shared ingest services and reply with an
   * `ack` frame (`ok`/`reject`). `ping` is answered with `pong`. `hello` is the #1847 handshake
   * seam — it is logged + metered but not yet negotiated. Every recognized frame touches
   * `routers.last_seen_at` for uniform liveness with the REST path (design §3.1/§5.5).
   */
  private def dispatch(
      router: Router,
      channel: WebSocketChannel,
      text: String,
      ingest: RouterIngestService,
      metricsSvc: RouterMetricsService,
      routerRepo: RouterRepo,
  ): ZIO[Any, Throwable, Unit] =
    text.fromJson[WsFrame] match {
      case Left(err)    =>
        timedInbound("unknown")(
          AppMetrics.recordWsFrame("unknown", "in", "reject") *>
            ZIO.logWarning(s"router ws: undecodable frame from router=${router.id}: $err"),
        )
      case Right(frame) =>
        LogContext.annotate(LogContext.RouterId, router.id.toString) {
          timedInbound(inboundOpLabel(frame.op)) {
            frame.op match {
              case "usage"   =>
                ingestFrame("usage", frame, channel, payload => ingest.ingestUsage(router, payload))
              case "events"  =>
                ingestFrame(
                  "events",
                  frame,
                  channel,
                  payload => ingest.ingestEvents(router, payload),
                )
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
                // #1847 seam: the capability handshake (hello→ready + snapshotVersion negotiation) is
                // NOT implemented here. Acknowledge receipt in the logs/metrics so the frame is
                // observable, but do not negotiate or reply `ready` yet.
                touch(routerRepo, router) *>
                  AppMetrics.recordWsFrame("hello", "in", "ok") *>
                  ZIO.logInfo(
                    s"router ws: hello from router=${router.id} (handshake deferred to #1847)",
                  )
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
    }

  /** The bounded inbound-op vocabulary (design §1.2); anything else collapses to `unknown`. */
  private val KnownInboundOps: Set[String] =
    Set("usage", "events", "metrics", "ping", "pong", "hello")

  /**
   * Collapse a raw frame `op` to the bounded metric label — an unrecognized (possibly
   * attacker-supplied) op must never become a Prometheus label value (cardinality firewall), so it
   * maps to `unknown`, exactly as [[recordWsFrame]] labels an unknown-op frame.
   */
  private def inboundOpLabel(op: String): String =
    if (KnownInboundOps.contains(op)) op else "unknown"

  /**
   * #2168: time the full processing of one inbound frame (decode → handle → ack) and record it to
   * `router_ws_message_duration_seconds{op,direction=in}` — the ws analog of the per-endpoint
   * `http_request_duration_seconds`. The observation fires on EVERY exit (ok, typed reject, or a
   * transport failure that tears the frame down) via `ensuring`, mirroring [[HttpMetrics]] timing
   * the handler's full success-and-failure exit.
   */
  private def timedInbound[R, E](
      op: String,
  )(effect: ZIO[R, E, Unit]): ZIO[R, E, Unit] =
    Clock.nanoTime.flatMap { start =>
      effect.ensuring(
        Clock.nanoTime.flatMap(end =>
          AppMetrics.recordWsMessageDuration(op, "in", (end - start) / 1e9d),
        ),
      )
    }

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
      case ApiError.RateLimited(_)   => "rate_limited"
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
}
