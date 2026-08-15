package wifihaven.api.feature

import doobie.*
import doobie.implicits.*
import wifihaven.api.db.*
import wifihaven.api.routes.RouterAuth
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.*
import zio.interop.catz.*
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant
import java.util.UUID

/**
 * #1176 / #1179 — dual-write `reason_text` (TEXT, pre-V40 wire shape) alongside `reason` (JSONB,
 * post-V40 shape) on both event tables.
 *
 * Once V45 cuts reads to `reason_text`, a Render auto-rollback that lands on a *post-V44* image
 * still finds the column populated. Combined with #1179's back-compat policy this prevents the
 * #1176-class outage from recurring.
 *
 * Reads remain off `reason` (JSONB) — no SPA-visible change here.
 */
object ReasonTextDualWriteSpec
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

  private val mac  = MacAddress.unsafe("aa:bb:cc:11:22:33")
  private val host = HostId.fqdn(Hostname.unsafe("example.com"))

  /**
   * Each `BlockReason` paired with its canonical pre-V40 wire-format string — the exact characters
   * a router agent prior to #1147 would have written to `connection_events.reason` (TEXT). The
   * post-V44 dual-write produces this same TEXT alongside the JSONB column.
   */
  private val cases: List[(BlockReason, String)] = List(
    BlockReason.Allow                                        -> "allow",
    BlockReason.Blocked                                      -> "blocked",
    BlockReason.ExtraAllowed                                 -> "extra_allowed",
    BlockReason.ExtraBlocked                                 -> "extra_blocked",
    BlockReason.NoProfile                                    -> "no_profile",
    MacBlockReason.Paused                                    -> "paused",
    MacBlockReason.Schedule                                  -> "schedule",
    MacBlockReason.TimeLimit                                 -> "time_limit",
    MacBlockReason.Manual                                    -> "manual",
    MacBlockReason.Unmanaged                                 -> "unmanaged_mac",
    BlockReason.Category(BlocklistId.unsafe("social-media")) -> "category:social-media",
    BlockReason.AppTimeLimit("youtube")                      -> "app_time_limit:youtube",
    BlockReason.AppBlocked("netflix")                        -> "app:netflix",
    BlockReason.Unknown("weird")                             -> "weird",
  )

  override def spec = suite("ReasonTextDualWrite")(
    test("ConnectionEventRepo.insertBatch populates both reason (JSONB) and reason_text (TEXT)") {
      for {
        _     <- cleanDb
        rRepo <- ZIO.service[RouterRepo]
        cRepo <- ZIO.service[ConnectionEventRepo]
        xa    <- ZIO.service[Transactor[Task]]
        rid   <- seedRouter(rRepo)
        inserts = cases.zipWithIndex.map { case ((r, _), idx) =>
          ConnectionEventInsert(
            routerId = rid,
            mac = Some(mac),
            host = host,
            destIp = None,
            allowed = false,
            reason = r,
            ts = Instant.parse("2026-05-30T12:00:00Z").plusSeconds(idx.toLong),
            eventId = Some(UUID.randomUUID()),
            resolvedHost = None,
          )
        }
        _    <- cRepo.insertBatch(inserts)
        rows <- sql"""SELECT reason::TEXT, reason_text
                      FROM connection_events
                      ORDER BY ts ASC"""
          .query[(String, Option[String])]
          .to[List]
          .transact(xa)
      } yield {
        val expected = cases.map(_._2)
        val got      = rows.map(_._2.getOrElse("<null>"))
        assert(got)(equalTo(expected))
      }
    },
    test("BlockEventRepo.insertBatch populates both reason (JSONB) and reason_text (TEXT)") {
      for {
        _     <- cleanDb
        rRepo <- ZIO.service[RouterRepo]
        rid   <- seedRouter(rRepo)
        bRepo <- ZIO.service[BlockEventRepo]
        xa    <- ZIO.service[Transactor[Task]]
        inserts = cases.map { case (r, _) =>
          BlockEventInsert(routerId = rid, mac = Some(mac), host = host, reason = r)
        }
        _    <- bRepo.insertBatch(inserts)
        rows <- sql"""SELECT reason_text
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
  ) @@ TestAspect.sequential
}
