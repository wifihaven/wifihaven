package wifihaven.api.feature

import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.flywaydb.core.Flyway
import zio.*
import zio.test.*

/**
 * #764: verify that V35 backfills legacy profiles.extra_blocked / profiles.extra_allowed /
 * site_time_limits into the apps schema, then drops the legacy storage.
 *
 * Strategy: wipe the DB, run Flyway up to V34 (legacy columns/table still present), seed legacy
 * rows directly via JDBC, then run Flyway again to pick up V35 and assert the apps schema reflects
 * the legacy data + the legacy columns/table are gone. V33/V34 are unrelated migrations
 * (fakenews-blocklist removal, unmanaged-mac policy) that landed on main between branching and
 * merging.
 *
 * EmbeddedPostgres is shared across the JVM (see TestDatabase) so we drop + recreate `public`
 * between steps to isolate this spec from sibling specs.
 */
object LegacyAppMigrationSpec extends ZIOSpec[EmbeddedPostgres] {

  override val bootstrap: ZLayer[Any, Throwable, EmbeddedPostgres] = TestDatabase.embeddedPg

  private def dropPublic(pg: EmbeddedPostgres): Task[Unit] = ZIO.attempt {
    val conn = pg.getPostgresDatabase.getConnection
    try {
      val st = conn.createStatement()
      st.execute("DROP SCHEMA public CASCADE")
      st.execute("CREATE SCHEMA public")
      ()
    } finally conn.close()
  }

  private def migrateTo(pg: EmbeddedPostgres, target: String): Task[Unit] = ZIO.attempt {
    Flyway
      .configure()
      .dataSource(pg.getPostgresDatabase)
      .locations("classpath:db/migration")
      .target(target)
      .baselineOnMigrate(true)
      .load()
      .migrate()
    ()
  }

  private def exec(pg: EmbeddedPostgres, sql: String): Task[Unit] = ZIO.attempt {
    val conn = pg.getPostgresDatabase.getConnection
    try {
      val _ = conn.createStatement().execute(sql)
    } finally conn.close()
  }

  private def query[A](pg: EmbeddedPostgres, sql: String)(extract: java.sql.ResultSet => A) =
    ZIO.attempt {
      val conn = pg.getPostgresDatabase.getConnection
      try {
        val rs  = conn.createStatement().executeQuery(sql)
        val out = scala.collection.mutable.ArrayBuffer.empty[A]
        while (rs.next()) out += extract(rs)
        out.toList
      } finally conn.close()
    }

  def spec = suite("V35 legacy-to-apps migration (#764)")(
    test("backfills extra_blocked / extra_allowed / site_time_limits into apps, then drops them") {
      for {
        pg          <- ZIO.service[EmbeddedPostgres]
        _           <- dropPublic(pg)
        _           <- migrateTo(pg, "34")
        // Seed a profile with one entry of each kind of legacy policy.
        _           <- exec(
          pg,
          """INSERT INTO profiles (id, name, blocked_categories, extra_blocked, extra_allowed)
             VALUES (99, 'TestKids', ARRAY[]::TEXT[],
                     ARRAY['ads.example']::TEXT[],
                     ARRAY['edu.example']::TEXT[])""",
        )
        _           <- exec(
          pg,
          """INSERT INTO site_time_limits (profile_id, domain_pattern, daily_minutes, label, exempt_from_daily)
             VALUES (99, 'youtube.com', 45, 'YouTube', true)""",
        )
        // Apply V35 — the migration under test.
        _           <- migrateTo(pg, "35")
        // Apps were created with slug = host apex.
        slugs       <- query(pg, "SELECT slug FROM apps ORDER BY slug")(_.getString(1))
        // Each app has the corresponding single host.
        hosts       <- query(
          pg,
          "SELECT a.slug, ah.host FROM apps a JOIN app_hosts ah ON ah.app_id = a.id ORDER BY a.slug",
        )(rs => (rs.getString(1), rs.getString(2)))
        // Assignments link back to profile 99 with the expected mode.
        assigns     <- query(
          pg,
          """SELECT a.slug, apa.mode, COALESCE(apa.daily_minutes, 0), apa.exempt_from_daily
               FROM app_policy_assignments apa
               JOIN apps a ON a.id = apa.app_id
              WHERE apa.profile_id = 99
              ORDER BY a.slug""",
        )(rs => (rs.getString(1), rs.getString(2), rs.getInt(3), rs.getBoolean(4)))
        // Legacy columns + table are gone.
        colExists   <- query(
          pg,
          """SELECT column_name FROM information_schema.columns
              WHERE table_name='profiles'
                AND column_name IN ('extra_blocked','extra_allowed')""",
        )(_.getString(1))
        tableExists <- query(
          pg,
          "SELECT table_name FROM information_schema.tables WHERE table_name='site_time_limits'",
        )(_.getString(1))
      } yield assertTrue(slugs == List("ads.example", "edu.example", "youtube.com")) &&
        assertTrue(
          hosts.toSet == Set(
            "ads.example" -> "ads.example",
            "edu.example" -> "edu.example",
            "youtube.com" -> "youtube.com",
          ),
        ) &&
        assertTrue(
          assigns == List(
            ("ads.example", "blocked", 0, true),
            ("edu.example", "allowed", 0, true),
            ("youtube.com", "time_limited", 45, true),
          ),
        ) &&
        assertTrue(colExists.isEmpty) &&
        assertTrue(tableExists.isEmpty)
    },
    test("a host present in both extra_blocked and extra_allowed → allowed wins") {
      for {
        pg    <- ZIO.service[EmbeddedPostgres]
        _     <- dropPublic(pg)
        _     <- migrateTo(pg, "34")
        _     <- exec(
          pg,
          """INSERT INTO profiles (id, name, blocked_categories, extra_blocked, extra_allowed)
             VALUES (88, 'Conflict', ARRAY[]::TEXT[],
                     ARRAY['both.example']::TEXT[],
                     ARRAY['both.example']::TEXT[])""",
        )
        _     <- migrateTo(pg, "35")
        modes <- query(
          pg,
          """SELECT apa.mode FROM app_policy_assignments apa
               JOIN apps a ON a.id = apa.app_id
              WHERE apa.profile_id = 88 AND a.slug = 'both.example'""",
        )(_.getString(1))
      } yield assertTrue(modes == List("allowed"))
    },
  ) @@ TestAspect.sequential
}
