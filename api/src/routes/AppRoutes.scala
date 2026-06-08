package wifihaven.api.routes

import wifihaven.api.AppTemplate
import wifihaven.api.AppTemplates
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import zio.*
import zio.http.*
import zio.json.*

/**
 * #1570: handlers fail with a typed [[ApiError]] mapped centrally by
 * [[ErrorMapper.errorToResponse]]; the [[wifihaven.api.ErrorBoundary]] logs (4xx WARN / 5xx ERROR)
 * + meters each error. Every case reproduces the EXACT status + body the hand-rolled code produced
 * — the structured `slug_taken` (409), `seed_failed` (500), `not_template_derived` (400), and
 * `unknown_template` (404) JSON bodies are preserved verbatim via [[ApiError.Wrapped]] (the SPA
 * parses them), DB failures stay 503 via [[ApiError.Db]]. Auth/profile-access helpers still return
 * `Response` and are bridged via [[ApiError.Wrapped]].
 */
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
      templates: Map[AppTemplateId, AppTemplate] = Map.empty,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "apps"                                                ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            _        <- requireAuth(req, auth).mapError(ApiError.Wrapped(_))
            apps     <- appRepo.listAll.mapError(ApiError.Db(_))
            detailed <- ZIO
              .foreach(apps)(detail(appRepo, _))
              .mapError(ApiError.Db(_))
          } yield Response.json(detailed.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.GET / "api" / "apps" / long("id")                                   ->
        handler { (id: Long, req: Request) =>
          val aid                                  = AppId(id)
          val handle: ZIO[Any, ApiError, Response] = for {
            _ <- requireAuth(req, auth).mapError(ApiError.Wrapped(_))
            a <- appRepo
              .findById(aid)
              .mapError(ApiError.Db(_))
              .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("App not found")))
            d <- detail(appRepo, a).mapError(ApiError.Db(_))
          } yield Response.json(d.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.POST / "api" / "apps"                                               ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            _    <- requireAdmin(req, auth).mapError(ApiError.Wrapped(_))
            body <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            cr   <- ZIO
              .fromEither(body.fromJson[CreateAppRequest])
              .mapError(ApiError.DecodeFailure(_))
            name = cr.name.trim
            _        <- ZIO.fail(ApiError.BadRequest("name is required")).when(name.isEmpty)
            slug     <- ZIO
              .fromEither(cr.slug match {
                case Some(s) => validateSlug(s.trim)
                case None    => Right(slugify(name))
              })
              .mapError(ApiError.BadRequest(_))
            hosts    <- ZIO
              .fromEither(parseHosts(cr.hosts))
              .mapError(ApiError.BadRequest(_))
            existing <- appRepo
              .findBySlug(slug)
              .mapError(ApiError.Db(_))
            _        <- ZIO
              .fail(
                ApiError.Wrapped(
                  Response
                    .json(s"""{"error":"slug_taken","slug":"$slug"}""")
                    .status(Status.Conflict),
                ),
              )
              .when(existing.isDefined)
            id       <- appRepo
              .create(name, slug, cr.templateId, cr.icon, cr.iconType.getOrElse(IconType.Emoji))
              .mapError(ApiError.Db(_))
            _        <- appRepo
              .setHosts(id, hosts)
              .mapError(ApiError.Db(_))
              .when(hosts.nonEmpty)
            a        <- appRepo
              .findById(id)
              .mapError(ApiError.Db(_))
              .flatMap(ZIO.fromOption(_).orElseFail(ApiError.Internal("App vanished")))
            d        <- detail(appRepo, a).mapError(ApiError.Db(_))
          } yield Response.json(d.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.PUT / "api" / "apps" / long("id")                                   ->
        handler { (id: Long, req: Request) =>
          val aid                                  = AppId(id)
          val handle: ZIO[Any, ApiError, Response] = for {
            _    <- requireAdmin(req, auth).mapError(ApiError.Wrapped(_))
            body <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            ur   <- ZIO
              .fromEither(body.fromJson[UpdateAppRequest])
              .mapError(ApiError.DecodeFailure(_))
            name = ur.name.trim
            _ <- ZIO.fail(ApiError.BadRequest("name is required")).when(name.isEmpty)
            a <- appRepo
              .findById(aid)
              .mapError(ApiError.Db(_))
              .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("App not found")))
            _ <- appRepo
              .update(
                a.copy(
                  name = name,
                  icon = ur.icon,
                  iconType = ur.iconType.getOrElse(a.iconType),
                  templateId = ur.templateId,
                ),
              )
              .mapError(ApiError.Db(_))
          } yield Response.ok
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.DELETE / "api" / "apps" / long("id")                                ->
        handler { (id: Long, req: Request) =>
          val handle: ZIO[Any, ApiError, Response] =
            requireAdmin(req, auth).mapError(ApiError.Wrapped(_)) *>
              appRepo.delete(AppId(id)).mapError(ApiError.Db(_)) *>
              ZIO.succeed(Response.ok)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.PUT / "api" / "apps" / long("id") / "hosts"                         ->
        handler { (id: Long, req: Request) =>
          val aid                                  = AppId(id)
          val handle: ZIO[Any, ApiError, Response] = for {
            _     <- requireAdmin(req, auth).mapError(ApiError.Wrapped(_))
            body  <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            sr    <- ZIO
              .fromEither(body.fromJson[SetAppHostsRequest])
              .mapError(ApiError.DecodeFailure(_))
            hosts <- ZIO
              .fromEither(parseHosts(sr.hosts))
              .mapError(ApiError.BadRequest(_))
            _     <- appRepo
              .findById(aid)
              .mapError(ApiError.Db(_))
              .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("App not found")))
            _     <- appRepo.setHosts(aid, hosts).mapError(ApiError.Db(_))
          } yield Response.ok
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.PUT / "api" / "apps" / long("id") / "policy" / long("profileId")    ->
        handler { (id: Long, profileIdRaw: Long, req: Request) =>
          val aid                                  = AppId(id)
          val pid                                  = ProfileId(profileIdRaw)
          val handle: ZIO[Any, ApiError, Response] = for {
            claims   <- requireWriter(req, auth).mapError(ApiError.Wrapped(_))
            _        <- requireProfileAccess(claims, pid, userProfileRepo)
              .mapError(ApiError.Wrapped(_))
            body     <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            ar       <- ZIO
              .fromEither(body.fromJson[UpsertAppAssignmentRequest])
              .mapError(ApiError.DecodeFailure(_))
            _        <- ZIO
              .fromEither(validateAssignment(ar.mode, ar.dailyMinutes))
              .mapError(ApiError.BadRequest(_))
            _        <- appRepo
              .findById(aid)
              .mapError(ApiError.Db(_))
              .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("App not found")))
            _        <- profileRepo
              .findById(pid)
              .mapError(ApiError.Db(_))
              .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("Profile not found")))
            assignId <- appRepo
              .upsertAssignment(
                aid,
                pid,
                ar.mode,
                ar.dailyMinutes,
                ar.exemptFromDaily.getOrElse(true),
              )
              .mapError(ApiError.Db(_))
            // #1379: replace this assignment's per-app schedule rules with the
            // requested set (additive field; existing clients send `Nil`).
            _        <- appRepo
              .setScheduleRules(assignId, ar.scheduleRules.map(r => (r.scheduleId, r.mode)))
              .mapError(ApiError.Db(_))
          } yield Response.ok
          handle.mapError(ErrorMapper.errorToResponse)
        },
      // #1024: admin-triggered re-run of the startup app-template seeder. Same idempotent
      // semantics as the boot pass (operator host edits preserved) — exposed as a route so the
      // operator can backfill without a redeploy when prod is missing the starter set.
      Method.POST / "api" / "apps" / "seed-from-templates"                       ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            _       <- requireAdmin(req, auth).mapError(ApiError.Wrapped(_))
            summary <- AppTemplates
              .seed(appRepo, templates.values.toList)
              .mapError(e =>
                ApiError.Wrapped(
                  Response
                    .json(s"""{"error":"seed_failed","message":${e.getMessage.toJson}}""")
                    .status(Status.InternalServerError),
                ),
              )
          } yield Response.json(summary.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.POST / "api" / "apps" / long("id") / "reset-to-template"            ->
        handler { (id: Long, req: Request) =>
          val aid                                  = AppId(id)
          val handle: ZIO[Any, ApiError, Response] = for {
            _    <- requireAdmin(req, auth).mapError(ApiError.Wrapped(_))
            app  <- appRepo
              .findById(aid)
              .mapError(ApiError.Db(_))
              .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("App not found")))
            tid  <- ZIO
              .fromOption(app.templateId)
              .orElseFail(
                ApiError.Wrapped(
                  Response
                    .json("""{"error":"not_template_derived"}""")
                    .status(Status.BadRequest),
                ),
              )
            tmpl <- ZIO
              .fromOption(templates.get(tid))
              .orElseFail(
                ApiError.Wrapped(
                  Response
                    .json(s"""{"error":"unknown_template","templateId":"${tid.value}"}""")
                    .status(Status.NotFound),
                ),
              )
            _    <- appRepo.setHosts(aid, tmpl.hosts).mapError(ApiError.Db(_))
            d    <- detail(appRepo, app).mapError(ApiError.Db(_))
          } yield Response.json(d.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      // #999: field-scoped partial update. Body is a subset of the App read
      // shape — `name`, `icon` (set/null-to-clear), `iconType`, `templateId`
      // (set/null-to-clear), `hosts` (replace, matches today's PUT /hosts).
      // `slug` is immutable post-create and not patchable.
      Method.PATCH / "api" / "apps" / long("id")                                 ->
        handler { (id: Long, req: Request) =>
          val aid                                  = AppId(id)
          val handle: ZIO[Any, ApiError, Response] = for {
            _         <- requireAdmin(req, auth).mapError(ApiError.Wrapped(_))
            a         <- appRepo
              .findById(aid)
              .mapError(ApiError.Db(_))
              .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("App not found")))
            body      <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            obj       <- ZIO.fromEither(FieldPatch.parseObj(body)).mapError(ApiError.BadRequest(_))
            namePatch <- ZIO
              .fromEither(FieldPatch.from[String](obj, "name"))
              .mapError(ApiError.BadRequest(_))
            iconPatch <- ZIO
              .fromEither(FieldPatch.from[String](obj, "icon"))
              .mapError(ApiError.BadRequest(_))
            iconTypePatch   <- ZIO
              .fromEither(FieldPatch.from[IconType](obj, "iconType"))
              .mapError(ApiError.BadRequest(_))
            templateIdPatch <- ZIO
              .fromEither(FieldPatch.from[AppTemplateId](obj, "templateId"))
              .mapError(ApiError.BadRequest(_))
            hostsPatch      <- ZIO
              .fromEither(FieldPatch.from[List[String]](obj, "hosts"))
              .mapError(ApiError.BadRequest(_))
            newName = namePatch.applyTo(a.name).trim
            _             <- namePatch match {
              case FieldPatch.Cleared => ZIO.fail(ApiError.BadRequest("name cannot be cleared"))
              case FieldPatch.Set(_) if newName.isEmpty =>
                ZIO.fail(ApiError.BadRequest("name is required"))
              case _                                    => ZIO.unit
            }
            _             <- iconTypePatch match {
              case FieldPatch.Cleared =>
                ZIO.fail(ApiError.BadRequest("iconType cannot be cleared"))
              case _                  => ZIO.unit
            }
            hostsResolved <- hostsPatch match {
              case FieldPatch.Cleared  =>
                ZIO.fail(ApiError.BadRequest("hosts cannot be cleared (send [] to remove all)"))
              case FieldPatch.Set(raw) =>
                ZIO.fromEither(parseHosts(raw)).mapError(ApiError.BadRequest(_)).map(Some(_))
              case FieldPatch.Absent   => ZIO.succeed(None)
            }
            updated = a.copy(
              name = newName,
              icon = iconPatch.applyToNullable(a.icon),
              iconType = iconTypePatch.applyTo(a.iconType),
              templateId = templateIdPatch.applyToNullable(a.templateId),
            )
            _             <- appRepo.update(updated).mapError(ApiError.Db(_))
            _             <- hostsResolved match {
              case Some(hs) => appRepo.setHosts(aid, hs).mapError(ApiError.Db(_))
              case None     => ZIO.unit
            }
          } yield Response.ok
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.DELETE / "api" / "apps" / long("id") / "policy" / long("profileId") ->
        handler { (id: Long, profileIdRaw: Long, req: Request) =>
          val aid                                  = AppId(id)
          val pid                                  = ProfileId(profileIdRaw)
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireWriter(req, auth).mapError(ApiError.Wrapped(_))
            _      <- requireProfileAccess(claims, pid, userProfileRepo)
              .mapError(ApiError.Wrapped(_))
            _      <- appRepo
              .deleteAssignment(aid, pid)
              .mapError(ApiError.Db(_))
          } yield Response.ok
          handle.mapError(ErrorMapper.errorToResponse)
        },
    )
}
