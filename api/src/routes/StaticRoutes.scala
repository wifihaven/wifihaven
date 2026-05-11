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
      Method.GET / trailing -> handler { (_: Path, req: Request) =>
        // Use req.url.path rather than the trailing-captured path: zio-http
        // substitutes URL.empty (encode == "") when netty can't parse the
        // request URI — e.g. unencoded `"` in a query string (issue #214).
        // The real "/" gives encode == "/", so this distinguishes them.
        val encoded = req.url.path.encode
        if encoded.isEmpty then ZIO.succeed(Response.badRequest("malformed request URI"))
        else {
          val rel = encoded.stripPrefix("/")
          if rel.startsWith("api/") then
            ZIO.succeed(Response.notFound(s"no such API route: ${req.method} /$rel"))
          else
            resolve(rel) match {
              case Some(f) => serve(f)
              case None    =>
                if index.isFile then serve(index)
                else ZIO.succeed(Response.notFound)
            }
        }
      },
    )
  }
}
