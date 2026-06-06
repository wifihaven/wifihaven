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
  )
}
