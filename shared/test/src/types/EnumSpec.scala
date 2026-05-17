package wifihaven.shared.types

import wifihaven.shared.{FailureMode, UserRole}
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
    suite("FailureMode (#385 — three modes, lower-kebab wire)")(
      test("encodes as lower-kebab") {
        assertTrue((FailureMode.BlockAll: FailureMode).toJson == "\"block-all\"") &&
        assertTrue((FailureMode.AllowAll: FailureMode).toJson == "\"allow-all\"") &&
        assertTrue(
          (FailureMode.LastKnownGood: FailureMode).toJson == "\"last-known-good\"",
        )
      },
      test("decodes lower-kebab and is case-insensitive") {
        assertTrue("\"block-all\"".fromJson[FailureMode].contains(FailureMode.BlockAll)) &&
        assertTrue("\"Allow-All\"".fromJson[FailureMode].contains(FailureMode.AllowAll)) &&
        assertTrue(
          "\"LAST-KNOWN-GOOD\"".fromJson[FailureMode].contains(FailureMode.LastKnownGood),
        )
      },
      test("rejects the legacy binary wire forms (#385 — no silent collapse)") {
        // Old "open" / "closed" must NOT decode — a snapshot served from a
        // pre-V12 build is a configuration mismatch, not silent data loss.
        assertTrue("\"open\"".fromJson[FailureMode].isLeft) &&
        assertTrue("\"closed\"".fromJson[FailureMode].isLeft) &&
        assertTrue("\"unknown\"".fromJson[FailureMode].isLeft)
      },
    ),
  )
}
