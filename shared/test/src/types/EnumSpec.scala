package familydns.shared.types

import familydns.shared.{FailureMode, UserRole}
import zio.json.*
import zio.test.*

object EnumSpec extends ZIOSpecDefault {

  def spec = suite("Simple enums")(
    suite("UserRole (extended with JsonCodec)")(
      test("encodes lowercased") {
        assertTrue((UserRole.Admin: UserRole).toJson == "\"admin\"") &&
        assertTrue((UserRole.Adult: UserRole).toJson == "\"adult\"") &&
        assertTrue((UserRole.Child: UserRole).toJson == "\"child\"")
      },
      test("decodes case-insensitively") {
        assertTrue("\"Admin\"".fromJson[UserRole].contains(UserRole.Admin)) &&
        assertTrue("\"ADULT\"".fromJson[UserRole].contains(UserRole.Adult))
      },
      test("rejects unknown role") {
        assertTrue("\"emperor\"".fromJson[UserRole].isLeft) &&
        assertTrue("\"\"".fromJson[UserRole].isLeft)
      },
    ),
    suite("ConnectionDecision")(
      test("round-trip") {
        val a: ConnectionDecision = ConnectionDecision.Allow
        val b: ConnectionDecision = ConnectionDecision.Block
        assertTrue(a.toJson == "\"allow\"") &&
        assertTrue(b.toJson == "\"block\"") &&
        assertTrue("\"allow\"".fromJson[ConnectionDecision].contains(a)) &&
        assertTrue("\"block\"".fromJson[ConnectionDecision].contains(b))
      },
      test("rejects unknown decision") {
        assertTrue("\"maybe\"".fromJson[ConnectionDecision].isLeft)
      },
    ),
    suite("FailureMode (existing — wire format preserved)")(
      test("wire format unchanged") {
        assertTrue((FailureMode.Open: FailureMode).toJson == "\"open\"") &&
        assertTrue((FailureMode.Closed: FailureMode).toJson == "\"closed\"") &&
        assertTrue("\"open\"".fromJson[FailureMode].contains(FailureMode.Open))
      },
    ),
  )
}
