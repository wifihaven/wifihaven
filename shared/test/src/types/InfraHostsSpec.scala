package wifihaven.shared.types

import zio.test.*

object InfraHostsSpec extends ZIOSpecDefault {

  def spec = suite("InfraHosts")(
    test("apex entries match every subdomain (gvt2 download shards, ls.apple services)") {
      assertTrue(
        InfraHosts.isInfra("gvt2.com"),
        InfraHosts.isInfra("r3---sn-abc.gvt2.com"),
        InfraHosts.isInfra("beacons3.gvt2.com"),
        InfraHosts.isInfra("gvt3.com"),
        InfraHosts.isInfra("gsp-ssl.ls.apple.com"),
        InfraHosts.isInfra("b1.nel.goog"),
      )
    },
    test("exact-host entries match the host and its subdomains") {
      assertTrue(
        InfraHosts.isInfra("connectivitycheck.gstatic.com"),
        InfraHosts.isInfra("safebrowsingohttpgateway.googleapis.com"),
        InfraHosts.isInfra("events.launchdarkly.com"),
        InfraHosts.isInfra("ocsp.digicert.com"),
      )
    },
    test("#1503 expansion covers the observed #1499 leaking infra classes") {
      val expanded = List(
        "x.gvt2.com",
        "x.gvt3.com",
        "x.ls.apple.com",
        "b1.nel.goog",
        "events.launchdarkly.com",
        "cc-api-data.adobe.io",
        "app-analytics-services.com",
        "safebrowsing.google.com",
        "safebrowsingohttpgateway.googleapis.com",
      )
      assertTrue(expanded.forall(InfraHosts.isInfra))
    },
    test("BOUNDARY: per-app CDN / asset hosts are NOT infra (they must attribute and count)") {
      // The seam that keeps suppression from re-opening the #1446 undercount: rotating per-app
      // CDN edges and an app's branded domains attribute to the app (#1505), never suppressed.
      val appOrCdn = List(
        "a1744.dscw154.akamai.net",
        "prod.khan.map.fastly.net",
        "www.mathacademy.com",
        "cdn.kastatic.org",
        "www.youtube.com",
        "www.tinkercad.com",
        // the googleapis apex is deliberately NOT on the list — only specific background
        // subdomains are — so legitimate per-app API traffic still attributes and counts.
        "firestore.googleapis.com",
      )
      assertTrue(appOrCdn.forall(h => !InfraHosts.isInfra(h)))
    },
    test("#1540 Windows NCSI connectivity probes are allow-carved and suppressed (apex matches)") {
      // Windows' Network Connectivity Status Indicator probes are device-level OS connectivity
      // checks the user never initiates → canonical (allow-carve + presence-suppress), same tier
      // as the Apple/Android probes. Apex form matches the www/ipv6/dns subdomains.
      val ncsi = List(
        "www.msftconnecttest.com",
        "ipv6.msftconnecttest.com",
        "www.msftncsi.com",
        "dns.msftncsi.com",
      )
      assertTrue(
        ncsi.forall(InfraHosts.isInfra),
        ncsi.forall(InfraHosts.isBackground),
        // WARP is an encrypted VPN tunnel — allow-carving it would punch an anti-filtering bypass
        // through every block (same reasoning as iCloud Private Relay in suppressOnly). Not carved.
        !InfraHosts.isInfra("connectivity.cloudflareclient.com"),
      )
    },
    test("matchedPattern returns the canonical pattern that matched") {
      assertTrue(
        InfraHosts.matchedPattern("r3---sn-abc.gvt2.com").contains("gvt2.com"),
        InfraHosts.matchedPattern("www.tinkercad.com").isEmpty,
      )
    },
    test("every canonical entry is a parseable lowercased hostname (valid for extraAllowed)") {
      assertTrue(
        InfraHosts.canonical.forall(h => Hostname.parse(h).isRight),
        InfraHosts.canonical.forall(h => h == h.toLowerCase),
        InfraHosts.canonical.forall(h => !h.startsWith("*.")),
      )
    },
    test(
      "#1525 suppress-only tier (folded from heartbeat_host_patterns) is background, not allowed",
    ) {
      // These suppress from presence counting (isBackground) but must NOT be allow-carved (isInfra):
      // allowing iCloud Private Relay through a block would be an anti-filtering bypass.
      val suppressOnly = List(
        "api-push.push.apple.com",
        "x.apple-dns.net",
        "y.akadns.net",
        "time.apple.com",
        "gdmf.apple.com",
        "mask.icloud.com",
        "mask-h2.icloud.com",
        "mtalk.google.com",
        "z.rcs.telephony.goog",
        "pool.ntp.org",
        "time.cloudflare.com",
      )
      assertTrue(
        suppressOnly.forall(InfraHosts.isBackground),
        suppressOnly.forall(h => !InfraHosts.isInfra(h)),
        // Private Relay specifically must never be on the allow (canonical) list.
        !InfraHosts.isInfra("mask.icloud.com"),
        InfraHosts.isBackground("mask.icloud.com"),
      )
    },
    test("#1525 canonical hosts are both allowed and background; the boundary holds for both") {
      assertTrue(
        InfraHosts.isInfra("gvt2.com") && InfraHosts.isBackground("gvt2.com"),
        // app/CDN hosts are neither allowed nor suppressed.
        !InfraHosts.isBackground("www.tinkercad.com"),
        !InfraHosts.isBackground("firestore.googleapis.com"),
        !InfraHosts.isBackground("a1744.dscw154.akamai.net"),
      )
    },
    test("#1525 every suppressOnly entry is a parseable lowercased apex/exact host") {
      assertTrue(
        InfraHosts.suppressOnly.forall(h => Hostname.parse(h).isRight),
        InfraHosts.suppressOnly.forall(h => h == h.toLowerCase),
        InfraHosts.suppressOnly.forall(h => !h.startsWith("*.")),
      )
    },
    test("#1629 Apple OS-services tail observed in prod orphan presence is suppressed") {
      // Captured from `GET /api/profiles/<id>/usage-by-app` on 2026-06-10..2026-06-11 across
      // kid profiles (Prima/Quintus/Kids) during operator-pinned away+bedtime windows. These
      // are device-level Apple OS background services (App Store/iTunes background polls,
      // Apple search-backend beacons, push channels, location daemons, software-update
      // metadata, analytics, Safe Browsing edge) that the kids were not interacting with —
      // they belong on the suppress side of the InfraHosts boundary, same tier as the
      // existing Apple entries (`g.aaplimg.com`, `netcts.cdn-apple.com`, `ls.apple.com`,
      // `ess.apple.com`, `gdmf.apple.com`, …). Suppress, not allow-carve: these don't
      // need to be reachable under a block.
      val appleTail = List(
        // App Store / iTunes Store background polling (covers p9-buy / p11-buy / p35-buy /
        // auth / init / ts / fpinit subdomains observed in prod)
        "init.itunes.apple.com",
        "ts.itunes.apple.com",
        "auth.itunes.apple.com",
        "fpinit.itunes.apple.com",
        "p9-buy.itunes.apple.com",
        "p11-buy.itunes.apple.com",
        "p35-buy.itunes.apple.com",
        // Apple search backend
        "gsa.apple.com",
        "gsas.apple.com",
        "api-glb-ausw2c.smoot.apple.com",
        "fbs.smoot.apple.com",
        // Push channels / system management
        "xp.apple.com",
        "smp-device-content.apple.com",
        "humb.apple.com",
        // Apple analytics
        "swallow.apple.com",
        "odin-signals.apple.com",
        // Device configuration / software update metadata
        "configuration.apple.com",
        "mesu.apple.com",
        "gdmf-ados.apple.com",
        // Location daemon / CDN
        "iphone-ld.apple.com",
        "lcdn-locator.apple.com",
        // Asset / media CDN
        "publicassets.cdn-apple.com",
        "cabana-server.cdn-apple.com",
        // Apple Safe Browsing edge (sibling of the canonical Google entries)
        "proxy.safebrowsing.apple",
      )
      assertTrue(
        appleTail.forall(InfraHosts.isBackground),
        // BOUNDARY: this is a suppress-only addition, not an allow-carve. None of these
        // should land on the canonical (`isInfra`) list — they have no app-allowlist
        // carve-out role.
        appleTail.forall(h => !InfraHosts.isInfra(h)),
      )
    },
    test("#1629 iCloud background services observed in prod orphan presence are suppressed") {
      // Same provenance as the Apple tail above — iCloud sync / Find-My / Keychain Escrow
      // background that runs without user initiation. Suppress-only, same reasoning as
      // mask.icloud.com: making these reachable under a block is not the goal; they
      // simply shouldn't count as engagement.
      val iCloudTail = List(
        "p157-fmip.icloud.com",        // Find My iPhone
        "p192-fmf.icloud.com",         // Find My Friends
        "p157-fmfmobile.icloud.com",   // Find My Friends Mobile
        "p108-escrowproxy.icloud.com", // iCloud Keychain Escrow
        "gateway.icloud.com",          // iCloud gateway
      )
      assertTrue(
        iCloudTail.forall(InfraHosts.isBackground),
        iCloudTail.forall(h => !InfraHosts.isInfra(h)),
      )
    },
    test(
      "#1629 icloud.com apex collateral: user-facing iCloud surfaces are also suppressed today",
    ) {
      // The `icloud.com` apex is intentionally broad — necessary because the kid hosts
      // are per-region-sharded (`p157-fmip` / `p192-fmf` / `p108-escrowproxy`) and the
      // matcher has no `*-` infix wildcards. Pin the accepted trade-off explicitly so
      // it's visible to whoever later adds an iCloud-anything app template: today
      // these user-facing iCloud surfaces ARE suppressed from presence counting, and
      // that's the trade-off — when an app template lands, #1506
      // (`Presence.isAppAttributed`) makes app attribution win over suppression for
      // hosts the template claims, identical to how `ess.apple.com` already coexists
      // between this list and the iMessage template.
      val collateral = List(
        "www.icloud.com",  // iCloud webmail
        "beta.icloud.com", // iCloud beta surfaces
        "setup.icloud.com",// device setup flow
      )
      assertTrue(
        collateral.forall(InfraHosts.isBackground),
        // BOUNDARY still holds: collateral is suppress-only, never allow-carved.
        collateral.forall(h => !InfraHosts.isInfra(h)),
      )
    },
    test("#1629 iCloud Private Relay second hop (apple-relay.cloudflare.com) is suppressed") {
      // The mask.icloud.com entry covers the first hop; the second hop is served from
      // Cloudflare under apple-relay.cloudflare.com. Same anti-filtering-tunnel reasoning
      // as the first hop — suppress (don't count as engagement) but NEVER allow-carve.
      assertTrue(
        InfraHosts.isBackground("apple-relay.cloudflare.com"),
        InfraHosts.isBackground("ingress.apple-relay.cloudflare.com"),
        !InfraHosts.isInfra("apple-relay.cloudflare.com"),
      )
    },
    test("#1672 Brave browser telemetry background hosts are suppressed") {
      // Brave Shields telemetry / STAR randomness — clearly device-level telemetry,
      // not user-initiated. Captured on profile 1 (Kids) in 2026-06-10..06-11 prod
      // orphan tail at ~1491s + ~555s propSec. Suppress-only (no allow-carve role).
      val brave = List(
        "collector.bsg.brave.com",
        "star-randsrv.bsg.brave.com",
      )
      assertTrue(
        brave.forall(InfraHosts.isBackground),
        brave.forall(h => !InfraHosts.isInfra(h)),
      )
    },
    test("#1672 Google Play / client-services background hosts are suppressed") {
      // Android Play Services / Google clients background polling captured on
      // profile 1 (Kids) + profile 5 (Quintus) in 2026-06-10..06-11 prod orphan
      // tail. Specific subdomains, NOT the `google.com` or `googleusercontent.com`
      // apex — keeps real-app traffic on sibling subdomains attributing and counting
      // (same seam as the existing `clientservices.googleapis.com` rather than the
      // `googleapis.com` apex). Suppress-only.
      val googleBg = List(
        "clients4.google.com",
        "android.clients.google.com",
        "clients2.googleusercontent.com",
      )
      assertTrue(
        googleBg.forall(InfraHosts.isBackground),
        googleBg.forall(h => !InfraHosts.isInfra(h)),
        // BOUNDARY: the apex must NOT be swept in. Sibling Google domains must
        // still attribute to their real apps.
        !InfraHosts.isBackground("www.google.com"),
        !InfraHosts.isBackground("mail.google.com"),
        !InfraHosts.isBackground("docs.google.com"),
        !InfraHosts.isBackground("photos.googleusercontent.com"),
        !InfraHosts.isBackground("lh3.googleusercontent.com"),
      )
    },
    test("#1672 ad-mediation / ad-quality background hosts are suppressed") {
      // OpenX / Unity / Google ad-quality mediation — these run in the background
      // of ad-supported pages. The mediation traffic itself is not user-interactive
      // engagement; the embedding page (if any) would attribute via its own app
      // template. Captured on profile 5 + profile 6 in the 2026-06-10 orphan tail.
      // Suppress-only.
      val adMediation = List(
        "oa.openxcdn.net",
        "ep2.adtrafficquality.google",
        "a-adq.mediation.unity3d.com",
      )
      assertTrue(
        adMediation.forall(InfraHosts.isBackground),
        adMediation.forall(h => !InfraHosts.isInfra(h)),
        // BOUNDARY: don't sweep the apex. unity3d.com hosts a wide surface beyond
        // the ad-mediation subdomain.
        !InfraHosts.isBackground("unity3d.com"),
        !InfraHosts.isBackground("www.unity3d.com"),
      )
    },
    test("#1672 asset CDNs used by orphan pages are suppressed") {
      // Webfont / image-thumbnail / user-content CDNs whose attribution "follows the
      // embedding page" — if the embedding page is orphan, the CDN traffic is too.
      // Specific subdomains (not apex `gstatic.com` / `googleusercontent.com`) so
      // real apps that use sibling subdomains keep attributing.
      //
      // Safety w.r.t. real apps: #1506 (`Presence.isAppAttributed`) makes app
      // attribution win over suppression — when an active app template claims
      // one of these hosts, it counts toward the app, not the suppression list.
      // These are suppress-only and never allow-carved.
      val assetCdns = List(
        "use.fontawesome.com",
        "encrypted-tbn0.gstatic.com",
        "ci3.googleusercontent.com",
      )
      assertTrue(
        assetCdns.forall(InfraHosts.isBackground),
        assetCdns.forall(h => !InfraHosts.isInfra(h)),
        // BOUNDARY: the apex must NOT be on the list. The existing
        // `connectivitycheck.gstatic.com` is the only `gstatic.com` entry — and
        // adding `ssl.gstatic.com` / the `gstatic.com` apex is deliberately deferred
        // (too broad — would absorb per-app static assets).
        !InfraHosts.isBackground("ssl.gstatic.com"),
        !InfraHosts.isBackground("fonts.gstatic.com"),
        !InfraHosts.isBackground("gstatic.com"),
        !InfraHosts.isBackground("googleusercontent.com"),
      )
    },
    test("#1672 additions do not shadow non-Apple app template host-sets") {
      // Defensive: the new hosts must not accidentally suppress real-app surfaces
      // on neighbouring apexes (Google search/mail, YouTube, etc.).
      val unrelated = List(
        "www.google.com",
        "mail.google.com",
        "docs.google.com",
        "www.youtube.com",
        "khanacademy.org",
        "www.mathacademy.com",
        "firestore.googleapis.com",
      )
      assertTrue(unrelated.forall(h => !InfraHosts.isBackground(h)))
    },
    test("#1694 Quintus-iPad phantom-engagement window: observed background hosts suppressed") {
      // Captured 2026-06-12 13:00–19:00 UTC (09:00–15:00 ET, kid-at-school) from
      // /api/usage/traffic for Quintus iPad (26:74:fc:f9:4e:9e). Top phantom-engagement
      // contributors NOT covered by the pre-#1694 list:
      //
      //   - Apple first-party widget / app backends: weather-edge, news-edge, profile.gc,
      //     aidc, guzzoni (Siri), sequoia.cdn — device-level background polling, not
      //     user-initiated.
      //   - Google background services: clients1..6.google.com + android.clients.google.com
      //     (Play check-ins / safebrowsing / update probes; appear on iPad too because the
      //     Google app and Chrome poll them).
      //   - Third-party crash/telemetry SaaS: sentry.io, bugsnag.com, 1passwordservices.com
      //     telemetry endpoint.
      //   - Plex pubsub keepalive: pubsub.plex.tv (the long-poll notification channel;
      //     NOT plex.tv apex, which carries real Plex client usage).
      //
      // Conservative additions: sibling subdomains listed explicitly, no broad apexes that
      // would shadow real-app surfaces.
      val phantomBackground = List(
        // Apple background widgets / OS services
        "weather-edge.apple.com",
        "news-edge.apple.com",
        "profile.gc.apple.com",
        "static.gc.apple.com",
        "aidc.apple.com",
        "guzzoni.apple.com",
        "sequoia.cdn-apple.com",
        // Google background services on iPad
        "android.clients.google.com",
        "clients1.google.com",
        "clients2.google.com",
        "clients3.google.com",
        "clients4.google.com",
        "clients5.google.com",
        "clients6.google.com",
        // Third-party telemetry / crash reporting SaaS
        "o4505093097586688.ingest.us.sentry.io", // subdomain match via sentry.io apex
        "sessions.bugsnag.com",                  // subdomain match via bugsnag.com apex
        // Plex pubsub keepalive (exact host only — plex.tv apex is left available for the
        // Plex client app to attribute against)
        "pubsub.plex.tv",
      )
      assertTrue(
        phantomBackground.forall(InfraHosts.isBackground),
        // Suppress-only tier: these must NOT be allow-carved through the block.
        phantomBackground.forall(h => !InfraHosts.isInfra(h)),
        // Defensive: plex.tv apex is NOT background — the Plex client app needs it to
        // attribute, and #1506 lets app attribution win over suppression even when entries
        // overlap. Verifying the absence here prevents over-fitting to this incident.
        !InfraHosts.isBackground("plex.tv"),
        !InfraHosts.isBackground("www.plex.tv"),
        // Defensive: kid-real-app apexes seen in the same window stay unsuppressed.
        !InfraHosts.isBackground("duolingo.com"),
        !InfraHosts.isBackground("ios-api-cf.duolingo.com"),
        !InfraHosts.isBackground("tinkercad.com"),
      )
    },
    test("#1694 sentry/bugsnag apex form covers SDK ingest beacons") {
      // sentry.io / bugsnag.com are dedicated telemetry / SaaS ingest apexes — these
      // ride embedded SDKs and aren't user-initiated. The apex form also covers the
      // vendors' own product UIs (sentry.io itself is Sentry's dashboard; app.bugsnag.com
      // is Bugsnag's), which is the deliberate trade-off documented at the source: a
      // kid profile won't visit a crash-reporting dashboard, and enumerating every
      // per-vendor SDK ingest host re-opens the gap each new project ID.
      assertTrue(
        InfraHosts.isBackground("sentry.io"),
        InfraHosts.isBackground("anything.ingest.us.sentry.io"),
        InfraHosts.isBackground("bugsnag.com"),
        InfraHosts.isBackground("notify.bugsnag.com"),
        // 1passwordservices.com is intentionally NOT here — the operator-authored
        // 1Password app already covers it as a member host, so it attributes to that
        // app (allowed + exempt-from-daily) under #1506 instead of being suppressed.
        !InfraHosts.isBackground("1passwordservices.com"),
        !InfraHosts.isBackground("telemetry.1passwordservices.com"),
        !InfraHosts.isBackground("1password.com"),
      )
    },
    test("#2177 cloud-background class: apex entries and the -pa.googleapis.com suffix family") {
      // The anchor-eligibility tier (see cloudBackground scaladoc): first-party-cloud
      // wakeup-burst families the isolation learner structurally cannot learn. Class-level
      // matching — one apex/suffix covers every current and future member.
      val cls = List(
        "apps.apple.com",
        "p46-buy.apps.apple.com",
        "amp-api.media.apple.com",
        "obv2-cdn.icloud-content.com",
        "cvws.icloud-content.com",
        "cstat.cdn-apple.com",
        "swdist.apple.com",
        "swcdn.apple.com",
        "wps.apple.com",
        "iadsdk.apple.com",
        "health.apple.com",
        "token.safebrowsing.apple",
        "app-measurement.com",
        "oauth2.googleapis.com",
        "oauthaccountmanager.googleapis.com",
        "securetoken.googleapis.com",
        "analytics.plex.tv",
        "excess-ga.duolingo.com",
        // the -pa (Google private API) suffix family, matched by suffix not apex
        "signaler-pa.googleapis.com",
        "people-pa.googleapis.com",
        "photosdata-pa.googleapis.com",
        "kidsmanagement-pa.googleapis.com",
        "ogads-pa.googleapis.com",
      )
      assertTrue(cls.forall(InfraHosts.isCloudBackground))
    },
    test("#2177 cloud-background BOUNDARY: engagement surfaces are NOT on the class") {
      // These carry genuine engagement and must keep anchoring spans — the class
      // deliberately excludes them (a real-use casualty is worse than residual phantom).
      val engagement = List(
        "ios-api-cf.duolingo.com",   // Duolingo's real iOS API (pinned kid-real-app above)
        "www.duolingo.com",
        "plex.tv",
        "www.plex.tv",
        "www.mathacademy.com",
        "khanacademy.org",
        "www.apple.com",             // apple.com apex is NOT swept in
        "music.apple.com",           // user-facing Apple Music web player
        "firestore.googleapis.com",  // per-app Google API — must attribute and count
        "www.googleapis.com",
        "spa.googleapis.com",        // suffix precision: matches "-pa." only, not "*pa."
        "lh3.googleusercontent.com", // user photos
        "accounts.google.com",
        "is1-ssl.mzstatic.com",      // App Store artwork CDN — anchors real store browsing
      )
      assertTrue(engagement.forall(h => !InfraHosts.isCloudBackground(h)))
    },
    test("#2177 cloud-background is a distinct tier: HostId predicate and non-FQDN behavior") {
      assertTrue(
        InfraHosts.isCloudBackground(HostId.Fqdn(Hostname.unsafe("apps.apple.com"))),
        // IP-literals / labels never match — the gate handles them via its span byte floor.
        !InfraHosts.isCloudBackground(HostId.IPv4(IpAddress.unsafe("17.253.3.141"))),
        !InfraHosts.isCloudBackground(HostId.Label("static-ip-range")),
        // every entry is a parseable lowercased apex/exact host, same hygiene as canonical
        InfraHosts.cloudBackground.forall(h => Hostname.parse(h).isRight),
        InfraHosts.cloudBackground.forall(h => h == h.toLowerCase),
        InfraHosts.cloudBackground.forall(h => !h.startsWith("*.")),
      )
    },
    test("#1629 additions do not shadow non-Apple app template host-sets") {
      // Defensive: none of the patterns added for #1629 should accidentally suppress
      // a real-app host on an unrelated apex (Khan, Math, etc. — their apexes don't
      // overlap with Apple/iCloud at all). The `ess.apple.com` co-listing with the
      // iMessage template is intentional and predates this change: #1506
      // (`Presence.isAppAttributed`) lets app attribution win over suppression at
      // runtime, so iMessage traffic keeps counting for profiles that have the
      // iMessage app configured — this PR's additions are no different in shape.
      val unrelatedAppHosts = List(
        "khanacademy.org", // Khan Academy template
        "kastatic.org",
        "kasandbox.org",
        "mathacademy.com", // Math Academy template
        "www.mathacademy.com",
      )
      assertTrue(unrelatedAppHosts.forall(h => !InfraHosts.isBackground(h)))
    },
    test("#2274 cloud-background extension: idle-Mac background sync/updater/OS hosts") {
      // Captured 2026-07-17 on the Kids profile's MacBook (ca:ef:a1:72:6a:a3) while it sat
      // lid-closed in a cabinet all day: macOS Power-Nap wakes every ~15 min emitted single-
      // sample background bursts to app-updater / telemetry / OS-config endpoints that the
      // #2091 isolation learner structurally cannot learn (they only ever fire in dense co-
      // occurring bursts, never in the ≤2-host isolated spans the learner keys on) and that
      // the prior #2177 class did not cover. Each anchored a phantom presence span → ~16 min
      // of the 31-min phantom over-count (offline replay, docs/design/idle-traffic-
      // discrimination.md §2274). Class membership removes only ANCHOR eligibility: a row
      // here still counts inside a genuinely engagement-anchored span (#1446/#2068 undercount
      // stays closed), and #1506 app-attribution still wins.
      val cls = List(
        // Serato DJ telemetry / update (insights. / id. / static. subdomains) — apex
        "serato.com",
        "insights.serato.com",
        // Brave browser component / update / usage-telemetry background hosts
        "go-updater.brave.com",
        "brave-core-ext.s3.brave.com",
        "usage-ping.brave.com",
        // Adobe Creative Cloud OOBE (onboarding / update) background feed — apex
        "oobesaas.adobe.com",
        "ffc-static-cdn.oobesaas.adobe.com",
        "prod-rel-ffc-ccm.oobesaas.adobe.com",
        // Apple OS background: A/B experiment config, analytics, tethering edge check,
        // device-configuration feed (siblings of the OS-services tail already suppress-only)
        "experiments.apple.com",
        "sylvan.apple.com",
        "tether.edge.apple",
        "device-config.pcms.apple.com",
        // Google software-update service (NOT a -pa private API; background update control)
        "update.googleapis.com",
      )
      assertTrue(cls.forall(InfraHosts.isCloudBackground))
    },
    test("#2274 additions keep the #2177 engagement boundary — no real-use casualty") {
      // The extension is deliberately scoped to unambiguous background telemetry/updater/OS
      // hosts. It must NOT shadow any engagement surface, and in particular must leave the
      // delicate mzstatic store-artwork boundary and the dual-use Google asset/auth tail
      // (handled by the learner + the follow-up isolated-span work) untouched.
      val stillEngagement = List(
        "is1-ssl.mzstatic.com", // App Store artwork CDN — still anchors real store browsing
        "apps.mzstatic.com",    // deliberately NOT added (mzstatic boundary is delicate)
        "play.googleapis.com",  // deliberately NOT added (per-app googleapis must attribute)
        "www.googleapis.com",
        "search.brave.com",     // real Brave search — the brave.com apex is NOT swept in
        "www.adobe.com",        // real Adobe surfaces — only the oobesaas feed is class
        "www.apple.com",        // apple.com apex is NOT swept in
        "docs.google.com",      // dual-use homework — handled by the learner, never blanket-classed
        "drive.google.com",
        "ssl.gstatic.com",
      )
      assertTrue(stillEngagement.forall(h => !InfraHosts.isCloudBackground(h)))
    },
  )
}
