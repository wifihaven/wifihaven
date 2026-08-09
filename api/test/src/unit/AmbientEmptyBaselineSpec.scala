package wifihaven.api.unit

import wifihaven.api.presence.{AmbientGate, Presence, PresenceRow}
import wifihaven.shared.HeartbeatFilter
import wifihaven.shared.types.*
import zio.test.*

import java.time.{Instant, LocalDate}

/**
 * #2643 (operator scope extension) — the determination the `ambientGateEnabled` default-on flip is
 * conditional on, pinned rather than asserted in prose.
 *
 * #2077 made the gate default OFF deliberately: the learner runs regardless, so an operator could
 * inspect the would-be ambient set via `GET /api/presence/ambient-hosts` before enabling.
 * Defaulting it ON skips that review, and a NEW household has no learned baseline at all — the
 * thresholds (`ambientMinIsolatedDays` = 3 distinct qualifying days inside
 * `ambientLearningWindowDays` = 14, migration V63) cannot be met for days. The operator's condition
 * was therefore: default-on is only correct if the gate over an EMPTY ambient set is a clean no-op
 * rather than a misclassifier, because screen-time accounting that is wrong and invisible is worse
 * than accounting that is merely unfiltered.
 *
 * It is a clean no-op. In `Presence.ambientGatedRowsWithDropCount` the learned set appears in
 * exactly ONE place — the `!ambient.hosts.contains(r.host.value)` conjunct of `isHostAnchor` — so
 * it can only ever REMOVE anchors. An empty set is thus the most permissive state of the learned
 * tier, not a different answer that later gets corrected: an immature baseline discounts LESS, and
 * the gate self-activates as days accrue. The two tiers that remain live on an empty set are
 * repo-authored code constants needing no learning at all — the #2177 `InfraHosts.cloudBackground`
 * class (the wakeup-burst hosts the learner structurally cannot learn, since they only ever fire in
 * dense co-occurring bursts and so never accrue isolated days) and the non-FQDN byte floor. Neither
 * can be immature.
 *
 * So a new household's first days are strictly better than the pre-#2643 default rather than
 * speculatively different: it gets #2177 wakeup-burst suppression (the #2274 phantom-usage shape)
 * immediately, and the learned tier arrives later without having produced a wrong answer meanwhile.
 *
 * Unit-level because this is a pure-function edge case (docs/process/testing.md); the default that
 * depends on it is pinned through the full stack in `AmbientGateDefaultSpec`.
 */
object AmbientEmptyBaselineSpec extends ZIOSpecDefault {

  private val mac      = MacAddress.unsafe("aa:bb:cc:dd:ee:01")
  private val base     = Instant.parse("2026-05-13T00:00:00Z")
  private val baseDate = LocalDate.parse("2026-05-13")

  private def row(bucket: Int, host: String) =
    PresenceRow(
      mac,
      baseDate,
      base.plusSeconds(bucket * 300L),
      HostId.Fqdn(Hostname.unsafe(host)),
      300,
      1_000_000L,
      300,
    )

  // An ordinary engagement span: plain FQDNs, not on the #2177 cloud-background class, so the ONLY
  // thing that could drop them is the learned tier. If an empty baseline misclassified, this is
  // where a new household would silently start under-counting.
  private val engagement = List(row(0, "www.youtube.com"), row(1, "i.ytimg.com"))

  private val filter = HeartbeatFilter(enabled = false, bytesThreshold = 0, Nil)

  private def gated(g: AmbientGate) =
    Presence.ambientGatedRows(
      engagement,
      g,
      filter,
      continuationSeconds = 120,
      appHostPatterns = Nil,
    )

  def spec = suite("AmbientEmptyBaselineSpec (#2643)")(
    test("an ENABLED gate over an EMPTY learned set drops nothing from an engagement span") {
      val (kept, dropped) = Presence.ambientGatedRowsWithDropCount(
        engagement,
        AmbientGate(enabled = true, hosts = Set.empty),
        filter,
        continuationSeconds = 120,
        appHostPatterns = Nil,
      )
      assertTrue(kept.size == engagement.size, dropped == 0)
    },
    test("an empty learned set gates identically to the OFF gate") {
      // The no-op stated as an equivalence: for traffic the code-constant tiers do not touch,
      // enabling the gate with no baseline changes nothing at all.
      assertTrue(gated(AmbientGate.Off) == gated(AmbientGate(enabled = true, hosts = Set.empty)))
    },
    test("the learned set only ever REMOVES anchors — learning discounts more, never less") {
      // Monotonicity is what makes an immature baseline SAFE rather than merely untested: a set that
      // grows can only drop more, so empty is the conservative end of the range. This is the
      // assertion that would go red if `isHostAnchor` ever grew a second use of `ambient.hosts` in
      // the opposite direction.
      val emptySet   = gated(AmbientGate(enabled = true, hosts = Set.empty))
      val learnedAll =
        gated(AmbientGate(enabled = true, hosts = engagement.map(_.host.value).toSet))
      assertTrue(learnedAll.size <= emptySet.size, learnedAll.isEmpty)
    },
  )
}
