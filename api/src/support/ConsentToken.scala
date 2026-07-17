package wifihaven.api.support

import wifihaven.shared.types.HouseholdId

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

/**
 * #2200 / #2241 — the per-session agent token. When the responder dispatches a cloud-agent session
 * for an inbound (UI-originated) support message, the backend mints one of these OUT-OF-BAND (in
 * the agent kickoff, never in customer-visible text) and it is the agent's ONLY credential: the
 * agent holds no Plain key, no GitHub token, no Anthropic key — every side effect goes back through
 * our `/api/support/agent/...` endpoints authenticated by this token. It is:
 *
 *   - **read-only + narrowly actioned** — it authorises exactly the agent endpoints (post a reply
 *     into ONE thread, file a scrubbed issue, read ONE household's summary); nothing else in the
 *     API accepts it, and there is no mutation path behind it;
 *   - **bound to exactly one `household_id`** — the token CARRIES the household, and the household
 *     read derives its scope FROM the verified token (never from the request), so a household-A
 *     token can never read household-B's data — cross-tenant reads are impossible by construction
 *     (#2107/#2108 isolation substrate);
 *   - **thread-bound** — it carries the originating Plain thread id; the reply endpoint posts into
 *     THAT thread only, regardless of what the request body says;
 *   - **consent-scoped** — `dataAccess` is true only when the customer opted in ("give agent access
 *     to my data", #2241); without it the household-read endpoint returns 403 and the agent answers
 *     without data access;
 *   - **short-TTL** — expiry is minted in and checked against an injected clock;
 *   - **audit-logged / metered** — every mint and redeem is logged + metered (SupportResponder).
 *
 * Wire shape: an HMAC-signed opaque string `v1.<b64url(payload)>.<hmacHex>` where `payload` is
 * `householdId|threadId|dataAccess|expEpochSeconds`. Signed server-side under a dedicated secret so
 * the agent (or anything reading the session transcript) cannot forge, widen, or extend a token.
 * This is a functional capability, not a policy the model could be argued out of.
 *
 * NOTE (revocation): the TTL is deliberately short (minutes), which bounds the exposure window; an
 * explicit revocation list is tracked as a follow-up (TODO(#2259)). The other #2241 properties
 * (read-only, single-household, thread-bound, consent-scoped, short-TTL, out-of-band, audit-logged)
 * hold today.
 */
object ConsentToken {

  private val Version: String = "v1"

  sealed trait Err
  object Err {
    case object Malformed    extends Err
    case object BadSignature extends Err
    case object Expired      extends Err
  }

  /**
   * The claims a verified token resolves to: the single household + thread it authorises, and
   * whether the customer consented to household data reads.
   */
  final case class Claims(
      householdId: HouseholdId,
      threadId: String,
      dataAccess: Boolean,
      expiresAt: Instant,
  )

  /**
   * Mint a token for `household` + `thread`, expiring at `now + ttl`. Server-side only; `secret` is
   * the dedicated agent-token secret (required at boot whenever the responder is enabled, #2265 —
   * with the responder disabled this is never called).
   */
  def mint(
      household: HouseholdId,
      threadId: String,
      dataAccess: Boolean,
      now: Instant,
      ttl: java.time.Duration,
      secret: String,
  ): String = {
    val exp     = now.plus(ttl).getEpochSecond
    val payload = s"${household.value}|${sanitize(threadId)}|$dataAccess|$exp"
    val b64     =
      Base64.getUrlEncoder.withoutPadding.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
    s"$Version.$b64.${hmacHex(secret, b64)}"
  }

  /**
   * Verify a token against `secret` and check it hasn't expired as of `now`. Returns the claims it
   * authorises. Any tampering (payload or signature) fails BadSignature; an expired token fails
   * Expired — no agent action is ever performed for a bad or stale token.
   */
  def verify(token: String, now: Instant, secret: String): Either[Err, Claims] =
    token.split("\\.", 3) match {
      case Array(Version, b64, sig) =>
        if !constantTimeEquals(sig, hmacHex(secret, b64)) then Left(Err.BadSignature)
        else
          decode(b64).flatMap { case (household, thread, dataAccess, exp) =>
            if now.getEpochSecond > exp then Left(Err.Expired)
            else
              Right(Claims(HouseholdId(household), thread, dataAccess, Instant.ofEpochSecond(exp)))
          }
      case _                        => Left(Err.Malformed)
    }

  private def decode(b64: String): Either[Err, (Long, String, Boolean, Long)] =
    scala.util
      .Try {
        val raw = new String(Base64.getUrlDecoder.decode(b64), StandardCharsets.UTF_8)
        raw.split("\\|", 4) match {
          case Array(h, t, d, e) => (h.toLong, t, d.toBoolean, e.toLong)
          case _                 => throw new IllegalArgumentException("bad payload")
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
