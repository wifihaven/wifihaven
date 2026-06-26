package wifihaven.api.routes

import wifihaven.api.metrics.AppMetrics
import wifihaven.api.observability.LogContext
import wifihaven.shared.{Clock, UserRole}
import zio.*
import zio.http.*
import zio.http.ChannelEvent.UserEvent
import zio.json.*
import zio.json.ast.Json

/**
 * #1968: the browser-facing websocket transport, `GET /api/ws`. This is step S1 of the
 * SPA-websocket rollout ([`docs/design/spa-websocket.md`](../../../docs/design/spa-websocket.md)
 * §9, design #1860; umbrella #1955) — the SERVER skeleton: the `{op, payload, seq?}` envelope
 * demux, the control ops (`hello`→`ready`, `subscribe`/`unsubscribe` with per-connection
 * subscription state + topic-keyed `ack`, `ping`/`pong`), and the forked per-connection
 * [[SpaWsRegistry]]. Purely additive: REST stays the fallback throughout the whole rollout (design
 * §3.3, §9), and the SPA has no ws client yet (that is S5). The endpoint is exercisable by a test
 * ws client today.
 *
 * MIRRORS the router transport ([[RouterWsRoutes]], #1846) — same envelope discipline (one JSON
 * text message per frame), same demux shape, same unknown-op-ignore+meter forward-compat rule
 * (design §2.2) — but FORKS where the two genuinely differ (design §5.1): a per-connection id keyed
 * registry with a subscription set (not a `RouterId`→snapshot registry), the SPA op vocabulary, and
 * a topic-keyed `ack` (not the router's seq-keyed data-frame ack).
 *
 * Auth is OUT OF SCOPE for S1 — the browser cannot set an `Authorization` header on the ws upgrade
 * (design §0.2.1), so S2 (#1969) authorizes the upgrade by verifying a tightly-scoped JWT cookie
 * via the existing [[wifihaven.api.auth.AuthService]] (no second auth surface). For S1 the
 * connection registers with a stub [[UserRole.Admin]] resolved at upgrade ([[upgradeRole]] — the
 * clear seam S2 plugs the cookie verify into), and the `hello` payload's optional `{role}` is the
 * test-handshake override that lets the subscription/authz machinery be exercised without a real
 * cookie.
 */
object SpaWsRoutes {

  /** Convenience for callers that need a registry instance (Main wiring, tests). */
  def registry: UIO[SpaWsRegistry] = SpaWsRegistry.make

  def routes(registry: SpaWsRegistry, clock: Clock): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "ws" ->
        handler { (_: Request) =>
          // S1: no upgrade auth — complete the upgrade unconditionally with the stub role. S2 (#1969)
          // verifies the `wh_ws` cookie + Origin allowlist here and rejects a bad/missing/expired
          // credential with a 401 (no 101), exactly like the router path and a REST 401.
          socketApp(registry, clock).toResponse
        },
    )

  /**
   * The role resolved at upgrade time. S1 stub: every connection starts at the LEAST-privileged
   * role ([[UserRole.Child]]) until a `hello{role}` overrides it (the test handshake).
   * Least-privilege by default is deliberate defense-in-depth: S1 has no upgrade auth yet (that is
   * S2 / #1969) and mounts in prod additively, so if a later step (S3/S4) ever pushed data before
   * S2's cookie verify lands, an unauthenticated connection that never sent a `hello{role}` would
   * see the *minimum* surface (fail closed), not Admin (fail open). The §4.4 authz gate
   * (`SpaTopic.visibleTo`) then filters on this role. S2 replaces this stub with the role read from
   * the verified `wh_ws` JWT cookie (design §4.2/§4.4), captured once and authoritative for the
   * connection's lifetime — and #1969 is a hard predecessor to any data-bearing push step (S3/S4)
   * for exactly this reason.
   */
  private val upgradeRole: UserRole = UserRole.Child

  private def socketApp(registry: SpaWsRegistry, clock: Clock): WebSocketApp[Any] =
    Handler.webSocket { channel =>
      // The connection id is assigned at HandshakeComplete (register) and read by every later frame;
      // events are delivered in order so the id is set before any Read. Deregister unconditionally on
      // exit so the registry + gauges never leak a dead channel.
      Ref.make(Option.empty[SpaConnId]).flatMap { idRef =>
        channel
          .receiveAll {
            case ChannelEvent.UserEventTriggered(UserEvent.HandshakeComplete) =>
              registry.register(channel, upgradeRole, None).flatMap(id => idRef.set(Some(id))) *>
                ZIO.logInfo("spa ws: connected")
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
            idRef.get.flatMap(ZIO.foreachDiscard(_)(registry.deregister)) *>
              ZIO.logInfo("spa ws: disconnected"),
          )
      }
    }

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
        AppMetrics.recordSpaWsFrame("unknown", "in", "reject") *>
          ZIO.logWarning(s"spa ws: undecodable frame: $err")
      case Right(frame) =>
        registry.roleFor(id).flatMap { roleOpt =>
          val role = roleOpt.getOrElse(upgradeRole)
          LogContext
            .annotateAll(LogContext.Op -> frame.op, LogContext.Role -> UserRole.asString(role)) {
              frame.op match {
                case "hello"       => handleHello(id, channel, frame, registry, clock)
                case "subscribe"   => handleSubscribe(id, channel, frame, role, registry)
                case "unsubscribe" => handleUnsubscribe(id, frame, registry)
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

  /**
   * `hello`→`ready` (design §1.2). S1: the optional payload `{role}` is the test-handshake override
   * that sets the connection's role (S2 ignores it — the role comes from the verified cookie).
   * Reply `ready{role, serverTime}`; `serverTime` is read from the INJECTED clock (never
   * wall-clock).
   */
  private def handleHello(
      id: SpaConnId,
      channel: WebSocketChannel,
      frame: SpaWsFrame,
      registry: SpaWsRegistry,
      clock: Clock,
  ): ZIO[Any, Throwable, Unit] = {
    val requested = decode[HelloPayload](frame).toOption.flatMap(_.role).flatMap(UserRole.parse)
    for {
      _    <- ZIO.foreachDiscard(requested)(registry.setRole(id, _))
      role <- registry.roleFor(id).map(_.getOrElse(upgradeRole))
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
  }

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

  /** `hello` payload — `{role?}` is the S1 test-handshake override (S2 ignores it). */
  private case class HelloPayload(role: Option[String]) derives JsonCodec

  /** `ready` payload (design §1.2): the resolved role + the server's (injected-clock) time. */
  private case class ReadyPayload(role: String, serverTime: String) derives JsonCodec

  /** `subscribe` payload (design §1.4): the topic + its verbatim endpoint query params. */
  private case class SubscribePayload(topic: String, params: Option[Json]) derives JsonCodec

  /** `unsubscribe` payload (design §1.4): just the topic. */
  private case class UnsubscribePayload(topic: String) derives JsonCodec

  /** The topic-keyed `ack` payload (design §1.4): which topic, ok/reject, optional reason. */
  private case class SpaAck(topic: String, status: String, reason: Option[String]) derives JsonCodec
}
