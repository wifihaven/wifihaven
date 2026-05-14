package familydns.api.sessions

import familydns.shared.Session
import familydns.shared.types.*

import java.time.{Instant, LocalDate}

/**
 * Raw input to the stitching algorithm — one row per 5-min traffic_reports period. The repo emits
 * these already grouped by `(routerId, mac, hostname, date)` and ordered by `periodStart`, but
 * `stitch` does not rely on that.
 */
case class SessionRow(
    routerId: RouterId,
    mac: MacAddress,
    hostname: Hostname,
    date: LocalDate,
    periodStart: Instant,
    periodEnd: Instant,
    activeSeconds: Int,
    bytesIn: Long,
    bytesOut: Long,
)

/**
 * Stitching of per-period traffic rollups into per-host sessions.
 *
 * Algorithm: rows are grouped by `(routerId, mac, hostname, date)` and within each group sorted by
 * `periodStart`. A new session starts whenever the current row's `periodStart` is not exactly the
 * previous row's `periodEnd` (strict contiguity — any gap, including a single missed 5-min period,
 * ends the session). Grouping on `date` means sessions never span midnight, which aligns the output
 * with the daily `time_usage` bucket.
 *
 * `durationSeconds` is the sum of `activeSeconds` across the rows in the session, not the
 * wall-clock span — so two contiguous 5-min periods where one only saw 30s of activity produce a
 * session whose duration is `300 + 30 = 330s`. This matches what the screen-time view reports.
 *
 * Joining device/profile names is done by the caller (see SessionRoutes). The pure stitching layer
 * is intentionally name-agnostic.
 */
object Sessions {

  /** Strict-contiguity stitch. See class-level doc. Output sorted by `startedAt` desc. */
  def stitch(rows: Seq[SessionRow]): List[Session] = {
    val grouped  = rows.groupBy(r => (r.routerId, r.mac, r.hostname, r.date))
    val sessions = grouped.toList.flatMap { case (_, grp) =>
      stitchGroup(grp.sortBy(_.periodStart))
    }
    sessions.sortBy(s => -Instant.parse(s.startedAt).toEpochMilli)
  }

  private def stitchGroup(sorted: Seq[SessionRow]): List[Session] = {
    val out              = scala.collection.mutable.ListBuffer.empty[Session]
    var cur: Option[Acc] = None
    sorted.foreach { r =>
      cur match {
        case Some(a) if a.periodEnd == r.periodStart => cur = Some(a.add(r))
        case Some(a)                                 =>
          out += a.toSession
          cur = Some(Acc.from(r))
        case None                                    => cur = Some(Acc.from(r))
      }
    }
    cur.foreach(a => out += a.toSession)
    out.toList
  }

  private case class Acc(
      routerId: RouterId,
      mac: MacAddress,
      hostname: Hostname,
      date: LocalDate,
      periodStart: Instant,
      periodEnd: Instant,
      durationSeconds: Long,
      bytesIn: Long,
      bytesOut: Long,
      periodCount: Int,
  ) {
    def add(r: SessionRow): Acc = copy(
      periodEnd = r.periodEnd,
      durationSeconds = durationSeconds + r.activeSeconds.toLong,
      bytesIn = bytesIn + r.bytesIn,
      bytesOut = bytesOut + r.bytesOut,
      periodCount = periodCount + 1,
    )
    def toSession: Session      = Session(
      mac = mac,
      deviceName = None,
      profileId = None,
      profileName = None,
      hostname = hostname,
      routerId = routerId,
      date = date.toString,
      startedAt = periodStart.toString,
      endedAt = periodEnd.toString,
      durationSeconds = durationSeconds,
      bytesIn = bytesIn,
      bytesOut = bytesOut,
      periodCount = periodCount,
    )
  }
  private object Acc {
    def from(r: SessionRow): Acc = Acc(
      routerId = r.routerId,
      mac = r.mac,
      hostname = r.hostname,
      date = r.date,
      periodStart = r.periodStart,
      periodEnd = r.periodEnd,
      durationSeconds = r.activeSeconds.toLong,
      bytesIn = r.bytesIn,
      bytesOut = r.bytesOut,
      periodCount = 1,
    )
  }
}
