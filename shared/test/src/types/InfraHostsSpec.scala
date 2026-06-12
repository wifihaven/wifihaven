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
  )
}
