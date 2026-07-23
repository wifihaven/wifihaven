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
 * on low bytes / short activity. For the same reason the background set enumerates *specific*
 * `googleapis.com` subdomains (client-services bootstrap, the safe-browsing gateway) rather than
 * the `googleapis.com` apex — the apex would absorb legitimate per-app API traffic that must
 * attribute and count. (#2369 demoted those two Google-fronted subdomains, plus the gvt2/gvt3/
 * nel.goog/app-analytics/safebrowsing beacons, from [[canonical]] to [[suppressOnly]] — see the TWO
 * TIERS note — but they remain enumerated as specific subdomains on the background set.)
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
 *     #2369 later demoted seven Google-fronted telemetry / analytics / safe-browsing / download
 *     hosts here for the SAME reason: they share YouTube's GFE anycast IP pool, so allow-carving
 *     them leaked every Google host-block at the IP layer (the connection-critical OCSP responder
 *     `ocsp.pki.goog` deliberately stays on [[canonical]]).
 *
 * Presence/dashboard suppression uses [[isBackground]] (= `canonical ++ suppressOnly`); the policy
 * allow carve-out uses [[canonical]] only.
 */
object InfraHosts {

  val canonical: List[String] = List(
    // ── Connectivity / captive-portal probes ──────────────────────────────
    "connectivitycheck.gstatic.com", // Android / Chrome connectivity probe
    "captive.apple.com",             // iOS / macOS captive-portal probe
    "msftconnecttest.com",     // #1540 Windows NCSI connectivity probe (www / ipv6 subdomains)
    "msftncsi.com",            // #1540 Windows NCSI legacy/secondary probe (www / dns subdomains)
    // ── CA OCSP / CRL responders ──────────────────────────────────────────
    "ocsp.apple.com",          // Apple OCSP responder
    "ocsp2.apple.com",         // Apple OCSP responder (secondary)
    "crl.apple.com",           // Apple CRL distribution
    "ocsp.pki.goog",           // Google Trust Services OCSP
    "ocsp.digicert.com",       // DigiCert OCSP (common CA for app backends)
    // ── Apple edge / OS infra ─────────────────────────────────────────────
    "g.aaplimg.com",           // Apple geo-edge CDN: OCSP + asset shards
    "netcts.cdn-apple.com",    // #1337 Apple network-connectivity-test CDN
    "ls.apple.com",            // #1503 Apple location services (*.ls.apple.com)
    // ── Analytics / telemetry SaaS ────────────────────────────────────────
    // NOTE (#2369): the Google-fronted telemetry / analytics / safe-browsing / download-beacon
    // hosts that used to live in this section — `clientservices.googleapis.com`, `gvt2.com`,
    // `gvt3.com`, `nel.goog`, `app-analytics-services.com`, `safebrowsing.google.com`,
    // `safebrowsingohttpgateway.googleapis.com` — have been DEMOTED to [[suppressOnly]]. They
    // front on Google's SHARED GFE anycast pool (the same IPs `youtube.com` / `googlevideo.com`
    // resolve to), so allow-carving them punched YouTube's IPs into `@global_allow` and leaked
    // every Google host-block at the IP layer. `events.launchdarkly.com` / `adobe.io` stay here:
    // they don't front on a blockable property's pool, so they carry no shared-IP leak.
    "events.launchdarkly.com", // #1503 LaunchDarkly analytics events
    "adobe.io",                // #1503 Adobe telemetry API (cc-api-data.adobe.io, …)
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
    "smp-device-content.apple.com",  // System Management Push content
    "humb.apple.com",                // background metrics
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
    // ── #1694 third iteration of the kid-away phantom-engagement bug class
    //    (#1629 → #1669 → #1675 → this). Captured 2026-06-12 13:00–19:00 UTC
    //    on Quintus iPad (26:74:fc:f9:4e:9e) during the 09:00–15:00 ET
    //    kid-at-school window: 52 min of phantom engagement from device-level
    //    background services NOT covered by the prior iterations. Same
    //    suppress-only reasoning as #1629 — these are background, not user-
    //    initiated, but we do not allow-carve them through the block.
    //
    // Apple first-party widget / OS backends — periodic widget feed polls
    // (Weather, News), Game Center profile background, Apple ID device
    // attestation, Siri command backend (only background when no Siri use),
    // and the iOS asset/update CDN. Each is a sibling subdomain of apple.com
    // and is listed explicitly to avoid suppressing the apex (which would
    // shadow real user-facing Apple surfaces).
    "weather-edge.apple.com",
    "news-edge.apple.com",
    "profile.gc.apple.com",
    "static.gc.apple.com",
    "aidc.apple.com",
    "guzzoni.apple.com",
    "sequoia.cdn-apple.com",
    // Google background services that run on iOS too (the Google app,
    // Chrome, Gmail, and YouTube all use this control plane). Listed
    // explicitly as sibling subdomains rather than as the `google.com`
    // apex — the apex would absorb the kid's real Google product traffic.
    // `android.clients.google.com` runs on iOS despite the name.
    "android.clients.google.com",
    "clients1.google.com",
    "clients2.google.com",
    "clients3.google.com",
    "clients4.google.com",
    "clients5.google.com",
    "clients6.google.com",
    // Third-party telemetry / crash-reporting SaaS embedded in apps via SDK.
    // Apex form so any tenant subdomain matches (the per-app subdomain
    // prefix varies, e.g. `o45050….ingest.us.sentry.io`,
    // `sessions.bugsnag.com`).
    //
    // `sentry.io` and `bugsnag.com` apex form suppresses the vendors' own
    // product UIs too (`sentry.io` is Sentry's dashboard URL, `app.bugsnag.com`
    // is Bugsnag's). Deliberate trade-off: a kid profile won't visit a crash-
    // reporting dashboard, and the alternative — enumerating every SDK ingest
    // host per vendor — re-opens the gap each new project ID. Same shape as
    // the iCloud apex collateral pinned for #1629.
    //
    // 1Password telemetry (`1passwordservices.com`) is NOT on this list: the
    // operator-authored 1Password app already covers it as a member host, so
    // it attributes to that app (which is `allowed` + `exemptFromDaily` for
    // every assigned profile) instead of being suppressed. Putting it here
    // would be redundant given #1506 (app attribution wins over suppression),
    // and the app-attribution path is the canonical model when a user-allowed
    // app exists.
    "sentry.io",
    "bugsnag.com",
    // Plex pubsub keepalive — the long-poll notification channel runs
    // independent of any Plex client activity. Narrow host (NOT the
    // `plex.tv` apex): leaving the apex unmatched preserves real Plex client
    // attribution for media playback. The matcher is suffix-based, so
    // `*.pubsub.plex.tv` would also match, but Plex doesn't publish any
    // such subdomain — this entry effectively pins `pubsub.plex.tv`.
    "pubsub.plex.tv",
    // ── #1672 Bucket A residual after #1669 — non-Apple orphan tail captured
    //    from `/api/profiles/<id>/usage-by-app` on kid profiles 1 (Kids), 5
    //    (Quintus), 6 in the 2026-06-10..06-11 prod orphan window. Each
    //    sub-section is suppress-only (no allow-carve role); specific
    //    subdomains rather than apexes where the apex would absorb legitimate
    //    per-app traffic on sibling subdomains. Per #1506 `Presence.isAppAttributed`,
    //    if a future app template claims one of these hosts, app attribution wins
    //    over suppression — the entries are a fallback.
    //
    //    Note: `clients4.google.com` and `android.clients.google.com` from the
    //    original #1672 evidence list are already covered by the #1694
    //    `clients{1..6}.google.com` + `android.clients.google.com` block above,
    //    so they are not re-listed here.
    //
    // Brave browser telemetry (profile 1): Shields telemetry collector + STAR
    // randomness service. Background, not user-initiated.
    "collector.bsg.brave.com",
    "star-randsrv.bsg.brave.com",
    // Google user-content background polling. Sibling-subdomain seam — the
    // `googleusercontent.com` apex is deliberately NOT swept in, so app-owned
    // subdomains (e.g. `lh3.googleusercontent.com`, `photos.googleusercontent.com`)
    // keep attributing to their apps.
    "clients2.googleusercontent.com",
    // Ad-mediation / ad-quality signals (profile 5, profile 6). Mediation
    // traffic is not user engagement; if an ad-supported app page is in scope,
    // its template attributes the visible activity, not these background calls.
    "oa.openxcdn.net",
    "ep2.adtrafficquality.google",
    "a-adq.mediation.unity3d.com",
    // Asset CDNs whose attribution follows the embedding page — if the page is
    // an orphan, the asset fetch is too. Specific subdomains so real apps that
    // use sibling subdomains of `gstatic.com` / `googleusercontent.com` keep
    // attributing. (The `ssl.gstatic.com` host is deliberately NOT added: the
    // gstatic apex is too broad and `ssl.gstatic.com` itself fans out across
    // many app surfaces — flagged in the PR body for operator decision.)
    "use.fontawesome.com",
    "encrypted-tbn0.gstatic.com",
    "ci3.googleusercontent.com",
    // ── #2369 Google shared-GFE frontend hosts — DEMOTED from [[canonical]] to suppress-only.
    //    These device-level Google telemetry / analytics / safe-browsing / download-beacon hosts
    //    front on Google's SHARED Global Front End anycast pool, which also serves `youtube.com`,
    //    `googlevideo.com`, `ytimg.com` and the rest of Google's blockable properties. While they
    //    were on `canonical` they were allow-carved into every profile's `global.extraAllowed`
    //    (`PolicyService.infraAllowHosts`); the router flattens that into `@global_allow` and the
    //    per-host drop rule reads `... ip daddr @eb_<host> ip daddr != @global_allow drop`. Because
    //    a shared GFE IP resolved for one of these allow-carved hosts also lands in `@global_allow`,
    //    the `!= @global_allow` guard went false for exactly that IP and the drop was SKIPPED — so a
    //    host-block on YouTube (or any Google property) leaked whenever the browser's connection
    //    landed on a shared IP. Live #2369 evidence: `142.251.46.142` was in BOTH `eb_youtube_com`
    //    and `global_allow` (added by `app-analytics-services.com`), and `clientservices.googleapis.com`
    //    was observed resolving to YouTube's exact frontend IP.
    //
    //    Moving them here PRESERVES their #1503/#1499 presence-suppression exactly (they stay on
    //    `background = canonical ++ suppressOnly`, so e.g. `gvt2.com` — ~36% of the #1499 background
    //    over-count — keeps being suppressed from engagement) while removing them from the allow
    //    carve-out. This is the same suppress-but-never-allow-carve reasoning `suppressOnly` was
    //    created for (`mask*.icloud.com` Private Relay): allow-carving a host that shares a blocked
    //    property's reachability defeats the block.
    //
    //    BOUNDARY: only the NON-connectivity-critical Google hosts are demoted. The Google Trust
    //    Services OCSP responder `ocsp.pki.goog` STAYS on `canonical` — OCSP validates TLS certs for
    //    the hosts a device legitimately reaches under a block (including allowed apps), so cutting it
    //    is a broader, user-visible failure than the narrow residual leak it leaves (a blocked Google
    //    host happening to share an OCSP-resolved IP). That residual is closed fully only by SNI-level
    //    disambiguation (the SNI sidecar already sees the ClientHello host) — tracked in #2377.
    "clientservices.googleapis.com", // Chrome variations / client-services bootstrap
    "gvt2.com",                      // Google download / Play / Widevine infra (all subdomains)
    "gvt3.com",                      // Google update beacons (sibling of gvt2)
    "nel.goog",                      // Network Error Logging beacons (*.nel.goog)
    "app-analytics-services.com",    // app analytics beacons (#2369 confirmed leak vector)
    "safebrowsing.google.com",       // Google Safe Browsing
    "safebrowsingohttpgateway.googleapis.com", // Safe Browsing OHTTP gateway
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

  // ── #2177: device-cloud BACKGROUND CLASS (anchor-eligibility ONLY) ─────────────
  //
  // Apex/suffix families of first-party-cloud telemetry / sync / OS-API / private-API
  // endpoints a device reaches without the user initiating anything, that the #2091
  // isolation learner STRUCTURALLY cannot classify ambient: they fire in dense
  // co-occurring wakeup/sync BURSTS (morning ~06:30–08:00), never in the ≤2-host
  // "isolated" spans the learner keys on, so no isolated day ever accrues. On prod
  // (2026-07-13 kid-iPad replay, docs/design/idle-traffic-discrimination.md §residual)
  // these anchored the residual phantom that survived the shipped gate.
  //
  // DISTINCT ROLE from [[canonical]] / [[suppressOnly]]: this tier is NOT suppression
  // and NOT allow-carve. It is consumed ONLY by the #2077 ambient anchor gate
  // ([[wifihaven.api.presence.Presence.ambientGatedRowsWithDropCount]]) to decide
  // ANCHOR eligibility — a host here cannot be the SOLE engagement anchor of a
  // presence span, so a burst composed only of (cloud-background ∪ learned-ambient ∪
  // IP-literals) drops. A row here still COUNTS when its span is anchored by a real
  // engagement host (a co-present non-background FQDN, or an app-attributed row), so
  // real sessions that merely touch these are never shaved (#1446/#2068 undercount
  // stays closed), and #1506 app-attribution still wins (a template claiming one of
  // these makes it a real anchor). Because it only ever removes an ANCHOR (never
  // suppresses a row outright) and rides the operator-gated, inspectable
  // `ambient_gate_enabled` switch, it may safely key on class-level apexes that
  // [[canonical]] deliberately avoids.
  //
  // CLASS-LEVEL, not per-host — the structural leverage over the InfraHosts curation
  // treadmill (design doc "fourth curation iteration"): one `apps.apple.com` or
  // `-pa.googleapis.com` entry covers every current and future member of that family,
  // so Apple/Google minting new background hostnames does not re-open the gap.
  //
  // Deliberately EXCLUDES ambiguous user-facing surfaces (`lh3.googleusercontent.com`
  // photos, `chat.google.com`, `accounts.google.com`, `ssl.gstatic.com`, the
  // `duolingo.com` apex) so genuine engagement on them still anchors — accepted
  // residual over a real-use casualty (the #1629 iCloud-apex collateral precedent).
  val cloudBackground: List[String] = List(
    // Apple App Store / Music / Media API backends (background polls, not user browsing)
    "apps.apple.com",
    "amp-api.media.apple.com",
    // iCloud photo / asset sync lanes (NOT `icloud.com` — that apex is already suppressOnly)
    "icloud-content.com",
    // Apple asset / config CDN background (cstat / idv / app-site-association / …)
    "cdn-apple.com",
    // Apple software distribution / update payload
    "swdist.apple.com",
    "swcdn.apple.com",
    // Apple push-status, ads SDK, Health background, safe-browsing tokens
    "wps.apple.com",
    "iadsdk.apple.com",
    "health.apple.com",
    "safebrowsing.apple",
    // Firebase Analytics (embedded in apps via SDK; pure telemetry)
    "app-measurement.com",
    // Google OAuth / token-refresh control plane (co-occurs with real login, which
    // anchors on its own app host; standalone it is background)
    "oauth2.googleapis.com",
    "oauthaccountmanager.googleapis.com",
    "securetoken.googleapis.com",
    // Plex analytics beacon (NOT `plex.tv` apex — that stays real-attributable)
    "analytics.plex.tv",
    // Duolingo background telemetry beacon (design-doc named). The app's REAL API/content
    // hosts (`ios-api-cf.duolingo.com`, `www.duolingo.com`) are deliberately NOT here —
    // they carry genuine engagement and must keep anchoring even without an app template.
    "excess.duolingo.com",
    "excess-ga.duolingo.com",
    // ── #2274 idle-Mac background-sync tail. Captured 2026-07-17 on the Kids profile's
    //    MacBook (`ca:ef:a1:72:6a:a3`) sitting lid-closed in a cabinet all day: macOS
    //    Power-Nap wakes every ~15 min emitted single-sample bursts to these app-updater /
    //    telemetry / OS-config endpoints, each anchoring a phantom presence span (~16 min of
    //    the 31-min phantom over-count; offline replay in
    //    docs/design/idle-traffic-discrimination.md §2274). Like the rest of this class they
    //    fire only in dense co-occurring wakeup bursts, so the #2091 isolation learner
    //    structurally cannot learn them. Scoped to unambiguous background — the dual-use
    //    Google asset/auth tail (docs/drive/gstatic/photos) is deliberately left to the
    //    learner + the #2287 isolated-span follow-up, NOT blanket-classed.
    //
    // Serato DJ telemetry / update (apex covers insights. / id. / static. subdomains)
    "serato.com",
    // Brave browser component / update / usage-telemetry. Sibling telemetry (collector.bsg /
    // star-randsrv) lives on suppressOnly (never counts); these land here on the class
    // (anchor-ineligible, but still count inside a genuinely-anchored span) deliberately —
    // #2274 is an ANCHOR problem (they anchored phantom spans), and the class tier keeps the
    // #1446/#2068 no-undercount guarantee that outright suppression would forgo. Specific
    // hosts — the brave.com apex is NOT swept in, so real Brave search/product keeps anchoring.
    "go-updater.brave.com",
    "brave-core-ext.s3.brave.com",
    "usage-ping.brave.com",
    // Adobe Creative Cloud OOBE (onboarding / feature-flag) background feed. Apex covers the
    // `ffc-static-cdn.` / `prod-rel-ffc-ccm.` shards; distinct from the `adobe.io` telemetry
    // API already on canonical.
    "oobesaas.adobe.com",
    // Apple OS background: A/B experiment config, background analytics, tethering captive
    // edge check, device-configuration feed — siblings of the #1629/#1694 apple.com
    // OS-services tail, listed explicitly so the apple.com apex is never swept in.
    "experiments.apple.com",
    "sylvan.apple.com",
    "tether.edge.apple",
    "device-config.pcms.apple.com",
    // Google software-update service. Explicitly NOT a `-pa` private API and NOT the
    // googleapis apex — background update control plane only.
    "update.googleapis.com",
  )

  // Google "private API" (protocol-agnostic) background services all share the
  // `-pa.googleapis.com` SUFFIX (signaler-pa / people-pa / photosdata-pa /
  // kidsmanagement-pa / ogads-pa / drivefrontend-pa / …). Matched as a suffix rather
  // than via the apex matcher: there is no literal `pa.googleapis.com` host, and the
  // `googleapis.com` apex would over-broadly absorb legitimate per-app API traffic
  // (exactly the boundary [[canonical]] documents). One entry covers the whole family.
  val cloudBackgroundSuffixes: List[String] = List("-pa.googleapis.com")

  /** Whether `fqdn` is on the #2177 device-cloud background CLASS (apex or suffix family). */
  def isCloudBackground(fqdn: String): Boolean =
    cloudBackground.exists(p => HostMatch.matchesPattern(fqdn, p)) ||
      cloudBackgroundSuffixes.exists(s => fqdn.endsWith(s))

  /**
   * #2177 host-keyed device-cloud-background CLASS predicate — the anchor-eligibility analogue of
   * [[isBackground]], consumed solely by the #2077 ambient anchor gate. IP-literal / label hosts
   * never match (the class keys on FQDN identity; IP-literals are handled by the gate's own
   * byte-floor rule).
   */
  def isCloudBackground(host: HostId): Boolean =
    host.asFqdn.exists(fqdn => isCloudBackground(fqdn.value))
}
