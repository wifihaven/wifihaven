package wifihaven.api.feature

import wifihaven.api.openapi.OpenApiSpec
import wifihaven.api.routes.HealthRoutes
import zio.*
import zio.http.*
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
      Method.GET / "api" / "router" / "policy"       -> handler((_: Request) => Response.ok),
    ) ++ HealthRoutes.routes(ZIO.succeed(true), ZIO.unit)

  private def asMap(j: Json): Map[String, Json] =
    j.asObject.get.fields.foldLeft(Map.empty[String, Json])((m, kv) => m + kv)

  def spec = suite("OpenApiSpec generator (#638)")(
    test("emits a valid OpenAPI 3.x envelope") {
      val ast = asMap(OpenApiSpec.generate("test-sha", sample))
      assertTrue(ast.get("openapi").flatMap(_.asString).exists(_.startsWith("3."))) &&
      assertTrue(ast.contains("info")) &&
      assertTrue(ast.contains("paths"))
    },
    test("info carries the build version") {
      val info = asMap(asMap(OpenApiSpec.generate("deadbeef", sample))("info"))
      assertTrue(info("version").asString.contains("deadbeef")) &&
      assertTrue(info("title").asString.exists(_.toLowerCase.contains("wifihaven")))
    },
    test("covers the well-known auth + policy routes") {
      val paths = asMap(asMap(OpenApiSpec.generate("v", sample))("paths"))
      assertTrue(paths.contains("/api/auth/login")) &&
      assertTrue(paths.contains("/api/auth/me")) &&
      assertTrue(paths.contains("/api/profiles")) &&
      assertTrue(paths.contains("/api/router/policy")) &&
      assertTrue(paths.contains("/api/health"))
    },
    test("renders path parameters in OpenAPI {name} form") {
      val paths = asMap(asMap(OpenApiSpec.generate("v", sample))("paths"))
      assertTrue(paths.contains("/api/profiles/{id}"))
    },
    test("groups multiple methods under the same path") {
      val paths   = asMap(asMap(OpenApiSpec.generate("v", sample))("paths"))
      val byIdOps = asMap(paths("/api/profiles/{id}"))
      assertTrue(byIdOps.contains("get")) && assertTrue(byIdOps.contains("patch"))
    },
    test("declares the bearer-JWT security scheme") {
      val root    = asMap(OpenApiSpec.generate("v", sample))
      val schemes = asMap(asMap(root("components"))("securitySchemes"))
      val bearer  = asMap(schemes("bearerAuth"))
      assertTrue(bearer("type").asString.contains("http")) &&
      assertTrue(bearer("scheme").asString.contains("bearer"))
    },
    test("authenticated routes carry a security requirement") {
      val paths = asMap(asMap(OpenApiSpec.generate("v", sample))("paths"))
      val meGet = asMap(asMap(paths("/api/auth/me"))("get"))
      assertTrue(meGet.contains("security"))
    },
    test("public probes do NOT carry a security requirement") {
      val paths     = asMap(asMap(OpenApiSpec.generate("v", sample))("paths"))
      val healthGet = asMap(asMap(paths("/api/health"))("get"))
      val loginPost = asMap(asMap(paths("/api/auth/login"))("post"))
      assertTrue(!healthGet.contains("security")) &&
      assertTrue(!loginPost.contains("security"))
    },
  )
}
