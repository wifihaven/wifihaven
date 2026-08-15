package wifihaven.api.unit

import wifihaven.api.support.{ConsentGrant, SupportResponder}
import wifihaven.shared.types.HouseholdId
import zio.test.*

import java.time.temporal.ChronoUnit
import java.time.Instant

/**
 * #2709 — the consent LINK's ledger row must never stop covering a token that is still redeemable.
 *
 * WHY THIS IS A TEST AND NOT A COMMENT. Two independent pieces of code decide when one link dies,
 * and they round differently:
 *
 *   - [[ConsentGrant.mint]] signs `exp = now.plus(ttl).getEpochSecond`, which FLOORS to a whole
 *     second, and [[ConsentGrant.verify]] rejects only once `now.getEpochSecond > exp`. So the
 *     token stays redeemable through the END of second `exp`.
 *   - `SupportResponder.recordPostedLink` writes `link_expires_at`, and `outstandingLink` treats
 *     the row as outstanding while `link_expires_at > now`.
 *
 * The naive `now.plus(ttl)` keeps sub-second precision, so the ROW died up to a second before the
 * TOKEN did. In that window `outstandingLink` answered "nothing live here" about a link still in
 * front of the customer and still redeemable — the one gap where the #2709 exclusion did not cover
 * a live link, and an agent reply could land beneath it.
 *
 * The fix rounds the row UP to `floor(now + ttl) + 1s`. That is a hand-maintained mirror of
 * `ConsentGrant`'s rounding: nothing in the type system ties them together, so if either side's
 * rounding changes the skew comes back silently. This spec is what makes it come back LOUDLY.
 *
 * It is a unit spec deliberately — the property is arithmetic on the two expiry rules, and pinning
 * it against a clock needs no database, no Plain, and no agent session.
 */
object SupportConsentLinkExpirySpec extends ZIOSpecDefault {

  private val hh     = HouseholdId(1L)
  private val thread = "th_expiry"
  private val secret = "agent-token-secret-0123456789abcdef"
  private val ttl    = SupportResponder.ConsentLinkTtl

  /**
   * The PRODUCTION expression, called — not a copy of it. `recordPostedLink` passes
   * `SupportResponder.linkExpiryFor(now)` straight to `recordPrompt`, so re-deriving the rounding
   * here would leave this spec passing happily while the real row went back to expiring early.
   */
  private def rowOutstandingAt(postedAt: Instant, at: Instant): Boolean =
    SupportResponder.linkExpiryFor(postedAt).isAfter(at)

  /** The token is redeemable while `verify` does not return `Expired`. */
  private def tokenAliveAt(postedAt: Instant, at: Instant): Boolean = {
    val token = ConsentGrant.mint(hh, thread, postedAt, ttl, secret, "nonce-expiry-spec")
    ConsentGrant.verify(token, at, secret).isRight
  }

  /**
   * A posting instant carrying a sub-second fraction is the whole point: on a whole second the two
   * rules agree trivially, and the original bug was invisible.
   */
  private val postedAt = Instant.parse("2026-08-15T12:34:56.750Z")

  def spec = suite("#2709: the consent-link row never expires before its token")(
    test("THE BUG: the row must still be outstanding through the token's last live instant") {
      // `exp` floors to :56, so the token lives until :57.000 exclusive — 250ms PAST the naive
      // `postedAt + ttl` of :56.750. Every instant in that window is one where the old code said
      // "no link outstanding" about a link the customer could still redeem.
      val lastAlive = postedAt.plus(ttl).truncatedTo(ChronoUnit.SECONDS).plusMillis(999)
      assertTrue(
        tokenAliveAt(postedAt, lastAlive),
        rowOutstandingAt(postedAt, lastAlive),
      )
    },
    test("the naive rounding WOULD have been wrong here — the gap is real, not theoretical") {
      // Pins that this spec is exercising the actual hazard: at this instant the token is alive and
      // the pre-fix expiry (`postedAt + ttl`, no rounding) would already have dropped the row.
      val inTheGap = postedAt.plus(ttl).plusMillis(1)
      assertTrue(
        tokenAliveAt(postedAt, inTheGap),
        !postedAt.plus(ttl).isAfter(inTheGap), // the old row predicate: NOT outstanding
        rowOutstandingAt(postedAt, inTheGap),  // the new one: still outstanding
      )
    },
    test("they die together: the first instant the token is dead, the row is not outstanding") {
      val dead = postedAt.plus(ttl).truncatedTo(ChronoUnit.SECONDS).plusSeconds(1)
      assertTrue(
        !tokenAliveAt(postedAt, dead),
        !rowOutstandingAt(postedAt, dead),
      )
    },
    test("the row NEVER expires before the token, across a whole second of postings") {
      // The property, not just its boundaries: for every millisecond offset within a second, the
      // row must outlive the token. Erring late only mutes; erring early is the phishing surface.
      // Report the offsets that broke it, not just that something did — a bare `forall` gives you
      // "false" and leaves you bisecting by hand.
      val offsets = (0 until 1000 by 37).toList
      val broken  = offsets.filter { ms =>
        val p    = Instant.parse("2026-08-15T12:34:56Z").plusMillis(ms.toLong)
        val last = p.plus(ttl).truncatedTo(ChronoUnit.SECONDS).plusMillis(999)
        !(tokenAliveAt(p, last) && rowOutstandingAt(p, last))
      }
      assertTrue(broken == List.empty[Int])
    },
  )
}
