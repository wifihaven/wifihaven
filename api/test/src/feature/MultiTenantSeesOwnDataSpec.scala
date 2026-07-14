package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.api.policy.TimeStatusServiceLive
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.types.*
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

import java.time.{LocalDate, ZoneOffset}

/**
 * #2176 (multi-tenant hardening, epic #2085/#622) — the POSITIVE half of the isolation gate.
 *
 * [[MultiTenantIsolationSpec]] pins the NEGATIVE invariant (household-A never reads household-B's
 * rows). But an equally-load-bearing property was NOT pinned by the D/E scoping waves: that a
 * household-scoped read still returns a household's OWN data — non-empty, correct. A predicate that
 * resolves to the WRONG or an empty household passes every isolation pin while silently returning
 * nothing. That is exactly the admin-0m usage regression
 * ([#2167](https://github.com/wifihaven/wifihaven/issues/2167)): the `admin`/`adult` "see-all"
 * time-status view came back `0m` for every profile — scoped, isolated, and broken. (Its root cause
 * turned out to be read-amplification, not the predicate — but the class of bug the missing pin
 * would have caught is real, and this spec closes it.)
 *
 * Every test here SEEDS household A with real data (a daily limit, presence minutes, an app
 * assignment, users/devices/routers) and asserts the caller reads it BACK non-empty — with the
 * cross-household read still empty, so each positive pin is paired with its negative sibling
 * (design §7, the convention `docs/design/multi-tenant-isolation.md` §7 now documents). Embedded
 * Postgres, no repo mocks, Clock injected — the SQL predicate itself is under test.
 *
 * Coverage — one positive pin per E-scoped read (design §2 gap 4):
 *   - `GET /api/time/status` (the admin-0m shape): usedMins/dailyLimitMins/appUsage non-empty for
 *     the see-all admin — exercises profile/device/timeLimit/appTimeLimit `…ForHousehold` at once.
 *   - `TimeLimitRepo.listAllForHousehold` / `AppTimeLimitRepo.listAllForHousehold` — the transitive
 *     `→ profiles` joins return the household's own rows (repo-level, so the join is what's
 *     tested).
 *   - `DeviceRepo` / `ProfileRepo` / `UserRepo` / `RouterRepo.listAllForHousehold` — own rows back.
 *   - Backfill integrity: no scoped table has a NULL or dangling `household_id`, and the seed admin
 *     resolves to a real household (guards the V65/V66 backfill the whole model assumes).
 *
 * NOT covered here (tracked elsewhere, deliberately): the still-unscoped usage/analytics/push reads
 * (`UsageRoutes`, `DashboardNowRoutes`, `SpaPush`) and `named_schedules` —
 * [#2126](https://github.com/wifihaven/wifihaven/issues/2126) /
 * [#2120](https://github.com/wifihaven/wifihaven/issues/2120). [[MultiTenantScopedReadGuardSpec]]
 * fails the build if a NEW unscoped route read appears outside that tracked set.
 */
object MultiTenantSeesOwnDataSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val macA = MacAddress.unsafe("aa:bb:cc:00:00:0a")
  private val macB = MacAddress.unsafe("aa:bb:cc:00:00:0b")

  private val jwt = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)

  // household-local "today" per the injected clock (Mon 14:00 UTC; default reset tz is UTC).
  private val today: LocalDate = TestClock.schoolDayAfternoon.toLocalDate

  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      hr    <- ZIO.service[HouseholdRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwt, clock, hr): AuthService

  private def login(
      auth: AuthService,
      user: String,
      pw: String,
      slug: Option[String] = None,
  ): Task[String] =
    auth
      .login(slug.fold(user)(s => s"$s/$user"), pw)
      .mapError(e => new RuntimeException(s"login failed: $e"))
      .map(_.token.value)

  private def getJson(
      routes: Routes[Any, Response],
      path: String,
      token: String,
  ): Task[(Status, String)] =
    for {
      resp <- routes.runZIO(
        Request.get(URL.decode(path).toOption.get).addHeader(Header.Authorization.Bearer(token)),
      )
      body <- resp.body.asString
    } yield (resp.status, body)

  /**
   * Seed `minutes/5` non-overlapping 5-min traffic_reports presence buckets for `mac`, starting at
   * 09:00 UTC on `today` — all before the 14:00 clock instant, all on the household-local day, so
   * the daily view credits them in full (bucket-presence = wall-clock minutes).
   */
  private def seedPresence(
      tr: TrafficReportRepo,
      routerId: RouterId,
      mac: MacAddress,
      hostname: String,
      minutes: Int,
  ): Task[Unit] = {
    val base    = today.atStartOfDay(ZoneOffset.UTC).toInstant.plusSeconds(9 * 3600L) // 09:00 UTC
    val buckets = minutes / 5
    val inserts = (0 until buckets).map { i =>
      val start = base.plusSeconds(i * 300L)
      TrafficReportInsert(
        routerId,
        mac,
        None,
        HostId.Fqdn(Hostname.unsafe(hostname)),
        today,
        start,
        start.plusSeconds(300),
        300,
        // Above the household heartbeat-filter byte threshold (default 10 KiB) so it counts as real
        // activity, not idle noise — a 0-byte bucket is dropped by the heartbeat filter.
        25_000L,
        25_000L,
      )
    }.toList
    tr.insertBatch(inserts).unit
  }

  private def statuses(body: String): List[ProfileTimeStatus] =
    body.fromJson[List[ProfileTimeStatus]].getOrElse(Nil)

  def spec = suite("Multi-tenant sees-own-data — the positive half of the gate (#2176)")(
    // ── The admin-0m shape: the see-all admin reads its OWN non-zero usage ──────
    test("GET /api/time/status returns household A's OWN non-zero usedMins to the admin") {
      for {
        _    <- cleanDb
        two  <- TestLayers.seedTwoHouseholds(macA, macB)
        pr   <- ZIO.service[ProfileRepo]
        dr   <- ZIO.service[DeviceRepo]
        tlr  <- ZIO.service[TimeLimitRepo]
        atlr <- ZIO.service[AppTimeLimitRepo]
        tr   <- ZIO.service[TrafficReportRepo]
        er   <- ZIO.service[TimeExtensionRepo]
        up   <- ZIO.service[UserProfileRepo]
        hsr  <- ZIO.service[HouseholdSettingsRepo]
        ar   <- ZIO.service[AppRepo]
        clk  <- ZIO.service[Clock]
        // Seed household A with a 60-min daily cap, 40 min of presence, and a TimeLimited app.
        _    <- tlr.upsert(two.profileA, 60)
        _    <- seedPresence(tr, two.routerIdA, macA, "youtube.com", 40)
        _    <- TestLayers.seedAppAssignment(
          ar,
          two.profileA,
          "games.example.com",
          AppMode.TimeLimited,
          dailyMinutes = Some(30),
          exemptFromDaily = false,
        )
        tss = new TimeStatusServiceLive(pr, tlr, atlr, dr, tr, er)
        auth   <- makeAuth
        tokenA <- login(auth, two.adminA, two.password)
        tokenB <- login(auth, two.adminB, two.password, Some(two.slugB))
        routes = TimeRoutes.routes(auth, dr, tlr, atlr, tr, er, pr, up, hsr, tss, clk)
        (sA, bodyA) <- getJson(routes, "/api/time/status", tokenA)
        (sB, bodyB) <- getJson(routes, "/api/time/status", tokenB)
        stA = statuses(bodyA).find(_.profileId == two.profileA)
        stB = statuses(bodyB).find(_.profileId == two.profileB)
      } yield assertTrue(sA == Status.Ok, sB == Status.Ok) &&
        // household A's admin sees its OWN data: the 40 min presence, the 60 cap, the app row.
        assertTrue(stA.isDefined) &&
        assertTrue(stA.exists(_.usedMins == 40)) &&
        assertTrue(stA.exists(_.dailyLimitMins.contains(60))) &&
        assertTrue(stA.exists(_.remainingMins.contains(20))) &&
        assertTrue(stA.exists(_.appUsage.nonEmpty)) &&
        // …and never household B's profile.
        assertTrue(!statuses(bodyA).exists(_.profileId == two.profileB)) &&
        // household B (no seeded traffic) sees its own profile at a genuine loaded-zero, not A's.
        assertTrue(stB.isDefined, stB.exists(_.usedMins == 0)) &&
        assertTrue(!statuses(bodyB).exists(_.profileId == two.profileA))
    },
    // ── Transitive `→ profiles` join reads return OWN rows (repo-level) ─────────
    test("TimeLimitRepo.listAllForHousehold returns A's own limit; B's is empty") {
      for {
        _      <- cleanDb
        two    <- TestLayers.seedTwoHouseholds(macA, macB)
        tlr    <- ZIO.service[TimeLimitRepo]
        _      <- tlr.upsert(two.profileA, 45)
        ownA   <- tlr.listAllForHousehold(two.hhA)
        emptyB <- tlr.listAllForHousehold(two.hhB)
      } yield
      // The join through `profiles` surfaces A's row…
      assertTrue(ownA.exists(l => l.profileId == two.profileA && l.dailyMinutes == 45)) &&
        // …and B (which has no time limit) sees none of A's.
        assertTrue(!emptyB.exists(_.profileId == two.profileA))
    },
    test("AppTimeLimitRepo.listAllForHousehold returns A's own app rows; B's is empty") {
      for {
        _      <- cleanDb
        two    <- TestLayers.seedTwoHouseholds(macA, macB)
        atlr   <- ZIO.service[AppTimeLimitRepo]
        ar     <- ZIO.service[AppRepo]
        _      <- TestLayers.seedAppAssignment(
          ar,
          two.profileA,
          "chat.example.com",
          AppMode.Blocked,
        )
        ownA   <- atlr.listAllForHousehold(two.hhA)
        emptyB <- atlr.listAllForHousehold(two.hhB)
      } yield assertTrue(ownA.exists(_.profileId == two.profileA)) &&
        assertTrue(!emptyB.exists(_.profileId == two.profileA))
    },
    // ── Root-table reads return OWN rows (paired with isolation) ────────────────
    test("Device / Profile / User / Router listAllForHousehold return each household's own rows") {
      for {
        _     <- cleanDb
        two   <- TestLayers.seedTwoHouseholds(macA, macB)
        dr    <- ZIO.service[DeviceRepo]
        pr    <- ZIO.service[ProfileRepo]
        ur    <- ZIO.service[UserRepo]
        rr    <- ZIO.service[RouterRepo]
        devA  <- dr.listAllForHousehold(two.hhA)
        devB  <- dr.listAllForHousehold(two.hhB)
        profA <- pr.listAllForHousehold(two.hhA)
        usrA  <- ur.listAllForHousehold(two.hhA)
        usrB  <- ur.listAllForHousehold(two.hhB)
        rtA   <- rr.listAllForHousehold(two.hhA)
        rtB   <- rr.listAllForHousehold(two.hhB)
      } yield
      // devices: A sees its own MAC and not B's, and vice-versa (both non-empty).
      assertTrue(devA.exists(_.mac == macA), !devA.exists(_.mac == macB)) &&
        assertTrue(devB.exists(_.mac == macB), !devB.exists(_.mac == macA)) &&
        // profiles / users / routers: each household reads back its own seeded row, non-empty.
        assertTrue(profA.exists(_.id == two.profileA)) &&
        assertTrue(usrA.exists(_.username == "admin"), !usrA.exists(_.username == "adminB")) &&
        assertTrue(usrB.exists(_.username == "adminB"), !usrB.exists(_.username == "admin")) &&
        assertTrue(rtA.nonEmpty, rtB.nonEmpty) &&
        assertTrue((rtA.map(_.id).toSet & rtB.map(_.id).toSet).isEmpty)
    },
    // ── Backfill integrity — the model assumes V65/V66 populated household_id ───
    test(
      "no scoped table has a NULL or dangling household_id, and the admin resolves to a household",
    ) {
      // The admin-0m bug hypothesis was a backfill hole (an admin with NULL/mismatched
      // household_id). V65 added these columns NOT NULL DEFAULT 1 with an FK, so a NULL or dangling
      // value is structurally impossible today — this pin locks that in against a future migration
      // that adds a scoped table without the same guarantee.
      val scopedTables = List(
        "users",
        "profiles",
        "devices",
        "routers",
        "household_settings",
        "time_usage",
        "time_extensions",
      )
      for {
        _             <- cleanDb
        two           <- TestLayers.seedTwoHouseholds(macA, macB)
        xa            <- ZIO.service[Transactor[Task]]
        // Give every scoped table at least one row so the invariant is non-vacuous.
        tlr           <- ZIO.service[TimeLimitRepo]
        tr            <- ZIO.service[TrafficReportRepo]
        tu            <- ZIO.service[TimeUsageRepo]
        er            <- ZIO.service[TimeExtensionRepo]
        _             <- tlr.upsert(two.profileA, 60)
        _             <- seedPresence(tr, two.routerIdA, macA, "youtube.com", 10)
        _             <- tu.incrementSecondsAndBytes(
          macA,
          HostId.Fqdn(Hostname.unsafe("youtube.com")),
          today,
          300,
          0L,
          0L,
        )
        _             <- er.grant(macA, today, 15, "admin", None)
        bad           <- ZIO.foreach(scopedTables) { t =>
          val q = Fragment.const(
            s"SELECT COUNT(*) FROM $t WHERE household_id IS NULL " +
              s"OR household_id NOT IN (SELECT id FROM households)",
          )
          q.query[Long].unique.transact(xa).map(t -> _)
        }
        adminHh       <- sql"SELECT household_id FROM users WHERE username='admin'"
          .query[HouseholdId]
          .unique
          .transact(xa)
        adminHhExists <- sql"SELECT COUNT(*) FROM households WHERE id=$adminHh"
          .query[Long]
          .unique
          .transact(xa)
      } yield assertTrue(bad.forall(_._2 == 0L)) &&
        assertTrue(adminHh == HouseholdId.Default, adminHhExists == 1L)
    },
  ) @@ TestAspect.sequential
}
