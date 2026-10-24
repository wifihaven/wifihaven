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
 * #2934 (epic #2930, design docs/design/weekly-limits-and-app-groups.md §9): household app groups
 * (`/api/app-groups`) and each profile's attachment of one (`/api/profiles/{id}/app-groups`).
 *
 * A group is a name and a member list and nothing else (not a policy tier, #1452); the limits live
 * on the attachment. Writes are `requireWriter` (admin or adult, like every other parenting surface
 * — AGENTS.md / #2522; `child` is refused). Every id a caller names is proven to be theirs first
 * ([[requireAppGroupInHousehold]] / [[requireProfileAccess]]), and the V92 composite FKs make a
 * cross-household member or attachment unrepresentable underneath that.
 *
 * Every successful write drops the computed policy snapshot (`invalidateSnapshot`, production
 * passes `policy.invalidate`). Dropping the profile's settled usage rows (design §6.4) is the
 * repo's, in the write's own transaction.
 */
object AppGroupRoutes {

  private val MaxNameLength = 80

  private def jsonError(status: Status, error: String, fields: (String, String)*): ApiError = {
    val body = (("error" -> error) +: fields)
      .map((k, v) => s"${k.toJson}:${v.toJson}")
      .mkString("{", ",", "}")
    ApiError.Wrapped(Response.json(body).status(status))
  }

  private def toApiError(e: AppGroupError): ApiError = e match {
    case AppGroupError.GroupNotFound                    => ApiError.NotFound("Group not found")
    case AppGroupError.ProfileNotFound                  => ApiError.NotFound("Profile not found")
    case AppGroupError.NameTaken(n)                     =>
      jsonError(Status.Conflict, "name_taken", "name" -> n)
    case AppGroupError.AppInOtherGroup(a, appName, grp) =>
      jsonError(
        Status.Conflict,
        "app_in_other_group",
        "appId"   -> a.value.toString,
        "group"   -> grp,
        "message" -> s"$appName is already in the group \"$grp\". An app can be in only one group.",
      )
    case AppGroupError.UnknownApp(a)                    =>
      ApiError.BadRequest(s"Unknown app id ${a.value}")
  }

  private def validName(raw: String): IO[ApiError, String] = {
    val n = raw.trim
    if (n.isEmpty) ZIO.fail(ApiError.BadRequest("name is required"))
    else if (n.length > MaxNameLength)
      ZIO.fail(ApiError.BadRequest(s"name must be at most $MaxNameLength characters"))
    else ZIO.succeed(n)
  }

  private def validLimits(daily: Option[Int], weekly: Option[Int]): IO[ApiError, Unit] =
    if (daily.isEmpty && weekly.isEmpty)
      ZIO.fail(ApiError.BadRequest("an app group needs a dailyMinutes or weeklyMinutes limit"))
    else if (daily.exists(_ <= 0)) ZIO.fail(ApiError.BadRequest("dailyMinutes must be > 0"))
    else if (weekly.exists(_ <= 0)) ZIO.fail(ApiError.BadRequest("weeklyMinutes must be > 0"))
    else ZIO.unit

  // Meter one write: `ok` when it succeeds, `rejected` when it fails with a typed API error.
  private def metered[A](op: String)(eff: IO[ApiError, A]): IO[ApiError, A] =
    eff.tapBoth(
      _ => AppMetrics.appGroupMutation(op, "rejected"),
      _ => AppMetrics.appGroupMutation(op, "ok"),
    )

  def routes(
      auth: AuthService,
      groupRepo: AppGroupRepo,
      profileRepo: ProfileRepo,
      userProfileRepo: UserProfileRepo,
      // Production passes `policy.invalidate`; defaulted to a no-op for test call sites that do not
      // care about the snapshot.
      invalidateSnapshot: HouseholdId => UIO[Unit] = _ => ZIO.unit,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "app-groups"                                                ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireAuth(req, auth)
            list   <- groupRepo.listForHousehold(claims.hh).mapError(ApiError.Db(_))
          } yield Response.json(list.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.POST / "api" / "app-groups"                                               ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = metered("create") {
            for {
              claims  <- requireWriter(req, auth)
              body    <- req.body.asString.orElseFail(ApiError.BadRequest(""))
              cr      <- ZIO
                .fromEither(body.fromJson[CreateAppGroupRequest])
                .mapError(ApiError.DecodeFailure(_))
              name    <- validName(cr.name)
              created <- groupRepo
                .create(claims.hh, name, cr.memberAppIds)
                .mapError(ApiError.Db(_))
                .flatMap(ZIO.fromEither(_).mapError(toApiError))
              _       <- invalidateSnapshot(claims.hh)
            } yield Response.json(created.toJson)
          }
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.PATCH / "api" / "app-groups" / long("id")                                 ->
        handler { (id: Long, req: Request) =>
          val gid                                  = AppGroupId(id)
          val handle: ZIO[Any, ApiError, Response] = metered("update") {
            for {
              claims  <- requireWriter(req, auth)
              _       <- requireAppGroupInHousehold(claims, gid, groupRepo)
              body    <- req.body.asString.orElseFail(ApiError.BadRequest(""))
              ur      <- ZIO
                .fromEither(body.fromJson[UpdateAppGroupRequest])
                .mapError(ApiError.DecodeFailure(_))
              name    <- ZIO.foreach(ur.name)(validName)
              updated <- groupRepo
                .update(claims.hh, gid, name, ur.memberAppIds)
                .mapError(ApiError.Db(_))
                .flatMap(ZIO.fromEither(_).mapError(toApiError))
              _       <- invalidateSnapshot(claims.hh)
            } yield Response.json(updated.toJson)
          }
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.DELETE / "api" / "app-groups" / long("id")                                ->
        handler { (id: Long, req: Request) =>
          val gid                                  = AppGroupId(id)
          val handle: ZIO[Any, ApiError, Response] = metered("delete") {
            for {
              claims <- requireWriter(req, auth)
              _      <- requireAppGroupInHousehold(claims, gid, groupRepo)
              _      <- groupRepo
                .delete(claims.hh, gid)
                .mapError(ApiError.Db(_))
                .flatMap(ZIO.fromEither(_).mapError(toApiError))
              _      <- invalidateSnapshot(claims.hh)
            } yield Response.ok
          }
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.GET / "api" / "profiles" / long("id") / "app-groups"                      ->
        handler { (id: Long, req: Request) =>
          val pid                                  = ProfileId(id)
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireAuth(req, auth)
            _      <- requireProfileReadAccess(claims, pid, userProfileRepo, profileRepo)
            list   <- groupRepo.listAttachments(claims.hh, pid).mapError(ApiError.Db(_))
          } yield Response.json(list.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.PUT / "api" / "profiles" / long("id") / "app-groups" / long("groupId")    ->
        handler { (id: Long, groupId: Long, req: Request) =>
          val pid                                  = ProfileId(id)
          val gid                                  = AppGroupId(groupId)
          val handle: ZIO[Any, ApiError, Response] = metered("attach") {
            for {
              claims   <- requireWriter(req, auth)
              _        <- requireProfileAccess(claims, pid, userProfileRepo, profileRepo)
              _        <- requireAppGroupInHousehold(claims, gid, groupRepo)
              body     <- req.body.asString.orElseFail(ApiError.BadRequest(""))
              ar       <- ZIO
                .fromEither(body.fromJson[UpsertProfileAppGroupRequest])
                .mapError(ApiError.DecodeFailure(_))
              _        <- validLimits(ar.dailyMinutes, ar.weeklyMinutes)
              attached <- groupRepo
                .upsertAttachment(
                  claims.hh,
                  pid,
                  gid,
                  ar.dailyMinutes,
                  ar.weeklyMinutes,
                  // Same default as a per-app assignment (D5): exempt unless the client says it counts.
                  ar.exemptFromDaily.getOrElse(true),
                )
                .mapError(ApiError.Db(_))
                .flatMap(ZIO.fromEither(_).mapError(toApiError))
              _        <- invalidateSnapshot(claims.hh)
            } yield Response.json(attached.toJson)
          }
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.DELETE / "api" / "profiles" / long("id") / "app-groups" / long("groupId") ->
        handler { (id: Long, groupId: Long, req: Request) =>
          val pid                                  = ProfileId(id)
          val gid                                  = AppGroupId(groupId)
          val handle: ZIO[Any, ApiError, Response] = metered("detach") {
            for {
              claims <- requireWriter(req, auth)
              _      <- requireProfileAccess(claims, pid, userProfileRepo, profileRepo)
              _      <- requireAppGroupInHousehold(claims, gid, groupRepo)
              _      <- groupRepo.deleteAttachment(claims.hh, pid, gid).mapError(ApiError.Db(_))
              _      <- invalidateSnapshot(claims.hh)
            } yield Response.ok
          }
          handle.mapError(ErrorMapper.errorToResponse)
        },
    )
}
