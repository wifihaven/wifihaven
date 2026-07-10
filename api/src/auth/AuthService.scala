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
    // #2105 (multi-tenant sub-issue B): the authenticated user's household
    // (users.household_id, V65), minted at login and read back here. Additive
    // alongside tokenVersion; both are HMAC-protected by the existing signature,
    // so a client cannot forge hh any more than role. Consumed by household-scoped
    // repo reads in sub-issue E (#2108). Defaults to household 1 — the default
    // tenant — for pre-#2105 tokens (minted before the claim existed).
    hh: HouseholdId = HouseholdId.Default,
) derives JsonCodec

// Wire shape of the JWT `content` claim. `tv` defaults to 0 so a pre-#2080 token
// (minted before this field existed) decodes cleanly instead of failing to parse.
// #2105: `hh` defaults to household 1 so a pre-#2105 token (no hh field) decodes to
// the default tenant instead of failing to parse.
private case class JwtContent(role: String, tv: Int = 0, hh: HouseholdId = HouseholdId.Default)
    derives JsonCodec

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
  // #2140 (multi-tenant P5-8): `householdSlug` names the household to authenticate against
  // (`households.slug`, V66). Absent/blank → the default household, so self-hosted single-household
  // deploys and pre-existing API clients keep exactly today's behavior (back-compat). An unknown
  // slug, or a valid username+password in the WRONG household, fails with the SAME
  // `InvalidCredentials` a bad password produces — no household/username enumeration.
  def login(
      username: String,
      password: String,
      householdSlug: Option[String] = None,
  ): IO[AuthError, LoginResponse]
  def verify(token: String): IO[AuthError, JwtClaims]
  def requireAdmin(token: String): IO[AuthError, JwtClaims]
  def requireWriter(token: String): IO[AuthError, JwtClaims]

  /**
   * Verify token and also check that must_change_password is not set. Returns Forbidden if the flag
   * is set (caller should 403 with {"error":"password_change_required"}).
   */
  def requirePasswordChanged(token: String): IO[AuthError, JwtClaims]

  // #2140: `household` scopes the user lookup (V65 UNIQUE(household_id, username)). Authenticated
  // callers pass `claims.hh`; defaults to the default household for back-compat.
  def changePassword(
      username: String,
      current: String,
      next: String,
      household: HouseholdId = HouseholdId.Default,
  ): IO[AuthError, Unit]
  def hashPassword(password: String): UIO[String]
}

class AuthServiceLive(
    userRepo: UserRepo,
    jwtConfig: JwtConfig,
    clock: Clock,
    // #2140: resolves the login request's household slug → tenancy key. Defaults to a DB-free
    // default-household-only repo so every pre-multi-tenant `AuthServiceLive(userRepo, jwtConfig,
    // clock)` construction keeps compiling and resolves the single self-hosted household.
    householdRepo: HouseholdRepo = HouseholdRepo.defaultOnly,
) extends AuthService {

  private val algo: JwtHmacAlgorithm = JwtAlgorithm.HS256
  private val secret                 = jwtConfig.secret

  // #2140: a fixed valid bcrypt hash used ONLY for timing equalization. When the (household,
  // username) lookup finds no user — unknown username, or a real username presented against the
  // wrong household — we still run a bcrypt verify against this hash so the failure's timing shape
  // matches a genuine wrong-password (which does run bcrypt). This closes the username/household
  // enumeration oracle required by #2140 scope item 3. The verify always returns false; the value
  // is never a real credential.
  private val timingDummyHash: String =
    BCrypt.withDefaults().hashToString(12, "wifihaven-timing-equalizer".toCharArray)

  def login(
      username: String,
      password: String,
      householdSlug: Option[String] = None,
  ): IO[AuthError, LoginResponse] =
    // #602: route/method (`POST /api/auth/login`) ride in via LoggingMiddleware on
    // the HTTP path. `user` is the only per-call dynamic context we add here, so
    // every log inside the for-comprehension (success info, bad-password warn)
    // inherits it via FiberRef.
    LogContext.annotate(LogContext.User, username) {
      (for {
        // #2140: resolve the requested household. Absent/blank → default household (back-compat).
        // A present-but-unknown slug yields None so the user lookup finds nothing — the SAME
        // failure path as an unknown username (no household enumeration).
        hhOpt   <- householdSlug.map(_.trim).filter(_.nonEmpty) match {
          case None       => ZIO.some(HouseholdId.Default)
          case Some(slug) =>
            householdRepo.findIdBySlug(slug).mapError(e => AuthError.Unexpected(e.getMessage))
        }
        userOpt <- hhOpt match {
          case None     => ZIO.none
          case Some(hh) =>
            userRepo.findByUsername(hh, username).mapError(e => AuthError.Unexpected(e.getMessage))
        }
        // #2140: always run bcrypt — against the real hash if the user exists, against a fixed
        // dummy hash otherwise — so an unknown username / wrong household is timing-indistinguishable
        // from a wrong password. The dummy path always yields false.
        valid = userOpt match {
          case Some(u) => BCrypt.verifyer().verify(password.toCharArray, u.passwordHash).verified
          case None    =>
            BCrypt.verifyer().verify(password.toCharArray, timingDummyHash.toCharArray).verified
        }
        user    <- ZIO
          .fromOption(userOpt.filter(_ => valid))
          .mapError(_ => AuthError.InvalidCredentials)
        now     <- clock.instant.map(_.getEpochSecond)
        claim = JwtClaim(
          // #2080: stamp the user's CURRENT token_version so a subsequent password
          // change (which bumps it) invalidates this token on its next verify().
          content =
            JwtContent(UserRole.asString(user.role), user.tokenVersion, user.householdId).toJson,
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
              hh = c.hh,
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
        // #2140: resolve within the token's own household (`claims.hh`) — usernames are only
        // unique per household now, so a bare-username lookup could hit a different tenant's user.
        userRepo
          .findByUsername(claims.hh, claims.sub)
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
        // #2140: scope to the token's household — usernames are unique per household only.
        .findByUsername(claims.hh, claims.sub)
        .mapError(e => AuthError.Unexpected(e.getMessage))
        .flatMap {
          case Some(user) if user.mustChangePassword => ZIO.fail(AuthError.Forbidden)
          case _                                     => ZIO.succeed(claims)
        }
    }

  def changePassword(
      username: String,
      current: String,
      next: String,
      household: HouseholdId = HouseholdId.Default,
  ): IO[AuthError, Unit] =
    for {
      user  <- userRepo
        // #2140: scope to the caller's household (route passes `claims.hh`).
        .findByUsername(household, username)
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
  // #2140: HouseholdRepo added so login can resolve the request's household slug → tenancy key.
  val layer: ZLayer[UserRepo & HouseholdRepo & JwtConfig & Clock, Nothing, AuthService] =
    ZLayer.fromFunction(AuthServiceLive(_, _, _, _))

  // #2084: minimum password length enforced on both create-user and
  // change-password — previously neither path validated strength at all.
  val MinPasswordLength: Int = 12

  def isPasswordStrongEnough(password: String): Boolean =
    password.length >= MinPasswordLength
}
