package wifihaven.shared.types

import zio.test.*
import zio.test.Assertion.*

/**
 * Pins the unit-separator byte used by the SQL `chr(N) ||` concat in `ConnectionEventRepo` and the
 * Scala-side `aggGroupKey` builders in `Routes` and `UsageTraffic`. Audit umbrella #1532.
 *
 * If this constant ever changes, BOTH sides must change in lockstep — the SQL fragment is
 * `Fragment.const(s"chr(${LogAggGroupKey.SeparatorCode})")`, and the Scala builders join with
 * `LogAggGroupKey.Separator`. A drift between SQL and Scala silently corrupts the #862 keyset
 * cursor at page boundaries.
 */
object LogAggGroupKeySpec extends ZIOSpecDefault {

  def spec = suite("LogAggGroupKey")(
    test("SeparatorCode is the ASCII unit separator (31, 0x1F)") {
      assertTrue(LogAggGroupKey.SeparatorCode == 31)
    },
    test("Separator is a single-character string equal to U+001F") {
      assertTrue(LogAggGroupKey.Separator == "") &&
      assertTrue(LogAggGroupKey.Separator.length == 1) &&
      assertTrue(LogAggGroupKey.Separator.head.toInt == LogAggGroupKey.SeparatorCode)
    },
    test("Separator does not collide with hostname, MAC, or profile-name characters") {
      val sep = LogAggGroupKey.Separator
      assertTrue(!"abc.example.com".contains(sep)) &&
      assertTrue(!"aa:bb:cc:11:22:33".contains(sep)) &&
      assertTrue(!"Kids".contains(sep))
    },
  )
}
