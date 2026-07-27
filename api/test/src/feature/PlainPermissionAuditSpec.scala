package wifihaven.api.feature

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import wifihaven.api.support.*
import wifihaven.api.{PlainConfig, SupportConfig}
import zio.*
import zio.json.ast.Json
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
              val body = new String(exchange.getRequestBody.readAllBytes(), "UTF-8")
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
        on.subsetOf(on) && off.subsetOf(on),
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
