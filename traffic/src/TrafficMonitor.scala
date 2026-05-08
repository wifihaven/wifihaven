package familydns.traffic

import familydns.shared.*
import org.pcap4j.core.*
import org.pcap4j.packet.*
import zio.*

import java.net.InetAddress
import java.time.LocalDate
import scala.collection.mutable

case class TrafficConfig(
    interface: String,
    sessionTimeoutSecs: Int,
    flushIntervalSecs: Int,
    location: String,
)

case class Session(
    mac: String,
    domain: String,
    startedAt: Long,
    lastSeenAt: Long,
    bytes: Long,
)

/**
 * Tracks active traffic sessions.
 *
 * Uses Scala mutable.HashMap intentionally — this runs in a tight inner loop handling every network
 * packet. ZIO Ref overhead per-packet would be significant. All mutation is single-threaded (called
 * from one fiber via captureLoop). The sweepExpired / drainPending methods are also called from a
 * single fiber.
 */
class SessionTracker(
    config: TrafficConfig,
    @scala.annotation.unused dnsCacheRef: Ref[DnsCache],
) {
  // mutable allowed: tight inner loop, single-fiber access
  private val sessions = mutable.HashMap[(String, String), Session]()
  private val pending  = mutable.HashMap[(String, String, String), Int]()

  def recordPacket(srcMac: String, dstIp: String, bytes: Int): Unit = {
    val domain = reverseDnsOrIp(dstIp)
    val apex   = apexDomain(domain)
    val key    = (srcMac, apex)
    val now    = java.lang.System.currentTimeMillis()
    sessions.get(key) match {
      case Some(sess) => sessions(key) = sess.copy(lastSeenAt = now, bytes = sess.bytes + bytes)
      case None       => sessions(key) = Session(srcMac, apex, now, now, bytes)
    }
  }

  def sweepExpired(): Unit = {
    val now     = java.lang.System.currentTimeMillis()
    val timeout = config.sessionTimeoutSecs * 1000L
    val today   = LocalDate.now().toString
    sessions.filterInPlace { case (_, sess) =>
      val expired = now - sess.lastSeenAt > timeout
      if expired then {
        val secs = ((sess.lastSeenAt - sess.startedAt) / 1000).toInt.max(1)
        val k    = (sess.mac, sess.domain, today)
        pending(k) = pending.getOrElse(k, 0) + secs
      }
      !expired
    }
  }

  def drainPending(): List[(String, String, LocalDate, Int)] = {
    val result = pending.toList.map { case ((mac, dom, dateStr), secs) =>
      (mac, dom, LocalDate.parse(dateStr), (secs / 60).max(1))
    }
    pending.clear()
    result
  }

  // Test helpers — only used in tests
  private[traffic] def activeSessions: Map[(String, String), Session] = sessions.toMap
  private[traffic] def forceExpireAll(): Unit                         = {
    val old = java.lang.System.currentTimeMillis() - (config.sessionTimeoutSecs + 1) * 1000L
    sessions.keys.foreach(k => sessions(k) = sessions(k).copy(lastSeenAt = old))
  }

  private def apexDomain(host: String): String = {
    val parts = host.split('.')
    if parts.length >= 2 then parts.takeRight(2).mkString(".") else host
  }

  private def reverseDnsOrIp(ip: String): String =
    try InetAddress.getByName(ip).getCanonicalHostName
    catch case _: Exception => ip
}

trait TrafficMonitor {
  def start: Task[Unit]
  def getSnapshot: UIO[TimeUsageSnapshot]
}

class TrafficMonitorLive(
    config: TrafficConfig,
    tracker: SessionTracker,
    usageRef: Ref[TimeUsageSnapshot],
    dbFlush: (String, String, LocalDate, Int) => Task[Unit],
) extends TrafficMonitor {

  def start: Task[Unit] =
    captureLoop.zipParLeft(flushLoop).zipParLeft(sweepLoop)

  def getSnapshot: UIO[TimeUsageSnapshot] = usageRef.get

  private def captureLoop: Task[Unit] =
    ZIO.attemptBlocking {
      val nif    = Pcaps.getDevByName(config.interface)
      val handle = nif.openLive(65536, PcapNetworkInterface.PromiscuousMode.PROMISCUOUS, 50)
      handle.setFilter("ip", BpfProgram.BpfCompileMode.OPTIMIZE)
      handle.loop(-1, (pkt: Packet) => handlePacket(pkt))
    }

  private def handlePacket(pkt: Packet): Unit =
    try {
      val eth    = pkt.get(classOf[EthernetPacket])
      if eth == null then return
      val srcMac = eth.getHeader.getSrcAddr.toString.toLowerCase
      val ip4    = pkt.get(classOf[IpV4Packet])
      if ip4 == null then return
      tracker.recordPacket(srcMac, ip4.getHeader.getDstAddr.getHostAddress, ip4.length())
    } catch case _: Exception => ()

  private def sweepLoop: Task[Unit] =
    (ZIO.succeed(tracker.sweepExpired()) *> ZIO.sleep(5.seconds)).forever

  private def flushLoop: Task[Unit] =
    (ZIO.sleep(config.flushIntervalSecs.seconds) *> flush).forever

  private def flush: Task[Unit] =
    ZIO.foreachDiscard(tracker.drainPending()) { (mac, dom, date, mins) =>
      dbFlush(mac, dom, date, mins).orElse(ZIO.unit)
    }
}
