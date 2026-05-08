package familydns.traffic.unit

import familydns.shared.*
import familydns.shared.Clock
import familydns.traffic.*
import zio.{Clock as _, *}
import zio.test.*
import zio.test.Assertion.*

/**
 * Unit tests for session tracking logic.
 *
 * SessionTracker uses a tight inner loop with Scala mutable state (allowed by convention). These
 * tests verify accumulation, expiry, and domain apex extraction.
 */
object SessionTrackerSpec extends ZIOSpecDefault {

  private val cfg = TrafficConfig(
    interface = "eth0",
    sessionTimeoutSecs = 60,
    flushIntervalSecs = 30,
    location = "home",
  )

  private def makeTracker =
    ZIO.serviceWithZIO[Clock] { clock =>
      Ref.make(DnsCache.empty).map(ref => new SessionTracker(cfg, ref))
    }

  def spec = suite("SessionTracker")(
    test("new session is created on first packet") {
      for {
        tracker <- makeTracker
        _        = tracker.recordPacket("aa:bb:cc:dd:ee:ff", "142.250.80.46", 500)
        sessions = tracker.activeSessions
      } yield assertTrue(sessions.size == 1)
    }.provide(Clock.live),
    test("same mac+domain accumulates in one session") {
      for {
        tracker <- makeTracker
        mac      = "aa:bb:cc:dd:ee:ff"
        _        = tracker.recordPacket(mac, "142.250.80.46", 500) // google.com
        _        = tracker.recordPacket(mac, "142.250.80.46", 300)
        _        = tracker.recordPacket(mac, "142.250.80.46", 200)
        sessions = tracker.activeSessions
      } yield assertTrue(sessions.size == 1)
    }.provide(Clock.live),
    test("different MACs create separate sessions for same domain") {
      for {
        tracker <- makeTracker
        _        = tracker.recordPacket("aa:bb:cc:dd:ee:01", "142.250.80.46", 500)
        _        = tracker.recordPacket("aa:bb:cc:dd:ee:02", "142.250.80.46", 500)
        sessions = tracker.activeSessions
      } yield assertTrue(sessions.size == 2)
    }.provide(Clock.live),
    test("different domains create separate sessions for same MAC") {
      for {
        tracker <- makeTracker
        mac      = "aa:bb:cc:dd:ee:ff"
        _        = tracker.recordPacket(mac, "142.250.80.46", 500) // google.com
        _        = tracker.recordPacket(mac, "31.13.71.36", 500)   // facebook.com
        sessions = tracker.activeSessions
      } yield assertTrue(sessions.size == 2)
    }.provide(Clock.live),
    test("expired sessions are swept and accumulated in pending") {
      for {
        tracker <- makeTracker
        mac     = "aa:bb:cc:dd:ee:ff"
        // Record a packet, then manually expire the session by setting lastSeen to past
        _       = tracker.recordPacket(mac, "142.250.80.46", 1000)
        _       = tracker.forceExpireAll()
        _       = tracker.sweepExpired()
        pending = tracker.drainPending()
      } yield assertTrue(tracker.activeSessions.isEmpty) &&
        assertTrue(pending.nonEmpty)
    }.provide(Clock.live),
    test("drain clears pending after first call") {
      for {
        tracker <- makeTracker
        _      = tracker.recordPacket("aa:bb:cc:dd:ee:ff", "142.250.80.46", 1000)
        _      = tracker.forceExpireAll()
        _      = tracker.sweepExpired()
        first  = tracker.drainPending()
        second = tracker.drainPending()
      } yield assertTrue(first.nonEmpty) &&
        assertTrue(second.isEmpty)
    }.provide(Clock.live),
    test("pending increments are in minutes (min 1)") {
      for {
        tracker <- makeTracker
        _       = tracker.recordPacket("aa:bb:cc:dd:ee:ff", "142.250.80.46", 1000)
        _       = tracker.forceExpireAll()
        _       = tracker.sweepExpired()
        pending = tracker.drainPending()
      } yield assertTrue(pending.forall(_._4 >= 1))
    }.provide(Clock.live),
  )
}
