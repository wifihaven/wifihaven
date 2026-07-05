package wifihaven.api.unit

import wifihaven.api.presence.{AmbientGate, Presence, PresenceRow}
import wifihaven.shared.HeartbeatFilter
import wifihaven.shared.types.*
import zio.test.*

import java.time.{Instant, LocalDate}

/**
 * #2077: the engagement-anchor gate over the isolation-learned ambient-host baseline
 * (docs/design/idle-traffic-discrimination.md). A device-level merged presence span counts toward
 * screen time iff it contains at least one ANCHOR row — app-attributed (#1506 seam) or a
 * non-ambient host. Unanchored spans drop whole; anchored spans count IN FULL (ambient rows inside
 * them still contribute), so the gate can only ever REMOVE minutes, never shave a real session.
 *
 * The fixtures mirror the prod evidence shapes: scattered singleton Apple-background bursts
 * overnight (must credit ~0), a diverse multi-host engagement burst (must credit in full), a
 * FaceTime-shaped IP-literal session (must survive), and an app-attributed single-host session
 * (structurally can never be dropped).
 */
object AmbientGateSpec extends ZIOSpecDefault {

  private val mac1 = MacAddress.unsafe("aa:bb:cc:dd:ee:01")
  private val mac2 = MacAddress.unsafe("aa:bb:cc:dd:ee:02")

  private val base               = Instant.parse("2026-07-01T00:00:00Z")
  private val baseDate           = LocalDate.parse("2026-07-01")
  private def b(i: Int): Instant = base.plusSeconds(i * 60L)

  private def row(
      mac: MacAddress,
      minute: Int,
      host: String,
      bytes: Long = 1_000_000L,
      periodSeconds: Int = 60,
  ) =
    PresenceRow(
      mac,
      baseDate,
      b(minute),
      HostId.Fqdn(Hostname.unsafe(host)),
      10,
      bytes,
      periodSeconds,
    )

  private def ipRow(mac: MacAddress, minute: Int, ip: String, bytes: Long = 1_000_000L) =
    PresenceRow(mac, baseDate, b(minute), HostId.IPv4(IpAddress.unsafe(ip)), 10, bytes, 60)

  private val filter = HeartbeatFilter(enabled = true, bytesThreshold = 10_000)

  private def gate(hosts: String*): AmbientGate = AmbientGate(enabled = true, hosts.toSet)

  private def gatedMinutes(
      rows: List[PresenceRow],
      ambient: AmbientGate,
      appPats: List[String] = Nil,
  ): Int = {
    val gated = Presence.ambientGatedRows(rows, ambient, filter, 120, appPats)
    Presence.totalMinutesByMac(rows = gated, Nil, filter, 120, appPats).getOrElse(mac1, 0)
  }

  def spec = suite("AmbientGateSpec")(
    test("disabled gate is the identity and drops no spans") {
      val rows = List(row(mac1, 0, "valid.apple.com"), row(mac1, 30, "weatherkit.apple.com"))
      val (out, drops) =
        Presence.ambientGatedRowsWithDropCount(rows, AmbientGate.Off, filter, 120, Nil)
      assertTrue(out == rows, drops == 0)
    },
    test("isolated ambient-host spans credit zero (the idle-overnight shape)") {
      // Two scattered singleton bursts to learned-ambient hosts, 30 min apart — the
      // prod overnight phantom shape. With the gate on, nothing credits.
      val rows         = List(
        row(mac1, 0, "valid.apple.com"),
        row(mac1, 30, "weatherkit.apple.com"),
        row(mac1, 31, "valid.apple.com"),
      )
      val g            = gate("valid.apple.com", "weatherkit.apple.com")
      val (out, drops) = Presence.ambientGatedRowsWithDropCount(rows, g, filter, 120, Nil)
      assertTrue(gatedMinutes(rows, g) == 0, out.isEmpty, drops == 2)
    },
    test("a diverse engagement burst keeps its full minutes, ambient rows included") {
      // 40-minute LEGO-shaped session: many hosts, with ambient rows co-present.
      // Gate must keep the span IN FULL — identical minutes to the ungated pipeline.
      val rows    = (0 until 40).toList.flatMap { m =>
        List(
          row(mac1, m, s"api.prod.cobuild.i.lego.com"),
          row(mac1, m, s"assets.lego.com"),
        )
      } ++ List(row(mac1, 20, "valid.apple.com"), row(mac1, 21, "cds.apple.com"))
      val g       = gate("valid.apple.com", "cds.apple.com")
      val ungated = Presence.totalMinutesByMac(rows, Nil, filter, 120).getOrElse(mac1, 0)
      val gated   = gatedMinutes(rows, g)
      assertTrue(gated == ungated, gated >= 40)
    },
    test("an app-attributed session is never dropped, even if its host is ambient") {
      // Single-host session on a host that (wrongly) got learned ambient, but the
      // host is on an active app's host-set: attribution beats ambient (#1506).
      val rows = (0 until 10).toList.map(m => row(mac1, m, "ios-api-cf.duolingo.com"))
      val g    = gate("ios-api-cf.duolingo.com")
      val pats = List("duolingo.com")
      assertTrue(gatedMinutes(rows, g, pats) >= 10)
    },
    test("an IP-literal peer anchors its span (the FaceTime shape)") {
      // High-byte session to a single IP-literal peer plus one ambient oauth host.
      // The IP is never in the learned set, so the span survives.
      val rows =
        (0 until 20).toList.map(m => ipRow(mac1, m, "174.219.32.52", bytes = 4_000_000L)) :+
          row(mac1, 5, "oauthaccountmanager.googleapis.com")
      val g    = gate("oauthaccountmanager.googleapis.com")
      assertTrue(gatedMinutes(rows, g) >= 20)
    },
    test("mixed day: the diverse span survives, the isolated ambient span drops") {
      val diverse      = (0 until 15).toList.flatMap { m =>
        List(row(mac1, m, "a-z-animals.com"), row(mac1, m, "cdn.shopify.com"))
      }
      val isolated     = List(row(mac1, 120, "valid.apple.com"), row(mac1, 121, "valid.apple.com"))
      val rows         = diverse ++ isolated
      val g            = gate("valid.apple.com")
      val (out, drops) = Presence.ambientGatedRowsWithDropCount(rows, g, filter, 120, Nil)
      val gated        = gatedMinutes(rows, g)
      val diverseOnly  = Presence.totalMinutesByMac(diverse, Nil, filter, 120).getOrElse(mac1, 0)
      assertTrue(
        gated == diverseOnly,
        drops == 1,
        out.toSet == diverse.toSet,
      )
    },
    test("gated minutes are always <= ungated minutes (gate only removes)") {
      val rows    = (0 until 15).toList.flatMap { m =>
        List(row(mac1, m, "a-z-animals.com"), row(mac1, m, "valid.apple.com"))
      } ++ List(row(mac1, 100, "valid.apple.com"), row(mac2, 3, "cds.apple.com"))
      val g       = gate("valid.apple.com", "cds.apple.com")
      val gated   = Presence.totalMinutesByMac(
        Presence.ambientGatedRows(rows, g, filter, 120, Nil),
        Nil,
        filter,
        120,
      )
      val ungated = Presence.totalMinutesByMac(rows, Nil, filter, 120)
      assertTrue(
        gated.getOrElse(mac1, 0) <= ungated.getOrElse(mac1, 0),
        gated.getOrElse(mac2, 0) <= ungated.getOrElse(mac2, 0),
      )
    },
    test("heartbeat-suppressed rows pass through but can neither anchor nor rescue a span") {
      // An InfraHosts row (push.apple.com) is not evidence: a span of [infra row,
      // ambient row] is still unanchored and drops; the infra row itself passes
      // through untouched (downstream surfaces drop it as heartbeat, as today).
      val infra    = row(mac1, 0, "push.apple.com")
      val ambient  = row(mac1, 1, "valid.apple.com")
      val rows     = List(infra, ambient)
      val g        = gate("valid.apple.com")
      val (out, _) = Presence.ambientGatedRowsWithDropCount(rows, g, filter, 120, Nil)
      assertTrue(out == List(infra), gatedMinutes(rows, g) == 0)
    },
    test("sub-byte-floor rows cannot anchor a span") {
      // A 60-byte keepalive to a novel host is heartbeat-suppressed evidence, so it
      // cannot anchor the co-present ambient span.
      val rows = List(
        row(mac1, 0, "valid.apple.com"),
        row(mac1, 0, "keepalive.example.com", bytes = 60L),
      )
      val g    = gate("valid.apple.com")
      assertTrue(gatedMinutes(rows, g) == 0)
    },
    test("fail-open: an ENABLED gate with an empty learned set drops nothing") {
      // Fresh install / learner not yet run: every host anchors, so the gate is the identity
      // over its input and behavior degrades to the status quo, never to over-suppression.
      val rows         = List(
        row(mac1, 0, "valid.apple.com"),
        row(mac1, 30, "weatherkit.apple.com"),
      )
      val (out, drops) = Presence.ambientGatedRowsWithDropCount(
        rows,
        AmbientGate(enabled = true, Set.empty),
        filter,
        120,
        Nil,
      )
      assertTrue(out == rows, drops == 0)
    },
    test("isolatedSpanHosts learns isolated hosts and never diverse or app-attributed ones") {
      val isolated = List(row(mac1, 0, "valid.apple.com"), row(mac1, 1, "valid.apple.com"))
      val diverse  = (30 until 40).toList.flatMap { m =>
        List(
          row(mac1, m, "a-z-animals.com"),
          row(mac1, m, "cdn.shopify.com"),
          row(mac1, m, "bam.nr-data.net"),
        )
      }
      val appOnly  = List(row(mac2, 0, "ios-api-cf.duolingo.com"))
      val learned  = Presence.isolatedSpanHosts(
        isolated ++ diverse ++ appOnly,
        isolationMaxHosts = 2,
        filter,
        120,
        appHostPatterns = List("duolingo.com"),
      )
      assertTrue(
        learned.keySet.map(_.value) == Set("valid.apple.com"),
        learned.values.sum == 1,
      )
    },
    test("isolatedSpanHosts learns a two-host isolated span but not a three-host one") {
      val two     = List(row(mac1, 0, "valid.apple.com"), row(mac1, 1, "cds.apple.com"))
      val three   = List(
        row(mac1, 60, "h1.example.com"),
        row(mac1, 61, "h2.example.com"),
        row(mac1, 62, "h3.example.com"),
      )
      val learned = Presence.isolatedSpanHosts(two ++ three, 2, filter, 120, Nil)
      assertTrue(learned.keySet.map(_.value) == Set("valid.apple.com", "cds.apple.com"))
    },
  )
}
