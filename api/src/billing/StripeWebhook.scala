package wifihaven.api.billing

import zio.json.ast.Json

import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * #2135: Stripe webhook signature verification + minimal event parsing — PURE, no external I/O, so
 * the "unsigned/forged payloads are rejected" and "valid payloads drive the right transition"
 * properties are unit- and feature-testable without a live Stripe. This is the security boundary
 * for `POST /api/billing/webhook`: only a payload whose `Stripe-Signature` HMAC matches the
 * configured signing secret is acted on.
 *
 * Signature scheme (Stripe "Constructing an event" docs): the `Stripe-Signature` header is a
 * comma-separated list of `k=v` pairs; `t` is the unix timestamp and each `v1` is
 * `HMAC-SHA256(secret, s"$t.$payload")` in lowercase hex. We recompute v1 and compare in constant
 * time, and reject a timestamp outside a tolerance window (replay guard).
 */
object StripeWebhook {

  /**
   * Default replay-tolerance: reject events whose `t` is more than 5 minutes from now (Stripe's own
   * default).
   */
  val DefaultToleranceSeconds: Long = 300

  sealed trait VerifyError
  object VerifyError {
    case object MissingSignature extends VerifyError
    case object BadSignature     extends VerifyError
    case object TimestampSkew    extends VerifyError
    case object MalformedPayload extends VerifyError
  }

  /**
   * The subset of a Stripe event we act on. `eventType` drives the state machine; the object fields
   * are pulled leniently (absent → None) since different event types carry different shapes.
   */
  final case class Event(
      eventType: String,
      customerId: Option[String],
      subscriptionId: Option[String],
      priceId: Option[String],
      currentPeriodEnd: Option[Instant],
      clientReferenceId: Option[String],
  )

  /**
   * Verify the `Stripe-Signature` header against `payload` using `secret`, then parse the event.
   * `now` is injected (Clock-driven in specs) for the replay-window check.
   */
  def verifyAndParse(
      payload: String,
      sigHeader: Option[String],
      secret: String,
      now: Instant,
      toleranceSeconds: Long = DefaultToleranceSeconds,
  ): Either[VerifyError, Event] =
    for {
      header <- sigHeader.toRight(VerifyError.MissingSignature)
      parsed <- parseSigHeader(header).toRight(VerifyError.BadSignature)
      (t, v1s) = parsed
      _ <- Either.cond(
        math.abs(now.getEpochSecond - t) <= toleranceSeconds,
        (),
        VerifyError.TimestampSkew,
      )
      expected = hmacSha256Hex(secret, s"$t.$payload")
      _     <- Either.cond(
        v1s.exists(constantTimeEquals(_, expected)),
        (),
        VerifyError.BadSignature,
      )
      event <- parseEvent(payload).toRight(VerifyError.MalformedPayload)
    } yield event

  /** Parse `t=<ts>,v1=<hex>,v1=<hex>,…` → (timestamp, list of v1 signatures). */
  private def parseSigHeader(header: String): Option[(Long, List[String])] = {
    val pairs = header
      .split(",")
      .iterator
      .flatMap { part =>
        part.split("=", 2) match {
          case Array(k, v) => Some(k.trim -> v.trim)
          case _           => None
        }
      }
      .toList
    val t     = pairs.collectFirst { case ("t", v) => v }.flatMap(s => s.toLongOption)
    val v1s   = pairs.collect { case ("v1", v) => v }
    t.filter(_ => v1s.nonEmpty).map(ts => ts -> v1s)
  }

  private def hmacSha256Hex(secret: String, data: String): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    mac.doFinal(data.getBytes("UTF-8")).map(b => f"${b & 0xff}%02x").mkString
  }

  /** Length-independent constant-time compare — avoids leaking the signature via timing. */
  private def constantTimeEquals(a: String, b: String): Boolean = {
    val ab = a.getBytes("UTF-8")
    val bb = b.getBytes("UTF-8")
    java.security.MessageDigest.isEqual(ab, bb)
  }

  /**
   * Parse the event JSON. Pulls `type` and, from `data.object`, the fields the state machine needs.
   * The three event types we act on carry these differently:
   *   - checkout.session.completed → object is a Checkout Session (customer, subscription,
   *     client_reference_id); the price rides on the expanded line item, but we resolve it from the
   *     subscription later, so `priceId` here is best-effort.
   *   - invoice.payment_failed → object is an Invoice (customer, subscription, current period on
   *     the line).
   *   - customer.subscription.deleted → object is a Subscription (customer, id, current_period_end,
   *     items[0].price.id).
   */
  private def parseEvent(payload: String): Option[Event] =
    Json.decoder.decodeJson(payload).toOption.flatMap {
      case root: Json.Obj =>
        val eventType = str(root, "type")
        val obj       = objField(root, "data").flatMap(d => objField(d, "object"))
        eventType.map { et =>
          Event(
            eventType = et,
            customerId = obj.flatMap(o => str(o, "customer")),
            subscriptionId =
              obj.flatMap(o => str(o, "subscription").orElse(idIfSubscription(o, et))),
            priceId = obj.flatMap(firstPriceId),
            currentPeriodEnd =
              obj.flatMap(o => longField(o, "current_period_end")).map(Instant.ofEpochSecond),
            clientReferenceId = obj.flatMap(o => str(o, "client_reference_id")),
          )
        }
      case _              => None
    }

  // On customer.subscription.deleted the object IS the subscription, so its `id` is the subscription id.
  private def idIfSubscription(o: Json.Obj, eventType: String): Option[String] =
    if eventType == "customer.subscription.deleted" then str(o, "id") else None

  // Resolve a price id from `items.data[0].price.id` (subscription) or `lines.data[0].price.id`
  // (invoice) — best-effort; None when absent.
  private def firstPriceId(o: Json.Obj): Option[String] =
    List("items", "lines").iterator
      .flatMap(k => objField(o, k))
      .flatMap(container => arrField(container, "data"))
      .flatMap(_.headOption)
      .collectFirst { case row: Json.Obj => row }
      .flatMap(row => objField(row, "price"))
      .flatMap(price => str(price, "id"))

  private def str(o: Json.Obj, key: String): Option[String] =
    o.fields.collectFirst { case (k, Json.Str(v)) if k == key => v }

  private def longField(o: Json.Obj, key: String): Option[Long] =
    o.fields.collectFirst { case (k, Json.Num(v)) if k == key => v.longValue }

  private def objField(o: Json.Obj, key: String): Option[Json.Obj] =
    o.fields.collectFirst { case (k, v: Json.Obj) if k == key => v }

  private def arrField(o: Json.Obj, key: String): Option[Chunk] =
    o.fields.collectFirst { case (k, Json.Arr(v)) if k == key => v.toList }

  private type Chunk = List[Json]
}
