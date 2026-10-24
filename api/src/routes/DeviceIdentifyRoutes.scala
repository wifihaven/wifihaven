package wifihaven.api.routes

import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.identify.MacVendors
import wifihaven.api.metrics.AppMetrics
import wifihaven.api.usage.{AppMembership, RetentionSweepJob}
import wifihaven.shared.*
import wifihaven.shared.types.*
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*

import java.time.Duration

// ── Device identify (#2926) ─────────────────────────────────────────────────
//
// `GET /api/devices/{mac}/identify` — "what is this device?" for an unmanaged one: its advertised
// name, the OUI vendor (or "private" for a randomized MAC), and every destination it tried to reach
// over the window, BLOCKED attempts included, grouped by host and by catalog app.
//
// Strictly a read. It writes nothing and resolves no policy: viewing a device must never unblock,
// assign, or otherwise change it.
object DeviceIdentifyRoutes {

  val DefaultWindowDays = 7
  val DefaultLimit      = 200
  val MaxLimit          = 500

  /**
   * A recognised app must carry at least this share of all attempts to be offered as a guess. A
   * product choice, not a measured threshold: "most of its traffic" is the bar the label claims, so
   * the label is only offered when that is literally true.
   */
  val GuessMinSharePct = 50

  def routes(
      auth: AuthService,
      deviceRepo: DeviceRepo,
      connectionEventRepo: ConnectionEventRepo,
      appRepo: AppRepo,
      userProfileRepo: UserProfileRepo,
      profileRepo: ProfileRepo,
      vendors: MacVendors,
      clock: wifihaven.shared.Clock,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "devices" / string("mac") / "identify" ->
        handler { (macRaw: String, req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireAuth(req, auth)
            mac = MacAddress.unsafe(normalizeMac(macRaw))
            // #2108: household-scoped device lookup, as `recent-apexes` does — a MAC that is not in
            // the caller's household 404s before any event is read (covers unmanaged devices, which
            // the profile guard alone would not).
            device <- deviceRepo
              .findByMacInHousehold(mac, claims.hh)
              .mapError(ApiError.Db(_))
              .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("Device not found")))
            _ <- requireProfileReadAccess(claims, device.profileId, userProfileRepo, profileRepo)
            windowDays = req.url
              .queryParam("windowDays")
              .flatMap(_.toIntOption)
              .getOrElse(DefaultWindowDays)
              .max(1)
              .min(RetentionSweepJob.EventsRetentionDays)
            limit      = req.url
              .queryParam("limit")
              .flatMap(_.toIntOption)
              .getOrElse(DefaultLimit)
              .max(1)
              .min(MaxLimit)
            now <- clock.instant
            from = now.minus(Duration.ofDays(windowDays.toLong))
            discovery <- deviceRepo
              .discoveryInHousehold(mac, claims.hh)
              .mapError(ApiError.Db(_))
            dests     <- connectionEventRepo
              .destinationsForMac(claims.hh, mac, from, now, limit)
              .mapError(ApiError.Db(_))
            appList   <- appRepo.listAll.mapError(ApiError.Db(_))
            mappings  <- appRepo.listAllHostMappings.mapError(ApiError.Db(_))
            vendor = vendors.lookup(mac)
            body   = build(
              device,
              vendor,
              // The row is created at INGEST time; an event that arrived late (router retry queue)
              // can be older, and the device was evidently on the network by then.
              (discovery.map(_.firstSeenAt).toList ++ dests.earliest).minOption.map(_.toString),
              discovery.flatMap(_.dhcpHostname),
              windowDays,
              dests,
              appList,
              mappings,
            )
            _ <- AppMetrics.recordDeviceIdentifyView(vendor.kind)
          } yield Response.json(body.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
    )

  /**
   * Host → app over DISTINCTIVE app hosts only ([[AppMembership.distinctiveOwners]]). A shared host
   * (a multi-tenant vendor backend, #1898) says little about which product a device is, so it
   * labels nothing here.
   */
  private[routes] def appIndex(
      appList: List[App],
      mappings: List[AppHost],
  ): Map[String, IdentifyAppRef] = {
    val byId = appList.map(a => a.id -> a).toMap
    AppMembership.distinctiveOwners(mappings).flatMap { case (host, id) =>
      byId.get(id).map(a => host -> IdentifyAppRef(a.slug, a.name))
    }
  }

  private[routes] def build(
      device: Device,
      vendor: DeviceVendor,
      firstSeenAt: Option[String],
      dhcpHostname: Option[String],
      windowDays: Int,
      dests: MacDestinations,
      appList: List[App],
      mappings: List[AppHost],
  ): DeviceIdentity = {
    val index        = appIndex(appList, mappings)
    val destinations = dests.rows.map { r =>
      IdentifyDestination(
        host = r.host,
        app = r.host.asFqdn.flatMap(h => HostMatch.lookupApex(h.value, index)),
        allowed = r.allowed,
        blocked = r.blocked,
        firstSeen = r.firstSeen.toString,
        lastSeen = r.lastSeen.toString,
      )
    }
    val apps         = destinations
      .flatMap(d => d.app.map(_ -> d))
      .groupMap(_._1)(_._2)
      .toList
      .map { case (ref, ds) =>
        IdentifyApp(ref.slug, ref.name, ds.map(_.allowed).sum, ds.map(_.blocked).sum, ds.size)
      }
      .sortBy(a => (-(a.allowed + a.blocked), a.slug))
    val total        = dests.totalAllowed + dests.totalBlocked
    val guess        = apps.headOption.flatMap { top =>
      val pct =
        if (total == 0L) 0 else math.round((top.allowed + top.blocked) * 100.0 / total).toInt
      Option.when(pct >= GuessMinSharePct)(IdentifyGuess(top.slug, top.name, pct))
    }
    DeviceIdentity(
      mac = device.mac,
      name = device.name,
      sentName = Option.when(!DeviceNames.isPlaceholder(device.name))(device.name),
      dhcpHostname = dhcpHostname,
      managed = device.profileId.isDefined,
      vendor = vendor,
      firstSeenAt = firstSeenAt,
      lastSeenAt = device.lastSeenAt,
      lastSeenIp = device.lastSeenIp,
      windowDays = windowDays,
      totalAllowed = dests.totalAllowed,
      totalBlocked = dests.totalBlocked,
      hostCount = dests.hostCount,
      truncated = dests.hostCount > destinations.size,
      destinations = destinations,
      apps = apps,
      guess = guess,
    )
  }
}
