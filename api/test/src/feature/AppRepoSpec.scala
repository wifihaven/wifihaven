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

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val youtube = Hostname.unsafe("youtube.com")
  private val ytimg   = Hostname.unsafe("ytimg.com")
  private val gvideo  = Hostname.unsafe("googlevideo.com")

  def spec = suite("AppRepo")(
    test("create + findById round-trips") {
      for {
        _    <- cleanDb
        repo <- ZIO.service[AppRepo]
        id   <- repo.create(
          "YouTube",
          "youtube",
          Some(AppTemplateId.unsafe("youtube")),
          Some("📺"),
        )
        row  <- repo.findById(id)
      } yield assertTrue(row.exists(_.name == "YouTube")) &&
        assertTrue(row.exists(_.slug == "youtube")) &&
        assertTrue(row.exists(_.templateId.contains(AppTemplateId.unsafe("youtube")))) &&
        assertTrue(row.exists(_.icon.contains("📺")))
    },
    test("findBySlug returns the row") {
      for {
        _    <- cleanDb
        repo <- ZIO.service[AppRepo]
        id   <- repo.create("TikTok", "tiktok", None, None)
        row  <- repo.findBySlug("tiktok")
      } yield assertTrue(row.exists(_.id == id)) &&
        assertTrue(row.exists(_.templateId.isEmpty)) &&
        assertTrue(row.exists(_.icon.isEmpty))
    },
    test("duplicate slug is rejected") {
      for {
        _      <- cleanDb
        repo   <- ZIO.service[AppRepo]
        _      <- repo.create("YouTube", "youtube", None, None)
        result <- repo.create("YouTube 2", "youtube", None, None).exit
      } yield assertTrue(result.isFailure)
    },
    test("update changes name/icon/templateId") {
      for {
        _      <- cleanDb
        repo   <- ZIO.service[AppRepo]
        id     <- repo.create("YouTube", "youtube", None, None)
        before <- repo.findById(id)
        _      <- repo.update(
          before.get.copy(
            name = "YT",
            icon = Some("🎥"),
            templateId = Some(AppTemplateId.unsafe("youtube")),
          ),
        )
        row    <- repo.findById(id)
      } yield assertTrue(row.exists(_.name == "YT")) &&
        assertTrue(row.exists(_.icon.contains("🎥"))) &&
        assertTrue(row.exists(_.templateId.contains(AppTemplateId.unsafe("youtube"))))
    },
    test("setHosts + getHosts round-trips and replaces on rerun") {
      for {
        _    <- cleanDb
        repo <- ZIO.service[AppRepo]
        id   <- repo.create("YouTube", "youtube", None, None)
        _    <- repo.setHosts(id, List(youtube, ytimg))
        a    <- repo.getHosts(id)
        _    <- repo.setHosts(id, List(youtube, gvideo))
        b    <- repo.getHosts(id)
      } yield assertTrue(a.toSet == Set(youtube, ytimg)) &&
        assertTrue(b.toSet == Set(youtube, gvideo))
    },
    test("#1896 setHostEntries persists the shared flag; setHosts defaults shared=false") {
      for {
        _       <- cleanDb
        repo    <- ZIO.service[AppRepo]
        id      <- repo.create("YouTube", "youtube", None, None)
        _       <- repo.setHostEntries(
          id,
          List(AppHostEntry(youtube, shared = false), AppHostEntry(ytimg, shared = true)),
        )
        entries <- repo.getHostEntries(id)
        // setHosts is the plain (all-distinctive) overload — every row defaults shared=false.
        _       <- repo.setHosts(id, List(youtube, gvideo))
        plain   <- repo.getHostEntries(id)
      } yield assertTrue(
        entries.toSet == Set(AppHostEntry(youtube, false), AppHostEntry(ytimg, true)),
      ) && assertTrue(plain.nonEmpty && plain.forall(!_.shared))
    },
    test("upsertAssignment inserts and overwrites on same (app, profile)") {
      for {
        _     <- cleanDb
        appR  <- ZIO.service[AppRepo]
        pRepo <- ZIO.service[ProfileRepo]
        pid   <- pRepo.create("Kids", Nil)
        id    <- appR.create("YouTube", "youtube", None, None)
        a1    <- appR.upsertAssignment(id, pid, AppMode.Blocked, None, true)
        rows1 <- appR.listAssignmentsForApp(id)
        a2    <- appR.upsertAssignment(id, pid, AppMode.TimeLimited, Some(30), false)
        rows2 <- appR.listAssignmentsForApp(id)
      } yield assertTrue(a1 == a2) &&
        assertTrue(rows1.length == 1 && rows1.head.mode == AppMode.Blocked) &&
        assertTrue(rows2.length == 1 && rows2.head.mode == AppMode.TimeLimited) &&
        assertTrue(rows2.head.dailyMinutes.contains(30)) &&
        assertTrue(!rows2.head.exemptFromDaily)
    },
    test("listAssignmentsForProfile filters by profile") {
      for {
        _     <- cleanDb
        appR  <- ZIO.service[AppRepo]
        pRepo <- ZIO.service[ProfileRepo]
        kids  <- pRepo.create("Kids", Nil)
        adts  <- pRepo.create("Adults", Nil)
        yt    <- appR.create("YouTube", "youtube", None, None)
        tt    <- appR.create("TikTok", "tiktok", None, None)
        _     <- appR.upsertAssignment(yt, kids, AppMode.Blocked, None, true)
        _     <- appR.upsertAssignment(tt, kids, AppMode.Blocked, None, true)
        _     <- appR.upsertAssignment(yt, adts, AppMode.Allowed, None, true)
        k     <- appR.listAssignmentsForProfile(kids)
        a     <- appR.listAssignmentsForProfile(adts)
      } yield assertTrue(k.length == 2) && assertTrue(a.length == 1)
    },
    test("delete cascades to hosts and assignments") {
      for {
        _     <- cleanDb
        appR  <- ZIO.service[AppRepo]
        pRepo <- ZIO.service[ProfileRepo]
        pid   <- pRepo.create("Kids", Nil)
        id    <- appR.create("YouTube", "youtube", None, None)
        _     <- appR.setHosts(id, List(youtube, ytimg))
        _     <- appR.upsertAssignment(id, pid, AppMode.Blocked, None, true)
        _     <- appR.delete(id)
        gone  <- appR.findById(id)
        hosts <- appR.getHosts(id)
        asgn  <- appR.listAssignmentsForApp(id)
      } yield assertTrue(gone.isEmpty) && assertTrue(hosts.isEmpty) && assertTrue(asgn.isEmpty)
    },
    test("deleting a profile cascades to assignments") {
      for {
        _     <- cleanDb
        appR  <- ZIO.service[AppRepo]
        pRepo <- ZIO.service[ProfileRepo]
        pid   <- pRepo.create("Kids", Nil)
        id    <- appR.create("YouTube", "youtube", None, None)
        _     <- appR.upsertAssignment(id, pid, AppMode.Blocked, None, true)
        _     <- pRepo.delete(pid)
        asgn  <- appR.listAssignmentsForApp(id)
      } yield assertTrue(asgn.isEmpty)
    },
    test("deleteAssignment removes only the targeted (app, profile) row") {
      for {
        _     <- cleanDb
        appR  <- ZIO.service[AppRepo]
        pRepo <- ZIO.service[ProfileRepo]
        kids  <- pRepo.create("Kids", Nil)
        adts  <- pRepo.create("Adults", Nil)
        id    <- appR.create("YouTube", "youtube", None, None)
        _     <- appR.upsertAssignment(id, kids, AppMode.Blocked, None, true)
        _     <- appR.upsertAssignment(id, adts, AppMode.Allowed, None, true)
        _     <- appR.deleteAssignment(id, kids)
        rows  <- appR.listAssignmentsForApp(id)
      } yield assertTrue(rows.length == 1 && rows.head.profileId == adts)
    },
    test("CHECK constraint rejects unknown mode values") {
      for {
        _      <- cleanDb
        xa     <- ZIO.service[Transactor[Task]]
        pRepo  <- ZIO.service[ProfileRepo]
        appR   <- ZIO.service[AppRepo]
        pid    <- pRepo.create("Kids", Nil)
        id     <- appR.create("YouTube", "youtube", None, None)
        result <- {
          import doobie.implicits.*
          import zio.interop.catz.*
          import wifihaven.api.db.TypeMeta.given
          sql"INSERT INTO app_policy_assignments(app_id, profile_id, mode) VALUES ($id, $pid, 'bogus')".update.run
            .transact(xa)
            .exit
        }
      } yield assertTrue(result.isFailure)
    },
    test("listAll returns all apps ordered by id") {
      for {
        _    <- cleanDb
        repo <- ZIO.service[AppRepo]
        a    <- repo.create("YouTube", "youtube", None, None)
        b    <- repo.create("TikTok", "tiktok", None, None)
        rows <- repo.listAll
      } yield assertTrue(rows.map(_.id) == List(a, b))
    },
  ) @@ TestAspect.sequential
}
