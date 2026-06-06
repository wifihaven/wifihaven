package wifihaven.api.routes

import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*

import java.time.Instant

/**
 * `GET /api/dashboard/now` — live "what is happening right now" snapshot.
 *
 * Returns every profile visible to the caller, including idle ones (so dashboard layout is stable).
 * Within each profile, lists devices that produced either a connection_events row in the last
 * `RecentActivityWindow` (5m) OR a traffic_reports period whose `period_end` falls in the last
 * `TrafficActiveWindow` (5m). For each active device, returns the top hosts by active_seconds over
 * the last `TopHostsWindow` (30m).
 */
object DashboardNowRoutes {

  // UX-level "is this device recently active?" knob; not config-tunable. See #738.
  private val RecentActivityWindow = java.time.Duration.ofSeconds(300) // connection_events: last 5m
  private val TrafficActiveWindow  = java.time.Duration.ofMinutes(5)
  private val TopHostsWindow       = java.time.Duration.ofMinutes(30)
  private val TopHostsLimit        = 3
  // #852 — nowActivity "watching X · Nm" runs back through consecutive earlier buckets where the
  // same host is also top; cap so we never claim hours of foreground use from sparse data.
  private val NowActivityMaxMinutes = 60

  def routes(
      auth: AuthService,
      trafficRepo: TrafficReportRepo,
      connRepo: ConnectionEventRepo,
      deviceRepo: DeviceRepo,
      profileRepo: ProfileRepo,
      userProfileRepo: UserProfileRepo,
      householdSettingsRepo: HouseholdSettingsRepo,
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
            // #1503: the now-widget ranks active hosts; route that ranking through the same
            // heartbeat/infra filter every other usage surface uses so device-level infra
            // (gvt2, ls.apple, OCSP, …) is never surfaced as "watching X right now".
            settings    <- householdSettingsRepo.get.mapError(ErrorMapper.dbErrorToResponse)
            visibleMacs = visibleDevs.map(_.mac)
            // Both inputs in parallel.
            since       = now.minus(TopHostsWindow)
            connSince   = now.minus(RecentActivityWindow)
            lastSeenF <- connRepo.lastSeenByMacSince(connSince).fork
            rowsF     <- trafficRepo
              .listTrafficRollupRows(
                TrafficRollupFilter(
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
              filter = settings.heartbeatFilter,
            )
          } yield Response.json(response.toJson)
        },
    )

  /**
   * Pure assembly — exposed for unit tests.
   *
   * `filter` (default `Off`) is applied only to the host *ranking* (`topHosts`, `nowActivity`) via
   * [[dropBackground]], so device-level infra never surfaces as the active host (#1503). It is
   * deliberately NOT applied to active-device detection: a device whose only recent traffic is
   * infra is still "online", it just has no foreground host to show.
   */
  def buildResponse(
      now: Instant,
      profiles: List[Profile],
      devices: List[Device],
      lastSeen: Map[MacAddress, Instant],
      rows: List[TrafficRollupRow],
      filter: HeartbeatFilter = HeartbeatFilter.Off,
  ): DashboardNow = {
    val trafficCutoff                             = now.minus(TrafficActiveWindow)
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
            topHosts = topHostsFromRows(devRows, filter),
            nowActivity = nowActivityFromRows(devRows, filter),
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

  /**
   * Per-device "what is this device watching right now". Reads rows from the latest populated 5-min
   * bucket and returns its top host. If earlier consecutive buckets share the same top host,
   * reports a `minutes` run (capped at 60); otherwise `minutes = None`. See #852.
   */
  def nowActivityFromRows(
      rows: List[TrafficRollupRow],
      filter: HeartbeatFilter = HeartbeatFilter.Off,
  ): Option[DashboardNowActivity] = {
    val buckets = dropBackground(rows, filter)
      .groupBy(_.periodEnd)
      .toList
      .sortBy { case (end, _) => -end.getEpochSecond }
    buckets.headOption.flatMap { case (_, latestRows) =>
      bucketTopHost(latestRows).map { top =>
        val tail       = buckets.tail
        val streakRest = tail.takeWhile { case (_, rs) => bucketTopHost(rs).contains(top) }
        val streakSecs = (latestRows :: streakRest.map(_._2)).map { rs =>
          val s = rs.head.periodStart.getEpochSecond
          val e = rs.head.periodEnd.getEpochSecond
          math.max(0L, e - s)
        }.sum
        val minutes    = math.min(NowActivityMaxMinutes, math.max(1, (streakSecs / 60L).toInt))
        // Only attach a duration once we have a multi-bucket signal — a single 5-min bucket isn't
        // strong enough to claim "watching for N minutes".
        val mins       = if (streakRest.nonEmpty) Some(minutes) else None
        DashboardNowActivity(topHost = top, minutes = mins)
      }
    }
  }

  private def bucketTopHost(rows: List[TrafficRollupRow]): Option[HostId] =
    rows
      .groupBy(_.host)
      .view
      .mapValues(_.map(_.activeSeconds.toLong).sum)
      .toList
      .filter { case (_, s) => s > 0 }
      .sortBy { case (h, s) => (-s, h.value) }
      .headOption
      .map(_._1)

  def topHostsFromRows(
      rows: List[TrafficRollupRow],
      filter: HeartbeatFilter = HeartbeatFilter.Off,
  ): List[DashboardNowHost] =
    dropBackground(rows, filter)
      .groupBy(_.host)
      .view
      .mapValues(rs => rs.map(_.activeSeconds.toLong).sum)
      .toList
      .sortBy { case (h, s) => (-s, h.value) }
      .take(TopHostsLimit)
      .map { case (h, s) => DashboardNowHost(h, s) }

  /**
   * #1503: drop device-level background hosts before ranking, so the now-widget never surfaces
   * infra/telemetry as "watching X right now". Suppression is keyed on host IDENTITY only — never
   * on the byte floor — so it cannot hide a genuine low-byte foreground request (the #1446
   * undercount mechanism); a single chatty 60-byte keepalive looks identical to a real
   * request-driven app at the byte level, so only identity is safe here. Two identity sources:
   *
   *   - the unified [[InfraHosts]] canonical set (the same list `PolicyService.infraAllowHosts`
   *     allows and `Presence` suppresses) — always, independent of the operator's filter toggle.
   *   - the operator's curated `heartbeatHostPatterns` (e.g. `*.push.apple.com`) — only when the
   *     heartbeat filter is enabled, matching how the other surfaces honor that toggle.
   */
  private def dropBackground(
      rows: List[TrafficRollupRow],
      filter: HeartbeatFilter,
  ): List[TrafficRollupRow] =
    rows.filterNot(r => isBackgroundHost(r.host, filter))

  private def isBackgroundHost(host: HostId, filter: HeartbeatFilter): Boolean =
    host.asFqdn.exists { fqdn =>
      InfraHosts.isInfra(fqdn.value) ||
      (filter.enabled && filter.heartbeatHostPatterns.exists(p =>
        HostMatch.matchesPattern(fqdn.value, p),
      ))
    }
}
