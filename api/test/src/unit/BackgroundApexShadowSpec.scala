package wifihaven.api.unit

import wifihaven.api.AppTemplates
import wifihaven.shared.types.{HostMatch, InfraHosts}
import zio.*
import zio.test.*

/**
 * #2815: the real catalog guard. `Presence.suppressedAsBackground` now resolves an app pattern
 * against a background entry by SPECIFICITY, so the set of (app pattern, background entry) pairs
 * where the app is STRICTLY less specific is the exact set of places that rule changes an outcome.
 *
 * This spec derives that set from the shipped catalog rather than restating it, so adding a
 * template that claims a brand apex over an enumerated background lane — or adding a background
 * entry underneath an existing app apex — fails here and makes someone look at it deliberately.
 * That is the guard the first cut of this work claimed to have and did not: a hardcoded list cannot
 * notice a fifth pair.
 *
 * It lives on the `api` side because `app_templates` is an `api` resource; `InfraHostsSpec` in
 * `shared` cannot reach it.
 *
 * A pair here is not a bug. Each is a deliberate decision that the background lane should NOT count
 * even though the app's apex sweeps it in. Adding one means confirming that; removing one means the
 * app now claims its lane specifically enough to win.
 */
object BackgroundApexShadowSpec extends ZIOSpecDefault {

  /**
   * Every background entry an app pattern covers but claims LESS specifically than the list does.
   */
  private def shadowPairs(templates: List[wifihaven.api.AppTemplate]): List[(String, String)] =
    for {
      t      <- templates
      appPat <- (t.hosts ++ t.sharedHosts).map(_.value)
      bg     <- InfraHosts.canonical ++ InfraHosts.suppressOnly
      if HostMatch.matchesPattern(bg, appPat)
      if InfraHosts.patternSpecificity(appPat) < InfraHosts.patternSpecificity(bg)
    } yield (appPat, bg)

  private val expected = Set(
    "brave.com"        -> "collector.bsg.brave.com",
    "brave.com"        -> "star-randsrv.bsg.brave.com",
    "plex.tv"          -> "pubsub.plex.tv",
    "wifihaven.net"    -> "api.wifihaven.net",
    "launchdarkly.com" -> "events.launchdarkly.com",
  )

  def spec = suite("BackgroundApexShadowSpec (#2815)")(
    test("the catalog's apex-over-lane pairs are exactly the reviewed set") {
      for {
        templates <- AppTemplates.loadAll()
        actual = shadowPairs(templates).toSet
      } yield assertTrue(
        // LIVENESS: a catalog that failed to load, or a background list that went empty, would make
        // `actual` empty and satisfy a subset check for free.
        templates.size > 20,
        (InfraHosts.canonical ++ InfraHosts.suppressOnly).size > 50,
        expected.nonEmpty,
        actual == expected,
      )
    },
    test("an app that names its infra host EXACTLY is not a shadow pair (the #1506 seam)") {
      // `imessage.yml` claims `ess.apple.com`, which is also a `suppressOnly` entry. Equal
      // specificity, so the app keeps attributing and it must NOT appear above — this is the case
      // the whole no-undercount argument rests on.
      for {
        templates <- AppTemplates.loadAll()
        pairs         = shadowPairs(templates).toSet
        equalOverlaps = for {
          t      <- templates
          appPat <- (t.hosts ++ t.sharedHosts).map(_.value)
          bg     <- InfraHosts.canonical ++ InfraHosts.suppressOnly
          if HostMatch.matchesPattern(bg, appPat)
          if InfraHosts.patternSpecificity(appPat) == InfraHosts.patternSpecificity(bg)
        } yield (appPat, bg)
      } yield assertTrue(
        equalOverlaps.contains("ess.apple.com" -> "ess.apple.com"),
        !pairs.exists { case (app, _) => app == "ess.apple.com" },
      )
    },
  )
}
