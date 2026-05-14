package familydns.api.routes

import familydns.api.auth.*
import familydns.api.db.*
import familydns.api.sessions.Sessions
import familydns.shared.*
import familydns.shared.types.*
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*

import java.time.Instant

/**
 * `GET /api/sessions` — per-host activity sessions stitched from the traffic_reports rollups. See
 * docs/sessions-design.md.
 */
object SessionRoutes {

  private val MaxLimit     = 500
  private val DefaultLimit = 100
  private val DefaultHours = 24

  def routes(
      auth: AuthService,
      trafficRepo: TrafficReportRepo,
      deviceRepo: DeviceRepo,
      profileRepo: ProfileRepo,
      userProfileRepo: UserProfileRepo,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "sessions" ->
        handler { (req: Request) =>
          for {
            claims <- requireAuth(req, auth)
            qp         = req.url.queryParams
            macParam   = qp.queryParam("mac")
            deviceIdP  = qp.queryParam("deviceId").flatMap(_.toLongOption)
            profileIdP = qp.queryParam("profileId").flatMap(_.toLongOption)
            host       = qp.queryParam("host")
            since <- parseInstantOpt(qp.queryParam("since"))
            until <- parseInstantOpt(qp.queryParam("until"))
            hours  = qp.queryParam("hours").flatMap(_.toIntOption).getOrElse(DefaultHours)
            limit  = qp
              .queryParam("limit")
              .flatMap(_.toIntOption)
              .getOrElse(DefaultLimit)
              .max(1)
              .min(MaxLimit)
            cursor = qp.queryParam("cursor")
            allDevices  <- deviceRepo.listAll.mapError(ErrorMapper.dbErrorToResponse)
            visibleDevs <- filterDevices(claims, allDevices, userProfileRepo)
            // Build the macs filter from the intersection of the device, profile,
            // and mac query params with the user's visibility scope.
            macs = computeMacs(macParam, deviceIdP, profileIdP.map(ProfileId(_)), visibleDevs)
            effectiveSince = since.orElse(Some(Instant.now().minusSeconds(hours.toLong * 3600L)))
            filter         = SessionFilter(
              macs = Some(macs),
              host = host,
              since = effectiveSince,
              until = until,
            )
            rows <- trafficRepo
              .listSessionRows(filter)
              .mapError(ErrorMapper.dbErrorToResponse)
            stitched     = Sessions.stitch(rows)
            devicesByMac = visibleDevs.map(d => d.mac -> d).toMap
            profiles <- profileRepo.listAll.mapError(ErrorMapper.dbErrorToResponse)
            profilesById = profiles.map(p => p.id -> p).toMap
            enriched     = stitched.map { s =>
              val dev = devicesByMac.get(s.mac)
              val pid = dev.flatMap(_.profileId)
              s.copy(
                deviceName = dev.map(_.name),
                profileId = pid,
                profileName = pid.flatMap(profilesById.get).map(_.name),
              )
            }
            paged        = applyCursor(enriched, cursor, limit)
          } yield Response.json(paged.toJson)
        },
    )

  private def parseInstantOpt(s: Option[String]): IO[Response, Option[Instant]] =
    s match {
      case None    => ZIO.succeed(None)
      case Some(v) =>
        ZIO
          .attempt(Instant.parse(v))
          .mapBoth(_ => Response.badRequest(s"invalid timestamp: $v"), Some(_))
    }

  /**
   * Resolve the visible-mac restriction. We always intersect against the user's visible-device list
   * (which already enforces profile scoping for child tokens). If a query param narrows further
   * (mac=, deviceId=, profileId=), apply it.
   */
  private def computeMacs(
      macParam: Option[String],
      deviceId: Option[Long],
      profileId: Option[ProfileId],
      visibleDevs: List[Device],
  ): List[MacAddress] = {
    var devs = visibleDevs
    macParam.foreach(m => devs = devs.filter(_.mac.value == m))
    deviceId.foreach(id => devs = devs.filter(_.id.value == id))
    profileId.foreach(pid => devs = devs.filter(_.profileId.contains(pid)))
    devs.map(_.mac).distinct
  }

  /**
   * Cursor format: `startedAt|mac|hostname` (pipe-separated). The next page is everything strictly
   * earlier than the cursor's `startedAt`, tiebroken by (mac, hostname) descending for stability.
   */
  private def applyCursor(
      sessions: List[Session],
      cursor: Option[String],
      limit: Int,
  ): SessionPage = {
    val afterCursor = cursor match {
      case None    => sessions
      case Some(c) =>
        c.split('|').toList match {
          case start :: cm :: ch :: Nil =>
            sessions.dropWhile { s =>
              val tieGte =
                (s.mac.value.compareTo(cm), s.hostname.value.compareTo(ch)) match {
                  case (m, _) if m > 0 => true
                  case (0, h)          => h >= 0
                  case _               => false
                }
              s.startedAt > start || (s.startedAt == start && tieGte)
            }
          case _                        => sessions
        }
    }
    val page        = afterCursor.take(limit)
    val next        =
      if afterCursor.lengthCompare(limit) > 0 then {
        val last = page.last
        Some(s"${last.startedAt}|${last.mac.value}|${last.hostname.value}")
      } else None
    SessionPage(sessions = page, nextCursor = next)
  }
}
