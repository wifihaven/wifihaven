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
          MacBlockReason.DefaultDeny,
        ).foldLeft(assertTrue(true)) { (acc, r) =>
          acc && assertTrue(r.toJson.fromJson[MacBlockReason].contains(r))
        }
      },
      // #1316 / #1308: DefaultDeny is the new per-profile default-deny baseline
      // reason. It must serialize distinctly from every existing case.
      test("DefaultDeny encodes as its own capitalized string") {
        val r: MacBlockReason = MacBlockReason.DefaultDeny
        assertTrue(r.toJson == "\"DefaultDeny\"") &&
        assertTrue("\"DefaultDeny\"".fromJson[MacBlockReason].contains(r))
      },
      test("DefaultDeny is distinct from every other MacBlockReason wire form") {
        val others = List[MacBlockReason](
          MacBlockReason.Paused,
          MacBlockReason.Schedule,
          MacBlockReason.TimeLimit,
          MacBlockReason.Manual,
          MacBlockReason.Unmanaged,
        )
        val dd     = (MacBlockReason.DefaultDeny: MacBlockReason).toJson
        assertTrue(others.forall(o => o.toJson != dd))
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
      test("AppTimeLimit carries label") {
        val r: BlockReason = BlockReason.AppTimeLimit("youtube")
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
      test("DefaultDeny encodes in kind-tagged BlockReason form") {
        val r: BlockReason = MacBlockReason.DefaultDeny
        assertTrue(r.toJson == "{\"kind\":\"defaultDeny\"}") &&
        assertTrue(r.toJson.fromJson[BlockReason].contains(MacBlockReason.DefaultDeny))
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
      test("DefaultDeny parses from both wire forms and round-trips asWire") {
        assertTrue(BlockReason.fromWire("default_deny") == MacBlockReason.DefaultDeny) &&
        assertTrue(BlockReason.fromWire("DefaultDeny") == MacBlockReason.DefaultDeny) &&
        assertTrue(BlockReason.asWire(MacBlockReason.DefaultDeny) == "default_deny") &&
        assertTrue(
          BlockReason.fromWire(BlockReason.asWire(MacBlockReason.DefaultDeny)) ==
            MacBlockReason.DefaultDeny,
        )
      },
      test("category:<slug>") {
        assertTrue(BlockReason.fromWire("category:ads") == BlockReason.Category(ads))
      },
      test("app_time_limit:<label> round-trips through asWire/fromWire (#1518)") {
        assertTrue(
          BlockReason.fromWire("app_time_limit:youtube") == BlockReason.AppTimeLimit("youtube"),
        ) &&
        assertTrue(
          BlockReason.asWire(BlockReason.AppTimeLimit("youtube")) == "app_time_limit:youtube",
        )
      },
      test("legacy site_time_limit: text falls back to Unknown(raw) (#1518)") {
        // #1518: routers echo back what they receive, so once the API rolls out
        // emitting `app_time_limit:` no live caller still posts the old prefix;
        // an old text string that does reach fromWire (e.g. a stale ad-hoc tool)
        // is preserved as Unknown(raw) rather than silently re-classified.
        assertTrue(
          BlockReason.fromWire("site_time_limit:youtube") ==
            BlockReason.Unknown("site_time_limit:youtube"),
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
      // #1545: pin that every reason `PolicyService.decide` can emit survives a
      // `fromWire(asWire(r))` round-trip, so routing the emitter and the block-page
      // re-parser through the typed `BlockReason` cannot silently drop a case.
      test("every reason decide() can emit round-trips through asWire/fromWire") {
        val emitted: List[BlockReason] = List(
          BlockReason.NoProfile,
          BlockReason.ExtraAllowed,
          BlockReason.Allow,
          MacBlockReason.Paused,
          MacBlockReason.Schedule,
          BlockReason.ExtraBlocked,
          MacBlockReason.TimeLimit,
          BlockReason.Category(ads),
          BlockReason.AppTimeLimit("youtube"),
        )
        emitted.foldLeft(assertTrue(true)) { (acc, r) =>
          acc && assertTrue(BlockReason.fromWire(BlockReason.asWire(r)) == r)
        }
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
