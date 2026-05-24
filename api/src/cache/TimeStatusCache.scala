package wifihaven.api.cache

import wifihaven.shared.*
import wifihaven.shared.types.*
import zio.*

import java.time.LocalDate
import java.time.Duration as JDuration
import java.util.concurrent.atomic.LongAdder

import com.github.benmanes.caffeine.cache.{Cache, Caffeine}

/**
 * In-process cache for the rendered `/api/time/status` payloads (#802).
 *
 * The cache is keyed per (profile, range) so the SPA's hot refresh loop doesn't re-execute the
 * underlying presence rollups for every poll. TTL is short (today-mode) or long (past-mode) — past
 * data is logically immutable, today's data accepts a small staleness window in exchange for
 * cutting query load by O(N) where N is the SPA's refresh frequency.
 *
 * Trait-not-class so a future external/distributed implementation (#803-style) can swap in.
 */
trait TimeStatusCache {

  /**
   * Look up a daily ProfileTimeStatus by (profileId, date); on miss, compute via `load` and store.
   */
  def getOrLoadDaily(
      profileId: ProfileId,
      date: LocalDate,
      today: LocalDate,
  )(load: => Task[wifihaven.shared.ProfileTimeStatus]): Task[wifihaven.shared.ProfileTimeStatus]

  /**
   * Look up a weekly ProfileTimeStatusWeek by (profileId, from, to, bucketOffsetMin); on miss,
   * compute via `load`. `bucketOffsetMin` participates in the key because the per-day bucket grid
   * (#794) shifts with it — different offsets must not collide.
   */
  def getOrLoadWeekly(
      profileId: ProfileId,
      from: LocalDate,
      to: LocalDate,
      bucketOffsetMin: Int,
      today: LocalDate,
  )(
      load: => Task[wifihaven.shared.ProfileTimeStatusWeek],
  ): Task[wifihaven.shared.ProfileTimeStatusWeek]

  /** Drop everything — exposed for tests and the debug endpoint. */
  def invalidateAll: UIO[Unit]

  /**
   * Drop every cached entry for this profile across both daily and weekly sub-caches (#946). Called
   * by mutating endpoints (`POST /api/time/extend`) so the next read reflects the write within the
   * same tick rather than waiting up to `todayTtl` for the entry to expire.
   */
  def invalidateProfile(profileId: ProfileId): UIO[Unit]

  def snapshot: UIO[TimeStatusCache.StatsSnapshot]
}

object TimeStatusCache {

  final case class StatsSnapshot(
      hits: Long,
      misses: Long,
      todaySize: Long,
      pastSize: Long,
  ) {
    def total: Long     = hits + misses
    def hitRate: Double = if total == 0 then 0.0 else hits.toDouble / total.toDouble
  }

  /**
   * Two underlying Caffeine caches keep TTL config simple — each cache has a single
   * `expireAfterWrite` rather than a per-entry `Expiry`. The "today" vs "past" decision happens at
   * the call site (which has the current date in hand).
   *
   *   - todayTtl defaults to 30s: a fresh insert into `traffic_reports` becomes visible within that
   *     window, which is the contract from the issue body.
   *   - pastTtl defaults to 1h: past data is logically immutable, but a bounded TTL keeps memory
   *     predictable and lets backfills/corrections eventually appear without an explicit
   *     invalidate.
   *   - maxSize defaults to 1000 entries per sub-cache: household scale is ~7 profiles × 90 days =
   *     630 daily entries; weekly entries are ~7 × number-of-anchor-dates-the-SPA-asks-about.
   */
  def live(
      todayTtl: zio.Duration = 30.seconds,
      pastTtl: zio.Duration = 1.hour,
      maxSize: Long = 1000L,
  ): ULayer[TimeStatusCache] =
    ZLayer.fromZIO(make(todayTtl, pastTtl, maxSize))

  def make(
      todayTtl: zio.Duration = 30.seconds,
      pastTtl: zio.Duration = 1.hour,
      maxSize: Long = 1000L,
  ): UIO[TimeStatusCache] =
    ZIO.succeed(new Live(todayTtl, pastTtl, maxSize))

  /**
   * Synchronous constructor — used for tests and as a default argument so existing call sites
   * (which don't care about caching) don't have to thread the dependency through.
   */
  def makeUnsafe(
      todayTtl: zio.Duration = 30.seconds,
      pastTtl: zio.Duration = 1.hour,
      maxSize: Long = 1000L,
  ): TimeStatusCache =
    new Live(todayTtl, pastTtl, maxSize)

  private sealed trait Key
  private final case class DailyKey(profileId: ProfileId, date: LocalDate) extends Key
  private final case class WeeklyKey(
      profileId: ProfileId,
      from: LocalDate,
      to: LocalDate,
      bucketOffsetMin: Int,
  ) extends Key

  private final class Live(
      todayTtl: zio.Duration,
      pastTtl: zio.Duration,
      maxSize: Long,
  ) extends TimeStatusCache {

    private val todayCache: Cache[Key, AnyRef] =
      Caffeine
        .newBuilder()
        .maximumSize(maxSize)
        .expireAfterWrite(JDuration.ofNanos(todayTtl.toNanos))
        .build[Key, AnyRef]()

    private val pastCache: Cache[Key, AnyRef] =
      Caffeine
        .newBuilder()
        .maximumSize(maxSize)
        .expireAfterWrite(JDuration.ofNanos(pastTtl.toNanos))
        .build[Key, AnyRef]()

    private val hits   = new LongAdder
    private val misses = new LongAdder

    def getOrLoadDaily(
        profileId: ProfileId,
        date: LocalDate,
        today: LocalDate,
    )(load: => Task[wifihaven.shared.ProfileTimeStatus]): Task[wifihaven.shared.ProfileTimeStatus] =
      getOrLoad(DailyKey(profileId, date), isTodayMode = !date.isBefore(today), load)

    def getOrLoadWeekly(
        profileId: ProfileId,
        from: LocalDate,
        to: LocalDate,
        bucketOffsetMin: Int,
        today: LocalDate,
    )(
        load: => Task[wifihaven.shared.ProfileTimeStatusWeek],
    ): Task[wifihaven.shared.ProfileTimeStatusWeek] =
      // A weekly window includes today (and so needs the short TTL) iff `to` is today or later.
      // Anything strictly in the past is immutable.
      getOrLoad(
        WeeklyKey(profileId, from, to, bucketOffsetMin),
        isTodayMode = !to.isBefore(today),
        load,
      )

    private def getOrLoad[V <: AnyRef](
        key: Key,
        isTodayMode: Boolean,
        load: => Task[V],
    ): Task[V] = {
      val cache = if isTodayMode then todayCache else pastCache
      ZIO.succeed(Option(cache.getIfPresent(key))).flatMap {
        case Some(v) =>
          ZIO.succeed(hits.increment()) *> ZIO.succeed(v.asInstanceOf[V])
        case None    =>
          ZIO.succeed(misses.increment()) *>
            load.tap(v => ZIO.succeed(cache.put(key, v)))
      }
    }

    def invalidateAll: UIO[Unit] = ZIO.succeed {
      todayCache.invalidateAll()
      pastCache.invalidateAll()
      hits.reset()
      misses.reset()
    }

    def invalidateProfile(profileId: ProfileId): UIO[Unit] = ZIO.succeed {
      def matches(k: Key): Boolean = k match {
        case DailyKey(pid, _)         => pid == profileId
        case WeeklyKey(pid, _, _, _)  => pid == profileId
      }
      val _ = todayCache.asMap().keySet().removeIf(matches(_))
      val _ = pastCache.asMap().keySet().removeIf(matches(_))
    }

    def snapshot: UIO[StatsSnapshot] = ZIO.succeed {
      StatsSnapshot(
        hits = hits.sum(),
        misses = misses.sum(),
        todaySize = todayCache.estimatedSize(),
        pastSize = pastCache.estimatedSize(),
      )
    }
  }
}
