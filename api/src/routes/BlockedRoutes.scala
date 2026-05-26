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
 *   - `reasonClass`: one of "paused" | "schedule" | "time_limit" | "site_time_limit" | "category"
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
              resolve(policy, deviceRepo, profileRepo, blocklistRepo, mac, host)
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
      mac: MacAddress,
      host: Hostname,
  ): Task[BlockedInfoResponse] = {
    val notBlocked = BlockedInfoResponse(blocked = false, None, None, None)
    for {
      decision   <- policy.decide(mac.value, host.value)
      device     <- deviceRepo.findByMac(mac)
      profileOpt <- device.flatMap(_.profileId) match {
        case Some(pid) => profileRepo.findById(pid)
        case None      => ZIO.succeed(None)
      }
      result     <-
        if decision.decision != ConnectionDecision.Block then ZIO.succeed(notBlocked)
        else
          mapReason(decision.reason, blocklistRepo).map { case (rc, catName) =>
            BlockedInfoResponse(
              blocked = true,
              reasonClass = Some(rc),
              categoryName = catName,
              profileName = profileOpt.map(_.name),
            )
          }
    } yield result
  }

  /**
   * Map the router decision wire-reason to a (reasonClass, categoryName?) pair. The reason strings
   * here mirror the literals emitted by [[PolicyService.decide]].
   */
  private def mapReason(
      reason: String,
      blocklistRepo: BlocklistRepo,
  ): Task[(String, Option[String])] = reason match {
    case "paused"                              => ZIO.succeed(("paused", None))
    case "schedule"                            => ZIO.succeed(("schedule", None))
    case "time_limit"                          => ZIO.succeed(("time_limit", None))
    case "extra_blocked"                       => ZIO.succeed(("extra_blocked", None))
    case r if r.startsWith("site_time_limit:") => ZIO.succeed(("site_time_limit", None))
    case r if r.startsWith("category:")        =>
      val rest = r.drop("category:".length)
      BlocklistId.parse(rest) match {
        case Right(id) =>
          blocklistRepo
            .findMeta(id)
            .map(meta => ("category", meta.map(_.name)))
            .catchAll(_ => ZIO.succeed(("category", None)))
        case Left(_)   => ZIO.succeed(("category", None))
      }
    case _                                     => ZIO.succeed(("extra_blocked", None))
  }
}
