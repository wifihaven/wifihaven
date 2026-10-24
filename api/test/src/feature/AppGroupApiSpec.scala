package wifihaven.api.feature

import doobie.*
import doobie.implicits.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.Clock.TestClock
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*

import java.time.LocalDate

/**
 * #2934 (epic #2930, design docs/design/weekly-limits-and-app-groups.md §5, §6.4, §9): the app
 * group + weekly-limit API, end to end over embedded Postgres. No repo is mocked; the snapshot
 * invalidation callback is the one injected seam (a counting spy, per `ScheduleRoutes`), because
 * enforcement of groups is #2933 and the ETag does not move on a group write until then.
 *
 * Every absence assertion sits beside a positive one on the same rig (a control group, profile, app
 * or row that DOES show up), so a rig that returns nothing cannot pass.
 */
object AppGroupApiSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val macA = MacAddress.unsafe("aa:bb:cc:00:00:2a")
  private val macB = MacAddress.unsafe("aa:bb:cc:00:00:2b")

  private val jwt = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)

  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      hr    <- ZIO.service[HouseholdRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwt, clock, hr): AuthService

  private def login(auth: AuthService, user: String, pw: String, slug: Option[String] = None) =
    auth
      .login(slug.fold(user)(s => s"$s/$user"), pw)
      .mapError(e => new RuntimeException(s"login failed: $e"))
      .map(_.token.value)

  /** The route table under test plus a spy on `invalidateSnapshot` (calls per household). */
  private final case class Rig(
      groups: Routes[Any, Response],
      apps: Routes[Any, Response],
      settings: Routes[Any, Response],
      invalidations: Ref[List[HouseholdId]],
  )

  private def rig =
    for {
      groupRepo <- ZIO.service[AppGroupRepo]
      appRepo   <- ZIO.service[AppRepo]
      profRepo  <- ZIO.service[ProfileRepo]
      upRepo    <- ZIO.service[UserProfileRepo]
      blRepo    <- ZIO.service[BlocklistRepo]
      hsRepo    <- ZIO.service[HouseholdSettingsRepo]
      auth      <- makeAuth
      spy       <- Ref.make(List.empty[HouseholdId])
      inval = (hh: HouseholdId) => spy.update(hh :: _)
    } yield Rig(
      AppGroupRoutes.routes(auth, groupRepo, profRepo, upRepo, inval),
      AppRoutes.routes(auth, appRepo, profRepo, upRepo, blRepo, groupRepo, inval),
      HouseholdSettingsRoutes.routes(auth, hsRepo, inval),
      spy,
    )

  private def url(p: String) = URL.decode(p).toOption.get

  private def send(
      rs: Routes[Any, Response],
      method: Method,
      path: String,
      tok: String,
      body: String = "",
  ) =
    rs.runZIO(
      Request
        .default(method, url(path), Body.fromString(body))
        .addHeader(Header.Authorization.Bearer(tok))
        .addHeader(Header.ContentType(MediaType.application.json)),
    )

  private def bodyOf(r: Response) = r.body.asString

  private def createGroup(rs: Routes[Any, Response], tok: String, name: String, apps: List[AppId]) =
    send(
      rs,
      Method.POST,
      "/api/app-groups",
      tok,
      s"""{"name":"$name","memberAppIds":[${apps.map(_.value).mkString(",")}]}""",
    )

  private def decodeGroup(r: Response) =
    bodyOf(r).flatMap(b => ZIO.fromEither(b.fromJson[AppGroup]).mapError(new RuntimeException(_)))

  private def listGroups(rs: Routes[Any, Response], tok: String) =
    send(rs, Method.GET, "/api/app-groups", tok)
      .flatMap(bodyOf)
      .flatMap(b => ZIO.fromEither(b.fromJson[List[AppGroup]]).mapError(new RuntimeException(_)))

  private def attach(
      rs: Routes[Any, Response],
      tok: String,
      p: ProfileId,
      g: AppGroupId,
      body: String,
  ) =
    send(rs, Method.PUT, s"/api/profiles/${p.value}/app-groups/${g.value}", tok, body)

  private def attachments(rs: Routes[Any, Response], tok: String, p: ProfileId) =
    send(rs, Method.GET, s"/api/profiles/${p.value}/app-groups", tok)
      .flatMap(bodyOf)
      .flatMap(b =>
        ZIO.fromEither(b.fromJson[List[ProfileAppGroup]]).mapError(new RuntimeException(_)),
      )

  private def assignmentsOf(rs: Routes[Any, Response], tok: String, app: AppId) =
    send(rs, Method.GET, s"/api/apps/${app.value}", tok)
      .flatMap(bodyOf)
      .flatMap(b => ZIO.fromEither(b.fromJson[AppDetail]).mapError(new RuntimeException(_)))
      .map(_.assignments)

  private def putAssignment(
      rs: Routes[Any, Response],
      tok: String,
      app: AppId,
      p: ProfileId,
      body: String,
  ) =
    send(rs, Method.PUT, s"/api/apps/${app.value}/policy/${p.value}", tok, body)

  private def newApps =
    for {
      ar       <- ZIO.service[AppRepo]
      roblox   <- ar.create("Roblox", "roblox", None, None)
      minecraf <- ar.create("Minecraft", "minecraft", None, None)
      zoom     <- ar.create("Zoom", "zoom", None, None)
    } yield (roblox, minecraf, zoom)

  def spec = suite("App groups + weekly limits API (#2934)")(
    test("group CRUD round-trips: create, list, rename + re-member, delete") {
      for {
        _                        <- cleanDb
        two                      <- TestLayers.seedTwoHouseholds(macA, macB)
        r                        <- rig
        auth                     <- makeAuth
        tok                      <- login(auth, two.adminA, two.password)
        (roblox, minecraf, zoom) <- newApps
        created <- createGroup(r.groups, tok, "Games", List(roblox, minecraf)).flatMap(decodeGroup)
        listed  <- listGroups(r.groups, tok)
        patched <- send(
          r.groups,
          Method.PATCH,
          s"/api/app-groups/${created.id.value}",
          tok,
          s"""{"name":"Gaming","memberAppIds":[${zoom.value}]}""",
        ).flatMap(decodeGroup)
        afterPatch  <- listGroups(r.groups, tok)
        del         <- send(r.groups, Method.DELETE, s"/api/app-groups/${created.id.value}", tok)
        afterDelete <- listGroups(r.groups, tok)
      } yield assertTrue(
        created.name == "Games",
        created.memberAppIds.toSet == Set(roblox, minecraf),
        listed.map(_.id) == List(created.id),
        patched.name == "Gaming",
        patched.memberAppIds == List(zoom),
        afterPatch.map(_.name) == List("Gaming"),
        del.status.isSuccess,
        // liveness anchor: the group was listed twice above, so an empty list here is a delete.
        afterDelete.isEmpty,
      )
    },
    test("an app already in another group is rejected with a message naming that group") {
      for {
        _                        <- cleanDb
        two                      <- TestLayers.seedTwoHouseholds(macA, macB)
        r                        <- rig
        auth                     <- makeAuth
        tok                      <- login(auth, two.adminA, two.password)
        (roblox, minecraf, zoom) <- newApps
        games        <- createGroup(r.groups, tok, "Games", List(roblox)).flatMap(decodeGroup)
        // POST: roblox again, in a second group.
        dupPost      <- createGroup(r.groups, tok, "Other", List(roblox))
        dupPostBody  <- bodyOf(dupPost)
        // PATCH: move roblox into a second (valid) group.
        other        <- createGroup(r.groups, tok, "Other", List(minecraf)).flatMap(decodeGroup)
        dupPatch     <- send(
          r.groups,
          Method.PATCH,
          s"/api/app-groups/${other.id.value}",
          tok,
          s"""{"memberAppIds":[${minecraf.value},${roblox.value}]}""",
        )
        dupPatchBody <- bodyOf(dupPatch)
        // Control: an app in no group is accepted, and the rejected write changed nothing.
        okPatch      <- send(
          r.groups,
          Method.PATCH,
          s"/api/app-groups/${other.id.value}",
          tok,
          s"""{"memberAppIds":[${minecraf.value},${zoom.value}]}""",
        )
        finalGroups  <- listGroups(r.groups, tok)
      } yield assertTrue(
        dupPost.status == Status.Conflict,
        dupPostBody.contains("Games"),
        dupPatch.status == Status.Conflict,
        dupPatchBody.contains("Games"),
        okPatch.status.isSuccess,
        finalGroups.find(_.id == games.id).exists(_.memberAppIds == List(roblox)),
        finalGroups.find(_.id == other.id).exists(_.memberAppIds.toSet == Set(minecraf, zoom)),
      )
    },
    test("another household cannot read, edit, delete or attach a household's group") {
      for {
        _              <- cleanDb
        two            <- TestLayers.seedTwoHouseholds(macA, macB)
        r              <- rig
        auth           <- makeAuth
        tokA           <- login(auth, two.adminA, two.password)
        tokB           <- login(auth, two.adminB, two.password, Some(two.slugB))
        (roblox, _, _) <- newApps
        gA             <- createGroup(r.groups, tokA, "Games", List(roblox)).flatMap(decodeGroup)
        listB          <- listGroups(r.groups, tokB)
        patchB         <- send(
          r.groups,
          Method.PATCH,
          s"/api/app-groups/${gA.id.value}",
          tokB,
          """{"name":"Hacked"}""",
        )
        delB           <- send(r.groups, Method.DELETE, s"/api/app-groups/${gA.id.value}", tokB)
        // B's own profile attaching A's group: the V92 composite FK would refuse it too; the API
        // answers 404 before the FK is reached.
        attachB        <- attach(r.groups, tokB, two.profileB, gA.id, """{"dailyMinutes":30}""")
        // A attaching its group to B's profile.
        attachX        <- attach(r.groups, tokA, two.profileB, gA.id, """{"dailyMinutes":30}""")
        // Control: A attaching to A's own profile works.
        attachOk       <- attach(r.groups, tokA, two.profileA, gA.id, """{"dailyMinutes":30}""")
        listA          <- listGroups(r.groups, tokA)
        attB           <- attachments(r.groups, tokB, two.profileB)
        attA           <- attachments(r.groups, tokA, two.profileA)
      } yield assertTrue(
        listB.isEmpty,
        patchB.status == Status.NotFound,
        delB.status == Status.NotFound,
        attachB.status == Status.NotFound,
        attachX.status == Status.NotFound,
        attachOk.status.isSuccess,
        listA.map(_.name) == List("Games"),
        attB.isEmpty,
        attA.map(_.groupId) == List(gA.id),
      )
    },
    test("an attachment needs at least one limit; values round-trip; DELETE detaches") {
      for {
        _              <- cleanDb
        two            <- TestLayers.seedTwoHouseholds(macA, macB)
        r              <- rig
        auth           <- makeAuth
        tok            <- login(auth, two.adminA, two.password)
        (roblox, _, _) <- newApps
        g              <- createGroup(r.groups, tok, "Games", List(roblox)).flatMap(decodeGroup)
        none           <- attach(r.groups, tok, two.profileA, g.id, "{}")
        zero           <- attach(r.groups, tok, two.profileA, g.id, """{"weeklyMinutes":0}""")
        afterBad       <- attachments(r.groups, tok, two.profileA)
        daily          <- attach(r.groups, tok, two.profileA, g.id, """{"dailyMinutes":45}""")
        both           <- attach(
          r.groups,
          tok,
          two.profileA,
          g.id,
          """{"dailyMinutes":45,"weeklyMinutes":300,"exemptFromDaily":false}""",
        )
        listed         <- attachments(r.groups, tok, two.profileA)
        del            <- send(
          r.groups,
          Method.DELETE,
          s"/api/profiles/${two.profileA.value}/app-groups/${g.id.value}",
          tok,
        )
        afterDel       <- attachments(r.groups, tok, two.profileA)
      } yield assertTrue(
        none.status == Status.BadRequest,
        zero.status == Status.BadRequest,
        afterBad.isEmpty,
        daily.status.isSuccess,
        both.status.isSuccess,
        listed.size == 1,
        listed.head.dailyMinutes.contains(45),
        listed.head.weeklyMinutes.contains(300),
        !listed.head.exemptFromDaily,
        listed.head.memberAppIds == List(roblox),
        del.status.isSuccess,
        afterDel.isEmpty,
      )
    },
    test("coveredBy is set on the overlapping assignment only, and clears on detach") {
      for {
        _                        <- cleanDb
        two                      <- TestLayers.seedTwoHouseholds(macA, macB)
        r                        <- rig
        auth                     <- makeAuth
        tok                      <- login(auth, two.adminA, two.password)
        profRepo                 <- ZIO.service[ProfileRepo]
        other                    <- profRepo.create("A-Teens", Nil)
        (roblox, minecraf, zoom) <- newApps
        g       <- createGroup(r.groups, tok, "Games", List(roblox, minecraf)).flatMap(decodeGroup)
        // Overlap on roblox (covered) + a control assignment on zoom (not in any group), plus the
        // same covered app on a profile the group is NOT attached to.
        _       <- putAssignment(r.apps, tok, roblox, two.profileA, """{"mode":"allowed"}""")
        _       <- putAssignment(r.apps, tok, zoom, two.profileA, """{"mode":"allowed"}""")
        _       <- putAssignment(r.apps, tok, roblox, other, """{"mode":"allowed"}""")
        _       <- attach(r.groups, tok, two.profileA, g.id, """{"dailyMinutes":30}""")
        roblox1 <- assignmentsOf(r.apps, tok, roblox)
        zoom1   <- assignmentsOf(r.apps, tok, zoom)
        _       <- send(
          r.groups,
          Method.DELETE,
          s"/api/profiles/${two.profileA.value}/app-groups/${g.id.value}",
          tok,
        )
        roblox2 <- assignmentsOf(r.apps, tok, roblox)
        covered = (as: List[AppPolicyAssignment], p: ProfileId) =>
          as.find(_.profileId == p).get.coveredBy
      } yield assertTrue(
        covered(roblox1, two.profileA).contains(AppGroupRef(g.id, "Games")),
        // not attached to `other` => no coverage there (control on the same app)
        covered(roblox1, other).isEmpty,
        // a non-member on the attached profile is not covered (control on the same profile)
        covered(zoom1, two.profileA).isEmpty,
        covered(roblox2, two.profileA).isEmpty,
      )
    },
    test("weeklyMinutes persists; either limit implies time_limited; clearing both demotes") {
      for {
        _              <- cleanDb
        two            <- TestLayers.seedTwoHouseholds(macA, macB)
        r              <- rig
        auth           <- makeAuth
        tok            <- login(auth, two.adminA, two.password)
        (roblox, _, _) <- newApps
        weekly         <- putAssignment(
          r.apps,
          tok,
          roblox,
          two.profileA,
          """{"mode":"allowed","weeklyMinutes":120}""",
        )
        a1             <- assignmentsOf(r.apps, tok, roblox)
        daily          <- putAssignment(
          r.apps,
          tok,
          roblox,
          two.profileA,
          """{"mode":"allowed","dailyMinutes":30}""",
        )
        a2             <- assignmentsOf(r.apps, tok, roblox)
        cleared <- putAssignment(r.apps, tok, roblox, two.profileA, """{"mode":"time_limited"}""")
        a3      <- assignmentsOf(r.apps, tok, roblox)
        blocked <- putAssignment(
          r.apps,
          tok,
          roblox,
          two.profileA,
          """{"mode":"blocked","weeklyMinutes":60}""",
        )
        a4      <- assignmentsOf(r.apps, tok, roblox)
        zeroWk  <- putAssignment(
          r.apps,
          tok,
          roblox,
          two.profileA,
          """{"mode":"allowed","weeklyMinutes":0}""",
        )
      } yield assertTrue(
        weekly.status.isSuccess,
        a1.head.mode == AppMode.TimeLimited,
        a1.head.weeklyMinutes.contains(120),
        a1.head.dailyMinutes.isEmpty,
        daily.status.isSuccess,
        a2.head.mode == AppMode.TimeLimited,
        a2.head.dailyMinutes.contains(30),
        // a plain daily write replaces the row, so the weekly limit is gone (full replace).
        a2.head.weeklyMinutes.isEmpty,
        cleared.status.isSuccess,
        a3.head.mode == AppMode.Allowed,
        a3.head.dailyMinutes.isEmpty && a3.head.weeklyMinutes.isEmpty,
        blocked.status.isSuccess,
        a4.head.mode == AppMode.Blocked,
        zeroWk.status == Status.BadRequest,
      )
    },
    test("PATCH /api/household/settings accepts weekStartDay 1..7 and rejects the rest") {
      for {
        _    <- cleanDb
        two  <- TestLayers.seedTwoHouseholds(macA, macB)
        r    <- rig
        auth <- makeAuth
        tok  <- login(auth, two.adminA, two.password)
        get0 <- send(r.settings, Method.GET, "/api/household/settings", tok).flatMap(bodyOf)
        ok   <- send(
          r.settings,
          Method.PATCH,
          "/api/household/settings",
          tok,
          """{"weekStartDay":7}""",
        )
        get1 <- send(r.settings, Method.GET, "/api/household/settings", tok).flatMap(bodyOf)
        bad0 <- send(
          r.settings,
          Method.PATCH,
          "/api/household/settings",
          tok,
          """{"weekStartDay":0}""",
        )
        bad8 <- send(
          r.settings,
          Method.PATCH,
          "/api/household/settings",
          tok,
          """{"weekStartDay":8}""",
        )
        get2 <- send(r.settings, Method.GET, "/api/household/settings", tok).flatMap(bodyOf)
        tokB <- login(auth, two.adminB, two.password, Some(two.slugB))
        getB <- send(r.settings, Method.GET, "/api/household/settings", tokB).flatMap(bodyOf)
        dec = (s: String) => s.fromJson[HouseholdSettings].toOption.get.weekStartDay
      } yield assertTrue(
        dec(get0) == 1,
        ok.status.isSuccess,
        dec(get1) == 7,
        bad0.status == Status.BadRequest,
        bad8.status == Status.BadRequest,
        dec(get2) == 7,
        // other household untouched (control)
        dec(getB) == 1,
      )
    },
    test("every successful write invalidates the policy snapshot; a rejected write does not") {
      for {
        _                     <- cleanDb
        two                   <- TestLayers.seedTwoHouseholds(macA, macB)
        r                     <- rig
        auth                  <- makeAuth
        tok                   <- login(auth, two.adminA, two.password)
        (roblox, minecraf, _) <- newApps
        count = r.invalidations.get.map(_.count(_ == two.hhA))
        c0  <- count
        g   <- createGroup(r.groups, tok, "Games", List(roblox)).flatMap(decodeGroup)
        c1  <- count
        _   <- send(
          r.groups,
          Method.PATCH,
          s"/api/app-groups/${g.id.value}",
          tok,
          s"""{"memberAppIds":[${minecraf.value}]}""",
        )
        c2  <- count
        _   <- attach(r.groups, tok, two.profileA, g.id, """{"dailyMinutes":30}""")
        c3  <- count
        _   <- attach(r.groups, tok, two.profileA, g.id, """{"dailyMinutes":60}""")
        c4  <- count
        _   <- send(
          r.groups,
          Method.DELETE,
          s"/api/profiles/${two.profileA.value}/app-groups/${g.id.value}",
          tok,
        )
        c5  <- count
        _   <- putAssignment(
          r.apps,
          tok,
          roblox,
          two.profileA,
          """{"mode":"allowed","weeklyMinutes":60}""",
        )
        c6  <- count
        _   <- send(
          r.apps,
          Method.DELETE,
          s"/api/apps/${roblox.value}/policy/${two.profileA.value}",
          tok,
        )
        c7  <- count
        _   <- send(
          r.settings,
          Method.PATCH,
          "/api/household/settings",
          tok,
          """{"weekStartDay":2}""",
        )
        c8  <- count
        _   <- send(r.groups, Method.DELETE, s"/api/app-groups/${g.id.value}", tok)
        c9  <- count
        // rejected writes: no limit, unknown group, duplicate member
        _   <- attach(r.groups, tok, two.profileA, g.id, "{}")
        _   <- createGroup(r.groups, tok, "Games2", List(minecraf))
        c10 <- count
        _   <- createGroup(r.groups, tok, "Games3", List(minecraf)) // minecraf already in Games2
        c11 <- count
      } yield assertTrue(
        c0 == 0,
        c1 == 1,
        c2 == 2,
        c3 == 3,
        c4 == 4,
        c5 == 5,
        c6 == 6,
        c7 == 7,
        c8 == 8,
        c9 == 9,
        // The group-create "Games2" succeeded (+1), the two rejected ones did not.
        c10 == 10,
        c11 == 10,
      )
    },
    test("a child (read-only) user is rejected on every write and can still read") {
      for {
        _              <- cleanDb
        two            <- TestLayers.seedTwoHouseholds(macA, macB)
        r              <- rig
        auth           <- makeAuth
        userRepo       <- ZIO.service[UserRepo]
        upRepo         <- ZIO.service[UserProfileRepo]
        hash           <- auth.hashPassword("pass")
        uid            <- userRepo.create("kid", hash, "child")
        _              <- userRepo.clearMustChangePassword(uid)
        _              <- upRepo.setProfilesForUser(uid, List(two.profileA))
        tokKid         <- login(auth, "kid", "pass")
        tokAdmin       <- login(auth, two.adminA, two.password)
        (roblox, _, _) <- newApps
        g     <- createGroup(r.groups, tokAdmin, "Games", List(roblox)).flatMap(decodeGroup)
        post  <- createGroup(r.groups, tokKid, "Mine", Nil)
        patch <- send(
          r.groups,
          Method.PATCH,
          s"/api/app-groups/${g.id.value}",
          tokKid,
          """{"name":"x"}""",
        )
        del   <- send(r.groups, Method.DELETE, s"/api/app-groups/${g.id.value}", tokKid)
        put   <- attach(r.groups, tokKid, two.profileA, g.id, """{"dailyMinutes":30}""")
        read  <- listGroups(r.groups, tokKid)
        after <- listGroups(r.groups, tokAdmin)
        att   <- attachments(r.groups, tokAdmin, two.profileA)
      } yield assertTrue(
        post.status == Status.Forbidden,
        patch.status == Status.Forbidden,
        del.status == Status.Forbidden,
        put.status == Status.Forbidden,
        read.map(_.name) == List("Games"),
        after.map(_.name) == List("Games"),
        att.isEmpty,
      )
    },
    test("writes drop the profile's current-week settled usage rows in the same transaction") {
      // The fixture clock is 2000-01-01, so `recent` is inside the week floor and `old` is not.
      val recent = LocalDate.of(2000, 1, 1)
      val old    = LocalDate.of(1990, 1, 1)
      def seed(xa: Transactor[Task], p: ProfileId, a: AppId, g: AppGroupId, hh: HouseholdId) =
        ZIO.foreachDiscard(List(recent, old)) { d =>
          (sql"""INSERT INTO app_used_daily(profile_id, app_id, date, engaged_seconds, rolled_through)
                   VALUES ($p, $a, $d, 60, NOW()) ON CONFLICT DO NOTHING""".update.run *>
            sql"""INSERT INTO app_group_used_daily(household_id, profile_id, group_id, date, engaged_seconds, rolled_through)
                   VALUES ($hh, $p, $g, $d, 60, NOW()) ON CONFLICT DO NOTHING""".update.run *>
            sql"""INSERT INTO time_used_daily(profile_id, date, used_seconds, rolled_through)
                   VALUES ($p, $d, 60, NOW())
                   ON CONFLICT (profile_id, date) DO NOTHING""".update.run).transact(xa)
        }
      def counts(xa: Transactor[Task], p: ProfileId)                                         =
        (for {
          appRecent <- sql"SELECT count(*) FROM app_used_daily WHERE profile_id=$p AND date=$recent"
            .query[Int]
            .unique
          appOld    <- sql"SELECT count(*) FROM app_used_daily WHERE profile_id=$p AND date=$old"
            .query[Int]
            .unique
          grpRecent <-
            sql"SELECT count(*) FROM app_group_used_daily WHERE profile_id=$p AND date=$recent"
              .query[Int]
              .unique
          timeRecent <-
            sql"SELECT count(*) FROM time_used_daily WHERE profile_id=$p AND date=$recent"
              .query[Int]
              .unique
          timeOld    <- sql"SELECT count(*) FROM time_used_daily WHERE profile_id=$p AND date=$old"
            .query[Int]
            .unique
        } yield (appRecent, appOld, grpRecent, timeRecent, timeOld)).transact(xa)
      for {
        _              <- cleanDb
        two            <- TestLayers.seedTwoHouseholds(macA, macB)
        xa             <- ZIO.service[Transactor[Task]]
        r              <- rig
        auth           <- makeAuth
        tok            <- login(auth, two.adminA, two.password)
        (roblox, _, _) <- newApps
        g              <- createGroup(r.groups, tok, "Games", List(roblox)).flatMap(decodeGroup)
        _              <- seed(xa, two.profileA, roblox, g.id, two.hhA)
        seeded         <- counts(xa, two.profileA)
        // A limit edit changes no usage: nothing is dropped.
        _              <- attach(
          r.groups,
          tok,
          two.profileA,
          g.id,
          """{"dailyMinutes":30,"exemptFromDaily":true}""",
        )
        afterAtt       <- counts(xa, two.profileA)
        _              <- seed(xa, two.profileA, roblox, g.id, two.hhA)
        _              <- attach(
          r.groups,
          tok,
          two.profileA,
          g.id,
          """{"dailyMinutes":45,"exemptFromDaily":true}""",
        )
        afterLim       <- counts(xa, two.profileA)
        // An exempt-flag edit drops only the profile daily total (time_used_daily).
        _              <- attach(
          r.groups,
          tok,
          two.profileA,
          g.id,
          """{"dailyMinutes":45,"exemptFromDaily":false}""",
        )
        afterEx        <- counts(xa, two.profileA)
        _              <- seed(xa, two.profileA, roblox, g.id, two.hhA)
        // Detaching drops all three kinds, current week only.
        _              <- send(
          r.groups,
          Method.DELETE,
          s"/api/profiles/${two.profileA.value}/app-groups/${g.id.value}",
          tok,
        )
        afterDet       <- counts(xa, two.profileA)
      } yield assertTrue(
        // liveness anchor: every row seeded
        seeded == ((1, 1, 1, 1, 1)),
        // attach (create) is a disposition change: recent rows dropped, the old control kept.
        afterAtt == ((0, 1, 0, 0, 1)),
        // after reseed, a limit-only edit (30 -> 45) drops nothing.
        afterLim == ((1, 1, 1, 1, 1)),
        // exempt flip: only time_used_daily (recent) goes.
        afterEx == ((1, 1, 1, 0, 1)),
        afterDet == ((0, 1, 0, 0, 1)),
      )
    },
  )
}
