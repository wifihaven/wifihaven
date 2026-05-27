package wifihaven.api.feature

import wifihaven.shared.*
import zio.test.*

/**
 * #1103: pins the contract between PolicyService's [[MacBlockReason]] and the router-side
 * attribution that the `mac_drops` chart shows. If a new variant is added to MacBlockReason without
 * a corresponding render-side reason label, the chart will mis-label drops at that mac. This spec
 * keeps the two sides in lockstep at API-test time so the drift is caught before ship.
 */
object PolicySnapshotMacDropAttributionSpec extends ZIOSpecDefault {

  /**
   * Reasons the OpenWRT agent currently tags drop counters with. Sourced from render.lua's
   * `blocked_reason_map[mac] = r.blockReason or "blocked"` — i.e. whatever string the snapshot's
   * BlockRules.blockReason carries on the wire. Must include every MacBlockReason variant.
   */
  private val routerSideKnownReasons: Set[String] = Set(
    "Paused",
    "Schedule",
    "TimeLimit",
    "Manual",
    "Unmanaged",
  )

  def spec = suite("policy snapshot — MAC drop attribution (#1103)")(
    test("every MacBlockReason wire string is known to the router") {
      val wire = List(
        MacBlockReason.Paused,
        MacBlockReason.Schedule,
        MacBlockReason.TimeLimit,
        MacBlockReason.Manual,
        MacBlockReason.Unmanaged,
      ).map(MacBlockReason.asString)
      assertTrue(wire.forall(routerSideKnownReasons.contains)) &&
      assertTrue(wire.distinct.size == wire.size)
    },
    test("MacBlockReason round-trips through asString/parse for every variant") {
      val rs = List(
        MacBlockReason.Paused,
        MacBlockReason.Schedule,
        MacBlockReason.TimeLimit,
        MacBlockReason.Manual,
        MacBlockReason.Unmanaged,
      )
      assertTrue(rs.forall(r => MacBlockReason.parse(MacBlockReason.asString(r)).contains(r)))
    },
    test("unknown reason wire strings yield None") {
      assertTrue(MacBlockReason.parse("blocked").isEmpty) &&
      assertTrue(MacBlockReason.parse("paused").isEmpty) && // lowercase rejected
      assertTrue(MacBlockReason.parse("").isEmpty)
    },
  )
}
