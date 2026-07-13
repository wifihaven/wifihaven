package wifihaven.api.db

import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import org.postgresql.util.PSQLException
import zio.*
import zio.interop.catz.*

import java.time.Duration

/**
 * Raised when a bounded read trips its per-statement `statement_timeout` (#1099). Kept distinct
 * from a bare `PSQLException` so callers (and `ErrorMapper`) can recognise "this query was
 * deliberately cancelled for taking too long" rather than a generic DB failure.
 */
final class QueryTimeoutException(message: String) extends RuntimeException(message)

/**
 * Per-request statement timeout for the unbounded-scan read paths (#1099).
 *
 * The /profiles page once wedged the whole Render instance: a single per-profile usage query ran
 * 90s (partition pruning was defeated — see [[TrafficReportRepoLive.listPresenceRowsInWindow]]),
 * held its DB connection the entire time, and once the small pool saturated every other endpoint
 * 502'd. This bounds each such query so a pathological profile fails fast (typed
 * [[QueryTimeoutException]] → 503) instead of holding a connection open and taking the instance
 * down with it.
 *
 * The bound is generous: healthy partition-pruned queries return sub-second, so [[PresenceWindow]]
 * never trips for a well-behaved request.
 */
object QueryTimeout {

  val PresenceWindow: Duration = Duration.ofSeconds(8)

  /**
   * #2174: bound for the raw-tier pre-aggregated Traffic Usage read
   * ([[TrafficReportRepoLive.listRawAggregatedInRange]]). Wider than [[PresenceWindow]] because the
   * page's window cap is 31 days (`UsageTraffic.maxOnTheFlyDuration`) and a legitimate month-wide
   * 10m aggregation over prod volume hash-aggregates millions of rows; 30s matches #846's original
   * "wider windows risk >30s queries → reject with 503" line rather than wedging the pool (#1099).
   */
  val RawAggregateWindow: Duration = Duration.ofSeconds(30)

  /**
   * Run `c` inside a transaction with a `SET LOCAL statement_timeout`, so the bound auto-releases
   * when the tx commits/rolls back and never leaks onto a pooled connection. A Postgres cancel
   * (SQLSTATE 57014) is remapped to [[QueryTimeoutException]]; all other failures pass through
   * unchanged.
   */
  def bounded[A](xa: Transactor[Task], timeout: Duration)(c: ConnectionIO[A]): Task[A] = {
    val ms      = math.max(1L, timeout.toMillis)
    // statement_timeout cannot be a bind parameter; `ms` is a server-derived
    // Long, never user input, so const-interpolation is injection-safe.
    val wrapped = Fragment.const(s"SET LOCAL statement_timeout = $ms").update.run *> c
    wrapped.transact(xa).mapError {
      case e: PSQLException if e.getSQLState == "57014" =>
        new QueryTimeoutException(s"query exceeded ${ms}ms statement_timeout")
      case e                                            => e
    }
  }
}
