package wifihaven.api.beta

import wifihaven.api.BetaConfig
import wifihaven.api.auth.AuthService
import wifihaven.api.db.*
import wifihaven.api.notify.Notifier
import wifihaven.api.policy.PolicyService
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock
import zio.*

import java.security.SecureRandom
import java.util.Base64

/**
 * #2132 (multi-tenant P5-2, epic #622): the non-SQL orchestration of the beta pipeline (design
 * docs/design/multi-tenant-launch.md §3) — slug derivation, invite-token mint + TTL, provisioning
 * (via the atomic [[BetaRequestRepo.approveAndProvision]] transaction), notification, and invite
 * acceptance (the second-household admin bootstrap). Route handlers stay thin over this so the "how
 * a household comes into existence" logic lives in exactly one place.
 *
 * `Clock` is injected so the invite-TTL boundary is TestClock-driven (design §3.4 / #2132 scope 5).
 */
final case class BetaService(
    betaRepo: BetaRequestRepo,
    householdRepo: HouseholdRepo,
    userRepo: UserRepo,
    auth: AuthService,
    notifier: Notifier,
    clock: Clock,
    cfg: BetaConfig,
) {
  import BetaService.*

  /**
   * Provision on approve (design §3.3), returning the invite URL + slug for the operator to send.
   */
  def approve(request: DbBetaRequest, decidedBy: UserId): IO[BetaError, ApproveBetaResponse] =
    for {
      _        <- ZIO.fail(BetaError.NotPending).when(request.status != BetaRequestStatus.Pending)
      slug     <- uniqueSlug(baseSlug(request))
      rawToken <- ZIO.succeed(newInviteToken())
      hash = PolicyService.hashToken(rawToken)
      now <- clock.instant
      expiresAt = now.plus(cfg.inviteTtl)
      // The atomic transaction: households row + household_billing row (status='beta',
      // founding=true — Stripe Customer creation is #2135's seam) + the request stamp.
      hid <- betaRepo
        .approveAndProvision(
          id = request.id,
          decidedBy = decidedBy,
          householdName = householdName(request),
          slug = slug,
          routerCap = DefaultRouterCap,
          billingStatus = BetaBillingStatus,
          founding = true,
          inviteTokenHash = hash,
          inviteExpiresAt = expiresAt,
          decidedAt = now,
        )
        .mapError {
          case _: IllegalStateException => BetaError.NotPending
          case e                        => BetaError.Db(e)
        }
      _   <- notifier.betaHouseholdProvisioned(request.email, slug, hid)
    } yield ApproveBetaResponse(
      householdId = hid,
      slug = slug,
      inviteUrl = cfg.inviteUrl(rawToken),
      inviteExpiresAt = expiresAt.toString,
    )

  /**
   * Accept an invite (design §3.4): validate the single-use, unexpired token, create the new
   * household's FIRST admin user in that household, and invalidate the token. The admin picks their
   * own password here, so `must_change_password` is cleared (unlike the admin-created-user path,
   * which forces a first-login change).
   */
  def accept(req: AcceptInviteRequest): IO[BetaError, AcceptInviteResponse] =
    for {
      _ <- ZIO
        .fail(BetaError.WeakPassword)
        .when(!AuthService.isPasswordStrongEnough(req.password))
      hash = PolicyService.hashToken(req.token)
      // A wrong/forged/already-consumed token resolves to nothing → InvalidToken (never distinguish
      // "unknown" from "already used" — no enumeration signal).
      request <- betaRepo
        .findByInviteHash(hash)
        .mapError(BetaError.Db(_))
        .someOrFail(BetaError.InvalidToken)
      // The row must be an approved, provisioned request with an unexpired token.
      hid     <- ZIO
        .fromOption(request.householdId)
        .orElseFail(BetaError.InvalidToken)
        .filterOrFail(_ => request.status == BetaRequestStatus.Approved)(BetaError.InvalidToken)
      now     <- clock.instant
      _       <- ZIO
        .fail(BetaError.Expired)
        .when(request.inviteExpiresAt.exists(now.isAfter))
      slug    <- householdRepo
        .findById(hid)
        .mapError(BetaError.Db(_))
        .someOrFail(BetaError.InvalidToken)
        .map(_.slug.getOrElse(""))
      pwHash  <- auth.hashPassword(req.password)
      uid     <- userRepo
        .create(req.username, pwHash, UserRole.asString(UserRole.Admin), hid)
        .mapError(BetaError.Db(_))
      // The admin set their own password → don't force a first-login change.
      _       <- userRepo.clearMustChangePassword(uid).mapError(BetaError.Db(_))
      // Single-use: burn the token so it can't be replayed (design §3.4 / #2132 scope 5).
      _       <- betaRepo.consumeInvite(request.id).mapError(BetaError.Db(_))
    } yield AcceptInviteResponse(householdId = hid, slug = slug, username = req.username)

  // Base slug from the requested household name (falling back to the email local-part).
  private def baseSlug(r: DbBetaRequest): String =
    slugify(r.name.map(_.trim).filter(_.nonEmpty).getOrElse(r.email.takeWhile(_ != '@')))

  private def householdName(r: DbBetaRequest): String =
    r.name.map(_.trim).filter(_.nonEmpty).getOrElse(r.email)

  // De-duplicate against existing slugs by appending -2, -3, … (design §3.3). Operator-serial in
  // v1; the `households.slug` UNIQUE constraint is the backstop if two approvals ever race.
  private def uniqueSlug(base: String): IO[BetaError, String] = {
    def attempt(n: Int): IO[BetaError, String] = {
      val candidate = if n == 1 then base else s"$base-$n"
      householdRepo
        .findBySlug(candidate)
        .mapError(BetaError.Db(_))
        .flatMap {
          case None    => ZIO.succeed(candidate)
          case Some(_) =>
            if n >= MaxSlugAttempts then ZIO.fail(BetaError.SlugExhausted) else attempt(n + 1)
        }
    }
    attempt(1)
  }
}

object BetaService {
  val DefaultRouterCap: Int     = 1
  val BetaBillingStatus: String = "beta"
  private val MaxSlugAttempts   = 1000

  /** Lowercase, ASCII-`[a-z0-9-]`-only slug; non-alnum runs collapse to single dashes. */
  def slugify(raw: String): String = {
    val mapped    = raw.trim.toLowerCase.map(c =>
      if (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') then c else '-',
    )
    val collapsed = mapped.split("-").iterator.filter(_.nonEmpty).mkString("-")
    if collapsed.isEmpty then "household" else collapsed
  }

  private def newInviteToken(): String = {
    val bytes = new Array[Byte](32)
    new SecureRandom().nextBytes(bytes)
    "bt_" + Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
  }
}

/** Typed failures the beta routes map to HTTP statuses. */
sealed trait BetaError
object BetaError {
  case object NotPending    extends BetaError // approve/reject a non-pending request
  case object InvalidToken  extends BetaError // accept: unknown / consumed / wrong token
  case object Expired       extends BetaError // accept: token past its TTL
  case object WeakPassword  extends BetaError // accept: password below the #2084 minimum
  case object SlugExhausted extends BetaError // couldn't derive a unique slug (should never happen)
  case class Db(cause: Throwable) extends BetaError
}
