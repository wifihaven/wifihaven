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
 * content, so YAML is the source of truth and edits made directly in the DB are overwritten on
 * the next API restart. Operators tune which blocklists are *enabled* per profile, not which
 * hosts a bundled blocklist contains.
 */
final case class BundledBlocklist(
    id: BlocklistId,
    name: String,
    description: String,
    source: String,
    hosts: List[Hostname],
)

object BundledBlocklists {

  private val ResourcePrefix         = "/blocklists"
  val DefaultManifestResource: String = s"$ResourcePrefix/_index.yml"

  /** Load and parse all bundled blocklists listed in the manifest. Fails fast on any malformed file. */
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
      hosts <- Option(root.get("hosts")) match {
        case Some(xs: java.util.List[?]) =>
          val strs = xs.asScala.toList.map(_.toString)
          if strs.isEmpty then Left("hosts must contain at least one entry")
          else
            strs
              .foldLeft[Either[String, List[Hostname]]](Right(Nil)) { (acc, raw) =>
                acc.flatMap(prev =>
                  Hostname.parse(raw.trim).left.map(e => s"invalid host '$raw': $e").map(_ :: prev),
                )
              }
              .map(_.reverse.distinct)
        case _                           => Left("hosts must be a non-empty list of strings")
      }
      _     <- Either.cond(
        source.endsWith(s"/${id.value}.yml"),
        (),
        s"id '${id.value}' does not match file name $source",
      )
    } yield BundledBlocklist(id, name, desc, src, hosts)
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
   *   - REPLACE the rows in blocklist_domains for this category (clear + insertBatch);
   *   - upsert the `blocklists` metadata row with display name, description, source, and the
   *     current instant as `last_built_at`.
   *
   * Idempotent — running twice with the same YAML content produces the same rows; only
   * `last_built_at` advances.
   */
  def seed(repo: BlocklistRepo, lists: List[BundledBlocklist]): Task[Unit] =
    Clock.instant.flatMap(now => ZIO.foreachDiscard(lists)(b => seedOne(repo, b, now)))

  private def seedOne(repo: BlocklistRepo, b: BundledBlocklist, now: java.time.Instant): Task[Unit] =
    for {
      _ <- repo.clearCategory(b.id)
      _ <- repo.insertBatch(b.hosts.map(h => (h.value, b.id.value)))
      _ <- repo.upsertMeta(b.id, b.name, Some(b.description), bundled = true, Some(b.source), now)
    } yield ()

  /** #706: dev-only test categories, seeded on startup when WIFIHAVEN_SEED_TEST_BLOCKLISTS is set. */
  val devTestBlocklists: List[BundledBlocklist] = List(
    BundledBlocklist(
      BlocklistId.unsafe("test_ads"),
      "Test Ads",
      "Dev-only test category. Not shipped to prod.",
      "dev seed",
      List("adserver.example.com", "doubleclick.net", "googleadservices.com").map(Hostname.unsafe),
    ),
    BundledBlocklist(
      BlocklistId.unsafe("test_social"),
      "Test Social",
      "Dev-only test category. Not shipped to prod.",
      "dev seed",
      List("facebook.com", "instagram.com", "tiktok.com").map(Hostname.unsafe),
    ),
  )
}
