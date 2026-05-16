package wifihaven.shared.types

import zio.json.*
import zio.test.*

/**
 * #354's PolicySnapshot uses Map[MacAddress, _], Map[ProfileId, _], Map[BlocklistId, _]. JSON
 * object keys must be strings — so the opaque types must provide JsonFieldEncoder/JsonFieldDecoder.
 * Verify the wire shape is unchanged from the bare String/Long versions.
 */
object MapKeySpec extends ZIOSpecDefault {

  def spec = suite("Opaque types as JSON Map keys")(
    test("Map[MacAddress, Int] keys are JSON strings") {
      val mac = MacAddress.parse("aa:bb:cc:11:22:33").toOption.get
      val m   = Map(mac -> 1)
      val j   = m.toJson
      assertTrue(j == """{"aa:bb:cc:11:22:33":1}""") &&
      assertTrue(j.fromJson[Map[MacAddress, Int]].contains(m))
    },
    test("Map[ProfileId, Int] keys are JSON strings (Long → String, wire-compat)") {
      val m = Map(ProfileId(42L) -> 1, ProfileId(7L) -> 2)
      val j = m.toJson
      // Wire shape matches bare Map[Long, Int].
      assertTrue(j.fromJson[Map[ProfileId, Int]].contains(m)) &&
      assertTrue(j.contains("\"42\"") && j.contains("\"7\""))
    },
    test("Map[BlocklistId, Int] keys round-trip") {
      val a = BlocklistId.parse("ads").toOption.get
      val s = BlocklistId.parse("social").toOption.get
      val m = Map(a -> 1, s -> 2)
      val j = m.toJson
      assertTrue(j.fromJson[Map[BlocklistId, Int]].contains(m))
    },
    test("Map decode of malformed key returns Left") {
      assertTrue(
        """{"not-a-mac":1}""".fromJson[Map[MacAddress, Int]].isLeft,
      )
    },
  )
}
