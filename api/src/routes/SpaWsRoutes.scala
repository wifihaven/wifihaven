package wifihaven.api.routes

import wifihaven.api.WsConfig
import wifihaven.api.auth.{AuthError, AuthService}
import wifihaven.api.metrics.AppMetrics
import wifihaven.api.observability.LogContext
import wifihaven.shared.{Clock, UserRole}
import wifihaven.shared.types.HouseholdId
import zio.*
import zio.http.*
import zio.http.ChannelEvent.UserEvent
import zio.json.*
import zio.json.ast.Json

import java.time.Instant

/**
 * #1968 / #1969: the browser-facing websocket transport, `GET /api/ws`. S1 (#1968) shipped the
 * SERVER skeleton — the `{op, payload, seq?}` envelope demux, the control ops (`hello`→`ready`,
 * `subscribe`/`unsubscribe` with per-connection subscription state + topic-keyed `ack`,
 * `ping`/`pong`, unknown-op ignore+meter), and the forked per-connection [[SpaWsRegistry]]. S2
 * (#1969) plugs in UPGRADE AUTH (design
 * [`docs/design/spa-websocket.md`](../../../docs/design/spa-websocket.md) §4/§8): the connection is
 * authorized BEFORE the 101, exactly like the router path.
 *
 * Auth (design §4.2): the browser `WebSocket` constructor can't set an `Authorization` header
 * (§0.2.1), so the operator JWT rides a tightly-scoped `wh_ws` cookie set just before connect. On
 * the upgrade we (a) check the `Origin` against the configured allowlist (§8 — the one server-side
 * ws config that differs by hosting mode), then (b) read the `wh_ws` cookie and verify it with the
 * EXISTING [[AuthService.verify]] (no second auth surface — `AGENTS.md#single-source-of-truth`). A
 * bad/missing/expired cookie or disallowed Origin → reject with a plain 401/403 (no 101), identical
 * to a REST 401 and the router path. The resolved [[UserRole]] is captured at upgrade and is the
 * fan-out authz key (§4.4); the JWT's `exp` is captured as the connection's authz deadline (§4.3) —
 * a per-connection watcher closes the channel with `4401 token-expired` on the tick where `now ≥
 * jwtExp`. Every upgrade outcome is metered `spa_ws_auth_total{result}` (§7).
 *
 * `reauth` is a forward-compat seam (§4.3): v1 has no silent token refresh, so the op is `ack`
 * rejected — the SPA falls back to polling + `/login` on `4401`.
 *
 * MIRRORS the router transport ([[RouterWsRoutes]], #1846) — same envelope discipline, same
 * pre-upgrade auth shape — but FORKS where the two genuinely differ (design §5.1): a cookie (not a
 * bearer header) carries the credential, a per-connection id keyed registry with a subscription
 * set, the SPA op vocabulary, and a topic-keyed `ack`.
 */
object SpaWsRoutes {

  /** Convenience for callers that need a registry instance (Main wiring, tests). */
  def registry: UIO[SpaWsRegistry] = SpaWsRegistry.make

  def routes(
      auth: AuthService,
      registry: SpaWsRegistry,
      clock: Clock,
      cfg: WsConfig,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "ws" ->
        handler { (req: Request) =>
          // Authorize the upgrade BEFORE completing it (design §4.2). On any rejection, meter the
          // outcome and return the plain 401/403 Response (no 101) — the SPA treats a ws reject like
          // a REST 401. On success, register with the resolved role + jwtExp and complete the upgrade.
          authorizeUpgrade(req, auth, cfg).foldZIO(
            reject =>
              LogContext.annotate(LogContext.Result, reject.metric) {
                AppMetrics.recordSpaWsAuth(reject.metric) *>
                  ZIO.logWarning(s"spa ws: upgrade rejected (${reject.metric})") *>
                  ZIO.succeed(reject.response)
              },
            authd =>
              AppMetrics.recordSpaWsAuth("ok") *>
                socketApp(registry, clock, authd, cfg).toResponse,
          )
        },
    )

  /**
   * The least-privileged role ([[UserRole.Child]]). S2 resolves the real role from the verified
   * cookie at upgrade, so this is only a defensive fallback for the (impossible-post-auth) case
   * where `roleFor` finds no registration for an active connection — fail closed, not open.
   */
  private val fallbackRole: UserRole = UserRole.Child

  /**
   * The resolved upgrade identity captured once and authoritative for the connection's lifetime.
   */
  // #1974 (S6a): `username` (JWT `sub`) is captured alongside the role so the class-(2)
  // `timeStatus`/`appUsage` pushes can reuse the GET's per-child profile filter (design §4.4).
  // #2251: `household` (JWT `hh`) is captured so the class-(2) `now` push is built + delivered
  // per-household (no cross-household leak in the live NOW push, closes #2120).
  private final case class Authorized(
      role: UserRole,
      jwtExp: Instant,
      username: String,
      household: HouseholdId,
  )

  /**
   * Why an upgrade was refused. Each case carries its `spa_ws_auth_total{result}` label and the
   * plain HTTP `Response` returned in place of the 101 — cookie failures map to 401 (mirroring a
   * REST 401 / the router bearer path), a disallowed Origin to 403.
   */
  private enum UpgradeReject(val metric: String) {
    case NoCookie   extends UpgradeReject("no_cookie")
    case InvalidJwt extends UpgradeReject("invalid_jwt")
    case ExpiredJwt extends UpgradeReject("expired_jwt")
    case BadOrigin  extends UpgradeReject("bad_origin")

    def response: Response = this match {
      case NoCookie   => Response.unauthorized("missing wh_ws cookie")
      case InvalidJwt => Response.unauthorized("invalid token")
      case ExpiredJwt => Response.unauthorized("token expired")
      case BadOrigin  => Response.forbidden("origin not allowed")
    }
  }

  /**
   * Resolve the upgrade to an [[Authorized]] identity, or fail with the reason it was refused.
   * Origin first (cheap, no crypto), then the cookie verify (design §4.2). The verify is delegated
   * to [[AuthService.verify]] — the SAME implementation REST uses — so the ws path adds no second
   * JWT-validation surface.
   */
  private def authorizeUpgrade(
      req: Request,
      auth: AuthService,
      cfg: WsConfig,
  ): IO[UpgradeReject, Authorized] =
    checkOrigin(req, cfg) *> verifyCookie(req, auth)

  private def checkOrigin(req: Request, cfg: WsConfig): IO[UpgradeReject, Unit] =
    if !cfg.enforceOrigin then ZIO.unit // self-hosted same-origin: cross-origin check off (§8)
    else
      originHost(req) match {
        case Some(host) if cfg.originAllowed(host) => ZIO.unit
        case _                                     => ZIO.fail(UpgradeReject.BadOrigin)
      }

  /** The Origin header's host (lower-cased), if the header is present and parseable. */
  private def originHost(req: Request): Option[String] =
    req
      .rawHeader("Origin")
      .flatMap(o => URL.decode(o).toOption)
      .flatMap(_.host)
      .map(_.toLowerCase)

  private def verifyCookie(
      req: Request,
      auth: AuthService,
  ): IO[UpgradeReject, Authorized] =
    req.cookie("wh_ws").map(_.content) match {
      case None        => ZIO.fail(UpgradeReject.NoCookie)
      case Some(token) =>
        auth
          .verify(token)
          .mapError {
            case AuthError.TokenExpired => UpgradeReject.ExpiredJwt
            case _                      => UpgradeReject.InvalidJwt
          }
          .map { claims =>
            Authorized(
              role = UserRole.parse(claims.role).getOrElse(fallbackRole),
              jwtExp = Instant.ofEpochSecond(claims.exp),
              username = claims.sub,
              household = claims.hh,
            )
          }
    }

  private def socketApp(
      registry: SpaWsRegistry,
      clock: Clock,
      authd: Authorized,
      cfg: WsConfig,
  ): WebSocketApp[Any] =
    Handler.webSocket { channel =>
      // The connection id is assigned at HandshakeComplete (register) and read by every later frame;
      // events are delivered in order so the id is set before any Read. A per-connection expiry
      // watcher is forked at register and interrupted on exit. Deregister unconditionally on exit so
      // the registry + gauges never leak a dead channel.
      for {
        idRef    <- Ref.make(Option.empty[SpaConnId])
        fiberRef <- Ref.make(Option.empty[Fiber.Runtime[Nothing, Unit]])
        _        <- channel
          .receiveAll {
            case ChannelEvent.UserEventTriggered(UserEvent.HandshakeComplete) =>
              registry
                .register(
                  channel,
                  authd.role,
                  Some(authd.jwtExp),
                  Some(authd.username),
                  authd.household,
                )
                .flatMap { id =>
                  idRef.set(Some(id)) *>
                    watchExpiry(
                      channel,
                      id,
                      authd.jwtExp,
                      registry,
                      clock,
                      cfg.expiryCheckInterval,
                    ).forkDaemon
                      .flatMap(f => fiberRef.set(Some(f)))
                } *> ZIO.logInfo("spa ws: connected")
            case ChannelEvent.Read(WebSocketFrame.Text(text))                 =>
              idRef.get.flatMap {
                case Some(id) =>
                  dispatch(id, channel, text, registry, clock)
                    .tapErrorCause(c => ZIO.logErrorCause("spa ws: dispatch failed", c))
                case None     =>
                  // A frame before the handshake completed — should not happen, but ignore rather
                  // than crash the receive loop.
                  ZIO.unit
              }
            case ChannelEvent.Unregistered                                    =>
              idRef.get.flatMap(ZIO.foreachDiscard(_)(registry.deregister))
            case _                                                            =>
              ZIO.unit
          }
          .ensuring(
            fiberRef.get.flatMap(ZIO.foreachDiscard(_)(_.interrupt)) *>
              idRef.get.flatMap(ZIO.foreachDiscard(_)(registry.deregister)) *>
              ZIO.logInfo("spa ws: disconnected"),
          )
      } yield ()
    }

  /**
   * The per-connection authz-deadline watcher (design §4.3). On each tick it reads the INJECTED
   * clock; once `now ≥ jwtExp` it closes the channel with `4401 token-expired`, deregisters, and
   * meters `jwt_expired_midconn`, then stops. Until then it sleeps one `interval` and re-checks, so
   * stale-authz carry-over is bounded by one tick. Never fails; interrupted on connection close.
   */
  private def watchExpiry(
      channel: WebSocketChannel,
      id: SpaConnId,
      jwtExp: Instant,
      registry: SpaWsRegistry,
      clock: Clock,
      interval: Duration,
  ): UIO[Unit] = {
    def loop: UIO[Unit] =
      clock.instant.flatMap { now =>
        if !now.isBefore(jwtExp) then closeExpired(channel, id, registry)
        else loop.delay(interval)
      }
    loop
  }

  private def closeExpired(
      channel: WebSocketChannel,
      id: SpaConnId,
      registry: SpaWsRegistry,
  ): UIO[Unit] =
    AppMetrics.recordSpaWsAuth("jwt_expired_midconn") *>
      ZIO.logInfo("spa ws: closing expired connection (4401 token-expired)") *>
      registry.deregister(id) *>
      // Send the close frame and let the handshake drive teardown (the peer echoes Close, then the
      // server's receive loop ends and `.ensuring` runs). Do NOT `channel.shutdown` here — aborting
      // the transport immediately after the send can truncate the Close frame before it flushes, so
      // the client would see an abnormal 1006 instead of the `4401 token-expired` code (§4.3).
      channel.send(ChannelEvent.read(WebSocketFrame.close(4401, Some("token-expired")))).ignore

  /**
   * Demux one inbound text frame (design §2.2). A frame that does not parse as the envelope, or
   * carries an unrecognized `op`, is metered + logged and otherwise ignored (forward-compat). Per-
   * frame structured logging attaches `op`/`role`/`result` to the MDC (design §10.5), mirroring
   * [[RouterWsRoutes]]' `LogContext` discipline.
   */
  private def dispatch(
      id: SpaConnId,
      channel: WebSocketChannel,
      text: String,
      registry: SpaWsRegistry,
      clock: Clock,
  ): ZIO[Any, Throwable, Unit] =
    text.fromJson[SpaWsFrame] match {
      case Left(err)    =>
        timedInbound("unknown")(
          AppMetrics.recordSpaWsFrame("unknown", "in", "reject") *>
            ZIO.logWarning(s"spa ws: undecodable frame: $err"),
        )
      case Right(frame) =>
        registry.roleFor(id).flatMap { roleOpt =>
          val role = roleOpt.getOrElse(fallbackRole)
          LogContext
            .annotateAll(LogContext.Op -> frame.op, LogContext.Role -> UserRole.asString(role)) {
              timedInbound(inboundOpLabel(frame.op)) {
                frame.op match {
                  case "hello"       => handleHello(id, channel, registry, clock)
                  case "subscribe"   => handleSubscribe(id, channel, frame, role, registry)
                  case "unsubscribe" => handleUnsubscribe(id, frame, registry)
                  case "reauth"      =>
                    // Forward-compat seam (design §4.3): v1 has NO silent refresh, so reject. The SPA
                    // falls back to polling + /login on `4401`; when refresh lands, the server will
                    // advance jwtExp here instead.
                    LogContext.annotate(LogContext.Result, "reject") {
                      AppMetrics.recordSpaWsFrame("reauth", "in", "reject") *>
                        sendAck(channel, "reauth", "reject", Some("reauth_unsupported"))
                    }
                  case "ping"        =>
                    AppMetrics.recordSpaWsFrame("ping", "in", "ok") *>
                      send(channel, SpaWsFrame("pong", Some(Json.Obj()))) *>
                      AppMetrics.recordSpaWsFrame("pong", "out", "ok")
                  case "pong"        =>
                    AppMetrics.recordSpaWsFrame("pong", "in", "ok")
                  case other         =>
                    // Forward-compat: ignore + meter an unrecognized op (design §2.2). The arbitrary op
                    // string collapses to the bounded literal `unknown` for the metric label (cardinality
                    // firewall — an attacker-supplied op must never become a Prometheus label value); the
                    // real op goes to the log only.
                    LogContext.annotate(LogContext.Result, "unknown_op") {
                      AppMetrics.recordSpaWsFrame("unknown", "in", "unknown_op") *>
                        ZIO.logDebug(s"spa ws: ignoring unknown op '$other'")
                    }
                }
              }
            }
        }
    }

  /** The bounded inbound-op vocabulary (design §2.2); anything else collapses to `unknown`. */
  private val KnownInboundOps: Set[String] =
    Set("hello", "subscribe", "unsubscribe", "reauth", "ping", "pong")

  /**
   * Collapse a raw frame `op` to the bounded metric label — an unrecognized (possibly
   * attacker-supplied) op must never become a Prometheus label value (cardinality firewall), so it
   * maps to `unknown`, exactly as [[recordSpaWsFrame]] labels an unknown-op frame.
   */
  private def inboundOpLabel(op: String): String =
    if (KnownInboundOps.contains(op)) op else "unknown"

  /**
   * #2168: time the full processing of one inbound frame (decode → handle → ack) and record it to
   * `spa_ws_message_duration_seconds{op,direction=in}` — the browser-side twin of the router path's
   * inbound timing. The observation fires on EVERY exit (ok, reject, or a transport failure) via
   * `ensuring`. Uses `zio.Clock.nanoTime` explicitly because the `Clock` in scope here is
   * [[wifihaven.shared.Clock]] (the injected wall-clock), not the monotonic runtime clock.
   */
  private def timedInbound[R, E](
      op: String,
  )(effect: ZIO[R, E, Unit]): ZIO[R, E, Unit] =
    zio.Clock.nanoTime.flatMap { start =>
      effect.ensuring(
        zio.Clock.nanoTime.flatMap(end =>
          AppMetrics.recordSpaWsMessageDuration(op, "in", (end - start) / 1e9d),
        ),
      )
    }

  /**
   * `hello`→`ready` (design §1.2). The connection's role was fixed at upgrade from the verified
   * cookie (§4.2), so `hello` carries no role override — it just elicits `ready{role, serverTime}`.
   * `serverTime` is read from the INJECTED clock (never wall-clock).
   */
  private def handleHello(
      id: SpaConnId,
      channel: WebSocketChannel,
      registry: SpaWsRegistry,
      clock: Clock,
  ): ZIO[Any, Throwable, Unit] =
    for {
      role <- registry.roleFor(id).map(_.getOrElse(fallbackRole))
      now  <- clock.instant
      _    <- AppMetrics.recordSpaWsFrame("hello", "in", "ok")
      _    <- send(
        channel,
        SpaWsFrame(
          "ready",
          Some(ReadyPayload(UserRole.asString(role), now.toString).toJsonAST.getOrElse(Json.Obj())),
        ),
      )
      _    <- AppMetrics.recordSpaWsFrame("ready", "out", "ok")
    } yield ()

  /**
   * `subscribe{topic, params?}` → topic-keyed `ack` (design §1.4). Fan-out is two gates (subscribe
   * AND authorize): an unknown topic or a topic the role can't see (§4.4) is `ack`-rejected without
   * registering it; an authorized topic is stored with its params (the verbatim endpoint query
   * params, §0.3) and `ack`-ok'd. The `ack` is keyed by TOPIC, not seq — distinct from the router
   * path's seq-keyed data-frame ack.
   */
  private def handleSubscribe(
      id: SpaConnId,
      channel: WebSocketChannel,
      frame: SpaWsFrame,
      role: UserRole,
      registry: SpaWsRegistry,
  ): ZIO[Any, Throwable, Unit] =
    decode[SubscribePayload](frame) match {
      case Left(_)     =>
        rejectSubscribe(channel, "unknown", "bad_payload")
      case Right(body) =>
        SpaTopic.parse(body.topic) match {
          case None                                            =>
            rejectSubscribe(channel, body.topic, "unknown_topic")
          case Some(topic) if !SpaTopic.visibleTo(topic, role) =>
            rejectSubscribe(channel, body.topic, "forbidden")
          case Some(topic)                                     =>
            registry.subscribe(id, topic, body.params.getOrElse(Json.Obj())) *>
              AppMetrics.recordSpaWsFrame("subscribe", "in", "ok") *>
              sendAck(channel, body.topic, "ok", None)
        }
    }

  private def rejectSubscribe(
      channel: WebSocketChannel,
      topic: String,
      reason: String,
  ): ZIO[Any, Throwable, Unit] =
    LogContext.annotate(LogContext.Result, "reject") {
      AppMetrics.recordSpaWsFrame("subscribe", "in", "reject") *>
        sendAck(channel, topic, "reject", Some(reason))
    }

  /**
   * `unsubscribe{topic}` (design §1.4). Connection-scoped, idempotent; no `ack` (the `ack` is per-
   * *subscribe* only). An unknown topic is metered `reject` but otherwise a no-op.
   */
  private def handleUnsubscribe(
      id: SpaConnId,
      frame: SpaWsFrame,
      registry: SpaWsRegistry,
  ): ZIO[Any, Throwable, Unit] =
    decode[UnsubscribePayload](frame).toOption.flatMap(b => SpaTopic.parse(b.topic)) match {
      case Some(topic) =>
        registry.unsubscribe(id, topic) *> AppMetrics.recordSpaWsFrame("unsubscribe", "in", "ok")
      case None        =>
        LogContext.annotate(LogContext.Result, "reject") {
          AppMetrics.recordSpaWsFrame("unsubscribe", "in", "reject")
        }
    }

  private def decode[A: JsonDecoder](frame: SpaWsFrame): Either[String, A] =
    frame.payload.map(_.toJson).getOrElse("{}").fromJson[A]

  private def send(channel: WebSocketChannel, frame: SpaWsFrame): ZIO[Any, Throwable, Unit] =
    channel.send(ChannelEvent.read(WebSocketFrame.text(frame.toJson)))

  /** Send a topic-keyed `ack` (design §1.4) and meter the outbound frame by its status. */
  private def sendAck(
      channel: WebSocketChannel,
      topic: String,
      status: String,
      reason: Option[String],
  ): ZIO[Any, Throwable, Unit] =
    send(
      channel,
      SpaWsFrame("ack", Some(SpaAck(topic, status, reason).toJsonAST.getOrElse(Json.Obj()))),
    ) *>
      AppMetrics.recordSpaWsFrame(
        "ack",
        "out",
        status match { case "ok" => "ok"; case _ => "reject" },
      )

  /**
   * The `{op, payload, seq?}` frame envelope (design §2.2). `payload`/`seq` optional (e.g. ping).
   */
  private case class SpaWsFrame(op: String, payload: Option[Json] = None, seq: Option[Int] = None)
      derives JsonCodec

  /** `ready` payload (design §1.2): the resolved role + the server's (injected-clock) time. */
  private case class ReadyPayload(role: String, serverTime: String) derives JsonCodec

  /** `subscribe` payload (design §1.4): the topic + its verbatim endpoint query params. */
  private case class SubscribePayload(topic: String, params: Option[Json]) derives JsonCodec

  /** `unsubscribe` payload (design §1.4): just the topic. */
  private case class UnsubscribePayload(topic: String) derives JsonCodec

  /** The topic-keyed `ack` payload (design §1.4): which topic, ok/reject, optional reason. */
  private case class SpaAck(topic: String, status: String, reason: Option[String]) derives JsonCodec
}
