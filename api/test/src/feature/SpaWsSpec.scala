package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.WsConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.routes.{SpaConnId, SpaTopic, SpaWsRegistry, SpaWsRoutes}
import wifihaven.shared.Clock
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import doobie.*
import zio.{Clock as _, *}
import zio.http.*
import zio.http.ChannelEvent.UserEvent
import zio.metrics.*
import zio.test.*

import java.time.LocalDateTime

/**
 * #1969 (S2 of the SPA-websocket rollout, design `docs/design/spa-websocket.md` §4/§8): upgrade
 * auth for the browser-facing `GET /api/ws`. Exercises the real endpoint end to end — a real
 * [[Server]] on an ephemeral port and a real ws [[Client]] for the success paths, and
 * `routes.runZIO` directly for the reject paths (the upgrade is refused with a plain 401/403 BEFORE
 * the 101, so no handshake is needed to observe it).
 *
 * The credential is the existing operator JWT, validated by the EXISTING [[AuthService.verify]] (no
 * second auth surface, `AGENTS.md#single-source-of-truth`) — tests issue a REAL token via the same
 * `AuthServiceLive` the app uses, never a mock. It rides a `wh_ws` cookie on the upgrade (the
 * browser `WebSocket` API can't set headers, design §0.2.1); the role is resolved from the verified
 * claims, NOT from the S1 `hello{role}` stub. The `Origin` allowlist (§8) is the one server-side ws
 * config that differs by hosting mode. Mid-connection the connection's authz deadline is the JWT's
 * `exp`: on the heartbeat tick where `now ≥ jwtExp` the channel is closed `4401 token-expired`
 * (§4.3). Every outcome increments `spa_ws_auth_total{result}` (§7).
 */
object SpaWsSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  private val testClockAt: LocalDateTime = LocalDateTime.of(2026, 6, 25, 14, 0, 0)

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(testClockAt)

  private val jwtCfg = JwtConfig(secret = "test-secret-at-least-32-chars!!", expiryHours = 1)

  // Origin enforcement ON (cloud/staging shape): `localhost` is the dev allowlist entry, so a
  // ws upgrade whose Origin host is localhost passes and any other host is rejected. A short
  // expiry-check interval keeps the mid-connection-expiry test fast (the 1s floor is fine under the
  // 90s suite timeout).
  private val enforcedCfg = WsConfig(allowedOrigins = "localhost", expiryCheckSeconds = 1)
  // Origin enforcement OFF (self-hosted same-origin shape): an empty allowlist disables the
  // cross-origin check — SameSite=Strict on the cookie (§4.2) is the CSWSH guard there.
  private val openCfg     = WsConfig(allowedOrigins = "", expiryCheckSeconds = 1)

  private val cleanDb = TestDatabase.cleanAndMigrate

  // Each test builds a FRESH registry with one connection, so the first (and only) connection is
  // deterministically SpaConnId(1) — the seq counter starts at 0 and increments to 1 on register.
  private val onlyConn: SpaConnId = SpaConnId(1L)

  /** A real `AuthServiceLive` over the per-spec DB + the given clock (never a mock — SSOT). */
  private def makeAuth(clock: Clock): URIO[UserRepo, AuthServiceLive] =
    ZIO.serviceWith[UserRepo](ur => AuthServiceLive(ur, jwtCfg, clock))

  /** Issue a real operator JWT for a freshly-created user with the given role. */
  private def tokenFor(
      auth: AuthService,
      role: String,
      username: String,
  ): ZIO[UserRepo, Throwable, String] =
    for {
      hash <- auth.hashPassword("pass")
      _    <- ZIO.serviceWithZIO[UserRepo](_.create(username, hash, role))
      tok  <- auth.login(username, "pass").mapError(e => new RuntimeException(s"login: $e"))
    } yield tok.token.value

  /** The seeded admin (`admin`/`changeme`, must_change_password cleared in the test template). */
  private def adminToken(auth: AuthService): ZIO[Any, Throwable, String] =
    auth
      .login("admin", "changeme")
      .mapError(e => new RuntimeException(s"login: $e"))
      .map(_.token.value)

  private def cookieAndOrigin(token: String, origin: String): Headers =
    Headers("Cookie", s"wh_ws=$token") ++ Headers("Origin", origin)

  private def authCount(result: String): UIO[Double] =
    Metric.counter("spa_ws_auth_total").tagged("result", result).value.map(_.count)

  /**
   * Open a ws connection (with the upgrade headers) to the bound server, drive it with `send` on
   * handshake, and collect every text frame the server sends. Resolves once a frame satisfies
   * `until`, then runs `probe` while still open. Returns the matching frame, all frames so far, and
   * the probe result.
   */
  private def connectAndCapture[B](
      port: Int,
      headers: Headers,
      send: WebSocketChannel => ZIO[Any, Throwable, Unit],
      until: String => Boolean,
      probe: ZIO[Any, Throwable, B] = ZIO.unit,
  ): ZIO[Client, Throwable, (String, Chunk[String], B)] =
    for {
      matched <- Promise.make[Nothing, String]
      frames  <- Ref.make(Chunk.empty[String])
      app = Handler.webSocket { channel =>
        channel.receiveAll {
          case ChannelEvent.UserEventTriggered(UserEvent.HandshakeComplete) =>
            send(channel)
          case ChannelEvent.Read(WebSocketFrame.Text(t))                    =>
            frames.update(_ :+ t) *> ZIO.when(until(t))(matched.succeed(t)).unit
          case _                                                            =>
            ZIO.unit
        }
      }
      result <- ZIO.scoped {
        for {
          _   <- app.connect(s"ws://localhost:$port/api/ws", headers).forkScoped
          t   <- matched.await
            .timeoutFail(new RuntimeException("no matching server frame within 30s"))(30.seconds)
          all <- frames.get
          b   <- probe
        } yield (t, all, b)
      }
    } yield result

  /** Send a sequence of text frames in order on handshake. */
  private def sendFrames(channel: WebSocketChannel, frames: String*): ZIO[Any, Throwable, Unit] =
    ZIO.foreachDiscard(frames)(f => channel.send(ChannelEvent.read(WebSocketFrame.text(f))))

  private def get(path: String, headers: Headers): Request =
    Request.get(URL.decode(path).toOption.get).addHeaders(headers)

  def spec = suite("SPA websocket /api/ws upgrade auth (#1969)")(
    // ── success paths (real client, 101 + ready) ────────────────────────────────
    test("valid cookie + allowed Origin → 101 + ready carrying the JWT's role (not the stub)") {
      (for {
        _     <- cleanDb
        clock <- ZIO.service[Clock]
        auth  <- makeAuth(clock)
        tok   <- adminToken(auth)
        reg   <- SpaWsRegistry.make
        routes = SpaWsRoutes.routes(auth, reg, clock, enforcedCfg)
        port          <- Server.install(routes)
        before        <- authCount("ok")
        (ready, _, _) <- connectAndCapture(
          port,
          cookieAndOrigin(tok, "http://localhost"),
          ch => sendFrames(ch, """{"op":"hello"}"""),
          until = _.contains("\"op\":\"ready\""),
        )
        after         <- authCount("ok")
      } yield assertTrue(ready.contains("\"op\":\"ready\"")) &&
        // role is the cookie's role (admin), NOT the S1 least-privilege Child stub.
        assertTrue(ready.contains("\"role\":\"admin\"")) &&
        assertTrue(ready.contains("\"serverTime\":\"2026-06-25T14:00:00Z\"")) &&
        assertTrue(after - before >= 1.0)).provideSome[
        TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task],
      ](Server.defaultWithPort(0), Client.default)
    },
    test("self-hosted same-origin (empty allowlist) → ok with no Origin header") {
      (for {
        _     <- cleanDb
        clock <- ZIO.service[Clock]
        auth  <- makeAuth(clock)
        tok   <- adminToken(auth)
        reg   <- SpaWsRegistry.make
        routes = SpaWsRoutes.routes(auth, reg, clock, openCfg)
        port          <- Server.install(routes)
        (ready, _, _) <- connectAndCapture(
          port,
          Headers("Cookie", s"wh_ws=$tok"), // no Origin — self-hosted same-origin
          ch => sendFrames(ch, """{"op":"hello"}"""),
          until = _.contains("\"op\":\"ready\""),
        )
      } yield assertTrue(ready.contains("\"op\":\"ready\"")) &&
        assertTrue(ready.contains("\"role\":\"admin\""))).provideSome[
        TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task],
      ](Server.defaultWithPort(0), Client.default)
    },
    test(
      "a child cookie's subscribe to a household topic is acked reject (role scoping per frame)",
    ) {
      (for {
        _     <- cleanDb
        clock <- ZIO.service[Clock]
        auth  <- makeAuth(clock)
        tok   <- tokenFor(auth, "child", "kid")
        reg   <- SpaWsRegistry.make
        routes = SpaWsRoutes.routes(auth, reg, clock, enforcedCfg)
        port <- Server.install(routes)
        res  <- connectAndCapture(
          port,
          cookieAndOrigin(tok, "http://localhost"),
          ch =>
            sendFrames(
              ch,
              """{"op":"hello"}""",
              """{"op":"subscribe","payload":{"topic":"connectionEvents"}}""",
            ),
          until = _.contains("\"op\":\"ack\""),
          probe = reg.subscriptionsFor(onlyConn),
        )
        (ack, _, subs) = res
      } yield assertTrue(ack.contains("\"op\":\"ack\"")) &&
        assertTrue(ack.contains("\"topic\":\"connectionEvents\"")) &&
        assertTrue(ack.contains("\"status\":\"reject\"")) &&
        assertTrue(subs.isEmpty)).provideSome[
        TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task],
      ](Server.defaultWithPort(0), Client.default)
    },
    test("an admin cookie's subscribe to a household topic is acked ok and held server-side") {
      (for {
        _     <- cleanDb
        clock <- ZIO.service[Clock]
        auth  <- makeAuth(clock)
        tok   <- adminToken(auth)
        reg   <- SpaWsRegistry.make
        routes = SpaWsRoutes.routes(auth, reg, clock, enforcedCfg)
        port <- Server.install(routes)
        res  <- connectAndCapture(
          port,
          cookieAndOrigin(tok, "http://localhost"),
          ch =>
            sendFrames(
              ch,
              """{"op":"hello"}""",
              """{"op":"subscribe","payload":{"topic":"trafficUsage"}}""",
            ),
          until = _.contains("\"op\":\"ack\""),
          probe = reg.subscriptionsFor(onlyConn),
        )
        (ack, _, subs) = res
      } yield assertTrue(ack.contains("\"status\":\"ok\"")) &&
        assertTrue(subs.keySet == Set(SpaTopic.TrafficUsage))).provideSome[
        TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task],
      ](Server.defaultWithPort(0), Client.default)
    },
    test("reauth is acked reject (forward-compat seam; v1 has no silent refresh)") {
      (for {
        _     <- cleanDb
        clock <- ZIO.service[Clock]
        auth  <- makeAuth(clock)
        tok   <- adminToken(auth)
        reg   <- SpaWsRegistry.make
        routes = SpaWsRoutes.routes(auth, reg, clock, enforcedCfg)
        port <- Server.install(routes)
        res  <- connectAndCapture(
          port,
          cookieAndOrigin(tok, "http://localhost"),
          ch => sendFrames(ch, """{"op":"reauth","payload":{"jwt":"whatever"}}"""),
          until = _.contains("\"op\":\"ack\""),
        )
        (ack, _, _) = res
      } yield assertTrue(ack.contains("\"topic\":\"reauth\"")) &&
        assertTrue(ack.contains("\"status\":\"reject\""))).provideSome[
        TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task],
      ](Server.defaultWithPort(0), Client.default)
    },
    // ── reject paths (no 101) ───────────────────────────────────────────────────
    test("missing cookie → 401, no 101, metered no_cookie") {
      for {
        _     <- cleanDb
        clock <- ZIO.service[Clock]
        auth  <- makeAuth(clock)
        reg   <- SpaWsRegistry.make
        routes = SpaWsRoutes.routes(auth, reg, clock, enforcedCfg)
        before <- authCount("no_cookie")
        resp   <- routes.runZIO(get("/api/ws", Headers("Origin", "http://localhost")))
        after  <- authCount("no_cookie")
      } yield assertTrue(resp.status == Status.Unauthorized) &&
        assertTrue(after - before >= 1.0)
    },
    test("forged/garbage cookie → 401, no 101, metered invalid_jwt") {
      for {
        _     <- cleanDb
        clock <- ZIO.service[Clock]
        auth  <- makeAuth(clock)
        reg   <- SpaWsRegistry.make
        routes = SpaWsRoutes.routes(auth, reg, clock, enforcedCfg)
        before <- authCount("invalid_jwt")
        resp   <- routes.runZIO(
          get("/api/ws", cookieAndOrigin("not-a-real-jwt", "http://localhost")),
        )
        after  <- authCount("invalid_jwt")
      } yield assertTrue(resp.status == Status.Unauthorized) &&
        assertTrue(after - before >= 1.0)
    },
    test("already-expired JWT → 401, no 101, metered expired_jwt") {
      for {
        _         <- cleanDb
        (clk, tc) <- TestClock.makeWithControl(testClockAt)
        auth      <- makeAuth(clk)
        tok       <- adminToken(auth)
        _         <- tc.advance(java.time.Duration.ofHours(2)) // exp was now+1h → now past it
        reg       <- SpaWsRegistry.make
        routes = SpaWsRoutes.routes(auth, reg, clk, enforcedCfg)
        before <- authCount("expired_jwt")
        resp   <- routes.runZIO(get("/api/ws", cookieAndOrigin(tok, "http://localhost")))
        after  <- authCount("expired_jwt")
      } yield assertTrue(resp.status == Status.Unauthorized) &&
        assertTrue(after - before >= 1.0)
    },
    test("disallowed Origin (valid cookie) → reject, no 101, metered bad_origin") {
      for {
        _     <- cleanDb
        clock <- ZIO.service[Clock]
        auth  <- makeAuth(clock)
        tok   <- adminToken(auth)
        reg   <- SpaWsRegistry.make
        routes = SpaWsRoutes.routes(auth, reg, clock, enforcedCfg)
        before <- authCount("bad_origin")
        resp   <- routes.runZIO(get("/api/ws", cookieAndOrigin(tok, "http://evil.example.com")))
        after  <- authCount("bad_origin")
      } yield assertTrue(resp.status != Status.SwitchingProtocols) &&
        assertTrue(resp.status == Status.Forbidden) &&
        assertTrue(after - before >= 1.0)
    },
    test("absent Origin under an enforced allowlist → reject, metered bad_origin") {
      for {
        _     <- cleanDb
        clock <- ZIO.service[Clock]
        auth  <- makeAuth(clock)
        tok   <- adminToken(auth)
        reg   <- SpaWsRegistry.make
        routes = SpaWsRoutes.routes(auth, reg, clock, enforcedCfg)
        before <- authCount("bad_origin")
        resp   <- routes.runZIO(get("/api/ws", Headers("Cookie", s"wh_ws=$tok")))
        after  <- authCount("bad_origin")
      } yield assertTrue(resp.status == Status.Forbidden) &&
        assertTrue(after - before >= 1.0)
    },
    // ── mid-connection expiry (§4.3) ────────────────────────────────────────────
    test("JWT exp crossing mid-connection → 4401 close + deregister, metered jwt_expired_midconn") {
      (for {
        _         <- cleanDb
        (clk, tc) <- TestClock.makeWithControl(testClockAt)
        auth      <- makeAuth(clk)
        tok       <- adminToken(auth)
        reg       <- SpaWsRegistry.make
        routes = SpaWsRoutes.routes(auth, reg, clk, enforcedCfg)
        port   <- Server.install(routes)
        before <- authCount("jwt_expired_midconn")
        readyP <- Promise.make[Nothing, Unit]
        closeP <- Promise.make[Nothing, Int]
        // forwardCloseFrames so the server's `4401` Close is delivered to this handler as a Read
        // (the default config handles close frames internally and would not surface the code).
        app            = Handler
          .webSocket { channel =>
            channel.receiveAll {
              case ChannelEvent.UserEventTriggered(UserEvent.HandshakeComplete)                =>
                channel.send(ChannelEvent.read(WebSocketFrame.text("""{"op":"hello"}""")))
              case ChannelEvent.Read(WebSocketFrame.Text(t)) if t.contains("\"op\":\"ready\"") =>
                readyP.succeed(()).unit
              case ChannelEvent.Read(WebSocketFrame.Close(code, _))                            =>
                closeP.succeed(code).unit
              case _                                                                           =>
                ZIO.unit
            }
          }
          .withConfig(WebSocketConfig.default.forwardCloseFrames(true))
        result <- ZIO.scoped {
          for {
            _      <- app
              .connect(s"ws://localhost:$port/api/ws", cookieAndOrigin(tok, "http://localhost"))
              .forkScoped
            _      <- readyP.await.timeoutFail(new RuntimeException("no ready"))(30.seconds)
            _      <- tc.advance(java.time.Duration.ofHours(2)) // cross exp mid-connection
            code   <- closeP.await.timeoutFail(new RuntimeException("no 4401 close"))(30.seconds)
            active <- reg.activeCount
          } yield (code, active)
        }
        (code, active) = result
        after <- authCount("jwt_expired_midconn")
      } yield assertTrue(code == 4401) &&
        assertTrue(active == 0) &&
        assertTrue(after - before >= 1.0)).provideSome[
        TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task],
      ](Server.defaultWithPort(0), Client.default)
    },
    // ── forward-compat (unchanged from S1) ──────────────────────────────────────
    test("an unknown op is ignored (no reply) while a known op still answers") {
      (for {
        _     <- cleanDb
        clock <- ZIO.service[Clock]
        auth  <- makeAuth(clock)
        tok   <- adminToken(auth)
        reg   <- SpaWsRegistry.make
        routes = SpaWsRoutes.routes(auth, reg, clock, enforcedCfg)
        port           <- Server.install(routes)
        (pong, all, _) <- connectAndCapture(
          port,
          cookieAndOrigin(tok, "http://localhost"),
          ch =>
            sendFrames(ch, """{"op":"definitelyNotARealOp","payload":{}}""", """{"op":"ping"}"""),
          until = _.contains("\"op\":\"pong\""),
        )
      } yield assertTrue(pong.contains("\"op\":\"pong\"")) &&
        assertTrue(all.count(_.contains("\"op\":")) == 1)).provideSome[
        TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task],
      ](Server.defaultWithPort(0), Client.default)
    },
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(90.seconds)
}
