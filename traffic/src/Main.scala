package familydns.traffic

import familydns.api.db.{Database, TimeUsageRepoLive}
import familydns.shared.{DnsCache, TimeUsageSnapshot}
import zio.*
import zio.logging.backend.SLF4J

object TrafficMain extends ZIOAppDefault {

  override val bootstrap =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  def run =
    (for {
      cfg <- ZIO.service[TrafficAppConfig]
      _   <- ZIO.logInfo(
        s"FamilyDNS traffic monitor starting on interface ${cfg.traffic.interface} " +
          s"(flush ${cfg.traffic.flushIntervalSecs}s, sessionTimeout ${cfg.traffic.sessionTimeoutSecs}s)",
      )
      xa   = Database.makeTransactor(cfg.db)
      repo = TimeUsageRepoLive(xa)
      cacheRef <- Ref.make(DnsCache.empty)
      usageRef <- Ref.make(TimeUsageSnapshot.empty)
      tracker = SessionTracker(cfg.traffic, cacheRef)
      monitor = TrafficMonitorLive(cfg.traffic, tracker, usageRef, repo.incrementUsage)
      _ <- monitor.start
    } yield ()).provide(TrafficAppConfig.layer)
}
