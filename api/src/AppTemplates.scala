package wifihaven.api

import wifihaven.api.db.AppRepo
import wifihaven.shared.IconType
import wifihaven.shared.types.*
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import zio.*

import java.io.InputStream
import scala.jdk.CollectionConverters.*

/**
 * #768: Starter library of common apps as YAML templates.
 *
 * Templates ship as `api/resources/app_templates/(slug).yml`. An `_index.yml` manifest lists every
 * template slug; the loader reads each `<slug>.yml` resource by name (classpath enumeration is
 * awkward inside a fat jar, so we use the manifest instead). At startup the seeder finds-or-creates
 * one `apps` row per template; operator host edits are preserved.
 */
final case class AppTemplate(
    slug: AppTemplateId,
    name: String,
    icon: Option[String],
    iconType: IconType,
    hosts: List[Hostname],
)

object AppTemplates {

  /** Classpath path prefix used both for the manifest and for individual templates. */
  private val ResourcePrefix = "/app_templates"

  /** Default location of the manifest. Overridable for tests. */
  val DefaultManifestResource: String = s"$ResourcePrefix/_index.yml"

  /** Load and parse all templates listed in the manifest. Fails fast on any malformed file. */
  def loadAll(manifestResource: String = DefaultManifestResource): Task[List[AppTemplate]] =
    for {
      slugs     <- readManifest(manifestResource)
      _         <- ZIO
        .fail(new RuntimeException(s"duplicate slug(s) in manifest $manifestResource"))
        .when(slugs.distinct.size != slugs.size)
      templates <- ZIO.foreach(slugs)(loadOne)
      _         <- ZIO
        .fail(
          new RuntimeException(
            s"duplicate template slugs after parse: ${templates.map(_.slug.value).mkString(",")}",
          ),
        )
        .when(templates.map(_.slug).distinct.size != templates.size)
    } yield templates

  private def readManifest(resource: String): Task[List[String]] =
    withResource(resource) { in =>
      val root = parseYaml(in, resource)
      root.get("slugs") match {
        case xs: java.util.List[?] => xs.asScala.toList.map(_.toString)
        case other                 =>
          throw new RuntimeException(s"$resource: expected 'slugs: [...]' list, got $other")
      }
    }

  private def loadOne(slug: String): Task[AppTemplate] = {
    val resource = s"$ResourcePrefix/$slug.yml"
    withResource(resource) { in =>
      val root = parseYaml(in, resource)
      parseTemplate(root, resource).fold(
        e => throw new RuntimeException(s"$resource: $e"),
        identity,
      )
    }
  }

  private[api] def parseTemplate(
      root: java.util.Map[String, AnyRef],
      source: String,
  ): Either[String, AppTemplate] = {
    def req[A](key: String)(f: AnyRef => Either[String, A]): Either[String, A] =
      Option(root.get(key)).toRight(s"missing required field '$key'").flatMap(f)

    for {
      slugRaw  <- req("slug") {
        case s: String => Right(s)
        case other     => Left(s"slug must be a string, got $other")
      }
      slug     <- AppTemplateId.parse(slugRaw)
      name     <- req("name") {
        case s: String if s.trim.nonEmpty => Right(s.trim)
        case _                            => Left("name must be a non-empty string")
      }
      icon     <- Option(root.get("icon")) match {
        case None            => Right(None)
        case Some(s: String) => Right(Some(s).filter(_.trim.nonEmpty))
        case Some(other)     => Left(s"icon must be a string if present, got $other")
      }
      iconType <- Option(root.get("icon_type")) match {
        case None            => Right(IconType.Emoji)
        case Some(s: String) =>
          IconType.parse(s.trim).toRight(s"unknown icon_type '$s' (expected emoji|url|png_base64)")
        case Some(other)     => Left(s"icon_type must be a string if present, got $other")
      }
      hosts    <- Option(root.get("hosts")) match {
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
      _        <- Either.cond(
        source.endsWith(s"/${slug.value}.yml"),
        (), {
          s"slug '${slug.value}' does not match file name $source"
        },
      )
    } yield AppTemplate(slug, name, icon, iconType, hosts)
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
   * Seed all templates into `apps`. For each template:
   *   - if no row exists for its `template_id`, create one and populate the host list;
   *   - if a row exists, leave the host list alone (operator edits win — the SPA "reset to
   *     template" endpoint restores defaults on demand).
   *
   * Idempotent — running twice is a no-op.
   */
  def seed(repo: AppRepo, templates: List[AppTemplate]): Task[Unit] =
    ZIO.foreachDiscard(templates)(t => seedOne(repo, t))

  private def seedOne(repo: AppRepo, t: AppTemplate): Task[Unit] =
    repo.findByTemplateId(t.slug).flatMap {
      case Some(existing) =>
        // Operator edits win: if any hosts exist we don't touch them. If the row exists but the
        // host list is empty, treat that as "never populated" and seed it now.
        repo.getHosts(existing.id).flatMap { hosts =>
          if hosts.nonEmpty then ZIO.unit
          else repo.setHosts(existing.id, t.hosts)
        }
      case None           =>
        for {
          freeSlug <- findFreeSlug(repo, t.slug.value)
          id       <- repo.create(t.name, freeSlug, Some(t.slug), t.icon, t.iconType)
          _        <- repo.setHosts(id, t.hosts)
        } yield ()
    }

  /**
   * The seeded `apps.slug` defaults to the template id but must be globally unique. If an
   * operator-created app happens to occupy that slug, fall back to `<slug>-template`, then
   * `<slug>-template-N`. Rare in practice.
   */
  private def findFreeSlug(repo: AppRepo, base: String): Task[String] = {
    def tryName(candidate: String, attempt: Int): Task[String] =
      repo.findBySlug(candidate).flatMap {
        case None    => ZIO.succeed(candidate)
        case Some(_) =>
          val next = if attempt == 0 then s"$base-template" else s"$base-template-$attempt"
          tryName(next, attempt + 1)
      }
    tryName(base, 0)
  }
}
