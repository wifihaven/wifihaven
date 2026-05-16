package wifihaven.api.feature

import wifihaven.api.routes.HealthRoutes
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object HealthApiSpec extends ZIOSpecDefault {

  private def get(routes: Routes[Any, Response]): UIO[Response] =
    routes(Request.get("/api/health")).merge

  def spec = suite("Health API")(
    test("GET /api/health returns 200 with status=ok,db=ok when DB check succeeds") {
      val routes = HealthRoutes.routes(ZIO.unit)
      for {
        resp <- get(routes)
        body <- resp.body.asString
        json <- ZIO.fromEither(body.fromJson[Json])
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(json.asObject.flatMap(_.get("status")).flatMap(_.asString).contains("ok")) &&
        assertTrue(json.asObject.flatMap(_.get("db")).flatMap(_.asString).contains("ok"))
    },
    test("GET /api/health returns 503 with status=error when DB check fails") {
      val routes = HealthRoutes.routes(
        ZIO.fail(new java.sql.SQLException("connection refused")),
      )
      for {
        resp <- get(routes)
        body <- resp.body.asString
        json <- ZIO.fromEither(body.fromJson[Json])
      } yield assertTrue(resp.status == Status.ServiceUnavailable) &&
        assertTrue(json.asObject.flatMap(_.get("status")).flatMap(_.asString).contains("error")) &&
        assertTrue(
          json.asObject.flatMap(_.get("db")).flatMap(_.asString).contains("SQLException"),
        )
    },
    test("error response does not leak SQL details") {
      val secret = "ERROR: relation \"secret_table\" does not exist at line 42"
      val routes = HealthRoutes.routes(ZIO.fail(new java.sql.SQLException(secret)))
      for {
        resp <- get(routes)
        body <- resp.body.asString
      } yield assertTrue(resp.status == Status.ServiceUnavailable) &&
        assertTrue(!body.contains("secret_table")) &&
        assertTrue(!body.contains("line 42"))
    },
  )
}
