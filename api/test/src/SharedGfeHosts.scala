package wifihaven.testinfra

import wifihaven.shared.types.*

/**
 * The Google-owned apexes that front on Google's SHARED GFE anycast pool, and therefore must never
 * become an IP-layer enforcement target in any repo-authored catalog (#2601).
 *
 * Why one list rather than a copy per spec: enforcement matches on DESTINATION IP, and both
 * catalogs reach the same nftables plane. A curated blocklist host lands in `bl_<id>`. An app
 * template's DISTINCTIVE hosts land in `extraBlocked`/`eb_<host>`, and its `sharedHosts` land in
 * `extraAllowed`/`ea_` (per #1899 the block side takes distinctive hosts only, but the allow side
 * takes the full set) — which is the worse direction, since `extraAllowed` beats every drop. The
 * hazard is the same either way, so the host set is single-sourced here and consumed by
 * `BundledBlocklistsSpec` and `AppTemplatesSpec` rather than hand-maintained twice (which is how it
 * would drift the next time this recurs).
 *
 * Prod evidence, 2026-08-06: one MAC had five hostnames dropped at a single timestamp — meaning one
 * address served all five — `static.doubleclick.net`, `pagead2.googlesyndication.com`,
 * `www.googletagmanager.com`, `www.googleadservices.com` and `drive.google.com`. Google Drive
 * downloads broke for an adult profile. See
 * `api/resources/blocklists/evidence/google-gfe-collateral-2601.md`.
 *
 * These hosts look like textbook ad-tech and the collateral is invisible from the hostname alone,
 * which is exactly why the guard has to be mechanical. #2377 (SNI-level disambiguation) is what
 * would make them safe to enforce on again.
 *
 * NOT exhaustive, and deliberately so. Two known pool members are excluded because they are
 * load-bearing for a catalog that accepts the trade on purpose: `play.google.com` (the
 * `google-play` app template — and it resolved to `2607:f8b0:400f:805::200e`, literally one of the
 * four addresses the evidence file names in `bl6_ads`) and `ai.google.dev` (the `ai` blocklist —
 * Google-fronted, though it answers from a different /48 than the addresses the evidence cites).
 * Removing either is a product decision tracked in #2605, not something a test should force.
 */
object SharedGfeHosts {

  /**
   * The eight #2601 apexes, plus the Google shared-frontend hosts #2369 independently confirmed on
   * the same pool.
   *
   * The #2369 set is pinned separately in `shared/test/src/types/InfraHostsSpec.scala`
   * (`googleSharedFrontend`) as the list that must stay OFF the infra allow-carve. That pin and
   * this one guard the same underlying fact from opposite sides — do not make a shared-GFE Google
   * host an IP-layer enforcement target, and do not let one onto the allow-carve either — so the
   * hosts are repeated here rather than left to a reader to connect. A literal collapse is not
   * available: `InfraHosts` lives in `shared/` and cannot depend on `api` test-infra. None of the
   * #2369 hosts is carried by any inline catalog today, so adding them costs nothing and closes the
   * gap where a traffic-driven pass could add `gvt2.com` or `app-analytics-services.com` to
   * `ads.yml` and hit a hazard the repo has already confirmed.
   */
  val googleAdApexes: List[Hostname] = List(
    // #2601 — the eight removed from ads.yml
    "doubleclick.net",
    "googleadservices.com",
    "googlesyndication.com",
    "googletagmanager.com",
    "googletagservices.com",
    "google-analytics.com",
    "adservice.google.com",
    "2mdn.net",
    // #2369 — confirmed in youtube.com's GFE pool; suffix matching covers their subdomains
    "app-analytics-services.com",
    "clientservices.googleapis.com",
    "gvt2.com",
    "gvt3.com",
    "nel.goog",
    "safebrowsing.google.com",
    "safebrowsingohttpgateway.googleapis.com",
  ).map(Hostname.unsafe)

  /**
   * True when `host` is one of the banned apexes OR a subdomain of one.
   *
   * Suffix rather than exact match because the observed drops were subdomains
   * (`static.doubleclick.net`, `pagead2.googlesyndication.com`, `www.googletagmanager.com`), those
   * forms resolve into the same pool and reproduce the bug identically, and a traffic-driven
   * catalog pass reads subdomains off the wire — so an exact-match guard would miss the shape most
   * likely to be re-added.
   *
   * Case-folded because `Hostname.unsafe` does NOT normalize (only `Hostname.parse` lowercases).
   * The YAML catalogs go through `parse` and are therefore already lowercase, but the inline
   * `BundledBlocklists` fixtures are built with `unsafe`, so a mixed-case literal there would
   * otherwise walk straight past this guard.
   */
  def isBanned(host: Hostname): Boolean = {
    val h = host.value.toLowerCase
    googleAdApexes.exists { apex =>
      val a = apex.value.toLowerCase
      h == a || h.endsWith("." + a)
    }
  }
}
