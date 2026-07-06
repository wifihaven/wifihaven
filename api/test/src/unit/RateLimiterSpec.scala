package wifihaven.api.unit

import wifihaven.api.auth.RateLimiterLive
import wifihaven.shared.Clock
import zio.*
import zio.test.*

/**
 * #2079/#2081: the bounded in-memory fixed-window limiter behind the login and access-requests rate
 * limits.
 */
object RateLimiterSpec extends ZIOSpecDefault {

  private def makeLimiter(maxAttempts: Int, windowSeconds: Long, maxKeys: Int = 10000) =
    for {
      pair <- Clock.TestClock.makeWithControl(Clock.TestClock.schoolDayAfternoon)
      (clock, testClock) = pair
      limiter <- RateLimiterLive
        .make(maxAttempts, windowSeconds, maxKeys)
        .provideEnvironment(ZEnvironment(clock))
    } yield (limiter, testClock)

  def spec = suite("RateLimiterLive")(
    test("allows up to maxAttempts calls within the window, then blocks") {
      for {
        pair <- makeLimiter(maxAttempts = 3, windowSeconds = 60)
        (limiter, _) = pair
        results <- ZIO.foreach(1 to 4)(_ => limiter.tryAcquire("k"))
      } yield assertTrue(results == List(true, true, true, false))
    },
    test("different keys have independent budgets") {
      for {
        pair <- makeLimiter(maxAttempts = 1, windowSeconds = 60)
        (limiter, _) = pair
        a1 <- limiter.tryAcquire("a")
        b1 <- limiter.tryAcquire("b")
        a2 <- limiter.tryAcquire("a")
      } yield assertTrue(a1) && assertTrue(b1) && assertTrue(!a2)
    },
    test("the window resets once elapsed, allowing further calls") {
      for {
        pair <- makeLimiter(maxAttempts = 1, windowSeconds = 60)
        (limiter, clock) = pair
        first  <- limiter.tryAcquire("k")
        _      <- clock.advance(java.time.Duration.ofSeconds(61))
        second <- limiter.tryAcquire("k")
      } yield assertTrue(first) && assertTrue(second)
    },
    test("bounds the key-space: a fresh key evicts the oldest tracked entry once maxKeys is hit") {
      for {
        pair <- makeLimiter(maxAttempts = 5, windowSeconds = 60, maxKeys = 2)
        (limiter, _) = pair
        _      <- limiter.tryAcquire("a")
        _      <- limiter.tryAcquire("b")
        _      <- limiter.tryAcquire("c") // evicts "a" (oldest)
        // "a" was evicted, so it gets a fresh budget as if never seen.
        aAgain <- limiter.tryAcquire("a")
      } yield assertTrue(aAgain)
    },
  )
}
