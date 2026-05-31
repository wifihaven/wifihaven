package wifihaven.api.feature

import wifihaven.api.Readiness
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ReadinessGateSpec extends ZIOSpecDefault {

  // A trivial downstream route that records whether it actually ran, so we can
  // assert the gate short-circuits *before* the handler touches anything.
  private def probeRoutes(ran: Ref[Boolean]): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "anything" ->
        handler { (_: Request) => ran.set(true).as(Response.json("""{"ok":true}""")) },
    )

  def spec = suite("Readiness gate")(
    test("returns 503 starting and never invokes the handler while not ready") {
      // #1248: the port binds before migrations finish, so a real request can
      // arrive while readiness is still false. The gate must reject it with 503
      // rather than let it hit an unmigrated DB.
      for {
        ran <- Ref.make(false)
        gated = Readiness.gate(probeRoutes(ran), ZIO.succeed(false))
        resp   <- gated(Request.get("/api/anything")).merge
        body   <- resp.body.asString
        json   <- ZIO.fromEither(body.fromJson[Json])
        didRun <- ran.get
      } yield assertTrue(resp.status == Status.ServiceUnavailable) &&
        assertTrue(
          json.asObject.flatMap(_.get("status")).flatMap(_.asString).contains("starting"),
        ) &&
        assertTrue(!didRun)
    },
    test("passes the request through once ready") {
      for {
        ran <- Ref.make(false)
        gated = Readiness.gate(probeRoutes(ran), ZIO.succeed(true))
        resp   <- gated(Request.get("/api/anything")).merge
        body   <- resp.body.asString
        didRun <- ran.get
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(body.contains("\"ok\":true")) &&
        assertTrue(didRun)
    },
    test("re-reads readiness on every request (false then true)") {
      for {
        readyRef <- Ref.make(false)
        ran      <- Ref.make(false)
        gated = Readiness.gate(probeRoutes(ran), readyRef.get)
        before <- gated(Request.get("/api/anything")).merge
        _      <- readyRef.set(true)
        after  <- gated(Request.get("/api/anything")).merge
      } yield assertTrue(before.status == Status.ServiceUnavailable) &&
        assertTrue(after.status == Status.Ok)
    },
  )
}
