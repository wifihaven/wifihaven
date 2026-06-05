package wifihaven.api.routes

import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.metrics.AppMetrics
import wifihaven.shared.*
import wifihaven.shared.types.*
import zio.*
import zio.http.*
import zio.json.*

/**
 * #1069: CRUD over household-scoped reusable named schedules (`/api/schedules`). A `NamedSchedule`
 * is the time-window primitive that profiles (and, later, per-app rules #1378 / blocklists #1067)
 * reference by id. Pure HTTP/validation glue over [[NamedScheduleRepo]] — PolicyService folds the
 * active windows into the per-MAC BlockRules at snapshot time; the router never sees schedules.
 */
object ScheduleRoutes {

  private val ValidDays = Set("mon", "tue", "wed", "thu", "fri", "sat", "sun")

  // Normalize day tokens to the lowercase 3-letter form `scheduleActiveAt` matches on, and reject
  // unknown tokens or empty day sets so a window can never be silently never-active.
  private def validateWindows(ws: List[ScheduleWindow]): Either[String, List[ScheduleWindow]] =
    ws.foldLeft[Either[String, List[ScheduleWindow]]](Right(Nil)) { (acc, w) =>
      acc.flatMap { prev =>
        val days = w.days.map(_.trim.toLowerCase).distinct
        if days.isEmpty then Left("each window needs at least one day")
        else if !days.forall(ValidDays.contains) then
          Left(
            s"invalid day token(s) in [${w.days.mkString(",")}]; expected mon,tue,wed,thu,fri,sat,sun",
          )
        else Right(w.copy(days = days) :: prev)
      }
    }.map(_.reverse)

  def routes(
      auth: AuthService,
      scheduleRepo: NamedScheduleRepo,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "schedules"                 ->
        handler { (req: Request) =>
          for {
            _    <- requireAuth(req, auth)
            list <- scheduleRepo.listAll.mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.json(list.toJson)
        },
      Method.GET / "api" / "schedules" / long("id")    ->
        handler { (id: Long, req: Request) =>
          for {
            _ <- requireAuth(req, auth)
            s <- scheduleRepo
              .findById(NamedScheduleId(id))
              .mapError(ErrorMapper.dbErrorToResponse)
              .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("Schedule not found")))
          } yield Response.json(s.toJson)
        },
      Method.POST / "api" / "schedules"                ->
        handler { (req: Request) =>
          for {
            _    <- requireAdmin(req, auth)
            body <- req.body.asString.orElseFail(Response.badRequest(""))
            cr   <- ZIO
              .fromEither(body.fromJson[CreateNamedScheduleRequest])
              .mapError(Response.badRequest(_))
            name = cr.name.trim
            _       <- ZIO.fail(Response.badRequest("name is required")).when(name.isEmpty)
            windows <- ZIO.fromEither(validateWindows(cr.windows)).mapError(Response.badRequest(_))
            taken   <- scheduleRepo.findByName(name).mapError(ErrorMapper.dbErrorToResponse)
            _       <- ZIO
              .fail(
                Response
                  .json(s"""{"error":"name_taken","name":${name.toJson}}""")
                  .status(Status.Conflict),
              )
              .when(taken.isDefined)
            id      <- scheduleRepo
              .create(name, cr.description.map(_.trim).filter(_.nonEmpty), windows)
              .mapError(ErrorMapper.dbErrorToResponse)
            s       <- scheduleRepo
              .findById(id)
              .mapError(ErrorMapper.dbErrorToResponse)
              .flatMap(
                ZIO.fromOption(_).orElseFail(Response.internalServerError("Schedule vanished")),
              )
            _       <- AppMetrics.scheduleMutation("create")
          } yield Response.json(s.toJson)
        },
      // PATCH carries the full schedule shape (name/description/windows) and replaces — symmetric
      // with the GET read shape, so the SPA autosaves the whole edit form (#423 / autosave default).
      Method.PATCH / "api" / "schedules" / long("id")  ->
        handler { (id: Long, req: Request) =>
          val sid = NamedScheduleId(id)
          for {
            _    <- requireAdmin(req, auth)
            body <- req.body.asString.orElseFail(Response.badRequest(""))
            ur   <- ZIO
              .fromEither(body.fromJson[UpdateNamedScheduleRequest])
              .mapError(Response.badRequest(_))
            name = ur.name.trim
            _       <- ZIO.fail(Response.badRequest("name is required")).when(name.isEmpty)
            windows <- ZIO.fromEither(validateWindows(ur.windows)).mapError(Response.badRequest(_))
            _       <- scheduleRepo
              .findById(sid)
              .mapError(ErrorMapper.dbErrorToResponse)
              .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("Schedule not found")))
            taken   <- scheduleRepo.findByName(name).mapError(ErrorMapper.dbErrorToResponse)
            _       <- ZIO
              .fail(
                Response
                  .json(s"""{"error":"name_taken","name":${name.toJson}}""")
                  .status(Status.Conflict),
              )
              .when(taken.exists(_.id != sid))
            _       <- scheduleRepo
              .update(sid, name, ur.description.map(_.trim).filter(_.nonEmpty), windows)
              .mapError(ErrorMapper.dbErrorToResponse)
            s       <- scheduleRepo
              .findById(sid)
              .mapError(ErrorMapper.dbErrorToResponse)
              .flatMap(
                ZIO.fromOption(_).orElseFail(Response.internalServerError("Schedule vanished")),
              )
            _       <- AppMetrics.scheduleMutation("update")
          } yield Response.json(s.toJson)
        },
      Method.DELETE / "api" / "schedules" / long("id") ->
        handler { (id: Long, req: Request) =>
          requireAdmin(req, auth) *>
            scheduleRepo
              .delete(NamedScheduleId(id))
              .mapError(ErrorMapper.dbErrorToResponse) *>
            AppMetrics.scheduleMutation("delete").as(Response.ok)
        },
    )
}
