package wifihaven.api.feature

import wifihaven.api.routes.VersionRoutes
import wifihaven.shared.WireSchema
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object VersionApiSpec extends ZIOSpecDefault {

  private def get: UIO[Response] =
    VersionRoutes.routes(Request.get("/api/version")).merge

  def spec = suite("Version API")(
    test("GET /api/version returns 200 with apiBuild and wireSchema") {
      for {
        resp <- get
        body <- resp.body.asString
        json <- ZIO.fromEither(body.fromJson[Json])
        obj   = json.asObject
        build = obj.flatMap(_.get("apiBuild")).flatMap(_.asString)
        wire  = obj.flatMap(_.get("wireSchema")).flatMap(_.asNumber).map(_.value.intValue)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(build.exists(_.nonEmpty)) &&
        assertTrue(wire.contains(WireSchema.Current)) &&
        assertTrue(WireSchema.Current >= 1)
    },
  )
}
