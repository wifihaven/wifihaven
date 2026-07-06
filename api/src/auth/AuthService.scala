package wifihaven.api.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import wifihaven.api.JwtConfig
import wifihaven.api.db.*
import wifihaven.api.metrics.AppMetrics
import wifihaven.api.observability.LogContext
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
    // #2080: the user's token_version at issuance time. verify() rejects a token
    // whose stamped version is behind the user's CURRENT token_version (bumped on
    // every password change) — this is what makes password change actually
    // invalidate previously-issued sessions. 0 for tokens minted before this field
    // existed, which compares equal to a never-changed user's token_version.
    tokenVersion: Int = 0,
) derives JsonCodec

// Wire shape of the JWT `content` claim. `tv` defaults to 0 so a pre-#2080 token
// (minted before this field existed) decodes cleanly instead of failing to parse.
private case class JwtContent(role: String, tv: Int = 0) derives JsonCodec

// ── Auth errors ────────────────────────────────────────────────────────────

sealed trait AuthError
object AuthError {
  case object InvalidCredentials     extends AuthError
  case object TokenExpired           extends AuthError
  // #2080: token is well-formed and unexpired, but was issued before the user's
  // last password change (token_version rolled forward since issuance).
  case object TokenRevoked           extends AuthError
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
    // #602: route/method (`POST /api/auth/login`) ride in via LoggingMiddleware on
    // the HTTP path. `user` is the only per-call dynamic context we add here, so
    // every log inside the for-comprehension (success info, bad-password warn)
    // inherits it via FiberRef.
    LogContext.annotate(LogContext.User, username) {
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
          // #2080: stamp the user's CURRENT token_version so a subsequent password
          // change (which bumps it) invalidates this token on its next verify().
          content = JwtContent(UserRole.asString(user.role), user.tokenVersion).toJson,
          subject = Some(user.username),
          issuedAt = Some(now),
          expiration = Some(now + jwtConfig.expiryHours * 3600L),
        )
        token <- ZIO
          .attempt(JwtZIOJson.encode(claim, secret, algo))
          .mapError(e => AuthError.Unexpected(e.getMessage))
        // #602: explicit success log (the boundary only logs failures). Bounded — one line
        // per successful login, MDC-annotated with the user + role for searchability.
        _     <- LogContext.annotate(LogContext.Reason, "ok") {
          ZIO.logInfo(s"login ok: user=$username role=${UserRole.asString(user.role)}")
        }
      } yield LoginResponse(
        JwtToken.unsafe(token),
        user.role,
        user.username,
        user.mustChangePassword,
      ))
        .tapError {
          // #1204: a bad password or unknown user both collapse to InvalidCredentials.
          // #602: the message text duplicates the annotation values (user=, reason=) on
          // purpose — same back-compat strategy as ErrorBoundary.scala. Existing log
          // readers grep substrings; the MDC keys are additive for structured consumers.
          // Don't drop the substrings until the JSON appender lands.
          case AuthError.InvalidCredentials =>
            AppMetrics.recordAuthFailure("bad_password") *>
              LogContext.annotate(LogContext.Reason, "bad_password") {
                ZIO.logWarning(s"login failed: user=$username reason=bad_password")
              }
          case _                            => ZIO.unit
        }
    }

  // We delegate expiration/not-before checks to our injected Clock (see below).
  private val jwtOpts = JwtOptions(expiration = false, notBefore = false)

  def verify(token: String): IO[AuthError, JwtClaims] =
    ZIO
      .fromTry(JwtZIOJson.decode(token, secret, Seq(algo), jwtOpts))
      .mapError(_ => AuthError.InvalidToken)
      .flatMap { claim =>
        ZIO
          .fromEither(claim.content.fromJson[JwtContent])
          .mapError(_ => AuthError.InvalidToken)
          .map { c =>
            JwtClaims(
              sub = claim.subject.getOrElse(""),
              role = c.role,
              iat = claim.issuedAt.getOrElse(0L),
              exp = claim.expiration.getOrElse(0L),
              tokenVersion = c.tv,
            )
          }
      }
      .flatMap { claims =>
        clock.instant.flatMap { i =>
          val now = i.getEpochSecond
          ZIO.fail(AuthError.TokenExpired).when(claims.exp < now).as(claims)
        }
      }
      .flatMap { claims =>
        // #2080: reject a token stamped with an older token_version than the
        // user's current one — the effect of a password change (which bumps it)
        // invalidating every session minted before the change.
        userRepo
          .findByUsername(claims.sub)
          .mapError(e => AuthError.Unexpected(e.getMessage))
          .flatMap {
            case Some(user) if user.tokenVersion > claims.tokenVersion =>
              ZIO.fail(AuthError.TokenRevoked)
            case _                                                     => ZIO.succeed(claims)
          }
      }
      .tapError {
        // #1204: only these are bounded, security-relevant reasons. A malformed/forged
        // token (InvalidToken) is not in the §5.2 reason enum.
        case AuthError.TokenExpired => AppMetrics.recordAuthFailure("expired_token")
        case AuthError.TokenRevoked => AppMetrics.recordAuthFailure("revoked_session")
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
      // #2080: invalidate every previously-issued JWT for this user.
      _     <- userRepo
        .bumpTokenVersion(user.id)
        .mapError(e => AuthError.Unexpected(e.getMessage))
    } yield ()

  def hashPassword(password: String): UIO[String] =
    ZIO.succeed(BCrypt.withDefaults().hashToString(12, password.toCharArray))
}

object AuthService {
  val layer: ZLayer[UserRepo & JwtConfig & Clock, Nothing, AuthService] =
    ZLayer.fromFunction(AuthServiceLive(_, _, _))

  // #2084: minimum password length enforced on both create-user and
  // change-password — previously neither path validated strength at all.
  val MinPasswordLength: Int = 12

  def isPasswordStrongEnough(password: String): Boolean =
    password.length >= MinPasswordLength
}
