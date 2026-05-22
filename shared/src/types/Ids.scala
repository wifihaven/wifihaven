package wifihaven.shared.types

import zio.json.*

import java.util.UUID

/**
 * Long-backed DB row identifiers. Each is a distinct opaque type so passing a `ProfileId` where a
 * `DeviceId` is expected fails to compile. Wire shape is unchanged: encodes as a raw JSON number,
 * and as a string when used as a JSON object key (matching `Map[Long, _]`'s default rendering).
 */

opaque type ProfileId             = Long
opaque type DeviceId              = Long
opaque type ScheduleId            = Long
opaque type TimeLimitId           = Long
opaque type SiteTimeLimitId       = Long
opaque type TimeExtensionId       = Long
opaque type UserId                = Long
opaque type BlockEventId          = Long
opaque type ConnectionEventId     = Long
opaque type QueryLogId            = Long
opaque type TrafficReportId       = Long
opaque type TimeUsageId           = Long
opaque type DeviceAlertId         = Long
opaque type AppId                 = Long
opaque type AppPolicyAssignmentId = Long

object ProfileId {
  def apply(l: Long): ProfileId            = l
  extension (p: ProfileId) def value: Long = p
  given JsonCodec[ProfileId]               = JsonCodec.long
  given JsonFieldEncoder[ProfileId]        = JsonFieldEncoder.long
  given JsonFieldDecoder[ProfileId]        = JsonFieldDecoder.long
  given Ordering[ProfileId]                = Ordering.Long
}

object DeviceId {
  def apply(l: Long): DeviceId            = l
  extension (d: DeviceId) def value: Long = d
  given JsonCodec[DeviceId]               = JsonCodec.long
  given JsonFieldEncoder[DeviceId]        = JsonFieldEncoder.long
  given JsonFieldDecoder[DeviceId]        = JsonFieldDecoder.long
  given Ordering[DeviceId]                = Ordering.Long
}

object ScheduleId {
  def apply(l: Long): ScheduleId            = l
  extension (s: ScheduleId) def value: Long = s
  given JsonCodec[ScheduleId]               = JsonCodec.long
  given Ordering[ScheduleId]                = Ordering.Long
}

object TimeLimitId {
  def apply(l: Long): TimeLimitId            = l
  extension (t: TimeLimitId) def value: Long = t
  given JsonCodec[TimeLimitId]               = JsonCodec.long
}

object SiteTimeLimitId {
  def apply(l: Long): SiteTimeLimitId            = l
  extension (s: SiteTimeLimitId) def value: Long = s
  given JsonCodec[SiteTimeLimitId]               = JsonCodec.long
}

object TimeExtensionId {
  def apply(l: Long): TimeExtensionId            = l
  extension (t: TimeExtensionId) def value: Long = t
  given JsonCodec[TimeExtensionId]               = JsonCodec.long
}

object UserId {
  def apply(l: Long): UserId            = l
  extension (u: UserId) def value: Long = u
  given JsonCodec[UserId]               = JsonCodec.long
  given Ordering[UserId]                = Ordering.Long
}

object BlockEventId {
  def apply(l: Long): BlockEventId            = l
  extension (b: BlockEventId) def value: Long = b
  given JsonCodec[BlockEventId]               = JsonCodec.long
}

object ConnectionEventId {
  def apply(l: Long): ConnectionEventId            = l
  extension (c: ConnectionEventId) def value: Long = c
  given JsonCodec[ConnectionEventId]               = JsonCodec.long
}

object QueryLogId {
  def apply(l: Long): QueryLogId            = l
  extension (q: QueryLogId) def value: Long = q
  given JsonCodec[QueryLogId]               = JsonCodec.long
}

object TrafficReportId {
  def apply(l: Long): TrafficReportId            = l
  extension (t: TrafficReportId) def value: Long = t
  given JsonCodec[TrafficReportId]               = JsonCodec.long
}

object TimeUsageId {
  def apply(l: Long): TimeUsageId            = l
  extension (t: TimeUsageId) def value: Long = t
  given JsonCodec[TimeUsageId]               = JsonCodec.long
}

object DeviceAlertId {
  def apply(l: Long): DeviceAlertId            = l
  extension (a: DeviceAlertId) def value: Long = a
  given JsonCodec[DeviceAlertId]               = JsonCodec.long
}

object AppId {
  def apply(l: Long): AppId            = l
  extension (a: AppId) def value: Long = a
  given JsonCodec[AppId]               = JsonCodec.long
  given JsonFieldEncoder[AppId]        = JsonFieldEncoder.long
  given JsonFieldDecoder[AppId]        = JsonFieldDecoder.long
  given Ordering[AppId]                = Ordering.Long
}

object AppPolicyAssignmentId {
  def apply(l: Long): AppPolicyAssignmentId            = l
  extension (a: AppPolicyAssignmentId) def value: Long = a
  given JsonCodec[AppPolicyAssignmentId]               = JsonCodec.long
  given Ordering[AppPolicyAssignmentId]                = Ordering.Long
}

/** UUID-backed router identifier. */
opaque type RouterId = UUID

object RouterId {
  def apply(u: UUID): RouterId            = u
  extension (r: RouterId) def value: UUID = r

  given JsonCodec[RouterId]        = JsonCodec.string.transformOrFail(
    s => scala.util.Try(UUID.fromString(s)).toEither.left.map(_.getMessage),
    _.value.toString,
  )
  given JsonFieldEncoder[RouterId] = JsonFieldEncoder.string.contramap(_.value.toString)
  given JsonFieldDecoder[RouterId] = JsonFieldDecoder.string.mapOrFail(s =>
    scala.util.Try(UUID.fromString(s)).toEither.left.map(_.getMessage),
  )
  given Ordering[RouterId]         = (a, b) => a.value.compareTo(b.value)
}
