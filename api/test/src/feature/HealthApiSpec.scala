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

  private def head(routes: Routes[Any, Response]): UIO[Response] =
    routes(Request.get("/api/health").copy(method = Method.HEAD)).merge

  private val ready    = ZIO.succeed(true)
  private val notReady = ZIO.succeed(false)

  def spec = suite("Health API")(
    test("GET /api/health returns 200 with status=ok,db=ok when ready and DB check succeeds") {
      val routes = HealthRoutes.routes(ready, ZIO.unit)
      for {
        resp <- get(routes)
        body <- resp.body.asString
        json <- ZIO.fromEither(body.fromJson[Json])
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(json.asObject.flatMap(_.get("status")).flatMap(_.asString).contains("ok")) &&
        assertTrue(json.asObject.flatMap(_.get("db")).flatMap(_.asString).contains("ok"))
    },
    test("GET /api/health returns 503 with status=starting while not ready (DB never touched)") {
      // #1248: until migrations + seeds finish, readiness is false. The health
      // probe must report not-ready even though the port is already bound, and
      // it must NOT run the DB check (the DB may be unmigrated/pegged).
      for {
        dbTouched <- Ref.make(false)
        checkDb = dbTouched.set(true)
        routes  = HealthRoutes.routes(notReady, checkDb)
        resp    <- get(routes)
        body    <- resp.body.asString
        json    <- ZIO.fromEither(body.fromJson[Json])
        touched <- dbTouched.get
      } yield assertTrue(resp.status == Status.ServiceUnavailable) &&
        assertTrue(
          json.asObject.flatMap(_.get("status")).flatMap(_.asString).contains("starting"),
        ) &&
        assertTrue(!touched)
    },
    test("GET /api/health returns 503 with status=error when ready but DB check fails") {
      val routes = HealthRoutes.routes(
        ready,
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
    test("HEAD /api/health returns 200 with empty body when ready and DB check succeeds") {
      val routes = HealthRoutes.routes(ready, ZIO.unit)
      for {
        resp <- head(routes)
        body <- resp.body.asString
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(body.isEmpty)
    },
    test("HEAD /api/health returns 503 with empty body when not ready") {
      val routes = HealthRoutes.routes(notReady, ZIO.unit)
      for {
        resp <- head(routes)
        body <- resp.body.asString
      } yield assertTrue(resp.status == Status.ServiceUnavailable) &&
        assertTrue(body.isEmpty)
    },
    test("HEAD /api/health returns 503 with empty body when ready but DB check fails") {
      val routes = HealthRoutes.routes(
        ready,
        ZIO.fail(new java.sql.SQLException("connection refused")),
      )
      for {
        resp <- head(routes)
        body <- resp.body.asString
      } yield assertTrue(resp.status == Status.ServiceUnavailable) &&
        assertTrue(body.isEmpty)
    },
    test("error response does not leak SQL details") {
      val secret = "ERROR: relation \"secret_table\" does not exist at line 42"
      val routes = HealthRoutes.routes(ready, ZIO.fail(new java.sql.SQLException(secret)))
      for {
        resp <- get(routes)
        body <- resp.body.asString
      } yield assertTrue(resp.status == Status.ServiceUnavailable) &&
        assertTrue(!body.contains("secret_table")) &&
        assertTrue(!body.contains("line 42"))
    },
  )
}
