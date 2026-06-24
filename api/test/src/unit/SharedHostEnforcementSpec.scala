package wifihaven.api.policy

import wifihaven.shared.*
import wifihaven.shared.types.*
import zio.test.*

import java.time.{Instant, LocalDate}

/**
 * #1899 (shared-hosts S4): enforcement for shared hosts follows the MOST-PERMISSIVE rule — allow
 * wins, block is withheld — and does NOT depend on co-presence (that gates attribution only). For a
 * shared host `S`:
 *
 *   - ALLOW side: `S` enters `extraAllowed` whenever an app listing it resolves to `Allowed`
 *     enforcement (subject to `allowed_during_schedule_block`, #1679). Over-allowing a shared
 *     backend is safe — `extraAllowed` already beats every block path at the router.
 *   - BLOCK side: `S` is NEVER added to a drop set — not to a `Blocked`-mode app's `extraBlocked`,
 *     and not to per-app cap-exhaustion's `appCapExhaustedHosts`. Dropping a shared backend because
 *     ONE app blocked/cap-exhausted would drop it for every OTHER app — the #1636 collateral
 *     failure. The cap is still enforced through the app's DISTINCTIVE hosts.
 *
 * Pure spec — `ProfileAppDispositions.enforcement`, `PolicyService.appCapExhaustedHosts`, and
 * `PolicyService.computeBlockRules` are pure projections, so no DB / Clock is needed. The DB
 * round-trip of the `app_hosts.shared` flag through `listForProfile` into the snapshot is pinned
 * end-to-end in `PolicySnapshotAppsSpec`.
 */
object SharedHostEnforcementSpec extends ZIOSpecDefault {

  private val pid          = ProfileId(1L)
  private val appId        = AppId(10L)
  private val assignmentId = AppPolicyAssignmentId(1L)
  private val today        = LocalDate.parse("2026-06-24")
  private val now          = Instant.parse("2026-06-24T18:00:00Z")

  // Feeling Great: distinctive `feelinggreat.com`, shared `elevenlabs.io`.
  private val distinctive = "feelinggreat.com"
  private val sharedHost  = "elevenlabs.io"

  private def disposition(
      mode: AppMode,
      exempt: Boolean = false,
      dailyMinutes: Option[Int] = None,
  ): AppDisposition =
    AppDisposition(
      appId = appId,
      assignmentId = assignmentId,
      mode = mode,
      exemptFromDaily = exempt,
      dailyMinutes = dailyMinutes,
      label = "app:feeling-great",
      hosts = List(distinctive, sharedHost),
      distinctiveHosts = List(distinctive),
    )

  private val profile = Profile(pid, "Kid", Nil, paused = false)

  // One per-app cap entry carrying both the distinctive and the shared host.
  private def appDayState(used: Int, cap: Option[Int], exempt: Boolean = false): AppDayState =
    AppDayState(
      label = "app:feeling-great",
      domainPattern = distinctive,
      dailyLimitMinutes = cap,
      usedMinutes = used,
      exemptFromDaily = exempt,
      hosts = List(distinctive, sharedHost),
      distinctiveHosts = List(distinctive),
      appId = appId,
    )

  private def stateWith(app: AppDayState): ProfileDayState =
    ProfileDayState(pid, today, None, 0, 0, None, blocked = false, None, List(app))

  def spec = suite("SharedHostEnforcementSpec (#1899)")(
    // ── ALLOW side: most-permissive — allow wins, unconditional ──
    test("Allowed-mode app: shared host carves into extraAllowed alongside the distinctive host") {
      val (allowed, blocked) =
        ProfileAppDispositions(List(disposition(AppMode.Allowed)))
          .enforcement(Map.empty, capExhausted = false, now)
      val a                  = allowed.map(_.value).toSet
      assertTrue(a.contains(distinctive)) &&
      assertTrue(a.contains(sharedHost)) &&
      assertTrue(blocked.isEmpty)
    },
    // ── BLOCK side: shared host never enters a drop set ──
    test("Blocked-mode app: distinctive host blocked, shared host omitted from extraBlocked") {
      val (allowed, blocked) =
        ProfileAppDispositions(List(disposition(AppMode.Blocked)))
          .enforcement(Map.empty, capExhausted = false, now)
      val b                  = blocked.map(_.value).toSet
      assertTrue(b.contains(distinctive)) &&
      assertTrue(!b.contains(sharedHost)) &&
      assertTrue(allowed.isEmpty)
    },
    test(
      "appCapExhaustedHosts: exhausted app contributes its distinctive hosts only, never shared",
    ) {
      val hosts = PolicyService
        .appCapExhaustedHosts(stateWith(appDayState(used = 30, cap = Some(30))))
        .map(_.value)
        .toSet
      assertTrue(hosts.contains(distinctive)) && assertTrue(!hosts.contains(sharedHost))
    },
    test("appCapExhaustedHosts: under-cap app contributes nothing (neither host)") {
      val hosts = PolicyService
        .appCapExhaustedHosts(stateWith(appDayState(used = 10, cap = Some(30))))
        .map(_.value)
        .toSet
      assertTrue(hosts.isEmpty)
    },
    test(
      "computeBlockRules: cap-exhausted app blocks the distinctive host but NEVER the shared one",
    ) {
      val rules = PolicyService.computeBlockRules(
        profile = profile,
        state = stateWith(appDayState(used = 30, cap = Some(30))),
      )
      val eb    = rules.extraBlocked.map(_.value).toSet
      assertTrue(eb.contains(distinctive)) && assertTrue(!eb.contains(sharedHost))
    },
    test("computeBlockRules: Allowed-mode shared host lands in extraAllowed (most-permissive)") {
      val (allowed, blocked) =
        ProfileAppDispositions(List(disposition(AppMode.Allowed)))
          .enforcement(Map.empty, capExhausted = false, now)
      val rules              = PolicyService.computeBlockRules(
        profile = profile,
        state = stateWith(appDayState(used = 0, cap = Some(30))),
        appExtraAllowed = allowed,
        appExtraBlocked = blocked,
      )
      val ea                 = rules.extraAllowed.map(_.value).toSet
      assertTrue(ea.contains(distinctive)) && assertTrue(ea.contains(sharedHost))
    },
  )
}
