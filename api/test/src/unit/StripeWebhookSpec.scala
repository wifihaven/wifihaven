package wifihaven.api.unit

import wifihaven.api.billing.StripeWebhook
import wifihaven.api.billing.StripeWebhook.VerifyError
import zio.test.*

import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * #2135: pure unit pins for the Stripe webhook signature verifier (the security boundary for `POST
 * /api/billing/webhook`). Complements the full-stack transitions in feature/BillingWebhookSpec —
 * here we exercise the reject paths (missing header, bad signature, timestamp skew) and the parse
 * of the fields the state machine reads, with no DB/HTTP.
 */
object StripeWebhookSpec extends ZIOSpecDefault {

  private val Secret = "whsec_unit_secret_at_least_32_chars_long"
  private val Now    = Instant.ofEpochSecond(1_700_000_000L)

  private def sign(payload: String, ts: Long, secret: String = Secret): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    val hex = mac.doFinal(s"$ts.$payload".getBytes("UTF-8")).map(b => f"${b & 0xff}%02x").mkString
    s"t=$ts,v1=$hex"
  }

  private val checkout =
    """{"type":"checkout.session.completed","data":{"object":{"customer":"cus_9","subscription":"sub_9","client_reference_id":"42","current_period_end":1700003600}}}"""

  def spec = suite("StripeWebhook signature verification (#2135)")(
    test("a correctly signed, in-window payload verifies and parses the acted-on fields") {
      val header = sign(checkout, Now.getEpochSecond)
      val result = StripeWebhook.verifyAndParse(checkout, Some(header), Secret, Now)
      assertTrue(
        result.isRight,
        result.exists(_.eventType == "checkout.session.completed"),
        result.exists(_.customerId.contains("cus_9")),
        result.exists(_.subscriptionId.contains("sub_9")),
        result.exists(_.clientReferenceId.contains("42")),
        result.exists(_.currentPeriodEnd.contains(Instant.ofEpochSecond(1700003600L))),
      )
    },
    test("a missing signature header is rejected") {
      assertTrue(
        StripeWebhook.verifyAndParse(checkout, None, Secret, Now) == Left(
          VerifyError.MissingSignature,
        ),
      )
    },
    test("a signature computed with the wrong secret is rejected") {
      val header =
        sign(checkout, Now.getEpochSecond, secret = "whsec_the_wrong_secret_padding_padding_x")
      assertTrue(
        StripeWebhook.verifyAndParse(checkout, Some(header), Secret, Now) == Left(
          VerifyError.BadSignature,
        ),
      )
    },
    test("a tampered payload (same signature) is rejected") {
      val header   = sign(checkout, Now.getEpochSecond)
      val tampered = checkout.replace("cus_9", "cus_ATTACKER")
      assertTrue(
        StripeWebhook.verifyAndParse(tampered, Some(header), Secret, Now) == Left(
          VerifyError.BadSignature,
        ),
      )
    },
    test("a timestamp outside the tolerance window is rejected (replay guard)") {
      // Signed 10 minutes ago; default tolerance is 5 minutes.
      val staleTs = Now.getEpochSecond - 600
      val header  = sign(checkout, staleTs)
      assertTrue(
        StripeWebhook.verifyAndParse(checkout, Some(header), Secret, Now) == Left(
          VerifyError.TimestampSkew,
        ),
      )
    },
    test("customer.subscription.deleted uses the object id as the subscription id") {
      val payload =
        """{"type":"customer.subscription.deleted","data":{"object":{"id":"sub_del","customer":"cus_1"}}}"""
      val header  = sign(payload, Now.getEpochSecond)
      val result  = StripeWebhook.verifyAndParse(payload, Some(header), Secret, Now)
      assertTrue(
        result.exists(_.subscriptionId.contains("sub_del")),
        result.exists(_.customerId.contains("cus_1")),
      )
    },
  )
}
