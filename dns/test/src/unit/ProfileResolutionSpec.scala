package familydns.dns.unit

import familydns.shared.*
import zio.test.*

object ProfileResolutionSpec extends ZIOSpecDefault {

  private def mkProfile(name: String): CachedProfile = CachedProfile(
    profile = Profile(1L, name, Nil, Nil, Nil, false),
    schedules = Nil,
    timeLimit = None,
    siteTimeLimits = Nil,
  )

  private val defaultProfile = mkProfile("Adults")
  private val kidsProfile    = mkProfile("Kids")
  private val testMac        = "aa:bb:cc:dd:ee:ff"

  // Mirror of the resolution expression in DnsServer.handleQuery
  private def resolve(mac: Option[String], cache: DnsCache): Option[CachedProfile] =
    mac.flatMap(m => cache.deviceProfiles.get(m).orElse(cache.defaultProfile))

  def spec = suite("Profile resolution")(
    test("no ARP entry (mac=None) yields no profile even when a default exists") {
      val cache = DnsCache(Map(testMac -> kidsProfile), Map.empty, Some(defaultProfile))
      assertTrue(resolve(None, cache).isEmpty)
    },
    test("registered MAC resolves to the device's assigned profile") {
      val cache = DnsCache(Map(testMac -> kidsProfile), Map.empty, Some(defaultProfile))
      assertTrue(resolve(Some(testMac), cache).contains(kidsProfile))
    },
    test("unregistered MAC falls back to the default profile") {
      val cache = DnsCache(Map.empty, Map.empty, Some(defaultProfile))
      assertTrue(resolve(Some("11:22:33:44:55:66"), cache).contains(defaultProfile))
    },
    test("unregistered MAC with no default profile yields no profile") {
      val cache = DnsCache(Map.empty, Map.empty, None)
      assertTrue(resolve(Some("11:22:33:44:55:66"), cache).isEmpty)
    },
  )
}
