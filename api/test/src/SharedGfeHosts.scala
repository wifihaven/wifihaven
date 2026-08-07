package wifihaven.testinfra

import wifihaven.shared.types.*

/**
 * The Google-owned apexes that front on Google's SHARED GFE anycast pool, and therefore must never
 * become an IP-layer enforcement target in any repo-authored catalog (#2601).
 *
 * Why one list rather than a copy per spec: enforcement matches on DESTINATION IP, and both
 * catalogs reach the same nftables plane — a curated blocklist host lands in `bl_<id>`, an app
 * template's host-set lands in `extraBlocked`/`eb_<host>`. The hazard is identical, so the host set
 * is single-sourced here and consumed by `BundledBlocklistsSpec` and `AppTemplatesSpec` rather than
 * hand-maintained twice (which is how it would drift the next time this recurs).
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
 * NOT exhaustive, and deliberately so: it is the set this incident proved, not every Google host on
 * the pool. `play.google.com` and `ai.google.dev` also resolve into it but are load-bearing for the
 * `google-play` app template and the `ai` blocklist respectively — removing those is a product
 * decision, tracked separately, not something a test should force.
 */
object SharedGfeHosts {

  val googleAdApexes: List[Hostname] = List(
    "doubleclick.net",
    "googleadservices.com",
    "googlesyndication.com",
    "googletagmanager.com",
    "googletagservices.com",
    "google-analytics.com",
    "adservice.google.com",
    "2mdn.net",
  ).map(Hostname.unsafe)

  /**
   * True when `host` is one of the banned apexes OR a subdomain of one.
   *
   * Suffix rather than exact match because the observed drops were subdomains
   * (`static.doubleclick.net`, `pagead2.googlesyndication.com`, `www.googletagmanager.com`), those
   * forms resolve into the same pool and reproduce the bug identically, and a traffic-driven
   * catalog pass reads subdomains off the wire — so an exact-match guard would miss the shape most
   * likely to be re-added.
   */
  def isBanned(host: Hostname): Boolean =
    googleAdApexes.exists(apex => host.value == apex.value || host.value.endsWith("." + apex.value))
}
