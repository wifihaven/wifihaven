package wifihaven.api.feature

import wifihaven.api.{PlainConfig, SupportConfig}
import wifihaven.api.support.*
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import zio.*
import zio.json.EncoderOps
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
 * #2240 — entitlement (plan / founding / household name) rides on the Plain *tenant*, not the
 * customer (Plain's customer input has no attributes/customFields channel). So a single
 * `upsertCustomer` DTO drives THREE Plain writes: the customer upsert, `upsertTenant` (household
 * name), and one `upsertTenantField` per entitlement field. This spec asserts all of them.
 *
 * This stubs the Plain HTTP transport (a JDK [[HttpServer]] pointed at by `cfg.apiBase`, no repo
 * mocks, no network) and asserts the exact wire bodies the LIVE client emits — the one thing the
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
 *     update;
 *   - `UpsertTenantInput` — `{ identifier: { externalId }, externalId, name }`;
 *   - `UpsertTenantFieldInput` — `{ tenantFieldIdentifier: { tenantId, externalFieldId }, type,
 *     <typed>Value }`.
 */
object PlainClientWireSpec extends ZIOSpecDefault {

  // A capture server: serves a Plain-style 200 that carries BOTH `upsertCustomer.customer.id` and
  // `upsertTenant.tenant.id` (so the client can chain tenant-field writes), and stashes EVERY
  // request body in order for assertion. Started/stopped inside a scoped resource so the port is
  // released even on failure.
  private final class CaptureServer(val server: HttpServer, val bodies: Ref[List[String]])

  private def captureServer: ZIO[Scope, Throwable, CaptureServer] =
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
                """{"data":{"upsertCustomer":{"customer":{"id":"c_1"}},"upsertTenant":{"tenant":{"id":"t_1"}},"upsertTenantField":{"tenantField":{"id":"tf_1"}}}}"""
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

  // A capture server that returns an ARBITRARY response body (to model Plain's HTTP-200 payload-level
  // failures — `{"data":{"<op>":{"...":null,"error":{"message":"..."}}}}`), still stashing every
  // request body in order (#2408).
  private def captureServerReturning(resp: String): ZIO[Scope, Throwable, CaptureServer] =
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

  private def parse(body: String): Json =
    Json.decoder.decodeJson(body).toOption.get

  // The GraphQL `query` string of a recorded request body (for asserting the mutation name).
  private def queryOf(body: String): String =
    field(parse(body), "query").collect { case Json.Str(q) => q }.getOrElse("")

  // Drill `input.onCreate` / `input.onUpdate` etc. out of a parsed variables object.
  private def field(j: Json, path: String*): Option[Json] =
    path.foldLeft(Option(j)) { (acc, key) =>
      acc.flatMap {
        case o: Json.Obj => o.fields.collectFirst { case (k, v) if k == key => v }
        case _           => None
      }
    }

  // Pick the recorded request body whose GraphQL `query` names `op`, and return its `variables`.
  private def varsForOp(bodies: List[String], op: String): Option[Json] =
    bodies
      .map(parse)
      .find(b =>
        field(b, "query").exists { case Json.Str(q) => q.contains(s"$op(input:"); case _ => false },
      )
      .map(b => field(b, "variables").getOrElse(b))

  def spec = suite("PlainClient.Live wire shape (#2253, #2240)")(
    test(
      "upsertCustomer serializes Plain's UpsertCustomerInput — plural tenantIdentifiers + correct onCreate/onUpdate",
    ) {
      ZIO.scoped {
        for {
          cap <- captureServer
          base   = s"http://127.0.0.1:${cap.server.getAddress.getPort}/"
          // #2266: write API is now an EXPLICIT flag; the wire test drives the Live client.
          cfg    = SupportConfig(plain =
            PlainConfig(writeEnabled = true, apiKey = "test-key", apiBase = base),
          )
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
          bodies  <- cap.bodies.get
          // The customer-upsert request's `variables` object we assert against.
          variables = varsForOp(bodies, "upsertCustomer").get
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
    // #2240: entitlement rides on the Plain TENANT. One upsertCustomer DTO with plan+founding drives
    // upsertTenant (household name) + one upsertTenantField per entitlement field, keyed on the
    // internal tenant id returned by upsertTenant.
    test(
      "upsertCustomer also upserts the tenant (name) + tenant fields (plan string, founding bool)",
    ) {
      ZIO.scoped {
        for {
          cap <- captureServer
          base   = s"http://127.0.0.1:${cap.server.getAddress.getPort}/"
          cfg    = SupportConfig(plain =
            PlainConfig(writeEnabled = true, apiKey = "test-key", apiBase = base),
          )
          client = PlainClient.layer
          outcome <- ZIO
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
            .provide(client, ZLayer.succeed(cfg))
          bodies  <- cap.bodies.get
          tenantVars = varsForOp(bodies, "upsertTenant")
          // Both field writes serialize with the SAME mutation name; collect all their variables.
          fieldVars  = bodies
            .map(parse)
            .filter(b =>
              field(b, "query").exists {
                case Json.Str(q) => q.contains("upsertTenantField(input:"); case _ => false
              },
            )
            .map(b => field(b, "variables").getOrElse(b))
        } yield {
          val ok = assertTrue(outcome == PlainOutcome.Ok)

          // upsertTenant carries the household id (identifier + top-level externalId) and the name.
          val tenantShape = assertTrue(
            field(tenantVars.get, "input", "identifier", "externalId").contains(Json.Str("7")),
            field(tenantVars.get, "input", "externalId").contains(Json.Str("7")),
            field(tenantVars.get, "input", "name").contains(Json.Str("Family Seven")),
          )

          // Exactly two field writes: plan (STRING_TYPE/stringValue) + founding (BOOLEAN_TYPE/booleanValue),
          // both keyed on the INTERNAL tenant id t_1 from upsertTenant's response.
          val twoFields     = assertTrue(fieldVars.size == 2)
          val planField     = assertTrue(
            fieldVars.exists { v =>
              field(v, "input", "tenantFieldIdentifier", "tenantId").contains(Json.Str("t_1")) &&
              field(v, "input", "tenantFieldIdentifier", "externalFieldId").contains(
                Json.Str("plan"),
              ) &&
              field(v, "input", "type").contains(Json.Str("STRING_TYPE")) &&
              field(v, "input", "stringValue").contains(Json.Str("beta"))
            },
          )
          val foundingField = assertTrue(
            fieldVars.exists { v =>
              field(v, "input", "tenantFieldIdentifier", "externalFieldId").contains(
                Json.Str("founding"),
              ) &&
              field(v, "input", "type").contains(Json.Str("BOOLEAN_TYPE")) &&
              field(v, "input", "booleanValue").contains(Json.Bool(true))
            },
          )
          // householdName is the tenant NAME, never a field.
          val noNameField   = assertTrue(
            !fieldVars.exists(v =>
              field(v, "input", "tenantFieldIdentifier", "externalFieldId")
                .contains(Json.Str("householdName")),
            ),
          )

          ok && tenantShape && twoFields && planField && foundingField && noNameField
        }
      }
    },
    // #2408 Problem 1: Plain returns HTTP 200 for a mutation-level FAILURE, carrying a PAYLOAD-level
    // `error { message }` with a null result object — NOT a top-level `errors` array. The old
    // `!body.contains("\"errors\"")` check passed it as success, so a dropped write reported Ok and
    // the real Plain error was never logged. The generalized check must classify this Error.
    test(
      "#2408: an HTTP-200 payload-level error / null result object ⇒ PlainOutcome.Error, not Ok",
    ) {
      ZIO.scoped {
        for {
          cap <- captureServerReturning(
            """{"data":{"replyToThread":{"error":{"message":"thread not found: th_x"}}}}""",
          )
          base = s"http://127.0.0.1:${cap.server.getAddress.getPort}/"
          cfg  = SupportConfig(plain =
            PlainConfig(writeEnabled = true, apiKey = "test-key", apiBase = base),
          )
          outcome <- ZIO
            .serviceWithZIO[PlainClient](
              _.writeThread(PlainThreadWrite(threadId = "th_x", markdown = "the answer")),
            )
            .provide(PlainClient.layer, ZLayer.succeed(cfg))
        } yield assertTrue(outcome == PlainOutcome.Error)
      }
    },
    // #2408 defense-in-depth: an explicitly-null payload (no result, no error, no top-level errors)
    // must never read as success — the mutation did not land.
    test("#2408: an HTTP-200 null mutation payload ⇒ PlainOutcome.Error, not Ok") {
      ZIO.scoped {
        for {
          cap <- captureServerReturning("""{"data":{"replyToThread":null}}""")
          base = s"http://127.0.0.1:${cap.server.getAddress.getPort}/"
          cfg  = SupportConfig(plain =
            PlainConfig(writeEnabled = true, apiKey = "test-key", apiBase = base),
          )
          outcome <- ZIO
            .serviceWithZIO[PlainClient](
              _.writeThread(PlainThreadWrite(threadId = "th_x", markdown = "the answer")),
            )
            .provide(PlainClient.layer, ZLayer.succeed(cfg))
        } yield assertTrue(outcome == PlainOutcome.Error)
      }
    },
    // #2408 Problem 2: the reply must post INTO the customer's existing thread via `replyToThread`
    // (threadId + textContent/markdownContent), NEVER `createThread`.
    test("#2408: writeThread emits replyToThread targeting the bound threadId") {
      ZIO.scoped {
        for {
          cap <- captureServerReturning("""{"data":{"replyToThread":{"error":null}}}""")
          base = s"http://127.0.0.1:${cap.server.getAddress.getPort}/"
          cfg  = SupportConfig(plain =
            PlainConfig(writeEnabled = true, apiKey = "test-key", apiBase = base),
          )
          outcome <- ZIO
            .serviceWithZIO[PlainClient](
              _.writeThread(PlainThreadWrite(threadId = "th_bound", markdown = "the answer")),
            )
            .provide(PlainClient.layer, ZLayer.succeed(cfg))
          bodies  <- cap.bodies.get
          vars = varsForOp(bodies, "replyToThread").getOrElse(Json.Null)
        } yield assertTrue(
          // A `null`-error payload with the reply mutation is a real success.
          outcome == PlainOutcome.Ok,
          // The reply targets the EXISTING thread — replyToThread, never createThread.
          bodies.exists(queryOf(_).contains("replyToThread")),
          !bodies.exists(queryOf(_).contains("createThread")),
          field(vars, "input", "threadId").contains(Json.Str("th_bound")),
          // textContent is required by Plain's schema; markdownContent carries the rich body.
          field(vars, "input", "textContent").contains(Json.Str("the answer")),
          field(vars, "input", "markdownContent").contains(Json.Str("the answer")),
        )
      }
    },
    // ── #2430: the thread-timeline READ that gives the stateless responder its context ──
    test("#2430: threadHistory queries ONLY the bound thread and role-labels the turns") {
      // Plain's timeline is a union of actors × entries. We keep the two customer-visible message
      // kinds (ChatEntry / EmailEntry) and label the role from the ACTOR: customer, us (the
      // machine user posts every AI reply), or a human teammate. Everything else — here a NoteEntry
      // (operator-only commentary that must never reach the prompt) and a system status transition
      // — is DROPPED. Order comes from `timestamp.iso8601`, so a connection returning newest-first
      // still renders oldest-first.
      val timeline =
        """{"data":{"thread":{"id":"th_bound","timelineEntries":{"edges":[
          |{"node":{"timestamp":{"iso8601":"2026-07-20T10:00:03Z"},"actor":{"__typename":"UserActor"},
          | "entry":{"__typename":"EmailEntry","textContent":"Sameer here, taking a look."}}},
          |{"node":{"timestamp":{"iso8601":"2026-07-20T10:00:02Z"},"actor":{"__typename":"MachineUserActor"},
          | "entry":{"__typename":"ChatEntry","text":"That's his weekday schedule."}}},
          |{"node":{"timestamp":{"iso8601":"2026-07-20T10:00:01Z"},"actor":{"__typename":"CustomerActor"},
          | "entry":{"__typename":"ChatEntry","text":"my son's iPad is blocked"}}},
          |{"node":{"timestamp":{"iso8601":"2026-07-20T10:00:04Z"},"actor":{"__typename":"UserActor"},
          | "entry":{"__typename":"NoteEntry","text":"internal: check the router logs"}}},
          |{"node":{"timestamp":{"iso8601":"2026-07-20T10:00:05Z"},"actor":{"__typename":"SystemActor"},
          | "entry":{"__typename":"ThreadStatusTransitionedEntry"}}}
          |]}}}}""".stripMargin
      ZIO.scoped {
        for {
          cap <- captureServerReturning(timeline)
          base = s"http://127.0.0.1:${cap.server.getAddress.getPort}/"
          cfg  = SupportConfig(plain =
            PlainConfig(writeEnabled = true, apiKey = "test-key", apiBase = base),
          )
          msgs   <- ZIO
            .serviceWithZIO[PlainClient](_.threadHistory("th_bound", 30))
            .provide(PlainClient.layer, ZLayer.succeed(cfg))
          bodies <- cap.bodies.get
          vars = bodies.headOption
            .map(parse)
            .flatMap(field(_, "variables"))
            .getOrElse(Json.Null)
        } yield assertTrue(
          // the query is scoped to the ONE bound thread — no customer/tenant/household parameter.
          bodies.size == 1,
          queryOf(bodies.head).contains("thread(threadId: $threadId)"),
          queryOf(bodies.head).contains("timelineEntries(last: $last)"),
          field(vars, "threadId").contains(Json.Str("th_bound")),
          // oldest-first by timestamp, regardless of the order Plain returned them in.
          msgs == List(
            PlainThreadMessage(ThreadMessageRole.Customer, "my son's iPad is blocked"),
            PlainThreadMessage(ThreadMessageRole.AiAssistant, "That's his weekday schedule."),
            PlainThreadMessage(ThreadMessageRole.HumanTeammate, "Sameer here, taking a look."),
          ),
        )
      }
    },
    test("#2430: the ACTOR decides the role — quoted AI attribution never promotes a turn") {
      // Review finding (run 1): a content heuristic that outranked the actor mislabeled ordinary
      // traffic. On the email intake a customer replying QUOTES our reply — attribution line and
      // all — back at us in `EmailEntry.textContent`. That turn is the CUSTOMER's; labelling it
      // `ai_assistant` would make the agent read the customer's words as its own prior answer AND
      // defeat the echo dedup (which matches on the Customer role). It is also a forgery vector:
      // the attribution string is visible in every AI reply, so anyone can paste it.
      val quoted   =
        SupportResponder.AiReplyAttribution + " > you said it was the schedule. No, it wasn't."
      val timeline =
        s"""{"data":{"thread":{"id":"th_bound","timelineEntries":{"edges":[
           |{"node":{"timestamp":{"iso8601":"2026-07-20T10:00:01Z"},"actor":{"__typename":"CustomerActor"},
           | "entry":{"__typename":"EmailEntry","textContent":${quoted.toJson}}}},
           |{"node":{"timestamp":{"iso8601":"2026-07-20T10:00:02Z"},"actor":{"__typename":"MachineUserActor"},
           | "entry":{"__typename":"ChatEntry","text":"Let me check."}}},
           |{"node":{"timestamp":{"iso8601":"2026-07-20T10:00:03Z"},"actor":null,
           | "entry":{"__typename":"ChatEntry","text":${(SupportResponder.AiReplyAttribution + " an unknown-actor reply of ours").toJson}}}}
           |]}}}}""".stripMargin
      ZIO.scoped {
        for {
          cap <- captureServerReturning(timeline)
          base = s"http://127.0.0.1:${cap.server.getAddress.getPort}/"
          cfg  = SupportConfig(plain =
            PlainConfig(writeEnabled = true, apiKey = "test-key", apiBase = base),
          )
          msgs <- ZIO
            .serviceWithZIO[PlainClient](_.threadHistory("th_bound", 30))
            .provide(PlainClient.layer, ZLayer.succeed(cfg))
        } yield assertTrue(
          msgs.map(_.role) == List(
            // the quoted-attribution turn stays the CUSTOMER's — the actor wins…
            ThreadMessageRole.Customer,
            ThreadMessageRole.AiAssistant,
            // …and the attribution check still applies where there is NO authoritative actor.
            ThreadMessageRole.AiAssistant,
          ),
        )
      }
    },
    test("#2430: entries Plain returns that NONE parse ⇒ its own bucket, not silent `empty`") {
      // Schema drift must not hide in `empty` (the normal first-message outcome). Entries present +
      // zero turns parsed is its own loud, metered state; the read still degrades to no history.
      val drifted =
        """{"data":{"thread":{"id":"th_bound","timelineEntries":{"edges":[
          |{"node":{"timestamp":{"iso8601":"2026-07-20T10:00:01Z"},"actor":{"__typename":"CustomerActor"},
          | "entry":{"__typename":"SomeNewEntryKind","body":"hi"}}}
          |]}}}}""".stripMargin
      ZIO.scoped {
        for {
          cap <- captureServerReturning(drifted)
          base = s"http://127.0.0.1:${cap.server.getAddress.getPort}/"
          cfg  = SupportConfig(plain =
            PlainConfig(writeEnabled = true, apiKey = "test-key", apiBase = base),
          )
          msgs <- ZIO
            .serviceWithZIO[PlainClient](_.threadHistory("th_bound", 30))
            .provide(PlainClient.layer, ZLayer.succeed(cfg))
        } yield assertTrue(
          msgs.isEmpty,
          // the discriminator the metric buckets on: entries WERE returned.
          PlainClient.timelineEntryCount(drifted) == 1,
        )
      }
    },
    test("#2430: a failure detail never carries customer conversation text into the logs") {
      // `sendForBody` appends up to 500 chars of the raw response body; on THIS path that body is
      // the customer's own messages (a GraphQL partial failure is a 200 whose `data` still carries
      // the timeline). It is stripped before it reaches the log line / Loki.
      val detail =
        "GraphQL errors: something broke (body: {\"data\":{\"thread\":{\"timelineEntries\":" +
          "{\"edges\":[{\"node\":{\"entry\":{\"text\":\"my kid's iPad is blocked\"}}}]}}}})"
      assertTrue(
        PlainClient.redactBody(detail) == "GraphQL errors: something broke (body redacted)",
        !PlainClient.redactBody(detail).contains("my kid's iPad"),
        // a detail with no body tail is passed through unchanged.
        PlainClient.redactBody("timed out") == "timed out",
        // a GraphQL query-validation error is SCHEMA drift, not the transient bucket.
        PlainClient.classifyFieldFailure(
          "GraphQL errors: Cannot query field \"timelineEntries\" on type \"Thread\"",
        ) == "schema",
      )
    },
    test("#2430: a thread-history read failure degrades to no history, never a failure") {
      // The `thread:read` permission gap Plain reports as a payload error — fail-open, so the
      // dispatch still happens with the latest message alone (SupportResponderSpec pins that end).
      ZIO.scoped {
        for {
          cap <- captureServerReturning(
            """{"errors":[{"message":"Insufficient permissions, missing \"thread:read\""}]}""",
          )
          base = s"http://127.0.0.1:${cap.server.getAddress.getPort}/"
          cfg  = SupportConfig(plain =
            PlainConfig(writeEnabled = true, apiKey = "test-key", apiBase = base),
          )
          msgs <- ZIO
            .serviceWithZIO[PlainClient](_.threadHistory("th_bound", 30))
            .provide(PlainClient.layer, ZLayer.succeed(cfg))
        } yield assertTrue(msgs.isEmpty)
      }
    },
    test("writeEnabled=false ⇒ Disabled no-op, no network call (ships dark, #2266)") {
      ZIO.scoped {
        for {
          cap <- captureServer
          base = s"http://127.0.0.1:${cap.server.getAddress.getPort}/"
          // #2266: writeEnabled defaults false ⇒ Disabled client (explicit flag, not key presence).
          cfg  = SupportConfig(plain = PlainConfig(apiBase = base))
          outcome <- ZIO
            .serviceWithZIO[PlainClient](
              _.upsertCustomer(
                PlainCustomerUpsert("hh-1", "1", "x@example.com", "Fam", Map.empty),
              ),
            )
            .provide(PlainClient.layer, ZLayer.succeed(cfg))
          bodies  <- cap.bodies.get
        } yield assertTrue(outcome == PlainOutcome.Disabled, bodies.isEmpty)
      }
    },
  ) @@ TestAspect.sequential
}
