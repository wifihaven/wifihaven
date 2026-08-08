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
 *
 * #1570: handlers fail with a typed [[ApiError]] mapped centrally by
 * [[ErrorMapper.errorToResponse]]; the [[wifihaven.api.ErrorBoundary]] logs (4xx WARN / 5xx ERROR)
 * + meters each error. Every case reproduces the EXACT status + body the hand-rolled code produced
 * before (the `name_taken` 409 JSON is preserved verbatim via [[ApiError.Wrapped]]), so the SPA
 * sees identical responses.
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

  // 409 + {"error":"name_taken","name":...} — preserved verbatim from the hand-rolled handler
  // (the SPA distinguishes it from a generic 400). Wrapped so the boundary maps/logs/meters it
  // without re-deriving the body.
  private def nameTaken(name: String): ApiError =
    ApiError.Wrapped(
      Response
        .json(s"""{"error":"name_taken","name":${name.toJson}}""")
        .status(Status.Conflict),
    )

  def routes(
      auth: AuthService,
      scheduleRepo: NamedScheduleRepo,
      // #1849: editing/deleting a named schedule changes the per-MAC `blocked` flag for every
      // profile that references it (PolicyService folds the windows in at snapshot time), so drop
      // the computed-snapshot cache on a schedule mutation. Defaulted to a no-op for test call sites;
      // production passes `policy.invalidate`. Create alone is NOT invalidated: a brand-new schedule
      // is referenced by no profile, so it is invisible to the snapshot until a profile attaches it
      // via PUT /api/profiles/{id}/schedules — which invalidates there.
      invalidateSnapshot: HouseholdId => UIO[Unit] = _ => ZIO.unit,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "schedules"                 ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            // #2126: scope to the caller's household — V72 added `named_schedules.household_id`, so
            // the list returns only this tenant's schedules (an unattached one stays visible to its
            // own household; another household's never leaks). Design §2 gap 4.
            claims <- requireAuth(req, auth)
            list   <- scheduleRepo.listAllForHousehold(claims.hh).mapError(ApiError.Db(_))
          } yield Response.json(list.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.GET / "api" / "schedules" / long("id")    ->
        handler { (id: Long, req: Request) =>
          val sid                                  = NamedScheduleId(id)
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireAuth(req, auth)
            // #2126: reject a cross-household id with 404 before reading the row (existence never
            // leaks across the tenant boundary).
            _      <- requireScheduleInHousehold(claims, sid, scheduleRepo)
            s      <- scheduleRepo
              .findById(sid)
              .mapError(ApiError.Db(_))
              .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("Schedule not found")))
          } yield Response.json(s.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.POST / "api" / "schedules"                ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireWriter(req, auth)
            body   <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            cr     <- ZIO
              .fromEither(body.fromJson[CreateNamedScheduleRequest])
              .mapError(ApiError.DecodeFailure(_))
            name = cr.name.trim
            _       <- ZIO.fail(ApiError.BadRequest("name is required")).when(name.isEmpty)
            windows <- ZIO.fromEither(validateWindows(cr.windows)).mapError(ApiError.BadRequest(_))
            // #2572: the name-taken probe is scoped to the caller's household — another
            // household's "Bedtime" must neither block this create/rename nor be observable.
            taken   <- scheduleRepo.findByName(name, claims.hh).mapError(ApiError.Db(_))
            _       <- ZIO.fail(nameTaken(name)).when(taken.isDefined)
            // #2126: stamp the creating user's household (from their JWT) so a freshly-created,
            // still-unattached schedule is scoped to its author's tenant — and thus visible only to
            // them via `listAllForHousehold` (#2130 stamp-on-create precedent).
            id      <- scheduleRepo
              .create(name, cr.description.map(_.trim).filter(_.nonEmpty), windows, claims.hh)
              .mapError(ApiError.Db(_))
            s       <- scheduleRepo
              .findById(id)
              .mapError(ApiError.Db(_))
              .flatMap(
                ZIO.fromOption(_).orElseFail(ApiError.Internal("Schedule vanished")),
              )
            _       <- AppMetrics.scheduleMutation("create")
          } yield Response.json(s.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      // PATCH carries the full schedule shape (name/description/windows) and replaces — symmetric
      // with the GET read shape, so the SPA autosaves the whole edit form (#423 / autosave default).
      Method.PATCH / "api" / "schedules" / long("id")  ->
        handler { (id: Long, req: Request) =>
          val sid                                  = NamedScheduleId(id)
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireWriter(req, auth)
            // #2126: reject a cross-household target with 404 before any read/write.
            _      <- requireScheduleInHousehold(claims, sid, scheduleRepo)
            body   <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            ur     <- ZIO
              .fromEither(body.fromJson[UpdateNamedScheduleRequest])
              .mapError(ApiError.DecodeFailure(_))
            name = ur.name.trim
            _       <- ZIO.fail(ApiError.BadRequest("name is required")).when(name.isEmpty)
            windows <- ZIO.fromEither(validateWindows(ur.windows)).mapError(ApiError.BadRequest(_))
            _       <- scheduleRepo
              .findById(sid)
              .mapError(ApiError.Db(_))
              .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("Schedule not found")))
            // #2572: the name-taken probe is scoped to the caller's household — another
            // household's "Bedtime" must neither block this create/rename nor be observable.
            taken   <- scheduleRepo.findByName(name, claims.hh).mapError(ApiError.Db(_))
            _       <- ZIO.fail(nameTaken(name)).when(taken.exists(_.id != sid))
            _       <- scheduleRepo
              .update(sid, name, ur.description.map(_.trim).filter(_.nonEmpty), windows)
              .mapError(ApiError.Db(_))
            s       <- scheduleRepo
              .findById(sid)
              .mapError(ApiError.Db(_))
              .flatMap(
                ZIO.fromOption(_).orElseFail(ApiError.Internal("Schedule vanished")),
              )
            _       <- AppMetrics.scheduleMutation("update")
            _       <- invalidateSnapshot(claims.hh)
          } yield Response.json(s.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.DELETE / "api" / "schedules" / long("id") ->
        handler { (id: Long, req: Request) =>
          val sid                                  = NamedScheduleId(id)
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireWriter(req, auth)
            // #2126: reject a cross-household delete with 404 (never mutate another tenant's row).
            _      <- requireScheduleInHousehold(claims, sid, scheduleRepo)
            _      <- scheduleRepo.delete(sid).mapError(ApiError.Db(_))
            _      <- invalidateSnapshot(claims.hh)
            _      <- AppMetrics.scheduleMutation("delete")
          } yield Response.ok
          handle.mapError(ErrorMapper.errorToResponse)
        },
    )
}
