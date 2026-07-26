package wifihaven.api.support

import wifihaven.api.SupportConfig
import wifihaven.api.metrics.AppMetrics
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration as JDuration

/**
 * #2199 (support intake B, epic #2197) — the external Plain write surface, the ONLY thing in this
 * integration that talks to Plain's GraphQL write API over the network (design/umbrella #2206 §1).
 * Deliberately a tiny swappable trait (mirroring the #578 [[wifihaven.api.notify.EmailSender]]
 * pattern) so the support layer depends on "upsert a customer" / "write a thread", not on Plain
 * GraphQL specifics, and feature-tests inject a recorder ([[PlainClient.recording]]) instead of
 * hitting the network.
 *
 * Fail-open by construction: every method returns a UIO that never fails — a transport error is
 * logged and surfaced as [[PlainOutcome.Error]]. Support is best-effort background context; a Plain
 * hiccup must never fail a household's admin request (the identity endpoint) or, later, #2200's
 * draft-post fiber.
 *
 * The two halves:
 *   - [[upsertCustomer]] carries the household→Plain-customer mapping (#2199 scope 3):
 *     `tenantIdentifier = household_id`, `externalId`, plan/entitlement + bounded account context.
 *   - [[writeThread]] is the reply-into-thread seam #2200's Claude responder posts AI drafts into —
 *     Plain's `replyToThread` against the customer's existing thread (#2408), the customer-visible
 *     send.
 */
trait PlainClient {

  /** Upsert a Plain customer for a WifiHaven household. Never fails. */
  def upsertCustomer(req: PlainCustomerUpsert): UIO[PlainOutcome]

  /**
   * Post an AI-drafted reply INTO a customer's existing Plain thread (#2200 seam, #2408 — Plain's
   * `replyToThread`, the customer-visible send). Never fails.
   */
  def writeThread(req: PlainThreadWrite): UIO[PlainOutcome]
}

/**
 * Household → Plain customer mapping payload (#2199 scope 3). `tenantIdentifier` is Plain's native
 * tenant field, set to the household id — the household-gating key. `attributes` is bounded account
 * context (plan/entitlement, household name); NEVER per-device / per-domain data.
 */
final case class PlainCustomerUpsert(
    externalId: String,
    tenantIdentifier: String,
    email: String,
    fullName: String,
    attributes: Map[String, String],
)

/**
 * A reply-into-thread write (#2200 seam). `threadId` is the customer's existing Plain thread the
 * reply posts INTO (#2408 — the customer-visible send, via Plain's `replyToThread`); `markdown` is
 * the AI-drafted body.
 */
final case class PlainThreadWrite(
    threadId: String,
    markdown: String,
)

/** Bounded outcome enum — also the label space for the support metrics (never per-household). */
enum PlainOutcome {
  case Ok
  case Disabled
  case Error
}

object PlainOutcome {
  def label(o: PlainOutcome): String = o match {
    case Ok       => "ok"
    case Disabled => "disabled"
    case Error    => "error"
  }
}

object PlainClient {

  private val UserAgent: String = "wifihaven-api/1 (+https://wifihaven.net)"

  // ── #2410: entitlement-write failure attribution ───────────────────────────
  // The bounded `reason` label on `support_tenant_upsert_total` — WHY the household→Plain
  // entitlement (plan/founding tenant fields) failed to land, so an operator can tell a
  // PROVISIONING GAP (a fix) apart from a transient hiccup (a blip). Enum-bounded, never
  // per-field/per-tenant (the §4 cardinality firewall).
  private object Reason {
    val Ok         = "ok"
    val Permission = "permission"  // machine-user lacks the tenantField:* permission
    val Schema     = "schema"      // the plan/founding tenant-field schema isn't registered
    val Tenant     = "tenant"      // the upsertTenant step itself failed (fields never reached)
    val FieldWrite = "field_write" // a transient / other field-write miss
  }

  // Map a field-write failure detail to its `reason` bucket. Best-effort over the Plain error
  // MESSAGE TEXT: Plain returns HTTP 200 with a payload `error { message }` for BOTH a permission
  // denial and an unregistered field, and the wording — not a stable machine code we can rely on —
  // is all it gives us. So the match is on substrings, and anything unrecognized falls to
  // `field_write` (the transient/other catch-all) rather than inventing precision the API doesn't
  // expose. permission ← auth/forbidden markers; schema ← not-found/unknown-field markers.
  private[support] def classifyFieldFailure(detail: String): String = {
    val d = detail.toLowerCase
    if d.contains("permission") || d.contains("forbidden") || d.contains("unauthorized") ||
      d.contains("not authorized") || d.contains("http 401") || d.contains("http 403")
    then Reason.Permission
    else if d.contains("not found") || d.contains("does not exist") || d.contains("no such") ||
      d.contains("unknown field") || d.contains("unrecognized")
    then Reason.Schema
    else Reason.FieldWrite
  }

  // #2410: entitlement-write failures are LOUD — `logError`, not `logWarning` (the no-dark-by-default
  // bar), carrying the attributed `reason`. permission/schema are a provisioning gap discovered at
  // FIRST WRITE (the prerequisite is Plain-workspace state, not local config, so it can't be checked
  // at boot without a live Plain call — and support boot must stay fail-open), so the message names
  // the fix inline. Fail-open is preserved: this only makes the drop observable; the customer upsert
  // outcome is untouched. The legitimate "entitlement off" state is the EXPLICIT `writeEnabled=false`
  // flag (the Disabled client, logged at config validation), never a silent runtime no-op.
  private def logEntitlementFailure(op: String, reason: String, detail: String): UIO[Unit] = {
    val hint = reason match {
      case Reason.Permission =>
        " — PROVISIONING GAP: the Plain machine-user API key lacks the tenantField:* permission; " +
          "grant it (docs/ops/plain-setup.md §5.1) so plan/founding entitlement reaches Plain"
      case Reason.Schema     =>
        " — PROVISIONING GAP: the plan/founding tenant-field schema is not registered in the Plain " +
          "workspace; register it (docs/ops/plain-setup.md §7.3) so entitlement writes land"
      case _                 => ""
    }
    ZIO.logError(s"plain $op failed [reason=$reason]: $detail$hint")
  }

  // A single small GraphQL POST; shorter than the blocklist fetcher's multi-MB pulls. Support is
  // best-effort and fail-open, so a slow Plain shouldn't tie up the caller's fiber for long.
  private val ConnectTimeout: JDuration = JDuration.ofSeconds(10)
  private val RequestTimeout: JDuration = JDuration.ofSeconds(20)

  /**
   * Config-gated layer. When [[SupportConfig.writeEnabled]] is false (no Plain API key — the
   * self-hosted default and any deployment that hasn't set the key) this yields the no-op
   * [[Disabled]] client whose every call returns [[PlainOutcome.Disabled]] without touching the
   * network — so the write half ships dark. When enabled it yields the live GraphQL client.
   */
  val layer: ZLayer[SupportConfig, Nothing, PlainClient] =
    ZLayer.fromFunction { (cfg: SupportConfig) =>
      if cfg.plain.writeEnabled then new Live(cfg): PlainClient
      else Disabled
    }

  /** No-op client used when the Plain write API is unconfigured. */
  val Disabled: PlainClient = new PlainClient {
    def upsertCustomer(req: PlainCustomerUpsert): UIO[PlainOutcome] =
      ZIO.succeed(PlainOutcome.Disabled)
    def writeThread(req: PlainThreadWrite): UIO[PlainOutcome]       =
      ZIO.succeed(PlainOutcome.Disabled)
  }

  /** Public no-op instance for specs that don't drive Plain. */
  val noop: PlainClient = Disabled

  // ── GraphQL request shapes ─────────────────────────────────────────────────
  // Plain's write API is a single GraphQL endpoint; we send `{query, variables}`. The mutation
  // strings are kept minimal (upsertCustomer's identifier + tenant + attributes). A failed write —
  // non-2xx, a top-level GraphQL `errors` array, a payload-level `error { message }`, or a missing
  // expected result id — is logged with the real Plain cause and metered Error, never thrown.
  private final case class GqlRequest(query: String, variables: Json)
  private object GqlRequest {
    given JsonEncoder[GqlRequest] = DeriveJsonEncoder.gen[GqlRequest]
  }

  /**
   * What a successful Plain mutation payload must contain (#2408). Plain returns HTTP 200 for BOTH
   * transport errors (a top-level `errors` array) AND mutation-level failures (a payload `error {
   * message }` with a null result object), so success is NOT "2xx without the substring errors". A
   * write succeeds only when there is no top-level errors array, no payload error, and — when the
   * mutation returns a result object — the expected `<resultKey>.id` is present.
   *   - `payloadKey`: the mutation field under `data` (e.g. `createThread`, `replyToThread`).
   *   - `resultKey`: the object whose `id` proves the write landed (e.g. `thread`, `customer`), or
   *     `None` for a mutation that returns only `{ error }` (Plain's `replyToThread`).
   */
  private final case class Expect(payloadKey: String, resultKey: Option[String])

  // ── Plain response inspection (#2408) ──────────────────────────────────────
  // Pure navigation over a parsed Plain response; any structural miss is a failure reason (logged),
  // never a throw.
  private def objField(j: Json, key: String): Option[Json] = j match {
    case o: Json.Obj => o.fields.collectFirst { case (k, v) if k == key => v }
    case _           => None
  }

  private def navigate(j: Json, path: List[String]): Option[Json] =
    path.foldLeft(Option(j))((acc, k) => acc.flatMap(objField(_, k)))

  // The concatenated messages of a non-empty top-level GraphQL `errors` array, if present.
  private def topLevelErrors(json: Json): Option[String] =
    objField(json, "errors") match {
      case Some(Json.Arr(items)) if items.nonEmpty =>
        val msgs = items.flatMap(it => objField(it, "message").collect { case Json.Str(m) => m })
        Some(if msgs.isEmpty then "unspecified" else msgs.mkString("; "))
      case _                                       => None
    }

  // A non-null payload `error` object's message (or "unspecified" if it carries none).
  private def payloadError(payload: Json): Option[String] =
    objField(payload, "error").flatMap {
      case Json.Null => None
      case e         =>
        Some(objField(e, "message").collect { case Json.Str(m) => m }.getOrElse("unspecified"))
    }

  // Left(reason) when `body` is NOT a clean success for `expect`; Right(()) otherwise. The reason is
  // the real Plain cause the caller logs so a dropped write is observable instead of reported Ok.
  private def checkPayload(body: String, expect: Expect): Either[String, Unit] =
    Json.decoder.decodeJson(body) match {
      case Left(err)   => Left(s"unparseable response: $err")
      case Right(json) =>
        topLevelErrors(json) match {
          case Some(msg) => Left(s"GraphQL errors: $msg")
          case None      =>
            navigate(json, List("data", expect.payloadKey)) match {
              // Absent OR explicitly null payload — the mutation did not land (defense-in-depth: a
              // null payload with no top-level errors must never read as success, #2408).
              case None | Some(Json.Null) => Left(s"no ${expect.payloadKey} in response")
              case Some(payload)          =>
                payloadError(payload) match {
                  case Some(msg) => Left(s"Plain error: $msg")
                  case None      =>
                    expect.resultKey match {
                      case None     => Right(())
                      case Some(rk) =>
                        navigate(payload, List(rk, "id")) match {
                          case Some(Json.Str(id)) if id.nonEmpty => Right(())
                          case _ => Left(s"missing $rk.id in response")
                        }
                    }
                }
            }
        }
    }

  /**
   * Live Plain GraphQL transport. One blocking HTTPS POST per call (same JDK-HttpClient /
   * `attemptBlocking` shape as [[wifihaven.api.billing.StripeClient]] — no new build dependency).
   * Any non-2xx, GraphQL error, or thrown error is logged and mapped to [[PlainOutcome.Error]]; no
   * method ever fails.
   */
  final class Live(cfg: SupportConfig) extends PlainClient {
    private val client = HttpClient
      .newBuilder()
      .connectTimeout(ConnectTimeout)
      .build()

    // Plain's upsertCustomer mutation, keyed on our externalId. Attributes/tenant ride as variables.
    private val UpsertCustomerMutation: String =
      """mutation upsertCustomer($input: UpsertCustomerInput!) {
        |  upsertCustomer(input: $input) { customer { id } error { message } }
        |}""".stripMargin

    // `onCreate` and `onUpdate` are DIFFERENT Plain input types (`UpsertCustomerOnCreateInput` vs
    // `UpsertCustomerOnUpdateInput`), so they take different field shapes — see the two builders
    // below. Sending the create shape to onUpdate is exactly what 400'd on staging (#2253).
    private def upsertCustomerVars(req: PlainCustomerUpsert): Json =
      Json.Obj(
        "input" -> Json.Obj(
          "identifier" -> Json.Obj("externalId" -> Json.Str(req.externalId)),
          "onCreate"   -> customerCreateFields(req),
          "onUpdate"   -> customerUpdateFields(req),
        ),
      )

    // Plain's `EmailAddressInput` — `{ email, isVerified }`. Shared by onCreate and onUpdate.
    private def emailInput(email: String): Json =
      Json.Obj("email" -> Json.Str(email), "isVerified" -> Json.Bool(true))

    // `UpsertCustomerOnCreateInput` (Plain schema, team-plain/typescript-sdk src/graphql/types.ts):
    //   fullName: String            — plain scalar
    //   email: EmailAddressInput     — { email, isVerified }
    //   tenantIdentifiers: [TenantIdentifierInput]  — PLURAL, a LIST of { externalId } (household id).
    // The tenant list is what scopes the customer to the household (household-gating); it lives ONLY
    // on the create input — Plain's update input has no tenantIdentifiers field (membership is set at
    // create), so re-asserting it on update is a schema error (#2253).
    // #2240: `req.attributes` (plan / founding / householdName) are NOT customer fields. Plain's
    // customer input has NO attributes/customFields channel (only the fields above), so entitlement
    // cannot ride on the customer. It is HOUSEHOLD-level context and rides on the Plain *tenant*
    // instead (upsertTenant name + upsertTenantField) — see `upsertTenantEntitlement` below.
    private def customerCreateFields(req: PlainCustomerUpsert): Json =
      Json.Obj(
        "fullName"          -> Json.Str(req.fullName),
        "email"             -> emailInput(req.email),
        "tenantIdentifiers" -> Json.Arr(
          Json.Obj("externalId" -> Json.Str(req.tenantIdentifier)),
        ),
      )

    // `UpsertCustomerOnUpdateInput` (same source): every field is optional and WRAPPED —
    //   fullName: StringInput        — { value: String }, NOT a bare scalar (this was the second
    //                                  #2253 400, on the onUpdate path)
    //   email: EmailAddressInput     — { email, isVerified }
    // There is NO tenantIdentifiers on the update input, so it is deliberately omitted — the tenant
    // mapping is carried by onCreate above and persists across upserts.
    private def customerUpdateFields(req: PlainCustomerUpsert): Json =
      Json.Obj(
        "fullName" -> Json.Obj("value" -> Json.Str(req.fullName)),
        "email"    -> emailInput(req.email),
      )

    def upsertCustomer(req: PlainCustomerUpsert): UIO[PlainOutcome] =
      // The customer upsert is the primary write and its outcome is what we return/meter. The
      // tenant entitlement (household name + plan/founding fields) is a best-effort follow-on that
      // must never flip the customer outcome — a missing tenant-field schema (not yet registered at
      // go-live) or a tenant hiccup is logged and ignored.
      post(
        UpsertCustomerMutation,
        upsertCustomerVars(req),
        "upsertCustomer",
        Expect("upsertCustomer", Some("customer")),
      )
        .zipLeft(upsertTenantEntitlement(req))

    // ── Tenant entitlement (#2240) ─────────────────────────────────────────────
    // Plain custom entitlement is HOUSEHOLD-level, and the household maps to a Plain *tenant*
    // (`tenantIdentifier = household id`). The household NAME is the tenant's first-class `name`
    // (upsertTenant); `plan` (billing status) and `founding` ride as Plain *tenant fields*
    // (upsertTenantField), each keyed on an operator-registered `externalFieldId` — the "pre-
    // registered field" go-live step (#2240). A tenant field needs the tenant's INTERNAL id, which
    // upsertTenant returns, so this is: upsertTenant → read tenant.id → upsertTenantField per field.
    // Field external ids the operator registers in the workspace (docs/ops/plain-setup.md §7.3):
    private val PlanFieldId     = "plan"     // TenantFieldType.STRING_TYPE
    private val FoundingFieldId = "founding" // TenantFieldType.BOOLEAN_TYPE

    private val UpsertTenantMutation: String =
      """mutation upsertTenant($input: UpsertTenantInput!) {
        |  upsertTenant(input: $input) { tenant { id } error { message } }
        |}""".stripMargin

    private val UpsertTenantFieldMutation: String =
      """mutation upsertTenantField($input: UpsertTenantFieldInput!) {
        |  upsertTenantField(input: $input) { tenantField { id } error { message } }
        |}""".stripMargin

    // `UpsertTenantInput` (Plain schema): { identifier: TenantIdentifierInput, externalId: String,
    // name: String, url? }. Both `identifier.externalId` and the top-level `externalId` are the
    // household id; `name` is the household name.
    private def upsertTenantVars(externalId: String, name: String): Json =
      Json.Obj(
        "input" -> Json.Obj(
          "identifier" -> Json.Obj("externalId" -> Json.Str(externalId)),
          "externalId" -> Json.Str(externalId),
          "name"       -> Json.Str(name),
        ),
      )

    // `UpsertTenantFieldInput`: { tenantFieldIdentifier: { tenantId, externalFieldId }, type,
    // <typed>Value }. `tenantId` is Plain's INTERNAL id (from upsertTenant), not our externalId.
    private def upsertTenantFieldVars(
        tenantId: String,
        externalFieldId: String,
        fieldType: String,
        valueKey: String,
        value: Json,
    ): Json =
      Json.Obj(
        "input" -> Json.Obj(
          "tenantFieldIdentifier" -> Json.Obj(
            "tenantId"        -> Json.Str(tenantId),
            "externalFieldId" -> Json.Str(externalFieldId),
          ),
          "type"                  -> Json.Str(fieldType),
          valueKey                -> value,
        ),
      )

    // Build the (mutation-vars, op-label) list for the entitlement fields present on this upsert.
    // `householdName` in `attributes` is NOT a field — it is the tenant `name` (set by upsertTenant).
    private def tenantFieldWrites(
        req: PlainCustomerUpsert,
        tenantId: String,
    ): List[(Json, String)] = {
      val plan     = req.attributes.get("plan").map { p =>
        upsertTenantFieldVars(tenantId, PlanFieldId, "STRING_TYPE", "stringValue", Json.Str(p)) ->
          "upsertTenantField(plan)"
      }
      val founding = req.attributes.get("founding").map { f =>
        upsertTenantFieldVars(
          tenantId,
          FoundingFieldId,
          "BOOLEAN_TYPE",
          "booleanValue",
          Json.Bool(f == "true"),
        ) -> "upsertTenantField(founding)"
      }
      List(plan, founding).flatten
    }

    private def upsertTenantEntitlement(req: PlainCustomerUpsert): UIO[Unit] =
      // Nothing household-level to carry ⇒ no tenant write at all (and nothing to meter).
      if req.fullName.isEmpty && req.attributes.isEmpty then ZIO.unit
      else
        // Aggregate outcome + attributed reason (#2410): `ok`/`ok` only when the tenant upsert AND
        // every field write succeed; `error`/<reason> if any step fails — `tenant` for the tenant
        // step, `permission`/`schema`/`field_write` for a field write. Each failure is logged LOUD
        // (logError) and metered so a silently-failing entitlement path is visible beyond the logs.
        // Fail-open is preserved: this whole method rides as a `zipLeft` follow-on and never flips
        // the customer upsert outcome.
        sendForBody(
          UpsertTenantMutation,
          upsertTenantVars(req.tenantIdentifier, req.fullName),
          Expect("upsertTenant", Some("tenant")),
        ).flatMap {
          case Left(detail) =>
            // The tenant step itself failed — the fields are never reached. Attributed `tenant`.
            logEntitlementFailure("upsertTenant", Reason.Tenant, detail)
              .as((PlainOutcome.Error, Reason.Tenant))
          case Right(body)  =>
            tenantIdFrom(body) match {
              case None           =>
                logEntitlementFailure(
                  "upsertTenant",
                  Reason.Tenant,
                  "no tenant id in response; skipping fields",
                ).as((PlainOutcome.Error, Reason.Tenant))
              case Some(tenantId) =>
                ZIO
                  .foreach(tenantFieldWrites(req, tenantId)) { case (vars, op) =>
                    sendForBody(
                      UpsertTenantFieldMutation,
                      vars,
                      Expect("upsertTenantField", Some("tenantField")),
                    ).flatMap {
                      case Right(_)     => ZIO.succeed(None)
                      case Left(detail) =>
                        val reason = classifyFieldFailure(detail)
                        logEntitlementFailure(op, reason, detail).as(Some(reason))
                    }
                  }
                  // First failure attributes the aggregate; a permission/schema gap fails every
                  // field identically, so the first is representative.
                  .map(_.flatten.headOption match {
                    case None         => (PlainOutcome.Ok, Reason.Ok)
                    case Some(reason) => (PlainOutcome.Error, reason)
                  })
            }
        }.flatMap { case (o, reason) =>
          AppMetrics.supportTenantUpsert(PlainOutcome.label(o), reason)
        }

    // Navigate `data.upsertTenant.tenant.id` out of the response body. Best-effort: any parse miss
    // yields None (logged by the caller), never throws.
    private def tenantIdFrom(body: String): Option[String] =
      Json.decoder.decodeJson(body).toOption.flatMap { j =>
        List("data", "upsertTenant", "tenant", "id")
          .foldLeft(Option(j)) { (acc, key) =>
            acc.flatMap {
              case o: Json.Obj => o.fields.collectFirst { case (k, v) if k == key => v }
              case _           => None
            }
          }
          .collect { case Json.Str(s) => s }
      }

    // #2408: post the reply INTO the customer's existing thread. Plain's `replyToThread` sends
    // through the thread's channel (chat/email) as a customer-visible message — unlike `createThread`
    // which opens a NEW thread the customer never sees on their conversation. It returns only
    // `{ error }` (no result object), so the success check is `resultKey = None`.
    private val ReplyToThreadMutation: String =
      """mutation replyToThread($input: ReplyToThreadInput!) {
        |  replyToThread(input: $input) { error { message } }
        |}""".stripMargin

    // `ReplyToThreadInput` (Plain schema, team-plain/typescript-sdk src/graphql/types.ts):
    //   threadId: ID!            — the existing thread the reply posts into (the token binding)
    //   textContent: String!     — REQUIRED plain-text body (Plain's fallback rendering)
    //   markdownContent: String  — optional rich body; our draft IS markdown, so send it as both.
    private def writeThreadVars(req: PlainThreadWrite): Json =
      Json.Obj(
        "input" -> Json.Obj(
          "threadId"        -> Json.Str(req.threadId),
          "textContent"     -> Json.Str(req.markdown),
          "markdownContent" -> Json.Str(req.markdown),
        ),
      )

    def writeThread(req: PlainThreadWrite): UIO[PlainOutcome] =
      post(
        ReplyToThreadMutation,
        writeThreadVars(req),
        "replyToThread",
        Expect("replyToThread", None),
      )

    private def post(
        query: String,
        variables: Json,
        op: String,
        expect: Expect,
    ): UIO[PlainOutcome] =
      sendForBody(query, variables, expect).flatMap {
        case Right(_)      => ZIO.succeed(PlainOutcome.Ok)
        // Customer/thread writes are fail-open best-effort context: a miss is logged at warning (not
        // error) and mapped to Error. Entitlement writes DON'T go through `post` — they call
        // `sendForBody` directly and log LOUD (logError) with an attributed reason (#2410).
        case Left(problem) => ZIO.logWarning(s"plain $op failed: $problem").as(PlainOutcome.Error)
      }

    // One blocking HTTPS POST. Returns `Right(body)` when the response is a clean success for
    // `expect` (2xx, no top-level errors, no payload error, expected result id present), else
    // `Left(detail)` carrying the REAL Plain cause so the caller can log AND attribute the failure
    // (#2408 observability, #2410 reason attribution). This method no longer logs — the caller owns
    // the level (warning for fail-open customer/thread writes, error for entitlement writes).
    private def sendForBody(
        query: String,
        variables: Json,
        expect: Expect,
    ): UIO[Either[String, String]] =
      ZIO
        .attemptBlocking {
          val payload = GqlRequest(query, variables).toJson
          val httpReq = HttpRequest
            .newBuilder(URI.create(cfg.plain.apiBase))
            .header("Authorization", s"Bearer ${cfg.plain.apiKeyTrimmed}")
            .header("Content-Type", "application/json")
            .header("User-Agent", UserAgent)
            .timeout(RequestTimeout)
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
          client.send(httpReq, HttpResponse.BodyHandlers.ofString())
        }
        .map { resp =>
          val body = resp.body()
          if resp.statusCode() / 100 != 2 then
            Left(s"HTTP ${resp.statusCode()} (body: ${body.take(500)})")
          else
            // Plain returns 200 for mutation-level failures too, so inspect the payload: a top-level
            // `errors` array, a payload `error { message }`, or a missing result id is a failed write.
            checkPayload(body, expect) match {
              case Right(_)      => Right(body)
              case Left(problem) => Left(s"$problem (body: ${body.take(500)})")
            }
        }
        .catchAll(e => ZIO.succeed(Left(s"transport error: ${e.getMessage}")))
  }

  /**
   * Test/inspection client: records every customer upsert + thread write into `Ref`s and always
   * reports [[PlainOutcome.Ok]]. Used by the feature suite to assert the mapping carried the right
   * `tenantIdentifier` + entitlement attributes without a network.
   */
  final case class Recorder(
      customers: Ref[List[PlainCustomerUpsert]],
      threads: Ref[List[PlainThreadWrite]],
  )

  def recording(rec: Recorder): PlainClient = new PlainClient {
    def upsertCustomer(req: PlainCustomerUpsert): UIO[PlainOutcome] =
      rec.customers.update(_ :+ req).as(PlainOutcome.Ok)
    def writeThread(req: PlainThreadWrite): UIO[PlainOutcome]       =
      rec.threads.update(_ :+ req).as(PlainOutcome.Ok)
  }

  def recorder: UIO[Recorder] =
    for {
      c <- Ref.make(List.empty[PlainCustomerUpsert])
      t <- Ref.make(List.empty[PlainThreadWrite])
    } yield Recorder(c, t)
}
