package wifihaven.api.feature

import wifihaven.api.SupportConfig
import wifihaven.api.support.*
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import zio.*
import zio.json.ast.Json
import zio.test.*

import java.io.OutputStream
import java.net.InetSocketAddress

/**
 * #2253 — the Plain `upsertCustomer` write must serialize to Plain's REAL `UpsertCustomerInput`
 * schema. #2199 shipped `tenantIdentifier` (singular object) on both onCreate and onUpdate, plus a
 * bare-scalar `fullName` on onUpdate — every field wrong for the update path — so every household
 * upsert 400'd on staging and `GET /api/support/identity` failed.
 *
 * This stubs the Plain HTTP transport (a JDK [[HttpServer]] pointed at by `cfg.apiBase`, no repo
 * mocks, no network) and asserts the exact wire body the LIVE client emits — the one thing the
 * recorder-based [[SupportIdentitySpec]] can't see, because the recorder captures the DTO, not the
 * serialized GraphQL variables.
 *
 * Field shapes pinned here are quoted from Plain's published schema (team-plain/typescript-sdk
 * `src/graphql/types.ts`):
 *   - `UpsertCustomerOnCreateInput.tenantIdentifiers: [TenantIdentifierInput!]` — PLURAL, a list of
 *     `{ externalId }` (the household id — household-gating);
 *   - `UpsertCustomerOnCreateInput.fullName: String` — a bare scalar;
 *   - `UpsertCustomerOnUpdateInput.fullName: StringInput` — a WRAPPED `{ value }`;
 *   - `UpsertCustomerOnUpdateInput` has NO `tenantIdentifiers` field — so it must be absent on
 *     update.
 */
object PlainClientWireSpec extends ZIOSpecDefault {

  // A one-request capture server: serves a Plain-style 200 `{data:{upsertCustomer:{customer:{id}}}}`
  // and stashes the last request body for assertion. Started/stopped inside a scoped resource so the
  // port is released even on failure.
  private final class CaptureServer(val server: HttpServer, val lastBody: Ref[Option[String]])

  private def captureServer: ZIO[Scope, Throwable, CaptureServer] =
    for {
      bodyRef <- Ref.make(Option.empty[String])
      runtime <- ZIO.runtime[Any]
      server  <- ZIO.acquireRelease(
        ZIO.attempt {
          val s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
          s.createContext(
            "/",
            (exchange: HttpExchange) => {
              val body             = new String(exchange.getRequestBody.readAllBytes(), "UTF-8")
              Unsafe.unsafe { implicit u =>
                runtime.unsafe.run(bodyRef.set(Some(body))).getOrThrowFiberFailure()
              }
              val resp             = """{"data":{"upsertCustomer":{"customer":{"id":"c_1"}}}}"""
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
    } yield new CaptureServer(server, bodyRef)

  private def parse(body: String): Json =
    Json.decoder.decodeJson(body).toOption.get

  // Drill `input.onCreate` / `input.onUpdate` etc. out of a parsed variables object.
  private def field(j: Json, path: String*): Option[Json] =
    path.foldLeft(Option(j)) { (acc, key) =>
      acc.flatMap {
        case o: Json.Obj => o.fields.collectFirst { case (k, v) if k == key => v }
        case _           => None
      }
    }

  def spec = suite("PlainClient.Live wire shape (#2253)")(
    test(
      "upsertCustomer serializes Plain's UpsertCustomerInput — plural tenantIdentifiers + correct onCreate/onUpdate",
    ) {
      ZIO.scoped {
        for {
          cap <- captureServer
          base   = s"http://127.0.0.1:${cap.server.getAddress.getPort}/"
          cfg    = SupportConfig(plainApiKey = "test-key", apiBase = base)
          client = PlainClient.layer
          outcome <- ZIO
            .serviceWithZIO[PlainClient](
              _.upsertCustomer(
                PlainCustomerUpsert(
                  externalId = "hh-7",
                  tenantIdentifier = "7",
                  email = "a@example.com",
                  fullName = "Family Seven",
                  attributes = Map("plan" -> "beta"),
                ),
              ),
            )
            .provide(client, ZLayer.succeed(cfg))
          raw     <- cap.lastBody.get
          vars      = parse(raw.get)
          // The `variables` object we assert against.
          variables = field(vars, "variables").getOrElse(vars)
        } yield {
          // Live client, not the Disabled no-op (key is set).
          val liveOk = assertTrue(outcome == PlainOutcome.Ok)

          // identifier.externalId = household externalId
          val identifier =
            assertTrue(
              field(variables, "input", "identifier", "externalId").contains(Json.Str("hh-7")),
            )

          // onCreate: fullName is a BARE scalar; tenantIdentifiers is a PLURAL LIST of {externalId}.
          val onCreateFullName =
            assertTrue(
              field(variables, "input", "onCreate", "fullName").contains(Json.Str("Family Seven")),
            )
          val onCreateTenants  =
            assertTrue(
              field(variables, "input", "onCreate", "tenantIdentifiers")
                .contains(Json.Arr(Json.Obj("externalId" -> Json.Str("7")))),
            )
          // The singular field that 400'd must NOT appear anywhere.
          val noSingularCreate =
            assertTrue(field(variables, "input", "onCreate", "tenantIdentifier").isEmpty)
          val onCreateEmail    =
            assertTrue(
              field(variables, "input", "onCreate", "email").contains(
                Json.Obj("email" -> Json.Str("a@example.com"), "isVerified" -> Json.Bool(true)),
              ),
            )

          // onUpdate: fullName is WRAPPED {value}; there is NO tenantIdentifiers/tenantIdentifier.
          val onUpdateFullName  =
            assertTrue(
              field(variables, "input", "onUpdate", "fullName")
                .contains(Json.Obj("value" -> Json.Str("Family Seven"))),
            )
          val noTenantsOnUpdate =
            assertTrue(
              field(variables, "input", "onUpdate", "tenantIdentifiers").isEmpty,
              field(variables, "input", "onUpdate", "tenantIdentifier").isEmpty,
            )

          liveOk && identifier && onCreateFullName && onCreateTenants && noSingularCreate &&
          onCreateEmail && onUpdateFullName && noTenantsOnUpdate
        }
      }
    },
    test("no Plain key ⇒ Disabled no-op, no network call (ships dark)") {
      ZIO.scoped {
        for {
          cap <- captureServer
          base = s"http://127.0.0.1:${cap.server.getAddress.getPort}/"
          // Empty key ⇒ writeEnabled=false ⇒ Disabled client.
          cfg  = SupportConfig(apiBase = base)
          outcome <- ZIO
            .serviceWithZIO[PlainClient](
              _.upsertCustomer(
                PlainCustomerUpsert("hh-1", "1", "x@example.com", "Fam", Map.empty),
              ),
            )
            .provide(PlainClient.layer, ZLayer.succeed(cfg))
          raw     <- cap.lastBody.get
        } yield assertTrue(outcome == PlainOutcome.Disabled, raw.isEmpty)
      }
    },
  ) @@ TestAspect.sequential
}
