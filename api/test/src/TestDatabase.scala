package wifihaven.testinfra

import doobie.Transactor
import wifihaven.api.db.*
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
      st.execute(
        "INSERT INTO household_settings (id, daily_reset_time, daily_reset_tz) " +
          "VALUES (1, '00:00', 'UTC') ON CONFLICT (id) DO NOTHING",
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
    TestDb & UserRepo & UserProfileRepo & ProfileRepo & ScheduleRepo & NamedScheduleRepo &
      HouseholdSettingsRepo & GlobalPolicyRepo & TimeLimitRepo & SiteTimeLimitRepo & DeviceRepo &
      BlocklistRepo & TimeUsageRepo & TimeExtensionRepo & RouterRepo & TrafficReportRepo &
      BlockEventRepo & ConnectionEventRepo & AlertRepo & AppRepo & RollupRepo & TimeUsedRollupRepo

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

  def withClock(dt: java.time.LocalDateTime): ULayer[Clock] =
    Clock.TestClock.make(dt)

  /** Seed helpers */
  def seedKidsProfile(profileRepo: ProfileRepo, scheduleRepo: ScheduleRepo): Task[ProfileId] =
    for {
      id <- profileRepo.create(
        "Kids",
        List(
          BlocklistId.unsafe("adult"),
          BlocklistId.unsafe("gambling"),
          BlocklistId.unsafe("social_media"),
        ),
      )
      _  <- scheduleRepo.replaceForProfile(
        id,
        List(
          wifihaven.shared.ScheduleRequest(
            "Bedtime",
            List("mon", "tue", "wed", "thu", "fri", "sat", "sun"),
            java.time.LocalTime.of(21, 0),
            java.time.LocalTime.of(7, 0),
            java.time.ZoneId.of("UTC"),
          ),
        ),
      )
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
}
