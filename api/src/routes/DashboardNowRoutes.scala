package familydns.api.routes

import familydns.api.auth.*
import familydns.api.db.*
import familydns.api.sessions.{SessionRow, Sessions}
import familydns.shared.*
import familydns.shared.types.*
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*

import java.time.Instant

/**
 * `GET /api/dashboard/now` — live "what is happening right now" snapshot.
 *
 * Returns every profile visible to the caller, including idle ones (so dashboard layout is stable).
 * Within each profile, lists devices that produced either a connection_events row in the last
 * `ActiveWindow` (60s) OR a traffic_reports period whose `period_end` falls in the last
 * `TrafficActiveWindow` (5m). For each active device, returns the top hosts by active_seconds over
 * the last `TopHostsWindow` (30m) and the in-progress session (via [[Sessions.stitch]]) iff its
 * latest period_end is within `SessionTolerance` (10m) of "now".
 */
object DashboardNowRoutes {

  private val ActiveWindow        = java.time.Duration.ofSeconds(300) // connection_events: last 5m
  private val TrafficActiveWindow = java.time.Duration.ofMinutes(5)
  private val TopHostsWindow      = java.time.Duration.ofMinutes(30)
  private val SessionTolerance    = java.time.Duration.ofMinutes(10)
  private val TopHostsLimit       = 3

  def routes(
      auth: AuthService,
      trafficRepo: TrafficReportRepo,
      connRepo: ConnectionEventRepo,
      deviceRepo: DeviceRepo,
      profileRepo: ProfileRepo,
      userProfileRepo: UserProfileRepo,
      clock: Clock,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "dashboard" / "now" ->
        handler { (req: Request) =>
          for {
            claims      <- requireAuth(req, auth)
            now         <- clock.instant
            allDevices  <- deviceRepo.listAll.mapError(ErrorMapper.dbErrorToResponse)
            visibleDevs <- filterDevices(claims, allDevices, userProfileRepo)
            allProfiles <- profileRepo.listAll.mapError(ErrorMapper.dbErrorToResponse)
            visibleProf <- visibleProfiles(claims, allProfiles, userProfileRepo)
            visibleMacs = visibleDevs.map(_.mac)
            // Both inputs in parallel.
            since       = now.minus(TopHostsWindow)
            connSince   = now.minus(ActiveWindow)
            lastSeenF <- connRepo.lastSeenByMacSince(connSince).fork
            rowsF     <- trafficRepo
              .listSessionRows(
                SessionFilter(
                  macs = Some(visibleMacs),
                  host = None,
                  since = Some(since),
                  until = None,
                ),
              )
              .fork
            lastSeen  <- lastSeenF.join.mapError(ErrorMapper.dbErrorToResponse)
            rows      <- rowsF.join.mapError(ErrorMapper.dbErrorToResponse)
            response = buildResponse(
              now = now,
              profiles = visibleProf,
              devices = visibleDevs,
              lastSeen = lastSeen,
              rows = rows,
            )
          } yield Response.json(response.toJson)
        },
    )

  /** Pure assembly — exposed for unit tests. */
  def buildResponse(
      now: Instant,
      profiles: List[Profile],
      devices: List[Device],
      lastSeen: Map[MacAddress, Instant],
      rows: List[SessionRow],
  ): DashboardNow = {
    val trafficCutoff                             = now.minus(TrafficActiveWindow)
    val sessionCutoff                             = now.minus(SessionTolerance)
    val rowsByMac                                 = rows.groupBy(_.mac)
    val latestTrafficTs: Map[MacAddress, Instant] =
      rowsByMac.view
        .mapValues(rs => rs.map(_.periodEnd).max)
        .toMap

    val profile = profiles.sortBy(_.id).map { p =>
      val devs   = devices.filter(_.profileId.contains(p.id))
      val active = devs.flatMap { d =>
        val connTs    = lastSeen.get(d.mac)
        val trafficTs = latestTrafficTs.get(d.mac).filter(_.isAfter(trafficCutoff))
        val activeTs  = (connTs, trafficTs) match {
          case (Some(a), Some(b)) => Some(if a.isAfter(b) then a else b)
          case (a, b)             => a.orElse(b)
        }
        activeTs.map { ts =>
          val lastSeenSeconds = math.max(0L, now.getEpochSecond - ts.getEpochSecond)
          val devRows         = rowsByMac.getOrElse(d.mac, Nil)
          DashboardNowDevice(
            id = d.id,
            name = d.name,
            mac = d.mac,
            lastSeenSeconds = lastSeenSeconds,
            topHosts = topHostsFromRows(devRows),
            currentSession = currentSessionFromRows(devRows, sessionCutoff),
          )
        }
      }
      DashboardNowProfile(
        id = p.id,
        name = p.name,
        paused = p.paused,
        activeDevices = active.sortBy(_.lastSeenSeconds),
      )
    }

    DashboardNow(asOf = now.toString, profiles = profile)
  }

  def topHostsFromRows(rows: List[SessionRow]): List[DashboardNowHost] =
    rows
      .groupBy(_.host)
      .view
      .mapValues(rs => rs.map(_.activeSeconds.toLong).sum)
      .toList
      .sortBy { case (h, s) => (-s, h.value) }
      .take(TopHostsLimit)
      .map { case (h, s) => DashboardNowHost(h, s) }

  def currentSessionFromRows(
      rows: List[SessionRow],
      sessionCutoff: Instant,
  ): Option[DashboardNowCurrentSession] =
    if rows.isEmpty then None
    else {
      val stitched = Sessions.stitch(rows)
      // Sessions.stitch returns newest-first by startedAt; pick the one whose endedAt is most recent
      // and after the cutoff.
      stitched
        .filter(s => Instant.parse(s.endedAt).isAfter(sessionCutoff))
        .sortBy(s => -Instant.parse(s.endedAt).toEpochMilli)
        .headOption
        .map { s =>
          DashboardNowCurrentSession(
            host = s.host,
            startedAt = s.startedAt,
            durationSeconds = s.durationSeconds,
          )
        }
    }
}
