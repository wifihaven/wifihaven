package wifihaven.api.observability

import zio.*

/**
 * #602: the single structured-log helper for the API. Every hot-path log line should attach context
 * via these helpers instead of interpolating ad-hoc `key=value` substrings into the message.
 *
 * The annotations attached here flow through `zio-logging-slf4j`'s default `logFormatDefault`
 * (`LogFormat.allAnnotations + LogFormat.line + LogFormat.cause`) which writes every annotation
 * into SLF4J MDC. Local `logback.xml` includes `%mdc` in the console pattern so they are visible
 * during dev; a downstream JSON appender (deferred — see the umbrella under #602) can read the same
 * MDC map without re-parsing message text.
 *
 * Keys are bounded — they must come from this object — to avoid the same per-mac/per-domain
 * cardinality footgun that `MetricGuard` defends against on the metrics side. A new key is added
 * here, not at the call site.
 *
 * Convention: keys are camelCase strings; values are the smallest unique identifier (router id,
 * mac, etag, profile id) or a bounded enum (op, reason, status). The same key in [[LogContext]] and
 * [[wifihaven.api.metrics.MetricGuard]] means the same thing — `route` is the templated path, never
 * the raw URL; `op` is a low-cardinality verb (`router_policy`, `router_usage`, `login`,
 * `device_upsert`, …).
 */
object LogContext {

  // ── Keys (bounded — extend deliberately) ─────────────────────────────────

  val Route     = "route"
  val Method    = "method"
  val Status    = "status"
  val Op        = "op"
  val RouterId  = "routerId"
  val Mac       = "mac"
  val Etag      = "etag"
  val ProfileId = "profileId"
  val User      = "user"
  val Reason    = "reason"
  val NotMod    = "notModified"
  val BatchSize = "batchSize"
  val Rejected  = "rejected"
  // #1968 — SPA-websocket per-frame structured logging (design §10.5). `role` is the
  // connection's resolved UserRole; `result` is the per-frame outcome (ok | reject |
  // unknown_op), mirroring the `spa_ws_frames_total` result label. Both bounded enums.
  val Role      = "role"
  val Result    = "result"
  // #2653 — the tenant a log line belongs to. Bounded by household count (the same order as
  // `routerId`, which is already here), and it is what makes a per-household line attributable
  // without parsing the message body.
  val Household = "household"

  // ── Helpers ──────────────────────────────────────────────────────────────

  /** Attach a single annotation to the wrapped effect. */
  def annotate[R, E, A](key: String, value: String)(zio: ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.logAnnotate(key, value)(zio)

  /**
   * Attach every (key, value) pair as a log annotation. For optional values, use [[annotateOpt]] —
   * this overload does not filter, so passing a null value would emit a stringified-null
   * annotation.
   */
  def annotateAll[R, E, A](pairs: (String, String)*)(zio: ZIO[R, E, A]): ZIO[R, E, A] =
    pairs.foldRight(zio)((kv, acc) => ZIO.logAnnotate(kv._1, kv._2)(acc))

  /** Optional-value annotation — when `value` is None this is a no-op wrapper. */
  def annotateOpt[R, E, A](key: String, value: Option[String])(zio: ZIO[R, E, A]): ZIO[R, E, A] =
    value.fold(zio)(v => ZIO.logAnnotate(key, v)(zio))
}
