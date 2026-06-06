package wifihaven.shared.types

/**
 * #1503: the single canonical list of *device-level infrastructure* hosts — connectivity /
 * captive-portal probes, CA OCSP/CRL responders, OS/telemetry/analytics beacons, and safe-browsing
 * endpoints — that a device reaches without the user initiating anything.
 *
 * One list, two consumers (the unification asked for in #1503 / the #1499 over-count analysis §A.1,
 * `docs/design/presence-tuning-overcount.md`):
 *
 *   - `PolicyService.infraAllowHosts` carves these *out of the block* (copied into every profile's
 *     `extraAllowed`) so an allowed app's transitive deps stay reachable under a whole-MAC block —
 *     #1307/#1337/#1411.
 *   - `Presence` suppression drops these from *presence counting* (`Presence.isHeartbeat`) so
 *     background OS/telemetry chatter does not inflate `usedMins` — #714/#1499.
 *
 * Before #1503 these were two hand-curated lists that drifted: the presence side was missing most
 * of what infra-allow already enumerated (notably `gvt2.com` — 36% of the counted background rows
 * in the #1499 prod sample — and the OCSP responders), and that drift was the ~93%-background daily
 * over-count leak. Collapsing them into this one source of truth is the durable fix.
 *
 * BOUNDARY — device-level infra ONLY. Do NOT add per-app CDN / asset hosts (`*.akamai.net`,
 * `*.fastly.net`, or an app's branded asset domains). Those rotate and, more importantly, must
 * ATTRIBUTE to the app and COUNT (the app host-set work), not be suppressed. That seam is what
 * keeps this list from re-opening the #1446 undercount: suppression keys on host *identity*, never
 * on low bytes / short activity. For the same reason this list enumerates *specific* background
 * `googleapis.com` subdomains (client-services bootstrap, the safe-browsing gateway) rather than
 * the `googleapis.com` apex — the apex would absorb legitimate per-app API traffic that must
 * attribute and count.
 *
 * Entries are apex- or exact-host patterns (no `*.` prefix, lowercased). An apex such as `gvt2.com`
 * matches every subdomain via [[HostMatch.matchesPattern]] (and the router's trailing-suffix match
 * on the allow side). Matching is case-sensitive on the assumption of normalized input
 * (`Hostname.parse` lowercases).
 */
object InfraHosts {

  val canonical: List[String] = List(
    // ── Connectivity / captive-portal probes ──────────────────────────────
    "connectivitycheck.gstatic.com", // Android / Chrome connectivity probe
    "captive.apple.com",             // iOS / macOS captive-portal probe
    // ── CA OCSP / CRL responders ──────────────────────────────────────────
    "ocsp.apple.com",                // Apple OCSP responder
    "ocsp2.apple.com",               // Apple OCSP responder (secondary)
    "crl.apple.com",                 // Apple CRL distribution
    "ocsp.pki.goog",                 // Google Trust Services OCSP
    "ocsp.digicert.com",             // DigiCert OCSP (common CA for app backends)
    // ── Apple edge / OS infra ─────────────────────────────────────────────
    "g.aaplimg.com",                 // Apple geo-edge CDN: OCSP + asset shards
    "netcts.cdn-apple.com",          // #1337 Apple network-connectivity-test CDN
    "ls.apple.com",                  // #1503 Apple location services (*.ls.apple.com)
    // ── Google infra ──────────────────────────────────────────────────────
    "clientservices.googleapis.com", // Google client-services bootstrap
    "gvt2.com",                // #1411 Google connectivity / Play / download infra (all subdomains)
    "gvt3.com",                // #1503 Google update beacons (sibling of gvt2)
    "nel.goog",                // #1503 Network Error Logging beacons (*.nel.goog)
    // ── Analytics / telemetry SaaS ────────────────────────────────────────
    "events.launchdarkly.com", // #1503 LaunchDarkly analytics events
    "adobe.io",                // #1503 Adobe telemetry API (cc-api-data.adobe.io, …)
    "app-analytics-services.com",             // #1503 app analytics beacons
    // ── Safe-browsing / security ──────────────────────────────────────────
    "safebrowsing.google.com",                // #1503 Google Safe Browsing
    "safebrowsingohttpgateway.googleapis.com",// #1503 Safe Browsing OHTTP gateway
  )

  /** The first canonical pattern this FQDN matches, if any (used for the classify reason). */
  def matchedPattern(fqdn: String): Option[String] =
    canonical.find(p => HostMatch.matchesPattern(fqdn, p))

  /** Whether `fqdn` is device-level infrastructure on the unified [[canonical]] list. */
  def isInfra(fqdn: String): Boolean = matchedPattern(fqdn).isDefined
}
