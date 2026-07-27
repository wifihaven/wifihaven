package wifihaven.api.feature

import wifihaven.api.{MetricsConfig, PlainConfig, SupportConfig}
import wifihaven.api.metrics.MetricsRuntime
import wifihaven.api.routes.MetricsRoutes
import wifihaven.api.support.*
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import zio.*
import zio.http.{Request, Response}
import zio.json.ast.Json
import zio.metrics.connectors.prometheus.PrometheusPublisher
import zio.test.*

import java.io.OutputStream
import java.net.InetSocketAddress

/**
 * #2435 — a Plain customer that already holds the household admin's email under NO `externalId`
 * (Plain auto-creates one from an inbound support email, #2198) permanently blocks the
 * household→customer mapping: `upsertCustomer` keyed on `identifier: { externalId }` has to CREATE,
 * and the create collides on Plain's workspace-wide email uniqueness. The email is taken forever,
 * so this never self-heals — every later identity call for that household fails,
 * `customer.externalId` stays null, and [[PlainWebhook.parseEvent]] can resolve no
 * `tenantIdentifier` for that customer's widget messages.
 *
 * The fix is to RECONCILE rather than give up: on Plain's stable
 * `customer_already_exists_with_email` error code, re-upsert keyed on `identifier: { emailAddress
 * }` and patch `onUpdate.externalId` to the household id, then assert tenant membership via
 * `addCustomerToTenants` (Plain's update input carries no `tenantIdentifiers`).
 *
 * Every shape asserted here is quoted from Plain's published schema (team-plain/typescript-sdk
 * `src/graphql/types.ts`) and error-code list (plain.com/docs/graphql/error-codes):
 *   - `UpsertCustomerIdentifierInput` — `{ customerId | emailAddress | externalId }`, one only;
 *   - `UpsertCustomerOnUpdateInput.externalId: OptionalStringInput` — a WRAPPED `{ value }`;
 *   - `UpsertCustomerOnCreateInput.externalId: ID` — a bare scalar;
 *   - `AddCustomerToTenantsInput` — `{ customerIdentifier, tenantIdentifiers: [ { externalId } ]
 *     }`;
 *   - `MutationError.code` — the fixed code `customer_already_exists_with_email` (matching the
 *     English `message` would be brittle; the code is contract).
 *
 * Drives the LIVE client against a routing capture server (no repo mocks, no network) and scrapes
 * the live Prometheus publisher, exactly like [[SupportEntitlementFailLoudSpec]].
 */
object SupportCustomerReconcileSpec extends ZIOSpecDefault {

  private final class CaptureServer(val server: HttpServer, val bodies: Ref[List[String]])

  // Plain's payload-level collision: HTTP 200, null customer, a fixed `error.code`.
  private val CustomerEmailCollision =
    """{"data":{"upsertCustomer":{"customer":null,"error":{"message":"A customer already exists with the provided email address","code":"customer_already_exists_with_email","type":"VALIDATION"}}}}"""
  private val CustomerOk             = """{"data":{"upsertCustomer":{"customer":{"id":"c_1"}}}}"""
  private val TenantsOk              = """{"data":{"addCustomerToTenants":{"error":null}}}"""

  // Plain's `not_found` on the membership join: "An entity referenced in the request is not found."
  // Says the target is missing, never WHY — which is why the caller threads TenantWrite in.
  private val JoinTargetNotFound =
    """{"data":{"addCustomerToTenants":{"error":{"message":"Tenant not found","code":"not_found","type":"VALIDATION"}}}}"""
  private val TenantOk           = """{"data":{"upsertTenant":{"tenant":{"id":"t_1"}}}}"""
  private val FieldOk = """{"data":{"upsertTenantField":{"tenantField":{"id":"tf_1"}}}}"""

  /**
   * Routes by mutation, and — for `upsertCustomer` — by WHICH identifier the request carries: the
   * externalId-keyed primary upsert collides, the emailAddress-keyed reconcile is answered by
   * `reconcileResp` so a test can also model the reconcile itself failing.
   */
  private def routingServer(
      reconcileResp: String = CustomerOk,
      tenantsResp: String = TenantsOk,
      tenantResp: String = TenantOk,
      collide: Boolean = true,
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
                if body.contains("addCustomerToTenants(input:") then tenantsResp
                else if body.contains("upsertTenantField(input:") then FieldOk
                else if body.contains("upsertTenant(input:") then tenantResp
                else if body.contains("\"emailAddress\"") then reconcileResp
                else if collide then CustomerEmailCollision
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

  private def parse(body: String): Json = Json.decoder.decodeJson(body).toOption.get

  private def field(j: Json, path: String*): Option[Json] =
    path.foldLeft(Option(j)) { (acc, key) =>
      acc.flatMap {
        case o: Json.Obj => o.fields.collectFirst { case (k, v) if k == key => v }
        case _           => None
      }
    }

  private def queryOf(body: String): String =
    field(parse(body), "query").collect { case Json.Str(q) => q }.getOrElse("")

  private def varsOf(body: String): Json = field(parse(body), "variables").getOrElse(Json.Null)

  // Every recorded request whose GraphQL query names `op`, as its `variables` object.
  private def allVarsForOp(bodies: List[String], op: String): List[Json] =
    bodies.filter(b => queryOf(b).contains(s"$op(input:")).map(varsOf)

  // ── Prometheus scrape harness (the SupportEntitlementFailLoudSpec #2410 pattern) ──
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

  private def customerUpsertLines(body: String): List[String] =
    body.linesIterator
      .filter(l => !l.startsWith("#") && l.startsWith("support_customer_upsert_total"))
      .toList

  /**
   * The counter VALUE for one `{outcome,reason}` pair, 0.0 when the series is absent. The
   * Prometheus registry is shared across the suite and `TestAspect.sequential` means an earlier
   * test's samples are still there, so a presence-only assertion (`lines.exists`) can pass on
   * someone else's increment (review NIT). Tests that own a reason another test also emits assert a
   * DELTA instead.
   */
  private def counterValue(body: String, outcome: String, reason: String): Double =
    customerUpsertLines(body)
      .filter(l => l.contains(s"""outcome="$outcome"""") && l.contains(s"""reason="$reason""""))
      // `<name>{<labels>} <value> [<timestamp>]` — the VALUE is the first field after the label set,
      // not the last field on the line (the publisher appends a millis timestamp).
      .flatMap(_.split('}').drop(1).headOption)
      .flatMap(_.trim.split("\\s+").headOption)
      .flatMap(v => scala.util.Try(v.toDouble).toOption)
      .sum

  /**
   * `householdContext = false` models the DEGRADED read: `SupportService.identity` `catchAll`s BOTH
   * `householdRepo.findById` and `billingRepo.findByHousehold` to `None`, yielding an empty
   * `fullName` and no `attributes` — the exact input on which `upsertTenantEntitlement`
   * short-circuits and writes no tenant.
   */
  private def driveUpsert(base: String, householdContext: Boolean = true): UIO[PlainOutcome] =
    ZIO
      .serviceWithZIO[PlainClient](
        _.upsertCustomer(
          PlainCustomerUpsert(
            externalId = "hh-7",
            tenantIdentifier = "7",
            email = "a@example.com",
            fullName = if householdContext then "Family Seven" else "",
            attributes =
              if householdContext then Map("plan" -> "beta", "householdName" -> "Family Seven")
              else Map.empty,
          ),
        ),
      )
      .provide(
        PlainClient.layer,
        ZLayer.succeed(
          SupportConfig(plain = PlainConfig(writeEnabled = true, apiKey = "k", apiBase = base)),
        ),
      )

  def spec = suite("Plain customer↔household reconcile on email collision (#2435)")(
    test("an email-keyed pre-existing customer is reconciled: externalId patched + tenant joined") {
      ZIO.scoped {
        for {
          cap <- routingServer()
          base = s"http://127.0.0.1:${cap.server.getAddress.getPort}/"
          outcome <- driveUpsert(base)
          bodies  <- cap.bodies.get
          customerVars = allVarsForOp(bodies, "upsertCustomer")
          joinVars     = allVarsForOp(bodies, "addCustomerToTenants")
        } yield {
          // Two customer upserts: the externalId-keyed primary (which collided), then the
          // emailAddress-keyed reconcile.
          val twoUpserts = assertTrue(customerVars.size == 2)

          val primaryKeyed = assertTrue(
            field(customerVars.head, "input", "identifier", "externalId")
              .contains(Json.Str("hh-7")),
          )

          val reconcile         = customerVars(1)
          // Keyed on the EMAIL — the only identifier that can reach a customer Plain created from an
          // inbound email (it has no externalId). `CustomerIdentifierInput`: one field only.
          val reconcileKeyed    = assertTrue(
            field(reconcile, "input", "identifier", "emailAddress")
              .contains(Json.Str("a@example.com")),
            field(reconcile, "input", "identifier", "externalId").isEmpty,
          )
          // …and it PATCHES externalId — `OptionalStringInput`, a wrapped `{ value }`.
          val patchesExternalId = assertTrue(
            field(reconcile, "input", "onUpdate", "externalId")
              .contains(Json.Obj("value" -> Json.Str("hh-7"))),
          )
          // The create half of the reconcile must carry externalId as a BARE scalar (the identifier
          // is the email, so nothing else would map a freshly-created customer to the household).
          val createCarriesId   = assertTrue(
            field(reconcile, "input", "onCreate", "externalId").contains(Json.Str("hh-7")),
          )

          // Plain's update input has no `tenantIdentifiers`, so membership is asserted separately.
          val joined = assertTrue(
            joinVars.size == 1,
            field(joinVars.head, "input", "customerIdentifier", "externalId")
              .contains(Json.Str("hh-7")),
            field(joinVars.head, "input", "tenantIdentifiers")
              .contains(Json.Arr(Json.Obj("externalId" -> Json.Str("7")))),
          )

          // The reconcile SUCCEEDED, so the caller sees a real success.
          val ok = assertTrue(outcome == PlainOutcome.Ok)

          twoUpserts && primaryKeyed && reconcileKeyed && patchesExternalId && createCarriesId &&
          joined && ok
        }
      }
    },
    test("the tenant is upserted BEFORE the customer join, so the membership target exists") {
      // `addCustomerToTenants` references the tenant by externalId; on a household whose first-ever
      // Plain write collides, that tenant would not exist yet if the entitlement write still ran
      // after the customer upsert.
      ZIO.scoped {
        for {
          cap <- routingServer()
          base = s"http://127.0.0.1:${cap.server.getAddress.getPort}/"
          _      <- driveUpsert(base)
          bodies <- cap.bodies.get
          tenantIdx = bodies.indexWhere(queryOf(_).contains("upsertTenant(input:"))
          joinIdx   = bodies.indexWhere(queryOf(_).contains("addCustomerToTenants(input:"))
        } yield assertTrue(tenantIdx >= 0, joinIdx > tenantIdx)
      }
    },
    test("a successful reconcile meters outcome=ok reason=reconciled") {
      // Asserted as a DELTA, not presence: an earlier test in this sequential suite already emitted
      // this exact `{outcome,reason}` pair into the shared registry, so `lines.exists` would pass
      // even if THIS drive metered nothing (review NIT).
      ZIO.scoped {
        for {
          cap <- routingServer()
          base = s"http://127.0.0.1:${cap.server.getAddress.getPort}/"
          _      <- tickPublisher
          before <- scrape.catchAll(resp => resp.body.asString.orDie)
          _      <- driveUpsert(base)
          _      <- tickPublisher
          after  <- scrape.catchAll(resp => resp.body.asString.orDie)
        } yield assertTrue(
          counterValue(after, "ok", "reconciled") - counterValue(before, "ok", "reconciled") == 1.0,
        )
      }
    },
    test("a FAILED reconcile is an error, attributed reason=email_collision — never a false Ok") {
      // The permanent-misconfiguration case: the collision is real and the reconcile could not
      // establish the mapping. It must not masquerade as success (#2408 rigor). This failure carries
      // no permission/schema marker, so it lands in the `email_collision` catch-all.
      ZIO.scoped {
        for {
          cap <- routingServer(reconcileResp =
            """{"data":{"upsertCustomer":{"customer":null,"error":{"message":"internal server error","code":"internal","type":"INTERNAL"}}}}""",
          )
          base = s"http://127.0.0.1:${cap.server.getAddress.getPort}/"
          _       <- tickPublisher
          before  <- scrape.catchAll(resp => resp.body.asString.orDie)
          outcome <- driveUpsert(base)
          _       <- tickPublisher
          after   <- scrape.catchAll(resp => resp.body.asString.orDie)
        } yield assertTrue(
          outcome == PlainOutcome.Error,
          counterValue(after, "error", "email_collision") -
            counterValue(before, "error", "email_collision") == 1.0,
        )
      }
    },
    test(
      "a reconcile whose tenant JOIN is DENIED attributes reason=permission, not email_collision",
    ) {
      // Review finding: hard-coding `email_collision` on both reconcile legs mislabelled the one
      // denial the dashboard promises `permission` covers — `customerTenantMembership:create` can
      // ONLY be denied on `addCustomerToTenants`. And it is still an error, not a silent
      // half-mapping: a customer patched with the household id but never joined to the tenant is
      // just as broken as no patch at all.
      ZIO.scoped {
        for {
          cap <- routingServer(tenantsResp =
            """{"data":{"addCustomerToTenants":{"error":{"message":"Insufficient permissions, missing \"customerTenantMembership:create\"","code":"insufficient_permissions","type":"FORBIDDEN"}}}}""",
          )
          base = s"http://127.0.0.1:${cap.server.getAddress.getPort}/"
          _       <- tickPublisher
          before  <- scrape.catchAll(resp => resp.body.asString.orDie)
          outcome <- driveUpsert(base)
          _       <- tickPublisher
          after   <- scrape.catchAll(resp => resp.body.asString.orDie)
        } yield assertTrue(
          outcome == PlainOutcome.Error,
          counterValue(after, "error", "permission") -
            counterValue(before, "error", "permission") == 1.0,
          // and NOT mislabelled as the generic collision bucket
          counterValue(after, "error", "email_collision") ==
            counterValue(before, "error", "email_collision"),
        )
      }
    },
    test(
      "a JOIN target missing because the tenant was SKIPPED is transient `error`, not `schema`",
    ) {
      // The reachable degraded-read path, end to end (review run 3): with NO household context
      // `upsertTenantEntitlement` short-circuits, so no tenant is ever written and the join has no
      // target. `classifyFieldFailure` maps every not-found to SCHEMA — right for an unregistered
      // tenant FIELD (never self-heals), wrong here, where it would fire a "SCHEMA DRIFT … will never
      // self-heal" ERROR and a provisioning-gap alert for a passing DB blip that the next identity
      // call repairs. Asserts the WHOLE chain, not just the classification: no `upsertTenant` request
      // was made at all.
      ZIO.scoped {
        for {
          cap <- routingServer(tenantsResp = JoinTargetNotFound)
          base = s"http://127.0.0.1:${cap.server.getAddress.getPort}/"
          _       <- tickPublisher
          before  <- scrape.catchAll(resp => resp.body.asString.orDie)
          outcome <- driveUpsert(base, householdContext = false)
          bodies  <- cap.bodies.get
          _       <- tickPublisher
          after   <- scrape.catchAll(resp => resp.body.asString.orDie)
        } yield assertTrue(
          // the short-circuit really happened — this is what makes the miss transient
          allVarsForOp(bodies, "upsertTenant").isEmpty,
          // still an error — the mapping did NOT land, so it must never read as success
          outcome == PlainOutcome.Error,
          counterValue(after, "error", "error") - counterValue(before, "error", "error") == 1.0,
          // and NOT in either permanent bucket
          counterValue(after, "error", "schema") == counterValue(before, "error", "schema"),
          counterValue(after, "error", "email_collision") ==
            counterValue(before, "error", "email_collision"),
        )
      }
    },
    test("a JOIN target missing after the tenant write FAILED is permanent, not transient") {
      // The other half of the same finding: `not_found` says the target is missing, never WHY, so a
      // permanently-failed tenant write must NOT ride the transient bucket just because the join
      // reports the same code. The tenant failure itself is separately logError'd + metered on
      // support_tenant_upsert_total{reason=tenant} (#2410); the customer side needs a PERMANENT bucket.
      ZIO.scoped {
        for {
          cap <- routingServer(
            tenantsResp = JoinTargetNotFound,
            tenantResp =
              """{"data":{"upsertTenant":{"tenant":null,"error":{"message":"You do not have permission to perform this action.","code":"forbidden","type":"FORBIDDEN"}}}}""",
          )
          base = s"http://127.0.0.1:${cap.server.getAddress.getPort}/"
          _       <- tickPublisher
          before  <- scrape.catchAll(resp => resp.body.asString.orDie)
          outcome <- driveUpsert(base)
          _       <- tickPublisher
          after   <- scrape.catchAll(resp => resp.body.asString.orDie)
        } yield assertTrue(
          outcome == PlainOutcome.Error,
          counterValue(after, "error", "email_collision") -
            counterValue(before, "error", "email_collision") == 1.0,
          // NOT laundered into the transient bucket
          counterValue(after, "error", "error") == counterValue(before, "error", "error"),
        )
      }
    },
    test("a JOIN not_found when the tenant WAS written keeps the ordinary attribution") {
      // The tenant exists, so `not_found` is about something else (the customer identifier, or a
      // contract change) — genuinely anomalous, and the transient bucket would be wrong. Guards the
      // threading: if `TenantWrite` were ever collapsed back to "assume transient", this fails.
      ZIO.scoped {
        for {
          cap <- routingServer(tenantsResp = JoinTargetNotFound)
          base = s"http://127.0.0.1:${cap.server.getAddress.getPort}/"
          _       <- tickPublisher
          before  <- scrape.catchAll(resp => resp.body.asString.orDie)
          outcome <- driveUpsert(base)
          bodies  <- cap.bodies.get
          _       <- tickPublisher
          after   <- scrape.catchAll(resp => resp.body.asString.orDie)
        } yield assertTrue(
          // the tenant WAS written on this drive
          allVarsForOp(bodies, "upsertTenant").size == 1,
          outcome == PlainOutcome.Error,
          counterValue(after, "error", "schema") - counterValue(before, "error", "schema") == 1.0,
          counterValue(after, "error", "error") == counterValue(before, "error", "error"),
        )
      }
    },
    test("a clean first-try upsert meters outcome=ok reason=ok and does NOT reconcile") {
      // The overwhelmingly common path must be untouched: ONE customer upsert, keyed on externalId,
      // no email-keyed re-upsert and no membership call.
      ZIO.scoped {
        for {
          cap <- routingServer(collide = false)
          base = s"http://127.0.0.1:${cap.server.getAddress.getPort}/"
          outcome <- driveUpsert(base)
          bodies  <- cap.bodies.get
          _       <- tickPublisher
          body    <- scrape.catchAll(resp => resp.body.asString.orDie)
          lines = customerUpsertLines(body)
        } yield assertTrue(
          outcome == PlainOutcome.Ok,
          allVarsForOp(bodies, "upsertCustomer").size == 1,
          allVarsForOp(bodies, "addCustomerToTenants").isEmpty,
          lines.exists(l => l.contains("""outcome="ok"""") && l.contains("""reason="ok"""")),
        )
      }
    },
  ).provideSomeLayer[TestEnvironment](MetricsRuntime.prometheus(pollInterval)) @@
    TestAspect.sequential
}
