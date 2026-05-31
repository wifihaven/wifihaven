package wifihaven.api.feature

import wifihaven.api.Bootstrap
import zio.*
import zio.test.*

object BootstrapSpec extends ZIOSpecDefault {

  def spec = suite("Bootstrap.boot")(
    test("starts the HTTP server before the warm-up chain completes") {
      for {
        warmupStarted <- Promise.make[Nothing, Unit]
        warmupBlocker <- Promise.make[Nothing, Unit]
        serverStarted <- Promise.make[Nothing, Unit]
        warmup = warmupStarted.succeed(()) *> warmupBlocker.await
        serve  = serverStarted.succeed(()) *> ZIO.never
        fiber <- Bootstrap.boot(warmup, serve).fork
        // Server must signal "started" without the warm-up ever finishing.
        // If boot is serial (warmup before serve), this await will hang
        // because warmupBlocker is never completed.
        ok    <- serverStarted.await.timeout(5.seconds)
        _     <- fiber.interrupt
      } yield assertTrue(ok.isDefined)
    },
    test("warm-up still runs alongside serve (not silently dropped)") {
      for {
        warmupStarted <- Promise.make[Nothing, Unit]
        serveStarted  <- Promise.make[Nothing, Unit]
        warmup = warmupStarted.succeed(()).unit
        serve  = serveStarted.succeed(()) *> ZIO.never
        fiber <- Bootstrap.boot(warmup, serve).fork
        ok    <- warmupStarted.await.timeout(5.seconds)
        _     <- serveStarted.await.timeout(5.seconds)
        _     <- fiber.interrupt
      } yield assertTrue(ok.isDefined)
    },
    test("warm-up failure does not crash the server fiber") {
      for {
        serveStarted <- Promise.make[Nothing, Unit]
        warmup = ZIO.fail(new RuntimeException("simulated cold-PG warm-up failure"))
        serve  = serveStarted.succeed(()) *> ZIO.never
        fiber <- Bootstrap.boot(warmup, serve).fork
        ok    <- serveStarted.await.timeout(5.seconds)
        _     <- fiber.interrupt
      } yield assertTrue(ok.isDefined)
    },
  )
}
