package wifihaven.api

import wifihaven.api.db.BlocklistRepo
import wifihaven.shared.types.*
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import zio.*

import java.io.InputStream
import scala.jdk.CollectionConverters.*

/**
 * #958: API-shipped blocklists (ads, social-media, gambling, adult).
 *
 * Blocklists ship as `api/resources/blocklists/<id>.yml` with an `_index.yml` manifest. At API
 * startup the seeder REPLACES each bundled list's `blocklist_domains` rows with the YAML's `hosts:`
 * field and upserts a corresponding `blocklists` metadata row (display name, description, source,
 * last_built_at). Operator-curated categories (those not in the manifest) are untouched.
 *
 * Contrast with AppTemplates, where operator host edits win — a bundled blocklist is API-managed
 * content, so YAML is the source of truth and edits made directly in the DB are overwritten on the
 * next API restart. Operators tune which blocklists are *enabled* per profile, not which hosts a
 * bundled blocklist contains.
 */
/** Tagged union of how a bundled list sources its hosts. */
sealed trait BundledBlocklistContent
object BundledBlocklistContent {

  /** Hand-curated host list baked into the YAML. */
  final case class Inline(hosts: List[Hostname]) extends BundledBlocklistContent

  /** Fetched at startup from a public upstream list. Cached in-memory after first fetch. */
  final case class Remote(url: String, format: BlocklistFormat) extends BundledBlocklistContent
}

/** Parser format selector for upstream files. */
enum BlocklistFormat {

  /**
   * Etc-hosts format: `0.0.0.0 example.com` / `127.0.0.1 example.com` / `# comment`. Bare hostnames
   * on a line are also accepted. Comments after `#` are stripped. Empty lines ignored. The classic
   * StevenBlack/hosts and pi-hole-style aggregations live here.
   */
  case HostsFile

  /**
   * One apex hostname per line; `#` comments; empty lines ignored. OISD's "domainswild" file etc.
   */
  case DomainList
}

final case class BundledBlocklist(
    id: BlocklistId,
    name: String,
    description: String,
    source: String,
    content: BundledBlocklistContent,
)

object BundledBlocklists {

  private val ResourcePrefix          = "/blocklists"
  val DefaultManifestResource: String = s"$ResourcePrefix/_index.yml"

  /**
   * Load and parse all bundled blocklists listed in the manifest. Fails fast on any malformed file.
   */
  def loadAll(manifestResource: String = DefaultManifestResource): Task[List[BundledBlocklist]] =
    for {
      ids <- readManifest(manifestResource)
      _   <- ZIO
        .fail(new RuntimeException(s"duplicate id(s) in manifest $manifestResource"))
        .when(ids.distinct.size != ids.size)
      out <- ZIO.foreach(ids)(loadOne)
      _   <- ZIO
        .fail(
          new RuntimeException(
            s"duplicate blocklist ids after parse: ${out.map(_.id.value).mkString(",")}",
          ),
        )
        .when(out.map(_.id).distinct.size != out.size)
    } yield out

  private def readManifest(resource: String): Task[List[String]] =
    withResource(resource) { in =>
      val root = parseYaml(in, resource)
      root.get("ids") match {
        case xs: java.util.List[?] => xs.asScala.toList.map(_.toString)
        case other                 =>
          throw new RuntimeException(s"$resource: expected 'ids: [...]' list, got $other")
      }
    }

  private def loadOne(id: String): Task[BundledBlocklist] = {
    val resource = s"$ResourcePrefix/$id.yml"
    withResource(resource) { in =>
      val root = parseYaml(in, resource)
      parseBlocklist(root, resource).fold(
        e => throw new RuntimeException(s"$resource: $e"),
        identity,
      )
    }
  }

  private[api] def parseBlocklist(
      root: java.util.Map[String, AnyRef],
      source: String,
  ): Either[String, BundledBlocklist] = {
    def req[A](key: String)(f: AnyRef => Either[String, A]): Either[String, A] =
      Option(root.get(key)).toRight(s"missing required field '$key'").flatMap(f)

    def reqStr(key: String): Either[String, String] = req(key) {
      case s: String if s.trim.nonEmpty => Right(s.trim)
      case _                            => Left(s"$key must be a non-empty string")
    }

    for {
      idRaw <- reqStr("id")
      id    <- BlocklistId.parse(idRaw)
      name  <- reqStr("name")
      desc  <- reqStr("description")
      src   <- reqStr("source")
      hasHosts = root.containsKey("hosts")
      hasUrl   = root.containsKey("url")
      _       <- Either.cond(
        hasHosts ^ hasUrl,
        (),
        "exactly one of 'hosts' or 'url' must be set",
      )
      content <-
        if (hasHosts) {
          Option(root.get("hosts")) match {
            case Some(xs: java.util.List[?]) =>
              val strs = xs.asScala.toList.map(_.toString)
              if strs.isEmpty then Left("hosts must contain at least one entry")
              else
                strs
                  .foldLeft[Either[String, List[Hostname]]](Right(Nil)) { (acc, raw) =>
                    acc.flatMap(prev =>
                      Hostname
                        .parse(raw.trim)
                        .left
                        .map(e => s"invalid host '$raw': $e")
                        .map(_ :: prev),
                    )
                  }
                  .map(hs => BundledBlocklistContent.Inline(hs.reverse.distinct))
            case _                           => Left("hosts must be a non-empty list of strings")
          }
        } else {
          for {
            url <- Option(root.get("url")) match {
              case Some(s: String) if s.trim.nonEmpty => Right(s.trim)
              case _                                  => Left("url must be a non-empty string")
            }
            _   <- Either.cond(
              url.startsWith("https://") || url.startsWith("http://"),
              (),
              s"url must start with http(s)://: $url",
            )
            fmt <- Option(root.get("format")) match {
              case None            => Right(BlocklistFormat.HostsFile)
              case Some(s: String) =>
                s.trim.toLowerCase match {
                  case "hosts-file" | "hosts"               => Right(BlocklistFormat.HostsFile)
                  case "domain-list" | "domains" | "domain" => Right(BlocklistFormat.DomainList)
                  case other => Left(s"unknown format '$other' (expected hosts-file|domain-list)")
                }
              case Some(other)     => Left(s"format must be a string if present, got $other")
            }
          } yield BundledBlocklistContent.Remote(url, fmt)
        }
      _       <- Either.cond(
        source.endsWith(s"/${id.value}.yml"),
        (),
        s"id '${id.value}' does not match file name $source",
      )
    } yield BundledBlocklist(id, name, desc, src, content)
  }

  private def withResource[A](resource: String)(f: InputStream => A): Task[A] =
    ZIO.attemptBlocking {
      val in = getClass.getResourceAsStream(resource)
      if in == null then throw new RuntimeException(s"resource not found on classpath: $resource")
      try f(in)
      finally in.close()
    }

  private def parseYaml(in: InputStream, source: String): java.util.Map[String, AnyRef] = {
    val opts = new LoaderOptions()
    opts.setAllowDuplicateKeys(false)
    val yaml = new Yaml(new SafeConstructor(opts))
    yaml.load[AnyRef](in) match {
      case m: java.util.Map[?, ?] =>
        m.asInstanceOf[java.util.Map[String, AnyRef]]
      case other                  =>
        throw new RuntimeException(s"$source: expected a YAML mapping, got $other")
    }
  }

  /**
   * Seed all bundled blocklists. For each:
   *   - resolve hosts: inline lists read directly from YAML; remote lists are fetched via
   *     `BlocklistFetcher`, parsed by the declared format, and cached in-memory (the cache enables
   *     future re-seed without re-fetching from upstream);
   *   - REPLACE the rows in blocklist_domains for this category (clear + insertBatch);
   *   - upsert the `blocklists` metadata row with display name, description, source, and the
   *     current instant as `last_built_at`.
   *
   * **Failure mode for remote lists**: if the upstream fetch fails (network down, 5xx, parse
   * error), log a warning and leave the existing `blocklist_domains` rows for that id alone. This
   * means a startup with no network preserves whatever was last seeded — a fresh enrollment with no
   * network will end up with empty remote lists, which is the right answer (an empty blocklist
   * blocks nothing; better than a stale or partial one).
   *
   * Idempotent — running twice with the same YAML content produces the same rows; only
   * `last_built_at` advances.
   */
  def seed(
      repo: BlocklistRepo,
      cache: BlocklistCache,
      fetcher: BlocklistFetcher,
      lists: List[BundledBlocklist],
  ): Task[Unit] =
    Clock.instant.flatMap(now =>
      ZIO.foreachDiscard(lists)(b => seedOne(repo, cache, fetcher, b, now)),
    )

  private def seedOne(
      repo: BlocklistRepo,
      cache: BlocklistCache,
      fetcher: BlocklistFetcher,
      b: BundledBlocklist,
      now: java.time.Instant,
  ): Task[Unit] =
    resolveHosts(cache, fetcher, b).flatMap {
      case None        => ZIO.unit // remote fetch failed; leave existing rows untouched
      case Some(hosts) =>
        for {
          _ <- repo.clearCategory(b.id)
          _ <- repo.insertBatch(hosts.map(h => (h.value, b.id.value)))
          _ <- repo.upsertMeta(
            b.id,
            b.name,
            Some(b.description),
            bundled = true,
            Some(b.source),
            now,
          )
        } yield ()
    }

  /** Resolve a bundled list's hosts. None means "skip this seed cycle, keep existing DB rows." */
  private def resolveHosts(
      cache: BlocklistCache,
      fetcher: BlocklistFetcher,
      b: BundledBlocklist,
  ): Task[Option[List[Hostname]]] = b.content match {
    case BundledBlocklistContent.Inline(hosts)    =>
      ZIO.succeed(Some(hosts))
    case BundledBlocklistContent.Remote(url, fmt) =>
      fetcher
        .fetch(url, fmt)
        .tap(hosts =>
          Clock.instant.flatMap(now =>
            cache.put(b.id, BlocklistCache.Entry(hosts, fetchedAt = now, source = url)),
          ),
        )
        .map(Some(_))
        .catchAll(e =>
          ZIO
            .logWarning(
              s"failed to fetch bundled blocklist '${b.id.value}' from $url: ${e.getMessage}; keeping existing DB rows",
            )
            .as(None),
        )
  }

  /** Refresh a single bundled list on demand (admin endpoint). Returns Some(count) on success. */
  def refresh(
      repo: BlocklistRepo,
      cache: BlocklistCache,
      fetcher: BlocklistFetcher,
      b: BundledBlocklist,
  ): Task[Option[Int]] =
    Clock.instant.flatMap { now =>
      resolveHosts(cache, fetcher, b).flatMap {
        case None        => ZIO.none
        case Some(hosts) =>
          for {
            _ <- repo.clearCategory(b.id)
            _ <- repo.insertBatch(hosts.map(h => (h.value, b.id.value)))
            _ <- repo.upsertMeta(
              b.id,
              b.name,
              Some(b.description),
              bundled = true,
              Some(b.source),
              now,
            )
          } yield Some(hosts.size)
      }
    }

  /**
   * #706: dev-only test categories, seeded on startup when WIFIHAVEN_SEED_TEST_BLOCKLISTS is set.
   */
  val devTestBlocklists: List[BundledBlocklist] = List(
    BundledBlocklist(
      BlocklistId.unsafe("test_ads"),
      "Test Ads",
      "Dev-only test category. Not shipped to prod.",
      "dev seed",
      BundledBlocklistContent.Inline(
        List("adserver.example.com", "doubleclick.net", "googleadservices.com").map(Hostname.unsafe),
      ),
    ),
    BundledBlocklist(
      BlocklistId.unsafe("test_social"),
      "Test Social",
      "Dev-only test category. Not shipped to prod.",
      "dev seed",
      BundledBlocklistContent.Inline(
        List("facebook.com", "instagram.com", "tiktok.com").map(Hostname.unsafe),
      ),
    ),
  )
}
