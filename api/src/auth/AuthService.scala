package wifihaven.api.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import wifihaven.api.JwtConfig
import wifihaven.api.db.*
import wifihaven.api.metrics.AppMetrics
import wifihaven.shared.*
import wifihaven.shared.types.*
import pdi.jwt.*
import pdi.jwt.algorithms.JwtHmacAlgorithm
import zio.{Clock as _, *}
import zio.json.*

// ── JWT Claims ─────────────────────────────────────────────────────────────

case class JwtClaims(
    sub: String,  // username
    role: String, // admin | readonly
    iat: Long,
    exp: Long,
) derives JsonCodec

// ── Auth errors ────────────────────────────────────────────────────────────

sealed trait AuthError
object AuthError {
  case object InvalidCredentials     extends AuthError
  case object TokenExpired           extends AuthError
  case object InvalidToken           extends AuthError
  case object Forbidden              extends AuthError
  case class Unexpected(msg: String) extends AuthError
}

// ── Auth service ───────────────────────────────────────────────────────────

trait AuthService {
  def login(username: String, password: String): IO[AuthError, LoginResponse]
  def verify(token: String): IO[AuthError, JwtClaims]
  def requireAdmin(token: String): IO[AuthError, JwtClaims]
  def requireWriter(token: String): IO[AuthError, JwtClaims]

  /**
   * Verify token and also check that must_change_password is not set. Returns Forbidden if the flag
   * is set (caller should 403 with {"error":"password_change_required"}).
   */
  def requirePasswordChanged(token: String): IO[AuthError, JwtClaims]

  def changePassword(username: String, current: String, next: String): IO[AuthError, Unit]
  def hashPassword(password: String): UIO[String]
}

class AuthServiceLive(
    userRepo: UserRepo,
    jwtConfig: JwtConfig,
    clock: Clock,
) extends AuthService {

  private val algo: JwtHmacAlgorithm = JwtAlgorithm.HS256
  private val secret                 = jwtConfig.secret

  def login(username: String, password: String): IO[AuthError, LoginResponse] =
    (for {
      user  <- userRepo
        .findByUsername(username)
        .mapError(e => AuthError.Unexpected(e.getMessage))
        .flatMap(ZIO.fromOption(_).mapError(_ => AuthError.InvalidCredentials))
      valid <- ZIO.succeed(
        BCrypt.verifyer().verify(password.toCharArray, user.passwordHash).verified,
      )
      _     <- ZIO.fail(AuthError.InvalidCredentials).when(!valid)
      now   <- clock.instant.map(_.getEpochSecond)
      claim = JwtClaim(
        content = s"""{"role":"${UserRole.asString(user.role)}"}""",
        subject = Some(user.username),
        issuedAt = Some(now),
        expiration = Some(now + jwtConfig.expiryHours * 3600L),
      )
      token <- ZIO
        .attempt(JwtZIOJson.encode(claim, secret, algo))
        .mapError(e => AuthError.Unexpected(e.getMessage))
    } yield LoginResponse(
      JwtToken.unsafe(token),
      user.role,
      user.username,
      user.mustChangePassword,
    ))
      .tapError {
        // #1204: a bad password or unknown user both collapse to InvalidCredentials.
        case AuthError.InvalidCredentials => AppMetrics.recordAuthFailure("bad_password")
        case _                            => ZIO.unit
      }

  // We delegate expiration/not-before checks to our injected Clock (see below).
  private val jwtOpts = JwtOptions(expiration = false, notBefore = false)

  def verify(token: String): IO[AuthError, JwtClaims] =
    ZIO
      .fromTry(JwtZIOJson.decode(token, secret, Seq(algo), jwtOpts))
      .mapError(_ => AuthError.InvalidToken)
      .flatMap { claim =>
        ZIO
          .fromEither(claim.content.fromJson[Map[String, String]])
          .mapError(_ => AuthError.InvalidToken)
          .map { m =>
            JwtClaims(
              sub = claim.subject.getOrElse(""),
              role = m.getOrElse("role", ""),
              iat = claim.issuedAt.getOrElse(0L),
              exp = claim.expiration.getOrElse(0L),
            )
          }
      }
      .flatMap { claims =>
        clock.instant.flatMap { i =>
          val now = i.getEpochSecond
          ZIO.fail(AuthError.TokenExpired).when(claims.exp < now).as(claims)
        }
      }
      .tapError {
        // #1204: only the expired case is a bounded, security-relevant reason. A
        // malformed/forged token (InvalidToken) is not in the §5.2 reason enum.
        case AuthError.TokenExpired => AppMetrics.recordAuthFailure("expired_token")
        case _                      => ZIO.unit
      }

  // #1204: a role check that fails Forbidden is the forbidden_role signal. verify's
  // own failures (expired_token) are already counted above, so they don't surface as
  // Forbidden here — no double counting.
  private def tapForbiddenRole(z: IO[AuthError, JwtClaims]): IO[AuthError, JwtClaims] =
    z.tapError {
      case AuthError.Forbidden => AppMetrics.recordAuthFailure("forbidden_role")
      case _                   => ZIO.unit
    }

  def requireAdmin(token: String): IO[AuthError, JwtClaims] =
    tapForbiddenRole(verify(token).flatMap { claims =>
      if claims.role == "admin" then ZIO.succeed(claims)
      else ZIO.fail(AuthError.Forbidden)
    })

  def requireWriter(token: String): IO[AuthError, JwtClaims] =
    tapForbiddenRole(verify(token).flatMap { claims =>
      if claims.role == "admin" || claims.role == "adult" then ZIO.succeed(claims)
      else ZIO.fail(AuthError.Forbidden)
    })

  def requirePasswordChanged(token: String): IO[AuthError, JwtClaims] =
    verify(token).flatMap { claims =>
      userRepo
        .findByUsername(claims.sub)
        .mapError(e => AuthError.Unexpected(e.getMessage))
        .flatMap {
          case Some(user) if user.mustChangePassword => ZIO.fail(AuthError.Forbidden)
          case _                                     => ZIO.succeed(claims)
        }
    }

  def changePassword(username: String, current: String, next: String): IO[AuthError, Unit] =
    for {
      user  <- userRepo
        .findByUsername(username)
        .mapError(e => AuthError.Unexpected(e.getMessage))
        .flatMap(ZIO.fromOption(_).mapError(_ => AuthError.InvalidCredentials))
      valid <- ZIO.succeed(
        BCrypt.verifyer().verify(current.toCharArray, user.passwordHash).verified,
      )
      _     <- ZIO.fail(AuthError.InvalidCredentials).when(!valid)
      hash  <- hashPassword(next)
      _     <- userRepo
        .updatePassword(user.id, hash)
        .mapError(e => AuthError.Unexpected(e.getMessage))
      // Clear must_change_password flag on successful rotation (#586).
      _     <- userRepo
        .clearMustChangePassword(user.id)
        .mapError(e => AuthError.Unexpected(e.getMessage))
    } yield ()

  def hashPassword(password: String): UIO[String] =
    ZIO.succeed(BCrypt.withDefaults().hashToString(12, password.toCharArray))
}

object AuthService {
  val layer: ZLayer[UserRepo & JwtConfig & Clock, Nothing, AuthService] =
    ZLayer.fromFunction(AuthServiceLive(_, _, _))
}
