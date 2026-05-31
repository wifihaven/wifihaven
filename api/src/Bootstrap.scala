package wifihaven.api

import zio.*

/**
 * Decouples the HTTP port-bind from the DB warm-up chain so that a slow Postgres at startup does
 * not cause Render's port-scan to time out (#1197).
 *
 * `warmup` runs in a forked daemon: migrations, seeds, and background-job kick-off. It is allowed
 * to fail without taking the server down — the next request to a DB-touching route surfaces the
 * underlying error in the normal way, and operator-side restart/observability picks it up.
 *
 * `serve` runs on the main fiber so the process exits naturally if `Server.serve` returns.
 */
object Bootstrap {
  def boot[R](
      warmup: ZIO[R, Throwable, Unit],
      serve: ZIO[R, Throwable, Unit],
  ): ZIO[R, Throwable, Unit] =
    for {
      _ <- warmup
        .catchAllCause(c => ZIO.logErrorCause("startup warm-up failed; server stays up", c))
        .forkDaemon
      _ <- serve
    } yield ()
}
