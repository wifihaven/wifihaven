package wifihaven.api.unit

import wifihaven.api.AppTemplates
import wifihaven.shared.types.{HostMatch, InfraHosts}
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
 * BOTH TIERS are derived, because the same specificity comparison governs both and an unguarded one
 * is the same latent surprise. The suppression tier (`canonical ++ suppressOnly`, #2815) drops the
 * row from counting outright. The class tier (`cloudBackground`, #2813) only removes the app's
 * ANCHOR role, so the row still counts inside a span anchored by something else — a milder
 * consequence, which is why #2813 shipped it without a guard, and why the two sets are kept
 * separate here rather than unioned.
 *
 * It lives on the `api` side because `app_templates` is an `api` resource; `InfraHostsSpec` in
 * `shared` cannot reach it.
 *
 * A pair here is not a bug. Each is a deliberate decision that the background lane should NOT count
 * (or should not anchor) even though the app's apex sweeps it in. Adding one means confirming that;
 * removing one means the app now claims its lane specifically enough to win.
 */
object BackgroundApexShadowSpec extends ZIOSpecDefault {

  /** The suppression tier (#2815) — what `Presence.suppressedAsBackground` compares against. */
  private val suppressionTier = InfraHosts.canonical ++ InfraHosts.suppressOnly

  /** The #2177 device-cloud background CLASS — what `Presence.isHostAnchor` compares (#2813). */
  private val classTier = InfraHosts.cloudBackground ++ InfraHosts.cloudBackgroundSuffixes

  /**
   * Every entry of `tier` an app pattern covers but claims LESS specifically than the list does.
   */
  private def shadowPairs(
      templates: List[wifihaven.api.AppTemplate],
      tier: List[String],
  ): List[(String, String)] =
    for {
      t      <- templates
      appPat <- (t.hosts ++ t.sharedHosts).map(_.value)
      bg     <- tier
      // the suffix family carries a leading `-`; strip it so the match is on the host shape
      if HostMatch.matchesPattern(bg.stripPrefix("-"), appPat)
      if InfraHosts.patternSpecificity(appPat) < InfraHosts.patternSpecificity(bg)
    } yield (appPat, bg)

  /** Pairs on the SUPPRESSION tier: the app loses and the row drops out of counting (#2815). */
  private val expectedSuppression = Set(
    "brave.com"        -> "collector.bsg.brave.com",
    "brave.com"        -> "star-randsrv.bsg.brave.com",
    "plex.tv"          -> "pubsub.plex.tv",
    "wifihaven.net"    -> "api.wifihaven.net",
    "launchdarkly.com" -> "events.launchdarkly.com",
  )

  /** Pairs on the CLASS tier: the app loses only its ANCHOR role (#2813). */
  private val expectedClass = Set(
    "1password.com" -> "client-log-forwarder.1password.com",
    "brave.com"     -> "brave-core-ext.s3.brave.com",
    "brave.com"     -> "go-updater.brave.com",
    "brave.com"     -> "usage-ping.brave.com",
    "duolingo.com"  -> "excess-ga.duolingo.com",
    "duolingo.com"  -> "excess.duolingo.com",
    "icanhazip.com" -> "ipv4.icanhazip.com",
    "icanhazip.com" -> "ipv6.icanhazip.com",
    "plex.tv"       -> "analytics.plex.tv",
  )

  def spec = suite("BackgroundApexShadowSpec (#2815)")(
    test("the catalog's apex-over-lane pairs are exactly the reviewed set, on BOTH tiers") {
      for {
        templates <- AppTemplates.loadAll()
        suppression = shadowPairs(templates, suppressionTier).toSet
        cls         = shadowPairs(templates, classTier).toSet
      } yield assertTrue(
        // LIVENESS: a catalog that failed to load, or a tier list that went empty, would make both
        // derived sets empty and satisfy a subset check for free.
        templates.size > 20,
        suppressionTier.size > 50,
        classTier.size > 20,
        expectedSuppression.nonEmpty,
        expectedClass.nonEmpty,
        suppression == expectedSuppression,
        cls == expectedClass,
      )
    },
    test("an app that names its infra host EXACTLY is not a shadow pair (the #1506 seam)") {
      // `imessage.yml` claims `ess.apple.com`, which is also a `suppressOnly` entry. Equal
      // specificity, so the app keeps attributing and it must NOT appear above — this is the case
      // the whole no-undercount argument rests on, and the only such overlap in the catalog.
      for {
        templates <- AppTemplates.loadAll()
        pairs         = shadowPairs(templates, suppressionTier).toSet
        equalOverlaps = for {
          t      <- templates
          appPat <- (t.hosts ++ t.sharedHosts).map(_.value)
          bg     <- suppressionTier
          if HostMatch.matchesPattern(bg.stripPrefix("-"), appPat)
          if InfraHosts.patternSpecificity(appPat) == InfraHosts.patternSpecificity(bg)
        } yield (appPat, bg)
      } yield assertTrue(
        equalOverlaps.contains("ess.apple.com" -> "ess.apple.com"),
        !pairs.exists { case (app, _) => app == "ess.apple.com" },
      )
    },
  )
}
