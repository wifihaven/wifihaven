package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.notify.{EscalationNotice, Notifier}
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.Clock.TestClock
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*

import java.time.Instant

/**
 * Alerts API — covers both kinds against the unified endpoints. new_device cases mirror the old
 * DeviceAlertApiSpec; access_request cases exercise the #960 surface (public create + per-kind
 * approve side-effects).
 */
object AlertApiSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)

  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val noopNotifier: Notifier = new Notifier {
    def alertCreated(a: Alert): UIO[Unit]                                          = ZIO.unit
    def betaInvite(
        email: String,
        slug: String,
        inviteUrl: String,
        ttlHours: Int,
    ): UIO[Unit] = ZIO.unit
    def betaFlipNotice(
        householdId: wifihaven.shared.types.HouseholdId,
        slug: String,
        window: String,
        flipDate: java.time.Instant,
        daysUntilFlip: Int,
    ): UIO[Unit] = ZIO.unit
    def passwordReset(email: String, resetUrl: String, ttlMinutes: Int): UIO[Unit] = ZIO.unit
    // #2437: this suite drives no escalation path; the notice is a no-op here.
    def escalation(notice: EscalationNotice): UIO[Unit]                            = ZIO.unit
  }

  private val mac1       = MacAddress.unsafe("aa:bb:cc:11:22:33")
  private val mac2       = MacAddress.unsafe("aa:bb:cc:44:55:66")
  private val noteworthy = Hostname.unsafe("example.com")
  private val now        = Instant.parse("2026-05-07T14:00:00Z")

  private case class Env(
      routes: Routes[Any, Response],
      auth: AuthService,
      alertRepo: AlertRepo,
      pRepo: ProfileRepo,
      extRepo: TimeExtensionRepo,
      appRepo: AppRepo,
      kidsPid: ProfileId,
  )

  private def setupWithKid =
    for {
      _         <- cleanDb
      alertRepo <- ZIO.service[AlertRepo]
      dRepo     <- ZIO.service[DeviceRepo]
      pRepo     <- ZIO.service[ProfileRepo]
      extRepo   <- ZIO.service[TimeExtensionRepo]
      appRepo   <- ZIO.service[AppRepo]
      hsRepo    <- ZIO.service[HouseholdSettingsRepo]
      upRepo    <- ZIO.service[UserProfileRepo]
      auth      <- makeAuth
      clock     <- ZIO.service[Clock]
      _         <- hsRepo.ensureDefault(java.time.ZoneId.of("UTC"))
      kidsPid   <- TestLayers.seedKidsProfile(pRepo)
      _         <- dRepo.upsert(mac1, "kid-laptop", Some(kidsPid), "")
      routes = AlertRoutes.routes(
        auth,
        alertRepo,
        dRepo,
        pRepo,
        extRepo,
        appRepo,
        hsRepo,
        upRepo,
        noopNotifier,
        clock,
        RateLimiter.allowAll,
        BlockPageHousehold.defaultOnly,
      )
    } yield Env(routes, auth, alertRepo, pRepo, extRepo, appRepo, kidsPid)

  private def adminToken(auth: AuthService) =
    auth.login("admin", "changeme").map(_.token)

  private def getAlerts(routes: Routes[Any, Response], token: JwtToken, path: String) =
    routes.runZIO(
      Request
        .get(URL.decode(path).toOption.get)
        .addHeader(Header.Authorization.Bearer(token.value)),
    )

  private def postAlertAction(
      routes: Routes[Any, Response],
      path: String,
      token: JwtToken,
      body: String = "",
  ) =
    routes.runZIO(
      Request
        .post(URL.decode(path).toOption.get, Body.fromString(body))
        .addHeader(Header.Authorization.Bearer(token.value)),
    )

  private def postCreateAR(routes: Routes[Any, Response], body: CreateAccessRequest) =
    routes.runZIO(
      Request
        .post(URL.decode("/api/access-requests").toOption.get, Body.fromString(body.toJson))
        .addHeader(Header.ContentType(MediaType.application.json)),
    )

  def spec = suite("Alerts API (#711 + #960 unified)")(
    // ── new_device kind ─────────────────────────────────────────────────
    test("GET /api/alerts returns only pending rows by default") {
      for {
        env   <- setupWithKid
        dRepo <- ZIO.service[DeviceRepo]
        _     <- dRepo.upsertUnknown(mac2, "device-445566", None, now)
        _     <- env.alertRepo.raiseNewDevice(mac1, now)
        _     <- env.alertRepo.raiseNewDevice(mac2, now)
        all   <- env.alertRepo.list(includeAll = false)
        _     <- env.alertRepo.decide(all.head.id, AlertStatus.Approved, now, "admin", None)
        token <- adminToken(env.auth)
        resp  <- getAlerts(env.routes, token, "/api/alerts")
        body  <- resp.body.asString
        rows  <- ZIO.fromEither(body.fromJson[List[Alert]])
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(rows.size == 1) &&
        assertTrue(rows.forall(_.status == AlertStatus.Pending))
    },
    test("raiseNewDevice is idempotent on (mac, kind=new_device)") {
      for {
        env <- setupWithKid
        _   <- env.alertRepo.raiseNewDevice(mac1, now)
        _   <- env.alertRepo.raiseNewDevice(mac1, now.plusSeconds(60))
        xs  <- env.alertRepo.list(includeAll = true)
      } yield assertTrue(xs.size == 1) &&
        assertTrue(xs.head.kind == AlertKind.NewDevice)
    },
    test("approve on a new_device alert records the decision without side effects") {
      for {
        env       <- setupWithKid
        _         <- env.alertRepo.raiseNewDevice(mac1, now)
        all       <- env.alertRepo.list(includeAll = false)
        token     <- adminToken(env.auth)
        resp      <- postAlertAction(
          env.routes,
          s"/api/alerts/${all.head.id.value}/approve",
          token,
          "{}",
        )
        // Profile state unchanged — new_device approval has no side effect.
        p         <- env.pRepo.findById(env.kidsPid).map(_.get)
        // And no exemption app got created either.
        exemptApp <- env.appRepo.findBySlug(noteworthy.value)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(!p.paused) &&
        assertTrue(exemptApp.isEmpty)
    },

    // ── access_request kind ────────────────────────────────────────────
    test("POST /api/access-requests creates a pending row without auth") {
      for {
        env  <- setupWithKid
        resp <- postCreateAR(
          env.routes,
          CreateAccessRequest(mac1, noteworthy, AccessRequestKind.Exemption, Some("for school")),
        )
        body <- resp.body.asString
        // #2566: the unauthenticated caller gets the narrow receipt, not the stored row.
        a    <- ZIO.fromEither(body.fromJson[AccessRequestReceipt])
        // …but the row itself still carries the full denormalised detail for the admin surface.
        // Read it back through the repo rather than the response, which is the point of #2566:
        // `profileId` / `note` / `deviceName` are STORED, and simply not disclosed here.
        row  <- env.alertRepo.findById(a.id).map(_.get)
      } yield assertTrue(resp.status == Status.Created) &&
        assertTrue(a.status == AlertStatus.Pending) &&
        assertTrue(a.kind == AccessRequestKind.Exemption) &&
        assertTrue(a.host == noteworthy) &&
        // The response body carries none of the household detail.
        assertTrue(!body.contains("profileName")) &&
        assertTrue(!body.contains("deviceName")) &&
        assertTrue(!body.contains("for school")) &&
        assertTrue(row.kind == AlertKind.AccessRequest) &&
        assertTrue(row.requestKind.contains(AccessRequestKind.Exemption)) &&
        assertTrue(row.host.contains(noteworthy)) &&
        assertTrue(row.profileId.contains(env.kidsPid)) &&
        assertTrue(row.note.contains("for school"))
    },
    test("repeat POST within the debounce window returns the existing row") {
      for {
        env <- setupWithKid
        r1  <- postCreateAR(
          env.routes,
          CreateAccessRequest(mac1, noteworthy, AccessRequestKind.Exemption, None),
        )
        b1  <- r1.body.asString
        a1  <- ZIO.fromEither(b1.fromJson[AccessRequestReceipt])
        r2  <- postCreateAR(
          env.routes,
          CreateAccessRequest(mac1, noteworthy, AccessRequestKind.Exemption, None),
        )
        b2  <- r2.body.asString
        a2  <- ZIO.fromEither(b2.fromJson[AccessRequestReceipt])
      } yield assertTrue(r1.status == Status.Created) &&
        assertTrue(r2.status == Status.Ok) &&
        assertTrue(a1.id == a2.id)
    },
    test("approve on an exemption upserts an Allowed app assignment for the host") {
      // Post-#1054: exemption is an App(slug=host) + assignment(profile, Allowed).
      for {
        env         <- setupWithKid
        token       <- adminToken(env.auth)
        createResp  <- postCreateAR(
          env.routes,
          CreateAccessRequest(mac1, noteworthy, AccessRequestKind.Exemption, None),
        )
        createBody  <- createResp.body.asString
        a           <- ZIO.fromEither(createBody.fromJson[AccessRequestReceipt])
        approveResp <- postAlertAction(
          env.routes,
          s"/api/alerts/${a.id.value}/approve",
          token,
          "{}",
        )
        app         <- env.appRepo.findBySlug(noteworthy.value).map(_.get)
        assignments <- env.appRepo.listAssignmentsForApp(app.id)
        kidsAssignment = assignments.find(_.profileId == env.kidsPid).get
      } yield assertTrue(approveResp.status == Status.Ok) &&
        assertTrue(kidsAssignment.mode == AppMode.Allowed)
    },
    test("approve on an extension creates a TimeExtension for the profile") {
      for {
        env         <- setupWithKid
        token       <- adminToken(env.auth)
        createResp  <- postCreateAR(
          env.routes,
          CreateAccessRequest(mac1, noteworthy, AccessRequestKind.Extension, None),
        )
        createBody  <- createResp.body.asString
        a           <- ZIO.fromEither(createBody.fromJson[AccessRequestReceipt])
        approveResp <- postAlertAction(
          env.routes,
          s"/api/alerts/${a.id.value}/approve",
          token,
          ApproveAlertRequest(Some(45)).toJson,
        )
        // Bucket the read against the same household-local date the route
        // used when writing — the TestClock instant ≠ wall-clock today.
        clock       <- ZIO.service[Clock]
        nowT        <- clock.instant
        today = java.time.LocalDate.ofInstant(nowT, java.time.ZoneOffset.UTC)
        total <- env.extRepo.getProfileTotalExtension(env.kidsPid, today)
      } yield assertTrue(approveResp.status == Status.Ok) &&
        assertTrue(total == 45)
    },
    test("approve on an unpause clears profile.paused") {
      for {
        env          <- setupWithKid
        token        <- adminToken(env.auth)
        _            <- env.pRepo.setPaused(env.kidsPid, true)
        createResp   <- postCreateAR(
          env.routes,
          CreateAccessRequest(mac1, noteworthy, AccessRequestKind.Unpause, None),
        )
        createBody   <- createResp.body.asString
        a            <- ZIO.fromEither(createBody.fromJson[AccessRequestReceipt])
        approveResp  <- postAlertAction(
          env.routes,
          s"/api/alerts/${a.id.value}/approve",
          token,
          "{}",
        )
        profileAfter <- env.pRepo.findById(env.kidsPid).map(_.get)
      } yield assertTrue(approveResp.status == Status.Ok) &&
        assertTrue(!profileAfter.paused)
    },
    test("deny marks the row decided without applying any grant") {
      for {
        env        <- setupWithKid
        token      <- adminToken(env.auth)
        createResp <- postCreateAR(
          env.routes,
          CreateAccessRequest(mac1, noteworthy, AccessRequestKind.Exemption, None),
        )
        createBody <- createResp.body.asString
        a          <- ZIO.fromEither(createBody.fromJson[AccessRequestReceipt])
        denyResp   <- postAlertAction(env.routes, s"/api/alerts/${a.id.value}/deny", token)
        denyBody   <- denyResp.body.asString
        denied     <- ZIO.fromEither(denyBody.fromJson[Alert])
        // No exemption app got created by the deny path.
        exemptApp  <- env.appRepo.findBySlug(noteworthy.value)
      } yield assertTrue(denyResp.status == Status.Ok) &&
        assertTrue(denied.status == AlertStatus.Denied) &&
        assertTrue(exemptApp.isEmpty)
    },
    test("approve on an already-decided row is a conflict") {
      for {
        env        <- setupWithKid
        token      <- adminToken(env.auth)
        createResp <- postCreateAR(
          env.routes,
          CreateAccessRequest(mac1, noteworthy, AccessRequestKind.Exemption, None),
        )
        createBody <- createResp.body.asString
        a          <- ZIO.fromEither(createBody.fromJson[AccessRequestReceipt])
        _          <- postAlertAction(env.routes, s"/api/alerts/${a.id.value}/approve", token, "{}")
        second     <- postAlertAction(env.routes, s"/api/alerts/${a.id.value}/approve", token, "{}")
      } yield assertTrue(second.status == Status.Conflict)
    },
  ) @@ TestAspect.sequential
}
