package wifihaven.api.unit

import wifihaven.api.identify.MacVendors
import wifihaven.shared.types.MacAddress
import zio.test.*

/**
 * #2926: MAC → vendor from the bundled IEEE MA-L table. The edge cases are the bits of the first
 * octet: a locally-administered (randomized / private Wi-Fi) address carries no meaningful OUI, so
 * it must read as "private" rather than whatever vendor happens to own that prefix.
 */
object MacVendorsSpec extends ZIOSpecDefault {

  private val table = MacVendors.fromLines(
    Iterator(
      "# comment line",
      "841B5E\tNETGEAR",
      "6A2003\tShould Never Be Returned",
      "",
    ),
  )

  private def mac(s: String) = MacAddress.unsafe(s)

  def spec = suite("MacVendors")(
    test("a universally-administered MAC resolves its OUI") {
      val v = table.lookup(mac("84:1b:5e:11:22:33"))
      assertTrue(v.kind == "vendor", v.name.contains("NETGEAR"))
    },
    test("a locally-administered MAC is private even when the prefix is in the table") {
      // 0x6a = 0110_1010: bit 1 (locally administered) set.
      val v = table.lookup(mac("6a:20:03:aa:bb:01"))
      assertTrue(v.kind == "private", v.name.isEmpty)
    },
    test("an unknown universal prefix is unknown, not private") {
      val v = table.lookup(mac("00:00:5e:00:53:01"))
      assertTrue(v.kind == "unknown", v.name.isEmpty)
    },
    test("a group (multicast-bit) address is unknown") {
      // 0x01 = group bit set, local bit clear.
      val v = table.lookup(mac("01:00:5e:00:00:01"))
      assertTrue(v.kind == "unknown")
    },
    test("the bundled resource loads and covers a well-known prefix") {
      for {
        t <- MacVendors.load
      } yield assertTrue(
        t.size > 10000,
        t.lookup(mac("b8:27:eb:00:00:01")).name.contains("Raspberry Pi Foundation"),
      )
    },
  )
}
