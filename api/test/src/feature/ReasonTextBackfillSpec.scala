package wifihaven.api.feature

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import wifihaven.api.ReasonTextBackfill
import wifihaven.api.db.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.api.routes.RouterAuth
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.*
import zio.interop.catz.*
import zio.json.*
import zio.test.*
import zio.test.Assertion.*

/**
 * #1176 / #1179 — startup backfill populates `reason_text` for rows inserted between V40 (TEXT →
 * JSONB) and V44 (the dual-write).
 *
 * Test seeds rows that match the "V40-applied, V44-not-yet" state: `reason` populated (JSONB),
 * `reason_text` NULL. Backfill must transform each kind back to its canonical pre-V40 wire string —
 * and the SQL must mirror exactly what `BlockReason.asWire` produces.
 */
object ReasonTextBackfillSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Transactor[Task]] {

  override val bootstrap
      : ZLayer[Any, Throwable, TestDatabase.AllRepos & EmbeddedPostgres & Transactor[Task]] =
    TestDatabase.layer

  private val cleanDb = TestDatabase.cleanAndMigrate

  private def seedRouter(rRepo: RouterRepo): Task[RouterId] = {
    val token = "ROUTER_TOKEN_PLAIN"
    val hash  = Sha256Hex.unsafe(RouterAuth.sha256Hex(token))
    for {
      id <- rRepo.create("rt", Sha256Hex.unsafe("a" * 64))
      _  <- rRepo.completeEnrollment(id, hash)
    } yield id
  }

  private val mac = MacAddress.unsafe("aa:bb:cc:11:22:33")

  /**
   * Each BlockReason variant: its JSONB shape (as `reason` stores it post-V40) and the expected
   * pre-V40 wire string the backfill should produce in `reason_text`.
   *
   * #1605: derived from `BlockReason.allCases` so the SQL CASE in `ReasonTextBackfill.asWireSql` is
   * pinned to whatever the Scala-side `asWire` / JSON encoder emits. A new BlockReason case forces
   * the exhaustive-match encoders to compile, prompts adding it to `BlockReason.allCases`, and then
   * THIS test fails until `asWireSql` grows the matching SQL arm.
   */
  private val derivedCases: List[(String, String)] =
    BlockReason.allCases.map(r => (r.toJson, BlockReason.asWire(r)))

  /**
   * Legacy `siteTimeLimit` kind from V40-migrated rows still backfills to the new canonical wire
   * text (#1518). The SPA-facing decoder accepts it as an alias, but it is not part of `allCases`
   * (it never round-trips out of any encoder), so it is tested explicitly here to pin the SQL alias
   * arm.
   */
  private val legacyCases: List[(String, String)] = List(
    """{"kind":"siteTimeLimit","label":"netflix"}""" -> "app_time_limit:netflix",
  )

  private val cases: List[(String, String)] = derivedCases ++ legacyCases

  override def spec = suite("ReasonTextBackfill")(
    test("backfills connection_events.reason_text from reason JSONB") {
      for {
        _     <- cleanDb
        rRepo <- ZIO.service[RouterRepo]
        xa    <- ZIO.service[Transactor[Task]]
        rid   <- seedRouter(rRepo)
        // Bypass dual-write: raw SQL insert populates `reason` (JSONB) but leaves
        // `reason_text` NULL — the exact V40-applied-V44-not-yet state.
        _     <- ZIO.foreachDiscard(cases.zipWithIndex) { case ((reasonJson, _), idx) =>
          val ts = java.time.Instant.parse("2026-05-30T12:00:00Z").plusSeconds(idx.toLong)
          sql"""INSERT INTO connection_events(
                   router_id, mac, host_type, host_value,
                   dest_ip, allowed, reason, ts, event_id, resolved_host_value, reason_text
                 ) VALUES(
                   $rid, $mac, 'fqdn', 'example.com',
                   NULL, false, ${reasonJson}::jsonb, $ts, gen_random_uuid(), NULL, NULL
                 )""".update.run.transact(xa)
        }
        _     <- ReasonTextBackfill.run(xa)
        rows  <- sql"""SELECT reason_text
                      FROM connection_events
                      ORDER BY ts ASC"""
          .query[Option[String]]
          .to[List]
          .transact(xa)
      } yield {
        val expected = cases.map(_._2)
        val got      = rows.map(_.getOrElse("<null>"))
        assert(got)(equalTo(expected))
      }
    },
    test("backfills block_events.reason_text from reason JSONB") {
      for {
        _     <- cleanDb
        rRepo <- ZIO.service[RouterRepo]
        _     <- seedRouter(rRepo)
        xa    <- ZIO.service[Transactor[Task]]
        _     <- ZIO.foreachDiscard(cases) { case (reasonJson, _) =>
          sql"""INSERT INTO block_events(mac, host_type, host_value, reason, reason_text)
                 VALUES($mac, 'fqdn', 'example.com', ${reasonJson}::jsonb, NULL)""".update.run
            .transact(xa)
        }
        _     <- ReasonTextBackfill.run(xa)
        rows  <- sql"""SELECT reason_text
                      FROM block_events
                      ORDER BY id ASC"""
          .query[Option[String]]
          .to[List]
          .transact(xa)
      } yield {
        val expected = cases.map(_._2)
        val got      = rows.map(_.getOrElse("<null>"))
        assert(got)(equalTo(expected))
      }
    },
    test("backfill is idempotent (no rows with NULL reason_text after run)") {
      for {
        _     <- cleanDb
        rRepo <- ZIO.service[RouterRepo]
        _     <- seedRouter(rRepo)
        xa    <- ZIO.service[Transactor[Task]]
        _     <- sql"""INSERT INTO block_events(mac, host_type, host_value, reason, reason_text)
                    VALUES($mac, 'fqdn', 'example.com', '{"kind":"allow"}'::jsonb, NULL)""".update.run
          .transact(xa)
        _     <- ReasonTextBackfill.run(xa)
        _     <- ReasonTextBackfill.run(xa)
        nNull <- sql"""SELECT COUNT(*)::INT FROM block_events WHERE reason_text IS NULL"""
          .query[Int]
          .unique
          .transact(xa)
      } yield assert(nNull)(equalTo(0))
    },
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock
}
