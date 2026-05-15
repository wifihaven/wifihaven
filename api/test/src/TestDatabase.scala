package familydns.testinfra

import doobie.Transactor
import familydns.api.db.*
import familydns.shared.types.*
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
    } yield ()

  /** All repo types bundled for convenience */
  type AllRepos =
    UserRepo & UserProfileRepo & ProfileRepo & ScheduleRepo & HouseholdSettingsRepo &
      TimeLimitRepo & SiteTimeLimitRepo & DeviceRepo & BlocklistRepo & TimeUsageRepo &
      TimeExtensionRepo & RouterRepo & TrafficReportRepo & BlockEventRepo & ConnectionEventRepo

  val layer: ZLayer[Any, Throwable, EmbeddedPostgres & Transactor[Task] & AllRepos] = {
    val pg = embeddedPg
    val xa = pg >>> transactor
    pg ++ xa ++ (xa >>> Repos.all)
  }
}

/** Helper for building test layers with a controllable clock. */
object TestLayers {
  import familydns.shared.Clock

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
          familydns.shared.ScheduleRequest(
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
    deviceRepo.upsert(MacAddress.unsafe(mac), name, profileId, "192.168.1.100")
}
