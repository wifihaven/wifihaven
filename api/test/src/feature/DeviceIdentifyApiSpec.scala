package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.identify.MacVendors
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import doobie.Transactor
import doobie.implicits.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*
import zio.interop.catz.*

import java.time.Instant

/**
 * #2926: `GET /api/devices/{mac}/identify` — the "What is this?" view for an unmanaged device.
 *
 * Pins, through the full stack on embedded Postgres:
 *   - an unmanaged device's view returns its connection attempts grouped by destination, BLOCKED
 *     ones included (the `unmanaged_mac` whole-MAC drop the nflog path records), with counts and
 *     first/last seen;
 *   - the advertised name (today `devices.name`, which first-seen discovery seeds from the DHCP
 *     hostname — #2927 splits it into its own column) and the OUI vendor, or "private" for a
 *     locally-administered MAC;
 *   - a MAC that only exists in another household is a 404, and a same-MAC device in another
 *     household contributes nothing to this household's counts (#2125);
 *   - reading the view changes no policy state.
 */
object DeviceIdentifyApiSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Transactor[Task] & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg   = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)
  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      // the DB-backed HouseholdRepo, so login can resolve household B's slug
      hr    <- ZIO.service[HouseholdRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock, hr): AuthService
  private val cleanDb  = TestDatabase.cleanAndMigrate

  // 84:1b:5e is NETGEAR in the IEEE MA-L table; 6a:… has the locally-administered bit set.
  private val netgearMac = MacAddress.unsafe("84:1b:5e:11:22:33")
  private val privateMac = MacAddress.unsafe("6a:20:03:aa:bb:01")
  private val managedA   = MacAddress.unsafe("aa:bb:cc:00:00:0a")
  private val managedB   = MacAddress.unsafe("aa:bb:cc:00:00:0b")

  private def fqdn(h: String): HostId = HostId.Fqdn(Hostname.unsafe(h))

  private def event(
      routerId: RouterId,
      mac: MacAddress,
      host: HostId,
      allowed: Boolean,
      ts: Instant,
  ): ConnectionEventInsert =
    ConnectionEventInsert(
      routerId = routerId,
      mac = Some(mac),
      host = host,
      destIp = None,
      allowed = allowed,
      reason = if allowed then BlockReason.Allow else MacBlockReason.Unmanaged,
      ts = ts,
    )

  private def buildRoutes =
    for {
      deviceRepo      <- ZIO.service[DeviceRepo]
      connRepo        <- ZIO.service[ConnectionEventRepo]
      appRepo         <- ZIO.service[AppRepo]
      userProfileRepo <- ZIO.service[UserProfileRepo]
      profileRepo     <- ZIO.service[ProfileRepo]
      clock           <- ZIO.service[Clock]
      vendors         <- MacVendors.load
      auth            <- makeAuth
    } yield (
      DeviceIdentifyRoutes.routes(
        auth,
        deviceRepo,
        connRepo,
        appRepo,
        userProfileRepo,
        profileRepo,
        vendors,
        clock,
      ),
      auth,
    )

  private def get(routes: Routes[Any, Response], token: String, mac: MacAddress) =
    routes.runZIO(
      Request
        .get(URL.decode(s"/api/devices/${mac.value}/identify").toOption.get)
        .addHeader(Header.Authorization.Bearer(token)),
    )

  private def decode(resp: Response): Task[DeviceIdentity] =
    resp.body.asString.flatMap(b =>
      ZIO.fromEither(b.fromJson[DeviceIdentity]).mapError(e => new RuntimeException(s"$e: $b")),
    )

  /** md5 over a table's rows, so "nothing changed" is checked on the whole table, not one field. */
  private def digest(xa: Transactor[Task], table: String): Task[String] =
    (fr"SELECT COALESCE(md5(string_agg(t::text, ',' ORDER BY t::text)), '') FROM" ++
      doobie.Fragment.const(table) ++ fr"t")
      .query[String]
      .unique
      .transact(xa)

  private val policyTables =
    List("devices", "device_profile_assignments", "alerts", "household_settings", "profiles")

  def spec = suite("Device identify API (GET /api/devices/:mac/identify)")(
    test("unmanaged device: blocked + allowed attempts grouped by host, name and vendor shown") {
      for {
        _          <- cleanDb
        two        <- TestLayers.seedTwoHouseholds(managedA, managedB)
        deviceRepo <- ZIO.service[DeviceRepo]
        connRepo   <- ZIO.service[ConnectionEventRepo]
        now        <- ZIO.serviceWithZIO[Clock](_.instant)
        _          <- deviceRepo.upsertUnknown(
          netgearMac,
          "Roku-Living-Room",
          None,
          now.minusSeconds(3600),
          two.hhA,
        )
        _          <- connRepo.insertBatch(
          List(
            // two BLOCKED attempts and one allowed to the same host
            event(two.routerIdA, netgearMac, fqdn("api.roku.com"), false, now.minusSeconds(3000)),
            event(two.routerIdA, netgearMac, fqdn("api.roku.com"), false, now.minusSeconds(600)),
            event(two.routerIdA, netgearMac, fqdn("api.roku.com"), true, now.minusSeconds(1200)),
            event(two.routerIdA, netgearMac, fqdn("time.example.org"), true, now.minusSeconds(60)),
          ),
        )
        rb         <- buildRoutes
        (routes, auth) = rb
        token <- auth.login(two.adminA, two.password).map(_.token.value)
        resp  <- get(routes, token, netgearMac)
        out   <- decode(resp)
        roku = out.destinations.find(_.host == fqdn("api.roku.com"))
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(out.name == "Roku-Living-Room") &&
        assertTrue(!out.managed) &&
        assertTrue(out.vendor.kind == "vendor") &&
        assertTrue(out.vendor.name.contains("NETGEAR")) &&
        assertTrue(out.firstSeenAt.isDefined) &&
        assertTrue(out.destinations.length == 2) &&
        // most attempts first
        assertTrue(out.destinations.head.host == fqdn("api.roku.com")) &&
        assertTrue(roku.exists(_.blocked == 2L)) &&
        assertTrue(roku.exists(_.allowed == 1L)) &&
        assertTrue(roku.exists(d => Instant.parse(d.firstSeen) == now.minusSeconds(3000))) &&
        assertTrue(roku.exists(d => Instant.parse(d.lastSeen) == now.minusSeconds(600))) &&
        assertTrue(out.totalBlocked == 2L) &&
        assertTrue(out.totalAllowed == 2L) &&
        assertTrue(out.hostCount == 2) &&
        assertTrue(!out.truncated)
    },
    test("catalog app attribution groups hosts by app and the guess is labelled from it") {
      for {
        _          <- cleanDb
        two        <- TestLayers.seedTwoHouseholds(managedA, managedB)
        deviceRepo <- ZIO.service[DeviceRepo]
        connRepo   <- ZIO.service[ConnectionEventRepo]
        appRepo    <- ZIO.service[AppRepo]
        now        <- ZIO.serviceWithZIO[Clock](_.instant)
        appId      <- appRepo.create("Streamy", "test-streamy", None, None)
        _          <- appRepo.setHosts(appId, List(Hostname.unsafe("streamy-test.example")))
        _          <- deviceRepo.upsertUnknown(netgearMac, "device-112233", None, now, two.hhA)
        _          <- connRepo.insertBatch(
          List(
            event(
              two.routerIdA,
              netgearMac,
              fqdn("a.streamy-test.example"),
              false,
              now.minusSeconds(50),
            ),
            event(
              two.routerIdA,
              netgearMac,
              fqdn("b.streamy-test.example"),
              false,
              now.minusSeconds(40),
            ),
            event(
              two.routerIdA,
              netgearMac,
              fqdn("b.streamy-test.example"),
              false,
              now.minusSeconds(30),
            ),
            event(two.routerIdA, netgearMac, fqdn("other.example.net"), true, now.minusSeconds(20)),
          ),
        )
        rb         <- buildRoutes
        (routes, auth) = rb
        token <- auth.login(two.adminA, two.password).map(_.token.value)
        out   <- get(routes, token, netgearMac).flatMap(decode)
        app = out.apps.find(_.slug == "test-streamy")
      } yield assertTrue(app.exists(_.hosts == 2)) &&
        assertTrue(app.exists(_.blocked == 3L)) &&
        assertTrue(
          out.destinations
            .filter(_.host != fqdn("other.example.net"))
            .forall(_.app.exists(_.slug == "test-streamy")),
        ) &&
        assertTrue(
          out.destinations.find(_.host == fqdn("other.example.net")).exists(_.app.isEmpty),
        ) &&
        // 3 of 4 attempts → 75%, above the guess threshold
        assertTrue(out.guess.exists(g => g.appSlug == "test-streamy" && g.sharePct == 75))
    },
    test("a locally-administered (randomized) MAC shows a private address, not a vendor") {
      for {
        _          <- cleanDb
        two        <- TestLayers.seedTwoHouseholds(managedA, managedB)
        deviceRepo <- ZIO.service[DeviceRepo]
        now        <- ZIO.serviceWithZIO[Clock](_.instant)
        _          <- deviceRepo.upsertUnknown(privateMac, "device-aabb01", None, now, two.hhA)
        rb         <- buildRoutes
        (routes, auth) = rb
        token <- auth.login(two.adminA, two.password).map(_.token.value)
        resp  <- get(routes, token, privateMac)
        out   <- decode(resp)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(out.vendor.kind == "private") &&
        assertTrue(out.vendor.name.isEmpty) &&
        // genuinely empty, not an error: a device with no traffic yet
        assertTrue(out.destinations.isEmpty) &&
        assertTrue(out.hostCount == 0)
    },
    test("a MAC that only exists in another household is a 404") {
      for {
        _          <- cleanDb
        two        <- TestLayers.seedTwoHouseholds(managedA, managedB)
        deviceRepo <- ZIO.service[DeviceRepo]
        connRepo   <- ZIO.service[ConnectionEventRepo]
        now        <- ZIO.serviceWithZIO[Clock](_.instant)
        _          <- deviceRepo.upsertUnknown(netgearMac, "B-only", None, now, two.hhB)
        _          <- connRepo.insertBatch(
          List(event(two.routerIdB, netgearMac, fqdn("b.example.com"), false, now.minusSeconds(10))),
        )
        rb         <- buildRoutes
        (routes, auth) = rb
        tokenA <- auth.login(two.adminA, two.password).map(_.token.value)
        tokenB <- auth.login(s"${two.slugB}/${two.adminB}", two.password).map(_.token.value)
        respA  <- get(routes, tokenA, netgearMac)
        // liveness anchor: the owning household CAN read it, so the 404 is the scope, not a broken route
        respB  <- get(routes, tokenB, netgearMac)
        outB   <- decode(respB)
      } yield assertTrue(respA.status == Status.NotFound) &&
        assertTrue(respB.status == Status.Ok) &&
        assertTrue(outB.destinations.map(_.host) == List(fqdn("b.example.com")))
    },
    test("the same MAC in another household contributes nothing to this household's counts") {
      for {
        _          <- cleanDb
        two        <- TestLayers.seedTwoHouseholds(managedA, managedB)
        deviceRepo <- ZIO.service[DeviceRepo]
        connRepo   <- ZIO.service[ConnectionEventRepo]
        now        <- ZIO.serviceWithZIO[Clock](_.instant)
        _          <- deviceRepo.upsertUnknown(netgearMac, "in-A", None, now, two.hhA)
        _          <- deviceRepo.upsertUnknown(netgearMac, "in-B", None, now, two.hhB)
        _          <- connRepo.insertBatch(
          List(
            event(
              two.routerIdA,
              netgearMac,
              fqdn("a-only.example.com"),
              true,
              now.minusSeconds(30),
            ),
            event(
              two.routerIdB,
              netgearMac,
              fqdn("b-only.example.com"),
              false,
              now.minusSeconds(20),
            ),
          ),
        )
        rb         <- buildRoutes
        (routes, auth) = rb
        token <- auth.login(two.adminA, two.password).map(_.token.value)
        out   <- get(routes, token, netgearMac).flatMap(decode)
      } yield
      // A's own row is present (liveness) and B's is absent
      assertTrue(out.destinations.map(_.host) == List(fqdn("a-only.example.com"))) &&
        assertTrue(out.totalBlocked == 0L) &&
        assertTrue(out.name == "in-A")
    },
    test("reading the view changes no policy state") {
      for {
        _          <- cleanDb
        two        <- TestLayers.seedTwoHouseholds(managedA, managedB)
        deviceRepo <- ZIO.service[DeviceRepo]
        connRepo   <- ZIO.service[ConnectionEventRepo]
        alertRepo  <- ZIO.service[AlertRepo]
        xa         <- ZIO.service[Transactor[Task]]
        now        <- ZIO.serviceWithZIO[Clock](_.instant)
        _          <- deviceRepo.upsertUnknown(netgearMac, "Roku-Living-Room", None, now, two.hhA)
        _          <- alertRepo.raiseNewDevice(netgearMac, now, two.hhA)
        _          <- connRepo.insertBatch(
          List(event(two.routerIdA, netgearMac, fqdn("api.roku.com"), false, now.minusSeconds(10))),
        )
        before     <- ZIO.foreach(policyTables)(digest(xa, _))
        rb         <- buildRoutes
        (routes, auth) = rb
        token  <- auth.login(two.adminA, two.password).map(_.token.value)
        resp   <- get(routes, token, netgearMac)
        out    <- decode(resp)
        after  <- ZIO.foreach(policyTables)(digest(xa, _))
        device <- deviceRepo.findByMacInHousehold(netgearMac, two.hhA)
      } yield
      // liveness: the read really happened and saw the blocked attempt
      assertTrue(resp.status == Status.Ok) &&
        assertTrue(out.totalBlocked == 1L) &&
        assertTrue(before.forall(_.nonEmpty)) &&
        assertTrue(before == after) &&
        assertTrue(device.exists(_.profileId.isEmpty))
    },
  ) @@ TestAspect.sequential
}
