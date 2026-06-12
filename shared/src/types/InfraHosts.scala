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
 * #1506 enforces this boundary at runtime: even if an entry here also appears in an ACTIVE app's
 * host-set, [[wifihaven.api.presence.Presence.isHeartbeat]] treats app attribution as winning over
 * suppression, so that host counts toward the app instead of being dropped as infra. This list is
 * therefore the *fallback* — it suppresses a host only when no active app claims it.
 *
 * Entries are apex- or exact-host patterns (no `*.` prefix, lowercased). An apex such as `gvt2.com`
 * matches every subdomain via [[HostMatch.matchesPattern]] (and the router's trailing-suffix match
 * on the allow side). Matching is case-sensitive on the assumption of normalized input
 * (`Hostname.parse` lowercases).
 *
 * TWO TIERS (#1525). The list has two parts because "allow through the block" and "don't count as
 * engagement" are *almost* the same set but not quite:
 *
 *   - [[canonical]] — allow **and** suppress. Safe to carve out of the block AND drop from
 *     presence. This is the set `PolicyService.infraAllowHosts` ships.
 *   - [[suppressOnly]] — suppress **only**, never allowed through the block. Folded in from the
 *     retired `household_settings.heartbeat_host_patterns` seed (#1525) — device infra that should
 *     not count as engagement but must NOT be made reachable under a block. The clearest example is
 *     iCloud Private Relay (`mask*.icloud.com`): allow-carving it would punch an anti-filtering
 *     tunnel through every block. These were already suppress-only before #1525 (they lived in the
 *     heartbeat list, never in `infraAllowHosts`), so this split preserves both behaviors exactly.
 *
 * Presence/dashboard suppression uses [[isBackground]] (= `canonical ++ suppressOnly`); the policy
 * allow carve-out uses [[canonical]] only.
 */
object InfraHosts {

  val canonical: List[String] = List(
    // ── Connectivity / captive-portal probes ──────────────────────────────
    "connectivitycheck.gstatic.com", // Android / Chrome connectivity probe
    "captive.apple.com",             // iOS / macOS captive-portal probe
    "msftconnecttest.com",  // #1540 Windows NCSI connectivity probe (www / ipv6 subdomains)
    "msftncsi.com",         // #1540 Windows NCSI legacy/secondary probe (www / dns subdomains)
    // ── CA OCSP / CRL responders ──────────────────────────────────────────
    "ocsp.apple.com",       // Apple OCSP responder
    "ocsp2.apple.com",      // Apple OCSP responder (secondary)
    "crl.apple.com",        // Apple CRL distribution
    "ocsp.pki.goog",        // Google Trust Services OCSP
    "ocsp.digicert.com",    // DigiCert OCSP (common CA for app backends)
    // ── Apple edge / OS infra ─────────────────────────────────────────────
    "g.aaplimg.com",        // Apple geo-edge CDN: OCSP + asset shards
    "netcts.cdn-apple.com", // #1337 Apple network-connectivity-test CDN
    "ls.apple.com",         // #1503 Apple location services (*.ls.apple.com)
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

  /**
   * #1525: suppress-from-presence ONLY — device infra that must NOT be allow-carved out of the
   * block. Folded in from the retired `household_settings.heartbeat_host_patterns` V24 seed (the
   * entries not already on [[canonical]]). These were suppress-only before #1525 too.
   *
   * `mask*.icloud.com` is the load-bearing reason this tier is separate from [[canonical]]: it is
   * iCloud Private Relay, an anti-filtering tunnel — counting it as engagement is wrong, but making
   * it reachable under a block would defeat the block. Apex form so subdomains match.
   */
  val suppressOnly: List[String] = List(
    "push.apple.com",      // APNs (was *.push.apple.com)
    "apple-dns.net",       // Apple DNS/edge infra (was *.apple-dns.net)
    "akadns.net",          // Akamai DNS infra (was *.akadns.net) — NOT allow-carved (CDN-DNS)
    "ess.apple.com",       // Apple enterprise/identity infra (was *.ess.apple.com)
    "time.apple.com",      // Apple time sync
    "gdmf.apple.com",      // Apple software-update metadata
    "pancake.apple.com",   // Apple Maps/infra
    "rcs.telephony.goog",  // RCS messaging infra (was *.rcs.telephony.goog)
    "mtalk.google.com",    // GCM/FCM push
    "ntp.org",             // NTP time sync (was *.ntp.org)
    "time.cloudflare.com", // Cloudflare NTP
    // ── #1629 Apple OS-services tail (suppress-only). Captured from
    //    /api/profiles/<id>/usage-by-app on kid profiles 2026-06-10..2026-06-11 during
    //    operator-pinned away+bedtime windows: device-level Apple background services
    //    (App Store/iTunes background polls, Apple search-backend beacons, push
    //    channels, location daemons, software-update metadata, analytics, Safe
    //    Browsing edge, asset/media CDNs) that the kids were not interacting with.
    //    Aggregated ~45–95 minutes of phantom orphan presence per kid per day, the
    //    single largest contributor to the #1629 widened-scope inflation.
    //
    // App Store / iTunes Store background. Apex form matches all observed
    // subdomains (p9-buy / p11-buy / p35-buy / init / ts / fpinit / auth on
    // `*.itunes.apple.com`). iMessage uses the distinct `ess.apple.com` apex,
    // so this does not shadow it. Sibling apple.com namespaces like the
    // device-config feed are listed separately below — they don't sit under
    // `itunes.apple.com`.
    "itunes.apple.com",
    // Apple search backend (Spotlight / Siri suggestions). `smoot.apple.com`
    // apex matches `api-glb-*.smoot.apple.com` / `fbs.smoot.apple.com`.
    "gsa.apple.com",
    "gsas.apple.com",
    "smoot.apple.com",
    // Push channels / system management
    "xp.apple.com",        // Apple Experience Push
    "smp-device-content.apple.com", // System Management Push content
    "humb.apple.com",               // background metrics
    // Apple analytics (not user-initiated)
    "swallow.apple.com",
    "odin-signals.apple.com",
    // Device configuration / software-update metadata. `mesu.apple.com` is the
    // update-metadata feed; `gdmf-ados.apple.com` is a separate metadata host
    // from `gdmf.apple.com` (already listed above) — both are background
    // metadata fetches, not user actions.
    "configuration.apple.com",
    "mesu.apple.com",
    "gdmf-ados.apple.com",
    // Location daemon / CDN
    "iphone-ld.apple.com",
    "lcdn-locator.apple.com",
    // Asset / media CDNs
    "publicassets.cdn-apple.com",
    "cabana-server.cdn-apple.com",
    // Apple Safe Browsing edge (the sibling of `safebrowsing.google.com`
    // already on canonical). gTLD `.apple` — exact-host match.
    "proxy.safebrowsing.apple",
    // ── #1629 iCloud — apex covers iCloud Private Relay first hop
    //    (`mask*.icloud.com`), background sync / Find My / Keychain Escrow
    //    (`p157-fmip.icloud.com`, `p192-fmf.icloud.com`,
    //    `p108-escrowproxy.icloud.com` — the shard prefix drifts per region),
    //    `gateway.icloud.com`, and any future similarly-sharded iCloud
    //    background service in one entry. (Pre-#1629 we listed three narrower
    //    `mask*.icloud.com` entries for Private Relay alone; this apex subsumes
    //    those and the granular Find-My / Escrow / gateway hosts.)
    //
    //    SUPPRESS-ONLY, NEVER ALLOW-CARVE. This is the load-bearing reason
    //    `mask*.icloud.com` lived in this tier from the start: iCloud Private
    //    Relay is an encrypted relay tunnel — allow-carving it would punch an
    //    anti-filtering bypass through every block. Same reasoning applies to
    //    any other iCloud service: count nothing toward engagement, but do not
    //    make iCloud reachable under a block.
    //
    //    Apex-form trade-off: this also matches user-facing iCloud surfaces
    //    that today aren't modelled as apps (`www.icloud.com` webmail,
    //    `beta.icloud.com`, etc.). Pinned as accepted collateral in the spec.
    //    When an iCloud-anything template lands, #1506 makes app attribution
    //    win over suppression here — same way `ess.apple.com` already coexists
    //    between this list and the iMessage template.
    "icloud.com",
    // ── #1629 iCloud Private Relay second hop. The first hop is the `icloud.com`
    //    apex above; the second hop runs on Cloudflare under
    //    `apple-relay.cloudflare.com`. Same anti-filtering-tunnel reasoning —
    //    suppress, never allow-carve.
    "apple-relay.cloudflare.com",
  )

  /** All hosts suppressed from presence counting: allow+suppress plus suppress-only (#1525). */
  private val background: List[String] = canonical ++ suppressOnly

  /** The first canonical (allow+suppress) pattern this FQDN matches, if any. */
  def matchedPattern(fqdn: String): Option[String] =
    canonical.find(p => HostMatch.matchesPattern(fqdn, p))

  /**
   * Whether `fqdn` is on the [[canonical]] allow+suppress list. Drives the policy allow carve-out.
   */
  def isInfra(fqdn: String): Boolean = matchedPattern(fqdn).isDefined

  /** The first background (allow+suppress or suppress-only) pattern this FQDN matches, if any. */
  def matchedBackgroundPattern(fqdn: String): Option[String] =
    background.find(p => HostMatch.matchesPattern(fqdn, p))

  /**
   * Whether `fqdn` is device-level background infra — the presence/dashboard suppression predicate.
   */
  def isBackground(fqdn: String): Boolean = matchedBackgroundPattern(fqdn).isDefined

  /**
   * #1560: host-keyed background-infra predicate. The SOLE entry point every presence/dashboard
   * suppression call site routes through — `Presence.isBackgroundHost`,
   * `Presence.suppressedHostUsage`, and `DashboardNowRoutes.dropBackground` all delegate here so
   * the rule cannot diverge between surfaces (the #1532 single-source-of-truth lesson). IP-literal
   * hosts never match (the suppression list keys on FQDN identity).
   */
  def isBackground(host: HostId): Boolean = host.asFqdn.exists(fqdn => isBackground(fqdn.value))
}
