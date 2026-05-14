package familydns.shared.types

import zio.json.*
import zio.test.*

import scala.compiletime.testing.typeChecks

object IdSpec extends ZIOSpecDefault {

  def spec = suite("Id types")(
    test("Long-backed ids round-trip as raw JSON numbers (wire-compat)") {
      val p = ProfileId(42L)
      assertTrue(p.toJson == "42") &&
      assertTrue("42".fromJson[ProfileId].contains(p)) &&
      assertTrue(p.value == 42L)
    },
    test("ProfileId and DeviceId are distinct at the type level") {
      // Same underlying Long but opaque types must not unify.
      assertTrue(!typeChecks("""
        val p: familydns.shared.types.ProfileId =
          familydns.shared.types.DeviceId(1L)
      """))
    },
    test("ProfileId and BlocklistId are distinct at the type level") {
      assertTrue(!typeChecks("""
        val b: familydns.shared.types.BlocklistId =
          familydns.shared.types.ProfileId(1L)
      """))
    },
    test("RouterId round-trips as UUID JSON string") {
      val u = java.util.UUID.fromString("11111111-2222-3333-4444-555555555555")
      val r = RouterId(u)
      assertTrue(r.toJson == "\"11111111-2222-3333-4444-555555555555\"") &&
      assertTrue(r.toJson.fromJson[RouterId].contains(r))
    },
    test("RouterId rejects malformed UUID JSON") {
      assertTrue("\"not-a-uuid\"".fromJson[RouterId].isLeft)
    },
    test("BlocklistId is a slug-typed opaque String") {
      val b = BlocklistId.parse("ads").toOption.get
      assertTrue(b.value == "ads") &&
      assertTrue(b.toJson == "\"ads\"") &&
      assertTrue(BlocklistId.parse("").isLeft) &&
      assertTrue(BlocklistId.parse("Bad Slug").isLeft)
    },
    test("ProfileId and ScheduleId are distinct at the type level") {
      assertTrue(!typeChecks("""
        val s: familydns.shared.types.ScheduleId =
          familydns.shared.types.ProfileId(1L)
      """))
    },
  )
}
