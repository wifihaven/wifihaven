package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.test.*

import java.time.{LocalTime, ZoneId}

// #998: PATCH /api/household/settings — top-level field-scoped PATCH plus
// deep-merge of the nested heartbeatFilter object.
object HouseholdPatchApiSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg   = JwtConfig(secret = "test-secret-at-least-32-chars!!", expiryHours = 1)
  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)
  private val cleanDb  = TestDatabase.cleanAndMigrate
  private def url(p: String) = URL.decode(p).toOption.get

  private val Seed = HouseholdSettings(
    dailyResetTime = LocalTime.of(0, 0),
    dailyResetTz = ZoneId.of("America/Los_Angeles"),
    // #1525: heartbeatHostPatterns is retired — the DB column is no longer written or read, so the
    // repo round-trip always returns Nil regardless of what the caller passes in.
    heartbeatFilter = HeartbeatFilter(
      enabled = false,
      bytesThreshold = 1024,
      heartbeatHostPatterns = Nil,
    ),
    unmanagedMacPolicy = UnmanagedMacPolicy.Default,
  )

  private def setupHousehold =
    for {
      _    <- cleanDb
      repo <- ZIO.service[HouseholdSettingsRepo]
      _    <- repo.update(Seed)
    } yield ()

  private def routesAndToken =
    for {
      auth <- makeAuth
      repo <- ZIO.service[HouseholdSettingsRepo]
      tk   <- auth.login("admin", "changeme").map(_.token.value)
    } yield (HouseholdSettingsRoutes.routes(auth, repo), tk)

  private def patch(routes: Routes[Any, Response], token: String, body: String) =
    routes.runZIO(
      Request
        .patch(url("/api/household/settings"), Body.fromString(body))
        .addHeader(Header.Authorization.Bearer(token))
        .addHeader(Header.ContentType(MediaType.application.json)),
    )

  def spec = suite("PATCH /api/household/settings (#998)")(
    test("single top-level field patch") {
      for {
        _            <- setupHousehold
        (routes, tk) <- routesAndToken
        resp         <- patch(routes, tk, """{"dailyResetTime":"03:30:00"}""")
        repo         <- ZIO.service[HouseholdSettingsRepo]
        after        <- repo.get
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(after.dailyResetTime == LocalTime.of(3, 30)) &&
        assertTrue(after.dailyResetTz == Seed.dailyResetTz) &&
        assertTrue(after.heartbeatFilter == Seed.heartbeatFilter)
    },
    test("nested heartbeatFilter deep-merges (enabled toggle preserves threshold)") {
      for {
        _            <- setupHousehold
        (routes, tk) <- routesAndToken
        resp         <- patch(routes, tk, """{"heartbeatFilter":{"enabled":true}}""")
        repo         <- ZIO.service[HouseholdSettingsRepo]
        after        <- repo.get
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(after.heartbeatFilter.enabled) &&
        assertTrue(after.heartbeatFilter.bytesThreshold == 1024) &&
        // #1525: heartbeatHostPatterns is retired — DB no longer persists it; reads return Nil.
        assertTrue(after.heartbeatFilter.heartbeatHostPatterns == Nil)
    },
    test(
      "#1525 PATCH that includes heartbeatHostPatterns is accepted but the field is ignored",
    ) {
      // Older SPA builds still send heartbeatHostPatterns on PATCH. We must tolerate the field on
      // input (no 400, no error) and ignore it: enforcement now comes from the canonical
      // InfraHosts code constant, not from a per-install hand-curated list.
      for {
        _            <- setupHousehold
        (routes, tk) <- routesAndToken
        resp         <- patch(
          routes,
          tk,
          """{"heartbeatFilter":{"heartbeatHostPatterns":["legacy.example.com"]}}""",
        )
        repo         <- ZIO.service[HouseholdSettingsRepo]
        after        <- repo.get
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(after.heartbeatFilter.heartbeatHostPatterns == Nil)
    },
    test(
      "#1525 PATCH that omits heartbeatHostPatterns produces the same persisted state as one that sends it",
    ) {
      // Enforcement no longer derives from `heartbeatHostPatterns` — InfraHosts is the source. So
      // a PATCH that omits the field and a PATCH that sends it must yield identical persisted
      // HouseholdSettings, proving the field has no effect on behavior.
      for {
        _             <- setupHousehold
        (routes, tk)  <- routesAndToken
        _             <- patch(routes, tk, """{"heartbeatFilter":{"bytesThreshold":2048}}""")
        repo          <- ZIO.service[HouseholdSettingsRepo]
        afterOmitted  <- repo.get
        _             <- setupHousehold
        _             <- patch(
          routes,
          tk,
          """{"heartbeatFilter":{"bytesThreshold":2048,"heartbeatHostPatterns":["x.example.com"]}}""",
        )
        afterIncluded <- repo.get
      } yield assertTrue(afterOmitted == afterIncluded)
    },
    test("absent fields preserve existing values") {
      for {
        _            <- setupHousehold
        (routes, tk) <- routesAndToken
        resp         <- patch(routes, tk, "{}")
        repo         <- ZIO.service[HouseholdSettingsRepo]
        after        <- repo.get
      } yield assertTrue(resp.status == Status.Ok) && assertTrue(after == Seed)
    },
    test("multi-field patch (top-level + nested) applied together") {
      for {
        _            <- setupHousehold
        (routes, tk) <- routesAndToken
        body =
          """{"dailyResetTz":"America/New_York","heartbeatFilter":{"bytesThreshold":4096,"heartbeatHostPatterns":["bar.com"]}}"""
        resp  <- patch(routes, tk, body)
        repo  <- ZIO.service[HouseholdSettingsRepo]
        after <- repo.get
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(after.dailyResetTz == ZoneId.of("America/New_York")) &&
        assertTrue(after.heartbeatFilter.enabled == false) && // preserved
        assertTrue(after.heartbeatFilter.bytesThreshold == 4096) &&
        // #1525: heartbeatHostPatterns is ignored on input and never persisted.
        assertTrue(after.heartbeatFilter.heartbeatHostPatterns == Nil)
    },
    test("null on non-nullable field returns 400") {
      for {
        _            <- setupHousehold
        (routes, tk) <- routesAndToken
        resp         <- patch(routes, tk, """{"dailyResetTime":null}""")
      } yield assertTrue(resp.status == Status.BadRequest)
    },
    test("null on heartbeatFilter object returns 400") {
      for {
        _            <- setupHousehold
        (routes, tk) <- routesAndToken
        resp         <- patch(routes, tk, """{"heartbeatFilter":null}""")
      } yield assertTrue(resp.status == Status.BadRequest)
    },
    test("#961 default unmanagedMacPolicy is allow/blockPage=true") {
      for {
        _     <- cleanDb
        repo  <- ZIO.service[HouseholdSettingsRepo]
        _     <- repo.ensureDefault(ZoneId.of("UTC"))
        after <- repo.get
      } yield assertTrue(after.unmanagedMacPolicy == UnmanagedMacPolicy.Default) &&
        assertTrue(after.unmanagedMacPolicy.policy == "allow") &&
        assertTrue(after.unmanagedMacPolicy.blockPage)
    },
    test("#961 unmanagedMacPolicy deep-merges (toggle blockPage preserves policy)") {
      for {
        _            <- setupHousehold
        (routes, tk) <- routesAndToken
        resp         <- patch(routes, tk, """{"unmanagedMacPolicy":{"blockPage":false}}""")
        repo         <- ZIO.service[HouseholdSettingsRepo]
        after        <- repo.get
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(after.unmanagedMacPolicy.policy == "allow") &&
        assertTrue(!after.unmanagedMacPolicy.blockPage)
    },
    test("#961 unmanagedMacPolicy.policy=block round-trips") {
      for {
        _            <- setupHousehold
        (routes, tk) <- routesAndToken
        resp         <- patch(routes, tk, """{"unmanagedMacPolicy":{"policy":"block"}}""")
        repo         <- ZIO.service[HouseholdSettingsRepo]
        after        <- repo.get
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(after.unmanagedMacPolicy.policy == "block") &&
        assertTrue(after.unmanagedMacPolicy.blockPage)
    },
    test("#961 unknown policy value returns 400") {
      for {
        _            <- setupHousehold
        (routes, tk) <- routesAndToken
        resp         <- patch(routes, tk, """{"unmanagedMacPolicy":{"policy":"quarantine"}}""")
      } yield assertTrue(resp.status == Status.BadRequest)
    },
    test("#961 null on unmanagedMacPolicy object returns 400") {
      for {
        _            <- setupHousehold
        (routes, tk) <- routesAndToken
        resp         <- patch(routes, tk, """{"unmanagedMacPolicy":null}""")
      } yield assertTrue(resp.status == Status.BadRequest)
    },
    test("403 when non-admin (adult) tries to PATCH") {
      for {
        _        <- setupHousehold
        userRepo <- ZIO.service[UserRepo]
        repo     <- ZIO.service[HouseholdSettingsRepo]
        auth     <- makeAuth
        hash     <- auth.hashPassword("pass")
        id       <- userRepo.create("mom", hash, "adult")
        _        <- userRepo.clearMustChangePassword(id)
        token    <- auth.login("mom", "pass").map(_.token.value)
        routes = HouseholdSettingsRoutes.routes(auth, repo)
        resp <- patch(routes, token, """{"dailyResetTime":"05:00:00"}""")
      } yield assertTrue(resp.status == Status.Forbidden)
    },
    test("401 without token") {
      for {
        _      <- setupHousehold
        routes <- routesAndToken.map(_._1)
        resp   <- routes.runZIO(
          Request
            .patch(
              url("/api/household/settings"),
              Body.fromString("""{"dailyResetTime":"05:00:00"}"""),
            )
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
      } yield assertTrue(resp.status == Status.Unauthorized)
    },
  ) @@ TestAspect.sequential
}
