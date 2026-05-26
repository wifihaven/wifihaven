package wifihaven.api.notify

import wifihaven.shared.{AccessRequestKind, Alert, AlertKind}
import zio.*

/**
 * #960: admin-notification sink for newly-raised alerts. Today the only transport is a structured
 * log line; the trait exists so when #874's notification bus lands, `Notifier.live` becomes
 * whatever bus that picks (email / web push / …) without re-shaping the call sites. The in-app
 * banner stays authoritative either way.
 */
trait Notifier {
  def alertCreated(a: Alert): UIO[Unit]
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
  })
}
