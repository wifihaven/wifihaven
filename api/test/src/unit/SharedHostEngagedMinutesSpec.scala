package wifihaven.api.unit

import wifihaven.api.policy.TimeStatusService
import wifihaven.api.presence.PresenceRow
import wifihaven.shared.*
import wifihaven.shared.types.*
import zio.test.*

import java.time.{Instant, LocalDate, LocalTime, ZoneId}

/**
 * #1897 (shared-hosts S2): the per-app engaged-minutes stitch
 * ([[TimeStatusService.appSecondsByApp]]) must consume each app's DISTINCTIVE (`shared = false`)
 * host-set only. A shared host that overlaps a distinctive span is already counted in that span
 * (no-op); excluding it from the stitch guarantees a shared host can never inflate, extend, or
 * single-handedly create an app's engaged time.
 *
 * Pure spec — `appSecondsByApp` is a pure projection over `(profile, appLimits, presence,
 * settings)`, so no DB / Clock is needed to pin the rule. The DB round-trip of the
 * `app_hosts.shared` flag through `listForProfile` is pinned separately in the feature suite.
 */
object SharedHostEngagedMinutesSpec extends ZIOSpecDefault {

  private val mac      = MacAddress.unsafe("aa:bb:cc:dd:ee:01")
  private val base     = Instant.parse("2026-06-23T00:00:00Z")
  private val baseDate = LocalDate.parse("2026-06-23")

  // bucket i → a 300 s presence row [i*300, i*300+300) on `mac` for `host`.
  private def row(bucket: Int, host: String): PresenceRow =
    PresenceRow(
      mac,
      baseDate,
      base.plusSeconds(bucket * 300L),
      HostId.Fqdn(Hostname.unsafe(host)),
      activeSeconds = 300,
      bytes = 1_000_000L, // ≥ AppSessionAnchorBytes so the session anchors (#1666)
      periodSeconds = 300,
    )

  private val appId        = AppId(7L)
  private val assignmentId = AppPolicyAssignmentId(11L)
  private val pid          = ProfileId(3L)

  // One (assignment × host) row, mirroring what AppTimeLimitRepo.listForProfile emits.
  private def limit(host: String, shared: Boolean): AppTimeLimit =
    AppTimeLimit(
      id = AppTimeLimitId(0L),
      profileId = pid,
      domainPattern = host,
      dailyMinutes = Some(60),
      label = "app:feeling-great",
      exemptFromDaily = false,
      appId = appId,
      mode = AppMode.TimeLimited,
      assignmentId = assignmentId,
      allowedDuringScheduleBlock = true,
      shared = shared,
    )

  // Feeling Great: distinctive `feelinggreat.com`, shared `elevenlabs.io`.
  private val appLimits = List(
    limit("feelinggreat.com", shared = false),
    limit("elevenlabs.io", shared = true),
  )

  private val profile  = Profile(pid, "Kid", Nil, paused = false)
  private val settings = HouseholdSettings(
    dailyResetTime = LocalTime.MIDNIGHT,
    dailyResetTz = ZoneId.of("UTC"),
    heartbeatFilter = HeartbeatFilter.Off,
  )

  private def engagedSeconds(presence: List[PresenceRow]): Long =
    TimeStatusService.appSecondsByApp(profile, appLimits, presence, settings).getOrElse(appId, 0L)

  def spec = suite("SharedHostEngagedMinutesSpec (#1897)")(
    test("shared host inside a distinctive span adds 0 minutes (no-op)") {
      // feelinggreat.com active buckets 0,1,2 → one engaged span [0, 900) = 900 s.
      // elevenlabs.io fires at bucket 1, strictly inside that span. Excluding it
      // from the stitch is a no-op: the wall-clock is already counted. == 900 s.
      val presence = List(
        row(0, "feelinggreat.com"),
        row(1, "feelinggreat.com"),
        row(2, "feelinggreat.com"),
        row(1, "elevenlabs.io"),
      )
      assertTrue(engagedSeconds(presence) == 900L)
    },
    test("shared host adjacent to a distinctive span never extends it") {
      // feelinggreat.com buckets 0,1 → span [0, 600). elevenlabs.io at bucket 2 is
      // adjacent (gap 0), so with the shared host IN the stitch it would bridge and
      // extend the app to [0, 900) = 900 s. Distinctive-only stitch keeps it at 600 s.
      val presence =
        List(row(0, "feelinggreat.com"), row(1, "feelinggreat.com"), row(2, "elevenlabs.io"))
      assertTrue(engagedSeconds(presence) == 600L)
    },
    test("shared host with no distinctive activity is not credited to the app") {
      // Only elevenlabs.io traffic, no feelinggreat.com. With the shared host in the
      // stitch the app would show 600 s; distinctive-only credits zero — the app is
      // absent from the engaged-seconds map.
      val presence = List(row(0, "elevenlabs.io"), row(1, "elevenlabs.io"))
      assertTrue(engagedSeconds(presence) == 0L)
    },
    test("distinctiveSpansByApp (the S3 seam) exposes distinctive-only spans") {
      // The reusable per-app distinctive-span primitive S3 reads for the co-presence overlap
      // test: feelinggreat.com [0, 600); elevenlabs.io at bucket 2 must NOT extend the span.
      val presence =
        List(row(0, "feelinggreat.com"), row(1, "feelinggreat.com"), row(2, "elevenlabs.io"))
      val spans    =
        TimeStatusService.distinctiveSpansByApp(profile, appLimits, presence, settings)
      val totalSec = spans.getOrElse(appId, Nil).map(s => s.endEpoch - s.startEpoch).sum
      assertTrue(totalSec == 600L)
    },
  )
}
