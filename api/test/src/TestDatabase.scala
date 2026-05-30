package wifihaven.testinfra

import doobie.Transactor
import wifihaven.api.db.*
import wifihaven.shared.types.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.flywaydb.core.Flyway
import zio.*
import zio.interop.catz.*

/**
 * Spins up a real embedded Postgres, runs Flyway migrations, provides a Transactor.
 *
 * A single EmbeddedPostgres instance is shared across all specs in the same JVM to avoid concurrent
 * initdb failures on ARM Mac (Postgres runs under Rosetta x86 emulation). Each test calls
 * cleanAndMigrate to reset state before running.
 */
object TestDatabase {

  // JVM-wide singleton: started once, shared across all ZIOSpec bootstrap layers
  // in this JVM. We don't use `lazy val` here — Scala 3 lazy vals can let two
  // racing threads both run the initializer, and EmbeddedPostgres.prepareBinaries
  // takes an internal file lock that throws OverlappingFileLockException on the
  // second concurrent call. Explicit double-checked locking serializes init.
  private val pgLock                                 = new Object
  @volatile private var pgInstance: EmbeddedPostgres = null
  private def sharedPg: EmbeddedPostgres             = {
    val cached = pgInstance
    if cached != null then cached
    else
      pgLock.synchronized {
        if pgInstance == null then {
          val pg = EmbeddedPostgres.start()
          runMigrations(pg)
          pgInstance = pg
        }
        pgInstance
      }
  }

  /** Run the production Flyway migrations (`classpath:db/migration`) against the embedded PG. */
  private[testinfra] def runMigrations(pg: EmbeddedPostgres): Unit = {
    Flyway
      .configure()
      .dataSource(pg.getPostgresDatabase)
      .locations("classpath:db/migration")
      .baselineOnMigrate(true)
      .load()
      .migrate()
    ()
  }

  /** Provides the shared EmbeddedPostgres instance (no lifecycle: lives for JVM lifetime). */
  val embeddedPg: ZLayer[Any, Throwable, EmbeddedPostgres] =
    ZLayer.fromZIO(ZIO.attemptBlocking(sharedPg))

  /** Transactor wired to the embedded PG. */
  val transactor: ZLayer[EmbeddedPostgres, Throwable, Transactor[Task]] =
    ZLayer.fromZIO {
      for {
        pg <- ZIO.service[EmbeddedPostgres]
        ds = pg.getPostgresDatabase
        xa = Transactor.fromDataSource[Task](ds, scala.concurrent.ExecutionContext.global)
      } yield xa
    }

  /**
   * Drop and recreate the public schema, then re-run migrations. This is faster and more reliable
   * than Flyway's clean() + migrate() in Flyway 10.
   */
  val cleanAndMigrate: ZIO[EmbeddedPostgres, Throwable, Unit] =
    for {
      pg <- ZIO.service[EmbeddedPostgres]
      _  <- ZIO.attempt {
        val conn = pg.getPostgresDatabase.getConnection
        try {
          conn.createStatement().execute("DROP SCHEMA public CASCADE")
          conn.createStatement().execute("CREATE SCHEMA public")
        } finally conn.close()
      }
      _  <- ZIO.attempt(runMigrations(pg))
      // #334: replicate Main.ensureDefault so PolicyService.snapshot/decide can
      // read household_settings during tests. Use UTC so DST doesn't perturb
      // existing test expectations.
      _  <- ZIO.attempt {
        val conn = pg.getPostgresDatabase.getConnection
        try {
          conn
            .createStatement()
            .execute(
              "INSERT INTO household_settings (id, daily_reset_time, daily_reset_tz) " +
                "VALUES (1, '00:00', 'UTC') ON CONFLICT (id) DO NOTHING",
            )
        } finally conn.close()
      }
      // #586: V18 migration sets must_change_password=true for the seeded admin so
      // production deployments force a password rotation. In tests we want the admin
      // to be fully operational, so clear the flag after each migration reset.
      _  <- ZIO.attempt {
        val conn = pg.getPostgresDatabase.getConnection
        try {
          conn
            .createStatement()
            .execute("UPDATE users SET must_change_password=false WHERE username='admin'")
        } finally conn.close()
      }
    } yield ()

  /** All repo types bundled for convenience */
  type AllRepos =
    UserRepo & UserProfileRepo & ProfileRepo & ScheduleRepo & HouseholdSettingsRepo &
      TimeLimitRepo & SiteTimeLimitRepo & DeviceRepo & BlocklistRepo & TimeUsageRepo &
      TimeExtensionRepo & RouterRepo & TrafficReportRepo & BlockEventRepo & ConnectionEventRepo &
      AlertRepo & AppRepo & RollupRepo & TimeUsedRollupRepo

  val layer: ZLayer[Any, Throwable, EmbeddedPostgres & Transactor[Task] & AllRepos] = {
    val pg = embeddedPg
    val xa = pg >>> transactor
    pg ++ xa ++ (xa >>> Repos.all)
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
