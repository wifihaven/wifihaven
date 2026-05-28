package wifihaven.shared.types

import wifihaven.shared.{BlockReason, MacBlockReason}
import zio.json.*
import zio.test.*

import scala.compiletime.testing.typeChecks

object BlockReasonSpec extends ZIOSpecDefault {

  private val host = Hostname.parse("ads.example.com").toOption.get
  private val ip   = IpAddress.parse("203.0.113.5").toOption.get
  private val list = BlocklistId.parse("ads").toOption.get

  def spec = suite("BlockReason / MacBlockReason")(
    suite("MacBlockReason wire format (preserved from #354)")(
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
    suite("BlockReason typed variants (post-#114)")(
      test("Host(Hostname) carries a typed hostname") {
        val r: BlockReason = BlockReason.Host(host)
        r match {
          case BlockReason.Host(h) => assertTrue(h.value == "ads.example.com")
          case _                   => assertTrue(false)
        }
      },
      test("Category(Hostname, BlocklistId) carries typed parameters") {
        val r: BlockReason = BlockReason.Category(host, list)
        r match {
          case BlockReason.Category(h, l) =>
            assertTrue(h.value == "ads.example.com") &&
            assertTrue(l.value == "ads")
          case _                          => assertTrue(false)
        }
      },
      test("IpOnly(IpAddress) carries a typed ip") {
        val r: BlockReason = BlockReason.IpOnly(ip)
        r match {
          case BlockReason.IpOnly(i) => assertTrue(i.value == "203.0.113.5")
          case _                     => assertTrue(false)
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
      test("BlockReason.Host does NOT typecheck as MacBlockReason") {
        assertTrue(!typeChecks("""
          val r: wifihaven.shared.MacBlockReason =
            wifihaven.shared.BlockReason.Host(
              wifihaven.shared.types.Hostname.parse("a.com").toOption.get
            )
        """))
      },
      test("BlockReason.IpOnly does NOT typecheck as MacBlockReason") {
        assertTrue(!typeChecks("""
          val r: wifihaven.shared.MacBlockReason =
            wifihaven.shared.BlockReason.IpOnly(
              wifihaven.shared.types.IpAddress.parse("1.2.3.4").toOption.get
            )
        """))
      },
    ),
  )
}
