package wifihaven.api.feature

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import wifihaven.api.support.*
import wifihaven.api.{PlainConfig, SupportConfig}
import zio.*
import zio.json.ast.Json
import zio.metrics.Metric
import zio.test.*

import java.io.OutputStream
import java.net.InetSocketAddress

/**
 * #2452 — the Plain machine-user API key's permission array is the ONE prerequisite this
 * integration cannot check locally, and two of its members were missing from the runbook for the
 * whole of #2430 (thread history) and #2240 (tenant entitlement). Both features shipped and were
 * PERMANENTLY INERT on the staging workspace, visible only as an ERROR log line that named the
 * WRONG permission.
 *
 * Per docs/process/no-dark-by-default.md a missing permission is a MISCONFIGURATION, not an
 * optional feature. This suite pins the durable fix:
 *
 *   - the boot probe reads the key's OWN permissions (Plain's `myPermissions` query — verified
 *     against Plain's published schema, https://core-api.uk.plain.com/graphql/v1/schema.graphql:
 *     "Returns the full list of permission strings granted to the currently authenticated user or
 *     machine user in this workspace") and reports EVERY gap at once, never just the first;
 *   - `timeline:read` and `tenantFieldSchema:read` are in the required set — the two the runbook
 *     omitted;
 *   - a Plain OUTAGE is distinguishable from "Plain says you lack X" (a transient blip must not
 *     read as a provisioning gap, and vice versa);
 *   - the 403 hint names the permission Plain ACTUALLY named, not a hardcoded guess.
 *
 * Plain's HTTP transport is stubbed with a JDK [[HttpServer]] (the [[PlainClientWireSpec]] pattern)
 * — external I/O only, no repo mocks, no network.
 */
object PlainPermissionAuditSpec extends ZIOSpecDefault {

  private final class Stub(val server: HttpServer, val bodies: Ref[List[String]])

  /** A Plain-shaped endpoint returning `status` + `resp` and stashing every request body. */
  private def stub(status: Int, resp: String): ZIO[Scope, Throwable, Stub] =
    for {
      bodiesRef <- Ref.make(List.empty[String])
      runtime   <- ZIO.runtime[Any]
      server    <- ZIO.acquireRelease(
        ZIO.attempt {
          val s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
          s.createContext(
            "/",
            (exchange: HttpExchange) => {
              val body             = new String(exchange.getRequestBody.readAllBytes(), "UTF-8")
              Unsafe.unsafe { implicit u =>
                runtime.unsafe.run(bodiesRef.update(_ :+ body)).getOrThrowFiberFailure()
              }
              val out: Array[Byte] = resp.getBytes("UTF-8")
              exchange.sendResponseHeaders(status, out.length.toLong)
              val os: OutputStream = exchange.getResponseBody
              os.write(out)
              os.close()
            },
          )
          s.start()
          s
        },
      )(s => ZIO.attempt(s.stop(0)).ignore)
    } yield new Stub(server, bodiesRef)

  private def permissionsBody(granted: Set[String]): String =
    Json
      .Obj(
        "data" -> Json.Obj(
          "myPermissions" -> Json.Obj(
            "permissions" -> Json.Arr(granted.toList.sorted.map(Json.Str(_))*),
          ),
        ),
      )
      .toString

  private def cfgFor(base: String, responder: Boolean): SupportConfig =
    SupportConfig(
      plain = PlainConfig(writeEnabled = true, apiKey = "test-key", apiBase = base),
      responderEnabled = responder,
    )

  private def queryOf(body: String): String =
    Json.decoder
      .decodeJson(body)
      .toOption
      .flatMap {
        case o: Json.Obj => o.fields.collectFirst { case ("query", Json.Str(q)) => q }
        case _           => None
      }
      .getOrElse("")

  private def counterValue(name: String, labels: (String, String)*): UIO[Double] =
    labels
      .foldLeft(Metric.counter(name))((m, kv) => m.tagged(kv._1, kv._2))
      .value
      .map(_.count)

  /**
   * Drive `PlainPermissionAudit.run` — the function `Main` actually calls — against a stubbed Plain
   * and assert it meters EXACTLY the expected `outcome` bucket and no neighbouring one. The
   * neighbour check is the point: the buckets carry different operator instructions (act now vs.
   * wait it out), so landing in the wrong one is the failure mode, not just a wrong count.
   */
  private def probeOutcomeFor(
      status: Int,
      body: String,
      expected: String,
  ): ZIO[Any, Throwable, TestResult] = {
    val buckets = List("ok", "missing", "broken", "unreachable", "skipped")
    ZIO.scoped {
      for {
        s <- stub(status, body)
        base   = s"http://127.0.0.1:${s.server.getAddress.getPort}/"
        cfg    = cfgFor(base, responder = true)
        client = new PlainClient.Live(cfg)
        before <- ZIO.foreach(buckets)(b =>
          counterValue("support_permission_probe_total", "outcome" -> b).map(v => (b, v)),
        )
        _      <- PlainPermissionAudit.run(cfg, client)
        after  <- ZIO.foreach(buckets)(b =>
          counterValue("support_permission_probe_total", "outcome" -> b).map(v => (b, v)),
        )
        beforeByBucket = before.toMap
        deltas: Map[String, Double] = after.toMap.map { case (b, v) =>
          (b, v - beforeByBucket(b))
        }
      } yield assertTrue(
        deltas(expected) == 1.0,
        (deltas - expected).values.forall(_ == 0.0),
      )
    }
  }

  def spec = suite("Plain API-key permission audit (#2452)")(
    test("the required set contains the two permissions the runbook omitted") {
      val required = PlainPermissionAudit.required(
        SupportConfig(plain = PlainConfig(writeEnabled = true), responderEnabled = true),
      )
      assertTrue(
        // Defect 1 — gates `thread { timelineEntries }`, SEPARATE from thread:read.
        required.contains("timeline:read"),
        required.contains("thread:read"),
        // Defect 2 — the tenant-field WRITE path resolves the field's schema, so it needs schema READ.
        required.contains("tenantFieldSchema:read"),
        required.contains("tenantField:create"),
        required.contains("tenantField:update"),
        // `customer:read` is recommended (debuggability) but NOT required — the app only writes.
        !required.contains("customer:read"),
        // We only write field VALUES, never read them back.
        !required.contains("tenantField:read"),
      )
    },
    test("responder-only permissions are required only when the responder is enabled") {
      val off = PlainPermissionAudit.required(
        SupportConfig(plain = PlainConfig(writeEnabled = true), responderEnabled = false),
      )
      val on  = PlainPermissionAudit.required(
        SupportConfig(plain = PlainConfig(writeEnabled = true), responderEnabled = true),
      )
      assertTrue(
        !off.contains("timeline:read"),
        !off.contains("thread:read"),
        !off.contains("thread:reply"),
        !off.contains("label:create"),
        // The entitlement chain is required either way — upsertCustomer always writes tenant fields.
        off.contains("tenantFieldSchema:read"),
        // Enabling the responder only ever ADDS requirements.
        off.subsetOf(on),
        (on -- off) == PlainPermissionAudit.ResponderPermissions,
      )
    },
    test("a key carrying every required permission audits Ok, via Plain's myPermissions query") {
      ZIO.scoped {
        for {
          s <- stub(200, permissionsBody(PlainPermissionAudit.required(cfgFor("", true))))
          base   = s"http://127.0.0.1:${s.server.getAddress.getPort}/"
          cfg    = cfgFor(base, responder = true)
          client = new PlainClient.Live(cfg)
          res    <- PlainPermissionAudit.check(cfg, client)
          bodies <- s.bodies.get
        } yield assertTrue(
          res.isInstanceOf[PlainPermissionAuditResult.Ok],
          bodies.size == 1,
          // Wire pin: the probe uses `myPermissions`, the schema's self-introspection query.
          queryOf(bodies.head).contains("myPermissions"),
        )
      }
    },
    test("EVERY missing permission is reported at once, not just the first") {
      ZIO.scoped {
        for {
          granted <- ZIO.succeed(
            PlainPermissionAudit.required(cfgFor("", true)) -- Set(
              "timeline:read",
              "tenantFieldSchema:read",
            ),
          )
          s       <- stub(200, permissionsBody(granted))
          base   = s"http://127.0.0.1:${s.server.getAddress.getPort}/"
          cfg    = cfgFor(base, responder = true)
          client = new PlainClient.Live(cfg)
          res <- PlainPermissionAudit.check(cfg, client)
        } yield res match {
          case PlainPermissionAuditResult.Missing(missing, _) =>
            assertTrue(missing == List("tenantFieldSchema:read", "timeline:read"))
          case other                                          =>
            assertTrue(false) ?? s"expected Missing, got $other"
        }
      }
    },
    test("a Plain outage is Unreachable, NOT a permission gap") {
      ZIO.scoped {
        for {
          s <- stub(503, "upstream unavailable")
          base   = s"http://127.0.0.1:${s.server.getAddress.getPort}/"
          cfg    = cfgFor(base, responder = true)
          client = new PlainClient.Live(cfg)
          res <- PlainPermissionAudit.check(cfg, client)
        } yield assertTrue(res.isInstanceOf[PlainPermissionAuditResult.Unreachable])
      }
    },
    // ── the credential itself is rejected: PERMANENT, not an outage ────────────
    // The trap this pins: `unreachable` is documented (dashboard + runbook) as transient and
    // safe to wait out. A revoked / rotated / wrong key 401s forever — routing it into that
    // bucket would tell the operator to ignore the one signal that every Plain call is dead.
    test("a 401 — a revoked or wrong key — is Broken, NOT Unreachable") {
      ZIO.scoped {
        for {
          s <- stub(401, """{"error":"unauthorized"}""")
          base   = s"http://127.0.0.1:${s.server.getAddress.getPort}/"
          cfg    = cfgFor(base, responder = true)
          client = new PlainClient.Live(cfg)
          res <- PlainPermissionAudit.check(cfg, client)
        } yield assertTrue(
          res.isInstanceOf[PlainPermissionAuditResult.Broken],
          !res.isInstanceOf[PlainPermissionAuditResult.Unreachable],
        )
      }
    },
    test("a 403 on the probe is Broken — myPermissions needs no permission of its own") {
      ZIO.scoped {
        for {
          s <- stub(403, """{"message":"Forbidden"}""")
          base   = s"http://127.0.0.1:${s.server.getAddress.getPort}/"
          cfg    = cfgFor(base, responder = true)
          client = new PlainClient.Live(cfg)
          res <- PlainPermissionAudit.check(cfg, client)
        } yield assertTrue(res.isInstanceOf[PlainPermissionAuditResult.Broken])
      }
    },
    test("a 200 with no permissions array is Broken (probe drift), never Granted(empty)") {
      ZIO.scoped {
        for {
          s <- stub(200, """{"data":{"myPermissions":{"scopes":[]}}}""")
          base   = s"http://127.0.0.1:${s.server.getAddress.getPort}/"
          cfg    = cfgFor(base, responder = true)
          client = new PlainClient.Live(cfg)
          res <- PlainPermissionAudit.check(cfg, client)
        } yield res match {
          case PlainPermissionAuditResult.Broken(_) => assertTrue(true)
          // Granted(empty) would report all 14 as "missing" and send the operator to re-grant
          // permissions they already hold; Unreachable would tell them to wait out drift.
          case other                                =>
            assertTrue(false) ?? s"expected Broken, got $other"
        }
      }
    },
    // ── the reporting half: `run` is what Main calls, so pin its metric outcomes ─
    suite("run — the boot report (the function Main actually calls)")(
      test("a complete key meters ok") {
        probeOutcomeFor(200, permissionsBody(PlainPermissionAudit.required(cfgFor("", true))), "ok")
      },
      test("a gap meters missing, the never-self-healing provisioning signal") {
        probeOutcomeFor(
          200,
          permissionsBody(
            PlainPermissionAudit.required(cfgFor("", true)) -- Set("timeline:read"),
          ),
          "missing",
        )
      },
      test("a rejected credential meters broken, NOT unreachable") {
        probeOutcomeFor(401, """{"error":"unauthorized"}""", "broken")
      },
      test("a Plain 5xx meters unreachable, NOT broken") {
        probeOutcomeFor(503, "upstream unavailable", "unreachable")
      },
      test("an unconfigured client meters skipped and makes no call") {
        val cfg = SupportConfig(plain = PlainConfig(writeEnabled = false))
        for {
          before <- counterValue("support_permission_probe_total", "outcome" -> "skipped")
          _      <- PlainPermissionAudit.run(cfg, PlainClient.Disabled)
          after  <- counterValue("support_permission_probe_total", "outcome" -> "skipped")
        } yield assertTrue(after == before + 1)
      },
      // The metric registry is process-global, so these before/after deltas are only meaningful
      // when one probe runs at a time — the default is to run a suite's tests concurrently, which
      // makes each test see its siblings' increments in the NEIGHBOUR buckets.
    ) @@ TestAspect.sequential,
    test("an unconfigured Plain client is Skipped and touches no network") {
      val cfg = SupportConfig(plain = PlainConfig(writeEnabled = false))
      for {
        res <- PlainPermissionAudit.check(cfg, PlainClient.Disabled)
      } yield assertTrue(res == PlainPermissionAuditResult.Skipped)
    },
    // ── the sub-defect: the 403 hint named a permission Plain never mentioned ──
    test("the permission hint names the permission Plain ACTUALLY named") {
      val detail = """GraphQL errors: Insufficient permissions, missing "timeline:read"."""
      val hint   = PlainClient.permissionGapHint(detail)
      assertTrue(
        PlainClient.missingPermissionName(detail).contains("timeline:read"),
        hint.contains("timeline:read"),
        // The old hardcoded guess must be gone — it is what cost the debugging time.
        !hint.contains("thread:read"),
      )
    },
    test("an unparseable 403 falls back to a generic hint rather than a confident wrong name") {
      val detail = "HTTP 403 (body redacted)"
      val hint   = PlainClient.permissionGapHint(detail)
      assertTrue(
        PlainClient.missingPermissionName(detail).isEmpty,
        hint.contains("docs/ops/plain-setup.md"),
        !hint.contains("thread:read"),
        !hint.contains("timeline:read"),
      )
    },
    test("only permission-shaped tokens are lifted out of the error text (no body leakage)") {
      // A message that mentions `missing` but carries conversation-ish text must yield nothing —
      // the parsed substring is Plain's own permission identifier or nothing at all.
      assertTrue(
        PlainClient.missingPermissionName("""missing "my credit card number is 4111"""").isEmpty,
        PlainClient.missingPermissionName("Plain error: something went wrong").isEmpty,
        PlainClient
          .missingPermissionName("""Insufficient permissions, missing "tenantFieldSchema:read".""")
          .contains("tenantFieldSchema:read"),
      )
    },
  )
}
