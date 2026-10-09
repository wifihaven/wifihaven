package wifihaven.api.feature

import wifihaven.api.db.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import doobie.*
import zio.{Clock as _, *}
import zio.test.*

import java.time.{LocalDate, LocalDateTime, LocalTime, ZoneOffset}

/**
 * #2876 regression (surfaced by the staging `e2e-router.sh` TimeLimit/usage checks going red on
 * every main run after #2876 merged): a device's FIRST-EVER assignment must be open-ended, exactly
 * like the V90 backfill row every pre-existing device got.
 *
 * `DeviceAssignment.assign` stamped `started_at = now` on every assignment, first-ever included.
 * But the backfill (design `docs/design/shared-devices.md` §5.1) opens every pre-existing device's
 * one `assigned` row with `started_at IS NULL` — "reproduces today's attribution exactly … changes
 * no behaviour". Design Q7 scopes the attribution change to *reassignment* only ("moving a device
 * to another profile no longer moves its past usage"); a first assignment has no prior profile
 * whose history to protect. A timestamped first assignment therefore made an API-created device
 * attribute differently from an identical backfilled one: usage reported for a period that began
 * before the assignment instant — the normal shape of a device's first report, and what the e2e
 * posts — was credited to no profile, so the daily-limit total never moved.
 *
 * Clock is noon on the traffic day, so the first-ever assignment lands mid-window and a 10:00 row
 * sits before it IN THE SAME day read — the only arrangement that exposes the bug (a start before
 * the read window, as `IntervalAttributionSpec`'s 2000-01-01 fixture clock produces, is dropped by
 * `PresenceSpans.within` and behaves open-ended for free).
 */
object FirstAssignmentAttributionSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Transactor[Task] & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(LocalDateTime.of(2025, 1, 8, 12, 0))

  private val day                    = LocalDate.of(2025, 1, 8)
  private def at(h: Int, m: Int = 0) =
    LocalDateTime.of(day, LocalTime.of(h, m)).toInstant(ZoneOffset.UTC)

  private val mac = MacAddress.unsafe("aa:bb:cc:dd:28:46")

  private val seed = for {
    _   <- TestDatabase.cleanAndMigrate
    hsr <- ZIO.service[HouseholdSettingsRepo]
    pr  <- ZIO.service[ProfileRepo]
    dr  <- ZIO.service[DeviceRepo]
    dar <- ZIO.service[DeviceAssignmentRepo]
    cur <- hsr.getForHousehold(HouseholdId.Default)
    settings = cur.copy(dailyResetTz = java.time.ZoneId.of("UTC"))
    _   <- hsr.update(HouseholdId.Default, settings)
    p   <- pr.create("P", Nil)
    // Create the device unassigned (no history row), then make its FIRST-EVER assignment at an
    // explicit noon — mid-window — so a 10:00 report sits before it in the same day read. (The
    // fixture repos' clock is pinned to 2000-01-01, so seeding with a profile would stamp the
    // assignment there, before any read window, and `within` would drop the bound for free.)
    dev <- dr.upsert(mac, "fresh", None, "192.168.1.100")
    _   <- dar.assign(
      HouseholdId.Default,
      dev,
      Some(p),
      at(12),
      AssignmentKind.Assigned,
      None,
      AssignmentEndCause.Unassigned,
    )
    rid <- ZIO.serviceWithZIO[RouterRepo](_.create("gw-first", Sha256Hex.unsafe("f" * 64)))
    // One 10:00 report period — before noon, but on the same local day.
    _   <- ZIO.serviceWithZIO[TrafficReportRepo](
      _.insertBatch(
        List(
          TrafficReportInsert(
            rid,
            mac,
            None,
            HostId.Fqdn(Hostname.unsafe("youtube.com")),
            day,
            at(10),
            at(10).plusSeconds(300),
            300,
            500_000L,
            500_000L,
          ),
        ),
      ).unit,
    )
  } yield (p, settings)

  def spec = suite("FirstAssignmentAttributionSpec")(
    test("a device's first-ever assignment is open-ended and attributes same-day usage before it") {
      for {
        res <- seed
        (p, settings) = res
        dr    <- ZIO.service[DeviceRepo]
        scope <- AttributionScope.forDay(dr, HouseholdId.Default, day, settings)
        spans = scope.spansFor(p)
      } yield assertTrue(
        // The writer left the interval open-ended (the backfill shape), not stamped at noon.
        scope.byProfile.get(p).exists(_.exists(_.from.isEmpty)),
        // So a report whose period began before the assignment instant still belongs to the profile.
        spans.covers(mac, at(10)),
      )
    },
  )
}
