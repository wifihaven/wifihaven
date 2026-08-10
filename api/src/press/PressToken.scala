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
 *   - **expiring** — expiry is minted in and checked against an injected clock. #2473 raised the
 *     default lifetime from 30 minutes to 24h ([[wifihaven.api.AgentTokenTtl]]) because a
 *     cloud-agent run can be paused on subscription usage limits and resumed hours later, so a
 *     minutes-long TTL guaranteed the journalist's reply was thrown away on resume. **TTL is
 *     therefore no longer the primary bound on a leaked token** — and for press it never was the
 *     load-bearing one: the token carries NO household and NO data scope, and the reply is
 *     destination-locked to the ONE address it was minted for, so the worst a leaked press token
 *     can do is email that same journalist. The "just wait for it to expire" response to a
 *     suspected leak is what a 24h TTL costs, which raises the value of TODO(#2259) (explicit
 *     agent-token revocation) — not implemented here;
 *   - **unforgeable** — HMAC-signed server-side under a dedicated secret so the agent (or anything
 *     reading the session transcript) cannot forge, widen, redirect, or extend a token;
 *   - **loudly rejected** — since #2473 a rejected callback increments
 *     `agent_token_rejected_total{channel="press",op,reason}` and logs, so an expired token
 *     silently eating a reply is observable.
 *
 * Wire shape: `v1.<b64url(payload)>.<hmacHex>` where `payload` is
 * `b64(replyTo)|b64(subject)|pressMessageId|expEpochSeconds|b64(inboundMessageId)|b64(inboundReferences)`
 * (every free-text field base64url'd so none of them can smuggle the `|` delimiter;
 * `pressMessageId` and `exp` are bare decimal). `pressMessageId` (#2296) is the id of the recorded
 * inbound `press_messages` row this session answers, so the reply callback can pair the outbound
 * row to its inquiry — it rides the SIGNED payload (like every other field), so a hijacked agent
 * can neither forge nor repoint it, and `0` means "no inbound row was recorded" (fail-open: the
 * inbound insert failed). `inboundMessageId` (#2451) is the journalist's RFC 5322 `Message-ID` the
 * reply threads under (`In-Reply-To`/`References`); empty means the inbound carried none, in which
 * case the reply sends unthreaded. It is inside the MAC for the same reason the reply target is: a
 * prompt-hijacked agent must not be able to graft its reply onto somebody else's conversation.
 * `inboundReferences` (#2467) is that message's own `References` header, normalised to a
 * space-separated msg-id list and bounded to
 * [[wifihaven.api.notify.EmailSender.MaxReferencesChars]] (986 chars, one RFC 5322 header line)
 * BEFORE it is minted — so an attacker-controlled header cannot inflate the bearer token past a
 * known bound (986 chars of msg-ids, ~1.3 KB once base64'd). It rides the MAC for exactly the same
 * reason the Message-ID does.
 *
 * '''Rollout (#2451/#2467).''' [[verify]] also accepts the pre-#2467 5-field payload (no
 * `inboundReferences`, resolving to an empty chain, i.e. the first-level threading #2451 shipped)
 * and the pre-#2451 4-field payload (no `inboundMessageId` either, resolving to an empty
 * Message-ID). Tokens expire (`press.agentTokenTtlMinutes`) and are both minted and verified by
 * this server — this is NOT the router wire contract — so the only exposure is a session dispatched
 * by the old build and redeemed by the new one. That window is real though, and failing it would
 * silently drop a journalist's reply. TODO(#2459): delete the 4-field arm once the deploy has been
 * live longer than one token TTL — note that #2473 raised that TTL from 30 minutes to **24 hours**,
 * so the wait is a day, not half an hour.
 */
object PressToken {

  /**
   * The version prefix this scheme mints with. PUBLIC because
   * [[wifihaven.api.agent.AgentCredential]] derives its outbound redaction pattern from it rather
   * than re-typing the literal (#2508) — this stays this scheme's OWN constant, so bumping it here
   * cannot silently re-version a sibling token scheme.
   */
  val Version: String = "v1"

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
      // #2451 — the journalist's inbound RFC 5322 Message-ID the reply threads under. Empty when the
      // inbound carried none (or the token predates #2451); the reply then sends unthreaded.
      inboundMessageId: String,
      // #2467 — the journalist's inbound `References` header (a normalised, bounded msg-id list),
      // which the reply's own `References` accumulates onto per RFC 5322 §3.6.4. Empty when the
      // inbound carried none (every first-contact email) or when the token predates #2467; the
      // reply then references the parent alone, exactly as #2451 shipped.
      inboundReferences: String,
      expiresAt: Instant,
      // #2451 — true when this token used the pre-#2451 4-field payload, i.e. it was minted by a
      // build older than the running one. Distinguishes "old token" from "inbound had no
      // Message-ID", which the empty `inboundMessageId` alone cannot: the reply path logs it, and
      // that is the signal #2459 waits to go quiet before deleting the tolerant arm. Deliberately
      // has NO default: a site that forgot to pass it would default to "not legacy" and
      // UNDER-report exactly the traffic #2459 is waiting on, so the compiler asks every time.
      legacyPayload: Boolean,
  )

  /**
   * Mint a token binding `replyTo` + `subject` + the recorded inbound `pressMessageId` + the
   * inbound `Message-ID` + the inbound `References` chain, expiring at `now + ttl`. Server-side
   * only. Pass `pressMessageId = 0` when no inbound row was recorded, `inboundMessageId = ""` when
   * the inbound carried no Message-ID, and `inboundReferences = ""` when it carried no chain (the
   * first-contact case, and every non-press caller). `inboundReferences` must already be normalised
   * and bounded by [[wifihaven.api.notify.EmailSender.normalizeReferences]] — minting is not where
   * an attacker-controlled header gets sanitised.
   */
  def mint(
      replyTo: String,
      subject: String,
      pressMessageId: Long,
      inboundMessageId: String,
      inboundReferences: String,
      now: Instant,
      ttl: java.time.Duration,
      secret: String,
  ): String = {
    val exp     = now.plus(ttl).getEpochSecond
    val payload =
      s"${b64(replyTo)}|${b64(subject)}|$pressMessageId|$exp|${b64(inboundMessageId)}|" +
        b64(inboundReferences)
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
          decode(body).flatMap {
            case (
                  replyTo,
                  subject,
                  pressMessageId,
                  exp,
                  inboundMessageId,
                  inboundReferences,
                  legacyPayload,
                ) =>
              if now.getEpochSecond > exp then Left(Err.Expired)
              else
                Right(
                  Claims(
                    replyTo,
                    subject,
                    pressMessageId,
                    inboundMessageId,
                    inboundReferences,
                    Instant.ofEpochSecond(exp),
                    legacyPayload,
                  ),
                )
          }
      case _                         => Left(Err.Malformed)
    }

  // Returns the payload fields plus a flag for WHICH arity matched, so the caller can tell a
  // legacy token from a current one whose Message-ID happens to be empty.
  private def decode(
      body: String,
  ): Either[Err, (String, String, Long, Long, String, String, Boolean)] =
    scala.util
      .Try {
        val raw = new String(Base64.getUrlDecoder.decode(body), StandardCharsets.UTF_8)
        // `-1` keeps trailing empty fields, so a payload whose Message-ID or References is empty
        // still yields its full arity — and the arity match below is then exact: a future 7-field
        // payload falls through to Malformed instead of silently folding its 7th field into `refs`.
        raw.split("\\|", -1) match {
          case Array(r, s, m, e, mid, refs) =>
            (unb64(r), unb64(s), m.toLong, e.toLong, unb64(mid), unb64(refs), false)
          // TODO(#2459): the pre-#2467 5-field payload — a session dispatched by the previous build
          // and redeemed by this one. Its reply threads under the parent alone (the #2451 shape),
          // which is correct for the first-level replies that are all the old build could produce.
          // Not flagged `legacyPayload`: that flag tracks the pre-#2451 arm #2459 waits on, and
          // conflating the two would keep it lit forever.
          case Array(r, s, m, e, mid)       =>
            (unb64(r), unb64(s), m.toLong, e.toLong, unb64(mid), "", false)
          // TODO(#2459): the pre-#2451 4-field payload — accepted so a session dispatched by the old
          // build and redeemed by the new one still verifies (its reply just can't thread). Delete
          // once the #2451 deploy has been live longer than one token TTL; `legacyPayload` on the
          // claims is what tells you this arm has stopped being hit.
          case Array(r, s, m, e) => (unb64(r), unb64(s), m.toLong, e.toLong, "", "", true)
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
