package wifihaven.api.feature

import wifihaven.api.{MetricsConfig, PlainConfig, SupportConfig}
import wifihaven.api.metrics.MetricsRuntime
import wifihaven.api.routes.MetricsRoutes
import wifihaven.api.support.*
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import zio.*
import zio.http.*
import zio.metrics.connectors.prometheus.PrometheusPublisher
import zio.test.*

import java.io.OutputStream
import java.net.InetSocketAddress

/**
 * #2410 — the household→Plain TENANT entitlement write (`plan` / `founding` tenant fields via
 * `upsertTenantField`) must fail LOUD, not degrade silently, when its prerequisites (the machine-
 * user's `tenantField:*` permission, or the registered `plan`/`founding` field schemas) are absent.
 *
 * Before #2410 the path metered only `support_tenant_upsert_total{outcome=ok|error}` — so a missing
 * PERMISSION, a missing field SCHEMA, and a transient tenant hiccup all read identically as
 * `error`, and an operator couldn't tell a provisioning gap (fix: grant permission / register the
 * schema) apart from a network blip. This spec drives the LIVE Plain client against a routing
 * capture server (no repos, no real network) and scrapes the live Prometheus publisher (the same
 * `GET /metrics` path prod scrapes) to assert the failure-kind is attributed on the bounded
 * `reason` label: `permission | schema | tenant | field_write | ok`.
 */
object SupportEntitlementFailLoudSpec extends ZIOSpecDefault {

  // ── Routing capture server ─────────────────────────────────────────────────
  // Returns a per-mutation response so we can make the customer + tenant upserts SUCCEED (yielding a
  // tenant id the client chains field writes onto) while the field write FAILS with a chosen Plain
  // error — the exact provisioning-gap shape #2410 is about. `tenantResp` lets a test also fail the
  // tenant step itself (the `tenant` reason bucket).
  private final class CaptureServer(val server: HttpServer, val bodies: Ref[List[String]])

  private val CustomerOk = """{"data":{"upsertCustomer":{"customer":{"id":"c_1"}}}}"""
  private val TenantOk   = """{"data":{"upsertTenant":{"tenant":{"id":"t_1"}}}}"""
  private val FieldOk    = """{"data":{"upsertTenantField":{"tenantField":{"id":"tf_1"}}}}"""

  private def routingServer(
      tenantResp: String = TenantOk,
      fieldResp: String = FieldOk,
  ): ZIO[Scope, Throwable, CaptureServer] =
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
              val resp             =
                if body.contains("upsertTenantField(input:") then fieldResp
                else if body.contains("upsertTenant(input:") then tenantResp
                else CustomerOk
              val out: Array[Byte] = resp.getBytes("UTF-8")
              exchange.sendResponseHeaders(200, out.length.toLong)
              val os: OutputStream = exchange.getResponseBody
              os.write(out)
              os.close()
            },
          )
          s.start()
          s
        },
      )(s => ZIO.attempt(s.stop(0)).ignore)
    } yield new CaptureServer(server, bodiesRef)

  // ── Prometheus scrape harness (the WsMessageMetricsSpec #2042 pattern) ───────
  private val pollInterval = 100.millis

  private val tickPublisher: UIO[Unit] =
    zio.test.TestClock.sleeps.repeatUntil(_.nonEmpty) *> zio.test.TestClock.adjust(pollInterval)

  private def scrape: ZIO[PrometheusPublisher, Response, String] =
    for {
      pub <- ZIO.service[PrometheusPublisher]
      routes = MetricsRoutes.routes(MetricsConfig(enabled = true), pub)
      resp <- routes(Request.get("/metrics"))
      body <- resp.body.asString.orElseFail(
        Response.internalServerError("scrape body decode failed"),
      )
    } yield body

  private def tenantUpsertLines(body: String): List[String] =
    body.linesIterator
      .filter(l => !l.startsWith("#") && l.startsWith("support_tenant_upsert_total"))
      .toList

  private def driveUpsert(base: String): UIO[PlainOutcome] =
    ZIO
      .serviceWithZIO[PlainClient](
        _.upsertCustomer(
          PlainCustomerUpsert(
            externalId = "hh-7",
            tenantIdentifier = "7",
            email = "a@example.com",
            fullName = "Family Seven",
            attributes =
              Map("plan" -> "beta", "founding" -> "true", "householdName" -> "Family Seven"),
          ),
        ),
      )
      .provide(
        PlainClient.layer,
        ZLayer.succeed(
          SupportConfig(plain = PlainConfig(writeEnabled = true, apiKey = "k", apiBase = base)),
        ),
      )

  def spec = suite("Support entitlement fail-loud attribution (#2410)")(
    test(
      "a missing tenantField:* permission attributes reason=permission on support_tenant_upsert_total",
    ) {
      ZIO.scoped {
        for {
          cap <- routingServer(fieldResp =
            """{"data":{"upsertTenantField":{"tenantField":null,"error":{"message":"You do not have permission to perform this action."}}}}""",
          )
          base = s"http://127.0.0.1:${cap.server.getAddress.getPort}/"
          _    <- driveUpsert(base)
          _    <- tickPublisher
          body <- scrape.catchAll(resp => resp.body.asString.orDie)
          lines = tenantUpsertLines(body)
        } yield assertTrue(
          lines.exists(l =>
            l.contains("""outcome="error"""") && l.contains("""reason="permission""""),
          ),
        )
      }
    },
    test("a missing plan/founding field schema attributes reason=schema") {
      ZIO.scoped {
        for {
          cap <- routingServer(fieldResp =
            """{"data":{"upsertTenantField":{"tenantField":null,"error":{"message":"Tenant field with external id 'plan' was not found."}}}}""",
          )
          base = s"http://127.0.0.1:${cap.server.getAddress.getPort}/"
          _    <- driveUpsert(base)
          _    <- tickPublisher
          body <- scrape.catchAll(resp => resp.body.asString.orDie)
          lines = tenantUpsertLines(body)
        } yield assertTrue(
          lines.exists(l => l.contains("""outcome="error"""") && l.contains("""reason="schema"""")),
        )
      }
    },
    test("a failed tenant upsert step attributes reason=tenant (fields never reached)") {
      ZIO.scoped {
        for {
          cap <- routingServer(tenantResp =
            """{"data":{"upsertTenant":{"tenant":null,"error":{"message":"tenant service unavailable"}}}}""",
          )
          base = s"http://127.0.0.1:${cap.server.getAddress.getPort}/"
          _    <- driveUpsert(base)
          _    <- tickPublisher
          body <- scrape.catchAll(resp => resp.body.asString.orDie)
          lines = tenantUpsertLines(body)
        } yield assertTrue(
          lines.exists(l => l.contains("""outcome="error"""") && l.contains("""reason="tenant"""")),
        )
      }
    },
    test(
      "an unrecognized field-write error falls to reason=field_write (the transient/other bucket)",
    ) {
      ZIO.scoped {
        for {
          cap <- routingServer(fieldResp =
            """{"data":{"upsertTenantField":{"tenantField":null,"error":{"message":"internal server error"}}}}""",
          )
          base = s"http://127.0.0.1:${cap.server.getAddress.getPort}/"
          _    <- driveUpsert(base)
          _    <- tickPublisher
          body <- scrape.catchAll(resp => resp.body.asString.orDie)
          lines = tenantUpsertLines(body)
        } yield assertTrue(
          lines.exists(l =>
            l.contains("""outcome="error"""") && l.contains("""reason="field_write""""),
          ),
        )
      }
    },
    test("a fully successful entitlement write attributes outcome=ok reason=ok") {
      ZIO.scoped {
        for {
          cap <- routingServer()
          base = s"http://127.0.0.1:${cap.server.getAddress.getPort}/"
          _    <- driveUpsert(base)
          _    <- tickPublisher
          body <- scrape.catchAll(resp => resp.body.asString.orDie)
          lines = tenantUpsertLines(body)
        } yield assertTrue(
          lines.exists(l => l.contains("""outcome="ok"""") && l.contains("""reason="ok"""")),
        )
      }
    },
  ).provideSomeLayer[TestEnvironment](MetricsRuntime.prometheus(pollInterval)) @@
    TestAspect.sequential
}
