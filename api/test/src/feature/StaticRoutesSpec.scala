package familydns.api.feature

import familydns.api.routes.StaticRoutes
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
