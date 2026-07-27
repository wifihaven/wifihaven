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
 *     It also RECONCILES the one collision Plain's own data model forces on us (#2435): a customer
 *     Plain auto-created from an inbound support email holds the admin's address under no
 *     `externalId`, so the externalId-keyed create collides permanently until we bind it.
 *   - [[writeThread]] is the reply-into-thread seam #2200's Claude responder posts AI drafts into —
 *     Plain's `replyToThread` against the customer's existing thread (#2408), the customer-visible
 *     send.
 */
trait PlainClient {

  /**
   * Upsert a Plain customer for a WifiHaven household, reconciling an email-keyed customer Plain
   * already holds onto that household (#2435). Never fails — but the returned outcome is real: an
   * [[PlainOutcome.Error]] means the household→customer mapping did NOT land.
   */
  def upsertCustomer(req: PlainCustomerUpsert): UIO[PlainOutcome]

  /**
   * Post an AI-drafted reply INTO a customer's existing Plain thread (#2200 seam, #2408 — Plain's
   * `replyToThread`, the customer-visible send). Never fails.
   */
  def writeThread(req: PlainThreadWrite): UIO[PlainOutcome]

  /**
   * #2430 — read the PRIOR conversation on ONE Plain thread, oldest-first, so the per-message cloud
   * dispatch can carry context (the responder is stateless: every inbound message fires a fresh
   * session). Scoped to the single `threadId` the dispatch is bound to — there is no parameter
   * through which another thread, customer, or household could be read.
   *
   * The returned text is UNTRUSTED CUSTOMER/OPERATOR DATA; the caller is responsible for framing
   * and bounding it ([[wifihaven.api.support.CloudAgentDispatcher.kickoffPrompt]]). Never fails: an
   * unconfigured client, a permission gap, or a transport error yields `Nil` so the webhook still
   * dispatches with the latest message alone.
   */
  def threadHistory(threadId: String, limit: Int): UIO[List[PlainThreadMessage]]

  /**
   * #2437 — MARK a thread so the operator's inbox is filterable: apply Plain label type(s) to the
   * thread (Plain's `addLabels`). The one write in this trait that is not customer-visible — it
   * changes inbox metadata, never the conversation. Used when the support agent escalates, so
   * "waiting on a human" is a filter instead of a full read of every thread. Never fails.
   */
  def markThread(req: PlainThreadMark): UIO[PlainOutcome]

  /**
   * #2452 — read the permission array of the API key we are authenticating WITH. The one
   * prerequisite of this integration that lives in Plain-workspace state rather than local config,
   * so it cannot be checked by `AppConfig.validateRequired`; [[PlainPermissionAudit]] runs this at
   * boot and reports every gap. Never fails, and never reports a false gap: a transient outage
   * yields [[PlainPermissionRead.Unreachable]] and a rejected credential
   * [[PlainPermissionRead.Broken]] — three distinct answers, not one.
   */
  def grantedPermissions: UIO[PlainPermissionRead]
}

/**
 * #2452 — the outcome of reading the machine-user key's own permission array. Three failure shapes,
 * split because they need three different operator actions and conflating them is exactly how this
 * class of defect stays invisible:
 *
 *   - [[Granted]] — Plain answered with the key's array (which may still be missing entries).
 *   - [[Broken]] — PERMANENT. Plain rejected the credential itself (401/403 — a revoked, rotated,
 *     or wrong key) or its own probe shape drifted. Never self-heals; the whole integration is
 *     down, not just the audit. This is the "broken credential ⇒ we should be broken too" case in
 *     docs/process/no-dark-by-default.md, so it is reported LOUD, not as an outage.
 *   - [[Unreachable]] — TRANSIENT. Transport error, timeout, 5xx. Says nothing about the grants.
 *
 * The `Broken`/`Unreachable` line is the SAME permanent-vs-transient line #2416 draws at the
 * cloud-agent boundary, and it is drawn by literally the same predicate —
 * `CloudAgentObservability.isPermanentClientStatus` — so the two boundaries cannot drift
 * (docs/process/single-source-of-truth.md).
 */
enum PlainPermissionRead {
  case Granted(permissions: Set[String])
  case Broken(detail: String)
  case Unreachable(detail: String)
  case NotConfigured
}

/**
 * Who authored one prior turn on a support thread (#2430). Derived from Plain's timeline-entry
 * ACTOR, so the agent can tell its own earlier answers from the customer's words — and can see that
 * a HUMAN TEAMMATE already took the thread over (the escalation/handoff signal).
 */
enum ThreadMessageRole {
  case Customer
  case AiAssistant
  case HumanTeammate
}

object ThreadMessageRole {

  /** The label rendered into the kickoff transcript — a closed set, never free text. */
  def label(r: ThreadMessageRole): String = r match {
    case Customer      => "customer"
    case AiAssistant   => "ai_assistant"
    case HumanTeammate => "human_teammate"
  }
}

/** One prior turn on a support thread (#2430). `text` is UNTRUSTED. */
final case class PlainThreadMessage(role: ThreadMessageRole, text: String)

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

/**
 * #2437 — a thread MARK (Plain `addLabels`). `threadId` is the thread to label — always the
 * token-bound one, never anything an agent supplied; `labelTypeIds` are Plain label TYPE ids
 * (`lt_…`) from config, because Plain's `addLabels` has no name-based form
 * (https://www.plain.com/docs — "You can add multiple labels to a thread with a call to
 * addLabels").
 */
final case class PlainThreadMark(
    threadId: String,
    labelTypeIds: List[String],
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

  // ── #2435: customer↔household mapping attribution ──────────────────────────
  // The bounded `reason` label on `support_customer_upsert_total` — WHY the household→Plain
  // CUSTOMER mapping landed (or didn't). Distinct from the tenant/entitlement vocabulary above
  // because the failure modes are different: this path's signature failure is Plain's workspace-wide
  // email uniqueness, and it is PERMANENT (the email is taken forever, so it never self-heals).
  private[api] object CustomerReason {
    val Ok             = "ok"              // upserted on the first try (the common path)
    val Reconciled     = "reconciled"      // collided on email, then reconciled onto the household
    val EmailCollision = "email_collision" // collided AND the reconcile failed — a broken mapping
    val Permission     = "permission"      // machine-user lacks customer:edit / membership perms
    val Schema         = "schema"          // the customer mutation no longer matches Plain's schema
    // The membership join had no TENANT to join to because the tenant write itself failed. Its own
    // bucket rather than `email_collision` (review): that bucket means "collided with no more
    // specific cause", and here the cause is specific, already attributed on
    // `support_tenant_upsert_total{reason=tenant}` — and the collision bucket's remediation text
    // names `customer:edit` / `customerTenantMembership:create`, which are the WRONG permissions to
    // send an operator to for a failed `upsertTenant`.
    val TenantMissing  = "tenant_missing"
    val Error          = "error"           // a transient / other miss
    val Disabled       = "disabled"        // the Plain write API is explicitly off (#2266)
  }

  /**
   * Plain's fixed error code for the workspace-wide customer-email uniqueness violation
   * (plain.com/docs/graphql/error-codes: "A customer with this email already exists in the
   * workspace and can't be created again"). Matched on the CODE, not the English `message` —
   * `MutationError` documents `code` as "a fixed error code that can be used to handle this error",
   * so unlike the #2410 tenant-field classification (where Plain gives us only prose) this branch
   * is contract, not a substring guess.
   */
  private[api] val EmailCollisionCode: String = "customer_already_exists_with_email"

  /**
   * Plain's fixed code for a referenced entity that isn't there. Quoted verbatim from
   * plain.com/docs/graphql/error-codes: "An entity referenced in the request is not found. For
   * example trying to create an issue for a customer that doesn't exist."
   *
   * Note what the source does and does not say: the entity is MISSING, with no statement about
   * whether it will appear later. So this code alone cannot tell a transient miss from a permanent
   * one — [[Live.joinFailure]] takes that from the threaded [[TenantWrite]] instead. (An earlier
   * revision paraphrased this as "is unavailable", which read as evidence for transience the docs
   * never offer; review run 3 caught it. The wording matters because "not found" is precisely the
   * token [[classifyFieldFailure]] routes to `schema`.)
   */
  private val NotFoundCode: String = "not_found"

  /**
   * Did the Plain TENANT the #2435 membership join targets get written on this call? Threaded from
   * [[Live.upsertTenantEntitlement]] into [[Live.joinFailure]] so a missing join target can be
   * attributed from what the CALL SITE knows, instead of guessed at from Plain's `not_found` code
   * (which reports that a referenced entity is missing, never why).
   *
   * `Written` tracks the `upsertTenant` step ONLY. A failed tenant-FIELD write leaves the tenant
   * itself in place, and the join targets the tenant, not its fields.
   */
  private enum TenantWrite {

    /** No household context to carry, so no tenant write was attempted — transient. */
    case Skipped

    /** `upsertTenant` landed; the join has a target. */
    case Written

    /** `upsertTenant` itself failed — no target, and it is already logError'd + metered (#2410). */
    case Failed
  }

  /**
   * `classifyFieldFailure`'s prose buckets projected onto the #2435 customer vocabulary, so the
   * primary upsert and both reconcile legs share ONE mapping instead of three hand-copied matches
   * (review NIT — a reason added to `classifyFieldFailure` had to be threaded into each copy). Only
   * the FALLBACK differs per call site: an unrecognized failure on the primary upsert is a
   * transient `error`, on a reconcile leg it is an unreconcilable `email_collision`.
   */
  private def customerReason(detail: String, fallback: String): String =
    classifyFieldFailure(detail) match {
      case Reason.Permission => CustomerReason.Permission
      case Reason.Schema     => CustomerReason.Schema
      case _                 => fallback
    }

  // Map a field-write failure detail to its `reason` bucket. Best-effort over the Plain error
  // MESSAGE TEXT: Plain returns HTTP 200 with a payload `error { message }` for BOTH a permission
  // denial and an unregistered field, and the wording — not a stable machine code we can rely on —
  // is all it gives us. So the match is on substrings, and anything unrecognized falls to
  // `field_write` (the transient/other catch-all) rather than inventing precision the API doesn't
  // expose. permission ← auth/forbidden markers; schema ← not-found/unknown-field markers.
  private[api] def classifyFieldFailure(detail: String): String = {
    val d = detail.toLowerCase
    if d.contains("permission") || d.contains("forbidden") || d.contains("unauthorized") ||
      d.contains("not authorized") || d.contains("http 401") || d.contains("http 403")
    then Reason.Permission
    else if d.contains("not found") || d.contains("does not exist") || d.contains("no such") ||
      d.contains("unknown field") || d.contains("unrecognized") ||
      // GraphQL query VALIDATION errors — how Plain reports a field/type we asked for that its
      // schema no longer has (#2430's thread-timeline read). Same class as an unregistered field:
      // a drift that will never self-heal, not a transient blip.
      d.contains("cannot query field") || d.contains("did you mean")
    then Reason.Schema
    else Reason.FieldWrite
  }

  // ── #2452: name the permission Plain ACTUALLY named ────────────────────────
  // Plain reports a denial as `Insufficient permissions, missing "timeline:read".` The quoted token
  // is Plain's OWN permission identifier — not customer data — so it is safe to log even though the
  // response body stays redacted (`redactBody`), and it is the only thing that tells an operator
  // what to grant.
  //
  // The match is deliberately narrow: `missing "<word>:<word>"`, i.e. a permission-SHAPED token
  // only. A looser "text after `missing`" would lift arbitrary response prose — which on the
  // thread-timeline path can be customer conversation text — straight past the redaction into Loki.
  // Anything that does not match yields None, and the caller falls back to a generic hint rather
  // than a confidently wrong permission name (the #2452 sub-defect: the old hardcoded `thread:read`
  // was the operator's ONLY signal, and it was wrong).
  private val MissingPermissionPattern = """missing\s+"([A-Za-z]+:[A-Za-z]+)"""".r

  private[api] def missingPermissionName(detail: String): Option[String] =
    MissingPermissionPattern.findFirstMatchIn(detail).map(_.group(1))

  /** The operator-facing "what to grant" hint for a permission denial. Never guesses. */
  private[api] def permissionGapHint(detail: String): String =
    missingPermissionName(detail) match {
      case Some(perm) =>
        s"the Plain machine-user API key lacks the `$perm` permission; grant it with the " +
          "`updateApiKey` mutation in docs/ops/plain-setup.md §5.3 (permissions are REPLACED in " +
          "full — send the complete set from §5.1)"
      case None       =>
        "the Plain machine-user API key lacks a required permission (Plain did not name it in a " +
          "form we can quote); re-run the `updateApiKey` mutation with the full set from " +
          "docs/ops/plain-setup.md §5.1"
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

  // #2435: the customer↔household mapping is not "best-effort context" the way a plan field is — if
  // it never lands, the household's widget messages carry no resolvable tenant and the responder
  // silently falls through to the email-intake fallback. A PERMANENT failure (an unreconcilable
  // email collision, a missing permission, schema drift) is therefore a misconfiguration-class
  // failure: logError with the fix named inline, so it is visible beyond a WARN nobody reads. Only
  // the transient bucket stays a warning. Fail-open is preserved either way — the identity endpoint
  // still answers; this only makes a broken mapping observable.
  //
  // `detail` is passed through [[redactBody]] first (review NIT): it carries up to 500 chars of the
  // raw Plain response, and on THIS path the request we sent — and so plausibly the response that
  // echoes it — contains the household admin's EMAIL ADDRESS. Same reasoning as the #2430
  // thread-timeline path.
  //
  // What survives redaction is the PROSE half — the Plain error message — because that is what an
  // operator acts on. Note this method is shared by the primary upsert, both reconcile legs and the
  // membership join, so several different messages flow through it. The one we have actually observed
  // carrying no address is the COLLISION message ("A customer already exists with the provided email
  // address", captured live on staging in #2435 and pinned in [[SupportCustomerReconcileSpec]]). That
  // is an observation about one message on one path, NOT a guarantee about every Plain message —
  // accepted risk, called out here rather than asserted as a property of the API we cannot verify.
  private def logCustomerFailure(op: String, reason: String, rawDetail: String): UIO[Unit] = {
    val detail = redactBody(rawDetail)
    reason match {
      case CustomerReason.EmailCollision =>
        ZIO.logError(
          s"plain $op failed [reason=$reason]: $detail — the household→Plain customer mapping is " +
            "BROKEN and will not self-heal: a Plain customer already holds this email with no " +
            "externalId (auto-created from an inbound support email) and the reconcile could not " +
            "bind it to the household. Widget messages from this customer resolve no tenant; " +
            "check the machine-user's customer:edit + customerTenantMembership:create permissions " +
            "(docs/ops/plain-setup.md §5.1)",
        )
      case CustomerReason.Permission     =>
        ZIO.logError(
          s"plain $op failed [reason=$reason]: $detail — PROVISIONING GAP: the Plain machine-user " +
            "API key lacks a permission this write needs — `customer:edit` for the upsert, " +
            "`customerTenantMembership:create` for the household-membership join; grant it " +
            "(docs/ops/plain-setup.md §5.1) so the household→customer mapping can be written",
        )
      case CustomerReason.Schema         =>
        ZIO.logError(
          s"plain $op failed [reason=$reason]: $detail — SCHEMA DRIFT: Plain rejected the customer " +
            "mutation; PlainClient's UpsertCustomerInput shape no longer matches Plain's schema " +
            "and NO household→customer mapping is being written",
        )
      case CustomerReason.TenantMissing  =>
        // Deliberately does NOT repeat the collision text's customer:edit /
        // customerTenantMembership:create remediation — those are the wrong permissions here. The
        // cause is the tenant write, which logged its own attributed failure moments earlier.
        ZIO.logError(
          s"plain $op failed [reason=$reason]: $detail — the household→Plain customer mapping is " +
            "BROKEN because the Plain TENANT it joins to was never written: see the preceding " +
            "`plain upsertTenant failed` line (and support_tenant_upsert_total{reason=tenant}) for " +
            "the actual cause — this join cannot succeed until that write does",
        )
      case _                             =>
        ZIO.logWarning(s"plain $op failed [reason=$reason]: $detail")
    }
  }

  // A single small GraphQL POST; shorter than the blocklist fetcher's multi-MB pulls. Support is
  // best-effort and fail-open, so a slow Plain shouldn't tie up the caller's fiber for long.
  private val ConnectTimeout: JDuration = JDuration.ofSeconds(10)
  private val RequestTimeout: JDuration = JDuration.ofSeconds(20)

  /**
   * #2430 — how many of a thread's most recent timeline entries we ask Plain for. Deliberately
   * larger than the kickoff's message cap ([[CloudAgentDispatcher.MaxHistoryMessages]]) because the
   * timeline also carries non-message entries (status transitions, notes) that we discard, so the
   * fetch window has to over-read to fill the render window. Bounded on BOTH sides: this caps what
   * Plain returns, the render caps what reaches the prompt.
   */
  val HistoryFetchLimit: Int = 30

  // A hard ZIO-level bound on the history read, on top of the HTTP client's own timeouts. History
  // is pure enrichment: a slow Plain must degrade to "no history" quickly rather than hold the
  // webhook fiber open (Plain retry-storms a slow/5xx webhook). `disconnect` so the CALLER returns
  // immediately instead of waiting on the uninterruptible blocking send — note the send itself is
  // not interruptible, so the borrowed blocking thread is released on the HTTP client's own
  // `RequestTimeout` (20s), not at 8s. What is bounded here is the webhook's latency, not the
  // thread's. `private[support]` so callers that reason about this read's latency (the #2460 consent
  // resume) can cite the value instead of re-stating "8s" in prose and letting the two drift.
  private[support] val HistoryTimeout: Duration = 8.seconds

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
    def upsertCustomer(req: PlainCustomerUpsert): UIO[PlainOutcome]                =
      AppMetrics
        .supportCustomerUpsert(PlainOutcome.label(PlainOutcome.Disabled), CustomerReason.Disabled)
        .as(PlainOutcome.Disabled)
    def writeThread(req: PlainThreadWrite): UIO[PlainOutcome]                      =
      ZIO.succeed(PlainOutcome.Disabled)
    def threadHistory(threadId: String, limit: Int): UIO[List[PlainThreadMessage]] =
      AppMetrics.supportThreadHistory("disabled").as(Nil)
    def markThread(req: PlainThreadMark): UIO[PlainOutcome]                        =
      ZIO.succeed(PlainOutcome.Disabled)
    def grantedPermissions: UIO[PlainPermissionRead]                               =
      ZIO.succeed(PlainPermissionRead.NotConfigured)
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

  /**
   * A failed Plain call. `detail` is the human-readable cause the caller logs (it already carries a
   * truncated `(body: …)` tail, stripped on the conversation-bearing path by [[redactBody]]).
   * `body` is the RAW response when there was one — carried so a caller can branch on Plain's fixed
   * `error.code` (#2435's email collision) instead of re-parsing prose out of `detail`. `None` for
   * a transport error or a timeout, where no response body exists.
   */
  private final case class PlainFailure(detail: String, body: Option[String])

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

  // Plain's fixed `error.code` on a mutation payload, if the response carries one (#2435). Best
  // effort over an already-parsed shape: any structural miss is None, never a throw.
  private[api] def payloadErrorCode(body: String, payloadKey: String): Option[String] =
    Json.decoder
      .decodeJson(body)
      .toOption
      .flatMap(navigate(_, List("data", payloadKey, "error", "code")))
      .collect { case Json.Str(c) => c }

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

  // ── #2430: thread-timeline read → role-labeled prior turns ─────────────────
  // Plain's timeline is a union: each entry carries an ACTOR (who) and an ENTRY (what). We keep
  // only the two customer-visible message kinds — `ChatEntry` (the in-app widget) and `EmailEntry`
  // (the #2198 email intake) — and DISCARD everything else, including `NoteEntry`: internal notes
  // are operator-only commentary and must never be fed to the responder (they are not part of the
  // customer conversation, and putting them in the prompt risks them leaking into a reply).
  //
  // The ONLY timeline entry types that carry a turn of the CUSTOMER-VISIBLE conversation: the
  // in-app chat widget (#2199) and the email intake (#2198). Everything else is dropped — most
  // importantly `NoteEntry`, Plain's INTERNAL operator note, which is not part of the conversation
  // and must never be fed to the responder (it would risk operator-only commentary surfacing in a
  // reply). An unknown/new Plain entry type is dropped by the same rule, so a schema addition can
  // never silently widen what the agent reads.
  private val CustomerVisibleEntryTypes: Set[String] = Set("ChatEntry", "EmailEntry")

  // Role comes from the ACTOR, which is Plain's own authoritative record of who wrote the turn:
  // `CustomerActor` is the customer, `MachineUserActor` is US (the machine-user API key posts every
  // AI reply, #2408), a real `UserActor` is a HUMAN TEAMMATE who took the thread over. System /
  // deleted actors carry no conversational turn and are dropped.
  //
  // The actor ALWAYS wins. Message CONTENT never promotes a turn's role — the
  // [[SupportResponder.AiReplyAttribution]] line is visible verbatim in every AI reply on the
  // customer's own thread, so anyone can paste it, and an ordinary email reply QUOTES it back to us
  // inside `EmailEntry.textContent`. Letting content decide would (a) hand a customer a forged
  // `ai_assistant` frame — the very thing `ManagedAgents.neutralizeTags` closes at the tag layer —
  // and (b) relabel a quoted-reply customer turn as the AI's own, both mislabelling the transcript
  // and defeating [[SupportResponder.priorTurns]]'s echo dedup (which matches on the Customer role).
  // The attribution check therefore runs ONLY when the actor is unknown, where there is nothing
  // authoritative to override.
  private def roleOf(actorType: Option[String], text: String): Option[ThreadMessageRole] =
    actorType match {
      case Some("CustomerActor")    => Some(ThreadMessageRole.Customer)
      case Some("MachineUserActor") => Some(ThreadMessageRole.AiAssistant)
      case Some("UserActor")        => Some(ThreadMessageRole.HumanTeammate)
      case Some(_) | None           =>
        // Unknown / absent actor: the only signal left. A turn carrying our own attribution line is
        // one of our replies reported under an actor type we don't recognise; anything else is
        // dropped rather than guessed at.
        Option.when(text.contains(SupportResponder.AiReplyAttribution))(
          ThreadMessageRole.AiAssistant,
        )
    }

  // The first non-empty string among the message-bearing fields of the entry union — the two the
  // query actually selects (`ChatEntry.text`, `EmailEntry.textContent`), plus `markdownContent` as
  // forward-tolerance if the selection ever widens. Plain text is preferred: the markdown variant
  // carries the same words plus markup we don't need in the prompt.
  private def entryText(entry: Json): Option[String] =
    // The entry-type ALLOWLIST is load-bearing, not belt-and-braces: it is what keeps an internal
    // `NoteEntry` (operator-only commentary) out of the responder's prompt. Anything not on the
    // list contributes no turn even if it happens to carry a text-shaped field.
    if !typeNameOf(entry).exists(CustomerVisibleEntryTypes.contains) then None
    else
      List("text", "textContent", "markdownContent")
        .flatMap(k => objField(entry, k).collect { case Json.Str(s) => s })
        .map(_.trim)
        .find(_.nonEmpty)

  private def typeNameOf(j: Json): Option[String] =
    objField(j, "__typename").collect { case Json.Str(s) => s }

  // How many timeline entries Plain returned that we SHOULD have been able to read — entries whose
  // `entry.__typename` is on the customer-visible allowlist. The discriminator behind the `unparsed`
  // bucket: "we asked for turns, Plain sent turn-shaped entries, and NONE parsed" is schema drift.
  //
  // Counting ALL edges here would be wrong (review run 2): a long, actively-managed thread whose
  // most recent entries are all status flips, assignments, and internal notes is perfectly healthy,
  // and every one of those is an edge — it would fire an ERROR-level "SCHEMA DRIFT" on every message
  // of that thread. Entries we deliberately DROP must not count as entries we failed to read.
  private[api] def readableEntryCount(body: String): Int =
    Json.decoder
      .decodeJson(body)
      .toOption
      .flatMap { json =>
        navigate(json, List("data", "thread", "timelineEntries", "edges")).collect {
          case Json.Arr(items) =>
            items.count { edge =>
              objField(edge, "node")
                .flatMap(objField(_, "entry"))
                .flatMap(typeNameOf)
                .exists(CustomerVisibleEntryTypes.contains)
            }
        }
      }
      .getOrElse(0)

  // Strip the `(body: …)` tail `sendForBody` appends to a failure detail. On the thread-timeline
  // path that tail is CUSTOMER CONVERSATION TEXT (a GraphQL partial failure is an HTTP 200 whose
  // `data` still carries the timeline), and it must not reach the logs / Loki.
  // #2452 — recover the HTTP status from a `sendForBody` failure detail. This reads OUR OWN
  // framing (`sendForBody` authors the exact prefix `HTTP <status> ` for every non-2xx), not
  // Plain's prose, so it is a structural read rather than a guess at someone else's wording — the
  // distinction that makes it safe to route a permanent-vs-transient decision through it.
  private val HttpStatusPrefix = """^HTTP (\d{3})\b""".r

  private[api] def statusOf(detail: String): Option[Int] =
    HttpStatusPrefix.findFirstMatchIn(detail).map(_.group(1).toInt)

  private[api] def redactBody(detail: String): String =
    detail.indexOf(" (body:") match {
      case -1 => detail
      case i  => detail.take(i) + " (body redacted)"
    }

  /**
   * Pure parse of a `thread { timelineEntries { edges { node … } } }` response into OLDEST-FIRST
   * prior turns. Structure-tolerant by construction: any entry we can't read (unknown union member,
   * missing actor, empty body) is DROPPED, never a failure — a Plain schema addition degrades the
   * transcript, it never breaks a dispatch. Ordering is taken from each entry's `timestamp.iso8601`
   * (UTC ISO-8601 sorts lexicographically) so we do not depend on the connection's return order —
   * but ONLY when every kept entry carries one; if any timestamp is missing we keep Plain's own
   * order rather than float the undated entries to the top.
   *
   * Be explicit about what that fallback costs, since the kickoff asserts "oldest first"
   * unconditionally: with it engaged, both the transcript order and
   * [[SupportResponder.priorTurns]]' TRAILING echo match ride on Plain's connection order. It is
   * not a correctness hazard — a wrong order costs the agent clarity and at worst leaves the echo
   * turn in — and it is unreachable while [[Live.ThreadTimelineQuery]] selects `timestamp { iso8601
   * }` on every entry, which is why it stays a quiet fallback rather than a drop or a third parse
   * of the body to meter it.
   */
  private[support] def parseThreadHistory(body: String): List[PlainThreadMessage] =
    Json.decoder.decodeJson(body).toOption.toList.flatMap { json =>
      val edges = navigate(json, List("data", "thread", "timelineEntries", "edges")) match {
        case Some(Json.Arr(items)) => items.toList
        case _                     => Nil
      }
      val rows  = edges.flatMap { edge =>
        objField(edge, "node").toList.flatMap { node =>
          val ts = navigate(node, List("timestamp", "iso8601")).collect { case Json.Str(s) => s }
          for {
            entry <- objField(node, "entry").toList
            text  <- entryText(entry).toList
            actor = objField(node, "actor").flatMap(typeNameOf)
            role <- roleOf(actor, text).toList
          } yield (ts.getOrElse(""), PlainThreadMessage(role, text))
        }
      }
      (if rows.forall(_._1.nonEmpty) then rows.sortBy(_._1) else rows).map(_._2)
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
    // `error { code }` is selected alongside `message` because #2435's reconcile branches on Plain's
    // FIXED code (`customer_already_exists_with_email`), never on the English prose.
    private val UpsertCustomerMutation: String =
      """mutation upsertCustomer($input: UpsertCustomerInput!) {
        |  upsertCustomer(input: $input) { customer { id } error { message code } }
        |}""".stripMargin

    // #2435: assert the reconciled customer's household (tenant) membership. Plain's
    // `UpsertCustomerOnUpdateInput` has NO `tenantIdentifiers` (membership is a create-time field),
    // so an already-existing customer can only be joined to the household through this mutation.
    // `AddCustomerToTenantsOutput` returns `{ customer, error }`; we select only the error.
    private val AddCustomerToTenantsMutation: String =
      """mutation addCustomerToTenants($input: AddCustomerToTenantsInput!) {
        |  addCustomerToTenants(input: $input) { error { message code } }
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
    //
    // `withExternalId` (#2435) adds `externalId` — `UpsertCustomerOnCreateInput.externalId: ID`, a
    // BARE scalar. It is off on the primary path (the identifier already carries it) and on for the
    // email-keyed reconcile, where nothing else would map a created customer to the household.
    private def customerCreateFields(
        req: PlainCustomerUpsert,
        withExternalId: Boolean = false,
    ): Json =
      Json.Obj(
        Chunk(
          "fullName"          -> Json.Str(req.fullName),
          "email"             -> emailInput(req.email),
          "tenantIdentifiers" -> Json.Arr(
            Json.Obj("externalId" -> Json.Str(req.tenantIdentifier)),
          ),
        ) ++ Chunk.fromIterable(
          Option.when(withExternalId)("externalId" -> Json.Str(req.externalId)),
        ),
      )

    // `UpsertCustomerOnUpdateInput` (same source): every field is optional and WRAPPED —
    //   fullName: StringInput        — { value: String }, NOT a bare scalar (this was the second
    //                                  #2253 400, on the onUpdate path)
    //   email: EmailAddressInput     — { email, isVerified }
    // There is NO tenantIdentifiers on the update input, so it is deliberately omitted — the tenant
    // mapping is carried by onCreate above and persists across upserts.
    // `withExternalId` (#2435) adds `externalId` — `UpsertCustomerOnUpdateInput.externalId:
    // OptionalStringInput`, a WRAPPED `{ value }` (NOT the create input's bare scalar). This is the
    // patch that binds a pre-existing, email-keyed Plain customer to its household.
    private def customerUpdateFields(
        req: PlainCustomerUpsert,
        withExternalId: Boolean = false,
    ): Json =
      Json.Obj(
        Chunk(
          "fullName" -> Json.Obj("value" -> Json.Str(req.fullName)),
          "email"    -> emailInput(req.email),
        ) ++ Chunk.fromIterable(
          Option.when(withExternalId)(
            "externalId" -> Json.Obj("value" -> Json.Str(req.externalId)),
          ),
        ),
      )

    // ── #2435: reconcile an email-keyed customer onto its household ─────────────
    // Plain enforces customer-email uniqueness workspace-wide, and the #2198 email intake
    // AUTO-CREATES a customer (no `externalId`) the first time someone emails support. If that
    // happens before they ever load the widget — the ordinary beta path — the externalId-keyed
    // upsert above can only CREATE, and the create collides on the taken email. Permanently: the
    // email is never freed, so every later identity call for that household fails the same way and
    // the household→customer mapping is never established.
    //
    // The reconcile keys the SAME mutation on `identifier: { emailAddress }` — the only identifier
    // that can reach a customer Plain created for us — and patches `onUpdate.externalId`
    // (`OptionalStringInput`, a wrapped `{ value }`) to the household id.
    //
    // LOAD-BEARING INVARIANT: `users.email` is GLOBALLY unique — `uq_users_email UNIQUE (email)`,
    // `api/resources/db/migration/V67__users_email.sql`. That is the whole reason keying on the
    // email workspace-wide is safe: at most one household can ever present a given address, so the
    // customer this re-points can only belong to the household asking. If that unique key were ever
    // relaxed to per-household (the direction the #2125 / #2140 multi-tenant work pushes), this
    // becomes a CROSS-TENANT HIJACK — household B's identity call would seize household A's Plain
    // customer and, via #2430's thread-timeline read, feed A's conversation to B's responder.
    // Widening `uq_users_email` therefore requires revisiting this method, not just the migration.
    private def reconcileCustomerVars(req: PlainCustomerUpsert): Json =
      Json.Obj(
        "input" -> Json.Obj(
          // `UpsertCustomerIdentifierInput` — exactly ONE field may be set, so externalId is absent.
          "identifier" -> Json.Obj("emailAddress" -> Json.Str(req.email)),
          // We only get here because the email is taken, so onCreate is unreachable in practice —
          // but it is a required input, and if Plain ever did create here the customer must still
          // carry the household id (the identifier is the email, so nothing else would map it).
          // `UpsertCustomerOnCreateInput.externalId: ID` — a BARE scalar, unlike the update's.
          "onCreate"   -> customerCreateFields(req, withExternalId = true),
          "onUpdate"   -> customerUpdateFields(req, withExternalId = true),
        ),
      )

    // `AddCustomerToTenantsInput` — `{ customerIdentifier: CustomerIdentifierInput,
    // tenantIdentifiers: [TenantIdentifierInput] }`. Keyed on the externalId the reconcile just
    // patched on, so the household id is the single identity used from here on.
    private def addCustomerToTenantsVars(req: PlainCustomerUpsert): Json =
      Json.Obj(
        "input" -> Json.Obj(
          "customerIdentifier" -> Json.Obj("externalId" -> Json.Str(req.externalId)),
          "tenantIdentifiers"  -> Json.Arr(
            Json.Obj("externalId" -> Json.Str(req.tenantIdentifier)),
          ),
        ),
      )

    // A failed reconcile leg, attributed. Review finding: hard-coding `email_collision` on BOTH
    // legs mislabelled the one denial an operator most needs to see — the membership grant. A
    // `customerTenantMembership:create` denial can ONLY surface on `addCustomerToTenants`, and the
    // dashboard/MetricGuard text promises `permission` covers it, so the reconcile legs classify
    // the same way the primary path does. `email_collision` stays the bucket for a collision we
    // genuinely could not reconcile for any other reason (all three are permanent + logError).
    private def reconcileFailure(op: String, f: PlainFailure): UIO[(PlainOutcome, String)] = {
      val reason = customerReason(f.detail, CustomerReason.EmailCollision)
      logCustomerFailure(op, reason, f.detail).as((PlainOutcome.Error, reason))
    }

    // The membership join's target is the Plain TENANT that `upsertTenantEntitlement` writes. When
    // that target is absent Plain answers with its `not_found` code — "An entity referenced in the
    // request is not found. For example trying to create an issue for a customer that doesn't exist."
    // (plain.com/docs/graphql/error-codes, quoted verbatim).
    //
    // Whether that is transient or permanent is NOT inferable from the code, and we must not try:
    // `not_found` says the target is missing, never WHY. The caller already knows why — it ran the
    // entitlement step — so the answer is THREADED IN as [[TenantWrite]] rather than re-derived here
    // (review finding: re-deriving a decision the call site already holds is the dimension-1
    // duplication this repo treats as a defect, and the first cut of this method guessed "transient"
    // for every not-found, which mislabels a permanently-failed tenant write):
    //
    //   - `Skipped` — no household context to write, so no tenant exists yet. TRANSIENT: the next
    //     identity call with a healthy read writes it and the reconcile lands.
    //   - `Failed` — `upsertTenant` itself failed, so the tenant does not exist and will not appear
    //     on its own. PERMANENT for this call, and attributed `tenant_missing`: the cause is already
    //     named on `support_tenant_upsert_total{reason=tenant}` (#2410), so this bucket's job is to
    //     point there rather than raise a second, differently-worded alert.
    //   - `Written` — the tenant DOES exist, so a `not_found` here is about something else (the
    //     customer identifier, or a contract change). Genuinely anomalous: keep the ordinary
    //     attribution, which routes not-found prose to `schema`.
    //
    // What must NOT happen is the transient case reaching `schema`: `classifyFieldFailure` maps every
    // not-found there, which is right for a tenant-FIELD write (an unregistered field never
    // self-heals) and wrong for a passing DB blip, where it raises a "SCHEMA DRIFT … will never
    // self-heal" ERROR and a provisioning-gap alert. The #2410 bar cuts both ways: reporting a
    // transient miss as permanent burns the operator's trust in the alert just as badly as the
    // reverse.
    private def joinFailure(f: PlainFailure, tenant: TenantWrite): UIO[(PlainOutcome, String)] = {
      val targetMissing =
        f.body.flatMap(payloadErrorCode(_, "addCustomerToTenants")).contains(NotFoundCode)
      // by-name: the Skipped / Failed arms below never need it
      def ordinary      = customerReason(f.detail, CustomerReason.EmailCollision)
      val reason        =
        if !targetMissing then ordinary
        else
          tenant match {
            case TenantWrite.Skipped => CustomerReason.Error
            case TenantWrite.Failed  => CustomerReason.TenantMissing
            case TenantWrite.Written => ordinary
          }
      logCustomerFailure("addCustomerToTenants", reason, f.detail).as((PlainOutcome.Error, reason))
    }

    // Run the reconcile: patch the externalId, then assert household membership. BOTH halves must
    // land — a patched customer with no tenant membership is still a broken mapping, so a failed
    // join is an error, not a half-success.
    private def reconcileCustomer(
        req: PlainCustomerUpsert,
        tenant: TenantWrite,
    ): UIO[(PlainOutcome, String)] =
      sendForBody(
        UpsertCustomerMutation,
        reconcileCustomerVars(req),
        Expect("upsertCustomer", Some("customer")),
      ).flatMap {
        case Left(f)  => reconcileFailure("upsertCustomer(reconcile)", f)
        case Right(_) =>
          sendForBody(
            AddCustomerToTenantsMutation,
            addCustomerToTenantsVars(req),
            Expect("addCustomerToTenants", None),
          ).flatMap {
            case Right(_) =>
              ZIO
                .logInfo(
                  s"plain upsertCustomer reconciled an email-keyed customer onto household " +
                    s"${req.externalId} (Plain had auto-created it from an inbound support email)",
                )
                .as((PlainOutcome.Ok, CustomerReason.Reconciled))
            case Left(f)  => joinFailure(f, tenant)
          }
      }

    def upsertCustomer(req: PlainCustomerUpsert): UIO[PlainOutcome] =
      // The tenant entitlement (household name + plan/founding fields) runs FIRST: it is what
      // creates the Plain tenant, and #2435's reconcile joins the customer to that tenant by
      // externalId — on a household whose very first Plain write collides, the tenant would not yet
      // exist if this still ran afterwards. It remains best-effort and never flips the customer
      // outcome (a missing tenant-field schema or a tenant hiccup is logged + metered, not fatal).
      //
      // Necessary but NOT sufficient, and deliberately so (review finding): `upsertTenantEntitlement`
      // short-circuits when there is nothing household-level to carry (`fullName` empty AND no
      // attributes), which `SupportService.identity` can produce — it `catchAll`s the household and
      // billing lookups to `None`, so a DB blip degrades the payload to "no household context". In
      // that state no tenant is written and a reconcile's membership join has no target, so it FAILS
      // — loudly and metered, which is the correct outcome for a degraded read: we do not want to
      // invent a Plain tenant from an empty household name (Plain's `UpsertTenantInput.name` is
      // required and emptiness-checked, so it would be rejected anyway). The mapping is retried on
      // the household's next identity call, when the repo read succeeds — which is exactly why that
      // failure is attributed TRANSIENT, and why the step's [[TenantWrite]] is threaded into the
      // reconcile instead of `joinFailure` guessing at it.
      //
      // The customer upsert is the primary write and its outcome is what we return/meter.
      upsertTenantEntitlement(req)
        .flatMap { tenant =>
          sendForBody(
            UpsertCustomerMutation,
            upsertCustomerVars(req),
            Expect("upsertCustomer", Some("customer")),
          ).flatMap {
            case Right(_) => ZIO.succeed((PlainOutcome.Ok, CustomerReason.Ok))
            case Left(f)  =>
              // Deliberately PAYLOAD-only: Plain reports this condition as HTTP 200 + a
              // `MutationError` on `data.upsertCustomer.error` (verified live, #2435), never as a
              // non-2xx or a top-level `errors[]`. A response in either of those shapes is a different
              // failure and falls through to the attributed non-collision branch below rather than
              // triggering a reconcile against an unverified error shape.
              if f.body.flatMap(payloadErrorCode(_, "upsertCustomer")).contains(EmailCollisionCode)
              then reconcileCustomer(req, tenant)
              else {
                // Not the collision: attribute permission / schema (provisioning gaps, LOUD) apart
                // from a transient miss (a warning), same split as the #2410 entitlement path.
                val reason = customerReason(f.detail, CustomerReason.Error)
                logCustomerFailure("upsertCustomer", reason, f.detail).as(
                  (PlainOutcome.Error, reason),
                )
              }
          }
        }
        .flatMap { case (outcome, reason) =>
          AppMetrics.supportCustomerUpsert(PlainOutcome.label(outcome), reason).as(outcome)
        }

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

    // Returns whether the TENANT the #2435 join targets now exists — see [[TenantWrite]]. The
    // (outcome, reason) pair it computes for `support_tenant_upsert_total` is unchanged; the
    // TenantWrite rides alongside so `joinFailure` need not re-derive it from Plain's response.
    private def upsertTenantEntitlement(req: PlainCustomerUpsert): UIO[TenantWrite] =
      // Nothing household-level to carry ⇒ no tenant write at all (and nothing to meter). NOTE the
      // condition: BOTH `fullName` and `attributes` must be empty. In `SupportService.identity` the
      // name comes from `householdRepo.findById` and the attributes from `billingRepo.findByHousehold`,
      // each `catchAll`'d to `None`, so reaching here takes BOTH lookups degrading — not just one.
      if req.fullName.isEmpty && req.attributes.isEmpty then ZIO.succeed(TenantWrite.Skipped)
      else
        // Aggregate outcome + attributed reason (#2410): `ok`/`ok` only when the tenant upsert AND
        // every field write succeed; `error`/<reason> if any step fails — `tenant` for the tenant
        // step, `permission`/`schema`/`field_write` for a field write. Each failure is logged LOUD
        // (logError) and metered so a silently-failing entitlement path is visible beyond the logs.
        // Fail-open is preserved: this whole method runs as a side-step ahead of the customer upsert
        // (#2435 moved it from a `zipLeft` follow-on to a `*>` predecessor so the reconcile's
        // membership join has a tenant to join) and never flips the customer upsert outcome.
        sendForBody(
          UpsertTenantMutation,
          upsertTenantVars(req.tenantIdentifier, req.fullName),
          Expect("upsertTenant", Some("tenant")),
        ).flatMap {
          case Left(f)     =>
            // The tenant step itself failed — the fields are never reached. Attributed `tenant`.
            logEntitlementFailure("upsertTenant", Reason.Tenant, f.detail)
              .as(((PlainOutcome.Error, Reason.Tenant), TenantWrite.Failed))
          case Right(body) =>
            tenantIdFrom(body) match {
              case None           =>
                logEntitlementFailure(
                  "upsertTenant",
                  Reason.Tenant,
                  "no tenant id in response; skipping fields",
                ).as(((PlainOutcome.Error, Reason.Tenant), TenantWrite.Failed))
              case Some(tenantId) =>
                ZIO
                  .foreach(tenantFieldWrites(req, tenantId)) { case (vars, op) =>
                    sendForBody(
                      UpsertTenantFieldMutation,
                      vars,
                      Expect("upsertTenantField", Some("tenantField")),
                    ).flatMap {
                      case Right(_) => ZIO.succeed(None)
                      case Left(f)  =>
                        val reason = classifyFieldFailure(f.detail)
                        logEntitlementFailure(op, reason, f.detail).as(Some(reason))
                    }
                  }
                  // First failure attributes the aggregate; a permission/schema gap fails every
                  // field identically, so the first is representative. Either way the TENANT is
                  // `Written` — we hold its id — so the join has a target even if a field missed.
                  .map(_.flatten.headOption match {
                    case None         => ((PlainOutcome.Ok, Reason.Ok), TenantWrite.Written)
                    case Some(reason) => ((PlainOutcome.Error, reason), TenantWrite.Written)
                  })
            }
        }.flatMap { case ((o, reason), tenant) =>
          AppMetrics.supportTenantUpsert(PlainOutcome.label(o), reason).as(tenant)
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

    // ── #2430: the bound thread's prior turns ──────────────────────────────────
    // The ONE read this integration performs. Scoped by construction: `threadId` is the single
    // parameter and it comes from the webhook event the dispatch is bound to, so there is no shape
    // in which another thread, customer, or household is readable. Requires the machine-user key's
    // `thread:read` permission (docs/ops/plain-setup.md §5.1) — a missing grant is a PROVISIONING
    // GAP, logged loud + metered `permission` below, not a silent degrade.
    //
    // `timelineEntries(last:)` asks for the most recent N; we request `__typename` on the actor and
    // the entry unions so [[parseThreadHistory]] can label the role and pick out the message body.
    private val ThreadTimelineQuery: String =
      """query threadTimeline($threadId: ID!, $last: Int!) {
        |  thread(threadId: $threadId) {
        |    id
        |    timelineEntries(last: $last) {
        |      edges {
        |        node {
        |          timestamp { iso8601 }
        |          actor { __typename }
        |          entry {
        |            __typename
        |            ... on ChatEntry { text }
        |            ... on EmailEntry { textContent }
        |          }
        |        }
        |      }
        |    }
        |  }
        |}""".stripMargin

    def threadHistory(threadId: String, limit: Int): UIO[List[PlainThreadMessage]] =
      if threadId.isEmpty then AppMetrics.supportThreadHistory("empty").as(Nil)
      else
        sendForBody(
          ThreadTimelineQuery,
          Json.Obj(
            "threadId" -> Json.Str(threadId),
            "last"     -> Json.Num(java.math.BigDecimal.valueOf(limit.toLong)),
          ),
          Expect("thread", None),
        ).disconnect
          .timeoutTo(Left(PlainFailure("timed out", None)): Either[PlainFailure, String])(identity)(
            HistoryTimeout,
          )
          .flatMap {
            case Right(body)   =>
              val msgs     = parseThreadHistory(body)
              // Only the entries we SHOULD have read — a thread whose recent timeline is all notes
              // and status flips is healthy, not drifted (bound once: this re-parses the body).
              val readable = readableEntryCount(body)
              if msgs.nonEmpty then AppMetrics.supportThreadHistory("ok").as(msgs)
              else if readable > 0 then
                // Plain returned turn-SHAPED entries and NOT ONE parsed into a turn. That is schema
                // DRIFT (the actor / entry union shapes moved), not a quiet thread — its own bucket,
                // logged loud, because it will never self-heal.
                ZIO.logError(
                  s"plain threadTimeline returned $readable message entries but none parsed into " +
                    "a turn — SCHEMA DRIFT: Plain's timeline actor/entry shapes no longer match " +
                    "PlainClient.parseThreadHistory; the support responder is answering every " +
                    "message with no thread context",
                ) *> AppMetrics.supportThreadHistory("unparsed").as(Nil)
              else AppMetrics.supportThreadHistory("empty").as(Nil)
            case Left(failure) =>
              // Fail-open (the dispatch proceeds with the latest message alone) but NOT silent: a
              // permission or schema miss is a misconfiguration, so it is logged at ERROR with the
              // fix named inline, exactly like the #2410 entitlement path — and gets its own metric
              // bucket, since neither self-heals. Transient misses stay a warning + `error`.
              //
              // `detail` carries up to 500 chars of the raw Plain response body, which on THIS path
              // is customer conversation text — every other sendForBody caller logs inputs we
              // authored. So the body is stripped before it reaches the log/Loki; the reason plus
              // the GraphQL error text is what an operator acts on.
              val reason = classifyFieldFailure(failure.detail)
              val safe   = redactBody(failure.detail)
              val log    = reason match {
                case Reason.Permission =>
                  // #2452: name the permission Plain ACTUALLY named. This path previously
                  // hardcoded `thread:read` for ANY 403 — and the real cause on staging was
                  // `timeline:read` (a SEPARATE permission gating `thread { timelineEntries }`).
                  // Because the body is redacted, that wrong string was the operator's only
                  // signal, and granting exactly what it asked for did not fix anything.
                  ZIO.logError(
                    s"plain threadTimeline failed [reason=$reason]: $safe — PROVISIONING GAP: " +
                      s"${permissionGapHint(safe)} — so the support responder cannot see the " +
                      "conversation so far",
                  )
                case Reason.Schema     =>
                  ZIO.logError(
                    s"plain threadTimeline failed [reason=$reason]: $safe — SCHEMA DRIFT: Plain " +
                      "rejected the thread-timeline query; PlainClient.ThreadTimelineQuery no " +
                      "longer matches Plain's schema and the responder has no thread context",
                  )
                case _                 =>
                  ZIO.logWarning(s"plain threadTimeline failed [reason=$reason]: $safe")
              }
              log *> AppMetrics
                .supportThreadHistory(reason match {
                  case Reason.Permission => "permission"
                  case Reason.Schema     => "schema"
                  case _                 => "error"
                })
                .as(Nil)
          }
    // ── #2452: the key's own permission array ──────────────────────────────────
    // Plain's `myPermissions` — "Returns the full list of permission strings granted to the
    // currently authenticated user or machine user in this workspace" (verified against Plain's
    // published schema, https://core-api.uk.plain.com/graphql/v1/schema.graphql; `Permissions
    // { permissions: [String!]! }`). It carries no `permission:read` requirement of its own — the
    // sibling `permissions` query, which enumerates the workspace's whole vocabulary, is the one
    // that does — so the probe cannot itself be the thing that is denied.
    //
    // Reads NOTHING about customers, threads, or conversations: the response is a list of our own
    // grant strings.
    private val MyPermissionsQuery: String                                         =
      """query myPermissions { myPermissions { permissions } }"""

    // Bounded exactly like `threadHistory`'s read (and for the same reason): the JDK client's own
    // ConnectTimeout + RequestTimeout can hold a borrowed blocking thread for 30s against a
    // black-holed Plain. The audit is pure observability, so it must give up quickly.
    //
    // Deliberately BELOW `RequestTimeout` (20s) so a hung Plain resolves to `unreachable` rather
    // than hanging the fiber; the cost is that a genuinely slow-but-healthy Plain also reads as
    // `unreachable`, which is the harmless direction (unverified, not misreported). Slightly
    // longer than `HistoryTimeout` because nothing waits on this one — it is a forked boot task,
    // not a webhook's latency budget.
    private val ProbeTimeout: Duration = 10.seconds

    def grantedPermissions: UIO[PlainPermissionRead] =
      sendForBody(MyPermissionsQuery, Json.Obj(), Expect("myPermissions", None)).disconnect
        .timeoutTo(Left("timed out"): Either[String, String])(identity)(ProbeTimeout)
        .map {
          case Right(body)  =>
            navigate(
              Json.decoder.decodeJson(body).getOrElse(Json.Null),
              List("data", "myPermissions", "permissions"),
            ) match {
              case Some(Json.Arr(items)) =>
                PlainPermissionRead.Granted(items.collect { case Json.Str(p) => p }.toSet)
              // A 200 whose payload has no permissions array is Plain-schema DRIFT, not a grant gap
              // and not an outage — reporting it as `Granted(empty)` would tell an operator to
              // grant everything, and as `Unreachable` would tell them to wait for a self-heal that
              // never comes. It is permanent, so it is Broken.
              case _                     =>
                PlainPermissionRead.Broken(
                  "myPermissions response carried no permissions array — Plain's probe shape drifted",
                )
            }
          // The split that matters, and it is drawn on the HTTP STATUS, not on message text.
          //
          // A 4xx means the KEY ITSELF is rejected — revoked, rotated out from under us, or simply
          // wrong — or that we are asking for something that does not exist. `myPermissions` needs
          // no permission of its own, so a 403 here can never be an under-grant. Either way it is a
          // PERMANENT misconfiguration in which every Plain call is failing, not an outage to wait
          // out (no-dark-by-default: a broken credential means the integration is broken and we
          // should be too). 5xx / timeout / transport is the transient half.
          //
          // That line is EXACTLY `CloudAgentObservability.isPermanentClientStatus` — the same
          // predicate #2416 uses at the cloud-agent boundary, including its 408/429 carve-out —
          // called, not re-derived. Deliberately NOT `classifyFieldFailure`: that one matches
          // substrings, so it would file a 400/404/422 as transient and could be fooled by an
          // upstream HTML error page containing the word "forbidden".
          case Left(detail) =>
            val safe = redactBody(detail)
            statusOf(safe) match {
              case Some(status) =>
                if CloudAgentObservability.isPermanentClientStatus(status) then
                  PlainPermissionRead.Broken(safe)
                else PlainPermissionRead.Unreachable(safe)
              // No status ⇒ the failure is not an HTTP one. Transport errors and timeouts are
              // transient; EVERYTHING else here is a payload-shape problem on a 200 — an absent or
              // null `myPermissions`, an unparseable body, a GraphQL error on this fixed query —
              // i.e. Plain-side DRIFT, which is permanent. The default is deliberately the LOUD
              // side: a false `broken` cries wolf, a false `unreachable` hides the failure, and
              // hiding is the entire bug #2452 exists to close.
              case None         =>
                if safe == "timed out" || safe.startsWith("transport error") then
                  PlainPermissionRead.Unreachable(safe)
                else PlainPermissionRead.Broken(safe)
            }
        }

    // ── #2437: thread marking (escalation label) ────────────────────────────────
    // Plain's `addLabels(input: AddLabelsInput!)` with `{ threadId, labelTypeIds }` (verified against
    // https://www.plain.com/docs — "You can add multiple labels to a thread with a call to
    // addLabels"; it needs the `label:create` API-key permission, see docs/ops/plain-setup.md §5.1).
    // The selection is deliberately ONLY `error { message }`: every Plain mutation payload carries
    // that, so we assume nothing further about the output shape (selecting a field Plain doesn't have
    // would fail the whole mutation). Hence `resultKey = None`.
    private val AddLabelsMutation: String =
      """mutation addLabels($input: AddLabelsInput!) {
        |  addLabels(input: $input) { error { message } }
        |}""".stripMargin

    private def markThreadVars(req: PlainThreadMark): Json =
      Json.Obj(
        "input" -> Json.Obj(
          "threadId"     -> Json.Str(req.threadId),
          "labelTypeIds" -> Json.Arr(req.labelTypeIds.map(Json.Str(_))*),
        ),
      )

    def markThread(req: PlainThreadMark): UIO[PlainOutcome] =
      // No label ids ⇒ nothing to write. Cannot happen in a configuration that boots (the label id is
      // required when the support responder is enabled), so this is a guard, not a disable switch.
      if req.labelTypeIds.forall(_.trim.isEmpty) then
        ZIO
          .logError(
            "plain addLabels skipped: no escalation label type id configured " +
              "(wifihaven.support.plain.escalationLabelTypeId) — the escalated thread is UNMARKED",
          )
          .as(PlainOutcome.Error)
      else
        sendForBody(
          AddLabelsMutation,
          markThreadVars(req),
          Expect("addLabels", None),
        ).flatMap {
          case Right(_)     => ZIO.succeed(PlainOutcome.Ok)
          // EVERY failure is loud. A miss here is usually a PROVISIONING gap (wrong label id, or the
          // key lacks `label:create`), not a blip: the escalated thread stays invisible in the
          // inbox. LOUD (logError) per the no-dark-by-default bar (#2410's precedent), and the
          // caller meters it.
          //
          // Deliberately NO "already labelled ⇒ Ok" special case. Plain documents a
          // `label_with_given_type_already_added_to_thread` validation code, but this selection —
          // like every other mutation here — carries only `error { message }`, and `payloadError`
          // reads only `message`, so that code is never observable in `detail`; and adding `code` to
          // the selection is unverified against Plain's schema (a field they don't have fails the
          // WHOLE mutation, breaking all labelling). Matching the wording loosely was worse still:
          // `already`+`label` also swallows "label type has already been archived", reporting a real
          // provisioning failure as success. So we assert nothing we cannot observe. If Plain turns
          // out to error on a duplicate label, that shows up as a benign non-zero on the
          // "escalated threads NOT marked" panel until TODO(#2449) resolves the real behavior from a
          // captured staging response (docs/ops/plain-setup.md §8 step 4).
          case Left(detail) =>
            ZIO
              .logError(
                s"plain addLabels failed: $detail — PROVISIONING GAP: check " +
                  "wifihaven.support.plain.escalationLabelTypeId and that the Plain machine-user key " +
                  "has the `label:create` permission (docs/ops/plain-setup.md §5.1); the escalated " +
                  "thread is UNMARKED in the inbox",
              )
              .as(PlainOutcome.Error)
        }

    private def post(
        query: String,
        variables: Json,
        op: String,
        expect: Expect,
    ): UIO[PlainOutcome] =
      sendForBody(query, variables, expect).flatMap {
        case Right(_) => ZIO.succeed(PlainOutcome.Ok)
        // Thread writes are fail-open best-effort context: a miss is logged at warning (not error)
        // and mapped to Error. Entitlement (#2410) and customer (#2435) writes DON'T go through
        // `post` — they call `sendForBody` directly and log LOUD with an attributed reason.
        case Left(f)  => ZIO.logWarning(s"plain $op failed: ${f.detail}").as(PlainOutcome.Error)
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
    ): UIO[Either[PlainFailure, String]] =
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
            Left(PlainFailure(s"HTTP ${resp.statusCode()} (body: ${body.take(500)})", Some(body)))
          else
            // Plain returns 200 for mutation-level failures too, so inspect the payload: a top-level
            // `errors` array, a payload `error { message }`, or a missing result id is a failed write.
            checkPayload(body, expect) match {
              case Right(_)      => Right(body)
              case Left(problem) =>
                Left(PlainFailure(s"$problem (body: ${body.take(500)})", Some(body)))
            }
        }
        .catchAll(e => ZIO.succeed(Left(PlainFailure(s"transport error: ${e.getMessage}", None))))
  }

  /**
   * Test/inspection client: records every customer upsert + thread write into `Ref`s and always
   * reports [[PlainOutcome.Ok]]. Used by the feature suite to assert the mapping carried the right
   * `tenantIdentifier` + entitlement attributes without a network.
   */
  /**
   * `history` is the canned transcript [[threadHistory]] returns (seed it to model a continuation
   * thread); `historyReads` records the thread ids it was asked for, so a spec can pin that the
   * read is scoped to the BOUND thread. `historyFails` models a Plain hiccup / permission gap — the
   * read then behaves exactly like the live client's fail-open path (`Nil`).
   */
  final case class Recorder(
      customers: Ref[List[PlainCustomerUpsert]],
      threads: Ref[List[PlainThreadWrite]],
      history: Ref[List[PlainThreadMessage]],
      historyReads: Ref[List[String]],
      historyFails: Ref[Boolean],
      // #2437: thread MARKS (escalation labels), so a spec can assert an escalated thread is labelled
      // and — the load-bearing half — that an AI-resolved one is NOT.
      marks: Ref[List[PlainThreadMark]],
  )

  def recording(rec: Recorder): PlainClient = new PlainClient {
    def upsertCustomer(req: PlainCustomerUpsert): UIO[PlainOutcome] =
      rec.customers.update(_ :+ req).as(PlainOutcome.Ok)
    def writeThread(req: PlainThreadWrite): UIO[PlainOutcome]       =
      rec.threads.update(_ :+ req).as(PlainOutcome.Ok)

    def threadHistory(threadId: String, limit: Int): UIO[List[PlainThreadMessage]] =
      rec.historyReads.update(_ :+ threadId) *> rec.historyFails.get.flatMap {
        case true  => ZIO.succeed(Nil)
        case false => rec.history.get.map(_.takeRight(limit))
      }
    def markThread(req: PlainThreadMark): UIO[PlainOutcome]                        =
      rec.marks.update(_ :+ req).as(PlainOutcome.Ok)

    // #2452: the recorder models a FULLY GRANTED key — specs that drive the support stack are not
    // about provisioning, and the audit's gap behaviour is pinned directly in
    // `PlainPermissionAuditSpec` against a stubbed Plain endpoint.
    def grantedPermissions: UIO[PlainPermissionRead] =
      ZIO.succeed(PlainPermissionRead.Granted(PlainPermissionAudit.AllPermissions))
  }

  def recorder: UIO[Recorder] =
    for {
      c <- Ref.make(List.empty[PlainCustomerUpsert])
      t <- Ref.make(List.empty[PlainThreadWrite])
      h <- Ref.make(List.empty[PlainThreadMessage])
      r <- Ref.make(List.empty[String])
      f <- Ref.make(false)
      m <- Ref.make(List.empty[PlainThreadMark])
    } yield Recorder(c, t, h, r, f, m)
}
