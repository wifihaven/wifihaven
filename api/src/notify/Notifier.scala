package wifihaven.api.notify

import wifihaven.shared.{AccessRequestKind, Alert, AlertKind}
import wifihaven.shared.types.HouseholdId
import zio.*

/**
 * #960: admin-notification sink for newly-raised alerts. Today the only transport is a structured
 * log line; the trait exists so when #874's notification bus lands, `Notifier.live` becomes
 * whatever bus that picks (email / web push / …) without re-shaping the call sites. The in-app
 * banner stays authoritative either way.
 */
trait Notifier {
  def alertCreated(a: Alert): UIO[Unit]

  /**
   * #2132: a beta household was provisioned on operator approval (design §3.3). No email transport
   * is invented (docs/design/alerting.md §4) — `.live` logs a line carrying everything an invite
   * email would need; the operator sends the actual invite link (returned in the approve response)
   * manually in v1. `email` is the requester's, `slug` the new household's.
   */
  def betaHouseholdProvisioned(email: String, slug: String, householdId: HouseholdId): UIO[Unit]
}

object Notifier {
  val live: ULayer[Notifier] = ZLayer.succeed(new Notifier {
    def alertCreated(a: Alert): UIO[Unit] =
      ZIO.logInfo(
        s"alert created: id=${a.id.value} kind=${AlertKind.asString(a.kind)} " +
          s"mac=${a.mac.value} " +
          s"host=${a.host.fold("-")(_.value)} " +
          s"requestKind=${a.requestKind.fold("-")(AccessRequestKind.asString)} " +
          s"profile=${a.profileName.getOrElse("-")}",
      )

    def betaHouseholdProvisioned(email: String, slug: String, householdId: HouseholdId): UIO[Unit] =
      ZIO.logInfo(
        s"beta household provisioned: email=$email slug=$slug householdId=${householdId.value} " +
          s"— send the invite link (from the approve response) to $email",
      )
  })
}
