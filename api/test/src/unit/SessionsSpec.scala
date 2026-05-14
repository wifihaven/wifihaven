package familydns.api.unit

import familydns.api.sessions.*
import familydns.shared.types.*
import zio.test.*

import java.time.{Instant, LocalDate, ZoneOffset}
import java.util.UUID

object SessionsSpec extends ZIOSpecDefault {

  // Reference midnight for date construction — every test row's `date` is computed
  // from periodStart in UTC, mirroring what RouterIngestRoutes does on insert.
  private val r1: RouterId = RouterId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val r2: RouterId = RouterId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
  private val mac1         = MacAddress.unsafe("aa:bb:cc:dd:ee:01")
  private val mac2         = MacAddress.unsafe("aa:bb:cc:dd:ee:02")
  private val youtube      = Hostname.unsafe("youtube.com")
  private val tiktok       = Hostname.unsafe("tiktok.com")

  private def row(
      routerId: RouterId,
      mac: MacAddress,
      host: Hostname,
      startSec: Long,
      activeSeconds: Int = 300,
      periodLen: Long = 300,
      bytesIn: Long = 1000,
      bytesOut: Long = 200,
  ): SessionRow = {
    val start = Instant.ofEpochSecond(startSec)
    val end   = Instant.ofEpochSecond(startSec + periodLen)
    SessionRow(
      routerId = routerId,
      mac = mac,
      hostname = host,
      date = start.atZone(ZoneOffset.UTC).toLocalDate,
      periodStart = start,
      periodEnd = end,
      activeSeconds = activeSeconds,
      bytesIn = bytesIn,
      bytesOut = bytesOut,
    )
  }

  // 2026-05-12 00:00:00 UTC
  private val baseT: Long = LocalDate
    .of(2026, 5, 12)
    .atStartOfDay()
    .toEpochSecond(ZoneOffset.UTC)

  def spec = suite("Sessions.stitch")(
    test("two adjacent 5-min periods for same (mac, host) → one 10-min session") {
      val rows = List(
        row(r1, mac1, youtube, baseT, activeSeconds = 300),
        row(r1, mac1, youtube, baseT + 300, activeSeconds = 300),
      )
      val out  = Sessions.stitch(rows)
      assertTrue(
        out.length == 1,
        out.head.durationSeconds == 600L,
        out.head.periodCount == 2,
        out.head.startedAt == Instant.ofEpochSecond(baseT).toString,
        out.head.endedAt == Instant.ofEpochSecond(baseT + 600).toString,
        out.head.hostname == youtube,
        out.head.mac == mac1,
      )
    },
    test("two periods separated by one empty period → two sessions (strict contiguity)") {
      // first period: baseT..baseT+300, then idle baseT+300..baseT+600, then baseT+600..baseT+900
      val rows = List(
        row(r1, mac1, youtube, baseT, activeSeconds = 300),
        row(r1, mac1, youtube, baseT + 600, activeSeconds = 300),
      )
      val out  = Sessions.stitch(rows)
      assertTrue(
        out.length == 2,
        out.map(_.durationSeconds).sum == 600L,
        out.forall(_.periodCount == 1),
      )
    },
    test("duration uses active_seconds, not wall-clock") {
      val rows = List(row(r1, mac1, youtube, baseT, activeSeconds = 180, periodLen = 300))
      val out  = Sessions.stitch(rows)
      assertTrue(
        out.length == 1,
        out.head.durationSeconds == 180L,
        // wall-clock span across the period is 300s, but duration is the active portion
        out.head.endedAt == Instant.ofEpochSecond(baseT + 300).toString,
      )
    },
    test("two different (mac, host) groups stitched independently") {
      val rows = List(
        row(r1, mac1, youtube, baseT, activeSeconds = 300),
        row(r1, mac2, tiktok, baseT + 100, activeSeconds = 200),
        row(r1, mac1, youtube, baseT + 300, activeSeconds = 300),
      )
      val out  = Sessions.stitch(rows)
      assertTrue(
        out.length == 2,
        out.exists(s => s.mac == mac1 && s.hostname == youtube && s.durationSeconds == 600L),
        out.exists(s => s.mac == mac2 && s.hostname == tiktok && s.durationSeconds == 200L),
      )
    },
    test("two contiguous periods on different router_ids → two sessions") {
      val rows = List(
        row(r1, mac1, youtube, baseT, activeSeconds = 300),
        row(r2, mac1, youtube, baseT + 300, activeSeconds = 300),
      )
      val out  = Sessions.stitch(rows)
      assertTrue(
        out.length == 2,
        out.exists(_.routerId == r1),
        out.exists(_.routerId == r2),
      )
    },
    test("sessions split at midnight (UTC) to align with time_usage daily bucket") {
      // 23:55..00:00 (day N) and 00:00..00:05 (day N+1) — contiguous wall-clock,
      // but different `date` columns, so should be two sessions.
      val midnight = baseT          // 2026-05-12 00:00 UTC
      val before   = midnight - 300 // 2026-05-11 23:55 UTC, date=2026-05-11
      val after    = midnight       // 2026-05-12 00:00 UTC, date=2026-05-12
      val rows     = List(
        row(r1, mac1, youtube, before, activeSeconds = 300),
        row(r1, mac1, youtube, after, activeSeconds = 300),
      )
      val out      = Sessions.stitch(rows)
      assertTrue(
        out.length == 2,
        out.exists(_.date == "2026-05-11"),
        out.exists(_.date == "2026-05-12"),
        out.map(_.durationSeconds).sum == 600L,
      )
    },
    test("sessions sorted by startedAt descending (most recent first)") {
      val rows = List(
        row(r1, mac1, youtube, baseT, activeSeconds = 300),
        row(r1, mac2, tiktok, baseT + 1200, activeSeconds = 300),
      )
      val out  = Sessions.stitch(rows)
      assertTrue(
        out.length == 2,
        out.head.startedAt == Instant.ofEpochSecond(baseT + 1200).toString,
        out(1).startedAt == Instant.ofEpochSecond(baseT).toString,
      )
    },
    test("empty input → empty output") {
      assertTrue(Sessions.stitch(Nil).isEmpty)
    },
    test("byte counters sum across folded periods") {
      val rows = List(
        row(r1, mac1, youtube, baseT, activeSeconds = 300, bytesIn = 1000, bytesOut = 200),
        row(r1, mac1, youtube, baseT + 300, activeSeconds = 300, bytesIn = 2500, bytesOut = 800),
      )
      val out  = Sessions.stitch(rows)
      assertTrue(
        out.length == 1,
        out.head.bytesIn == 3500L,
        out.head.bytesOut == 1000L,
      )
    },
  )
}
