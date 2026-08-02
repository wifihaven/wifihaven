package wifihaven.api.auth

import wifihaven.shared.types.RouterId

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * #2566 / #2569 / #2322 — the block-page token (`bpt`).
 *
 * The block page is unauthenticated by construction: the router DNATs blocked traffic to it and the
 * redirect carries only `?mac=&host=` (#1615/#1617/#1618). That left `GET /api/blocked` and `POST
 * /api/access-requests` with no household in scope, so both guessed — and both guesses leak one
 * household's data to another (#2569) or file a child's request against the wrong household
 * (#2322).
 *
 * This token is the missing context. It is minted by the API for an enrolled router (which already
 * has an authoritative `household_id`, #2106), handed to that router over the router-authenticated
 * `GET /api/router/block-page-token`, stamped onto the redirect by the agent, and relayed back by
 * the SPA.
 *
 * ==Shape==
 * `<routerId>.<base64url(HMAC-SHA256(secret, "wifihaven-block-page-v1:" + routerId))>`
 *
 * Signed with the API's existing JWT HMAC secret, so there is no new secret to provision and no
 * schema change. The router id is carried in the clear and the signature only proves the API minted
 * it; the HOUSEHOLD is never carried on the token — it is read live from the `routers` row at
 * verify time. That is deliberate: it keeps the token correct if a router is ever moved between
 * households, and it makes deleting the router revoke the token (there is no expiry to lean on).
 *
 * ==What it authorizes==
 * Nothing that a client on the household's own LAN cannot already do: read the block-page reason
 * and today's minutes for a MAC, and file an access request. It is not a session, it grants no
 * write access to policy, and it is deliberately not treated as authentication — an absent or
 * invalid token degrades to the pre-existing default-household behaviour rather than failing the
 * request, because the kid staring at a block page is not the attacker we can afford to punish.
 */
object BlockPageToken {

  /** Domain separator, so this HMAC can never be confused with another use of the same secret. */
  private val Domain = "wifihaven-block-page-v1:"

  private def sign(secret: String, routerId: RouterId): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
    val raw = mac.doFinal((Domain + routerId.value).getBytes(StandardCharsets.UTF_8))
    Base64.getUrlEncoder.withoutPadding.encodeToString(raw)
  }

  def mint(secret: String, routerId: RouterId): String =
    s"${routerId.value}.${sign(secret, routerId)}"

  /**
   * Recover the router id a token was minted for, or `None` if it is malformed or the signature
   * doesn't verify. Comparison is constant-time (`MessageDigest.isEqual`) so the signature can't be
   * recovered a byte at a time.
   */
  def verify(secret: String, token: String): Option[RouterId] =
    // Split on the FIRST '.' by index rather than `String.split`, which drops trailing empty
    // fields — `"<uuid>.<sig>."` and `"<uuid>.<sig>.."` would both match an `Array(id, sig)`
    // pattern and verify, making the token needlessly malleable. A router id is a UUID (hyphens,
    // no dots), so the first '.' is exactly the separator.
    token.indexOf('.') match {
      case -1  => None
      case dot =>
        val idStr = token.substring(0, dot)
        val sig   = token.substring(dot + 1)
        scala.util
          .Try(java.util.UUID.fromString(idStr))
          .toOption
          .map(RouterId(_))
          .filter { rid =>
            MessageDigest.isEqual(
              sign(secret, rid).getBytes(StandardCharsets.UTF_8),
              sig.getBytes(StandardCharsets.UTF_8),
            )
          }
    }
}
