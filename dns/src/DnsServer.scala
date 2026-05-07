package familydns.dns

import familydns.shared.*
import zio.*

import java.net.{DatagramPacket, DatagramSocket, InetAddress}
import java.time.{LocalDate, LocalTime}
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

// ── Config ─────────────────────────────────────────────────────────────────

case class DnsConfig(
    port: Int,
    location: String,
    upstreamPrimary: String,
    upstreamSecondary: String,
    upstreamPort: Int,
    cacheRefreshSeconds: Int,
    logBatchSize: Int,
    logFlushSeconds: Int,
)

// ── DNS Server ─────────────────────────────────────────────────────────────

class DnsServer(
    config: DnsConfig,
    cacheRef: Ref[DnsCache],
    usageRef: Ref[TimeUsageSnapshot],
    logQueue: ConcurrentLinkedQueue[QueryLogEntry],
    deviceRepo: familydns.api.db.DeviceRepo,
):

  private val upstreams = List(
    (config.upstreamPrimary, config.upstreamPort),
    (config.upstreamSecondary, config.upstreamPort),
  )

  def serve: Task[Unit] =
    ZIO.acquireReleaseWith(
      ZIO.attempt(new DatagramSocket(config.port)),
    )(sock => ZIO.succeed(sock.close())) { sock =>
      ZIO.logInfo(s"DNS server listening on :${config.port}") *>
        ZIO
          .loop(())(Function.const(true), identity) { _ =>
            for
              buf <- ZIO.succeed(new Array[Byte](512))
              pkt <- ZIO.succeed(new DatagramPacket(buf, buf.length))
              _   <- ZIO.attempt(sock.receive(pkt))
              data = buf.take(pkt.getLength)
              addr = pkt.getAddress
              port = pkt.getPort
              _ <- handleQuery(sock, data, addr, port).forkDaemon
            yield ()
          }
          .unit
    }

  private def handleQuery(
      sock: DatagramSocket,
      data: Array[Byte],
      addr: InetAddress,
      port: Int,
  ): Task[Unit] =
    DnsPacket.parseQuery(data) match
      case None        => ZIO.unit
      case Some(query) =>
        for
          cache <- cacheRef.get
          usage <- usageRef.get
          clientIp = addr.getHostAddress
          mac      = arpLookup(clientIp)
          _ <- ZIO.logDebug(
            s"query from $clientIp mac=${mac.getOrElse("?")} domain=${query.domain} qtype=${query.qtype}",
          )
          // Only fall back to the default profile when we have a real MAC that isn't registered.
          // If ARP failed (mac=None) the device is truly unrecognized and we forward+log it.
          profile = mac.flatMap(m => cache.deviceProfiles.get(m).orElse(cache.defaultProfile))
          _        <- ZIO.logDebug(
            s"profile for mac=${mac.getOrElse("?")} -> ${profile.map(_.profile.name).getOrElse("none (unrecognized)")}",
          )
          response <- profile match
            case None     =>
              // Unknown device — forward with no filtering but log so we can identify & onboard it
              ZIO.logDebug(
                s"unrecognized device ip=$clientIp mac=${mac.getOrElse("no-arp-entry")}, forwarding upstream",
              ) *>
                forwardUpstream(data)
                  .map(_.getOrElse(DnsPacket.buildNxdomain(data)))
                  .tap(_ => enqueueLog(mac, None, query, blocked = false, ""))
            case Some(cp) =>
              for
                _ <- ZIO.foreachDiscard(mac) { m =>
                  deviceRepo
                    .updateLastSeen(m, clientIp)
                    .catchAllCause(c => ZIO.logWarningCause("updateLastSeen failed", c))
                    .forkDaemon
                }
                now      = LocalTime.now()
                today    = LocalDate.now()
                decision = BlockingEngine.decide(
                  query.domain,
                  cp,
                  cache.blocklists,
                  usage,
                  mac.getOrElse(""),
                  now,
                  today,
                )
                _    <- ZIO.logDebug(
                  s"decision for ${query.domain} profile=${cp.profile.name} -> $decision",
                )
                resp <- decision match
                  case BlockingEngine.Decision.Allow         =>
                    forwardUpstream(data)
                      .map(_.getOrElse(DnsPacket.buildNxdomain(data)))
                      .tap(_ => enqueueLog(mac, Some(cp), query, blocked = false, ""))
                  case BlockingEngine.Decision.Block(reason) =>
                    val r =
                      if reason.startsWith("schedule") || reason == "paused"
                      then DnsPacket.buildRefused(data)
                      else DnsPacket.buildNxdomain(data)
                    ZIO.succeed(r)
                      <* enqueueLog(mac, Some(cp), query, blocked = true, reason)
              yield resp
          _        <- ZIO.attempt:
            val out = new DatagramPacket(response, response.length, addr, port)
            sock.send(out)
        yield ()

  private def enqueueLog(
      mac: Option[String],
      profile: Option[CachedProfile],
      query: DnsPacket.Query,
      blocked: Boolean,
      reason: String,
  ): UIO[Unit] =
    // Only log A/AAAA queries to keep noise down
    if !List(DnsPacket.TYPE_A, DnsPacket.TYPE_AAAA).contains(query.qtype) then ZIO.unit
    else
      ZIO.succeed:
        val _ = logQueue.add(
          QueryLogEntry(
            mac = mac,
            deviceName = mac, // DNS doesn't have name; API joins later
            profileId = profile.map(_.profile.id),
            profileName = profile.map(_.profile.name),
            domain = query.domain,
            qtype = query.qtype,
            blocked = blocked,
            reason = reason,
            location = Some(config.location),
          ),
        )

  private def forwardUpstream(data: Array[Byte]): Task[Option[Array[Byte]]] =
    ZIO
      .attemptBlocking:
        upstreams.iterator
          .flatMap { (host, port) =>
            try
              val sock = new DatagramSocket()
              sock.setSoTimeout(3000)
              val addr = InetAddress.getByName(host)
              sock.send(new DatagramPacket(data, data.length, addr, port))
              val buf  = new Array[Byte](4096)
              val resp = new DatagramPacket(buf, buf.length)
              sock.receive(resp)
              sock.close()
              Some(buf.take(resp.getLength))
            catch case _: Exception => None
          }
          .nextOption()
      .orElse(ZIO.succeed(None))

  /** ARP table lookup: IP → MAC. Returns canonical lowercase MAC or None. */
  private def arpLookup(ip: String): Option[String] =
    try
      val lines = scala.io.Source.fromFile("/proc/net/arp").getLines().drop(1)
      lines.collectFirst {
        case line if line.split("\\s+").headOption.contains(ip) =>
          line.split("\\s+")(3).toLowerCase
      }
    catch case _: Exception => None

case class QueryLogEntry(
    mac: Option[String],
    deviceName: Option[String],
    profileId: Option[Long],
    profileName: Option[String],
    domain: String,
    qtype: Int,
    blocked: Boolean,
    reason: String,
    location: Option[String],
)
