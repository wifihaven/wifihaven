package wifihaven.api.routes

import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.policy.ProfileAppDispositions
import wifihaven.api.presence.Presence
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
      appTimeLimitRepo: AppTimeLimitRepo,
      clock: Clock,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "dashboard" / "now" ->
        handler { (req: Request) =>
          // #1570: fail with typed ApiError; ErrorMapper.errorToResponse maps it and the
          // ErrorBoundary logs (4xx WARN / 5xx ERROR) + meters. Same final Response as before.
          val handle: ZIO[Any, ApiError, Response] = for {
            claims      <- requireAuth(req, auth)
            now         <- clock.instant
            allDevices  <- deviceRepo.listAll.mapError(ApiError.Db(_))
            visibleDevs <- filterDevices(claims, allDevices, userProfileRepo)
            allProfiles <- profileRepo.listAll.mapError(ApiError.Db(_))
            visibleProf <- visibleProfiles(claims, allProfiles, userProfileRepo)
            // #1970: the gather-and-assemble is shared with the SPA-websocket `now` push (S3) so the
            // streamed body and this GET body are produced by ONE builder (SSOT) — recomputed on
            // change for the push, per-request here.
            response    <- computeNow(
              now,
              visibleProf,
              visibleDevs,
              trafficRepo,
              connRepo,
              appTimeLimitRepo,
            )
              .mapError(ApiError.Db(_))
          } yield Response.json(response.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
    )

  /**
   * #1970: gather the NOW inputs and assemble the [[DashboardNow]] body. The single implementation
   * of the NOW snapshot (design `docs/design/spa-websocket.md` §5.2 / `AGENTS.md`
   * single-source-of-truth) — the `GET /api/dashboard/now` handler calls it per-request, and the
   * SPA websocket `now` push ([[SpaPush]]) calls it on change. `profiles`/`devices` are the
   * caller's already-visibility-filtered lists (the GET filters by claims; the push passes the full
   * set since `now` is visible only to roles the GET shows everything to, §4.4), so this method is
   * agnostic to the authz model. Reads run in parallel; a repo failure surfaces as the
   * [[Throwable]].
   *
   * #1559: per-profile active-app host-set is read through the canonical [[ProfileAppDispositions]]
   * fold so the dashboard cannot disagree with the counting paths on what "active app host" means
   * (#1532 / #1560).
   */
  def computeNow(
      now: Instant,
      profiles: List[Profile],
      devices: List[Device],
      trafficRepo: TrafficReportRepo,
      connRepo: ConnectionEventRepo,
      appTimeLimitRepo: AppTimeLimitRepo,
  ): Task[DashboardNow] = {
    val visibleMacs = devices.map(_.mac)
    val since       = now.minus(TopHostsWindow)
    val connSince   = now.minus(RecentActivityWindow)
    for {
      lastSeenF  <- connRepo.lastSeenByMacSince(connSince).fork
      rowsF      <- trafficRepo
        .listTrafficRollupRows(
          TrafficRollupFilter(
            macs = Some(visibleMacs),
            host = None,
            since = Some(since),
            until = None,
          ),
        )
        .fork
      appLimitsF <- appTimeLimitRepo.listAll.fork
      lastSeen   <- lastSeenF.join
      rows       <- rowsF.join
      appLimits  <- appLimitsF.join
      appHostPatternsByProfile = appLimits
        .groupBy(_.profileId)
        .view
        .mapValues(ProfileAppDispositions.from(_).appHostPatterns)
        .toMap
    } yield buildResponse(
      now = now,
      profiles = profiles,
      devices = devices,
      lastSeen = lastSeen,
      rows = rows,
      appHostPatternsByProfile = appHostPatternsByProfile,
    )
  }

  /**
   * Pure assembly — exposed for unit tests.
   *
   * The host *ranking* (`topHosts`, `nowActivity`) drops device-level background infra via
   * [[dropBackground]], so infra never surfaces as the active host (#1503/#1525). Suppression keys
   * on host IDENTITY only (the unified [[InfraHosts]] set), never the byte floor — so it cannot
   * hide a genuine low-byte foreground request (the #1446 undercount mechanism). It is deliberately
   * NOT applied to active-device detection: a device whose only recent traffic is infra is still
   * "online", it just has no foreground host to show.
   */
  def buildResponse(
      now: Instant,
      profiles: List[Profile],
      devices: List[Device],
      lastSeen: Map[MacAddress, Instant],
      rows: List[TrafficRollupRow],
      appHostPatternsByProfile: Map[ProfileId, List[String]] = Map.empty,
  ): DashboardNow = {
    val trafficCutoff                             = now.minus(TrafficActiveWindow)
    val rowsByMac                                 = rows.groupBy(_.mac)
    val latestTrafficTs: Map[MacAddress, Instant] =
      rowsByMac.view
        .mapValues(rs => rs.map(_.periodEnd).max)
        .toMap

    val profile = profiles.sortBy(_.id).map { p =>
      val devs            = devices.filter(_.profileId.contains(p.id))
      val appHostPatterns = appHostPatternsByProfile.getOrElse(p.id, Nil)
      val active          = devs.flatMap { d =>
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
            topHosts = topHostsFromRows(devRows, appHostPatterns),
            nowActivity = nowActivityFromRows(devRows, appHostPatterns),
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
      appHostPatterns: List[String] = Nil,
  ): Option[DashboardNowActivity] = {
    val buckets = dropBackground(rows, appHostPatterns)
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
      appHostPatterns: List[String] = Nil,
  ): List[DashboardNowHost] =
    dropBackground(rows, appHostPatterns)
      .groupBy(_.host)
      .view
      .mapValues(rs => rs.map(_.activeSeconds.toLong).sum)
      .toList
      .sortBy { case (h, s) => (-s, h.value) }
      .take(TopHostsLimit)
      .map { case (h, s) => DashboardNowHost(h, s) }

  /**
   * #1503/#1525: drop device-level background infra before ranking, so the now-widget never
   * surfaces infra/telemetry as "watching X right now". Suppression is keyed on host IDENTITY only
   * (the unified [[InfraHosts]] background set — `canonical ∪ suppressOnly`), never on the byte
   * floor — so it cannot hide a genuine low-byte foreground request (the #1446 undercount
   * mechanism): a single chatty 60-byte keepalive looks identical to a real request-driven app at
   * the byte level, so only identity is safe here.
   *
   * #1559: ATTRIBUTION BEATS SUPPRESSION. `appHostPatterns` is the union of the profile's
   * active-app host-sets (via [[ProfileAppDispositions.appHostPatterns]]). A row whose host matches
   * an active app's host-set is attributed to that app and kept in the ranking — so an off-domain
   * asset/CDN host an app genuinely depends on that also happens to be on the [[InfraHosts]]
   * device-infra list (e.g. `beacons3.gvt2.com` when a "Google" app claims `gvt2.com`) surfaces in
   * topHosts/nowActivity instead of being silently dropped. Routed through the single host-keyed
   * [[Presence.suppressedAsBackground]] predicate — same rule the counting surfaces use, no second
   * copy (#1532 / #1560).
   */
  private def dropBackground(
      rows: List[TrafficRollupRow],
      appHostPatterns: List[String],
  ): List[TrafficRollupRow] =
    rows.filterNot(r => Presence.suppressedAsBackground(r.host, appHostPatterns))
}
