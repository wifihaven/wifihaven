package wifihaven.api.routes

import wifihaven.api.db.*
import wifihaven.api.policy.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*

/**
 * #959: kid-side block-page support endpoint. Unauthenticated by design — the router redirects
 * blocked clients here (DNAT to the SPA), the SPA reads `mac` + `host` from the query string and
 * calls this endpoint to render a kid-friendly reason.
 *
 * Resolution reuses [[PolicyService.decide]] so the reason matches what the router enforced. The
 * response is intentionally narrow per the #952 design doc Q4 decision:
 *   - `reasonClass`: one of "paused" | "schedule" | "time_limit" | "app_time_limit" | "category"
 * \| "extra_blocked".
 *   - `categoryName`: only populated for the "category" class (so kids see what kind of site is
 *     blocked).
 *   - `profileName`: included so the page can say e.g. "for Octavius" — already visible to anyone
 *     on the household LAN via the router itself; no new info-leak risk.
 *
 * Granular details that the design doc explicitly excludes (schedule end time, per-site label,
 * daily-cap minute counts) are NOT returned. The response shape is identical for an unknown MAC and
 * an unblocked-but-known MAC: `{blocked: false, ...}` so the endpoint doesn't double as a
 * MAC-enrollment oracle.
 */
object BlockedRoutes {
  def routes(
      policy: PolicyService,
      deviceRepo: DeviceRepo,
      profileRepo: ProfileRepo,
      blocklistRepo: BlocklistRepo,
      timeStatusService: TimeStatusService,
      householdSettingsRepo: HouseholdSettingsRepo,
      clock: Clock,
  ): Routes[Any, Response] = {
    val notBlocked = BlockedInfoResponse(blocked = false, None, None, None)

    Routes(
      Method.GET / "api" / "blocked" ->
        handler { (req: Request) =>
          val macRaw  = req.url.queryParam("mac").getOrElse("")
          val hostRaw = req.url.queryParam("host").getOrElse("")
          val parsed  = for {
            mac  <- MacAddress.parse(macRaw)
            host <- Hostname.parse(hostRaw)
          } yield (mac, host)

          parsed match {
            case Left(_)            =>
              ZIO.succeed(Response.json(notBlocked.toJson))
            case Right((mac, host)) =>
              resolve(
                policy,
                deviceRepo,
                profileRepo,
                blocklistRepo,
                timeStatusService,
                householdSettingsRepo,
                clock,
                mac,
                host,
              )
                .map(r => Response.json(r.toJson))
                .catchAll(_ => ZIO.succeed(Response.json(notBlocked.toJson)))
          }
        },
    )
  }

  private def resolve(
      policy: PolicyService,
      deviceRepo: DeviceRepo,
      profileRepo: ProfileRepo,
      blocklistRepo: BlocklistRepo,
      timeStatusService: TimeStatusService,
      householdSettingsRepo: HouseholdSettingsRepo,
      clock: Clock,
      mac: MacAddress,
      host: Hostname,
  ): Task[BlockedInfoResponse] =
    for {
      // #2107: the block page is unauthenticated (reached via HTTP DNAT with only ?mac=&host=), so
      // it carries no router token and thus no household. Single-household today: decide against
      // HouseholdId.Default. Resolving the household for the block page in a multi-tenant deploy is
      // the edge / custom-domain concern (#2109), not this read-scoping change.
      decision    <- policy.decide(HouseholdId.Default, mac.value, host.value)
      // #2312: household-scoped lookup — the same MAC can exist in two households (V74/V75), and the
      // old global findByMac threw on the 2-row match. Same HouseholdId.Default as `decide` above
      // until per-request block-page household derivation lands (#2109).
      device      <- deviceRepo.findByMac(mac, HouseholdId.Default)
      profileOpt  <- device.flatMap(_.profileId) match {
        case Some(pid) => profileRepo.findById(pid)
        case None      => ZIO.succeed(None)
      }
      // #335: today's usage for the device's profile, sourced from the canonical
      // TimeStatusService — same primitive that drives the snapshot's TimeLimit
      // decision and the admin /api/time/status UI. None when there's no profile.
      dayStateOpt <- profileOpt match {
        case None    => ZIO.succeed(None)
        case Some(p) =>
          for {
            now      <- clock.instant
            settings <- householdSettingsRepo.get
            ds       <- timeStatusService.todaysState(now, settings, p.id)
          } yield ds
      }
      reasonPair  <-
        if decision.decision != ConnectionDecision.Block then ZIO.succeed(None)
        else mapReason(decision.reason, blocklistRepo).map(Some(_))
    } yield BlockedInfoResponse(
      blocked = reasonPair.isDefined,
      reasonClass = reasonPair.map(_._1),
      categoryName = reasonPair.flatMap(_._2),
      profileName = profileOpt.map(_.name),
      usedMinutes = dayStateOpt.map(_.usedMinutes),
      dailyLimitMinutes = dayStateOpt.flatMap(_.dailyLimitMinutes),
      extensionMinutes = dayStateOpt.map(_.extensionMinutes),
      remainingMinutes = dayStateOpt.flatMap(_.remainingMinutes),
    )

  /**
   * Map the router decision wire-reason to a (reasonClass, categoryName?) pair.
   *
   * #1545: the wire-reason is parsed once through the canonical [[BlockReason.fromWire]] and the
   * sealed result is matched exhaustively, so this re-parser can no longer drift from the strings
   * [[PolicyService.decide]] emits (which now also go through [[BlockReason.asWire]]) — the
   * compiler fails the build if a new `BlockReason` case is added without a mapping here.
   *
   * The old `case _ => ("extra_blocked", None)` fallthrough silently relabeled ANY unrecognized
   * reason as "a specific site", so a future `decide()` reason an older block-page build hadn't
   * learned would render the wrong copy. Now `Unknown(raw)` — and every reason that isn't one of
   * the specific block classes the kid page distinguishes — maps to the generic `"blocked"` class
   * (the SPA's `copyFor` default → "Access blocked."), never to `extra_blocked`.
   */
  private def mapReason(
      reason: String,
      blocklistRepo: BlocklistRepo,
  ): Task[(String, Option[String])] = BlockReason.fromWire(reason) match {
    // #1532: reasonClass strings are sourced from each case's `wireKind` rather
    // than hand-written parallel literals. The block page's reason taxonomy and
    // the BlockReason wire taxonomy are intentionally aligned; reading the value
    // from `wireKind` keeps them aligned through future renames instead of
    // leaving it to a "must mirror" comment.
    case r @ MacBlockReason.Paused     => ZIO.succeed((r.wireKind, None))
    case r @ MacBlockReason.Schedule   => ZIO.succeed((r.wireKind, None))
    case r @ MacBlockReason.TimeLimit  => ZIO.succeed((r.wireKind, None))
    case r @ BlockReason.ExtraBlocked  => ZIO.succeed((r.wireKind, None))
    case BlockReason.ExtraBlockedBy(_) =>
      // #1645: a per-flow host block from another path still renders as the
      // generic "extra_blocked" class — the block page does not distinguish
      // ExtraBlocked vs ExtraBlockedBy(host).
      ZIO.succeed((BlockReason.ExtraBlocked.wireKind, None))
    case r: BlockReason.AppTimeLimit   =>
      // #1518 rename (`site_time_limit` → `app_time_limit`); `r.wireKind` is the
      // SPA-API surface string, updated atomically with the SPA in the same PR.
      ZIO.succeed((r.wireKind, None))
    case r @ BlockReason.Category(id)  =>
      blocklistRepo
        .findMeta(id)
        .map(meta => (r.wireKind, meta.map(_.name)))
        .catchAll(_ => ZIO.succeed((r.wireKind, None)))
    // Generic / unrecognized blocks. `decide()` only reaches mapReason on a Block decision, so the
    // allow-side cases (Allow/ExtraAllowed/NoProfile) are defensive; Manual/Unmanaged/DefaultDeny/
    // AppBlocked and any Unknown(raw) wire string render the neutral block copy rather than being
    // mislabeled as a specific-site block.
    case BlockReason.Allow | BlockReason.Blocked | BlockReason.ExtraAllowed |
        BlockReason.NoProfile | BlockReason.AppBlocked(_) | BlockReason.Unknown(_) |
        MacBlockReason.Manual | MacBlockReason.Unmanaged | MacBlockReason.DefaultDeny =>
      ZIO.succeed(("blocked", None))
  }
}
