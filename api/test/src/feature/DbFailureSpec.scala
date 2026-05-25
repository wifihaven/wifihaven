package wifihaven.api.feature

import wifihaven.api.db.*
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.json.*
import zio.test.*

import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/**
 * Covers #310: API must return 503 (not bare 500) when the DB throws, so the OpenWRT agent can
 * distinguish transient failures from genuine errors. See docs/resilience.md §3 and
 * api/src/routes/ErrorMapper.scala.
 */
object DbFailureSpec extends ZIOSpecDefault {

  private val secretMsg =
    "ERROR: relation \"secret_users_table\" does not exist at line 42"

  /**
   * RouterAuth stub: always fails with the given throwable. Simulates a DB outage at the auth step.
   */
  private def failingAuth(t: Throwable): RouterAuth =
    new RouterAuth {
      def authenticate(req: Request): IO[Response, Router] =
        ZIO.fail(t).mapError(ErrorMapper.dbErrorToResponse)
    }

  /** RouterAuth stub: succeeds with a synthetic Router; never touches the DB. */
  private def okAuth(routerId: RouterId): RouterAuth =
    new RouterAuth {
      def authenticate(req: Request): IO[Response, Router] =
        ZIO.succeed(
          Router(
            id = routerId,
            name = "test",
            enrollmentTokenHash = Some(Sha256Hex.unsafe("a" * 64)),
            tokenHash = Some(Sha256Hex.unsafe("b" * 64)),
            lastSeenAt = None,
            lastEtag = None,
            createdAt = "2026-05-13T00:00:00Z",
          ),
        )
    }

  /**
   * Stub repos: every method throws SQLException. Defeats the type system but matches the failure
   * mode under test — calling code wraps these via `.mapError(ErrorMapper.dbErrorToResponse)`.
   */
  private def throwing[A]: Task[A] = ZIO.fail(new SQLException(secretMsg))

  private def brokenRouterRepo: RouterRepo = new RouterRepo {
    def listAll                                        = throwing
    def findById(id: RouterId)                         = throwing
    def findByEnrollmentTokenHash(h: Sha256Hex)        = throwing
    def findByTokenHash(h: Sha256Hex)                  = throwing
    def create(n: String, h: Sha256Hex)                = throwing
    def completeEnrollment(id: RouterId, h: Sha256Hex) = throwing
    def touch(id: RouterId, etag: Option[ETag])        = throwing
    def delete(id: RouterId)                           = throwing
  }

  private def brokenTrafficRepo: TrafficReportRepo = new TrafficReportRepo {
    def insertBatch(rs: List[TrafficReportInsert])                       = throwing
    def listForDevice(mac: MacAddress, d: java.time.LocalDate)           = throwing
    def listForRouter(r: RouterId, l: Int)                               = throwing
    def listTrafficRollupRows(f: TrafficRollupFilter)                    = throwing
    def listPresenceRows(macs: List[MacAddress], d: java.time.LocalDate) = throwing
    def listPresenceRows(
        macs: List[MacAddress],
        from: java.time.LocalDate,
        to: java.time.LocalDate,
    ) = throwing
    def listRawInRange(
        macs: List[MacAddress],
        fromInstant: java.time.Instant,
        toInstant: java.time.Instant,
        cursor: Option[wifihaven.api.usage.RawTrafficCursorKey] = None,
        limit: Option[Int] = None,
    ) = throwing
    def earliestPeriodStart                                              = throwing
    def listFqdnHostAggregatesForDevice(
        mac: MacAddress,
        fromInstant: java.time.Instant,
        toInstant: java.time.Instant,
    ) = throwing
  }

  private def brokenTimeUsageRepo: TimeUsageRepo = new TimeUsageRepo {
    def incrementSecondsAndBytes(
        mac: MacAddress,
        host: HostId,
        date: java.time.LocalDate,
        s: Long,
        bi: Long,
        bo: Long,
        proportional: Long,
    ) = throwing
    def getSecondsUsed(mac: MacAddress, host: HostId, date: java.time.LocalDate)         = throwing
    def getSecondsAndBytes(mac: MacAddress, host: HostId, date: java.time.LocalDate)     = throwing
    def getProportionalSeconds(mac: MacAddress, host: HostId, date: java.time.LocalDate) = throwing
    def listForDevice(mac: MacAddress, date: java.time.LocalDate)                        = throwing
    def listForDeviceMacs(macs: List[MacAddress], date: java.time.LocalDate)             = throwing
    def snapshotAll(date: java.time.LocalDate)                                           = throwing
  }

  private def brokenDeviceRepo: DeviceRepo = new DeviceRepo {
    def listAll                                                                          = throwing
    def findByMac(mac: MacAddress)                                                       = throwing
    def upsert(mac: MacAddress, name: String, pid: Option[ProfileId], ip: String)        = throwing
    def updateLastSeen(mac: MacAddress, ip: String)                                      = throwing
    def touchLastSeen(mac: MacAddress, ip: Option[IpAddress], at: Instant)               = throwing
    def upsertUnknown(mac: MacAddress, name: String, ip: Option[IpAddress], at: Instant) = throwing
    def renameIfAutoGenerated(mac: MacAddress, newName: String)                          = throwing
    def updateProfile(mac: MacAddress, pid: ProfileId)                                   = throwing
    def delete(mac: MacAddress)                                                          = throwing
  }

  private def brokenDeviceAlertRepo: DeviceAlertRepo = new DeviceAlertRepo {
    def raise(mac: MacAddress, firstSeenAt: Instant) = throwing
    def listAll(includeDismissed: Boolean)           = throwing
    def dismiss(id: DeviceAlertId, at: Instant)      = throwing
  }

  private def brokenHouseholdSettingsRepo: HouseholdSettingsRepo = new HouseholdSettingsRepo {
    def get                                = throwing
    def update(s: HouseholdSettings)       = throwing
    def ensureDefault(z: java.time.ZoneId) = throwing
  }

  private def brokenConnectionEventRepo: ConnectionEventRepo = new ConnectionEventRepo {
    def insertBatch(es: List[ConnectionEventInsert])                                    = throwing
    def recent(l: Int)                                                                  = throwing
    def listForMac(mac: MacAddress, l: Int)                                             = throwing
    def listForRouter(r: RouterId, l: Int)                                              = throwing
    def query(f: LogFilter)                                                             = throwing
    def querySeries(f: LogFilter, bucketSeconds: Int, groupBy: Set[String])             = throwing
    def stats                                                                           = throwing
    def topBlocked(h: Int, l: Int)                                                      = throwing
    def lastSeenByMacSince(since: Instant)                                              = throwing
    def findRecentFqdnFor(r: RouterId, ip: IpAddress, since: Instant)                   = throwing
    def backfillResolvedFor(r: RouterId, ip: IpAddress, fqdn: Hostname, since: Instant) =
      throwing
  }

  private def jsonField(body: String, key: String): Option[String] =
    body.fromJson[Json].toOption.flatMap(_.asObject).flatMap(_.get(key)).flatMap(_.asString)

  def spec = suite("DB failure → 503 (#310)")(
    test("ErrorMapper returns 503 + JSON + Retry-After + class-only body") {
      val resp = ErrorMapper.dbErrorToResponse(new SQLException(secretMsg))
      for {
        body <- resp.body.asString
      } yield assertTrue(resp.status == Status.ServiceUnavailable) &&
        assertTrue(jsonField(body, "status").contains("error")) &&
        assertTrue(jsonField(body, "db").contains("SQLException")) &&
        assertTrue(
          resp.headers
            .get("Retry-After")
            .contains("30"),
        )
    },
    test("ErrorMapper does not leak SQL details (no table name, no line number)") {
      val resp = ErrorMapper.dbErrorToResponse(new SQLException(secretMsg))
      for {
        body <- resp.body.asString
      } yield assertTrue(!body.contains("secret_users_table")) &&
        assertTrue(!body.contains("line 42")) &&
        assertTrue(!body.contains("relation"))
    },
    test("router endpoint returns 503 + Retry-After when auth step's DB lookup throws") {
      val auth   = failingAuth(new SQLException(secretMsg))
      val routes = RouterIngestRoutes.routes(
        auth,
        brokenRouterRepo,
        brokenTrafficRepo,
        brokenTimeUsageRepo,
        brokenDeviceRepo,
        brokenConnectionEventRepo,
        brokenDeviceAlertRepo,
        brokenHouseholdSettingsRepo,
      )
      val req    = Request
        .post(URL.decode("/api/router/usage").toOption.get, Body.fromString("{}"))
        .addHeader(Header.Authorization.Bearer("any"))
      for {
        resp <- routes.runZIO(req)
        body <- resp.body.asString
      } yield assertTrue(resp.status == Status.ServiceUnavailable) &&
        assertTrue(jsonField(body, "status").contains("error")) &&
        assertTrue(jsonField(body, "db").contains("SQLException")) &&
        assertTrue(resp.headers.get("Retry-After").contains("30")) &&
        assertTrue(!body.contains("secret_users_table"))
    },
    test("router endpoint returns 400 (not 503) on malformed body even when repos would throw") {
      // Auth succeeds; body is junk → must short-circuit to 400 before any DB call.
      val auth   = okAuth(RouterId(UUID.randomUUID()))
      val routes = RouterIngestRoutes.routes(
        auth,
        brokenRouterRepo,
        brokenTrafficRepo,
        brokenTimeUsageRepo,
        brokenDeviceRepo,
        brokenConnectionEventRepo,
        brokenDeviceAlertRepo,
        brokenHouseholdSettingsRepo,
      )
      val req    = Request
        .post(URL.decode("/api/router/usage").toOption.get, Body.fromString("not json"))
        .addHeader(Header.Authorization.Bearer("any"))
      for {
        resp <- routes.runZIO(req)
      } yield assertTrue(resp.status == Status.BadRequest)
    },
    test("router endpoint returns 401 (not 503) when bearer token missing") {
      // RouterAuthLive checks the bearer header before touching the repo, so missing-auth must 401
      // regardless of repo health.
      val auth   = new RouterAuthLive(brokenRouterRepo)
      val routes = RouterIngestRoutes.routes(
        auth,
        brokenRouterRepo,
        brokenTrafficRepo,
        brokenTimeUsageRepo,
        brokenDeviceRepo,
        brokenConnectionEventRepo,
        brokenDeviceAlertRepo,
        brokenHouseholdSettingsRepo,
      )
      val req    = Request.post(URL.decode("/api/router/usage").toOption.get, Body.fromString("{}"))
      for {
        resp <- routes.runZIO(req)
      } yield assertTrue(resp.status == Status.Unauthorized)
    },
  )
}
