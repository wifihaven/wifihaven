package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.policy.{PolicyService, PolicyServiceLive}
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*

/**
 * #2382 (epic #622) — the server-level per-household "disable enforcement" escape hatch, end to
 * end. Full stack, embedded Postgres, NO repo mocks; the clock is injected ([[TestClock]]).
 *
 * Pins:
 *   - toggle ON → the household's snapshot is fully PERMISSIVE (allow-all, no devices/profiles),
 *     via the SAME permissive path a `lapsed` household takes (#2137); toggle OFF → normal policy
 *     returns.
 *   - strict per-household isolation: household A's toggle does NOT change household B's snapshot
 *     (positive sees-own + negative no-cross-tenant).
 *   - admin-only: a non-admin cannot flip it via PUT /api/household/enforcement.
 */
object EnforcementDisableSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & doobie.Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val jwtCfg = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)

  // A PolicyService whose escape-hatch reader is the REAL household_settings.enforcement_disabled
  // column (a behavioral setting), so flipping the flag flows through to the built snapshot exactly
  // as in production.
  private def mkPolicy =
    for {
      pr    <- ZIO.service[ProfileRepo]
      hsr   <- ZIO.service[HouseholdSettingsRepo]
      tlr   <- ZIO.service[TimeLimitRepo]
      atlr  <- ZIO.service[AppTimeLimitRepo]
      dr    <- ZIO.service[DeviceRepo]
      blr   <- ZIO.service[BlocklistRepo]
      trr   <- ZIO.service[TrafficReportRepo]
      er    <- ZIO.service[TimeExtensionRepo]
      ar    <- ZIO.service[AppRepo]
      clock <- ZIO.service[Clock]
    } yield PolicyServiceLive(
      pr,
      hsr,
      tlr,
      atlr,
      dr,
      blr,
      trr,
      er,
      ar,
      clock,
      enforcementDisabledOf = hsr.enforcementDisabled,
    ): PolicyService

  /** Create a household + one device so its enforcing snapshot has content to check against. */
  private def seedHousehold(slug: String) =
    for {
      hr  <- ZIO.service[HouseholdRepo]
      pr  <- ZIO.service[ProfileRepo]
      dr  <- ZIO.service[DeviceRepo]
      hid <- hr.create(slug, slug, 1)
      pid <- pr.create(s"$slug-profile", Nil, hid)
      mac = MacAddress.unsafe(f"aa:bb:cc:00:00:${hid.value}%02x")
      _ <- dr.upsert(mac, s"$slug-device", Some(pid), "10.0.0.2", hid)
    } yield (hid, mac)

  def spec = suite("EnforcementDisableSpec")(
    test("toggle ON → permissive snapshot; OFF → normal policy returns") {
      for {
        _            <- cleanDb
        hsr          <- ZIO.service[HouseholdSettingsRepo]
        policy       <- mkPolicy
        (hid, mac)   <- seedHousehold("house-a")
        // Baseline: enforcing (its device is in the snapshot).
        snapBefore   <- policy.snapshot(hid)
        // Flip the escape hatch on → permissive.
        _            <- hsr.setEnforcementDisabled(hid, true)
        _            <- policy.invalidate(hid) // bust any cached build
        snapDisabled <- policy.snapshot(hid)
        // Flip it back off → enforcement returns.
        _            <- hsr.setEnforcementDisabled(hid, false)
        _            <- policy.invalidate(hid)
        snapAfter    <- policy.snapshot(hid)
      } yield assertTrue(
        snapBefore.devices.contains(mac),
        // permissive: no devices/profiles, global allow-all
        snapDisabled.devices.isEmpty,
        snapDisabled.profiles.isEmpty,
        snapDisabled.global == BlockRules.allowAll,
        // enforcement restored
        snapAfter.devices.contains(mac),
      )
    },
    test("per-household isolation: A's toggle does not change B's snapshot") {
      for {
        _            <- cleanDb
        hsr          <- ZIO.service[HouseholdSettingsRepo]
        policy       <- mkPolicy
        (hidA, macA) <- seedHousehold("iso-a")
        (hidB, macB) <- seedHousehold("iso-b")
        // Disable enforcement for A only.
        _            <- hsr.setEnforcementDisabled(hidA, true)
        _            <- policy.invalidate(hidA)
        snapA        <- policy.snapshot(hidA)
        snapB        <- policy.snapshot(hidB)
        // The stored flags themselves are per-household.
        aDisabled    <- hsr.enforcementDisabled(hidA)
        bDisabled    <- hsr.enforcementDisabled(hidB)
      } yield assertTrue(
        // A sees its own permissive snapshot (positive sees-own)
        snapA.devices.isEmpty,
        snapA.global == BlockRules.allowAll,
        // B is completely unaffected — still enforcing with its device present (no cross-tenant)
        snapB.devices.contains(macB),
        !snapB.devices.contains(macA),
        aDisabled,
        !bDisabled,
      )
    },
    test("admin-only: a non-admin cannot flip the toggle; admin can") {
      for {
        _        <- cleanDb
        userRepo <- ZIO.service[UserRepo]
        hsr      <- ZIO.service[HouseholdSettingsRepo]
        clock    <- ZIO.service[Clock]
        auth   = AuthServiceLive(userRepo, jwtCfg, clock)
        routes = HouseholdEnforcementRoutes.routes(auth, hsr)
        // A usable non-admin (adult) user in the default household: create, clear the forced
        // password-change flag, then log in.
        pwHash   <- auth.hashPassword("adultpass123")
        adultId  <- userRepo.create("adult1", pwHash, "adult")
        _        <- userRepo.clearMustChangePassword(adultId)
        adultTok <- auth.login("adult1", "adultpass123").map(_.token.value)
        adminTok <- auth.login("admin", "changeme").map(_.token.value)
        body = SetEnforcementRequest(enforcementDisabled = true).toJson
        put  = (tok: String) =>
          Request
            .put(URL.decode("/api/household/enforcement").toOption.get, Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(tok))
            .addHeader(Header.ContentType(MediaType.application.json))
        // Non-admin write is rejected AND leaves the flag untouched.
        adultResp  <- routes.runZIO(put(adultTok))
        afterAdult <- hsr.enforcementDisabled(HouseholdId.Default)
        // Admin write succeeds AND flips the flag.
        adminResp  <- routes.runZIO(put(adminTok))
        afterAdmin <- hsr.enforcementDisabled(HouseholdId.Default)
      } yield assertTrue(
        adultResp.status == Status.Forbidden,
        !afterAdult,
        adminResp.status == Status.Ok,
        afterAdmin,
      )
    },
  ) @@ TestAspect.sequential
}
