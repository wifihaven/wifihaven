package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.cache.TimeStatusCache
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

import java.time.{LocalDate, LocalTime, ZoneId}

/**
 * #1069: feature tests for the household-scoped named-schedule CRUD (`/api/schedules`) and the
 * profile→schedule reference route. Full stack against embedded Postgres — no mocks.
 */
object SchedulesApiSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)

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
      tlr  <- ZIO.service[TimeLimitRepo]
      up   <- ZIO.service[UserProfileRepo]
      ur   <- ZIO.service[UserRepo]
      nsr  <- ZIO.service[NamedScheduleRepo]
      auth <- makeAuth
    } yield ProfileRoutes.routes(auth, pr, tlr, up, ur, nsr)

  // #1538: same as `profileRoutes`, but wires a caller-supplied cache so a test can observe that
  // the schedule-attach/detach PUT busts the shared per-profile time-status entry.
  private def profileRoutesWithCache(cache: TimeStatusCache) =
    for {
      pr   <- ZIO.service[ProfileRepo]
      tlr  <- ZIO.service[TimeLimitRepo]
      up   <- ZIO.service[UserProfileRepo]
      ur   <- ZIO.service[UserRepo]
      nsr  <- ZIO.service[NamedScheduleRepo]
      auth <- makeAuth
    } yield ProfileRoutes.routes(auth, pr, tlr, up, ur, nsr, cache)

  private def sentinel(pid: ProfileId, date: LocalDate, usedMins: Int) =
    ProfileTimeStatus(pid, "Kids", date.toString, None, usedMins, 0, None, Nil, Nil, Nil)

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
    test("DELETE removes it and cascades away a referencing profile's attachment") {
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
          s"/api/profiles/${pid.value}/schedules",
          SetProfileSchedulesRequest(List(sched.id)).toJson,
          token,
        )
        linked <- nsr.blockScheduleIdsForProfile(pid)
        del    <- rs.runZIO(
          Request
            .delete(url(s"/api/schedules/${sched.id.value}"))
            .addHeader(Header.Authorization.Bearer(token)),
        )
        after  <- nsr.blockScheduleIdsForProfile(pid)
        remain <- nsr.listAllForHousehold(HouseholdId.Default)
      } yield assertTrue(link.status == Status.Ok) &&
        assertTrue(linked == List(sched.id)) &&
        assertTrue(del.status == Status.Ok) &&
        assertTrue(after.isEmpty) &&
        assertTrue(remain.isEmpty)
    },
    test("PUT /profiles/{id}/schedules 404s for an unknown schedule id") {
      for {
        _     <- cleanDb
        token <- adminToken
        prRs  <- profileRoutes
        pr    <- ZIO.service[ProfileRepo]
        pid   <- pr.create("Kids", Nil)
        resp  <- put(
          prRs,
          s"/api/profiles/${pid.value}/schedules",
          SetProfileSchedulesRequest(List(NamedScheduleId(9999))).toJson,
          token,
        )
      } yield assertTrue(resp.status == Status.NotFound)
    },
    test("a profile can reference MANY schedules; GET detail returns them; re-PUT replaces") {
      for {
        _     <- cleanDb
        token <- adminToken
        rs    <- scheduleRoutes
        prRs  <- profileRoutes
        pr    <- ZIO.service[ProfileRepo]
        pid   <- pr.create("Kids", Nil)
        a     <- post(rs, "/api/schedules", CreateNamedScheduleRequest("Bedtime").toJson, token)
          .flatMap(_.body.asString)
          .flatMap(b => ZIO.fromEither(b.fromJson[NamedSchedule]))
        b    <- post(rs, "/api/schedules", CreateNamedScheduleRequest("School hours").toJson, token)
          .flatMap(_.body.asString)
          .flatMap(b => ZIO.fromEither(b.fromJson[NamedSchedule]))
        both <- put(
          prRs,
          s"/api/profiles/${pid.value}/schedules",
          SetProfileSchedulesRequest(List(a.id, b.id)).toJson,
          token,
        )
        detail <- get(prRs, s"/api/profiles/${pid.value}", token)
          .flatMap(_.body.asString)
          .flatMap(s => ZIO.fromEither(s.fromJson[ProfileDetail]))
        // re-PUT with just one → replaces (not appends)
        _      <- put(
          prRs,
          s"/api/profiles/${pid.value}/schedules",
          SetProfileSchedulesRequest(List(b.id)).toJson,
          token,
        )
        after  <- get(prRs, s"/api/profiles/${pid.value}", token)
          .flatMap(_.body.asString)
          .flatMap(s => ZIO.fromEither(s.fromJson[ProfileDetail]))
      } yield assertTrue(both.status == Status.Ok) &&
        assertTrue(detail.scheduleIds.toSet == Set(a.id, b.id)) &&
        assertTrue(after.scheduleIds == List(b.id))
    },
    test("#1538: detaching a profile's schedules invalidates its time-status cache") {
      // Bug B: the PUT /profiles/{id}/schedules handler used to call setProfileBlockSchedules
      // without busting the per-profile TimeStatusCache, so a detached profile kept showing a
      // stale "paused for schedule" for up to the today-TTL. ProfileTimeStatus carries no blocked
      // field, so we observe the fix at the cache layer: prime the shared cache, run the real
      // detach PUT, and assert the entry was dropped (the next load recomputes).
      for {
        _     <- cleanDb
        token <- adminToken
        rs    <- scheduleRoutes
        pr    <- ZIO.service[ProfileRepo]
        cache = TimeStatusCache.makeUnsafe()
        prRs   <- profileRoutesWithCache(cache)
        clock  <- ZIO.service[Clock]
        today  <- clock.today
        pid    <- pr.create("Kids", Nil)
        sched  <- post(rs, "/api/schedules", CreateNamedScheduleRequest("Bedtime").toJson, token)
          .flatMap(_.body.asString)
          .flatMap(b => ZIO.fromEither(b.fromJson[NamedSchedule]))
        attach <- put(
          prRs,
          s"/api/profiles/${pid.value}/schedules",
          SetProfileSchedulesRequest(List(sched.id)).toJson,
          token,
        )
        // prime the cache, then confirm it really is cached (a 2nd load with a new value is ignored)
        primed <- cache.getOrLoadDaily(pid, today, today)(ZIO.succeed(sentinel(pid, today, 111)))
        cached <- cache.getOrLoadDaily(pid, today, today)(ZIO.succeed(sentinel(pid, today, 222)))
        // detach via the real PUT handler — this must invalidate the cache
        detach <- put(
          prRs,
          s"/api/profiles/${pid.value}/schedules",
          SetProfileSchedulesRequest(Nil).toJson,
          token,
        )
        // next load recomputes because the entry was dropped
        fresh  <- cache.getOrLoadDaily(pid, today, today)(ZIO.succeed(sentinel(pid, today, 333)))
      } yield assertTrue(attach.status == Status.Ok) &&
        assertTrue(detach.status == Status.Ok) &&
        assertTrue(primed.usedMins == 111) &&
        assertTrue(cached.usedMins == 111) && // proves it was genuinely cached
        assertTrue(fresh.usedMins == 333)     // proves the detach invalidated it
    },
  ) @@ TestAspect.sequential
}
