package familydns.api.routes

import zio.*
import zio.http.*
import zio.http.codec.PathCodec.trailing

object StaticRoutes {
  def routes(staticDir: String): Routes[Any, Response] = {
    val base  = java.io.File(staticDir).getCanonicalFile
    val index = java.io.File(base, "index.html")

    def resolve(rel: String): Option[java.io.File] =
      if rel.isEmpty then None
      else {
        val candidate = java.io.File(base, rel).getCanonicalFile
        if candidate.getPath == base.getPath || candidate.getPath.startsWith(
            base.getPath + java.io.File.separator,
          )
        then if candidate.isFile then Some(candidate) else None
        else None
      }

    def mimeFor(name: String): MediaType = {
      val ext = name.lastIndexOf('.') match {
        case -1 => ""
        case i  => name.substring(i + 1).toLowerCase
      }
      MediaType.forFileExtension(ext).getOrElse(MediaType.application.`octet-stream`)
    }

    def serve(file: java.io.File): ZIO[Any, Nothing, Response] =
      Body
        .fromFile(file)
        .map(b =>
          Response(
            status = Status.Ok,
            headers = Headers(Header.ContentType(mimeFor(file.getName))),
            body = b,
          ),
        )

    Routes(
      Method.GET / trailing -> handler { (path: Path, _: Request) =>
        val rel = path.encode.stripPrefix("/")
        if rel.startsWith("api/") then ZIO.succeed(Response.notFound)
        else
          resolve(rel) match {
            case Some(f) => serve(f)
            case None    =>
              if index.isFile then serve(index)
              else ZIO.succeed(Response.notFound)
          }
      },
    )
  }
}
