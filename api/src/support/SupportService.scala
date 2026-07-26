package wifihaven.api.support

import wifihaven.api.SupportConfig
import wifihaven.api.auth.JwtClaims
import wifihaven.api.db.{HouseholdBillingRepo, HouseholdRepo, UserRepo}
import wifihaven.api.metrics.AppMetrics
import wifihaven.shared.SupportIdentityResponse
import wifihaven.shared.types.HouseholdId
import zio.*

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.charset.StandardCharsets

/**
 * #2199 (support intake B, epic #2197) — the household-gated Plain support integration
 * (design/umbrella #2206 §2). Two responsibilities, both keyed on the AUTHENTICATED caller's own
 * household (from their JWT — never a client-supplied household/email).
 *
 * The primary job is [[identity]]: return the SERVER-SIGNED widget identity for the logged-in admin
 * — the Plain chat-auth HMAC over the caller's OWN email + their household as `tenantIdentifier`.
 * Because the hash is computed server-side over the JWT-resolved email, household A can never
 * obtain household B's widget identity — the household-gating boundary (#2199 injection/security
 * note). It is dark when the widget is unconfigured OR the caller has no email.
 *
 * As a side-effect of a first identity call, it also does a best-effort lazy
 * [[PlainClient.upsertCustomer]] carrying the household→Plain-customer mapping (`tenantIdentifier =
 * household_id`, `externalId`, plan/entitlement attributes). Fail-open: the upsert can't fail the
 * identity response.
 *
 * `PlainClient` is the only external I/O and is stubbed in specs (docs/process/testing.md — mock
 * ONLY external I/O; repos stay real). Everything policy-shaped (the widget gate, the HMAC, the
 * entitlement lookup) is pure/DB-backed so it's feature-tested without a live Plain.
 */
final case class SupportService(
    cfg: SupportConfig,
    userRepo: UserRepo,
    householdRepo: HouseholdRepo,
    billingRepo: HouseholdBillingRepo,
    plain: PlainClient,
) {

  /**
   * Resolve the server-signed widget identity for the authenticated caller. Never fails (a DB blip
   * on the best-effort context lookups degrades to a still-valid identity or the dark response).
   */
  def identity(claims: JwtClaims): UIO[SupportIdentityResponse] =
    // Gate 1: widget unconfigured (no app id / no identity secret) ⇒ dark, no work, no Plain call.
    // #2429: BOTH dark responses still carry `supportEmail` — the chat widget being off does not
    // make the support inbox unreachable, so the SPA degrades to the email-only affordance.
    if !cfg.plain.widgetEnabled then
      AppMetrics
        .supportIdentity("disabled")
        .as(SupportIdentityResponse.disabled(cfg.supportEmailOpt))
    else
      resolveEmail(claims.hh, claims.sub).flatMap {
        // Gate 2: caller has no email ⇒ not identifiable ⇒ dark (username-only / self-hosted admin).
        case None        =>
          AppMetrics
            .supportIdentity("no_email")
            .as(SupportIdentityResponse.disabled(cfg.supportEmailOpt))
        case Some(email) =>
          for {
            household <- householdRepo.findById(claims.hh).catchAll(_ => ZIO.none)
            billing   <- billingRepo.findByHousehold(claims.hh).catchAll(_ => ZIO.none)
            // The chat-auth hash is over the caller's OWN lowercased email — the impersonation guard.
            hash = SupportService.hmacSha256Hex(cfg.plain.identitySecretTrimmed, email.toLowerCase)
            fullName = household.map(_.name).getOrElse("")
            tenant   = claims.hh.value.toString
            // Lazy household→customer upsert (best-effort). Awaited but fail-open: PlainClient never
            // fails and no-ops when the write API is unconfigured, so this can't break the response.
            _ <- upsertCustomer(
              externalId = tenant,
              tenantIdentifier = tenant,
              email = email,
              fullName = fullName,
              plan = billing.map(_.status),
              founding = billing.map(_.founding),
            )
            _ <- AppMetrics.supportIdentity("issued")
          } yield SupportIdentityResponse(
            configured = true,
            appId = Some(cfg.plain.appIdTrimmed),
            email = Some(email),
            emailHash = Some(hash),
            tenantIdentifier = Some(tenant),
            fullName = Some(fullName),
            plan = billing.map(_.status),
            founding = billing.map(_.founding),
            supportEmail = cfg.supportEmailOpt,
          )
      }

  // A DB failure resolving the caller's email must not crash the request — treat it as "no email"
  // (dark), same as a genuinely email-less user. The identity endpoint is background context.
  private def resolveEmail(hh: HouseholdId, username: String): UIO[Option[String]] =
    userRepo
      .emailForUser(hh, username)
      .catchAll(e =>
        ZIO.logWarning(s"support: could not resolve email for $username: ${e.getMessage}").as(None),
      )

  private def upsertCustomer(
      externalId: String,
      tenantIdentifier: String,
      email: String,
      fullName: String,
      plan: Option[String],
      founding: Option[Boolean],
  ): UIO[Unit] = {
    // Bounded account context only (plan / entitlement / household name) — NEVER per-device or
    // per-domain data. `plan` is the billing status (beta / active / lapsed, #2135).
    val attributes = Map.newBuilder[String, String]
    plan.foreach(p => attributes += ("plan" -> p))
    founding.foreach(f => attributes += ("founding" -> f.toString))
    if fullName.nonEmpty then attributes += ("householdName" -> fullName)
    plain
      .upsertCustomer(
        PlainCustomerUpsert(
          externalId = externalId,
          tenantIdentifier = tenantIdentifier,
          email = email,
          fullName = fullName,
          attributes = attributes.result(),
        ),
      )
      .flatMap(o => AppMetrics.supportCustomerUpsert(PlainOutcome.label(o)))
  }
}

object SupportService {

  val layer: ZLayer[
    SupportConfig & UserRepo & HouseholdRepo & HouseholdBillingRepo & PlainClient,
    Nothing,
    SupportService,
  ] =
    ZLayer.fromFunction(SupportService.apply)

  /**
   * HMAC-SHA256 of `message` under `secret`, lowercase-hex encoded — Plain "chat authentication"
   * (https://help.plain.com/article/chat-authentication). Pure; the caller lowercases the email so
   * the hash is stable across differently-cased login state.
   */
  def hmacSha256Hex(secret: String, message: String): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
    val raw = mac.doFinal(message.getBytes(StandardCharsets.UTF_8))
    raw.map(b => f"$b%02x").mkString
  }
}
