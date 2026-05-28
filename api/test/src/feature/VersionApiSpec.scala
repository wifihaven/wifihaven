package wifihaven.api.feature

import wifihaven.api.BuildInfo
import wifihaven.api.routes.VersionRoutes
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object VersionApiSpec extends ZIOSpecDefault {

  private def get(routes: Routes[Any, Response]): UIO[Response] =
    routes(Request.get("/api/version")).merge

  private def field(json: Json, key: String): Option[String] =
    json.asObject.flatMap(_.get(key)).flatMap(_.asString)

  def spec = suite("Version API")(
    test("GET /api/version returns the injected sha") {
      val routes = VersionRoutes.routes(BuildInfo("abc1234"))
      for {
        resp <- get(routes)
        body <- resp.body.asString
        json <- ZIO.fromEither(body.fromJson[Json])
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(field(json, "sha").contains("abc1234"))
    },
    test("BuildInfo.fromEnv falls back to unknown when the env var is unset or blank") {
      assertTrue(BuildInfo.resolve(None, BuildInfo.UnknownSha) == "unknown") &&
      assertTrue(BuildInfo.resolve(Some(""), BuildInfo.UnknownSha) == "unknown") &&
      assertTrue(BuildInfo.resolve(Some("   "), BuildInfo.UnknownSha) == "unknown") &&
      assertTrue(BuildInfo.resolve(Some(" abc1234 "), BuildInfo.UnknownSha) == "abc1234")
    },
  )
}
