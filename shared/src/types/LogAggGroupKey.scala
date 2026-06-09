package wifihaven.shared.types

/**
 * Single source of truth for the lexicographic group-key delimiter used by the #862
 * connection-event aggregation cursor and the #846 traffic-aggregation cursor.
 *
 * The keyset cursor sorts on `(window_start DESC, group_key ASC)`, where `group_key` is the
 * concatenation of the requested group values (`domain`, `device`, `profile`) joined by an ASCII
 * unit separator (`U+001F`, code 31) — chosen because it cannot appear in hostnames, MAC strings,
 * or profile names. The SQL `group_key` expression and the Scala-side per-row builders MUST agree
 * on the delimiter byte; if they drift, the cursor's `(prevWs, prevKey) < (ws, key)` compare on the
 * SQL side and the `aggGroupKey(row)` value on the Scala side will use different separators and
 * pagination will silently skip or repeat rows at page boundaries.
 *
 * Before this object the byte was hard-coded as `chr(31)` in the SQL (`ConnectionEventRepo`), a
 * literal one-character `` string in two Scala builders (`Routes.aggGroupKey`,
 * `UsageTraffic.aggGroupKey`), and tracked only by "must mirror" / "Same separator as the SQL"
 * comments. Audit umbrella #1532.
 */
object LogAggGroupKey {

  /** The ASCII unit-separator code point. Stable wire/protocol constant — do not change. */
  val SeparatorCode: Int = 31

  /**
   * The unit separator as a single-character string. Equivalent to `SeparatorCode.toChar.toString`.
   */
  val Separator: String = SeparatorCode.toChar.toString
}
