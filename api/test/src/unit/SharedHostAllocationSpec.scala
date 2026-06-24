package wifihaven.api.unit

import wifihaven.api.presence.{Presence, PresenceRow}
import wifihaven.shared.*
import wifihaven.shared.types.*
import zio.test.*

import java.time.{Instant, LocalDate}

/**
 * #1898 (shared-hosts S3): the per-host reporting allocation primitive
 * ([[Presence.allocateSharedHostSeconds]]) attributes a SHARED host's stitched session-seconds to
 * app(s) by TEMPORAL CO-PRESENCE with each candidate app's DISTINCTIVE spans (the #1897
 * `distinctiveSpansByApp` seam) — never by byte magnitude (NOT the rejected #842/#715 Path 1
 * argmax-foreground heuristic). Ties split EQUALLY among co-present qualifiers; a shared span that
 * overlaps no candidate's distinctive span is unattributed (`None` → "Other").
 *
 * Pins the design doc's worked example (docs/design/shared-host-allocation.md §"Worked example"):
 * Device D, `app.feelinggreat.com` active 09:00–09:20 and 14:00–14:05; `api.elevenlabs.io` rows at
 * 09:05, 11:30, 14:02; a "Speechify" app distinctive 11:25–11:40.
 */
object SharedHostAllocationSpec extends ZIOSpecDefault {

  private val mac      = MacAddress.unsafe("aa:bb:cc:dd:ee:01")
  private val baseDate = LocalDate.parse("2026-06-23")
  // t0 = local midnight of baseDate (epoch seconds irrelevant — overlap is relative).
  private val t0       = Instant.parse("2026-06-23T00:00:00Z")

  private val feelingGreat = AppId(1L)
  private val speechify    = AppId(2L)

  private val sharedHost = HostId.Fqdn(Hostname.unsafe("api.elevenlabs.io"))

  // A 300 s presence row for the shared host at `atSeconds` past t0.
  private def sharedRow(atSeconds: Long): PresenceRow =
    PresenceRow(
      mac,
      baseDate,
      t0.plusSeconds(atSeconds),
      sharedHost,
      activeSeconds = 300,
      bytes = 1_000_000L,
      periodSeconds = 300,
    )

  private def span(fromSeconds: Long, toSeconds: Long): Presence.Span =
    Presence.Span(t0.getEpochSecond + fromSeconds, t0.getEpochSecond + toSeconds)

  private val h = 3600L
  private val m = 60L

  // FG distinctive spans: 09:00–09:20 and 14:00–14:05.
  private val fgSpans       = List(span(9 * h, 9 * h + 20 * m), span(14 * h, 14 * h + 5 * m))
  // Speechify distinctive span: 11:25–11:40.
  private val speechifySpan = List(span(11 * h + 25 * m, 11 * h + 40 * m))

  def spec = suite("SharedHostAllocationSpec (#1898)")(
    test(
      "worked example: each shared row credited to the app whose distinctive session covers it",
    ) {
      // elevenlabs rows: 09:05 (inside FG span 1), 11:30 (inside Speechify), 14:02 (inside FG span 2).
      val rows            = List(
        sharedRow(9 * h + 5 * m),
        sharedRow(11 * h + 30 * m),
        sharedRow(14 * h + 2 * m),
      )
      val candidates      = Map(feelingGreat -> fgSpans, speechify -> speechifySpan)
      val (alloc, counts) =
        Presence.allocateSharedHostSeconds(rows, sharedHost, candidates)
      // 09:05 → FG, 14:02 → FG (600 s), 11:30 → Speechify (300 s); nothing unattributed.
      assertTrue(alloc.getOrElse(Some(feelingGreat), 0L) == 600L) &&
      assertTrue(alloc.getOrElse(Some(speechify), 0L) == 300L) &&
      assertTrue(alloc.getOrElse(None, 0L) == 0L) &&
      assertTrue(counts.attributed == 3L) &&
      assertTrue(counts.split == 0L) &&
      assertTrue(counts.other == 0L)
    },
    test("tie-break: a shared row inside two apps' distinctive spans splits 50/50 (NOT argmax)") {
      // Both FG and Speechify distinctive at 11:30; the 11:30 elevenlabs row splits equally.
      val rows            = List(sharedRow(11 * h + 30 * m))
      val fgCoversNoon    = List(span(11 * h, 12 * h)) // FG distinctive 11:00–12:00, covers 11:30
      val candidates      = Map(feelingGreat -> fgCoversNoon, speechify -> speechifySpan)
      val (alloc, counts) =
        Presence.allocateSharedHostSeconds(rows, sharedHost, candidates)
      // 300 s split equally — no winner-by-magnitude, deterministic 150/150.
      assertTrue(alloc.getOrElse(Some(feelingGreat), 0L) == 150L) &&
      assertTrue(alloc.getOrElse(Some(speechify), 0L) == 150L) &&
      assertTrue(alloc.getOrElse(None, 0L) == 0L) &&
      assertTrue(counts.split == 1L) &&
      assertTrue(counts.attributed == 0L)
    },
    test("no qualifier: a shared row with no live distinctive session falls to 'Other'") {
      // elevenlabs fires at 03:00 with neither app active.
      val rows            = List(sharedRow(3 * h))
      val candidates      = Map(feelingGreat -> fgSpans, speechify -> speechifySpan)
      val (alloc, counts) =
        Presence.allocateSharedHostSeconds(rows, sharedHost, candidates)
      assertTrue(alloc.getOrElse(None, 0L) == 300L) &&
      assertTrue(alloc.get(Some(feelingGreat)).isEmpty) &&
      assertTrue(alloc.get(Some(speechify)).isEmpty) &&
      assertTrue(counts.other == 1L) &&
      assertTrue(counts.attributed == 0L)
    },
    test("allocation conserves the host's proportional seconds (sum == proportionalHostSeconds)") {
      val rows           = List(
        sharedRow(9 * h + 5 * m),
        sharedRow(11 * h + 30 * m),
        sharedRow(14 * h + 2 * m),
        sharedRow(3 * h),
      )
      val candidates     = Map(feelingGreat -> fgSpans, speechify -> speechifySpan)
      val (alloc, _)     =
        Presence.allocateSharedHostSeconds(rows, sharedHost, candidates)
      val totalAllocated = alloc.values.sum
      val proportional   =
        Presence.proportionalHostSeconds(rows).getOrElse(sharedHost, 0L)
      assertTrue(totalAllocated == proportional) &&
      assertTrue(totalAllocated == 1200L) // four 300 s rows, all separate sessions
    },
  )
}
