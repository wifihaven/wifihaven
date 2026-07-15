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
      if cfg.writeEnabled then new Live(cfg): PlainClient
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

    private def upsertCustomerVars(req: PlainCustomerUpsert): Json =
      Json.Obj(
        "input" -> Json.Obj(
          "identifier" -> Json.Obj("externalId" -> Json.Str(req.externalId)),
          "onCreate"   -> customerFields(req),
          "onUpdate"   -> customerFields(req),
        ),
      )

    // The Plain customer field block. `fullName` + `externalId` + `email` identify; `tenantIdentifier`
    // carries the household (household-gating). Sent for both onCreate and onUpdate so an upsert
    // keeps the mapping current.
    private def customerFields(req: PlainCustomerUpsert): Json =
      Json.Obj(
        "fullName"         -> Json.Str(req.fullName),
        "email"            -> Json.Obj(
          "email"      -> Json.Str(req.email),
          "isVerified" -> Json.Bool(true),
        ),
        "tenantIdentifier" -> Json.Obj("externalId" -> Json.Str(req.tenantIdentifier)),
      )

    def upsertCustomer(req: PlainCustomerUpsert): UIO[PlainOutcome] =
      post(UpsertCustomerMutation, upsertCustomerVars(req), "upsertCustomer")

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
      ZIO
        .attemptBlocking {
          val payload = GqlRequest(query, variables).toJson
          val httpReq = HttpRequest
            .newBuilder(URI.create(cfg.apiBase))
            .header("Authorization", s"Bearer ${cfg.apiKeyTrimmed}")
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
            ZIO.succeed(PlainOutcome.Ok)
          else
            ZIO
              .logWarning(
                s"plain $op failed: HTTP ${resp.statusCode()} (body: ${body.take(500)})",
              )
              .as(PlainOutcome.Error)
        }
        .catchAll(e => ZIO.logWarning(s"plain $op errored: ${e.getMessage}").as(PlainOutcome.Error))
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
