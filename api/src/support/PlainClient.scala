package wifihaven.api.support

import wifihaven.api.SupportConfig
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
 *   - [[writeThread]] is the create-or-reply seam #2200's Claude responder posts AI drafts into. It
 *     ships now (not invented later) so #2200 reuses this exact trait — but it has no producer in
 *     THIS PR (the identity/mapping path only calls `upsertCustomer`).
 */
trait PlainClient {

  /** Upsert a Plain customer for a WifiHaven household. Never fails. */
  def upsertCustomer(req: PlainCustomerUpsert): UIO[PlainOutcome]

  /**
   * Create-or-reply a Plain thread for a customer (#2200 seam — post an AI-drafted reply). Never
   * fails. No producer in this PR; the trait method exists so #2200 wires into it without a wire or
   * trait change.
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

/** A create-or-reply thread write (#2200 seam). `markdown` is the AI-drafted body. */
final case class PlainThreadWrite(
    customerExternalId: String,
    tenantIdentifier: String,
    title: String,
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
  // strings are kept minimal (upsertCustomer's identifier + tenant + attributes). A non-2xx or a
  // GraphQL `errors` array is treated as a failed write (logged, metered Error) — never thrown.
  private final case class GqlRequest(query: String, variables: Json)
  private object GqlRequest {
    given JsonEncoder[GqlRequest] = DeriveJsonEncoder.gen[GqlRequest]
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
      post(UpsertCustomerMutation, upsertCustomerVars(req), "upsertCustomer")
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
      // Nothing household-level to carry ⇒ no tenant write at all.
      if req.fullName.isEmpty && req.attributes.isEmpty then ZIO.unit
      else
        sendForBody(
          UpsertTenantMutation,
          upsertTenantVars(req.tenantIdentifier, req.fullName),
          "upsertTenant",
        ).flatMap {
          case None       => ZIO.unit // already logged
          case Some(body) =>
            tenantIdFrom(body) match {
              case None           =>
                ZIO.logWarning("plain upsertTenant: no tenant id in response; skipping fields").unit
              case Some(tenantId) =>
                ZIO.foreachDiscard(tenantFieldWrites(req, tenantId)) { case (vars, op) =>
                  post(UpsertTenantFieldMutation, vars, op)
                }
            }
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

    private val CreateThreadMutation: String =
      """mutation createThread($input: CreateThreadInput!) {
        |  createThread(input: $input) { thread { id } error { message } }
        |}""".stripMargin

    private def writeThreadVars(req: PlainThreadWrite): Json =
      Json.Obj(
        "input" -> Json.Obj(
          "customerIdentifier" -> Json.Obj("externalId" -> Json.Str(req.customerExternalId)),
          "title"              -> Json.Str(req.title),
          "components"         -> Json.Arr(
            Json.Obj("componentText" -> Json.Obj("text" -> Json.Str(req.markdown))),
          ),
        ),
      )

    def writeThread(req: PlainThreadWrite): UIO[PlainOutcome] =
      post(CreateThreadMutation, writeThreadVars(req), "createThread")

    private def post(query: String, variables: Json, op: String): UIO[PlainOutcome] =
      sendForBody(query, variables, op).map {
        case Some(_) => PlainOutcome.Ok
        case None    => PlainOutcome.Error
      }

    // One blocking HTTPS POST. Returns `Some(body)` on a 2xx with no top-level GraphQL `errors`
    // array (the shared success check), else `None` (logged). Callers that need the response body
    // (tenant-id extraction) read it; `post` just maps presence to Ok/Error.
    private def sendForBody(query: String, variables: Json, op: String): UIO[Option[String]] =
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
        .flatMap { resp =>
          // A 2xx with no top-level GraphQL `errors` array is a success. Plain returns 200 for
          // GraphQL errors too, so inspect the body — but keep it a substring check (no schema
          // coupling): if the body advertises an error we log + meter Error, else Ok.
          val body = resp.body()
          if resp.statusCode() / 100 == 2 && !body.contains("\"errors\"") then
            ZIO.succeed(Some(body))
          else
            ZIO
              .logWarning(
                s"plain $op failed: HTTP ${resp.statusCode()} (body: ${body.take(500)})",
              )
              .as(None)
        }
        .catchAll(e => ZIO.logWarning(s"plain $op errored: ${e.getMessage}").as(None))
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
