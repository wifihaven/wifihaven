package wifihaven.api

import wifihaven.shared.types.*
import zio.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration as JDuration

/**
 * #958 (URL-sourced lists): fetches public blocklists over HTTPS, parses them per the declared
 * format, and returns a deduped list of apex hostnames.
 *
 * Network behavior:
 *   - 30-second connect timeout, 60-second read timeout (some upstream lists are several MB).
 *   - HTTP 2xx required; anything else fails with a typed error.
 *   - User-Agent identifies wifihaven so upstream operators can attribute traffic.
 *   - No retry logic — `BundledBlocklists.seed` keeps existing DB rows on failure, so a transient
 *     network glitch on boot just delays the next refresh until the admin or scheduler triggers it.
 *
 * Parsing leniency:
 *   - Lines starting with `#` are comments (whole-line and trailing).
 *   - Hosts-file lines accept `0.0.0.0 host` / `127.0.0.1 host` / bare `host`.
 *   - Hosts that fail `Hostname.parse` are dropped silently (upstream lists often include IPv6
 *     placeholders, `localhost`, malformed wildcards — none of these are useful at the apex level).
 *   - Output is deduped and sorted for stable seeding.
 */
trait BlocklistFetcher {
  def fetch(url: String, format: BlocklistFormat): Task[List[Hostname]]
}

object BlocklistFetcher {

  /** The User-Agent we send when fetching upstream lists. */
  val UserAgent: String = "wifihaven-api/1 (+https://wifihaven.net)"

  val live: ZLayer[Any, Nothing, BlocklistFetcher] =
    ZLayer.succeed(new Live)

  final class Live extends BlocklistFetcher {
    private val client = HttpClient
      .newBuilder()
      .connectTimeout(JDuration.ofSeconds(30))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build()

    override def fetch(url: String, format: BlocklistFormat): Task[List[Hostname]] =
      ZIO.attemptBlocking {
        val req = HttpRequest
          .newBuilder(URI.create(url))
          .header("User-Agent", UserAgent)
          .header("Accept", "text/plain, */*;q=0.5")
          .timeout(JDuration.ofSeconds(60))
          .GET()
          .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() / 100 != 2)
          throw new RuntimeException(s"HTTP ${resp.statusCode()} fetching $url")
        Parser.parse(resp.body(), format)
      }
  }

  /** Pure parsing — also re-used by unit tests so we don't need a live HTTP server. */
  object Parser {

    def parse(body: String, format: BlocklistFormat): List[Hostname] = format match {
      case BlocklistFormat.HostsFile  => parseHostsFile(body)
      case BlocklistFormat.DomainList => parseDomainList(body)
    }

    def parseHostsFile(body: String): List[Hostname] =
      body.linesIterator
        .flatMap(parseHostsLine)
        .toList
        .distinct
        .sorted

    private def parseHostsLine(raw: String): Option[Hostname] = {
      val noComment = raw.takeWhile(_ != '#').trim
      if noComment.isEmpty then None
      else {
        // Split on any whitespace; either `host` or `0.0.0.0 host` shape.
        val parts = noComment.split("\\s+").toList
        val candidate = parts match {
          case Nil          => None
          case one :: Nil   => Some(one)
          case _ :: rest    => rest.headOption
        }
        candidate.flatMap(c => Hostname.parse(c).toOption)
      }
    }

    def parseDomainList(body: String): List[Hostname] =
      body.linesIterator
        .flatMap { raw =>
          val noComment = raw.takeWhile(_ != '#').trim
          if noComment.isEmpty then None
          else Hostname.parse(noComment).toOption
        }
        .toList
        .distinct
        .sorted
  }
}
