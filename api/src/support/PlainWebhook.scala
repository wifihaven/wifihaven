package wifihaven.api.support

import zio.json.ast.Json

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * #2200 (support intake C, epic #2197) — inbound Plain webhook signature verification + minimal
 * event parsing. PURE, no external I/O, so "unsigned/forged payloads are rejected" and "a UI-
 * originated thread is recognised" are unit- and feature-testable without a live Plain. This is the
 * security boundary for `POST /api/support/webhook`: only a payload whose `Plain-Request-Signature`
 * HMAC matches the configured signing secret is acted on (design/umbrella #2206 §3).
 *
 * Signature scheme (Plain webhooks, https://www.plain.com/docs/webhooks): the raw request body is
 * signed with HMAC-SHA256 under the workspace webhook signing secret; the lowercase-hex digest is
 * sent in the `Plain-Request-Signature` header. We recompute it over the RAW body and compare in
 * constant time. The body is NEVER re-serialized before verification — a re-encode would change the
 * bytes and break the MAC.
 *
 * The parsed [[PlainNewMessageEvent]] carries only the functional fields the responder needs: the
 * thread id (to reply into), the customer external id, the sender email (the inbound From — the
 * #2307 email-intake gate key), whether this is a NEW thread vs a continuation, the
 * `tenantIdentifier` (the household id stamped on the customer by the #2199 identified-widget
 * upsert — the UI-origin key the responder gates on), the `actorType` (who authored the message —
 * the #2403 loop-guard key: only `customer`-authored inbound events are acted on), the inbound
 * message text (UNTRUSTED DATA), and a consent flag. Inbound text is quoted to Claude as content,
 * never as instructions (#2200 injection model).
 *
 * #2403 — the wire shape here is Plain's REAL webhook envelope, verified against
 * `core-api.uk.plain.com/webhooks/schema/latest.json`:
 *   - the event type is `payload.eventType` (NOT a top-level `type`);
 *   - the household id rides on `payload.thread.customer.externalId` — the field
 *     `PlainClient.upsertCustomer` writes as `identifier.externalId = household_id`. Plain's thread
 *     payload has NO tenant object (a `tenant` only appears on the dedicated
 *     `thread.thread_tenant_updated` event), so `customer.externalId` is the UI-origin key;
 *   - the message body is on `chat.text` (`thread.chat_received`) or `email.textContent` /
 *     `email.markdownContent` (`thread.email_received`); `thread.thread_created` carries only
 *     thread metadata (no body), so it is a new-thread SIGNAL, not a message.
 */
object PlainWebhook {

  /**
   * The inbound CUSTOMER event types the responder may act on (#2403 loop guard). Everything else —
   * our own outbound `thread.chat_sent` / `thread.email_sent`, notes, status transitions — is
   * ignored so the assistant's own replies can never re-trigger a dispatch (a reply loop). Kept as
   * a `Set` so the responder is the single reader; the actor guard (`actorType == "customer"`) is
   * the complementary second check for a webhook subscribed to all events.
   */
  val InboundCustomerEventTypes: Set[String] =
    Set("thread.thread_created", "thread.chat_received", "thread.email_received")

  val SignatureHeader: String = "Plain-Request-Signature"

  sealed trait VerifyError
  object VerifyError {
    case object MissingSignature extends VerifyError
    case object BadSignature     extends VerifyError
    case object MalformedPayload extends VerifyError
  }

  /**
   * Verify the `Plain-Request-Signature` header against `payload` using `secret`, then parse the
   * new-message event. Constant-time comparison avoids leaking the expected signature via timing.
   */
  def verifyAndParse(
      payload: String,
      sigHeader: Option[String],
      secret: String,
  ): Either[VerifyError, PlainNewMessageEvent] =
    for {
      header <- sigHeader.map(_.trim).filter(_.nonEmpty).toRight(VerifyError.MissingSignature)
      expected = hmacSha256Hex(secret, payload)
      _     <- Either.cond(constantTimeEquals(header, expected), (), VerifyError.BadSignature)
      event <- parseEvent(payload).toRight(VerifyError.MalformedPayload)
    } yield event

  private def hmacSha256Hex(secret: String, data: String): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    mac.doFinal(data.getBytes("UTF-8")).map(b => f"${b & 0xff}%02x").mkString
  }

  /** Length-independent constant-time compare — avoids leaking the signature via timing. */
  private def constantTimeEquals(a: String, b: String): Boolean =
    java.security.MessageDigest.isEqual(a.getBytes("UTF-8"), b.getBytes("UTF-8"))

  /**
   * Parse the Plain webhook envelope (#2403). Plain wraps every event as `{workspaceId, payload:
   * {eventType, ...}, id}`; the inbound-message events carry a `thread` object plus a `chat`
   * (chats) or `email` (emails). We pull fields leniently (absent → None / "") since the exact
   * shape varies by event type — the responder only acts on an inbound customer-authored event that
   * resolves an origin, so a partially-parsed cold event simply falls through to
   * skipped_unauthenticated. A legacy top-level `type` is accepted as a defensive fallback for
   * back-compat.
   */
  private def parseEvent(payload: String): Option[PlainNewMessageEvent] =
    Json.decoder.decodeJson(payload).toOption.flatMap {
      case root: Json.Obj =>
        val inner       = objField(root, "payload").getOrElse(root)
        val eventType   = str(inner, "eventType").orElse(str(root, "type")).getOrElse("")
        val thread      = objField(inner, "thread")
        val customerObj = thread.flatMap(t => objField(t, "customer"))
        // The household id is the customer's externalId — what upsertCustomer writes as
        // `identifier.externalId = household_id`. Plain's thread payload has no tenant object, so
        // this is the UI-origin key. Defensive fallbacks: a thread `tenant.externalId` (only present
        // on tenant-update events) and the legacy `tenantIdentifier` shape.
        val tenant      = customerObj
          .flatMap(c => str(c, "externalId"))
          .orElse(thread.flatMap(t => objField(t, "tenant")).flatMap(o => str(o, "externalId")))
          .orElse(
            thread.flatMap(t => objField(t, "tenantIdentifier")).flatMap(o => str(o, "externalId")),
          )
          .orElse(thread.flatMap(t => str(t, "tenantIdentifier")))
          .filter(_.nonEmpty)
        val threadId    =
          thread.flatMap(t => str(t, "id")).orElse(str(inner, "threadId")).getOrElse("")
        // The Plain customer identifier for a reply/reject write: the externalId (household id) for an
        // identified customer, else the customer's internal id (`c_…`) so a cold-email reply still
        // has a target.
        val customer    = customerObj
          .flatMap(c => str(c, "externalId"))
          .filter(_.nonEmpty)
          .orElse(customerObj.flatMap(c => str(c, "id")))
          .orElse(thread.flatMap(t => str(t, "customerId")))
          .getOrElse("")
        Some(
          PlainNewMessageEvent(
            eventType = eventType,
            threadId = threadId,
            customerExternalId = customer,
            customerEmail = customerEmail(customerObj, inner),
            isNewThread = isNewThread(eventType),
            tenantIdentifier = tenant,
            actorType = actorType(inner, thread),
            messageText = messageText(inner),
            consent = consentFlag(thread, inner),
          ),
        )
      case _              => None
    }

  // Who authored this event (#2403 loop guard). Plain models it as an `actor` on the message
  // component (`chat.createdBy` / `email.createdBy`) — `actorType == "customer"` for a genuine
  // inbound customer message; our own outbound replies and system/agent events carry a `user` /
  // `machineUser` / `system` actor. Falls back to `thread.createdBy` for thread-level events.
  // Absent ⇒ None (the responder treats an absent actor as non-blocking; the event-type allowlist
  // is the primary guard).
  private def actorType(inner: Json.Obj, thread: Option[Json.Obj]): Option[String] =
    objField(inner, "chat")
      .flatMap(c => objField(c, "createdBy"))
      .orElse(objField(inner, "email").flatMap(e => objField(e, "createdBy")))
      .orElse(thread.flatMap(t => objField(t, "createdBy")))
      .flatMap(cb => str(cb, "actorType"))
      .map(_.trim)
      .filter(_.nonEmpty)

  // The sender's From address — the #2307 email-intake gate key. Plain's webhook payloads model a
  // customer email as an `EmailAddress` (`customer.email.email`, per team-plain/typescript-sdk
  // graphql types — the same `{email,isVerified}` shape PlainClient writes on upsert); we also
  // accept a bare `customer.email` string and a top-level `customer` carrier defensively, since the
  // exact envelope varies by event type. The nested `thread.customer.email.email` shape is pinned
  // end-to-end by SupportResponderSpec's `emailPayload`. Absent ⇒ None (no address to gate on, so a
  // new email with no From simply falls through to skipped_unauthenticated).
  private def customerEmail(customerObj: Option[Json.Obj], inner: Json.Obj): Option[String] =
    customerObj
      .flatMap(c => objField(c, "email"))
      .flatMap(e => str(e, "email"))
      .orElse(customerObj.flatMap(c => str(c, "email")))
      .orElse(objField(inner, "customer").flatMap(c => str(c, "email")))
      .map(_.trim)
      .filter(_.nonEmpty)

  // Whether this delivery opens a NEW thread — the #2307 static-reject-on-new-thread signal. Plain
  // fires `thread.thread_created` once, when a thread is first opened; subsequent messages arrive as
  // `thread.chat_received` / `thread.email_received`. The email-intake gate sends its FIXED reject to
  // an unregistered sender ONLY on this new-thread event, so an ongoing thread is not re-rejected on
  // every message (the backscatter guard). Pinned by SupportResponderSpec (the reject tests use
  // `threadCreatedPayload`).
  private def isNewThread(eventType: String): Boolean =
    eventType == "thread.thread_created"

  // The inbound message text — the UNTRUSTED customer content. Try the real Plain carriers in order:
  // a `chat` component's `text` (`thread.chat_received`), an `email` component's `textContent` /
  // `markdownContent` (`thread.email_received`), then legacy fallbacks (a `timelineEntry` component
  // list, a bare `text`/`message`). `thread.thread_created` carries no body ⇒ "".
  private def messageText(inner: Json.Obj): String =
    objField(inner, "chat")
      .flatMap(c => str(c, "text"))
      .orElse(
        objField(inner, "email").flatMap(e =>
          str(e, "textContent").orElse(str(e, "markdownContent")),
        ),
      )
      .orElse(firstComponentText(inner))
      .orElse(str(inner, "text"))
      .orElse(objField(inner, "message").flatMap(m => str(m, "text")))
      .getOrElse("")

  private def firstComponentText(inner: Json.Obj): Option[String] =
    objField(inner, "timelineEntry")
      .flatMap(te => arrField(te, "components"))
      .flatMap(_.collectFirst {
        case c: Json.Obj if objField(c, "componentText").flatMap(t => str(t, "text")).isDefined =>
          objField(c, "componentText").flatMap(t => str(t, "text")).get
      })

  // Consent — the customer opted into per-household data access (#2241). Read from a thread label /
  // attribute or a top-level flag; absent ⇒ false (no data token minted).
  private def consentFlag(thread: Option[Json.Obj], inner: Json.Obj): Boolean =
    boolField(inner, "dataConsent")
      .orElse(thread.flatMap(t => boolField(t, "dataConsent")))
      .getOrElse(false)

  private def str(o: Json.Obj, key: String): Option[String] =
    o.fields.collectFirst { case (k, Json.Str(v)) if k == key => v }

  private def boolField(o: Json.Obj, key: String): Option[Boolean] =
    o.fields.collectFirst { case (k, Json.Bool(v)) if k == key => v }

  private def objField(o: Json.Obj, key: String): Option[Json.Obj] =
    o.fields.collectFirst { case (k, v: Json.Obj) if k == key => v }

  private def arrField(o: Json.Obj, key: String): Option[List[Json]] =
    o.fields.collectFirst { case (k, Json.Arr(v)) if k == key => v.toList }
}

/**
 * The functional slice of a Plain new-message webhook the responder acts on. `tenantIdentifier` is
 * the household id stamped on the Plain customer by the #2199 identified widget (carried on
 * `customer.externalId`) — its presence (and resolution to a real household) is the UI-origin gate.
 * `actorType` is who authored the message (`customer` for a genuine inbound message) — the #2403
 * loop guard drops any non-customer actor. `customerEmail` is the inbound From — the #2307 fallback
 * gate: an inbound email with no resolvable tenant is admitted to the responder iff this address
 * matches a registered household admin, else a NEW thread gets a static reject. `messageText` is
 * UNTRUSTED customer content (#2200 injection model): quoted to Claude as data, never as
 * instructions.
 */
final case class PlainNewMessageEvent(
    eventType: String,
    threadId: String,
    customerExternalId: String,
    customerEmail: Option[String],
    isNewThread: Boolean,
    tenantIdentifier: Option[String],
    actorType: Option[String],
    messageText: String,
    consent: Boolean,
)
