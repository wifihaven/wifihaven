package wifihaven.api.feature

import wifihaven.api.routes.OpenApiRoutes
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/**
 * #638 — `/api/openapi.json` serves the generated spec; `/api/docs` serves a Swagger UI page that
 * loads it. Pure route-level test (no DB, no auth), since both endpoints are unauthenticated by
 * design (operator/agent discovery surface).
 */
object OpenApiRoutesSpec extends ZIOSpecDefault {

  private val sample: Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "auth" / "me"     -> handler((_: Request) => Response.ok),
      Method.POST / "api" / "auth" / "login" -> handler((_: Request) => Response.ok),
    )

  private val under = OpenApiRoutes.routes("test-sha", sample)

  def spec = suite("/api/openapi.json + /api/docs (#638)")(
    test("/api/openapi.json returns JSON with the OpenAPI envelope") {
      for {
        resp <- under.runZIO(Request.get(URL.decode("/api/openapi.json").toOption.get))
        body <- resp.body.asString
        ast = body.fromJson[Json].toOption.get.asObject.get.fields.toMap
      } yield assertTrue(resp.status.code == 200) &&
        assertTrue(ast.get("openapi").flatMap(_.asString).exists(_.startsWith("3."))) &&
        assertTrue(ast.contains("paths"))
    },
    test("/api/docs returns an HTML page that points at the spec URL") {
      for {
        resp <- under.runZIO(Request.get(URL.decode("/api/docs").toOption.get))
        body <- resp.body.asString
      } yield assertTrue(resp.status.code == 200) &&
        assertTrue(
          resp.headers.get(Header.ContentType).exists(_.toString.toLowerCase.contains("html")),
        ) &&
        assertTrue(body.contains("/api/openapi.json")) &&
        assertTrue(body.toLowerCase.contains("swagger"))
    },
  )
}
