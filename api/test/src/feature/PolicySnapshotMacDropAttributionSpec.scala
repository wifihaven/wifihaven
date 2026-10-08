package wifihaven.api.feature

import wifihaven.shared.*
import zio.test.*

/**
 * #1122: pin the wire-string contract between PolicyService's `MacBlockReason` cases and the
 * strings the OpenWRT agent emits in the `comment "wh_drop:<mac>:<reason>"` clause of each per-MAC
 * nftables drop rule (render.lua).
 *
 * If `MacBlockReason.asString` and the strings render.lua hard-codes ever diverge, the agent's
 * nflog tail will produce events whose `reason` field is not recognised on the Logs page, or worse,
 * will mis-classify a Paused drop as a Schedule drop. This spec is the single round-trip pin
 * between the two so any rename in `Models.scala` requires a paired update in render.lua.
 *
 * The corresponding Lua-side assertions live in `openwrt/test/render_spec.lua` under the "drop
 * rules carry log group + counter + comment" describe block.
 */
object PolicySnapshotMacDropAttributionSpec extends ZIOSpecDefault {

  // The exact set of MacBlockReason values render.lua may emit in a wh_drop:
  // comment. Order intentional — matches MacBlockReason.asString's match order
  // so a renamed/added case here also fails any subset assertion below.
  private val expected: List[(MacBlockReason, String)] = List(
    MacBlockReason.Paused      -> "Paused",
    MacBlockReason.Schedule    -> "Schedule",
    MacBlockReason.TimeLimit   -> "TimeLimit",
    MacBlockReason.Manual      -> "Manual",
    MacBlockReason.Unmanaged   -> "Unmanaged",
    MacBlockReason.DefaultDeny -> "DefaultDeny",
    // #2847: the agent renders the reason opaquely; render_spec / nft_drops_spec pin that.
    MacBlockReason.CheckedOut  -> "CheckedOut",
  )

  // Compiler-enforced: a non-exhaustive match fails the build (as in BlockReasonSpec's
  // `shapeCheck`), so a new MacBlockReason case cannot compile until it is added here, which is
  // the signal to add it to `expected` and to a render.lua / nft_drops pin.
  private def pinned(r: MacBlockReason): String = r match {
    case MacBlockReason.Paused      => "Paused"
    case MacBlockReason.Schedule    => "Schedule"
    case MacBlockReason.TimeLimit   => "TimeLimit"
    case MacBlockReason.Manual      => "Manual"
    case MacBlockReason.Unmanaged   => "Unmanaged"
    case MacBlockReason.DefaultDeny => "DefaultDeny"
    case MacBlockReason.CheckedOut  => "CheckedOut"
  }

  def spec = suite("PolicySnapshotMacDropAttribution: MacBlockReason ↔ render.lua comment strings")(
    test("MacBlockReason.asString covers every enum case with the agent-expected wire string") {
      val checks = expected.map { case (reason, wire) =>
        assertTrue(MacBlockReason.asString(reason) == wire) &&
        assertTrue(MacBlockReason.parse(wire).contains(reason))
      }
      checks.reduce(_ && _)
    },
    test("parse rejects an unknown wire string so a typo in render.lua surfaces immediately") {
      assertTrue(MacBlockReason.parse("Manuel").isEmpty) &&
      assertTrue(MacBlockReason.parse("paused").isEmpty) &&
      assertTrue(MacBlockReason.parse("").isEmpty)
    },
    test("expected covers every case of the exhaustive `pinned` match, once each") {
      assertTrue(expected.map(_._1).distinct.length == expected.length) &&
      assertTrue(expected.length == 7) &&
      assertTrue(expected.forall { case (r, wire) => pinned(r) == wire })
    },
  )
}
