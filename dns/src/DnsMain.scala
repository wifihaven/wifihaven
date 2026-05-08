package familydns.dns

import familydns.api.{AppConfig, DnsClientConfig}
import familydns.api.db.*
import familydns.shared.{Schedule as _, *}
import zio.*
import zio.logging.backend.SLF4J

import java.time.LocalDate
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

object DnsMain extends ZIOAppDefault:

  override val bootstrap =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  def run = program.provide(env)

  private val env =
    AppConfig.layer >+>
      ZLayer.fromZIO(ZIO.serviceWith[AppConfig](_.db)) >+>
      ZLayer.fromZIO(ZIO.serviceWith[AppConfig](c => Database.makeTransactor(c.db))) >+>
      Repos.all

  private def toDnsConfig(c: DnsClientConfig): DnsConfig = DnsConfig(
    port = c.port,
    location = c.location,
    upstreamPrimary = c.upstreamPrimary,
    upstreamSecondary = c.upstreamSecondary,
    upstreamPort = c.upstreamPort,
    cacheRefreshSeconds = c.cacheRefreshSeconds,
    logBatchSize = c.logBatchSize,
    logFlushSeconds = c.logFlushSeconds,
  )

  private def loadCache: ZIO[
    ProfileRepo & ScheduleRepo & TimeLimitRepo & SiteTimeLimitRepo & DeviceRepo & BlocklistRepo,
    Throwable,
    DnsCache,
  ] =
    for
      profileRepo <- ZIO.service[ProfileRepo]
      schedRepo   <- ZIO.service[ScheduleRepo]
      tlRepo      <- ZIO.service[TimeLimitRepo]
      stlRepo     <- ZIO.service[SiteTimeLimitRepo]
      deviceRepo  <- ZIO.service[DeviceRepo]
      blRepo      <- ZIO.service[BlocklistRepo]
      profiles    <- profileRepo.listAll
      schedules   <- schedRepo.listAll
      timeLimits  <- tlRepo.listAll
      stls        <- stlRepo.listAll
      devices     <- deviceRepo.listAll
      blocklists  <- blRepo.loadAll
      cached    = profiles.map { p =>
        val sched = schedules.filter(_.profileId == p.id)
        val tl    = timeLimits.find(_.profileId == p.id).map(_.dailyMinutes)
        val pstls = stls.filter(_.profileId == p.id)
        p.id -> CachedProfile(p, sched, tl, pstls)
      }.toMap
      deviceMap = devices.flatMap(d => d.profileId.flatMap(cached.get).map(d.mac -> _)).toMap
    yield DnsCache(deviceMap, blocklists, cached.values.find(_.profile.name == "Adults"))

  private def loadUsage: ZIO[TimeUsageRepo & TimeExtensionRepo, Throwable, TimeUsageSnapshot] =
    for
      usageRepo <- ZIO.service[TimeUsageRepo]
      extRepo   <- ZIO.service[TimeExtensionRepo]
      today = LocalDate.now()
      ts    = today.toString
      rows <- usageRepo.snapshotAll(today)
      exts <- extRepo.snapshotAll(today)
    yield TimeUsageSnapshot(
      domainUsage = rows.map { case ((mac, dom), m) => (mac, dom, ts) -> m },
      totalUsage = rows.groupBy(_._1._1).map((m, vs) => (m, ts) -> vs.values.sum),
      extensions = exts.map((m, v) => (m, ts) -> v),
    )

  private def drainLogs(
      queue: ConcurrentLinkedQueue[QueryLogEntry],
      batchSize: Int,
  ): ZIO[QueryLogRepo, Throwable, Unit] =
    for
      logRepo <- ZIO.service[QueryLogRepo]
      drained <- ZIO.attempt {
        val buf = scala.collection.mutable.ListBuffer.empty[QueryLogEntry]
        var i   = 0
        var e   = queue.poll()
        while e != null && i < batchSize do
          buf += e
          i += 1
          e = queue.poll()
        buf.toList
      }
      _       <- ZIO.when(drained.nonEmpty):
        val inserts = drained.map(e =>
          QueryLogInsert(
            e.mac,
            e.deviceName,
            e.profileId,
            e.profileName,
            e.domain,
            e.qtype,
            e.blocked,
            e.reason,
            e.location,
          ),
        )
        logRepo.insertBatch(inserts)
    yield ()

  private def program =
    for
      cfg <- ZIO.service[AppConfig]
      dnsCfg = toDnsConfig(cfg.dns)
      _ <- ZIO.logInfo(s"FamilyDNS DNS starting on :${dnsCfg.port} (location=${dnsCfg.location})")
      cacheRef <- Ref.make(DnsCache.empty)
      usageRef <- Ref.make(TimeUsageSnapshot.empty)
      logQueue = new ConcurrentLinkedQueue[QueryLogEntry]()
      _          <- loadCache
        .flatMap(cacheRef.set)
        .catchAllCause(c => ZIO.logErrorCause("initial cache load failed", c))
      _          <- loadUsage
        .flatMap(usageRef.set)
        .catchAllCause(c => ZIO.logErrorCause("initial usage load failed", c))
      _          <- loadCache
        .flatMap(cacheRef.set)
        .catchAllCause(c => ZIO.logErrorCause("cache refresh failed", c))
        .repeat(Schedule.spaced(dnsCfg.cacheRefreshSeconds.seconds))
        .forkDaemon
      _          <- loadUsage
        .flatMap(usageRef.set)
        .catchAllCause(c => ZIO.logErrorCause("usage refresh failed", c))
        .repeat(Schedule.spaced(dnsCfg.cacheRefreshSeconds.seconds))
        .forkDaemon
      _          <- drainLogs(logQueue, dnsCfg.logBatchSize)
        .catchAllCause(c => ZIO.logErrorCause("log drain failed", c))
        .repeat(Schedule.spaced(dnsCfg.logFlushSeconds.seconds))
        .forkDaemon
      deviceRepo <- ZIO.service[DeviceRepo]
      _          <- new DnsServer(dnsCfg, cacheRef, usageRef, logQueue, deviceRepo).serve
    yield ()
