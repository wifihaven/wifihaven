package wifihaven.api.support

import wifihaven.shared.types.HouseholdId

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

/**
 * #2419 — the CONSENT-REQUEST token: the capability that rides in the consent link the SERVER posts
 * into a support thread when the agent asks for data access. It is NOT a credential the agent can
 * use; it is a signed statement of "this household + this thread were asked for consent, before
 * this deadline", which the CUSTOMER redeems from their authenticated dashboard session.
 *
 * The security split this type exists to enforce (docs/ops/support-data-consent.md):
 *   - the agent can REQUEST consent with its thread-bound [[ConsentToken]] — the server then posts
 *     the prompt + link into that ONE thread;
 *   - only the customer, authenticated by their normal session JWT, can GRANT it: `POST
 *     /api/support/consent` requires BOTH this token and a JWT whose household matches it.
 * Requesting and having are different privileges backed by different credentials, so a hijacked or
 * prompt-injected agent cannot widen its own data scope.
 *
 * #2453 — the link is SINGLE-USE and cannot outlive a withdrawal. Two fields carry that:
 *   - `nonce`, a fresh 128-bit random per link. Redemption CONSUMES it (`support_consent_link_use`,
 *     V85), so a captured link cannot be replayed to re-grant access. Replay is not merely refused,
 *     it cannot WRITE: the ledger's primary key decides consumption inside the same transaction as
 *     the grant, so two concurrent redemptions of one link can never both grant.
 *   - `issuedAt`, the mint instant. A grant whose link predates the record's `revoked_at` is
 *     refused, so an unredeemed link outstanding at the moment the customer withdrew cannot undo
 *     the withdrawal. Derived from `exp - ttl` would couple the verifier to the TTL constant, so it
 *     rides explicitly.
 *
 * Both are in the SIGNED payload, so neither can be edited by whoever holds the link.
 *
 * Wire shape mirrors [[ConsentToken]] — `g1.<b64url(payload)>.<hmacHex>` over
 * `householdId|threadId|iatEpochSeconds|expEpochSeconds|nonce` — but with a DISTINCT `g1` version
 * prefix, signed under the same agent-token secret. The prefix is domain separation and it is
 * CRYPTOGRAPHIC, not cosmetic: the MAC is computed over `"<version>.<b64>"`, so re-labelling a `v1`
 * agent token as `g1` (or the reverse) invalidates the signature outright — the version is bound
 * into the MAC, not merely compared. Do not "simplify" either side to MAC the payload alone: that
 * would leave the separation resting on a payload-parse accident. A consent link therefore can
 * never be replayed as an agent credential or vice-versa. Pinned by SupportConsentSpec.
 */
object ConsentGrant {

  /**
   * The version prefix this scheme mints with. PUBLIC because
   * [[wifihaven.api.agent.AgentCredential]] derives its outbound redaction pattern from it rather
   * than re-typing the literal (#2508) — this stays this scheme's OWN constant, so bumping it here
   * cannot silently re-version a sibling token scheme.
   */
  val Version: String = "g1"

  sealed trait Err
  object Err {
    case object Malformed    extends Err
    case object BadSignature extends Err
    case object Expired      extends Err
  }

  /**
   * The household + thread a redeemed consent link authorises a grant for, plus the #2453
   * single-use fields: when the link was minted and the nonce redemption consumes.
   */
  final case class Claims(
      householdId: HouseholdId,
      threadId: String,
      issuedAt: Instant,
      expiresAt: Instant,
      nonce: String,
  )

  /**
   * A fresh link nonce — [[java.security.SecureRandom]], url-safe base64 (so it carries no `|` and
   * needs no escaping in the payload). Same CONSTRUCTION as the enrollment / password-reset tokens,
   * but 128 bits where those use 256: this is a uniqueness token INSIDE a signed payload, not a
   * bearer secret — the HMAC is the authenticator, and guessing a nonce grants nothing. 128 bits is
   * far past collision relevance for a human-scale table.
   */
  def newNonce(): String = {
    val bytes = new Array[Byte](16)
    new java.security.SecureRandom().nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
  }

  /** Mint a consent-request token for `household` + `thread`, valid until `now + ttl`. */
  def mint(
      household: HouseholdId,
      threadId: String,
      now: Instant,
      ttl: java.time.Duration,
      secret: String,
      nonce: String,
  ): String = {
    val exp     = now.plus(ttl).getEpochSecond
    val payload =
      s"${household.value}|${sanitize(threadId)}|${now.getEpochSecond}|$exp|${sanitize(nonce)}"
    val b64     =
      Base64.getUrlEncoder.withoutPadding.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
    s"$Version.$b64.${hmacHex(secret, s"$Version.$b64")}"
  }

  /**
   * Verify a consent-request token and check it hasn't expired as of `now`. Tampering fails
   * BadSignature; a stale link fails Expired. The caller MUST additionally require that the
   * redeeming session's household equals `Claims.householdId` — this token proves which thread was
   * asked, not who is asking.
   *
   * #2453 widened the payload from three fields to five. A link minted by the PREVIOUS image
   * therefore fails [[Err.Malformed]] rather than verifying without a nonce — fail-CLOSED, and
   * bounded by the link TTL: the customer is told the link is no longer valid and the assistant can
   * mint a new one. Deliberately not made tolerant; accepting a nonce-less link would leave exactly
   * the replay hole this change closes, for as long as any old link survives.
   */
  def verify(token: String, now: Instant, secret: String): Either[Err, Claims] =
    token.split("\\.", 3) match {
      case Array(Version, b64, sig) =>
        if !constantTimeEquals(sig, hmacHex(secret, s"$Version.$b64")) then Left(Err.BadSignature)
        else
          decode(b64).flatMap { case (household, thread, iat, exp, nonce) =>
            if now.getEpochSecond > exp then Left(Err.Expired)
            else
              Right(
                Claims(
                  HouseholdId(household),
                  thread,
                  Instant.ofEpochSecond(iat),
                  Instant.ofEpochSecond(exp),
                  nonce,
                ),
              )
          }
      case _                        => Left(Err.Malformed)
    }

  private def decode(b64: String): Either[Err, (Long, String, Long, Long, String)] =
    scala.util
      .Try {
        val raw = new String(Base64.getUrlDecoder.decode(b64), StandardCharsets.UTF_8)
        raw.split("\\|", 5) match {
          case Array(h, t, i, e, n) if n.nonEmpty => (h.toLong, t, i.toLong, e.toLong, n)
          case _ => throw new IllegalArgumentException("bad payload")
        }
      }
      .toEither
      .left
      .map(_ => Err.Malformed)

  // Thread ids are Plain ULIDs (no `|`), but sanitize defensively so the delimiter is unambiguous.
  private def sanitize(s: String): String = s.replace("|", "_")

  private def hmacHex(secret: String, data: String): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
    mac.doFinal(data.getBytes(StandardCharsets.UTF_8)).map(b => f"${b & 0xff}%02x").mkString
  }

  private def constantTimeEquals(a: String, b: String): Boolean =
    java.security.MessageDigest
      .isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8))
}
