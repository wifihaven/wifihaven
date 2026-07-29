package wifihaven.api.support

import wifihaven.api.agent.AgentCredential
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
 *   - **expiring** — expiry is minted in and checked against an injected clock. NOTE (#2473) this
 *     is no longer a *short* TTL and is no longer the primary bound — see the revocation note
 *     below;
 *   - **audit-logged / metered** — every mint and redeem is logged + metered (SupportResponder),
 *     and since #2473 a REJECTED callback is loud in its own right
 *     (`agent_token_rejected_total{channel="support",op,reason}`), so an answer thrown away at the
 *     token check can never again be silent.
 *
 * Wire shape: an HMAC-signed opaque string `v1.<b64url(payload)>.<hmacHex>` where `payload` is
 * `householdId|threadId|dataAccess|expEpochSeconds`. Signed server-side under a dedicated secret so
 * the agent (or anything reading the session transcript) cannot forge, widen, or extend a token.
 * The MAC covers `"<version>.<b64>"`, so the version tag is BOUND into the signature: the #2419
 * consent link ([[ConsentGrant]], `g1`) shares this secret, and MACing the payload alone would let
 * either token be re-labelled as the other and pass its signature check. (The press token
 * `wifihaven.api.press.PressToken` has its OWN secret so it is unaffected, but it still MACs the
 * payload alone — symmetry tracked in [#2426](https://github.com/wifihaven/wifihaven/issues/2426).)
 * This is a functional capability, not a policy the model could be argued out of.
 *
 * NOTE (TTL and revocation) — #2473 changed this, so read it rather than assuming the old story.
 * The TTL USED to be minutes, and "the exposure window is short" was a real part of the answer to
 * "what if a token leaks". It no longer is: a cloud-agent run can be paused on subscription usage
 * limits and resumed hours later, so a minutes-long TTL guaranteed the customer's reply was thrown
 * away on resume (the observed #2473 failure). The default is now 24h ([[wifihaven.api
 * .AgentTokenTtl]] carries the sizing rationale).
 *
 * **TTL is therefore no longer the primary bound.** The #2241 model still holds because it never
 * rested on the TTL alone — every other property above is UNCHANGED and is what carries it: single
 * household (scope derives from the verified token, so cross-tenant reads are impossible by
 * construction), thread-bound reply, read-only with no mutation path, consent-scoped data access,
 * out-of-band mint, rate-limited and audit-logged.
 *
 * Note that [[Claims.dataAccess]] is stamped at MINT (the responder reads the live #2419 grant once
 * and bakes it in), so a longer TTL would have stretched the window in which an already-minted
 * token survives a customer's WITHDRAWAL. It does not, because `SupportResponder.agentHousehold`
 * re-reads the grant at read time (#2476): the stamp is necessary but no longer sufficient.
 *
 * What the longer TTL does still cost is incident response — "just wait for it to expire" is no
 * longer a workable answer to a suspected leak. TODO(#2259), an explicit agent-token revocation
 * list, is what closes that; not implemented as part of #2473. See [[wifihaven.api.AgentTokenTtl]]
 * for the full write-up.
 */
object ConsentToken {

  // #2508: single-sourced with the redactor that has to recognise what this mints — change the
  // grammar in ONE place and both the minting and the outbound credential scrub move together.
  private val Version: String = AgentCredential.Version

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
    s"$Version.$b64.${hmacHex(secret, s"$Version.$b64")}"
  }

  /**
   * Verify a token against `secret` and check it hasn't expired as of `now`. Returns the claims it
   * authorises. Any tampering (payload or signature) fails BadSignature; an expired token fails
   * Expired — no agent action is ever performed for a bad or stale token.
   */
  def verify(token: String, now: Instant, secret: String): Either[Err, Claims] =
    token.split("\\.", 3) match {
      case Array(Version, b64, sig) =>
        if !constantTimeEquals(sig, hmacHex(secret, s"$Version.$b64")) then Left(Err.BadSignature)
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
