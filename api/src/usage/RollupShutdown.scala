package wifihaven.api.usage

import java.sql.SQLException

/**
 * #1247: shutdown-ordering helper shared by the rollup `runOnce` wrappers.
 *
 * On container teardown the scoped `HikariDataSource` finalizer closes the pool. The structural fix
 * (forking the rollup fibers into a scope that depends on the transactor layer, so they are
 * interrupted before the pool closes) handles the common case, but a tick already
 * mid-`getConnection` can still surface "HikariDataSource (HikariPool-N) has been closed" before
 * its interruption lands. That failure is a benign shutdown artifact, not a rollup failure:
 * recording it in `rollup_runs` would fire a false rollup-health alert (#1240, #1243) on every
 * deploy.
 *
 * We match *only* the pool-closed signal — a Hikari pool is never closed except on teardown — so a
 * genuine SQL error during a normal tick still logs ERROR and records an error run. Interruption
 * itself needs no handling here: it bypasses the `.either` in the wrappers, so an interrupted tick
 * never reaches the error branch at all.
 */
object RollupShutdown {

  /**
   * True if `t` (or any cause in its chain) is a SQLException reporting a closed datasource/pool.
   */
  def isPoolClosed(t: Throwable): Boolean = {
    @annotation.tailrec
    def loop(cause: Throwable, seen: List[Throwable]): Boolean =
      if (cause == null || seen.exists(_ eq cause)) false
      else {
        val msg = Option(cause.getMessage).getOrElse("")
        val hit = cause.isInstanceOf[SQLException] && msg.contains("has been closed")
        hit || loop(cause.getCause, cause :: seen)
      }
    loop(t, Nil)
  }
}
