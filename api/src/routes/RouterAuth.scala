package familydns.api.routes

import familydns.api.db.RouterRepo
import familydns.shared.Router
import familydns.shared.types.*
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
  def authenticate(req: Request): IO[Response, Router]
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
  def authenticate(req: Request): IO[Response, Router] =
    ZIO
      .fromOption(RouterAuth.bearer(req))
      .orElseFail(Response.unauthorized("Missing router token"))
      .flatMap { tok =>
        repo
          .findByTokenHash(Sha256Hex.unsafe(RouterAuth.sha256Hex(tok)))
          .mapError(ErrorMapper.dbErrorToResponse)
          .flatMap(
            ZIO.fromOption(_).orElseFail(Response.unauthorized("Invalid router token")),
          )
      }
}
