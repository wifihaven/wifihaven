package wifihaven.api.support

import wifihaven.api.SupportConfig
import wifihaven.api.metrics.AppMetrics
import zio.*

/**
 * #2452 — the boot-time audit of the Plain machine-user API key's permission array.
 *
 * Every other prerequisite of the support integration is local config, so
 * `AppConfig.validateRequired` can fail boot on it. The API key's PERMISSIONS are not: they live in
 * Plain-workspace state, set only through Plain's `updateApiKey` mutation (there is no UI —
 * docs/ops/plain-setup.md §5.1). Until this audit existed, a gap was discovered at FIRST WRITE, per
 * call, as a fail-open ERROR line — and two of them (`timeline:read`, `tenantFieldSchema:read`)
 * went unnoticed for the whole of #2430 and #2240, leaving both features PERMANENTLY INERT on a
 * workspace provisioned exactly as the runbook said.
 *
 * Per docs/process/no-dark-by-default.md a missing permission is a MISCONFIGURATION, not an
 * optional-feature off-switch, so it must not degrade silently.
 *
 * '''Why this is a loud alerting error and not a boot crash.''' The doc sanctions either "a typed
 * `zio-config` startup error that crashes boot" or "a loud alerting runtime error". A crash is the
 * right shape for LOCAL config, which is knowable offline and deterministic. This check is a live
 * call to a third party: crashing boot on its answer would let a Plain outage, a Plain-side
 * permission RENAME, or a schema change to `myPermissions` take down the whole API — router
 * enforcement, policy, usage ingest — for a degraded support desk. That trade is wrong, so the
 * audit is loud-and-alertable instead: `logError` naming EVERY gap plus the exact mutation to run,
 * and `support_permission_probe_total{outcome}` (panel on
 * `deploy/grafana/dashboards/support.json`), which sits at zero on a correctly provisioned
 * workspace. Boot itself never depends on Plain answering.
 *
 * The audit is also the reason the required set now has ONE home: this object, cited by the
 * runbook, rather than a prose table that drifted (docs/process/single-source-of-truth.md).
 */
object PlainPermissionAudit {

  /**
   * Required whenever the Plain WRITE client is live, because `upsertCustomer` always drives the
   * customer upsert → `upsertTenant` → `upsertTenantField` chain (#2240 entitlement).
   *
   * `tenantFieldSchema:read` is here despite the code never creating or reading a schema: Plain
   * resolves the field's schema to type-check the value as part of the WRITE, so the write path
   * needs schema read. Verified live on the staging workspace 2026-07-26 — with
   * `tenantField:create` + `tenantField:update` granted and both schemas registered, every
   * `upsertTenantField` still failed `Insufficient permissions, missing "tenantFieldSchema:read"`.
   * (Plain's own schema doc-string for `upsertTenantField` says only `tenant:edit`; it understates
   * the requirement, which is why the empirical set is what this object encodes.)
   */
  val CorePermissions: Set[String] = Set(
    "customer:create",
    "customer:edit",
    "tenant:read",
    "tenant:create",
    "tenant:edit",
    "customerTenantMembership:create",
    "customerTenantMembership:delete",
    "tenantField:create",
    "tenantField:update",
    "tenantFieldSchema:read",
  )

  /**
   * Required additionally when the support RESPONDER is enabled — the reply, the history read, and
   * the #2437 escalation label.
   *
   * `timeline:read` is a SEPARATE permission from `thread:read` and gates `thread { timelineEntries
   * }`, which is the whole of [[PlainClient.threadHistory]]. Plain's own schema documents
   * operations that "require the `thread:read` and `timeline:read` permissions" — holding only the
   * first returns 403 on the timeline read, which is exactly how #2430 shipped inert.
   */
  val ResponderPermissions: Set[String] = Set(
    "thread:reply",
    "thread:read",
    "timeline:read",
    "label:create",
  )

  /**
   * Everything this integration can need. Used by the test recorder to model a fully granted key.
   */
  val AllPermissions: Set[String] = CorePermissions ++ ResponderPermissions

  /**
   * NOT required — the app only writes customers — but worth granting: it is what makes a
   * customer↔household mapping inspectable from the API playground, and what a reconcile-by-email
   * fix needs. Documented in the runbook as recommended; deliberately absent from [[required]] so
   * the audit never nags about a permission nothing depends on.
   */
  val RecommendedPermissions: Set[String] = Set("customer:read")

  /** The permissions this deployment's ENABLED feature set actually needs. */
  def required(cfg: SupportConfig): Set[String] =
    if !cfg.plain.writeEnabled then Set.empty
    else CorePermissions ++ (if cfg.responderEnabled then ResponderPermissions else Set.empty)

  def check(cfg: SupportConfig, client: PlainClient): UIO[PlainPermissionAuditResult] = {
    val need = required(cfg)
    if need.isEmpty then ZIO.succeed(PlainPermissionAuditResult.Skipped)
    else
      client.grantedPermissions.map {
        case PlainPermissionRead.NotConfigured       => PlainPermissionAuditResult.Skipped
        case PlainPermissionRead.Unreachable(detail) =>
          PlainPermissionAuditResult.Unreachable(detail)
        case PlainPermissionRead.Granted(granted)    =>
          // ALL gaps at once (no-dark-by-default rule 4): an operator who fixes one and reboots to
          // find the next is exactly the loop #2452 was.
          val missing = (need -- granted).toList.sorted
          if missing.isEmpty then PlainPermissionAuditResult.Ok(granted)
          else PlainPermissionAuditResult.Missing(missing, granted)
      }
  }

  /** The exact remediation an operator can paste into Plain's API playground. */
  private def remediation(cfg: SupportConfig): String = {
    val full = (required(cfg) ++ RecommendedPermissions).toList.sorted
      .map(p => s""""$p"""")
      .mkString(",")
    s"run `updateApiKey(input: { apiKeyId: \"<apiKey_...>\", permissions: [$full] })` in Plain's " +
      "API playground (permissions are REPLACED in full, so send the whole set) — " +
      "docs/ops/plain-setup.md §5.3"
  }

  /**
   * Run the audit and report it. Never fails, never blocks anything: the caller forks this at boot.
   */
  def run(cfg: SupportConfig, client: PlainClient): UIO[Unit] =
    check(cfg, client).flatMap {
      case PlainPermissionAuditResult.Skipped             =>
        AppMetrics.supportPermissionProbe("skipped")
      case PlainPermissionAuditResult.Ok(granted)         =>
        ZIO.logInfo(
          s"plain api-key permissions OK — all ${required(cfg).size} required permissions granted " +
            s"(${granted.size} total on the key)",
        ) *> AppMetrics.supportPermissionProbe("ok")
      case PlainPermissionAuditResult.Missing(missing, _) =>
        ZIO.logError(
          s"plain api-key permissions INCOMPLETE — PROVISIONING GAP: the machine-user API key is " +
            s"missing ${missing.mkString(", ")}. Every Plain call gated on these fails 403 and the " +
            s"feature behind it is INERT (thread history → the responder answers with no memory; " +
            s"tenant fields → the operator sees no entitlement). Fix: ${remediation(cfg)}",
        ) *> AppMetrics.supportPermissionProbe("missing")
      case PlainPermissionAuditResult.Unreachable(detail) =>
        // NOT a permission gap: we could not ask. Transient by assumption, so warning + its own
        // bucket — folding it into `missing` would send an operator to grant permissions they
        // already hold.
        ZIO.logWarning(
          s"plain api-key permission probe could not reach Plain: $detail — the key's permissions " +
            "are UNVERIFIED this boot (docs/ops/plain-setup.md §5.1)",
        ) *> AppMetrics.supportPermissionProbe("unreachable")
    }
}

/**
 * #2452 — the audit outcome. `Missing` and `Unreachable` are separate cases on purpose: "you lack
 * X" and "we could not ask" demand different operator actions.
 */
enum PlainPermissionAuditResult {

  /** The Plain write client is not configured, so there is nothing to audit. */
  case Skipped

  /** Every required permission is granted. `granted` is the key's full array. */
  case Ok(granted: Set[String])

  /** `missing` is EVERY required permission the key lacks, sorted — never just the first. */
  case Missing(missing: List[String], granted: Set[String])

  /** Plain did not answer (outage, transport, schema drift). Says nothing about the grants. */
  case Unreachable(detail: String)
}
