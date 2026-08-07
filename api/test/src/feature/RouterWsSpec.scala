package wifihaven.api.feature

import wifihaven.api.{ErrorBoundary, Readiness}
import wifihaven.api.db.*
import wifihaven.api.metrics.{HttpMetrics, RouterMetricsService}
import wifihaven.api.observability.LoggingMiddleware
import wifihaven.api.policy.PolicyServiceLive
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import doobie.Transactor
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.http.ChannelEvent.UserEvent
import zio.json.*
import zio.metrics.Metric
import zio.test.*

import java.time.{Instant, LocalDateTime}
import java.util.UUID

/**
 * #1846: server-side websocket transport. Exercises the real `/api/router/ws` endpoint end to end —
 * a real [[Server]] on an ephemeral port and a real ws [[Client]] — to prove upgrade-time auth, the
 * `{op, payload}` demux dispatching into the shared ingest service, the per-router connection
 * registry, and the heartbeat (`ping`→`pong`). The REST ingest path stays covered by
 * [[RouterIngestSpec]], which is the back-compat gate for the transport-agnostic ingest extraction.
 */
object RouterWsSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  private val testClockAt: LocalDateTime = LocalDateTime.of(2026, 5, 7, 14, 0, 0)

  override val bootstrap = TestDatabase.layer ++ TestLayers.withClock(testClockAt)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val periodStart = Instant.parse("2026-05-07T14:00:00Z")
  private val periodEnd   = Instant.parse("2026-05-07T14:05:00Z")
  private val knownMac    = "aa:bb:cc:11:22:33"

  private def seedRouter(rRepo: RouterRepo): Task[(RouterId, String)] = {
    val token = "ROUTER_TOKEN_PLAIN"
    val hash  = Sha256Hex.unsafe(RouterAuth.sha256Hex(token))
    for {
      id <- rRepo.create("test-router", Sha256Hex.unsafe("m" * 64))
      _  <- rRepo.completeEnrollment(id, hash)
    } yield (id, token)
  }

  private def seedKnownDevice(dRepo: DeviceRepo, profileRepo: ProfileRepo): Task[Unit] =
    for {
      pid <- profileRepo.create("Kids", List(BlocklistId.unsafe("adult")))
      _   <- dRepo.upsert(MacAddress.unsafe(knownMac), "kid-ipad", Some(pid), "192.168.1.10")
    } yield ()

  /**
   * A [[WebSocketChannel]] stand-in whose `send` simply succeeds — enough for the registry's push
   * path, which only needs the send to resolve OK before it stamps the delivered etag (#2619). A
   * socket is external I/O, the one stand-in `docs/process/testing.md` allows; every other
   * collaborator in those tests (registry, delivery sink, repo, Postgres) is real, and the
   * end-to-end path over a REAL client and server is pinned by the socket-driving tests below.
   */
  private class SendOnlyChannel extends WebSocketChannel {
    def awaitShutdown(implicit trace: Trace): UIO[Unit]                                 = ZIO.unit
    def receive(implicit trace: Trace): Task[WebSocketChannelEvent]                     = ZIO.never
    def receiveAll[Env, Err](f: WebSocketChannelEvent => ZIO[Env, Err, Any])(
        implicit trace: Trace,
    ): ZIO[Env, Err, Unit] = ZIO.never
    def send(in: WebSocketChannelEvent)(implicit trace: Trace): Task[Unit]              = ZIO.unit
    def sendAll(in: Iterable[WebSocketChannelEvent])(implicit trace: Trace): Task[Unit] = ZIO.unit
    def shutdown(implicit trace: Trace): UIO[Unit]                                      = ZIO.unit
  }

  private def sendOnlyChannel: UIO[WebSocketChannel] = ZIO.succeed(new SendOnlyChannel)

  /**
   * A [[SendOnlyChannel]] that also RECORDS the text of every frame handed to it. `SendOnlyChannel`
   * proves a push resolved OK; this proves WHO it reached, which is what #2630 turns on — a leak is
   * a frame arriving at a channel that should never have been a recipient, and a channel that only
   * swallows sends cannot tell that apart from no frame at all.
   */
  private final class RecordingChannel(sent: Ref[Chunk[String]]) extends SendOnlyChannel {
    override def send(in: WebSocketChannelEvent)(implicit trace: Trace): Task[Unit] =
      in match {
        case ChannelEvent.Read(WebSocketFrame.Text(t)) => sent.update(_ :+ t)
        case _                                         => ZIO.unit
      }
  }

  /** A recording channel plus the handle to read back what it was sent. */
  private def recordingChannel: UIO[(WebSocketChannel, Ref[Chunk[String]])] =
    Ref.make(Chunk.empty[String]).map(r => (new RecordingChannel(r), r))

  /**
   * The cumulative `router_ws_etag_stamp_total{outcome=...}` count (#2619), read straight off the
   * ZIO metric registry with the same key `MetricGuard.counter` writes. Asserted as a DELTA around
   * the action, not absolutely: the registry is JVM-global and this counter is additive, so an
   * absolute assertion would couple this spec to whatever else in the suite pushed a policy frame.
   * The read is synchronous — there is no Prometheus publisher or snapshot listener to wait on, so
   * no wall-clock polling is involved (`docs/process/testing.md`, #2042).
   *
   * The delta is exact rather than a lower bound, which holds only because this suite carries
   * `TestAspect.sequential` (see the bottom of `spec`) and mill runs one spec class at a time. If
   * either changes, a concurrent spec pushing a policy frame would inflate these deltas — relax
   * them to `>=` at that point rather than deleting them.
   */
  private def etagStampTotal(outcome: String): UIO[Double] =
    Metric.counter("router_ws_etag_stamp_total").tagged("outcome", outcome).value.map(_.count)

  /** `router_ws_policy_push_total{result=...}` — same delta discipline as [[etagStampTotal]]. */
  private def policyPushTotal(result: String): UIO[Double] =
    Metric.counter("router_ws_policy_push_total").tagged("result", result).value.map(_.count)

  /** A minimal snapshot for tests that only need SOMETHING with an etag to push. */
  private val emptySnapshot = PolicySnapshot(
    etag = ETag.unsafe("etag-2619-probe"),
    generatedAt = "2026-05-07T14:00:00Z",
    devices = Map.empty,
    profiles = Map.empty,
    blocklists = Map.empty,
  )

  /** The same PolicyServiceLive wiring [[buildWsRoutes]] uses, for a test that needs a snapshot. */
  private def buildPolicyService =
    for {
      dRepo <- ZIO.service[DeviceRepo]
      hsr   <- ZIO.service[HouseholdSettingsRepo]
      pRepo <- ZIO.service[ProfileRepo]
      tlr   <- ZIO.service[TimeLimitRepo]
      atlr  <- ZIO.service[AppTimeLimitRepo]
      er    <- ZIO.service[TimeExtensionRepo]
      ar    <- ZIO.service[AppRepo]
      clk   <- ZIO.service[Clock]
      blr   <- ZIO.service[BlocklistRepo]
      tRepo <- ZIO.service[TrafficReportRepo]
    } yield PolicyServiceLive(pRepo, hsr, tlr, atlr, dRepo, blr, tRepo, er, ar, clk)

  private def buildWsRoutes =
    for {
      rRepo   <- ZIO.service[RouterRepo]
      tRepo   <- ZIO.service[TrafficReportRepo]
      tu      <- ZIO.service[TimeUsageRepo]
      dRepo   <- ZIO.service[DeviceRepo]
      cRepo   <- ZIO.service[ConnectionEventRepo]
      aRepo   <- ZIO.service[AlertRepo]
      hsr     <- ZIO.service[HouseholdSettingsRepo]
      metrics <- RouterMetricsService.make
      reg     <- RouterWsRegistry.make(rRepo.touchEtag)
      // #1849: a PolicyService so the ws endpoint can push the current snapshot on connect. Cache
      // off here (the `apply` default) — the connect push reads `snapshot`, which builds fresh.
      policy  <- buildPolicyService
      auth   = new RouterAuthLive(rRepo)
      ingest = new RouterIngestService(rRepo, tRepo, tu, dRepo, cRepo, aRepo, hsr)
      // Mount the ws route through the SAME aspect stack `Main` wraps the router routes in
      // (HttpMetrics.instrument → LoggingMiddleware.annotate → Readiness.gate → ErrorBoundary.observe)
      // so the test proves the HTTP/1.1 upgrade survives the production middleware, not just the raw
      // route. These aspects are response-transparent for a websocket Response, but pinning it here
      // closes the gap between the test path and the assembled prod path.
      raw    = RouterWsRoutes.routes(auth, reg, ingest, metrics, rRepo, policy)
      routes = HttpMetrics.instrument(
        LoggingMiddleware.annotate(
          Readiness.gate(ErrorBoundary.observe(raw), ZIO.succeed(true)),
        ),
      )
    } yield (routes, reg)

  /**
   * Open a ws connection to the bound server, drive it with `send` on handshake, and collect every
   * text frame the server sends. Resolves once a frame satisfies `until` (so a test can wait for
   * the specific `ack`/`pong`/`policy` it cares about, ignoring the others — #1849's connect push
   * means a `policy` frame now arrives first, ahead of any ack/pong). Returns the matching frame,
   * ALL frames received up to that point, and the probe result. Connects with the given bearer
   * header.
   *
   * `probe` takes `Client` (rather than `Any`) purely so it can itself be another
   * `connectAndCapture` — the #2561 case nests a second real connection inside the first one's
   * scope. `ZIO` is contravariant in `R`, so every existing `ZIO.unit` / `reg.isConnected` probe
   * still fits unchanged; this widens what a probe may do, it does not weaken any assertion.
   */
  private def connectAndCapture[B](
      port: Int,
      bearer: Option[String],
      send: WebSocketChannel => ZIO[Any, Throwable, Unit],
      probe: ZIO[Client, Throwable, B],
      until: String => Boolean = _ => true,
  ): ZIO[Client, Throwable, (String, Chunk[String], B)] =
    for {
      matched <- Promise.make[Nothing, String]
      frames  <- Ref.make(Chunk.empty[String])
      app     = Handler.webSocket { channel =>
        channel.receiveAll {
          case ChannelEvent.UserEventTriggered(UserEvent.HandshakeComplete) =>
            send(channel)
          case ChannelEvent.Read(WebSocketFrame.Text(t))                    =>
            frames.update(_ :+ t) *> ZIO.when(until(t))(matched.succeed(t)).unit
          case _                                                            =>
            ZIO.unit
        }
      }
      headers = bearer.fold(Headers.empty)(t => Headers(Header.Authorization.Bearer(t)))
      // Drive the connection in a LOCAL scope closed as soon as we have the server's reply, so the
      // ws fiber is interrupted promptly and the test does not wait on connection teardown. `probe`
      // runs INSIDE the scope (connection still open) so a registry-liveness check observes the live
      // channel rather than racing the post-scope deregister.
      result <- ZIO.scoped {
        for {
          _   <- app.connect(s"ws://localhost:$port/api/router/ws", headers).forkScoped
          t   <- matched.await
            .timeoutFail(new RuntimeException("no matching server frame within 30s"))(30.seconds)
          all <- frames.get
          b   <- probe
        } yield (t, all, b)
      }
    } yield result

  def spec = suite("Router websocket /api/router/ws")(
    test("rejects the upgrade with 401 when the bearer token is missing or invalid") {
      for {
        _           <- cleanDb
        (routes, _) <- buildWsRoutes
        noToken     <- routes.runZIO(
          Request.get(URL.decode("/api/router/ws").toOption.get),
        )
        badToken    <- routes.runZIO(
          Request
            .get(URL.decode("/api/router/ws").toOption.get)
            .addHeader(Header.Authorization.Bearer("not-a-real-token")),
        )
      } yield assertTrue(noToken.status == Status.Unauthorized) &&
        assertTrue(badToken.status == Status.Unauthorized)
    },
    test("a usage frame ingests through the shared service and the server acks ok") {
      (for {
        _             <- cleanDb
        rRepo         <- ZIO.service[RouterRepo]
        pRepo         <- ZIO.service[ProfileRepo]
        dRepo         <- ZIO.service[DeviceRepo]
        tRepo         <- ZIO.service[TrafficReportRepo]
        _             <- seedKnownDevice(dRepo, pRepo)
        (id, tk)      <- seedRouter(rRepo)
        (routes, reg) <- buildWsRoutes
        port          <- Server.install(routes)
        rec       = UsageRecord(
          MacAddress.unsafe(knownMac),
          Some(IpAddress.unsafe("192.168.1.10")),
          HostId.Fqdn(Hostname.unsafe("youtube.com")),
          240L,
          1000L,
          500L,
        )
        usageBody = UsageReport(id, periodStart.toString, periodEnd.toString, List(rec)).toJson
        frame     = s"""{"op":"usage","seq":7,"payload":$usageBody}"""
        result <- connectAndCapture(
          port,
          Some(tk),
          ch => ch.send(ChannelEvent.read(WebSocketFrame.text(frame))),
          reg.isConnected(id),
          until = _.contains("\"op\":\"ack\""),
        )
        (ack, all, connected) = result
        rows <- tRepo.listForRouter(id, 100)
      } yield assertTrue(ack.contains("\"op\":\"ack\"")) &&
        assertTrue(ack.contains("\"status\":\"ok\"")) &&
        assertTrue(ack.contains("\"seq\":7")) &&
        // #1849: the connect-time policy push arrives before the ack.
        assertTrue(all.exists(_.contains("\"op\":\"policy\""))) &&
        assertTrue(rows.size == 1) &&
        assertTrue(connected)).provideSome[
        TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task],
      ](Server.defaultWithPort(0), Client.default)
    },
    test("#2268: a usage frame SPLIT across Text(non-final)+Continuation frames is reassembled") {
      // The mirror of #1959: an intermediary (Render's edge) fragments a large frame at ~4 KiB, so a
      // single logical `usage` message arrives as `Text(isFinal=false)` + `Continuation…`. Before the
      // reassembler, the server decoded only the truncated first fragment ("Unexpected end of input")
      // and ingested nothing. Here we send the identical usage message as THREE ws frames and assert
      // it still acks ok and lands exactly one traffic_report row.
      (for {
        _             <- cleanDb
        rRepo         <- ZIO.service[RouterRepo]
        pRepo         <- ZIO.service[ProfileRepo]
        dRepo         <- ZIO.service[DeviceRepo]
        tRepo         <- ZIO.service[TrafficReportRepo]
        _             <- seedKnownDevice(dRepo, pRepo)
        (id, tk)      <- seedRouter(rRepo)
        (routes, reg) <- buildWsRoutes
        port          <- Server.install(routes)
        rec       = UsageRecord(
          MacAddress.unsafe(knownMac),
          Some(IpAddress.unsafe("192.168.1.10")),
          HostId.Fqdn(Hostname.unsafe("youtube.com")),
          240L,
          1000L,
          500L,
        )
        usageBody = UsageReport(id, periodStart.toString, periodEnd.toString, List(rec)).toJson
        frame     = s"""{"op":"usage","seq":9,"payload":$usageBody}"""
        // Fragment the message into a lead Text(fin=false) + two Continuation frames (the last
        // fin=true), the RFC 6455 §5.4 shape an intermediary produces. Bytes are UTF-8 (ASCII here).
        thirds    = frame.grouped(math.max(1, frame.length / 3)).toList
        bytesOf   = (s: String) =>
          Chunk.fromArray(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        result <- connectAndCapture(
          port,
          Some(tk),
          ch =>
            ch.send(ChannelEvent.read(WebSocketFrame.Text(thirds.head, isFinal = false))) *>
              ZIO.foreachDiscard(thirds.tail.init)(part =>
                ch.send(
                  ChannelEvent.read(WebSocketFrame.Continuation(bytesOf(part), isFinal = false)),
                ),
              ) *>
              ch.send(
                ChannelEvent.read(WebSocketFrame.Continuation(bytesOf(thirds.last), isFinal = true)),
              ),
          reg.isConnected(id),
          until = _.contains("\"op\":\"ack\""),
        )
        (ack, _, _) = result
        rows <- tRepo.listForRouter(id, 100)
      } yield assertTrue(ack.contains("\"op\":\"ack\"")) &&
        assertTrue(ack.contains("\"status\":\"ok\"")) &&
        assertTrue(ack.contains("\"seq\":9")) &&
        // The reassembled body ingested exactly one row — proof the continuation frames were rejoined.
        assertTrue(rows.size == 1)).provideSome[
        TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task],
      ](Server.defaultWithPort(0), Client.default)
    },
    test("a ping frame is answered with a pong") {
      (for {
        _           <- cleanDb
        rRepo       <- ZIO.service[RouterRepo]
        (id, tk)    <- seedRouter(rRepo)
        (routes, _) <- buildWsRoutes
        port        <- Server.install(routes)
        result      <- connectAndCapture(
          port,
          Some(tk),
          ch => ch.send(ChannelEvent.read(WebSocketFrame.text("""{"op":"ping"}"""))),
          ZIO.unit,
          until = _.contains("\"op\":\"pong\""),
        )
        pong = result._1
      } yield assertTrue(pong.contains("\"op\":\"pong\""))).provideSome[
        TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task],
      ](Server.defaultWithPort(0), Client.default)
    },
    test("#1849: a freshly-connected router is pushed the current policy snapshot on connect") {
      (for {
        _           <- cleanDb
        rRepo       <- ZIO.service[RouterRepo]
        pRepo       <- ZIO.service[ProfileRepo]
        dRepo       <- ZIO.service[DeviceRepo]
        _           <- seedKnownDevice(dRepo, pRepo)
        (_, tk)     <- seedRouter(rRepo)
        (routes, _) <- buildWsRoutes
        port        <- Server.install(routes)
        // Send nothing; just wait for the server's unsolicited first-policy push.
        result      <- connectAndCapture(
          port,
          Some(tk),
          _ => ZIO.unit,
          ZIO.unit,
          until = _.contains("\"op\":\"policy\""),
        )
        policyFrame = result._1
      } yield
      // The pushed frame is the `{op:"policy", payload:<snapshot>}` envelope carrying the real
      // snapshot — it must include the device the snapshot enumerates and an etag.
      assertTrue(policyFrame.contains("\"op\":\"policy\"")) &&
        assertTrue(policyFrame.contains("\"etag\"")) &&
        assertTrue(policyFrame.contains(knownMac))).provideSome[
        TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task],
      ](Server.defaultWithPort(0), Client.default)
    },
    test("#2619: a ws policy push stamps routers.last_etag with the etag it delivered") {
      // `last_etag` is "which policy version does this router have" — it backs the operator's
      // "who is on current policy?" view. Only the REST poll ever wrote it (RouterRoutes passes
      // Some(snap.etag) to `touch`); the ws path called `touch(id, None, None)` and the repo's
      // `last_etag = COALESCE($etag, last_etag)` left the column alone. Survivable while ws was
      // opt-in; #2608 made ws the shipped default and the poll goes DORMANT on a healthy link
      // (#2037), so the column freezes for the whole fleet.
      //
      // Driven through the registry's push entrypoints directly rather than over a live socket.
      // The stamp runs inside the push effect, so awaiting THAT effect is what makes the assertion
      // deterministic — reading the row after a frame arrives at a client instead would race, since
      // the server's HandshakeComplete handler and its first Read are not serialised against each
      // other (no wall-clock waiting either way: docs/process/testing.md, #2042). The registry, the
      // delivery sink and the repo are all real, against embedded Postgres; only the socket is
      // stubbed, which is the external-I/O carve-out the testing doc allows.
      for {
        _         <- cleanDb
        rRepo     <- ZIO.service[RouterRepo]
        pRepo     <- ZIO.service[ProfileRepo]
        dRepo     <- ZIO.service[DeviceRepo]
        _         <- seedKnownDevice(dRepo, pRepo)
        (rid, _)  <- seedRouter(rRepo)
        // A freshly-enrolled router has never fetched policy, so the column starts NULL — that is
        // what makes the post-push value attributable to the push and nothing else.
        beforeRow <- rRepo.findById(rid)
        policySvc <- buildPolicyService
        snap      <- policySvc.snapshot(HouseholdId.Default)
        (_, reg)  <- buildWsRoutes
        ch        <- sendOnlyChannel
        okPre     <- etagStampTotal("ok")
        _         <- reg.register(rid, HouseholdId.Default, ch)
        _         <- reg.pushPolicyTo(rid, ch, HouseholdScoped(HouseholdId.Default, snap))
        afterPush <- rRepo.findById(rid)
        // The push-on-change fan-out (publishPolicy) must stamp too — it is the path that carries
        // the fleet once the poll is dormant, and it is a DIFFERENT call into sendPolicyFrame. It
        // has to carry a DIFFERENT etag to be worth asserting: re-pushing `snap` would leave the
        // column on the value `pushPolicyTo` already wrote, and the assertion would pass even if
        // this path stamped nothing. (Resetting the column first does not work either — the repo's
        // `last_etag = COALESCE($etag, last_etag)` makes `touch(id, None, None)` a no-op on it.)
        fanSnap = snap.copy(etag = ETag.unsafe("etag-2619-fanout"))
        _        <- reg.publishPolicy(HouseholdScoped(HouseholdId.Default, fanSnap))
        afterFan <- rRepo.findById(rid)
        okPost   <- etagStampTotal("ok")
        // ...and last_seen_at must NOT have moved. That column means "we heard from this router" —
        // every other writer of it is router-triggered (poll, ingest, ws heartbeat), and it backs
        // `agent_connected_routers` plus the SPA's connected badge. A server-initiated push feeding
        // it would let the server hold the gauge green for a router whose socket went half-open
        // (#2561) and whose sends still succeed into the local buffer. The fix for that is a wiring
        // choice — `make(routerRepo.touchEtag)` rather than `make(… routerRepo.touch …)` — and
        // without this assertion every test here would pass under the wrong one.
      } yield assertTrue(beforeRow.exists(_.lastEtag.isEmpty)) &&
        assertTrue(afterPush.flatMap(_.lastSeenAt) == beforeRow.flatMap(_.lastSeenAt)) &&
        assertTrue(afterFan.flatMap(_.lastSeenAt) == beforeRow.flatMap(_.lastSeenAt)) &&
        assertTrue(okPost - okPre == 2.0) &&
        assertTrue(afterPush.flatMap(_.lastEtag).contains(snap.etag)) &&
        assertTrue(afterFan.flatMap(_.lastEtag).contains(fanSnap.etag))
      // No Server/Client layers here, unlike the socket-driving tests around it: this exercises the
      // registry's push entrypoints directly, so it needs neither.
    },
    test("#2630: a policy push reaches only the publishing household's routers") {
      // The beta-launch blocker. `publishPolicy` iterated the WHOLE registry and sent the same
      // frame to every connected channel, so a router in household B was handed household A's
      // snapshot — device names, MAC addresses, profile names, blocked hosts — and, being a dumb
      // applier, wrote it to /etc/wifihaven/policy.json and into its nft ruleset. Observed on live
      // hardware (#2630): a test-household router carried the operator's family household's 33
      // device names and real MACs for ~8 minutes.
      //
      // What makes this test the one that would have caught it: it asserts on DELIVERY, per
      // channel. The #2619 test below asserts only that the DURABLE `last_etag` write is refused,
      // which was true throughout the leak — the frame still went out.
      for {
        _         <- cleanDb
        rRepo     <- ZIO.service[RouterRepo]
        pRepo     <- ZIO.service[ProfileRepo]
        dRepo     <- ZIO.service[DeviceRepo]
        hRepo     <- ZIO.service[HouseholdRepo]
        _         <- seedKnownDevice(dRepo, pRepo)
        // Household A = the default household, with the seeded device/profile; household B = a
        // second tenant whose router is connected at the same time. Both routers are real rows.
        (ridA, _) <- seedRouter(rRepo)
        hhB       <- hRepo.create("Other household", "other-household")
        ridB      <- rRepo.create("other-router", Sha256Hex.unsafe("n" * 64), hhB)
        _ <- rRepo.completeEnrollment(ridB, Sha256Hex.unsafe(RouterAuth.sha256Hex("TOKEN_B")))
        policySvc    <- buildPolicyService
        snapA        <- policySvc.snapshot(HouseholdId.Default)
        (_, reg)     <- buildWsRoutes
        (chA, sentA) <- recordingChannel
        (chB, sentB) <- recordingChannel
        _            <- reg.register(ridA, HouseholdId.Default, chA)
        _            <- reg.register(ridB, hhB, chB)
        _            <- reg.publishPolicy(HouseholdScoped(HouseholdId.Default, snapA))
        framesA      <- sentA.get
        framesB      <- sentB.get
        // A's router got the policy frame, and it is the real snapshot (the seeded MAC rides it) —
        // so a "fix" that simply stopped pushing would not pass.
      } yield assertTrue(framesA.size == 1) &&
        assertTrue(framesA.head.contains("\"op\":\"policy\"")) &&
        assertTrue(framesA.head.contains(knownMac)) &&
        // ...and B's router got NOTHING. Not a redacted frame, not an empty one: no frame.
        assertTrue(framesB.isEmpty)
    },
    test("#2630: a first-policy push carrying another household's snapshot is refused") {
      // The other push entrypoint. `publishPolicy` gets its household from the snapshot it is
      // handed and matches it against each registry entry; `pushPolicyTo` targets ONE channel, and
      // #2619 originally let the caller assert that channel's household — a caller that can assert
      // its household can assert the wrong one. It now reads the household from the REGISTRY entry,
      // so the wrong snapshot cannot be delivered even by a caller that insists.
      //
      // Before #2630 this case delivered the frame and refused only the durable `last_etag` write
      // (metered `router_ws_etag_stamp_total{outcome="household_mismatch"}`). The frame was the
      // leak, so the refusal moved to the delivery.
      for {
        _     <- cleanDb
        rRepo <- ZIO.service[RouterRepo]
        pRepo <- ZIO.service[ProfileRepo]
        dRepo <- ZIO.service[DeviceRepo]
        hRepo <- ZIO.service[HouseholdRepo]
        _     <- seedKnownDevice(dRepo, pRepo)
        other <- hRepo.create("Other household", "other-household")
        ridB  <- rRepo.create("other-router", Sha256Hex.unsafe("n" * 64), other)
        _     <- rRepo.completeEnrollment(ridB, Sha256Hex.unsafe(RouterAuth.sha256Hex("TOKEN_B")))
        policySvc    <- buildPolicyService
        snapA        <- policySvc.snapshot(HouseholdId.Default)
        (_, reg)     <- buildWsRoutes
        (ch, sent)   <- recordingChannel
        mismatchPre  <- policyPushTotal("household_mismatch")
        okPre        <- policyPushTotal("ok")
        _            <- reg.register(ridB, other, ch)
        _            <- reg.pushPolicyTo(ridB, ch, HouseholdScoped(HouseholdId.Default, snapA))
        frames       <- sent.get
        after        <- rRepo.findById(ridB)
        mismatchPost <- policyPushTotal("household_mismatch")
        okPost       <- policyPushTotal("ok")
        // Nothing delivered, nothing written, and the refusal is visible. The counter matters
        // because this is now a should-never-happen: the caller reads
        // `policy.snapshot(router.householdId)`, so a non-zero rate is a caller bug, and a silent
        // skip would leave a router with no first policy and no signal saying why.
      } yield assertTrue(frames.isEmpty) &&
        assertTrue(after.exists(_.lastEtag.isEmpty)) &&
        assertTrue(mismatchPost - mismatchPre == 1.0) &&
        assertTrue(okPost - okPre == 0.0)
    },
    test("#2619: a failed stamp is metered and does not tear down the push") {
      // The stamp is a best-effort side-write hanging off the push path. A DB hiccup must never
      // kill a policy delivery — but per `docs/process/no-dark-by-default.md` it must not fail
      // INVISIBLY either: nothing else on the push path would move if the write silently stopped
      // working, so `router_ws_etag_stamp_total{outcome="error"}` is the only signal that the
      // operator's "who is on current policy?" view has started going stale.
      //
      // Both failure MODES are pinned, because they need different handling and only one of them is
      // obvious. A typed failure is what `foldZIO` would catch. A DEFECT is not: it would kill the
      // fiber, and since `publishPolicy` runs the per-channel sends under `foreachDiscard`, one
      // dying sink would abandon the fan-out for every router not yet reached — strictly worse than
      // the failure the guard exists for. Hence `foldCauseZIO` in the registry, and hence the second
      // arm here.
      //
      // The sink is the injected failure; the registry, the channel accounting and the metric
      // registry are real. Each push effect is awaited, so the counter reads are ordered after the
      // stamp attempt rather than racing it. Two routers so the fan-out has somewhere to continue to.
      for {
        ridA     <- ZIO.succeed(RouterId(UUID.fromString("6b1f0f2c-6b3e-4a1a-9a8f-2f1c0b4d9e77")))
        ridB     <- ZIO.succeed(RouterId(UUID.fromString("0d1a5c47-9b8e-4f30-8c25-7a6e3d2b1f04")))
        okPre    <- etagStampTotal("ok")
        errPre   <- etagStampTotal("error")
        failing  <- RouterWsRegistry.make((_, _) => ZIO.fail(new RuntimeException("db down")))
        chA      <- sendOnlyChannel
        _        <- failing.register(ridA, HouseholdId.Default, chA)
        // Completes normally: the failure is caught inside the registry, not propagated.
        _        <- failing.publishPolicy(HouseholdScoped(HouseholdId.Default, emptySnapshot))
        // The channel is still registered — a stamp failure is not a delivery failure, so it must
        // not deregister the router the way a `channel_closed` send failure does.
        stillA   <- failing.isConnected(ridA)
        dying    <- RouterWsRegistry.make((_, _) => ZIO.die(new RuntimeException("sink defect")))
        chB      <- sendOnlyChannel
        chC      <- sendOnlyChannel
        _        <- dying.register(ridA, HouseholdId.Default, chB)
        _        <- dying.register(ridB, HouseholdId.Default, chC)
        // Must not die, and must reach BOTH routers: two `error` samples, not one.
        _        <- dying.publishPolicy(HouseholdScoped(HouseholdId.Default, emptySnapshot))
        // Third mode: a sink that THROWS while building its effect rather than returning a failed
        // or dying one. `foldCauseZIO` alone cannot see that — the throw happens before there is an
        // effect to fold over — so it relies on the `suspendSucceed` in `stampEtag`. Without that,
        // this escapes into `publishPolicy`'s `foreachDiscard` and takes the fan-out down.
        throwing <- RouterWsRegistry.make((_, _) => throw new RuntimeException("sink threw"))
        chD      <- sendOnlyChannel
        _        <- throwing.register(ridA, HouseholdId.Default, chD)
        _        <- throwing.publishPolicy(HouseholdScoped(HouseholdId.Default, emptySnapshot))
        okPost   <- etagStampTotal("ok")
        errPost  <- etagStampTotal("error")
      } yield assertTrue(errPost - errPre == 4.0) &&
        assertTrue(okPost - okPre == 0.0) &&
        assertTrue(stillA)
    },
    test("#2630: a push to an unregistered router sends nothing") {
      // `pushPolicyTo` reads the recipient household from the registry entry, so an id with no
      // entry has no establishable household — and an unverifiable delivery is precisely what
      // #2630 was. So the frame is NOT sent, and the refusal is metered rather than silent.
      //
      // This inverts the #2619 behaviour, which delivered the frame and skipped only the
      // `last_etag` stamp: back then the household guard sat on the write, so the delivery was
      // never the thing being gated. `pushPolicyTo` documents the matching precondition; the
      // production caller (`RouterWsRoutes`) registers before it pushes, so nothing in the fleet
      // relies on the old behaviour.
      // No device seed: this pushes `emptySnapshot` rather than building one, so no profile or
      // device is read.
      for {
        _          <- cleanDb
        rRepo      <- ZIO.service[RouterRepo]
        (rid, _)   <- seedRouter(rRepo)
        okPre      <- etagStampTotal("ok")
        pushOkPre  <- policyPushTotal("ok")
        unregPre   <- policyPushTotal("unregistered")
        (_, reg)   <- buildWsRoutes
        (ch, sent) <- recordingChannel
        // Deliberately NO `register`.
        _          <- reg.pushPolicyTo(rid, ch, HouseholdScoped(HouseholdId.Default, emptySnapshot))
        frames     <- sent.get
        after      <- rRepo.findById(rid)
        okPost     <- etagStampTotal("ok")
        pushOkPost <- policyPushTotal("ok")
        unregPost  <- policyPushTotal("unregistered")
      } yield assertTrue(frames.isEmpty) &&
        assertTrue(pushOkPost - pushOkPre == 0.0) &&
        assertTrue(unregPost - unregPre == 1.0) &&
        assertTrue(after.exists(_.lastEtag.isEmpty)) &&
        assertTrue(okPost - okPre == 0.0)
    },
    test("#2561: a second connection for the same router supersedes the first (registry holds 1)") {
      // The prod leak: a router whose socket went half-open reconnects, the server still holds the
      // dead channel, and `router_ws_connections_active` reads 2 for one router — permanently, since
      // the stale channel's teardown never fires. Here both sockets are real and concurrently open
      // against the real endpoint; the registry must still report exactly ONE channel for the router.
      //
      // No wall-clock wait is needed to observe that (`docs/process/testing.md` — never poll real
      // time for async work; #2042): the server registers BEFORE it pushes the first policy frame
      // (`RouterWsRoutes.socketApp`: `register *> log *> snapshot.flatMap(pushPolicyTo)`), so a
      // client that has received `op:"policy"` has already observed its own registration. Both
      // `connectAndCapture`s gate on exactly that frame, so by the time the inner probe runs, both
      // registrations have completed. The return-to-zero half of the pin has no such client-visible
      // signal — deregistration happens on the server fiber after the socket closes — so it is
      // asserted deterministically in RouterWsConnectionsGaugeSpec instead of raced for here.
      (for {
        _             <- cleanDb
        rRepo         <- ZIO.service[RouterRepo]
        pRepo         <- ZIO.service[ProfileRepo]
        dRepo         <- ZIO.service[DeviceRepo]
        _             <- seedKnownDevice(dRepo, pRepo)
        (_, tk)       <- seedRouter(rRepo)
        (routes, reg) <- buildWsRoutes
        port          <- Server.install(routes)
        // Nest the two connections so BOTH are open at the same time when we sample the registry —
        // the first is still in scope while the second connects, exactly the overlap that leaked.
        counts        <- connectAndCapture(
          port,
          Some(tk),
          _ => ZIO.unit,
          connectAndCapture(
            port,
            Some(tk),
            _ => ZIO.unit,
            // Sampled with both sockets open, after both have seen their own `policy` push.
            reg.activeCount,
            until = _.contains("\"op\":\"policy\""),
          ).map(_._3),
          until = _.contains("\"op\":\"policy\""),
        ).map(_._3)
      } yield assertTrue(counts == 1)).provideSome[
        TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task],
      ](Server.defaultWithPort(0), Client.default)
    },
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(90.seconds)
}
