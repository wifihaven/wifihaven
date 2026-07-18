package wifihaven.api.press

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

/**
 * #2203 — the per-session PRESS agent token. When the press responder dispatches a cloud-agent
 * session for an inbound (public, untrusted) press message, the backend mints one of these
 * OUT-OF-BAND (in the agent kickoff, never in sender-visible text) and it is the agent's ONLY
 * credential: the press agent holds no Anthropic key and — unlike #2200's support
 * [[wifihaven.api.support.ConsentToken]] — NO household binding and NO data-access scope.
 *
 * That difference is deliberate and STRUCTURAL, not a prompt-level promise. Press arrives from the
 * public, unauthenticated (an inbound email address, not the #2199 household-gated widget), so the
 * press agent must answer from PUBLIC info only. This token type has no `householdId` field and no
 * `dataAccess` flag, so there is literally nothing it can authorise beyond "post a reply that gets
 * emailed to the ONE address it carries" — and no `/api/press/agent/household`-style endpoint
 * exists. A fully prompt-hijacked press agent still cannot read any household's data OR redirect
 * the reply, because neither capability was ever minted.
 *
 * It is:
 *   - **reply-target-bound** — it carries the original sender's email address + subject; the reply
 *     endpoint EMAILS the agent's copy to THAT address only, regardless of what the request body or
 *     the (untrusted) message said. This is the load-bearing control for autonomous send: a
 *     hijacked agent cannot email an attacker-chosen recipient;
 *   - **short-TTL** — expiry is minted in and checked against an injected clock;
 *   - **unforgeable** — HMAC-signed server-side under a dedicated secret so the agent (or anything
 *     reading the session transcript) cannot forge, widen, redirect, or extend a token.
 *
 * Wire shape: `v1.<b64url(payload)>.<hmacHex>` where `payload` is
 * `b64(replyTo)|b64(subject)|pressMessageId|expEpochSeconds` (`replyTo`/`subject` base64url'd so
 * neither an email nor a subject can smuggle the `|` delimiter; `pressMessageId` and `exp` are bare
 * decimal). `pressMessageId` (#2296) is the id of the recorded inbound `press_messages` row this
 * session answers, so the reply callback can pair the outbound row to its inquiry — it rides the
 * SIGNED payload (like every other field), so a hijacked agent can neither forge nor repoint it,
 * and `0` means "no inbound row was recorded" (fail-open: the inbound insert failed).
 */
object PressToken {

  private val Version: String = "v1"

  sealed trait Err
  object Err {
    case object Malformed    extends Err
    case object BadSignature extends Err
    case object Expired      extends Err
  }

  /**
   * The claims a verified press token resolves to: the single email address the reply is emailed to
   * and the subject to reply under. NO household, NO data scope — the type cannot express them.
   */
  final case class Claims(
      replyTo: String,
      subject: String,
      pressMessageId: Long,
      expiresAt: Instant,
  )

  /**
   * Mint a token binding `replyTo` + `subject` + the recorded inbound `pressMessageId`, expiring at
   * `now + ttl`. Server-side only. Pass `pressMessageId = 0` when no inbound row was recorded.
   */
  def mint(
      replyTo: String,
      subject: String,
      pressMessageId: Long,
      now: Instant,
      ttl: java.time.Duration,
      secret: String,
  ): String = {
    val exp     = now.plus(ttl).getEpochSecond
    val payload = s"${b64(replyTo)}|${b64(subject)}|$pressMessageId|$exp"
    val body    =
      Base64.getUrlEncoder.withoutPadding.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
    s"$Version.$body.${hmacHex(secret, body)}"
  }

  /**
   * Verify a token against `secret` and check it hasn't expired as of `now`. Any tampering fails
   * BadSignature; an expired token fails Expired — no reply is emailed for a bad/stale token.
   */
  def verify(token: String, now: Instant, secret: String): Either[Err, Claims] =
    token.split("\\.", 3) match {
      case Array(Version, body, sig) =>
        if !constantTimeEquals(sig, hmacHex(secret, body)) then Left(Err.BadSignature)
        else
          decode(body).flatMap { case (replyTo, subject, pressMessageId, exp) =>
            if now.getEpochSecond > exp then Left(Err.Expired)
            else Right(Claims(replyTo, subject, pressMessageId, Instant.ofEpochSecond(exp)))
          }
      case _                         => Left(Err.Malformed)
    }

  private def decode(body: String): Either[Err, (String, String, Long, Long)] =
    scala.util
      .Try {
        val raw = new String(Base64.getUrlDecoder.decode(body), StandardCharsets.UTF_8)
        raw.split("\\|", 4) match {
          case Array(r, s, m, e) => (unb64(r), unb64(s), m.toLong, e.toLong)
          case _                 => throw new IllegalArgumentException("bad payload")
        }
      }
      .toEither
      .left
      .map(_ => Err.Malformed)

  private def b64(s: String): String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(s.getBytes(StandardCharsets.UTF_8))

  private def unb64(s: String): String =
    new String(Base64.getUrlDecoder.decode(s), StandardCharsets.UTF_8)

  private def hmacHex(secret: String, data: String): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
    mac.doFinal(data.getBytes(StandardCharsets.UTF_8)).map(b => f"${b & 0xff}%02x").mkString
  }

  private def constantTimeEquals(a: String, b: String): Boolean =
    java.security.MessageDigest
      .isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8))
}
