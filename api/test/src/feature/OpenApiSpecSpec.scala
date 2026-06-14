package wifihaven.api.feature

import wifihaven.api.openapi.OpenApiSpec
import wifihaven.api.routes.HealthRoutes
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/**
 * #638 — generator + served endpoint coverage. The generator walks any `Routes` tree and emits a
 * valid OpenAPI 3.0.3 document; the served `/api/openapi.json` + `/api/docs` are exercised in
 * [[OpenApiRoutesSpec]] (no DB needed there either, so kept colocated).
 */
object OpenApiSpecSpec extends ZIOSpecDefault {

  // Construct a small but varied Routes value covering: GET, POST, a path parameter, a public
  // endpoint, and a method overload on the same path. This is intentionally NOT the production
  // tree — exercising the generator surface, not the deployed surface.
  private val sample: Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "auth" / "me"             -> handler((_: Request) => Response.ok),
      Method.POST / "api" / "auth" / "login"         -> handler((_: Request) => Response.ok),
      Method.GET / "api" / "profiles"                -> handler((_: Request) => Response.ok),
      Method.GET / "api" / "profiles" / long("id")   -> handler((_: Request) => Response.ok),
      Method.PATCH / "api" / "profiles" / long("id") -> handler((_: Request) => Response.ok),
      Method.GET / "api" / "health"                  -> handler((_: Request) => Response.ok),
      Method.GET / "api" / "router" / "policy"       -> handler((_: Request) => Response.ok),
    ) ++ HealthRoutes.routes(ZIO.succeed(true), ZIO.unit)

  def spec = suite("OpenApiSpec generator (#638)")(
    test("emits a valid OpenAPI 3.x envelope") {
      val json = OpenApiSpec.generate("test-sha", sample).toJson
      val ast  = json.fromJson[Json].toOption.get.asObject.get
      assertTrue(
        ast.fields.toMap.get("openapi").exists(_.asString.exists(_.startsWith("3."))),
      ) &&
      assertTrue(ast.fields.toMap.contains("info")) &&
      assertTrue(ast.fields.toMap.contains("paths"))
    },
    test("info carries the build version") {
      val ast  = OpenApiSpec.generate("deadbeef", sample).asObject.get
      val info = ast.fields.toMap("info").asObject.get.fields.toMap
      assertTrue(info("version").asString.contains("deadbeef")) &&
      assertTrue(info("title").asString.exists(_.toLowerCase.contains("wifihaven")))
    },
    test("covers the well-known auth + policy routes") {
      val ast   = OpenApiSpec.generate("v", sample).asObject.get
      val paths = ast.fields.toMap("paths").asObject.get.fields.toMap
      assertTrue(paths.contains("/api/auth/login")) &&
      assertTrue(paths.contains("/api/auth/me")) &&
      assertTrue(paths.contains("/api/profiles")) &&
      assertTrue(paths.contains("/api/router/policy")) &&
      assertTrue(paths.contains("/api/health"))
    },
    test("renders path parameters in OpenAPI {name} form") {
      val ast   = OpenApiSpec.generate("v", sample).asObject.get
      val paths = ast.fields.toMap("paths").asObject.get.fields.toMap
      assertTrue(paths.contains("/api/profiles/{id}"))
    },
    test("groups multiple methods under the same path") {
      val ast     = OpenApiSpec.generate("v", sample).asObject.get
      val paths   = ast.fields.toMap("paths").asObject.get.fields.toMap
      val byIdOps = paths("/api/profiles/{id}").asObject.get.fields.toMap
      assertTrue(byIdOps.contains("get")) && assertTrue(byIdOps.contains("patch"))
    },
    test("declares the bearer-JWT security scheme") {
      val ast     = OpenApiSpec.generate("v", sample).asObject.get
      val schemes = ast.fields
        .toMap("components")
        .asObject
        .get
        .fields
        .toMap("securitySchemes")
        .asObject
        .get
        .fields
        .toMap
      val bearer  = schemes("bearerAuth").asObject.get.fields.toMap
      assertTrue(bearer("type").asString.contains("http")) &&
      assertTrue(bearer("scheme").asString.contains("bearer"))
    },
    test("authenticated routes carry a security requirement") {
      val ast   = OpenApiSpec.generate("v", sample).asObject.get
      val paths = ast.fields.toMap("paths").asObject.get.fields.toMap
      val meGet = paths("/api/auth/me").asObject.get.fields.toMap("get").asObject.get
      assertTrue(meGet.fields.toMap.contains("security"))
    },
    test("public probes do NOT carry a security requirement") {
      val ast       = OpenApiSpec.generate("v", sample).asObject.get
      val paths     = ast.fields.toMap("paths").asObject.get.fields.toMap
      val healthGet = paths("/api/health").asObject.get.fields.toMap("get").asObject.get
      val loginPost = paths("/api/auth/login").asObject.get.fields.toMap("post").asObject.get
      assertTrue(!healthGet.fields.toMap.contains("security")) &&
      assertTrue(!loginPost.fields.toMap.contains("security"))
    },
  )
}
