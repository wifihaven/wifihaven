package wifihaven.api.feature

import ch.qos.logback.classic.{Level, LoggerContext}
import ch.qos.logback.classic.spi.LoggingEvent
import com.github.loki4j.logback.{Loki4jAppender, MeteredLoki4jAppender, PipelineConfigAppenderBase}
import zio.*
import zio.test.*

import java.net.{InetSocketAddress, ServerSocket, Socket}

/**
 * #1885 (epic #1831): PROVE the loki4j appender is fail-open under load.
 *
 * The hard requirement from the architecture is that a slow/unreachable Loki must NEVER wedge the
 * request path. loki4j is async/drop-on-backpressure by construction: the calling thread only
 * offers an event to an in-memory buffer; a background thread batches and ships it, and when the
 * bounded `sendQueueMaxBytes` queue fills the appender DROPS events instead of blocking the caller.
 *
 * This spec demonstrates that with a real appender pointed at a Loki endpoint that accepts the TCP
 * connection but never responds (the worst case for a synchronous client — the request would hang
 * until `requestTimeoutMs`):
 *
 *   1. the appender sheds events (its drop counter climbs) — backpressure resolves to DROP, and 2.
 *      the calling thread keeps appending at full speed — 200k appends complete in well under the
 *      time a single blocked network send would take, so request latency is provably unaffected.
 *
 * Assertion (2) is the load-bearing one: it is the regression guard against anyone ever wrapping
 * this in a blocking/synchronous appender or flipping the queue to block-on-full. Assertion (1)
 * confirms the drop path the `loki_logs_dropped_total` metric (LokiDropMetrics) actually counts.
 */
object LokiAppenderFailOpenSpec extends ZIOSpecDefault {

  /** A ~400-byte message so the small send queue fills after a few hundred events, not millions. */
  private val message: String = "x" * 400

  /**
   * A server socket that accepts connections and then holds them open forever without writing any
   * response. A `java.net.http`-based loki4j sender will block on the response until its request
   * timeout — exactly the "Loki is a black hole" failure we must stay fail-open against.
   */
  private final class StallServer extends AutoCloseable {
    private val server                       = new ServerSocket()
    private val held: java.util.List[Socket] =
      java.util.Collections.synchronizedList(new java.util.ArrayList[Socket]())
    @volatile private var running            = true

    server.bind(new InetSocketAddress("127.0.0.1", 0))
    val port: Int = server.getLocalPort

    private val acceptor = new Thread(
      () =>
        while running do
          try {
            val s = server.accept()
            val _ = held.add(s) // never read/respond — just keep the socket open
          } catch { case _: Throwable => () },
      "loki-stall-acceptor",
    )
    acceptor.setDaemon(true)
    acceptor.start()

    def url: String = s"http://127.0.0.1:$port/loki/api/v1/push"

    override def close(): Unit = {
      running = false
      try server.close()
      catch { case _: Throwable => () }
      held.forEach { s =>
        try s.close()
        catch { case _: Throwable => () }
      }
    }
  }

  /**
   * Build a [[MeteredLoki4jAppender]] pointed at `url`, tuned so backpressure manifests quickly: a
   * tiny send queue, small fast-flushing batches, and a long request timeout so the background
   * sender stays parked on the very first (never-answered) batch for the whole test — the send
   * queue fills and stays full, so drops are continuous and deterministic.
   */
  private def buildAppender(ctx: LoggerContext, url: String): MeteredLoki4jAppender = {
    val http = new PipelineConfigAppenderBase.HttpCfg()
    http.setUrl(url)
    http.setRequestTimeoutMs(60000L) // sender stays stuck on batch #1 for the whole test
    http.setConnectionTimeoutMs(60000L)
    http.setUseProtobufApi(false)

    val batch = new PipelineConfigAppenderBase.BatchCfg()
    batch.setMaxItems(5)
    batch.setMaxBytes(8192)
    batch.setTimeoutMs(50L)            // flush partial batches fast so the queue fills quickly
    batch.setSendQueueMaxBytes(65536L) // small bounded queue → fills after a few hundred events
    batch.setDrainOnStop(false)        // never hold shutdown waiting on Loki

    val appender = new MeteredLoki4jAppender()
    appender.setContext(ctx)
    appender.setName("LOKI-failopen-test")
    appender.setLabels("service=test") // static label → no per-event label parsing
    // Disable structured-metadata (MDC) extraction: the synthetic LoggingEvents below come from a
    // bare LoggerContext with no slf4j MDC adapter wired, so the default `* = %%mdc` pattern would
    // NPE in extraction before events ever reach the async pipeline. The fail-open behaviour under
    // test (async buffer + drop-on-full) is independent of label/metadata extraction.
    appender.setStructuredMetadata(Loki4jAppender.DISABLE_SMD_PATTERN)
    appender.setHttp(http)
    appender.setBatch(batch)
    appender.start()
    appender
  }

  private def event(logger: ch.qos.logback.classic.Logger): LoggingEvent =
    new LoggingEvent(getClass.getName, logger, Level.INFO, message, null, Array.empty)

  /** Result of the imperative load run; asserted back in ZIO. */
  private final case class Outcome(droppedAfterFill: Long, phase2Millis: Long, droppedDelta: Long)

  private def runLoad: Task[Outcome] =
    ZIO.attemptBlocking {
      val ctx      = new LoggerContext()
      val server   = new StallServer()
      val appender = buildAppender(ctx, server.url)
      val logger   = ctx.getLogger("loki-failopen-test")
      val ev       = event(logger)
      try {
        // ── Phase 1: drive the appender until the bounded queue is full and it starts dropping.
        // The background encoder needs wall-clock to batch + offer + hit the stalled sender, so we
        // append in bursts with brief yields and poll the drop counter. The stalled server makes
        // this monotonic — once full, it stays full.
        val deadline         = java.lang.System.nanoTime() + 20_000_000_000L // 20s safety cap
        while appender.droppedEventsTotal == 0L && java.lang.System.nanoTime() < deadline do {
          var i = 0
          while i < 1000 do { appender.doAppend(ev); i += 1 }
          Thread.sleep(20L) // let the encoder fiber run
        }
        val droppedAfterFill = appender.droppedEventsTotal

        // ── Phase 2: with the appender now shedding, prove the calling thread is never blocked.
        // 200k appends against a black-hole Loki must complete near-instantly; a blocking/sync
        // appender would stall on the 60s-timeout sends and take effectively forever.
        val start        = java.lang.System.nanoTime()
        var j            = 0
        while j < 200_000 do { appender.doAppend(ev); j += 1 }
        val phase2Millis = (java.lang.System.nanoTime() - start) / 1_000_000L
        val droppedEnd   = appender.droppedEventsTotal

        Outcome(droppedAfterFill, phase2Millis, droppedEnd - droppedAfterFill)
      } finally {
        try appender.stop()
        catch { case _: Throwable => () }
        try server.close()
        catch { case _: Throwable => () }
        ctx.stop()
      }
    }

  def spec = suite("Loki appender fail-open under load (#1885)")(
    test("backpressure resolves to DROP and the calling thread is never blocked") {
      for {
        out <- runLoad
      } yield assertTrue(
        // (1) the appender actually shed events under backpressure (drop-on-full, not block).
        out.droppedAfterFill > 0L,
        // (1b) drops keep accruing while Loki is a black hole.
        out.droppedDelta > 0L,
        // (2) 200k appends against an unreachable Loki finished fast — request latency is
        // unaffected. Wildly generous bound (real runs are tens of ms); a blocking appender would
        // be orders of magnitude over this.
        out.phase2Millis < 5000L,
      )
    } @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds),
  )
}
