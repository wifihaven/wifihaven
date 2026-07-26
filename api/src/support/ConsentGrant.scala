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
 * Wire shape mirrors [[ConsentToken]] — `g1.<b64url(payload)>.<hmacHex>` over
 * `householdId|threadId|expEpochSeconds` — but with a DISTINCT `g1` version prefix, signed under
 * the same agent-token secret. The prefix is domain separation and it is CRYPTOGRAPHIC, not
 * cosmetic: the MAC is computed over `"<version>.<b64>"`, so re-labelling a `v1` agent token as
 * `g1` (or the reverse) invalidates the signature outright — the version is bound into the MAC, not
 * merely compared. Do not "simplify" either side to MAC the payload alone: that would leave the
 * separation resting on a payload-parse accident. A consent link therefore can never be replayed as
 * an agent credential or vice-versa. Pinned by SupportConsentSpec.
 */
object ConsentGrant {

  private val Version: String = "g1"

  sealed trait Err
  object Err {
    case object Malformed    extends Err
    case object BadSignature extends Err
    case object Expired      extends Err
  }

  /** The household + thread a redeemed consent link authorises a grant for. */
  final case class Claims(householdId: HouseholdId, threadId: String, expiresAt: Instant)

  /** Mint a consent-request token for `household` + `thread`, valid until `now + ttl`. */
  def mint(
      household: HouseholdId,
      threadId: String,
      now: Instant,
      ttl: java.time.Duration,
      secret: String,
  ): String = {
    val exp     = now.plus(ttl).getEpochSecond
    val payload = s"${household.value}|${sanitize(threadId)}|$exp"
    val b64     =
      Base64.getUrlEncoder.withoutPadding.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
    s"$Version.$b64.${hmacHex(secret, s"$Version.$b64")}"
  }

  /**
   * Verify a consent-request token and check it hasn't expired as of `now`. Tampering fails
   * BadSignature; a stale link fails Expired. The caller MUST additionally require that the
   * redeeming session's household equals `Claims.householdId` — this token proves which thread was
   * asked, not who is asking.
   */
  def verify(token: String, now: Instant, secret: String): Either[Err, Claims] =
    token.split("\\.", 3) match {
      case Array(Version, b64, sig) =>
        if !constantTimeEquals(sig, hmacHex(secret, s"$Version.$b64")) then Left(Err.BadSignature)
        else
          decode(b64).flatMap { case (household, thread, exp) =>
            if now.getEpochSecond > exp then Left(Err.Expired)
            else Right(Claims(HouseholdId(household), thread, Instant.ofEpochSecond(exp)))
          }
      case _                        => Left(Err.Malformed)
    }

  private def decode(b64: String): Either[Err, (Long, String, Long)] =
    scala.util
      .Try {
        val raw = new String(Base64.getUrlDecoder.decode(b64), StandardCharsets.UTF_8)
        raw.split("\\|", 3) match {
          case Array(h, t, e) => (h.toLong, t, e.toLong)
          case _              => throw new IllegalArgumentException("bad payload")
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
