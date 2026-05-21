package wifihaven.api.feature

import wifihaven.api.db.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import doobie.Transactor
import zio.*
import zio.test.*

object AppRepoSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Transactor[Task]] {

  override val bootstrap = TestDatabase.layer

  private def cleanDb = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
    TestDatabase.cleanAndMigrate.provide(ZLayer.succeed(pg)),
  )

  private val yt   = Hostname.unsafe("youtube.com")
  private val ytg  = Hostname.unsafe("ytimg.com")
  private val gvid = Hostname.unsafe("googlevideo.com")

  def spec = suite("AppRepo")(
    test("insertApp + getApp round-trips name/slug/template/icon") {
      for {
        _   <- cleanDb
        r   <- ZIO.service[AppRepo]
        id  <- r.insertApp(AppInsert("YouTube", "youtube", Some("youtube"), Some("📺")))
        got <- r.getApp(id)
      } yield assertTrue(
        got.exists(a =>
          a.name == "YouTube" && a.slug == "youtube" &&
            a.templateId.contains("youtube") && a.icon.contains("📺"),
        ),
      )
    },
    test("listApps returns all rows ordered by id") {
      for {
        _   <- cleanDb
        r   <- ZIO.service[AppRepo]
        a   <- r.insertApp(AppInsert("YouTube", "youtube"))
        b   <- r.insertApp(AppInsert("TikTok", "tiktok"))
        all <- r.listApps
      } yield assertTrue(all.map(_.id) == List(a, b))
    },
    test("slug uniqueness is enforced") {
      for {
        _   <- cleanDb
        r   <- ZIO.service[AppRepo]
        _   <- r.insertApp(AppInsert("YouTube", "youtube"))
        dup <- r.insertApp(AppInsert("YouTube 2", "youtube")).exit
      } yield assertTrue(dup.isFailure)
    },
    test("updateApp mutates name/slug/template/icon") {
      for {
        _   <- cleanDb
        r   <- ZIO.service[AppRepo]
        id  <- r.insertApp(AppInsert("YouTube", "youtube"))
        _   <- r.updateApp(id, AppUpdate("YT", "yt", Some("youtube"), Some("🎥")))
        got <- r.getApp(id)
      } yield assertTrue(
        got.exists(a =>
          a.name == "YT" && a.slug == "yt" && a.templateId.contains("youtube") &&
            a.icon.contains("🎥"),
        ),
      )
    },
    test("setHosts replaces the host set; deleteApp cascades hosts and assignments") {
      for {
        _          <- cleanDb
        appR       <- ZIO.service[AppRepo]
        profR      <- ZIO.service[ProfileRepo]
        pid        <- profR.create("Kids", Nil)
        id         <- appR.insertApp(AppInsert("YouTube", "youtube"))
        _          <- appR.setHosts(id, List(yt, ytg))
        h1         <- appR.listHosts(id)
        _          <- appR.setHosts(id, List(yt, gvid))
        h2         <- appR.listHosts(id)
        _          <- appR.upsertAssignment(AssignmentUpsert(id, pid, AppMode.Blocked, None))
        _          <- appR.deleteApp(id)
        gone       <- appR.getApp(id)
        hostsAfter <- appR.listHosts(id)
        asgAfter   <- appR.listAssignmentsByProfile(pid)
      } yield assertTrue(h1.toSet == Set(yt, ytg)) &&
        assertTrue(h2.toSet == Set(yt, gvid)) &&
        assertTrue(gone.isEmpty) &&
        assertTrue(hostsAfter.isEmpty) &&
        assertTrue(asgAfter.isEmpty)
    },
    test("upsertAssignment is idempotent on (app_id, profile_id) and updates fields") {
      for {
        _     <- cleanDb
        appR  <- ZIO.service[AppRepo]
        profR <- ZIO.service[ProfileRepo]
        pid   <- profR.create("Kids", Nil)
        id    <- appR.insertApp(AppInsert("YouTube", "youtube"))
        a1    <- appR.upsertAssignment(
          AssignmentUpsert(id, pid, AppMode.TimeLimited, Some(30), exemptFromDaily = true),
        )
        a2    <- appR.upsertAssignment(
          AssignmentUpsert(id, pid, AppMode.Blocked, None, exemptFromDaily = false),
        )
        rows  <- appR.listAssignmentsByProfile(pid)
      } yield assertTrue(a1 == a2) && assertTrue(rows.size == 1) &&
        assertTrue(rows.head.mode == AppMode.Blocked) &&
        assertTrue(rows.head.dailyMinutes.isEmpty) &&
        assertTrue(!rows.head.exemptFromDaily)
    },
    test("deleteAssignment removes only the targeted (app, profile) row") {
      for {
        _      <- cleanDb
        appR   <- ZIO.service[AppRepo]
        profR  <- ZIO.service[ProfileRepo]
        kids   <- profR.create("Kids", Nil)
        adults <- profR.create("Adults", Nil)
        id     <- appR.insertApp(AppInsert("YouTube", "youtube"))
        _      <- appR.upsertAssignment(AssignmentUpsert(id, kids, AppMode.Blocked, None))
        _      <- appR.upsertAssignment(AssignmentUpsert(id, adults, AppMode.Allowed, None))
        _      <- appR.deleteAssignment(id, kids)
        byApp  <- appR.listAssignmentsByApp(id)
      } yield assertTrue(byApp.size == 1) &&
        assertTrue(byApp.head.profileId == adults) &&
        assertTrue(byApp.head.mode == AppMode.Allowed)
    },
    test("deleting a profile cascades to its app assignments") {
      for {
        _     <- cleanDb
        appR  <- ZIO.service[AppRepo]
        profR <- ZIO.service[ProfileRepo]
        pid   <- profR.create("Kids", Nil)
        id    <- appR.insertApp(AppInsert("YouTube", "youtube"))
        _     <- appR.upsertAssignment(AssignmentUpsert(id, pid, AppMode.Blocked, None))
        _     <- profR.delete(pid)
        byApp <- appR.listAssignmentsByApp(id)
      } yield assertTrue(byApp.isEmpty)
    },
    test("Hostname.canonicalize strips a leading *. then parses") {
      val a = Hostname.canonicalize("*.youtube.com")
      val b = Hostname.canonicalize("youtube.com")
      val c = Hostname.canonicalize("*.NotAHost!!")
      assertTrue(a == Right(Hostname.unsafe("youtube.com"))) &&
      assertTrue(b == Right(Hostname.unsafe("youtube.com"))) &&
      assertTrue(c.isLeft)
    },
  ) @@ TestAspect.sequential
}
