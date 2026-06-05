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

import java.time.{LocalTime, ZoneId}

/**
 * #1069: feature tests for the household-scoped named-schedule CRUD (`/api/schedules`) and the
 * profile→schedule reference route. Full stack against embedded Postgres — no mocks.
 */
object SchedulesApiSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg = JwtConfig(secret = "test-secret-at-least-32-chars!!", expiryHours = 1)

  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private def scheduleRoutes =
    for {
      nsr  <- ZIO.service[NamedScheduleRepo]
      auth <- makeAuth
    } yield ScheduleRoutes.routes(auth, nsr)

  private def profileRoutes =
    for {
      pr   <- ZIO.service[ProfileRepo]
      sr   <- ZIO.service[ScheduleRepo]
      tlr  <- ZIO.service[TimeLimitRepo]
      up   <- ZIO.service[UserProfileRepo]
      ur   <- ZIO.service[UserRepo]
      nsr  <- ZIO.service[NamedScheduleRepo]
      auth <- makeAuth
    } yield ProfileRoutes.routes(auth, pr, sr, tlr, up, ur, nsr)

  private def adminToken =
    for {
      auth  <- makeAuth
      token <- auth.login("admin", "changeme").map(_.token.value)
    } yield token

  private def url(p: String) = URL.decode(p).toOption.get

  private def win(days: List[String], from: (Int, Int), to: (Int, Int)) =
    ScheduleWindow(
      days,
      LocalTime.of(from._1, from._2),
      LocalTime.of(to._1, to._2),
      ZoneId.of("UTC"),
    )

  private def post(rs: Routes[Any, Response], path: String, body: String, token: String) =
    rs.runZIO(
      Request
        .post(url(path), Body.fromString(body))
        .addHeader(Header.Authorization.Bearer(token))
        .addHeader(Header.ContentType(MediaType.application.json)),
    )

  private def patch(rs: Routes[Any, Response], path: String, body: String, token: String) =
    rs.runZIO(
      Request
        .patch(url(path), Body.fromString(body))
        .addHeader(Header.Authorization.Bearer(token))
        .addHeader(Header.ContentType(MediaType.application.json)),
    )

  private def put(rs: Routes[Any, Response], path: String, body: String, token: String) =
    rs.runZIO(
      Request
        .put(url(path), Body.fromString(body))
        .addHeader(Header.Authorization.Bearer(token))
        .addHeader(Header.ContentType(MediaType.application.json)),
    )

  private def get(rs: Routes[Any, Response], path: String, token: String) =
    rs.runZIO(Request.get(url(path)).addHeader(Header.Authorization.Bearer(token)))

  def spec = suite("Schedules API (#1069)")(
    test("POST creates a named schedule; GET list + by-id return it with windows") {
      for {
        _     <- cleanDb
        token <- adminToken
        rs    <- scheduleRoutes
        body = CreateNamedScheduleRequest(
          name = "Bedtime",
          description = Some("overnight"),
          windows = List(win(List("mon", "tue"), (20, 0), (7, 0))),
        ).toJson
        created <- post(rs, "/api/schedules", body, token)
        cBody   <- created.body.asString
        sched   <- ZIO.fromEither(cBody.fromJson[NamedSchedule])
        list    <- get(rs, "/api/schedules", token)
        lBody   <- list.body.asString
        all     <- ZIO.fromEither(lBody.fromJson[List[NamedSchedule]])
        one     <- get(rs, s"/api/schedules/${sched.id.value}", token)
        oBody   <- one.body.asString
        byId    <- ZIO.fromEither(oBody.fromJson[NamedSchedule])
      } yield assertTrue(created.status == Status.Ok) &&
        assertTrue(sched.name == "Bedtime") &&
        assertTrue(sched.windows.length == 1) &&
        assertTrue(sched.windows.head.days == List("mon", "tue")) &&
        assertTrue(all.length == 1 && all.head.id == sched.id) &&
        assertTrue(byId.windows == sched.windows)
    },
    test("POST normalises day tokens to lowercase") {
      for {
        _     <- cleanDb
        token <- adminToken
        rs    <- scheduleRoutes
        body = CreateNamedScheduleRequest(
          name = "School",
          windows = List(
            ScheduleWindow(
              List("MON", "Tue"),
              LocalTime.of(8, 0),
              LocalTime.of(15, 0),
              ZoneId.of("UTC"),
            ),
          ),
        ).toJson
        resp  <- post(rs, "/api/schedules", body, token)
        rBody <- resp.body.asString
        s     <- ZIO.fromEither(rBody.fromJson[NamedSchedule])
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(s.windows.head.days == List("mon", "tue"))
    },
    test("POST returns 409 on duplicate name") {
      for {
        _     <- cleanDb
        token <- adminToken
        rs    <- scheduleRoutes
        body = CreateNamedScheduleRequest(name = "Bedtime").toJson
        _    <- post(rs, "/api/schedules", body, token)
        dupe <- post(rs, "/api/schedules", body, token)
      } yield assertTrue(dupe.status == Status.Conflict)
    },
    test("POST rejects an unknown day token with 400") {
      for {
        _     <- cleanDb
        token <- adminToken
        rs    <- scheduleRoutes
        body = CreateNamedScheduleRequest(
          name = "Bad",
          windows = List(win(List("funday"), (8, 0), (9, 0))),
        ).toJson
        resp <- post(rs, "/api/schedules", body, token)
      } yield assertTrue(resp.status == Status.BadRequest)
    },
    test("PATCH replaces name + windows") {
      for {
        _     <- cleanDb
        token <- adminToken
        rs    <- scheduleRoutes
        c     <- post(
          rs,
          "/api/schedules",
          CreateNamedScheduleRequest(
            "Bedtime",
            windows = List(win(List("mon"), (20, 0), (7, 0))),
          ).toJson,
          token,
        )
        cBody <- c.body.asString
        sched <- ZIO.fromEither(cBody.fromJson[NamedSchedule])
        upd = UpdateNamedScheduleRequest(
          name = "Bedtime (later)",
          windows = List(win(List("mon", "tue", "wed"), (21, 30), (7, 0))),
        ).toJson
        p     <- patch(rs, s"/api/schedules/${sched.id.value}", upd, token)
        pBody <- p.body.asString
        after <- ZIO.fromEither(pBody.fromJson[NamedSchedule])
      } yield assertTrue(p.status == Status.Ok) &&
        assertTrue(after.name == "Bedtime (later)") &&
        assertTrue(after.windows.head.days == List("mon", "tue", "wed")) &&
        assertTrue(after.windows.head.startLocal == LocalTime.of(21, 30))
    },
    test("DELETE removes it and clears a referencing profile's schedule_id") {
      for {
        _      <- cleanDb
        token  <- adminToken
        rs     <- scheduleRoutes
        prRs   <- profileRoutes
        pr     <- ZIO.service[ProfileRepo]
        nsr    <- ZIO.service[NamedScheduleRepo]
        pid    <- pr.create("Kids", Nil)
        c      <- post(rs, "/api/schedules", CreateNamedScheduleRequest("Bedtime").toJson, token)
        cBody  <- c.body.asString
        sched  <- ZIO.fromEither(cBody.fromJson[NamedSchedule])
        link   <- put(
          prRs,
          s"/api/profiles/${pid.value}/schedule",
          SetProfileScheduleRequest(Some(sched.id)).toJson,
          token,
        )
        linked <- pr.findById(pid)
        del    <- rs.runZIO(
          Request
            .delete(url(s"/api/schedules/${sched.id.value}"))
            .addHeader(Header.Authorization.Bearer(token)),
        )
        after  <- pr.findById(pid)
        remain <- nsr.listAll
      } yield assertTrue(link.status == Status.Ok) &&
        assertTrue(linked.flatMap(_.scheduleId).contains(sched.id)) &&
        assertTrue(del.status == Status.Ok) &&
        assertTrue(after.flatMap(_.scheduleId).isEmpty) &&
        assertTrue(remain.isEmpty)
    },
    test("PUT /profiles/{id}/schedule 404s for an unknown schedule id") {
      for {
        _     <- cleanDb
        token <- adminToken
        prRs  <- profileRoutes
        pr    <- ZIO.service[ProfileRepo]
        pid   <- pr.create("Kids", Nil)
        resp  <- put(
          prRs,
          s"/api/profiles/${pid.value}/schedule",
          SetProfileScheduleRequest(Some(NamedScheduleId(9999))).toJson,
          token,
        )
      } yield assertTrue(resp.status == Status.NotFound)
    },
  ) @@ TestAspect.sequential
}
