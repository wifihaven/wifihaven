package wifihaven.api.routes

import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import zio.*
import zio.http.*
import zio.json.*

object AppRoutes {

  // Strip a leading "*." and parse as apex Hostname.
  private def parseHostInput(raw: String): Either[String, Hostname] = {
    val s        = raw.trim
    val stripped = if s.startsWith("*.") then s.drop(2) else s
    Hostname.parse(stripped)
  }

  private def parseHosts(raw: List[String]): Either[String, List[Hostname]] =
    raw
      .foldLeft[Either[String, List[Hostname]]](Right(Nil)) { (acc, s) =>
        acc.flatMap(prev => parseHostInput(s).map(h => h :: prev))
      }
      .map(_.reverse.distinct)

  private val SlugPattern = "^[a-z0-9][a-z0-9-]{0,63}$".r

  private def slugify(name: String): String = {
    val lower     = name.toLowerCase
    val replaced  = lower.map(c => if c.isLetterOrDigit then c else '-')
    val collapsed = replaced.foldLeft("") { (acc, c) =>
      if c == '-' && acc.endsWith("-") then acc else acc + c
    }
    val trimmed   = collapsed.stripPrefix("-").stripSuffix("-")
    if trimmed.isEmpty then "app" else trimmed.take(64)
  }

  private def validateSlug(s: String): Either[String, String] =
    if SlugPattern.matches(s) then Right(s)
    else Left(s"invalid slug: '$s' (must match ^[a-z0-9][a-z0-9-]{0,63}$$)")

  private def validateAssignment(
      mode: AppMode,
      dailyMinutes: Option[Int],
  ): Either[String, Unit] = mode match {
    case AppMode.TimeLimited =>
      dailyMinutes match {
        case Some(m) if m > 0 => Right(())
        case Some(_)          => Left("dailyMinutes must be > 0")
        case None             => Left("time_limited mode requires dailyMinutes")
      }
    case _                   =>
      dailyMinutes match {
        case Some(m) if m > 0 => Right(())
        case Some(_)          => Left("dailyMinutes must be > 0")
        case None             => Right(())
      }
  }

  private def detail(appRepo: AppRepo, a: App): Task[AppDetail] =
    for {
      hosts <- appRepo.getHosts(a.id)
      asgn  <- appRepo.listAssignmentsForApp(a.id)
    } yield AppDetail(a, hosts, asgn)

  def routes(
      auth: AuthService,
      appRepo: AppRepo,
      profileRepo: ProfileRepo,
      userProfileRepo: UserProfileRepo,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "apps"                                                ->
        handler { (req: Request) =>
          for {
            _        <- requireAuth(req, auth)
            apps     <- appRepo.listAll.mapError(ErrorMapper.dbErrorToResponse)
            detailed <- ZIO
              .foreach(apps)(detail(appRepo, _))
              .mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.json(detailed.toJson)
        },
      Method.GET / "api" / "apps" / long("id")                                   ->
        handler { (id: Long, req: Request) =>
          val aid = AppId(id)
          for {
            _ <- requireAuth(req, auth)
            a <- appRepo
              .findById(aid)
              .mapError(ErrorMapper.dbErrorToResponse)
              .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("App not found")))
            d <- detail(appRepo, a).mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.json(d.toJson)
        },
      Method.POST / "api" / "apps"                                               ->
        handler { (req: Request) =>
          for {
            _    <- requireAdmin(req, auth)
            body <- req.body.asString.orElseFail(Response.badRequest(""))
            cr   <- ZIO
              .fromEither(body.fromJson[CreateAppRequest])
              .mapError(e => Response.badRequest(e))
            name = cr.name.trim
            _        <- ZIO.fail(Response.badRequest("name is required")).when(name.isEmpty)
            slug     <- ZIO
              .fromEither(cr.slug match {
                case Some(s) => validateSlug(s.trim)
                case None    => Right(slugify(name))
              })
              .mapError(e => Response.badRequest(e))
            hosts    <- ZIO
              .fromEither(parseHosts(cr.hosts))
              .mapError(e => Response.badRequest(e))
            existing <- appRepo
              .findBySlug(slug)
              .mapError(ErrorMapper.dbErrorToResponse)
            _        <- ZIO
              .fail(
                Response
                  .json(s"""{"error":"slug_taken","slug":"$slug"}""")
                  .status(Status.Conflict),
              )
              .when(existing.isDefined)
            id       <- appRepo
              .create(name, slug, cr.templateId, cr.icon)
              .mapError(ErrorMapper.dbErrorToResponse)
            _        <- appRepo
              .setHosts(id, hosts)
              .mapError(ErrorMapper.dbErrorToResponse)
              .when(hosts.nonEmpty)
            a        <- appRepo
              .findById(id)
              .mapError(ErrorMapper.dbErrorToResponse)
              .flatMap(ZIO.fromOption(_).orElseFail(Response.internalServerError("App vanished")))
            d        <- detail(appRepo, a).mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.json(d.toJson)
        },
      Method.PUT / "api" / "apps" / long("id")                                   ->
        handler { (id: Long, req: Request) =>
          val aid = AppId(id)
          for {
            _    <- requireAdmin(req, auth)
            body <- req.body.asString.orElseFail(Response.badRequest(""))
            ur   <- ZIO
              .fromEither(body.fromJson[UpdateAppRequest])
              .mapError(e => Response.badRequest(e))
            name = ur.name.trim
            _ <- ZIO.fail(Response.badRequest("name is required")).when(name.isEmpty)
            a <- appRepo
              .findById(aid)
              .mapError(ErrorMapper.dbErrorToResponse)
              .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("App not found")))
            _ <- appRepo
              .update(a.copy(name = name, icon = ur.icon, templateId = ur.templateId))
              .mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.ok
        },
      Method.DELETE / "api" / "apps" / long("id")                                ->
        handler { (id: Long, req: Request) =>
          requireAdmin(req, auth) *>
            appRepo.delete(AppId(id)).mapError(ErrorMapper.dbErrorToResponse) *>
            ZIO.succeed(Response.ok)
        },
      Method.PUT / "api" / "apps" / long("id") / "hosts"                         ->
        handler { (id: Long, req: Request) =>
          val aid = AppId(id)
          for {
            _     <- requireAdmin(req, auth)
            body  <- req.body.asString.orElseFail(Response.badRequest(""))
            sr    <- ZIO
              .fromEither(body.fromJson[SetAppHostsRequest])
              .mapError(e => Response.badRequest(e))
            hosts <- ZIO
              .fromEither(parseHosts(sr.hosts))
              .mapError(e => Response.badRequest(e))
            _     <- appRepo
              .findById(aid)
              .mapError(ErrorMapper.dbErrorToResponse)
              .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("App not found")))
            _     <- appRepo.setHosts(aid, hosts).mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.ok
        },
      Method.PUT / "api" / "apps" / long("id") / "policy" / long("profileId")    ->
        handler { (id: Long, profileIdRaw: Long, req: Request) =>
          val aid = AppId(id)
          val pid = ProfileId(profileIdRaw)
          for {
            claims <- requireWriter(req, auth)
            _      <- requireProfileAccess(claims, pid, userProfileRepo)
            body   <- req.body.asString.orElseFail(Response.badRequest(""))
            ar     <- ZIO
              .fromEither(body.fromJson[UpsertAppAssignmentRequest])
              .mapError(e => Response.badRequest(e))
            _      <- ZIO
              .fromEither(validateAssignment(ar.mode, ar.dailyMinutes))
              .mapError(e => Response.badRequest(e))
            _      <- appRepo
              .findById(aid)
              .mapError(ErrorMapper.dbErrorToResponse)
              .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("App not found")))
            _      <- profileRepo
              .findById(pid)
              .mapError(ErrorMapper.dbErrorToResponse)
              .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("Profile not found")))
            _      <- appRepo
              .upsertAssignment(
                aid,
                pid,
                ar.mode,
                ar.dailyMinutes,
                ar.exemptFromDaily.getOrElse(true),
              )
              .mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.ok
        },
      Method.DELETE / "api" / "apps" / long("id") / "policy" / long("profileId") ->
        handler { (id: Long, profileIdRaw: Long, req: Request) =>
          val aid = AppId(id)
          val pid = ProfileId(profileIdRaw)
          for {
            claims <- requireWriter(req, auth)
            _      <- requireProfileAccess(claims, pid, userProfileRepo)
            _      <- appRepo
              .deleteAssignment(aid, pid)
              .mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.ok
        },
    )
}
