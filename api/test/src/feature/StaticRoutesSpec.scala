package wifihaven.api.feature

import wifihaven.api.routes.{HealthRoutes, StaticRoutes}
import zio.*
import zio.http.*
import zio.test.*

object StaticRoutesSpec extends ZIOSpecDefault {

  private def withTempDir[A](f: java.io.File => Task[A]): Task[A] =
    ZIO.acquireReleaseWith(
      ZIO.attempt {
        val d = java.nio.file.Files.createTempDirectory("static-routes-spec").toFile
        d
      },
    ) { dir =>
      ZIO.attempt {
        def del(f: java.io.File): Unit = {
          if f.isDirectory then f.listFiles.foreach(del)
          val _ = f.delete()
        }
        del(dir)
      }.orDie
    }(f)

  private def write(dir: java.io.File, name: String, content: String): Task[Unit] =
    ZIO.attempt {
      val f = java.io.File(dir, name)
      java.nio.file.Files.writeString(f.toPath, content)
      ()
    }

  private def get(routes: Routes[Any, Response], path: String): UIO[Response] =
    routes(Request.get(path)).merge

  def spec = suite("StaticRoutes")(
    test("serves index.html for /") {
      withTempDir { dir =>
        for {
          _ <- write(dir, "index.html", "<html>hi</html>")
          rs = StaticRoutes.routes(dir.getAbsolutePath)
          resp <- get(rs, "/")
          body <- resp.body.asString
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(body == "<html>hi</html>")
      }
    },
    test("serves an existing asset") {
      withTempDir { dir =>
        for {
          _ <- write(dir, "app.js", "console.log(1)")
          rs = StaticRoutes.routes(dir.getAbsolutePath)
          resp <- get(rs, "/app.js")
          body <- resp.body.asString
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(body == "console.log(1)")
      }
    },
    test("falls back to index.html for unknown non-API path (SPA)") {
      withTempDir { dir =>
        for {
          _ <- write(dir, "index.html", "<html>spa</html>")
          rs = StaticRoutes.routes(dir.getAbsolutePath)
          resp <- get(rs, "/devices/some-deep-route")
          body <- resp.body.asString
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(body == "<html>spa</html>")
      }
    },
    test("returns 404 for unknown /api/ path") {
      withTempDir { dir =>
        for {
          _ <- write(dir, "index.html", "<html>spa</html>")
          rs = StaticRoutes.routes(dir.getAbsolutePath)
          resp <- get(rs, "/api/unknown")
        } yield assertTrue(resp.status == Status.NotFound)
      }
    },
    test("refuses to serve SPA for unparseable request URI (zio-http URL.empty fallback)") {
      withTempDir { dir =>
        for {
          _ <- write(dir, "index.html", "<html>spa</html>")
          rs = StaticRoutes.routes(dir.getAbsolutePath)
          // zio-http substitutes URL.empty when netty can't parse the URI —
          // e.g. when the agent issued GET /api/router/policy?since="..." with
          // literal unencoded quotes (see issue #214).
          resp <- rs(Request.get(URL.empty)).merge
          ct = resp.header(Header.ContentType).map(_.renderedValue).getOrElse("")
        } yield assertTrue(resp.status.code >= 400 && resp.status.code < 500) &&
          assertTrue(!ct.startsWith("text/html"))
      }
    },
    test("returns 404 (not SPA) for /api/router/* even with malformed query string") {
      withTempDir { dir =>
        for {
          _ <- write(dir, "index.html", "<html>spa</html>")
          rs = StaticRoutes.routes(dir.getAbsolutePath)
          // mimic the agent bug: literal unencoded quotes in the query string
          resp <- get(rs, "/api/router/policy?since=%22bogus%22")
          ct = resp.header(Header.ContentType).map(_.renderedValue).getOrElse("")
        } yield assertTrue(resp.status.code >= 400 && resp.status.code < 500) &&
          assertTrue(!ct.startsWith("text/html"))
      }
    },
    // #614: the env-gated SPA disable for Render API deployments. Main.scala
    // composes either `StaticRoutes.routes(...)` or `Routes.empty` based on
    // `cfg.http.serveSpa`. These tests simulate both modes alongside the
    // /api/health route to verify the acceptance criteria.
    test("serveSpa=false: GET / returns 404, /api/health still 200") {
      withTempDir { dir =>
        for {
          _ <- write(dir, "index.html", "<html>spa</html>")
          serveSpa = false
          rs       = HealthRoutes.routes(ZIO.unit) ++
            (if (serveSpa) StaticRoutes.routes(dir.getAbsolutePath) else Routes.empty)
          root   <- get(rs, "/")
          health <- get(rs, "/api/health")
        } yield assertTrue(root.status == Status.NotFound) &&
          assertTrue(health.status == Status.Ok)
      }
    },
    test("serveSpa=true: GET / returns SPA HTML, /api/health still 200") {
      withTempDir { dir =>
        for {
          _ <- write(dir, "index.html", "<html>spa</html>")
          serveSpa = true
          rs       = HealthRoutes.routes(ZIO.unit) ++
            (if (serveSpa) StaticRoutes.routes(dir.getAbsolutePath) else Routes.empty)
          root   <- get(rs, "/")
          body   <- root.body.asString
          health <- get(rs, "/api/health")
        } yield assertTrue(root.status == Status.Ok) &&
          assertTrue(body == "<html>spa</html>") &&
          assertTrue(health.status == Status.Ok)
      }
    },
    test("rejects path traversal attempts") {
      withTempDir { dir =>
        for {
          _ <- write(dir, "index.html", "<html>spa</html>")
          rs = StaticRoutes.routes(dir.getAbsolutePath)
          resp <- get(rs, "/../../etc/passwd")
          body <- resp.body.asString
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(body == "<html>spa</html>")
      }
    },
  )
}
