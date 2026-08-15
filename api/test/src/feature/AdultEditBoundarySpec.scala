package wifihaven.api.feature

import wifihaven.api.{JwtConfig, StripeConfig}
import wifihaven.api.auth.*
import wifihaven.api.billing.*
import wifihaven.api.db.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.api.policy.{PolicyService, PolicyServiceLive}
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import doobie.*
import doobie.implicits.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.interop.catz.*
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*

/**
 * #2522 — **adult is the editing role**. Paired with #2512 (a household has exactly ONE admin),
 * which is only tolerable if the second parent is not effectively read-only.
 *
 * The principle: the admin owns the **account** (who exists, who pays, what hardware is enrolled,
 * and the off-switch); adults do the **parenting** (profiles, schedules, blocklists, apps,
 * settings).
 *
 * Two halves, and the NEGATIVE one is load-bearing: a blanket `requireAdmin` → `requireWriter`
 * sweep that overshot would sail through a positive-only suite. So every admin-only CLASS gets its
 * own explicit assertion here — account lifecycle (`/api/users`), billing, `admin/routers`, the
 * `/api/household/enforcement` kill-switch (#2382), and the `/api/admin` prefix — not a spot check.
 *
 * A third half pins that `child` is untouched: `requireWriter` admits admin+adult only, so every
 * flipped route still refuses a child exactly as `requireAdmin` did.
 *
 * Household discipline (#2512/#2526): the fixture household is minted FRESH via
 * `HouseholdRepo.create` (which seeds its settings + billing + global-sentinel rows in one
 * transaction), never household 1 — which owns the V1-seeded `admin`, and where V86's
 * `uq_users_household_single_admin` would reject a second admin outright.
 *
 * Full stack on embedded Postgres, no repo mocks (only the external StripeClient is the noop stub).
 */
object AdultEditBoundarySpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate
  private val jwtCfg  = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)

  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      hr    <- ZIO.service[HouseholdRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock, hr): AuthService

  private val Password = "parenting123"
  private val Slug     = "house-p"

  /** The three tokens the whole suite runs against, plus the household they live in. */
  private final case class Fx(
      hh: HouseholdId,
      admin: String,
      adult: String,
      child: String,
      adultId: UserId,
      childId: UserId,
  )

  /**
   * `users.must_change_password` is `true` on every `UserRepo.create` (invite-accept sets it false
   * on first login), and `requireAuth` refuses a token minted for such a user — so the fixture
   * inserts the rows directly with the flag already cleared, the same shape
   * `OneAdminPerHouseholdSpec` uses.
   */
  private def insertUser(
      xa: Transactor[Task],
      hh: HouseholdId,
      username: String,
      role: String,
      hash: String,
  ): Task[UserId] =
    sql"""INSERT INTO users(username, password_hash, role, must_change_password, household_id)
          VALUES ($username, $hash, $role, false, $hh) RETURNING id"""
      .query[UserId]
      .unique
      .transact(xa)

  private def fixture =
    for {
      xa      <- ZIO.service[Transactor[Task]]
      hr      <- ZIO.service[HouseholdRepo]
      auth    <- makeAuth
      // `create` is the #2355 SSOT seam: households + household_billing + household_settings +
      // the #2286 global-sentinel profile, all in one transaction. Several routes under test read
      // those rows, so minting the household any other way would fail for reasons unrelated to role.
      hh      <- hr.create("House Parenting", Slug)
      hash    <- auth.hashPassword(Password)
      _       <- insertUser(xa, hh, "boss", "admin", hash)
      adultId <- insertUser(xa, hh, "parent2", "adult", hash)
      childId <- insertUser(xa, hh, "kiddo", "child", hash)
      aTok    <- auth.login(s"$Slug/boss", Password).map(_.token.value)
      dTok    <- auth.login(s"$Slug/parent2", Password).map(_.token.value)
      cTok    <- auth.login(s"$Slug/kiddo", Password).map(_.token.value)
    } yield Fx(hh, aTok, dTok, cTok, adultId, childId)

  // ── request helpers ──────────────────────────────────────────────────────

  private def send(
      routes: Routes[Any, Response],
      method: Method,
      path: String,
      token: String,
      body: Option[String] = None,
  ): Task[Response] = {
    val base = Request(
      method = method,
      url = URL.decode(path).toOption.get,
      body = body.fold(Body.empty)(Body.fromString(_)),
    ).addHeader(Header.Authorization.Bearer(token))
    routes.runZIO(
      body.fold(base)(_ => base.addHeader(Header.ContentType(MediaType.application.json))),
    )
  }

  private def statusOf(
      routes: Routes[Any, Response],
      method: Method,
      path: String,
      token: String,
      body: Option[String] = None,
  ): Task[Status] = send(routes, method, path, token, body).map(_.status)

  // ── route-object wiring ──────────────────────────────────────────────────

  private def authRoutes =
    for {
      ur     <- ZIO.service[UserRepo]
      upRepo <- ZIO.service[UserProfileRepo]
      auth   <- makeAuth
    } yield AuthRoutes.routes(auth, ur, upRepo, RateLimiter.allowAll)

  private def profileRoutes =
    for {
      pr   <- ZIO.service[ProfileRepo]
      tlr  <- ZIO.service[TimeLimitRepo]
      up   <- ZIO.service[UserProfileRepo]
      ur   <- ZIO.service[UserRepo]
      nsr  <- ZIO.service[NamedScheduleRepo]
      auth <- makeAuth
    } yield ProfileRoutes.routes(auth, pr, tlr, up, ur, nsr)

  private def scheduleRoutes =
    for {
      nsr  <- ZIO.service[NamedScheduleRepo]
      auth <- makeAuth
    } yield ScheduleRoutes.routes(auth, nsr)

  private def blocklistRoutes =
    for {
      blRepo <- ZIO.service[BlocklistRepo]
      auth   <- makeAuth
      cache  <- Ref
        .make(Map.empty[BlocklistId, wifihaven.api.BlocklistCache.Entry])
        .map(new wifihaven.api.BlocklistCache.Live(_))
    } yield BlocklistRoutes.routes(auth, blRepo, cache, NeverFetcher, Map.empty)

  /**
   * The refresh route never gets past its bundled-list lookup in this suite (the `bundled` map is
   * empty), so the fetcher is deliberately one that would fail loudly if it were ever reached — a
   * silent stub could hide the route quietly taking a different path.
   */
  private object NeverFetcher extends wifihaven.api.BlocklistFetcher {
    def fetch(url: String, format: wifihaven.api.BlocklistFormat): Task[List[Hostname]] =
      ZIO.fail(new RuntimeException(s"fetcher must not be reached (url=$url)"))
  }

  private def appRoutes =
    for {
      appRepo <- ZIO.service[AppRepo]
      pr      <- ZIO.service[ProfileRepo]
      up      <- ZIO.service[UserProfileRepo]
      blRepo  <- ZIO.service[BlocklistRepo]
      auth    <- makeAuth
    } yield AppRoutes.routes(auth, appRepo, pr, up, blRepo)

  private def householdSettingsRoutes =
    for {
      hsr  <- ZIO.service[HouseholdSettingsRepo]
      auth <- makeAuth
    } yield HouseholdSettingsRoutes.routes(auth, hsr)

  private def logRoutes =
    for {
      connRepo <- ZIO.service[ConnectionEventRepo]
      upRepo   <- ZIO.service[UserProfileRepo]
      auth     <- makeAuth
    } yield LogRoutes.routes(auth, connRepo, upRepo)

  private def timeRoutes =
    for {
      profileRepo <- ZIO.service[ProfileRepo]
      tlRepo      <- ZIO.service[TimeLimitRepo]
      atlRepo     <- ZIO.service[AppTimeLimitRepo]
      deviceRepo  <- ZIO.service[DeviceRepo]
      trafficRepo <- ZIO.service[TrafficReportRepo]
      extRepo     <- ZIO.service[TimeExtensionRepo]
      upRepo      <- ZIO.service[UserProfileRepo]
      hsRepo      <- ZIO.service[HouseholdSettingsRepo]
      ambientRepo <- ZIO.service[AmbientHostsRepo]
      clock       <- ZIO.service[Clock]
      auth        <- makeAuth
      tss = new wifihaven.api.policy.TimeStatusServiceLive(
        profileRepo,
        tlRepo,
        atlRepo,
        deviceRepo,
        trafficRepo,
        extRepo,
      )
    } yield TimeRoutes.routes(
      auth,
      deviceRepo,
      tlRepo,
      atlRepo,
      trafficRepo,
      extRepo,
      profileRepo,
      upRepo,
      hsRepo,
      tss,
      clock,
      ambientRepo = ambientRepo,
    )

  private def billingRoutes =
    for {
      hbr   <- ZIO.service[HouseholdBillingRepo]
      clock <- ZIO.service[Clock]
      auth  <- makeAuth
    } yield BillingRoutes.routes(
      auth,
      BillingService(StripeClient.noop, hbr, clock, StripeConfig()),
      ZIO.succeed(FlipService.FlipWindow(open = false, flipDate = None)),
    )

  private def adminRouterRoutes =
    for {
      rr   <- ZIO.service[RouterRepo]
      ur   <- ZIO.service[UserRepo]
      en   <- ZIO.service[EntitlementsRepo]
      auth <- makeAuth
    } yield AdminRouterRoutes.routes(auth, rr, ur, en)

  private def adminDebugRoutes =
    for {
      pr     <- ZIO.service[ProfileRepo]
      hsr    <- ZIO.service[HouseholdSettingsRepo]
      tlr    <- ZIO.service[TimeLimitRepo]
      atlr   <- ZIO.service[AppTimeLimitRepo]
      dr     <- ZIO.service[DeviceRepo]
      blr    <- ZIO.service[BlocklistRepo]
      trRepo <- ZIO.service[TrafficReportRepo]
      er     <- ZIO.service[TimeExtensionRepo]
      ar     <- ZIO.service[AppRepo]
      clock  <- ZIO.service[Clock]
      auth   <- makeAuth
      ps = PolicyServiceLive(pr, hsr, tlr, atlr, dr, blr, trRepo, er, ar, clock): PolicyService
    } yield AdminDebugRoutes.routes(auth, ps)

  private def rollupAdminRoutes =
    for {
      repo <- ZIO.service[RollupRepo]
      auth <- makeAuth
    } yield RollupAdminRoutes.routes(auth, repo)

  private def enforcementRoutes =
    for {
      hsr  <- ZIO.service[HouseholdSettingsRepo]
      auth <- makeAuth
    } yield HouseholdEnforcementRoutes.routes(auth, hsr)

  // ── the OPENED half: an adult can now do the parenting ───────────────────

  private val opened = suite("becomes adult-or-admin")(
    test("POST /api/profiles — an adult can create a profile, and it lands in THEIR household") {
      for {
        _   <- cleanDb
        fx  <- fixture
        pr  <- ZIO.service[ProfileRepo]
        rts <- profileRoutes
        body = UpsertProfileRequest("Teens", Nil, paused = false, timeLimit = None).toJson
        resp  <- send(rts, Method.POST, "/api/profiles", fx.adult, Some(body))
        kid   <- statusOf(rts, Method.POST, "/api/profiles", fx.child, Some(body))
        after <- pr.listAllForHousehold(fx.hh)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(after.exists(_.name == "Teens")) &&
        assertTrue(kid == Status.Forbidden)
    },
    test("DELETE /api/profiles/{id} — an adult can delete a profile") {
      for {
        _     <- cleanDb
        fx    <- fixture
        pr    <- ZIO.service[ProfileRepo]
        rts   <- profileRoutes
        pid   <- pr.create("Doomed", Nil, fx.hh)
        resp  <- statusOf(rts, Method.DELETE, s"/api/profiles/${pid.value}", fx.adult)
        after <- pr.findById(pid)
      } yield assertTrue(resp == Status.Ok) && assertTrue(after.isEmpty)
    },
    test("GET /api/profiles/global — an adult can read the household's global sentinel") {
      for {
        _    <- cleanDb
        fx   <- fixture
        rts  <- profileRoutes
        resp <- statusOf(rts, Method.GET, "/api/profiles/global", fx.adult)
        kid  <- statusOf(rts, Method.GET, "/api/profiles/global", fx.child)
      } yield assertTrue(resp == Status.Ok) && assertTrue(kid == Status.Forbidden)
    },
    test("GET + PUT /api/profiles/{id}/users — an adult can assign users to a profile") {
      for {
        _      <- cleanDb
        fx     <- fixture
        pr     <- ZIO.service[ProfileRepo]
        upRepo <- ZIO.service[UserProfileRepo]
        rts    <- profileRoutes
        pid    <- pr.create("Kids", Nil, fx.hh)
        get    <- statusOf(rts, Method.GET, s"/api/profiles/${pid.value}/users", fx.adult)
        put    <- statusOf(
          rts,
          Method.PUT,
          s"/api/profiles/${pid.value}/users",
          fx.adult,
          Some(SetProfileUsersRequest(List(fx.childId)).toJson),
        )
        linked <- upRepo.listUsersForProfile(pid)
      } yield assertTrue(get == Status.Ok) &&
        assertTrue(put == Status.Ok) &&
        assertTrue(linked == List(fx.childId))
    },
    test("PUT /api/users/{id}/profiles — the INVERSE direction lands on the same role") {
      // The two directions of the SAME `user_profiles` write. #2522 flagged the split as incoherent
      // and resolved it toward adult-or-admin: linking an existing user to a profile is parenting,
      // it creates and deletes nothing. Gating one side and not the other would leave an adult able
      // to assign from the profile page but not the user page.
      for {
        _      <- cleanDb
        fx     <- fixture
        pr     <- ZIO.service[ProfileRepo]
        upRepo <- ZIO.service[UserProfileRepo]
        rts    <- authRoutes
        pid    <- pr.create("Kids", Nil, fx.hh)
        resp   <- statusOf(
          rts,
          Method.PUT,
          s"/api/users/${fx.childId.value}/profiles",
          fx.adult,
          Some(SetUserProfilesRequest(List(pid)).toJson),
        )
        kid    <- statusOf(
          rts,
          Method.PUT,
          s"/api/users/${fx.childId.value}/profiles",
          fx.child,
          Some(SetUserProfilesRequest(List(pid)).toJson),
        )
        linked <- upRepo.listProfilesForUser(fx.childId)
      } yield assertTrue(resp == Status.Ok) &&
        assertTrue(linked == List(pid)) &&
        assertTrue(kid == Status.Forbidden)
    },
    test("POST + PATCH + DELETE /api/schedules — an adult can author named schedules") {
      for {
        _      <- cleanDb
        fx     <- fixture
        nsr    <- ZIO.service[NamedScheduleRepo]
        rts    <- scheduleRoutes
        create <- send(
          rts,
          Method.POST,
          "/api/schedules",
          fx.adult,
          Some(CreateNamedScheduleRequest("Bedtime", None, Nil).toJson),
        )
        cBody  <- create.body.asString
        sid = cBody.fromJson[NamedSchedule].toOption.get.id
        patch <- statusOf(
          rts,
          Method.PATCH,
          s"/api/schedules/${sid.value}",
          fx.adult,
          Some(UpdateNamedScheduleRequest("Bedtime v2", None, Nil).toJson),
        )
        mid   <- nsr.findById(sid)
        del   <- statusOf(rts, Method.DELETE, s"/api/schedules/${sid.value}", fx.adult)
        after <- nsr.findById(sid)
        kid   <- statusOf(
          rts,
          Method.POST,
          "/api/schedules",
          fx.child,
          Some(CreateNamedScheduleRequest("Nope", None, Nil).toJson),
        )
      } yield assertTrue(create.status == Status.Ok) &&
        assertTrue(patch == Status.Ok) &&
        assertTrue(mid.map(_.name) == Some("Bedtime v2")) &&
        assertTrue(del == Status.Ok) &&
        assertTrue(after.isEmpty) &&
        assertTrue(kid == Status.Forbidden)
    },
    test("the /api/blocklists READ routes admit an adult; the two mutators are operator-only") {
      // #2535/#2567 narrowed `clear` and `refresh` from `requireWriter` to `requireOperator`: they
      // mutate the install-wide `blocklist_domains` catalog, which carries no household dimension.
      // The adult's 403 is now just the role half of `requireOperator` (it wraps `requireAdmin`);
      // the load-bearing assertion is `asAdmin` — this suite's household is NOT household 1, so
      // its ADMIN is refused too, which is the tenancy half and cannot come from a role check. The
      // READS stay adult-admissible (bundled public data on live SPA surfaces). The paired
      // operator-succeeds side lives in `CatalogOperatorGateSpec`.
      for {
        _       <- cleanDb
        fx      <- fixture
        rts     <- blocklistRoutes
        list    <- statusOf(rts, Method.GET, "/api/blocklists", fx.adult)
        hosts   <- statusOf(rts, Method.GET, "/api/blocklists/ads/hosts", fx.adult)
        clear   <- statusOf(rts, Method.POST, "/api/blocklists/ads/clear", fx.adult)
        refresh <- statusOf(rts, Method.POST, "/api/blocklists/ads/refresh", fx.adult)
        asAdmin <- statusOf(rts, Method.POST, "/api/blocklists/ads/refresh", fx.admin)
        kid     <- statusOf(rts, Method.GET, "/api/blocklists", fx.child)
      } yield assertTrue(list == Status.Ok) &&
        assertTrue(hosts == Status.Ok) &&
        assertTrue(clear == Status.Forbidden) &&
        assertTrue(refresh == Status.Forbidden) &&
        assertTrue(asAdmin == refresh) &&
        assertTrue(kid == Status.Forbidden)
    },
    test("the three /api/apps maintenance routes are operator-only, not adult-or-admin") {
      // #2567: `DELETE /api/apps/:id`, `seed-from-templates` and `reset-to-template` all write the
      // install-wide `apps` / `app_hosts` catalog, so they moved to `requireOperator`. As above,
      // `asAdmin` is the load-bearing half — this suite's admin clears the role check and is still
      // refused, because its household is not household 1. And the app row SURVIVES the refused
      // DELETE, which is the property that actually matters.
      for {
        _        <- cleanDb
        fx       <- fixture
        appRepo  <- ZIO.service[AppRepo]
        rts      <- appRoutes
        aid      <- appRepo.create("Doomed App", "doomed-app", None, None)
        del      <- statusOf(rts, Method.DELETE, s"/api/apps/${aid.value}", fx.adult)
        survived <- appRepo.findById(aid)
        seed     <- statusOf(rts, Method.POST, "/api/apps/seed-from-templates", fx.adult)
        reset    <- statusOf(rts, Method.POST, "/api/apps/999999/reset-to-template", fx.adult)
        asAdmin  <- statusOf(rts, Method.POST, "/api/apps/999999/reset-to-template", fx.admin)
        kid      <- statusOf(rts, Method.POST, "/api/apps/seed-from-templates", fx.child)
      } yield assertTrue(del == Status.Forbidden) &&
        assertTrue(survived.isDefined) &&
        assertTrue(seed == Status.Forbidden) &&
        assertTrue(reset == Status.Forbidden) &&
        assertTrue(asAdmin == reset) &&
        assertTrue(kid == Status.Forbidden)
    },
    test("PUT + PATCH /api/household/settings — an adult can edit household settings") {
      for {
        _       <- cleanDb
        fx      <- fixture
        hsr     <- ZIO.service[HouseholdSettingsRepo]
        rts     <- householdSettingsRoutes
        // Round-trip the current settings through the full-replace PUT: `HouseholdSettings` and
        // `UpdateHouseholdSettingsRequest` share their field names, so the GET body is a valid PUT
        // body — no hand-built fixture to drift from the model.
        current <- send(rts, Method.GET, "/api/household/settings", fx.adult)
        curBody <- current.body.asString
        put     <- statusOf(rts, Method.PUT, "/api/household/settings", fx.adult, Some(curBody))
        patch   <- statusOf(
          rts,
          Method.PATCH,
          "/api/household/settings",
          fx.adult,
          Some("""{"blockEncryptedDns":true}"""),
        )
        after   <- hsr.getForHousehold(fx.hh)
        kid     <- statusOf(
          rts,
          Method.PATCH,
          "/api/household/settings",
          fx.child,
          Some("""{"blockEncryptedDns":false}"""),
        )
      } yield assertTrue(put == Status.Ok) &&
        assertTrue(patch == Status.Ok) &&
        assertTrue(after.blockEncryptedDns) &&
        assertTrue(kid == Status.Forbidden)
    },
    test("GET /api/stats — an adult can read the dashboard stat cards") {
      for {
        _    <- cleanDb
        fx   <- fixture
        rts  <- logRoutes
        resp <- statusOf(rts, Method.GET, "/api/stats", fx.adult)
        kid  <- statusOf(rts, Method.GET, "/api/stats", fx.child)
      } yield assertTrue(resp == Status.Ok) && assertTrue(kid == Status.Forbidden)
    },
    test("GET /api/presence/ambient-hosts — an adult can read the ambient-host learning view") {
      for {
        _    <- cleanDb
        fx   <- fixture
        rts  <- timeRoutes
        resp <- statusOf(rts, Method.GET, "/api/presence/ambient-hosts", fx.adult)
        kid  <- statusOf(rts, Method.GET, "/api/presence/ambient-hosts", fx.child)
      } yield assertTrue(resp == Status.Ok) && assertTrue(kid == Status.Forbidden)
    },
  )

  // ── the ADMIN-ONLY half: the overshoot detector ──────────────────────────

  private val stillAdminOnly = suite("stays admin-only — one assertion per class")(
    test("account lifecycle: all four /api/users verbs refuse an adult") {
      // #2529 (nothing guards at-LEAST-one admin) makes this the sharpest of the five: an adult who
      // could PATCH roles or DELETE users could unseat or strand the household's single admin.
      for {
        _      <- cleanDb
        fx     <- fixture
        ur     <- ZIO.service[UserRepo]
        rts    <- authRoutes
        create <- statusOf(
          rts,
          Method.POST,
          "/api/users",
          fx.adult,
          Some(CreateUserRequest("intruder", "initial123456", UserRole.Adult, Nil).toJson),
        )
        list   <- statusOf(rts, Method.GET, "/api/users", fx.adult)
        patch  <- statusOf(
          rts,
          Method.PATCH,
          s"/api/users/${fx.childId.value}",
          fx.adult,
          Some("""{"role":"adult"}"""),
        )
        del    <- statusOf(rts, Method.DELETE, s"/api/users/${fx.childId.value}", fx.adult)
        after  <- ur.listAllForHousehold(fx.hh)
      } yield assertTrue(create == Status.Forbidden) &&
        assertTrue(list == Status.Forbidden) &&
        assertTrue(patch == Status.Forbidden) &&
        assertTrue(del == Status.Forbidden) &&
        // Refused, not merely reported: no row created, none deleted, the child still a child.
        assertTrue(after.map(_.username).sorted == List("boss", "kiddo", "parent2")) &&
        assertTrue(after.find(_.username == "kiddo").map(_.role) == Some(UserRole.Child))
    },
    test("billing: an adult cannot read billing state or reach Stripe") {
      // Unlike the other four classes this one has no "the write did not land" half, because none of
      // the three verbs writes. `GET /api/billing` is a pure read; `startCheckout`
      // (`BillingService.scala:77`) and `startPortal` (`:113`) only READ `stripeCustomerId`. The
      // sole writer of that column anywhere in `api/src` is `provisionCustomer` (`:47`), whose only
      // caller is the beta-approval path (`BetaService.scala:170`) — no route under test reaches it.
      // A DB assertion here would therefore hold with the role guard deleted, which is worse than
      // none. The admin-succeeds pin below carries the weight instead: it proves the 403s come from
      // the role gate rather than from broken wiring.
      for {
        _        <- cleanDb
        fx       <- fixture
        rts      <- billingRoutes
        get      <- statusOf(rts, Method.GET, "/api/billing", fx.adult)
        checkout <- statusOf(rts, Method.POST, "/api/billing/checkout", fx.adult, Some("{}"))
        portal   <- statusOf(rts, Method.GET, "/api/billing/portal", fx.adult)
        // The admin is NOT refused — proving the 403s above come from the role gate and not from
        // some unrelated failure in the billing wiring.
        adminGet <- statusOf(rts, Method.GET, "/api/billing", fx.admin)
      } yield assertTrue(get == Status.Forbidden) &&
        assertTrue(checkout == Status.Forbidden) &&
        assertTrue(portal == Status.Forbidden) &&
        assertTrue(adminGet == Status.Ok)
    },
    test("hardware: an adult cannot mint or revoke a router enrollment") {
      for {
        _    <- cleanDb
        fx   <- fixture
        rr   <- ZIO.service[RouterRepo]
        rts  <- adminRouterRoutes
        post <- statusOf(
          rts,
          Method.POST,
          "/api/admin/routers",
          fx.adult,
          Some("""{"name":"Sneaky Router"}"""),
        )
        list <- statusOf(rts, Method.GET, "/api/admin/routers", fx.adult)
        del  <- statusOf(rts, Method.DELETE, "/api/admin/routers/1", fx.adult)
        // #2571: `fx.hh`, NOT `HouseholdId.Default` — the fixture household is minted fresh and is
        // never household 1, so a Default-scoped read would make this absence assertion vacuous: a
        // regressed `requireAdmin` would write the router into `fx.hh` and still leave hh-1 empty.
        all  <- rr.listAllForHousehold(fx.hh)
      } yield assertTrue(post == Status.Forbidden) &&
        assertTrue(list == Status.Forbidden) &&
        assertTrue(del == Status.Forbidden) &&
        assertTrue(all.isEmpty)
    },
    test("the kill-switch: an adult cannot disable enforcement for the household") {
      // #2382. The one toggle that turns the product off wholesale — it belongs to whoever owns the
      // account, not to whoever does the parenting.
      for {
        _     <- cleanDb
        fx    <- fixture
        hsr   <- ZIO.service[HouseholdSettingsRepo]
        rts   <- enforcementRoutes
        resp  <- statusOf(
          rts,
          Method.PUT,
          "/api/household/enforcement",
          fx.adult,
          Some("""{"disabled":true}"""),
        )
        after <- hsr.enforcementDisabled(fx.hh)
      } yield assertTrue(resp == Status.Forbidden) && assertTrue(!after)
    },
    test("the /api/admin prefix: an adult reaches none of its three non-router surfaces") {
      // Every route under `/api/admin` this case is responsible for, not a representative one — the
      // whole point of this half is that an overshoot anywhere is caught. The rest of the prefix is
      // covered elsewhere: `/api/admin/routers` has its own case above, and the operator-only
      // free-forever grant sits behind the narrower `requireOperator` (unchanged by #2522).
      //
      // #2567: `reconcile-templates` has since moved from `requireAdmin` to that same
      // `requireOperator` gate (it merges rows in the install-wide `apps` catalog and repoints FK
      // refs across every household), so this suite's admin is refused there too — its household
      // is not household 1. The other two stay plain admin-only.
      for {
        _         <- cleanDb
        fx        <- fixture
        apps      <- appRoutes
        snap      <- adminDebugRoutes
        rollup    <- rollupAdminRoutes
        reconcile <- statusOf(apps, Method.POST, "/api/admin/apps/reconcile-templates", fx.adult)
        snapshot  <- statusOf(snap, Method.GET, "/api/admin/snapshot", fx.adult)
        status    <- statusOf(rollup, Method.GET, "/api/admin/rollup-status", fx.adult)
        // Each paired with the admin's answer, so a 403 caused by broken wiring rather than by the
        // role gate cannot masquerade as coverage.
        nonOpA    <- statusOf(apps, Method.POST, "/api/admin/apps/reconcile-templates", fx.admin)
        okS       <- statusOf(snap, Method.GET, "/api/admin/snapshot", fx.admin)
        okR       <- statusOf(rollup, Method.GET, "/api/admin/rollup-status", fx.admin)
      } yield assertTrue(List(reconcile, snapshot, status) == List.fill(3)(Status.Forbidden)) &&
        assertTrue(nonOpA == Status.Forbidden) &&
        assertTrue(List(okS, okR) == List.fill(2)(Status.Ok))
    },
  )

  def spec = suite("#2522 — the adult edit boundary")(opened, stillAdminOnly) @@
    TestAspect.sequential
}
