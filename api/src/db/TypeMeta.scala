package familydns.api.db

import doobie.*
import doobie.postgres.implicits.*
import familydns.shared.{FailureMode, MacBlockReason, UserRole}
import familydns.shared.types.*

/**
 * Doobie `Meta` instances for opaque-type wrappers (#114). Each delegates to the underlying base
 * type's `Meta` — so the DB schema (text / bigint / uuid) is unchanged, only the in-Scala type
 * carried at the API boundary differs.
 *
 * Reads use the `unsafe` constructors: DB values are presumed canonical because they were inserted
 * via parsed values. If you suspect garbage in a column, run a one-shot migration to canonicalize
 * before reading.
 */
object TypeMeta {

  // ── Long-backed identifiers ────────────────────────────────────────────
  given Meta[ProfileId]         = Meta[Long].imap(ProfileId(_))(_.value)
  given Meta[DeviceId]          = Meta[Long].imap(DeviceId(_))(_.value)
  given Meta[ScheduleId]        = Meta[Long].imap(ScheduleId(_))(_.value)
  given Meta[TimeLimitId]       = Meta[Long].imap(TimeLimitId(_))(_.value)
  given Meta[SiteTimeLimitId]   = Meta[Long].imap(SiteTimeLimitId(_))(_.value)
  given Meta[TimeExtensionId]   = Meta[Long].imap(TimeExtensionId(_))(_.value)
  given Meta[UserId]            = Meta[Long].imap(UserId(_))(_.value)
  given Meta[BlockEventId]      = Meta[Long].imap(BlockEventId(_))(_.value)
  given Meta[ConnectionEventId] = Meta[Long].imap(ConnectionEventId(_))(_.value)
  given Meta[QueryLogId]        = Meta[Long].imap(QueryLogId(_))(_.value)
  given Meta[TrafficReportId]   = Meta[Long].imap(TrafficReportId(_))(_.value)
  given Meta[TimeUsageId]       = Meta[Long].imap(TimeUsageId(_))(_.value)

  // ── UUID-backed ────────────────────────────────────────────────────────
  given Meta[RouterId] = Meta[java.util.UUID].imap(RouterId(_))(_.value)

  // ── String-backed: identifiers and validated primitives ────────────────
  given Meta[MacAddress]  = Meta[String].imap(MacAddress.unsafe)(_.value)
  given Meta[Hostname]    = Meta[String].imap(Hostname.unsafe)(_.value)
  given Meta[IpAddress]   = Meta[String].imap(IpAddress.unsafe)(_.value)
  given Meta[BlocklistId] = Meta[String].imap(BlocklistId.unsafe)(_.value)

  // ── String-backed: tokens / hashes ─────────────────────────────────────
  given Meta[ETag]             = Meta[String].imap(ETag.unsafe)(_.value)
  given Meta[Sha256Hex]        = Meta[String].imap(Sha256Hex.unsafe)(_.value)
  given Meta[EnrollmentToken]  = Meta[String].imap(EnrollmentToken.unsafe)(_.value)
  given Meta[RouterToken]      = Meta[String].imap(RouterToken.unsafe)(_.value)
  given Meta[BlocklistVersion] = Meta[String].imap(BlocklistVersion.unsafe)(_.value)
  given Meta[Url]              = Meta[String].imap(Url.unsafe)(_.value)
  given Meta[BlocklistUrl]     = Meta[String].imap(BlocklistUrl.unsafe)(_.value)
  given Meta[JwtToken]         = Meta[String].imap(JwtToken.unsafe)(_.value)

  // ── Enums persisted as text columns ────────────────────────────────────
  given Meta[UserRole] = Meta[String].imap(s =>
    UserRole.parse(s).getOrElse(throw new IllegalStateException(s"DB has unknown role: $s")),
  )(UserRole.asString)

  given Meta[FailureMode] = Meta[String].imap(s =>
    FailureMode
      .parse(s)
      .getOrElse(
        throw new IllegalStateException(s"DB has unknown failureMode: $s"),
      ),
  )(FailureMode.asString)

  given Meta[MacBlockReason] = Meta[String].imap(s =>
    MacBlockReason
      .parse(s)
      .getOrElse(
        throw new IllegalStateException(s"DB has unknown blockReason: $s"),
      ),
  )(MacBlockReason.asString)

  // ── text[] columns where the element is a typed wrapper ────────────────
  given blocklistIdListMeta: Meta[List[BlocklistId]] = Meta[Array[String]]
    .imap(a => a.toList.map(BlocklistId.unsafe))(_.map(_.value).toArray)
  given hostnameListMeta: Meta[List[Hostname]]       = Meta[Array[String]]
    .imap(a => a.toList.map(Hostname.unsafe))(_.map(_.value).toArray)

  // ── HostId: two-column composite (host_type, host_value) per #391 ──────
  // Every table that holds a HostId stores it as a pair of TEXT columns; the
  // discriminator lets us cleanly distinguish FQDN rows from IP-literal rows
  // (e.g. so site-limit pattern matchers can skip the latter).
  // Reads expect the SELECT list to emit (host_type, host_value) in order;
  // writes expect the INSERT/UPDATE column list to be (host_type, host_value)
  // in the same order. Any SQL touching HostId columns must respect that.
  given Read[HostId]  = Read[(String, String)].map { case (kind, value) =>
    kind match {
      case "fqdn" => HostId.Fqdn(Hostname.unsafe(value))
      case "ipv4" => HostId.IPv4(IpAddress.unsafe(value))
      case "ipv6" => HostId.IPv6(IpAddress.unsafe(value))
      case other  =>
        throw new IllegalStateException(s"DB has unknown host_type: $other")
    }
  }
  given Write[HostId] = Write[(String, String)].contramap(h => (h.kind, h.value))
}
