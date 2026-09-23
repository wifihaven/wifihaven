package wifihaven.shared.types

/**
 * The apexes that front on Google's SHARED GFE anycast pool, and therefore must never become an
 * IP-layer enforcement target in any catalog — authored or fetched.
 *
 * Enforcement matches on DESTINATION IP, never on hostname. A blocklist host lands in `bl_<id>`; an
 * app template's distinctive hosts land in `extraBlocked`/`eb_<host>`. Either way the nftables set
 * holds the ADDRESSES the host resolved to, so blocking a host that shares a frontend pool blocks
 * every other host served from that pool.
 *
 * Prod evidence, 2026-08-06 (#2601): one MAC had five hostnames dropped at a single timestamp —
 * meaning one address served all five — `static.doubleclick.net`, `pagead2.googlesyndication.com`,
 * `www.googletagmanager.com`, `www.googleadservices.com` and `drive.google.com`. Google Drive
 * downloads broke for an adult profile. See
 * `api/resources/blocklists/evidence/google-gfe-collateral-2601.md`.
 *
 * Prod evidence, 2026-09-23 (#2809): the same failure through the FETCHED door. See
 * `api/resources/blocklists/evidence/googleapis-shared-gfe-2809.md`.
 *
 * These hosts look like textbook ad-tech and the collateral is invisible from the hostname alone,
 * which is exactly why the guard has to be mechanical. #2377 (SNI-level disambiguation) is what
 * would make them safe to enforce on again.
 *
 * **Where this is applied.** Two places, deliberately different in kind:
 *   - Repo-authored catalogs (inline blocklists, app templates) are guarded at AUTHORING time by
 *     `BundledBlocklistsSpec` / `AppTemplatesSpec`, which FAIL the build. Authoring mistakes should
 *     be loud, not silently repaired.
 *   - Fetched blocklists are filtered at INGEST by `BundledBlocklists`, because upstream content is
 *     not ours to author and can reintroduce a banned host on any refresh.
 *
 * This object lives in `shared` rather than api test-infra (where #2601 first put it) so the
 * production ingest filter and both authoring guards read one list. The #2369 pin in
 * `shared/test/src/types/InfraHostsSpec.scala` guards the same fact from the opposite side: do not
 * let a shared-GFE host onto the infra ALLOW-carve either, where it would punch the pool out of
 * every block (`extraAllowed` beats every drop, #421).
 *
 * NOT exhaustive, and deliberately so. Two known pool members are excluded because they are
 * load-bearing for a catalog that accepts the trade on purpose: `play.google.com` (the
 * `google-play` app template) and `ai.google.dev` (the `ai` blocklist). Removing either is a
 * product decision tracked in #2605, not something a guard should force.
 *
 * One collision to expect rather than be surprised by: `gvt2.com` IS banned, and #2369 documents it
 * as "Google download / Play / Widevine infra". So an author extending `google-play.yml` beyond
 * `play.google.com` to cover Play *downloads* will hit this guard. That is working as intended —
 * gvt2 is shared frontend — but it is the one plausible-soon red that has no obvious explanation
 * from the failure message alone.
 */
object SharedGfeHosts {

  /**
   * The #2601 ad apexes, the Google shared-frontend hosts #2369 independently confirmed on the same
   * pool, and the #2809 class-level `googleapis.com` entry.
   */
  val googleAdApexes: List[Hostname] = List(
    // ── #2601 — the eight removed from ads.yml ──────────────────────────────
    "doubleclick.net",
    "googleadservices.com",
    "googlesyndication.com",
    "googletagmanager.com",
    "googletagservices.com",
    "google-analytics.com",
    "adservice.google.com",
    "2mdn.net",
    // ── #2369 — demoted off the infra allow-carve as Google shared-frontend ──
    // Two were individually observed on youtube.com's pool
    // (`app-analytics-services.com` was the confirmed leak vector;
    // `clientservices.googleapis.com` resolved to YouTube's exact frontend IP);
    // the other five were demoted by CLASS. Suffix matching covers their
    // subdomain forms, so all twelve `googleSharedFrontend` entries are reached.
    "app-analytics-services.com",
    "clientservices.googleapis.com", // subsumed by `googleapis.com` below; kept for the record
    "gvt2.com",
    "gvt3.com",
    "nel.goog",
    "safebrowsing.google.com",
    "safebrowsingohttpgateway.googleapis.com", // likewise subsumed; kept for the record
    // ── #2809 — CLASS-LEVEL. The whole `googleapis.com` frontend is one pool. ──
    // Measured 2026-09-23 against the live resolver: `oauthaccountmanager`,
    // `securetoken`, `firebaselogging`, `firebaselogging-pa`, `clientmetrics-pa`,
    // `ogads-pa`, `people-pa`, `signaler-pa` and `kidsmanagement-pa` — Google's
    // login control plane and its ad/telemetry API surface alike — all answer
    // from the SAME eight addresses, 172.217.112.4 … 172.217.119.4.
    //
    // So IP-layer enforcement on ANY `googleapis.com` host is unsound on its own
    // terms: it does not block that host, it blocks the pool, which is Google
    // sign-in for every device on the MAC. That makes this a class rule rather
    // than a list of the four members that happen to be in today's upstream file
    // — the structural leverage `InfraHosts.cloudBackgroundSuffixes` documents,
    // so Google minting new API hostnames does not re-open the gap.
    //
    // No repo catalog carries a `googleapis.com` host today, so the authoring
    // guards are unaffected by the widening.
    "googleapis.com",
  ).map(Hostname.unsafe)

  /**
   * True when `host` is one of the banned apexes OR a subdomain of one.
   *
   * Suffix rather than exact match because the observed drops were subdomains
   * (`static.doubleclick.net`, `pagead2.googlesyndication.com`, `www.googletagmanager.com`), those
   * forms resolve into the same pool and reproduce the bug identically, and both a traffic-driven
   * catalog pass and an upstream hosts file are overwhelmingly subdomains — so an exact-match guard
   * would miss the shape most likely to appear.
   *
   * Case-folded because `Hostname.unsafe` does NOT normalize (only `Hostname.parse` lowercases).
   * The YAML catalogs go through `parse` and are therefore already lowercase, but inline fixtures
   * are built with `unsafe`, so a mixed-case literal would otherwise walk straight past this.
   */
  def isBanned(host: Hostname): Boolean = {
    val h = host.value.toLowerCase
    lowered.exists { case (apex, dotApex) => h == apex || h.endsWith(dotApex) }
  }

  // Precomputed once: `isBanned` runs over every host of every fetched list at ingest
  // (tens of thousands of hosts x this list on the startup critical path), so folding case
  // and building the dotted form per (host, apex) pair would be a throwaway allocation per
  // comparison, on a comparison that never changes.
  private val lowered: List[(String, String)] =
    googleAdApexes.map { apex =>
      val a = apex.value.toLowerCase
      (a, "." + a)
    }
}
