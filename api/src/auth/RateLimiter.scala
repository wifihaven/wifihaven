package wifihaven.api.auth

import wifihaven.shared.Clock
import zio.*

/**
 * Bounded in-memory fixed-window rate limiter keyed by an arbitrary string (source IP, IP+username,
 * etc). #2079 / #2081: app-layer defense against online brute-force (`/api/auth/login`) and
 * alert/notification flooding (`/api/access-requests`) — both unauthenticated, internet-facing
 * routes with no other rate limiting. A Cloudflare edge rate-limit rule is a separate, declarative
 * defense-in-depth layer for the cloud deploy; this covers the self-hosted path (and the cloud path
 * when the edge rule is absent/misses).
 *
 * Fixed window: at most `maxAttempts` calls to [[tryAcquire]] for a given key within `window`; once
 * the window elapses the count resets. Bounded key-space via `maxKeys` — a single-instance API
 * process holds at most this many distinct keys, evicting the oldest-window entry once exceeded, so
 * an attacker cycling source IPs can't grow this map unboundedly.
 */
trait RateLimiter {

  /**
   * Returns true if the call for `key` is allowed under the current window, false if rate-limited.
   */
  def tryAcquire(key: String): UIO[Boolean]
}

object RateLimiter {

  /** Test double: never limits. For specs that exercise a rate-limited route's OTHER behavior. */
  val allowAll: RateLimiter = _ => ZIO.succeed(true)
}

final case class RateLimiterLive(
    clock: Clock,
    state: Ref[Map[String, RateLimiterLive.Window]],
    maxAttempts: Int,
    windowSeconds: Long,
    maxKeys: Int,
) extends RateLimiter {
  import RateLimiterLive.Window

  def tryAcquire(key: String): UIO[Boolean] =
    clock.instant.map(_.getEpochSecond).flatMap { now =>
      state.modify { m =>
        val existing = m.get(key)
        val fresh    = existing.forall(w => now - w.startedAt >= windowSeconds)
        val allowed  = fresh || existing.exists(_.count < maxAttempts)
        val updated  =
          if fresh then Window(now, 1)
          else Window(existing.get.startedAt, existing.get.count + 1)
        val trimmed  =
          if !m.contains(key) && m.size >= maxKeys then
            // Bound the key-space: evict the single oldest-window entry before adding a new one.
            m.minByOption(_._2.startedAt).map(_._1).fold(m)(m - _)
          else m
        (allowed, if allowed then trimmed.updated(key, updated) else trimmed)
      }
    }
}

object RateLimiterLive {
  private[auth] case class Window(startedAt: Long, count: Int)

  def make(maxAttempts: Int, windowSeconds: Long, maxKeys: Int = 10000): URIO[Clock, RateLimiter] =
    for {
      clock <- ZIO.service[Clock]
      ref   <- Ref.make(Map.empty[String, Window])
    } yield RateLimiterLive(clock, ref, maxAttempts, windowSeconds, maxKeys)
}
