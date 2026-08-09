package wifihaven.testinfra

import doobie.Transactor
import wifihaven.api.db.*
import wifihaven.shared.HouseholdSettings
import wifihaven.shared.types.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.flywaydb.core.Flyway
import zio.*
import zio.interop.catz.*

/**
 * Spins up a real embedded Postgres once per JVM, creates a per-spec Postgres database by cloning a
 * pre-migrated template, and provides a `Transactor` wired to that per-spec database.
 *
 * ==Why the template-DB pattern (#1188)==
 *
 * The naïve approach — run Flyway from scratch in `cleanAndMigrate` between every test — dominates
 * the test-suite wall time: each `DROP SCHEMA + Flyway migrate` is ~400–500 ms with 44 migrations,
 * and the suite calls `cleanDb` once per test. For ~250 tests that's roughly two minutes of pure
 * migration work.
 *
 * Instead, on first use this object boots one shared `EmbeddedPostgres` for the JVM, creates a
 * `wh_template` database in it, runs the production Flyway migrations into `wh_template` exactly
 * once, and applies the standard test-only seed tweaks (household_settings row, admin
 * must_change_password=false) into `wh_template`.
 *
 * Each spec's `TestDatabase.layer` then allocates a fresh per-spec database with a unique name
 * (`wh_test_<n>`) by issuing `CREATE DATABASE … TEMPLATE wh_template`, which Postgres satisfies
 * with a file-level copy — no Flyway runs at all per spec or per test. `cleanAndMigrate` resets the
 * per-spec database the same way (drop + re-clone) so each test starts from the template's
 * post-Flyway, post-seed state.
 *
 * Cross-spec isolation comes from the unique database name: even when multiple specs run in the
 * same JVM and call `cleanAndMigrate` concurrently, they each operate on their own DB.
 */
object TestDatabase {

  private val TemplateDbName = "wh_template"

  // JVM-wide singleton: started once, shared across all ZIOSpec bootstrap layers in this JVM.
  // We don't use `lazy val` here — Scala 3 lazy vals can let two racing threads both run the
  // initializer, and EmbeddedPostgres.prepareBinaries takes an internal file lock that throws
  // OverlappingFileLockException on the second concurrent call. Explicit double-checked locking
  // serialises init. The template DB + Flyway migrate also happens inside this critical section
  // so it runs exactly once per JVM.
  private val pgLock                                 = new Object
  @volatile private var pgInstance: EmbeddedPostgres = null
  private def sharedPg: EmbeddedPostgres             = {
    val cached = pgInstance
    if cached != null then cached
    else
      pgLock.synchronized {
        if pgInstance == null then {
          val pg = EmbeddedPostgres.start()
          initialiseTemplate(pg)
          pgInstance = pg
        }
        pgInstance
      }
  }

  /**
   * Create the template DB, migrate it, and apply standard test seed tweaks. Called exactly once
   * per JVM, under `pgLock`. After this returns, `wh_template` is the source of truth that every
   * per-spec DB clones from.
   */
  private def initialiseTemplate(pg: EmbeddedPostgres): Unit = {
    execOnPostgresDb(pg, s"""CREATE DATABASE "$TemplateDbName"""")
    val templateDs = pg.getDatabase("postgres", TemplateDbName)
    Flyway
      .configure()
      .dataSource(templateDs)
      .locations("classpath:db/migration")
      .baselineOnMigrate(true)
      .load()
      .migrate()
    val conn       = templateDs.getConnection
    try {
      val st = conn.createStatement()
      // #334: replicate Main.ensureDefault so PolicyService.snapshot/decide can read
      // household_settings during tests. Use UTC so DST doesn't perturb existing expectations.
      // #2643: `block_encrypted_dns` is stamped from the ONE new-household default constant,
      // exactly as `ensureDefault` does — the template household is a FRESH install, so it must
      // start where a real fresh install starts. Left implicit it would take V61's column default
      // (FALSE, the pre-existing-row value) and every spec bootstrapped off this template would be
      // testing a state no new install is ever in.
      st.execute(
        "INSERT INTO household_settings (id, daily_reset_time, daily_reset_tz, block_encrypted_dns) " +
          s"VALUES (1, '00:00', 'UTC', ${HouseholdSettings.DefaultBlockEncryptedDns}) ON CONFLICT (id) DO NOTHING",
      )
      // #1771: replicate Main.scala's startup seed of the global sentinel profile so
      // PolicyService.snapshot can read it during tests. ON CONFLICT DO NOTHING + V59's
      // partial unique index keeps this idempotent.
      st.execute(
        "INSERT INTO profiles (name, is_global) VALUES ('Global', TRUE) ON CONFLICT DO NOTHING",
      )
      // #586: V18 sets must_change_password=true for the seeded admin so production deployments
      // force a password rotation. In tests we want the admin fully operational.
      st.execute("UPDATE users SET must_change_password=false WHERE username='admin'")
    } finally conn.close()
    ()
  }

  /**
   * Per-spec database identity. Allocated fresh by `testDb` for every bootstrap of
   * `TestDatabase.layer`. The `ds` is non-pooled (zonky's `getDatabase` returns a JDBC-URL-backed
   * DataSource), so a drop-and-recreate of `name` invalidates nothing held in the Transactor — the
   * next `xa.run` opens a fresh connection to the freshly-cloned DB.
   */
  final case class TestDb(name: String, ds: javax.sql.DataSource)

  // Atomic counter for unique DB names. Crosses spec boundaries within a JVM.
  private val dbCounter = new java.util.concurrent.atomic.AtomicLong(0)

  /** Provides the shared EmbeddedPostgres instance (no lifecycle: lives for JVM lifetime). */
  val embeddedPg: ZLayer[Any, Throwable, EmbeddedPostgres] =
    ZLayer.fromZIO(ZIO.attemptBlocking(sharedPg))

  /** Allocates a fresh per-spec database by cloning the JVM's pre-migrated template. */
  val testDb: ZLayer[EmbeddedPostgres, Throwable, TestDb] =
    ZLayer.fromZIO {
      for {
        pg <- ZIO.service[EmbeddedPostgres]
        n  <- ZIO.succeed(dbCounter.incrementAndGet())
        dbName = s"wh_test_$n"
        _ <- cloneTemplateInto(pg, dbName)
      } yield TestDb(dbName, pg.getDatabase("postgres", dbName))
    }

  /**
   * Drop `dbName` (terminating live backends first so PG will let us) and recreate it as a copy of
   * `wh_template`. Postgres turns `CREATE DATABASE … TEMPLATE` into a fast file-level copy, so this
   * runs in single-digit milliseconds even with 44 migrations' worth of schema.
   */
  private def cloneTemplateInto(pg: EmbeddedPostgres, dbName: String): Task[Unit] =
    ZIO.attemptBlocking {
      val conn = pg.getPostgresDatabase.getConnection
      try {
        val st = conn.createStatement()
        // Terminate any lingering backends on this DB so the DROP below succeeds. Skip our own
        // backend (pg_backend_pid()) since it's on the `postgres` DB, not `dbName`, but be
        // defensive anyway. Both template and target need to be free of other connections.
        st.execute(
          s"""SELECT pg_terminate_backend(pid) FROM pg_stat_activity
              WHERE datname IN ('$dbName', '$TemplateDbName') AND pid <> pg_backend_pid()""",
        )
        st.execute(s"""DROP DATABASE IF EXISTS "$dbName"""")
        st.execute(s"""CREATE DATABASE "$dbName" TEMPLATE "$TemplateDbName"""")
        ()
      } finally conn.close()
    }

  /** Execute a single SQL statement against the singleton's `postgres` admin DB. */
  private def execOnPostgresDb(pg: EmbeddedPostgres, sql: String): Unit = {
    val conn = pg.getPostgresDatabase.getConnection
    try {
      val _ = conn.createStatement().execute(sql)
    } finally conn.close()
  }

  /** Transactor wired to the per-spec database. */
  val transactor: ZLayer[TestDb, Throwable, Transactor[Task]] =
    ZLayer.fromZIO {
      for {
        db <- ZIO.service[TestDb]
        xa = Transactor.fromDataSource[Task](db.ds, scala.concurrent.ExecutionContext.global)
      } yield xa
    }

  /**
   * Reset the per-spec database to the template's post-Flyway, post-seed state.
   *
   * Replaces the legacy `DROP SCHEMA public CASCADE + Flyway migrate + reseed`, which spent
   * ~400–500 ms per call re-running 44 migrations. Template clone is single-digit ms — see the
   * class doc for the full rationale (#1188).
   */
  val cleanAndMigrate: ZIO[EmbeddedPostgres & TestDb, Throwable, Unit] =
    for {
      pg <- ZIO.service[EmbeddedPostgres]
      db <- ZIO.service[TestDb]
      _  <- cloneTemplateInto(pg, db.name)
    } yield ()

  /**
   * All repo types bundled for convenience.
   *
   * `TestDb` is included so a spec that declares `ZIOSpec[TestDatabase.AllRepos & …]` gets the
   * per-spec DB handle in its environment automatically — `cleanAndMigrate` needs it, and the
   * bootstrap layer always provides it.
   */
  type AllRepos =
    TestDb & UserRepo & HouseholdRepo & UserProfileRepo & ProfileRepo & NamedScheduleRepo &
      HouseholdSettingsRepo & TimeLimitRepo & AppTimeLimitRepo & DeviceRepo & BlocklistRepo &
      TimeUsageRepo & TimeExtensionRepo & RouterRepo & TrafficReportRepo & BlockEventRepo &
      ConnectionEventRepo & AlertRepo & AppRepo & RollupRepo & TimeUsedRollupRepo &
      AppUsedRollupRepo & AmbientHostsRepo & HouseholdBillingRepo & BetaRequestRepo &
      BetaCohortRepo & EntitlementsRepo & PressMessageRepo & PasswordResetTokenRepo &
      SupportConsentRepo

  val layer: ZLayer[Any, Throwable, EmbeddedPostgres & TestDb & Transactor[Task] & AllRepos] = {
    val pg = embeddedPg
    val td = pg >>> testDb
    val xa = td >>> transactor
    pg ++ td ++ xa ++ (xa >>> Repos.all)
  }
}

/** Helper for building test layers with a controllable clock. */
object TestLayers {
  import wifihaven.shared.Clock

  /**
   * #2566/#2569/#2322: HMAC secret for block-page tokens in specs. In production this is the API's
   * own `jwt.secret` (`BlockPageToken` domain-separates it); specs pass a fixed value so a token
   * minted through `GET /api/router/block-page-token` verifies on the way back in.
   */
  val TestBlockPageSecret: String = "test-secret-at-least-32-chars!!x"

  def withClock(dt: java.time.LocalDateTime): ULayer[Clock] =
    Clock.TestClock.make(dt)

  /** Seed helpers */

  // The legacy 21:00–07:00 daily "Bedtime" window this fixture has always attached to Kids.
  private val kidsBedtimeWindow =
    wifihaven.shared.ScheduleWindow(
      List("mon", "tue", "wed", "thu", "fri", "sat", "sun"),
      java.time.LocalTime.of(21, 0),
      java.time.LocalTime.of(7, 0),
      java.time.ZoneId.of("UTC"),
    )

  // #1709: the legacy `schedules` row write has been removed; named-schedule model is the sole
  // source of truth for schedule enforcement. Requires a `NamedScheduleRepo` in the environment —
  // every test runs under `TestDatabase.layer`, which provides it, so call sites are unchanged.
  def seedKidsProfile(
      profileRepo: ProfileRepo,
  ): ZIO[NamedScheduleRepo, Throwable, ProfileId] =
    for {
      id  <- profileRepo.create(
        "Kids",
        List(
          BlocklistId.unsafe("adult"),
          BlocklistId.unsafe("gambling"),
          BlocklistId.unsafe("social_media"),
        ),
      )
      nsr <- ZIO.service[NamedScheduleRepo]
      // Name is suffixed with the profile id so repeated seeds in one test don't collide on the
      // UNIQUE(name) constraint.
      sid <- nsr.create(s"Bedtime#${id.value}", Some("Kids bedtime"), List(kidsBedtimeWindow))
      _   <- nsr.setProfileBlockSchedules(id, List(sid))
    } yield id

  def seedAdultsProfile(profileRepo: ProfileRepo): Task[ProfileId] =
    profileRepo.create("Adults", List.empty)

  def seedDevice(
      deviceRepo: DeviceRepo,
      mac: String,
      name: String,
      profileId: ProfileId,
  ): Task[DeviceId] =
    deviceRepo.upsert(MacAddress.unsafe(mac), name, Some(profileId), "192.168.1.100")

  /**
   * Post-#764: ensure a single-host app exists with `slug = host` (creating it if missing) and
   * upsert an assignment for `(app, profileId)` with the given mode. Used by tests that previously
   * wrote to legacy profiles.extra_blocked/extra_allowed/site_time_limits columns.
   */
  def seedAppAssignment(
      appRepo: AppRepo,
      profileId: ProfileId,
      host: String,
      mode: wifihaven.shared.AppMode,
      dailyMinutes: Option[Int] = None,
      exemptFromDaily: Boolean = true,
  ): Task[Unit] =
    for {
      existing <- appRepo.findBySlug(host)
      appId    <- existing match {
        case Some(a) => ZIO.succeed(a.id)
        case None    =>
          for {
            id <- appRepo.create(host, host, None, None)
            _  <- appRepo.setHosts(id, List(wifihaven.shared.types.Hostname.unsafe(host)))
          } yield id
      }
      _        <- appRepo.upsertAssignment(appId, profileId, mode, dailyMinutes, exemptFromDaily)
    } yield ()

  /**
   * #2107 (multi-tenant, epic #622): identifiers produced by [[seedTwoHouseholds]] for a two-tenant
   * isolation fixture. `tokenA`/`tokenB` are the RAW router bearer tokens (already enrolled) so a
   * spec can hit the `/api/router` endpoints as either household's router. Reused by sub-issue E
   * (#2108).
   */
  final case class TwoHouseholds(
      hhA: wifihaven.shared.types.HouseholdId,
      hhB: wifihaven.shared.types.HouseholdId,
      profileA: ProfileId,
      profileB: ProfileId,
      macA: MacAddress,
      macB: MacAddress,
      tokenA: String,
      tokenB: String,
      // #2108 (sub-issue E): the enrolled router ids, so an ingest pin can build a
      // `UsageReport(routerId, …)` whose router_id matches the authed router (the mismatch guard,
      // RouterIngestService.scala:59).
      routerIdA: RouterId,
      routerIdB: RouterId,
      // #2108 (sub-issue E): each household's admin username + the shared password, so a spec can
      // `auth.login(adminA, password)` / `auth.login(adminB, password)` to mint a JWT carrying that
      // household's `hh` claim (#2105) and exercise the user-facing read/write isolation pins (§7).
      // adminA is the single backfill install's seeded `admin` (household 1); adminB is a fresh admin
      // user inserted into household B.
      adminA: String,
      adminB: String,
      password: String,
      // #2164: each household's login slug (`households.slug`, V66). Household A is the default
      // install (slug `default`, set by V66's backfill). Household B gets an explicit slug so a spec
      // can `auth.login(s"$slugB/$adminB", password)` (the single-identifier slug/username form) —
      // now that usernames are per-household, adminB no longer authenticates via a bare username
      // (which resolves to the default household).
      slugA: String,
      slugB: String,
  )

  /**
   * #2107: seed two isolated households and return their identifiers + enrolled router tokens.
   *
   *   - Household A is the single backfill household (`HouseholdId.Default`, id=1) — its profile,
   *     device, and router are created through the normal repos (which default to household 1).
   *   - Household B is a freshly-inserted `households` row. Its profile + device are written with
   *     an explicit `household_id` via raw SQL, because the per-household WRITE paths land in
   *     sub-issue E (#2108) — this read-scoping issue only needs the rows to EXIST in household B
   *     so the scoped reads can prove they don't leak into household A. Household B's router is
   *     created through `RouterRepo.create(..., hhB)` (the household param already exists from
   *     #2106).
   *
   * Note the two devices use DISTINCT MACs: V65 keeps the global `devices_mac_key` UNIQUE(mac)
   * (dropped in E), so the same MAC cannot yet exist in two households.
   */
  def seedTwoHouseholds(
      macA: MacAddress,
      macB: MacAddress,
  ): ZIO[
    ProfileRepo & DeviceRepo & RouterRepo & doobie.Transactor[Task],
    Throwable,
    TwoHouseholds,
  ] = {
    import doobie.implicits.*
    import wifihaven.api.db.TypeMeta.given
    import wifihaven.api.policy.PolicyService
    import wifihaven.shared.types.HouseholdId
    for {
      pr <- ZIO.service[ProfileRepo]
      dr <- ZIO.service[DeviceRepo]
      rr <- ZIO.service[RouterRepo]
      xa <- ZIO.service[doobie.Transactor[Task]]
      hhA = HouseholdId.Default
      // Household A (id=1) through the normal repos.
      profileA <- pr.create("A-Kids", Nil)
      _        <- dr.upsert(macA, "devA", Some(profileA), "192.168.1.10")
      // Household B: a new households row + its profile/device via raw SQL (E owns the write path).
      // #2140: stamp an explicit slug so household B is reachable at login (V66's backfill only
      // slugs pre-existing rows; a test-inserted household would otherwise have NULL slug).
      hhB      <-
        sql"INSERT INTO households(name, slug) VALUES ('B household', 'household-b') RETURNING id"
          .query[HouseholdId]
          .unique
          .transact(xa)
      // #2386: seed household B's OWN settings row, mirroring production's atomic creation unit
      // (HouseholdSeed.insertHousehold). Without it, getForHousehold(hhB) now fails loud (the id=1
      // fallback that used to leak household #1's settings was removed). All columns default; id
      // auto-generates (V82).
      _        <-
        sql"INSERT INTO household_settings(household_id) VALUES ($hhB) ON CONFLICT (household_id) DO NOTHING".update.run
          .transact(xa)
      // profileB is paused so a cross-household leak on the /decision path is observable: if
      // household A's decide ever resolved this row it would BLOCK, but scoped correctly A never
      // finds it and allows.
      profileB <-
        sql"INSERT INTO profiles(name, blocked_categories, paused, household_id) VALUES ('B-Kids', '{}', TRUE, $hhB) RETURNING id"
          .query[ProfileId]
          .unique
          .transact(xa)
      _        <-
        sql"INSERT INTO devices(mac, name, profile_id, household_id) VALUES ($macB, 'devB', $profileB, $hhB)".update.run
          .transact(xa)
      // Enrolled routers for each household (known raw tokens).
      tokenA = "rt_hhA_token_0000000000000000"
      tokenB = "rt_hhB_token_0000000000000000"
      ridA <- rr.create("gwA", PolicyService.hashToken("et_a"), hhA)
      _    <- rr.completeEnrollment(ridA, PolicyService.hashToken(tokenA))
      ridB <- rr.create("gwB", PolicyService.hashToken("et_b"), hhB)
      _    <- rr.completeEnrollment(ridB, PolicyService.hashToken(tokenB))
      // #2108: each household's admin user. Household A reuses the seeded `admin` (household 1,
      // must_change_password already cleared in the template). Household B gets a fresh `adminB`
      // whose password_hash is COPIED from `admin` (so `password` == "changeme" for both) — no
      // AuthService dependency in the seeder. household_id is stamped so login mints hh=B (#2105).
      _    <-
        sql"""INSERT INTO users(username, password_hash, role, must_change_password, household_id)
              SELECT 'adminB', password_hash, 'admin', false, $hhB FROM users WHERE username='admin'""".update.run
          .transact(xa)
    } yield TwoHouseholds(
      hhA,
      hhB,
      profileA,
      profileB,
      macA,
      macB,
      tokenA,
      tokenB,
      routerIdA = ridA,
      routerIdB = ridB,
      adminA = "admin",
      adminB = "adminB",
      password = "changeme",
      slugA = "default",
      slugB = "household-b",
    )
  }
}
