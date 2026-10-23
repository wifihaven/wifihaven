package wifihaven.api.identify

import wifihaven.shared.DeviceVendor
import wifihaven.shared.types.MacAddress
import zio.*

import scala.io.Source

/**
 * #2926: MAC → vendor, from the bundled IEEE MA-L (24-bit OUI) registry at
 * `api/resources/oui/ma-l.tsv`.
 *
 * Repo-authored data, never a runtime third-party call — the same stance as app templates and
 * blocklists (#2535). `scripts/refresh-oui.sh` regenerates the file from IEEE's public CSV; the
 * diff is the review surface. MA-M / MA-S (28/36-bit) blocks are not bundled: their owners are
 * mostly industrial and embedded vendors, and a consumer device in one reads as "unknown".
 *
 * The first octet decides before the table does:
 *   - bit 1 set (locally administered) → "private". iOS / Android / Windows randomized Wi-Fi
 *     addresses all set it, and whatever vendor the table lists for that prefix is coincidence.
 *   - bit 0 set (group address) → "unknown". A real device never sources from one.
 */
final class MacVendors private (byPrefix: Map[String, String]) {

  def size: Int = byPrefix.size

  def lookup(mac: MacAddress): DeviceVendor = {
    val hex = mac.value.filter(c => c.isDigit || ('a' to 'f').contains(c.toLower)).toUpperCase
    if (hex.length < 6) DeviceVendor("unknown", None)
    else {
      val first = Integer.parseInt(hex.take(2), 16)
      if ((first & 0x02) != 0) DeviceVendor("private", None)
      else if ((first & 0x01) != 0) DeviceVendor("unknown", None)
      else
        byPrefix.get(hex.take(6)) match {
          case Some(name) => DeviceVendor("vendor", Some(name))
          case None       => DeviceVendor("unknown", None)
        }
    }
  }
}

object MacVendors {

  val ResourcePath = "oui/ma-l.tsv"

  /** Parse `PREFIX<TAB>Organization` lines; `#` comments and blank lines are skipped. */
  def fromLines(lines: Iterator[String]): MacVendors =
    new MacVendors(
      lines
        .filter(l => l.nonEmpty && !l.startsWith("#"))
        .flatMap { l =>
          l.split('\t') match {
            case Array(prefix, name) if prefix.length == 6 => Some(prefix.toUpperCase -> name)
            case _                                         => None
          }
        }
        .toMap,
    )

  /**
   * Load the bundled table. Fails loud (AGENTS.md#no-dark-by-default) when the resource is missing
   * or implausibly small — a build that dropped it must not boot into a view that labels every
   * device "unknown".
   */
  val load: Task[MacVendors] =
    ZIO
      .scoped {
        ZIO
          .fromAutoCloseable(
            ZIO
              .attempt(Option(getClass.getClassLoader.getResourceAsStream(ResourcePath)))
              .someOrFail(new IllegalStateException(s"bundled OUI table missing: $ResourcePath"))
              .map(Source.fromInputStream(_, "UTF-8")),
          )
          .flatMap(src => ZIO.attempt(fromLines(src.getLines())))
      }
      .filterOrElseWith(_.size >= 10000)(t =>
        ZIO.fail(
          new IllegalStateException(s"bundled OUI table $ResourcePath has only ${t.size} prefixes"),
        ),
      )
}
