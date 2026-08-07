package wifihaven.api.unit

import wifihaven.shared.types.*
import wifihaven.testinfra.SharedGfeHosts
import zio.test.*

/**
 * #2601: the matcher two catalog guards depend on. Both `BundledBlocklistsSpec` and
 * `AppTemplatesSpec` assert "no offenders", which passes vacuously if `isBanned` is wrong — a guard
 * that never fires looks exactly like a clean catalog. These pin the matcher itself.
 */
object SharedGfeHostsSpec extends ZIOSpec[Any] {

  override val bootstrap = zio.ZLayer.empty

  private def banned(s: String) = SharedGfeHosts.isBanned(Hostname.unsafe(s))

  def spec = suite("SharedGfeHosts.isBanned")(
    test("matches the bare apex") {
      assertTrue(banned("doubleclick.net")) && assertTrue(banned("gvt2.com"))
    },
    test("matches subdomains — the shape every observed prod drop actually had") {
      // static.doubleclick.net / pagead2.googlesyndication.com / www.googletagmanager.com were
      // the names in the block events; an exact-match guard would have missed all three.
      assertTrue(banned("static.doubleclick.net")) &&
      assertTrue(banned("pagead2.googlesyndication.com")) &&
      assertTrue(banned("www.googletagmanager.com")) &&
      assertTrue(banned("r3---sn-abc.gvt2.com"))
    },
    test("does NOT match a host that merely ends with the apex text") {
      // The suffix check is anchored on a dot, so an unrelated registration that happens to end
      // in the same characters is not swept up.
      assertTrue(!banned("notdoubleclick.net")) &&
      assertTrue(!banned("mydoubleclick.net")) &&
      assertTrue(!banned("doubleclick.net.evil.example"))
    },
    test("is case-folded, because Hostname.unsafe does not normalize") {
      // Only Hostname.parse lowercases. The inline BundledBlocklists fixtures use `unsafe`, so a
      // mixed-case literal there would otherwise walk past the guard.
      assertTrue(banned("DoubleClick.net")) && assertTrue(banned("Static.DOUBLECLICK.NET"))
    },
    test("leaves unrelated ad apexes alone, so the guard cannot swallow the whole ads list") {
      assertTrue(!banned("adnxs.com")) &&
      assertTrue(!banned("criteo.com")) &&
      assertTrue(!banned("google.com")) &&
      assertTrue(!banned("drive.google.com"))
    },
    test("excludes the two load-bearing pool members #2605 tracks") {
      // play.google.com and ai.google.dev are on the pool but are deliberately NOT banned; a test
      // must not force that product decision. If someone adds them, this fails loudly.
      assertTrue(!banned("play.google.com")) && assertTrue(!banned("ai.google.dev"))
    },
  )
}
