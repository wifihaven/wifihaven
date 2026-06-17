package wifihaven.api.routes

import wifihaven.api.db.RouterRepo
import wifihaven.api.metrics.AppMetrics
import wifihaven.shared.Router
import wifihaven.shared.types.*
import zio.*
import zio.http.*

import java.security.MessageDigest

/**
 * Authentication for the `/api/router/`-prefixed family of endpoints. The OpenWRT agent sends a
 * per-router bearer token (stored on disk in UCI). The API stores only a SHA-256 hash of the token
 * in the `routers` table; this trait resolves a presented token back to the router record.
 *
 * Defined as a trait so #68 (enrollment) can swap implementations without editing every route.
 */
trait RouterAuth {
  def authenticate(req: Request): IO[ApiError, Router]
}

object RouterAuth {

  /**
   * Lowercase hex SHA-256 of the input bytes. Must match what `POST /api/router/register` writes to
   * `routers.token_hash` in #68.
   */
  def sha256Hex(s: String): String = {
    val md = MessageDigest.getInstance("SHA-256")
    md.digest(s.getBytes("UTF-8")).map(b => f"$b%02x").mkString
  }

  private[routes] def bearer(req: Request): Option[String] =
    req.header(Header.Authorization).flatMap { h =>
      val v = h.renderedValue
      if v.startsWith("Bearer ") then Some(v.drop(7)) else None
    }
}

class RouterAuthLive(repo: RouterRepo) extends RouterAuth {
  def authenticate(req: Request): IO[ApiError, Router] =
    ZIO
      .fromOption(RouterAuth.bearer(req))
      .orElseFail(ApiError.Unauthorized("Missing router token"): ApiError)
      .flatMap { tok =>
        repo
          .findByTokenHash(Sha256Hex.unsafe(RouterAuth.sha256Hex(tok)))
          .mapError(ApiError.Db(_): ApiError)
          .flatMap(
            ZIO
              .fromOption(_)
              .orElseFail(ApiError.Unauthorized("Invalid router token"): ApiError),
          )
      }
      // #1204: count only the 401s (missing / unrecognized token), not a 500 from a
      // transient DB error that is mapped to a 503 by the central handler.
      .tapError {
        case _: ApiError.Unauthorized => AppMetrics.recordAuthFailure("bad_router_token")
        case _                        => ZIO.unit
      }
}
