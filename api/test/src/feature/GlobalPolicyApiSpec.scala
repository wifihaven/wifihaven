package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*

// #1320 / #1308: management + audit surface for the household-global policy
// (`PolicySnapshot.global`). Exercises the GlobalPolicyRoutes CRUD endpoints
// (allow / block / blocklists / flags) and the per-profile default-deny toggle
// the SPA drives via the existing profile PUT.
object GlobalPolicyApiSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg  = JwtConfig(secret = "test-secret-at-least-32-chars!!", expiryHours = 1)
  private val cleanDb = TestDatabase.cleanAndMigrate

  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)

  private def url(p: String) = URL.decode(p).toOption.get

  private def globalRoutesAndToken =
    for {
      auth <- makeAuth
      repo <- ZIO.service[GlobalPolicyRepo]
      ur   <- ZIO.service[UserRepo]
      tk   <- auth.login("admin", "changeme").map(_.token.value)
    } yield (GlobalPolicyRoutes.routes(auth, repo, ur), tk)

  private def send(
      routes: Routes[Any, Response],
      req: Request,
      token: String,
  ) =
    routes.runZIO(
      req
        .addHeader(Header.Authorization.Bearer(token))
        .addHeader(Header.ContentType(MediaType.application.json)),
    )

  private def getView(routes: Routes[Any, Response], token: String) =
    for {
      resp <- send(routes, Request.get(url("/api/global/policy")), token)
      body <- resp.body.asString
      view <- ZIO.fromEither(body.fromJson[GlobalPolicyView]).mapError(new RuntimeException(_))
    } yield (resp.status, view)

  def spec = suite("global policy management (#1320)")(
    test("GET /api/global/policy returns the assembled view") {
      for {
        _            <- cleanDb
        (routes, tk) <- globalRoutesAndToken
        (st, view)   <- getView(routes, tk)
      } yield assertTrue(
        st == Status.Ok,
        view.allow.isEmpty,
        view.blocks.isEmpty,
        view.blocklistIds.isEmpty,
        !view.blocked,
        !view.blockIpOnly,
      )
    },
    test("POST /api/global/allow adds a host with audit reason + acting user") {
      for {
        _            <- cleanDb
        (routes, tk) <- globalRoutesAndToken
        resp         <- send(
          routes,
          Request.post(
            url("/api/global/allow"),
            Body.fromString("""{"host":"pki.example","reason":"PKI"}"""),
          ),
          tk,
        )
        (_, view)    <- getView(routes, tk)
        entry = view.allow.headOption
      } yield assertTrue(
        resp.status == Status.Ok,
        view.allow.map(_.host.value) == List("pki.example"),
        entry.flatMap(_.reason).contains("PKI"),
        entry.flatMap(_.addedBy).contains("admin"),
        entry.flatMap(_.removedAt).isEmpty,
      )
    },
    test("DELETE /api/global/allow soft-deletes (active set drops it, history kept)") {
      for {
        _            <- cleanDb
        (routes, tk) <- globalRoutesAndToken
        _            <- send(
          routes,
          Request.post(url("/api/global/allow"), Body.fromString("""{"host":"gone.example"}""")),
          tk,
        )
        del          <- send(routes, Request.delete(url("/api/global/allow/gone.example")), tk)
        repo         <- ZIO.service[GlobalPolicyRepo]
        active       <- repo.get
        (_, view)    <- getView(routes, tk)
      } yield assertTrue(
        del.status == Status.Ok,
        // active wire set no longer carries it…
        active.extraAllowed.isEmpty,
        // …but the audit history retains the soft-deleted row.
        view.allow.map(_.host.value) == List("gone.example"),
        view.allow.headOption.flatMap(_.removedAt).isDefined,
      )
    },
    test("POST/DELETE /api/global/blocks manage the network-wide block set") {
      for {
        _            <- cleanDb
        (routes, tk) <- globalRoutesAndToken
        _            <- send(
          routes,
          Request.post(
            url("/api/global/blocks"),
            Body.fromString("""{"host":"evil.example","reason":"operator"}"""),
          ),
          tk,
        )
        repo         <- ZIO.service[GlobalPolicyRepo]
        afterAdd     <- repo.get
        _            <- send(routes, Request.delete(url("/api/global/blocks/evil.example")), tk)
        afterDel     <- repo.get
        (_, view)    <- getView(routes, tk)
      } yield assertTrue(
        afterAdd.extraBlocked.map(_.value) == List("evil.example"),
        afterDel.extraBlocked.isEmpty,
        view.blocks.map(_.host.value) == List("evil.example"),
      )
    },
    test("PUT /api/global/blocklists replaces the household-global category set") {
      for {
        _            <- cleanDb
        blr          <- ZIO.service[BlocklistRepo]
        _            <- blr.upsertMeta(
          BlocklistId.unsafe("ads"),
          "Ads",
          None,
          bundled = true,
          None,
          java.time.Instant.parse("2026-01-01T00:00:00Z"),
        )
        _            <- blr.upsertMeta(
          BlocklistId.unsafe("adult"),
          "Adult",
          None,
          bundled = true,
          None,
          java.time.Instant.parse("2026-01-01T00:00:00Z"),
        )
        (routes, tk) <- globalRoutesAndToken
        _            <- send(
          routes,
          Request.put(
            url("/api/global/blocklists"),
            Body.fromString("""{"blocklistIds":["ads","adult"]}"""),
          ),
          tk,
        )
        repo         <- ZIO.service[GlobalPolicyRepo]
        afterSet     <- repo.get
        // a second PUT with a narrower set replaces, not appends
        _            <- send(
          routes,
          Request
            .put(url("/api/global/blocklists"), Body.fromString("""{"blocklistIds":["ads"]}""")),
          tk,
        )
        afterReplace <- repo.get
      } yield assertTrue(
        afterSet.blocklistIds.map(_.value).toSet == Set("ads", "adult"),
        afterReplace.blocklistIds.map(_.value) == List("ads"),
      )
    },
    test("PUT /api/global/flags sets the lockdown + strict-IP flags") {
      for {
        _            <- cleanDb
        (routes, tk) <- globalRoutesAndToken
        resp         <- send(
          routes,
          Request.put(
            url("/api/global/flags"),
            Body.fromString("""{"blocked":true,"blockReason":"Manual","blockIpOnly":true}"""),
          ),
          tk,
        )
        repo         <- ZIO.service[GlobalPolicyRepo]
        after        <- repo.get
      } yield assertTrue(
        resp.status == Status.Ok,
        after.blocked,
        after.blockReason.contains(MacBlockReason.Manual),
        after.blockIpOnly,
      )
    },
    test("non-admin (read-only) cannot write the global allow set") {
      for {
        _    <- cleanDb
        auth <- makeAuth
        repo <- ZIO.service[GlobalPolicyRepo]
        ur   <- ZIO.service[UserRepo]
        hash <- auth.hashPassword("ro-pass-123")
        _    <- ur.create("viewer", hash, "child")
        roTk <- auth.login("viewer", "ro-pass-123").map(_.token.value)
        routes = GlobalPolicyRoutes.routes(auth, repo, ur)
        resp  <- send(
          routes,
          Request.post(url("/api/global/allow"), Body.fromString("""{"host":"x.example"}""")),
          roTk,
        )
        after <- repo.get
      } yield assertTrue(resp.status == Status.Forbidden, after.extraAllowed.isEmpty)
    },
    test("PUT /api/profiles/:id persists the per-profile default-deny flag") {
      for {
        _    <- cleanDb
        auth <- makeAuth
        pr   <- ZIO.service[ProfileRepo]
        tlr  <- ZIO.service[TimeLimitRepo]
        upr  <- ZIO.service[UserProfileRepo]
        ur   <- ZIO.service[UserRepo]
        tk   <- auth.login("admin", "changeme").map(_.token.value)
        pid  <- pr.create("kids", Nil)
        routes = ProfileRoutes.routes(auth, pr, tlr, upr, ur)
        resp   <- send(
          routes,
          Request.put(
            url(s"/api/profiles/${pid.value}"),
            Body.fromString(
              """{"name":"kids","blockedCategories":[],"paused":false,"schedules":[],"timeLimit":null,"defaultDeny":true}""",
            ),
          ),
          tk,
        )
        after  <- pr.findById(pid)
        // omitting defaultDeny on a later PUT preserves the stored value
        resp2  <- send(
          routes,
          Request.put(
            url(s"/api/profiles/${pid.value}"),
            Body.fromString(
              """{"name":"kids","blockedCategories":[],"paused":false,"schedules":[],"timeLimit":null}""",
            ),
          ),
          tk,
        )
        after2 <- pr.findById(pid)
      } yield assertTrue(
        resp.status == Status.Ok,
        after.exists(_.defaultDeny),
        resp2.status == Status.Ok,
        after2.exists(_.defaultDeny),
      )
    },
  ) @@ TestAspect.sequential
}
