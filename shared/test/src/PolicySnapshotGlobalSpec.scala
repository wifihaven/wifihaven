package wifihaven.shared

import wifihaven.shared.types.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/**
 * #1316 / #1308: the global-policy foundation. `PolicySnapshot` gains a single `global: BlockRules`
 * field carrying fleet-wide policy once, reusing the existing `BlockRules` shape (no new struct —
 * see docs/design/global-policy-layer.md §3.1). These tests pin the wire shape and codec behaviour
 * the follow-ups (#1318 server, #1319 router, #1320 web) build on.
 */
object PolicySnapshotGlobalSpec extends ZIOSpecDefault {

  private val mac     = MacAddress.parse("aa:bb:cc:11:22:33").toOption.get
  private val pid     = ProfileId(3L)
  private val ads     = BlocklistId.parse("ads").toOption.get
  private val conn    = Hostname.parse("connectivitycheck.gstatic.com").toOption.get
  private val uiHost  = Hostname.parse("wifihaven.local").toOption.get
  private val blocked = Hostname.parse("evil.example.com").toOption.get

  private def snapshot(global: BlockRules): PolicySnapshot =
    PolicySnapshot(
      etag = ETag.unsafe("\"sha256:deadbeefcafe0001\""),
      generatedAt = "2026-06-04T00:00:00Z",
      global = global,
      devices = Map(
        mac -> DevicePolicy(profileId = Some(pid), name = "kid-ipad", rules = None),
      ),
      profiles = Map(
        pid -> ProfilePolicy(
          name = "kids",
          rules = BlockRules.allowAll.copy(blocklistIds = List(ads)),
          failureMode = FailureMode.BlockAll,
        ),
      ),
      blocklists = Map(
        ads -> Blocklist(
          version = BlocklistVersion.unsafe("2026.06.01"),
          url = BlocklistUrl.unsafe("/api/blocklists/ads"),
        ),
      ),
    )

  def spec = suite("PolicySnapshot.global (#1316)")(
    test("snapshot with a populated global round-trips through the codec") {
      val global = BlockRules.allowAll.copy(extraAllowed = List(conn, uiHost))
      val snap   = snapshot(global)
      assertTrue(snap.toJson.fromJson[PolicySnapshot].contains(snap))
    },
    test("the global section reuses the BlockRules shape (every field on the wire)") {
      val global = BlockRules(
        blocked = true,
        blockReason = Some(MacBlockReason.Manual),
        extraBlocked = List(blocked),
        extraAllowed = List(conn, uiHost),
        blocklistIds = List(ads),
        blockIpOnly = true,
      )
      val obj    = snapshot(global).toJson.fromJson[Json].toOption.get.asInstanceOf[Json.Obj]
      val g      = obj.fields.toMap.apply("global").asInstanceOf[Json.Obj].fields.map(_._1).toSet
      assertTrue(
        g == Set(
          "blocked",
          "blockReason",
          "extraBlocked",
          "extraAllowed",
          "blocklistIds",
          "blockIpOnly",
        ),
      )
    },
    test("global decodes as a full BlockRules value, not a flattened/renamed shape") {
      val global  = BlockRules.allowAll.copy(extraAllowed = List(conn), blockIpOnly = true)
      val decoded = snapshot(global).toJson.fromJson[PolicySnapshot]
      assertTrue(decoded.map(_.global).contains(global))
    },
    // The field defaults to allowAll so an older snapshot JSON that predates
    // `global` still decodes (additive wire change, per design §9). The router
    // sees an inert global and enforces exactly as before.
    test("a snapshot JSON omitting `global` decodes to the inert allowAll default") {
      val full    = snapshot(BlockRules.allowAll)
      val obj     = full.toJson.fromJson[Json].toOption.get.asInstanceOf[Json.Obj]
      val without = Json.Obj(obj.fields.filterNot(_._1 == "global")).toJson
      val decoded = without.fromJson[PolicySnapshot]
      assertTrue(decoded.map(_.global).contains(BlockRules.allowAll))
    },
    test("constructing a snapshot without specifying global yields allowAll") {
      val snap = PolicySnapshot(
        etag = ETag.unsafe("\"sha256:0000000000000000\""),
        generatedAt = "2026-06-04T00:00:00Z",
        devices = Map.empty,
        profiles = Map.empty,
        blocklists = Map.empty,
      )
      assertTrue(snap.global == BlockRules.allowAll)
    },
    // global is independent of any device/profile: a global block carved out by
    // global.extraAllowed must serialize alongside per-MAC rules untouched.
    test("global composes on the wire beside per-MAC rules without interfering") {
      val global = BlockRules.allowAll.copy(
        blocked = true,
        blockReason = Some(MacBlockReason.Manual),
        extraAllowed = List(uiHost),
      )
      val snap   = snapshot(global)
      val rt     = snap.toJson.fromJson[PolicySnapshot]
      assertTrue(rt.map(_.global).contains(global)) &&
      assertTrue(rt.map(_.profiles).contains(snap.profiles)) &&
      assertTrue(rt.map(_.devices).contains(snap.devices))
    },
  )
}
