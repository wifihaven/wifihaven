package wifihaven.shared.contract

import wifihaven.shared.*
import wifihaven.shared.types.*
import zio.json.*

import java.util.UUID

/**
 * Representative payloads for bidirectional contract tests (#634).
 *
 * These are constructed via the *production* zio-json codecs (see Models.scala `derives
 * JsonCodec`). Renaming a wire field in Models.scala flips the generated JSON and trips the
 * consumer-side parser tests.
 *
 * Keep these vivid: every optional field should appear set in at least one fixture so a "field
 * accidentally dropped from the codec" regression is caught regardless of which fixture the
 * consumer loads.
 */
object ContractFixtures {

  // ── API → router fixtures ────────────────────────────────────────────────

  /**
   * Policy snapshot — the central API→router surface (#297/#302/#354). Exercises:
   *   - devices: per-MAC override rules (rules != null) AND profile-resolved rules (rules == null)
   *   - profiles: every BlockRules field populated incl. blockReason and blockIpOnly
   *   - blocklists: at least one entry with version + url
   *   - failureMode: all three variants represented across the profile set
   */
  val policySnapshot: PolicySnapshot = {
    val macKid     = MacAddress.parse("aa:bb:cc:11:22:33").toOption.get
    val macAdult   = MacAddress.parse("de:ad:be:ef:00:01").toOption.get
    val macBlocked = MacAddress.parse("12:34:56:78:9a:bc").toOption.get
    val kidsPid    = ProfileId(3L)
    val adultsPid  = ProfileId(4L)
    val adsList    = BlocklistId.parse("ads").toOption.get
    val adultList  = BlocklistId.parse("adult").toOption.get
    val tiktok     = Hostname.parse("tiktok.com").toOption.get
    val khan       = Hostname.parse("khanacademy.org").toOption.get

    PolicySnapshot(
      etag = ETag.unsafe("\"sha256:abc123def4567890\""),
      generatedAt = "2026-05-17T14:00:00Z",
      devices = Map(
        macKid     -> DevicePolicy(
          profileId = Some(kidsPid),
          name = "kid-ipad",
          rules = None, // resolves to profiles["3"].rules
        ),
        macAdult   -> DevicePolicy(
          profileId = Some(adultsPid),
          name = "parent-phone",
          rules = None,
        ),
        macBlocked -> DevicePolicy(
          profileId = None,
          name = "unknown-device",
          rules = Some(
            BlockRules(
              blocked = true,
              blockReason = Some(MacBlockReason.Manual),
              extraBlocked = Nil,
              extraAllowed = List(Hostname.parse("school.example.org").toOption.get),
              blocklistIds = Nil,
              blockIpOnly = false,
            ),
          ),
        ),
      ),
      profiles = Map(
        kidsPid   -> ProfilePolicy(
          name = "kids",
          rules = BlockRules(
            blocked = false,
            blockReason = None,
            extraBlocked = List(tiktok),
            extraAllowed = List(khan),
            blocklistIds = List(adsList, adultList),
            blockIpOnly = false,
          ),
          failureMode = FailureMode.BlockAll,
        ),
        adultsPid -> ProfilePolicy(
          name = "adults",
          rules = BlockRules(
            blocked = false,
            blockReason = None,
            extraBlocked = Nil,
            extraAllowed = Nil,
            blocklistIds = List(adsList),
            blockIpOnly = true,
          ),
          failureMode = FailureMode.LastKnownGood,
        ),
      ),
      blocklists = Map(
        adsList   -> Blocklist(
          version = BlocklistVersion.unsafe("2026.05.01"),
          url = BlocklistUrl.unsafe("/api/blocklists/ads"),
        ),
        adultList -> Blocklist(
          version = BlocklistVersion.unsafe("2026.05.01"),
          url = BlocklistUrl.unsafe("https://example.org/lists/adult.txt"),
        ),
      ),
    )
  }

  // ── Router → API fixtures ────────────────────────────────────────────────
  //
  // These mirror what `openwrt/files/usr/lib/lua/wifihaven/conntrack.lua`
  // and `usage.lua` POST to /api/router/events and /api/router/usage. The
  // API decoder MUST accept these shapes without losing fields or falling
  // back to defaults (the #456 / hostname-drift regression class).
  //
  // The agent batches connection_attempt, dhcp_lease, and first_seen_mac
  // events into a single RouterEventsRequest. We include all three variants
  // so codec changes (e.g. renaming `destIp` to `dest_ip`) break the test.

  private val routerId = RouterId(UUID.fromString("11111111-2222-3333-4444-555555555555"))
  private val macKid   = MacAddress.parse("aa:bb:cc:11:22:33").toOption.get

  val routerEventsRequest: RouterEventsRequest = RouterEventsRequest(
    routerId = routerId,
    events = List(
      // connection_attempt with FQDN host (the common case)
      RouterEvent(
        `type` = "connection_attempt",
        mac = Some(macKid),
        host = Some(HostId.fqdn(Hostname.parse("youtube.com").toOption.get)),
        destIp = Some(IpAddress.parse("142.250.80.46").toOption.get),
        allowed = Some(false),
        reason = Some("blocked"),
        ts = "2026-05-17T14:00:01Z",
        eventId = Some(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")),
      ),
      // connection_attempt with IPv6 host (DNS attribution miss, direct-IP path)
      RouterEvent(
        `type` = "connection_attempt",
        mac = Some(macKid),
        host = Some(HostId.ip(IpAddress.parse("2607:f8b0:4005:80a::200e").toOption.get)),
        destIp = Some(IpAddress.parse("2607:f8b0:4005:80a::200e").toOption.get),
        allowed = Some(true),
        reason = Some("allow"),
        ts = "2026-05-17T14:00:02Z",
        eventId = Some(UUID.fromString("11111111-2222-3333-4444-555555555556")),
      ),
      // dhcp_lease — late hostname for previously seen MAC (#249)
      RouterEvent(
        `type` = "dhcp_lease",
        mac = Some(macKid),
        ip = Some(IpAddress.parse("192.168.10.50").toOption.get),
        hostname = Some(Hostname.parse("kid-ipad").toOption.get),
        ts = "2026-05-17T14:00:03Z",
      ),
      // first_seen_mac — initial flow, hostname may be absent
      RouterEvent(
        `type` = "first_seen_mac",
        mac = Some(MacAddress.parse("76:2d:95:47:d1:8e").toOption.get),
        ip = Some(IpAddress.parse("192.168.10.51").toOption.get),
        hostname = None,
        ts = "2026-05-17T14:00:04Z",
      ),
    ),
  )

  val usageReport: UsageReport = UsageReport(
    routerId = routerId,
    periodStart = "2026-05-17T13:55:00Z",
    periodEnd = "2026-05-17T14:00:00Z",
    records = List(
      UsageRecord(
        mac = macKid,
        ip = Some(IpAddress.parse("192.168.10.50").toOption.get),
        host = HostId.fqdn(Hostname.parse("khanacademy.org").toOption.get),
        activeSeconds = 240L,
        bytesIn = 1_048_576L,
        bytesOut = 0L,
      ),
      UsageRecord(
        mac = MacAddress.parse("76:2d:95:47:d1:8e").toOption.get,
        ip = None,
        host = HostId.ip(IpAddress.parse("192.168.10.99").toOption.get),
        activeSeconds = 60L,
        bytesIn = 4096L,
        bytesOut = 0L,
      ),
    ),
  )

  val registerRouterRequest: RegisterRouterRequest = RegisterRouterRequest(
    enrollmentToken = EnrollmentToken.unsafe("et_" + "a" * 32),
    platformVersion = Some("OpenWrt 23.05.5"),
    agentVersion = Some("wifihaven-agent 1.2.3"),
  )

  // ── Pretty-printed JSON for golden files ────────────────────────────────

  def pretty[A: JsonEncoder](a: A): String = a.toJsonPretty + "\n"

  val apiToRouter: List[(String, String)] = List(
    "policy_snapshot.json" -> pretty(policySnapshot),
  )

  val routerToApi: List[(String, String)] = List(
    "router_events_request.json"   -> pretty(routerEventsRequest),
    "usage_report.json"            -> pretty(usageReport),
    "register_router_request.json" -> pretty(registerRouterRequest),
  )
}
