package wifihaven.api

import wifihaven.api.db.BlocklistRepo
import wifihaven.api.metrics.AppMetrics
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
      ZIO.foreachDiscard(lists)(b => applyResolved(repo, cache, fetcher, b, now).unit),
    )

  /**
   * The one ingest primitive. Both doors — the startup `seed` and the admin `refresh` endpoint —
   * resolve hosts and then land here, so neither can acquire a behaviour the other lacks.
   *
   * They used to carry the clear+insert+upsertMeta body twice, which is how #2809's review found
   * them already diverging: the failure-path sweep had been added to `seed` alone, leaving the
   * poison in place on exactly the door an admin reaches for when sign-in is broken.
   *
   * Returns the number of hosts the category now holds, or None when nothing was written.
   */
  private def applyResolved(
      repo: BlocklistRepo,
      cache: BlocklistCache,
      fetcher: BlocklistFetcher,
      b: BundledBlocklist,
      now: java.time.Instant,
  ): Task[Option[Int]] =
    resolveHosts(cache, fetcher, b).flatMap {
      // #2809: a failed fetch leaves the existing rows — but those rows may be the POISON this
      // filter exists to remove, seeded by an earlier build that had no filter. Stale CONTENT is
      // fine to keep (an empty blocklist blocks nothing, which beats a partial one); stale
      // shared-GFE rows are the bug itself, and waiting for upstream to answer before removing
      // them means Google sign-in stays broken on a network-partitioned install.
      case None        => purgeBannedRows(repo, b).as(None)
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

  /**
   * #2809: drop the shared-GFE hosts from a FETCHED list before it is seeded.
   *
   * Enforcement matches on DESTINATION IP. A fetched member that fronts on Google's shared GFE
   * anycast pool puts that pool's addresses into `bl_<id>`, which drops every other host GFE serves
   * from them — on prod that was `oauthaccountmanager.googleapis.com`, i.e. Google sign-in, taken
   * out by `firebaselogging.googleapis.com` and three siblings in StevenBlack/hosts. The same file
   * carries 170 members of the already-confirmed #2601/#2369 ban set, including the literal hosts
   * that broke Drive.
   *
   * INGEST rather than allow-carve: the host never enters the drop set at all. Adding it to
   * `InfraHosts.canonical` instead would put the shared pool into `@global_allow`, where it beats
   * every drop (#421) and silently defeats every Google host-block — the #2369 regression.
   *
   * Scoped to FETCHED content on purpose. Repo-authored catalogs are guarded at authoring time by
   * `BundledBlocklistsSpec` / `AppTemplatesSpec`, which fail the build; filtering them here too
   * would let an authoring mistake ship green.
   *
   * Applied at the one point both ingest doors pass through — startup `seed` and the admin
   * `refresh` endpoint — so a refresh cannot undo it.
   */
  private def exceptSharedGfe(
      id: BlocklistId,
      hosts: List[Hostname],
  ): UIO[List[Hostname]] = {
    val (excluded, kept) = hosts.partition(SharedGfeHosts.isBanned)
    for {
      // Always logged, including at zero (no-dark-by-default): an exception set that silently
      // matches nothing must not be indistinguishable from one that is not running.
      _ <- ZIO.logInfo(
        s"event=blocklist_ingest_exceptions blocklist_id=${id.value} " +
          s"fetched=${hosts.size} kept=${kept.size} excluded=${excluded.size}" +
          (if excluded.isEmpty then ""
           else s" sample=${excluded.take(5).map(_.value).mkString(",")}"),
      )
      // Two outcomes on one GAUGE so a zero `excluded` is readable: `kept` carrying a plausible
      // host count while `excluded` sits at zero means the filter ran and matched nothing, which
      // is a different state from the filter never running at all. A gauge rather than a counter
      // because this is a composition, not a rate — a second admin refresh must re-state the
      // same numbers, not double them.
      _ <- AppMetrics.recordBlocklistComposition(id, kept = kept.size, excluded = excluded.size)
    } yield kept
  }

  /**
   * #2809: remove already-seeded shared-GFE hosts from a list we could not re-fetch.
   *
   * The seed path normally repairs the table by rewriting it from a filtered fetch. When the fetch
   * fails we keep the existing rows (that is the right call for stale content — an empty blocklist
   * blocks nothing), but rows that are themselves the collateral hazard have to go regardless.
   * No-ops when the table is already clean, so a healthy install pays one read.
   */
  private def purgeBannedRows(repo: BlocklistRepo, b: BundledBlocklist): Task[Unit] = {
    val id = b.id
    repo.loadCategory(id).flatMap { existing =>
      val (banned, kept) = existing.toList.partition(SharedGfeHosts.isBanned)
      ZIO
        .when(banned.nonEmpty)(
          ZIO.logWarning(
            s"event=blocklist_ingest_purged blocklist_id=${id.value} " +
              s"purged=${banned.size} kept=${kept.size} " +
              s"sample=${banned.take(5).map(_.value).mkString(",")} " +
              "(upstream fetch failed; swept shared-GFE rows seeded before the exception set)",
          ) *>
            // A TARGETED delete, not clear+reinsert. This runs only when upstream is already
            // unreachable, so the DB rows are the only surviving copy of the list — a
            // clear-then-insert pair would open a window where the category is empty, and a
            // failure inside that window would lose the whole list rather than ~170 rows of it.
            repo.deleteHosts(id, banned).unit *>
            // Record the post-sweep composition, so the gauge keeps describing what the table
            // actually holds. `kept` here is "rows that survived", not "hosts ingested" — the
            // `event=blocklist_ingest_purged` warning above is what distinguishes the two.
            AppMetrics.recordBlocklistComposition(id, kept = kept.size, excluded = banned.size) *>
            Clock.instant.flatMap(now =>
              // #2809 review: the sweep changes the rows, so `last_built_at` has to move with
              // them — otherwise the SPA's host count and its "last built" stamp disagree about
              // when the category last changed. The other columns are restated from the YAML,
              // not defaulted: `upsertMeta` overwrites every column it is given, so passing
              // placeholder name/description/source here would clobber the real metadata.
              repo
                .upsertMeta(
                  id,
                  b.name,
                  Some(b.description),
                  bundled = true,
                  Some(b.source),
                  now,
                )
                .unit,
            ),
        )
        .unit
    }
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
            // The cache holds what upstream SENT, not what we seeded — it exists to avoid
            // re-fetching, and recording a filtered copy would make the exception set
            // irreversible without a network round trip.
            cache.put(b.id, BlocklistCache.Entry(hosts, fetchedAt = now, source = url)),
          ),
        )
        .flatMap(exceptSharedGfe(b.id, _))
        .map(Some(_))
        .catchAll(e =>
          ZIO
            .logWarning(
              s"failed to fetch bundled blocklist '${b.id.value}' from $url: ${e.getMessage}; keeping existing DB rows",
            )
            .as(None),
        )
  }

  /**
   * Refresh a single bundled list on demand (admin endpoint). Returns Some(count) on success.
   *
   * Shares `applyResolved` with the startup seed, so it gets the #2809 ingest exception AND the
   * failed-fetch sweep on the same terms. This is the door an admin reaches for when sign-in is
   * broken, so it is the one that must not silently skip the repair.
   */
  def refresh(
      repo: BlocklistRepo,
      cache: BlocklistCache,
      fetcher: BlocklistFetcher,
      b: BundledBlocklist,
  ): Task[Option[Int]] =
    Clock.instant.flatMap(applyResolved(repo, cache, fetcher, b, _))

  /**
   * #706: dev-only test categories, seeded on startup when WIFIHAVEN_SEED_TEST_BLOCKLISTS is set.
   */
  val devTestBlocklists: List[BundledBlocklist] = List(
    BundledBlocklist(
      BlocklistId.unsafe("test_ads"),
      "Test Ads",
      "Dev-only test category. Not shipped to prod.",
      "dev seed",
      // #2601: was doubleclick.net / googleadservices.com. Both front on Google's shared GFE
      // anycast pool, so seeding them here put a shared Google address into `bl_test_ads` and
      // dropped unrelated Google traffic on whichever dev router had the seed enabled — the same
      // collateral that broke Drive downloads on prod. Real ad apexes off the shared pool.
      BundledBlocklistContent.Inline(
        List("adserver.example.com", "adnxs.com", "criteo.com").map(Hostname.unsafe),
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
