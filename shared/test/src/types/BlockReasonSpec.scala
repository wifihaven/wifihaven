package wifihaven.shared.types

import wifihaven.shared.{BlockReason, MacBlockReason}
import zio.json.*
import zio.test.*

import scala.compiletime.testing.typeChecks

object BlockReasonSpec extends ZIOSpecDefault {

  private val ads = BlocklistId.parse("ads").toOption.get

  def spec = suite("BlockReason / MacBlockReason")(
    suite("MacBlockReason snapshot wire format (preserved from #354)")(
      test("Paused encodes as capitalized string") {
        val r: MacBlockReason = MacBlockReason.Paused
        assertTrue(r.toJson == "\"Paused\"") &&
        assertTrue("\"Paused\"".fromJson[MacBlockReason].contains(r))
      },
      test("all variants round-trip") {
        List[MacBlockReason](
          MacBlockReason.Paused,
          MacBlockReason.Schedule,
          MacBlockReason.TimeLimit,
          MacBlockReason.Manual,
          MacBlockReason.Unmanaged,
        ).foldLeft(assertTrue(true)) { (acc, r) =>
          acc && assertTrue(r.toJson.fromJson[MacBlockReason].contains(r))
        }
      },
      test("rejects unknown reason") {
        assertTrue("\"Whatever\"".fromJson[MacBlockReason].isLeft)
      },
    ),
    suite("BlockReason kind-tagged JSON (#962)")(
      test("Allow round-trips as {kind:allow}") {
        val r: BlockReason = BlockReason.Allow
        assertTrue(r.toJson == "{\"kind\":\"allow\"}") &&
        assertTrue(r.toJson.fromJson[BlockReason].contains(r))
      },
      test("Category carries slug") {
        val r: BlockReason = BlockReason.Category(ads)
        assertTrue(r.toJson == "{\"kind\":\"category\",\"slug\":\"ads\"}") &&
        assertTrue(r.toJson.fromJson[BlockReason].contains(r))
      },
      test("SiteTimeLimit carries label") {
        val r: BlockReason = BlockReason.SiteTimeLimit("youtube")
        assertTrue(r.toJson.fromJson[BlockReason].contains(r))
      },
      test("Unknown preserves raw text") {
        val r: BlockReason = BlockReason.Unknown("weirdshape")
        assertTrue(r.toJson.fromJson[BlockReason].contains(r))
      },
      test("MacBlockReason variants encode in BlockReason form") {
        val r: BlockReason = MacBlockReason.Paused
        assertTrue(r.toJson == "{\"kind\":\"paused\"}") &&
        assertTrue(r.toJson.fromJson[BlockReason].contains(MacBlockReason.Paused))
      },
      test("decoder rejects unknown kind") {
        assertTrue("{\"kind\":\"frobnicate\"}".fromJson[BlockReason].isLeft)
      },
    ),
    suite("BlockReason.fromWire (router/PolicyService strings)")(
      test("known lowercase forms") {
        assertTrue(BlockReason.fromWire("allowed") == BlockReason.Allow) &&
        assertTrue(BlockReason.fromWire("blocked") == BlockReason.Blocked) &&
        assertTrue(BlockReason.fromWire("extra_blocked") == BlockReason.ExtraBlocked) &&
        assertTrue(BlockReason.fromWire("host") == BlockReason.ExtraBlocked) &&
        assertTrue(BlockReason.fromWire("paused") == MacBlockReason.Paused) &&
        assertTrue(BlockReason.fromWire("schedule") == MacBlockReason.Schedule) &&
        assertTrue(BlockReason.fromWire("time_limit") == MacBlockReason.TimeLimit)
      },
      test("MacBlockReason capitalized snapshot form parses") {
        assertTrue(BlockReason.fromWire("Paused") == MacBlockReason.Paused) &&
        assertTrue(BlockReason.fromWire("Schedule") == MacBlockReason.Schedule) &&
        assertTrue(BlockReason.fromWire("TimeLimit") == MacBlockReason.TimeLimit) &&
        assertTrue(BlockReason.fromWire("Manual") == MacBlockReason.Manual)
      },
      test("category:<slug>") {
        assertTrue(BlockReason.fromWire("category:ads") == BlockReason.Category(ads))
      },
      test("site_time_limit:<label>") {
        assertTrue(
          BlockReason.fromWire("site_time_limit:youtube") == BlockReason.SiteTimeLimit("youtube"),
        )
      },
      test("unknown form falls back to Unknown(raw)") {
        assertTrue(BlockReason.fromWire("weird") == BlockReason.Unknown("weird"))
      },
      test("category with invalid slug falls back to Unknown") {
        assertTrue(
          BlockReason.fromWire("category:UPPER!!!") == BlockReason.Unknown("category:UPPER!!!"),
        )
      },
    ),
    suite("Type-system subtype constraint")(
      test("MacBlockReason is assignable to BlockReason") {
        assertTrue(typeChecks("""
          val r: wifihaven.shared.BlockReason =
            wifihaven.shared.MacBlockReason.Paused
        """))
      },
      test("BlockReason.Allow does NOT typecheck as MacBlockReason") {
        assertTrue(!typeChecks("""
          val r: wifihaven.shared.MacBlockReason =
            wifihaven.shared.BlockReason.Allow
        """))
      },
    ),
  )
}
