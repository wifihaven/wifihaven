package wifihaven.api.routes

import wifihaven.api.auth.*
import wifihaven.api.cache.TimeStatusCache
import wifihaven.api.db.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*

import java.time.{LocalDate, LocalTime, ZoneId}

import zio.json.ast.Json

// ── Auth routes ────────────────────────────────────────────────────────────

// #1570 Stage 2: handlers fail with a typed [[ApiError]] mapped centrally by
// [[ErrorMapper.errorToResponse]]; the [[wifihaven.api.ErrorBoundary]] logs (4xx WARN / 5xx ERROR) +
// meters each error. Every case reproduces the EXACT status + body the hand-rolled code produced —
// DB failures stay 503 via [[ApiError.Db]]; the `password_change_required` 403 JSON and the
// `{status,db}` 503 auth-failure bodies are preserved verbatim (the latter via
// [[ApiError.Wrapped]] of `ErrorMapper.dbUnavailable`, since the label is a static string). The
// shared auth/visibility helpers below still return `Response` and are bridged via
// [[ApiError.Wrapped]] (their own migration is a later stage).
object AuthRoutes {
  def routes(
      auth: AuthService,
      userRepo: UserRepo,
      userProfileRepo: UserProfileRepo,
  ): Routes[Any, Response] =
    Routes(
      Method.POST / "api" / "auth" / "login"                 ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            body <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            lr   <- ZIO
              .fromEither(body.fromJson[LoginRequest])
              .mapError(ApiError.DecodeFailure(_))
            resp <- auth
              .login(lr.username, lr.password)
              .mapError {
                case AuthError.InvalidCredentials => ApiError.Unauthorized("Invalid credentials")
                case AuthError.Unexpected(_)      =>
                  ApiError.Wrapped(ErrorMapper.dbUnavailable("Unexpected"))
                case _                            =>
                  ApiError.Wrapped(ErrorMapper.dbUnavailable("AuthError"))
              }
          } yield Response.json(resp.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.POST / "api" / "auth" / "change-password"       ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            // Skip the must_change_password guard: this is the one route that
            // clears it. Using requireAuthSkipPwCheck prevents a deadlock where
            // the flag blocks the only endpoint that can reset itself (#586).
            claims <- requireAuthSkipPwCheck(req, auth).mapError(ApiError.Wrapped(_))
            body   <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            cpr    <- ZIO
              .fromEither(body.fromJson[ChangePasswordRequest])
              .mapError(ApiError.DecodeFailure(_))
            _      <- auth
              .changePassword(claims.sub, cpr.currentPassword, cpr.newPassword)
              .mapError {
                case AuthError.InvalidCredentials =>
                  ApiError.Unauthorized("Current password incorrect")
                case AuthError.Unexpected(_)      =>
                  ApiError.Wrapped(ErrorMapper.dbUnavailable("Unexpected"))
                case _                            =>
                  ApiError.Wrapped(ErrorMapper.dbUnavailable("AuthError"))
              }
          } yield Response.json(ChangePasswordResponse(mustChangePassword = false).toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.GET / "api" / "me"                              ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireAuth(req, auth).mapError(ApiError.Wrapped(_))
            pids   <- userProfileRepo
              .listProfilesForUsername(claims.sub)
              .mapError(ApiError.Db(_))
          } yield Response.json(
            MeResponse(
              claims.sub,
              UserRole.parse(claims.role).getOrElse(UserRole.Child),
              pids,
            ).toJson,
          )
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.POST / "api" / "users"                          ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            _    <- requireAdmin(req, auth).mapError(ApiError.Wrapped(_))
            body <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            cur  <- ZIO
              .fromEither(body.fromJson[CreateUserRequest])
              .mapError(ApiError.DecodeFailure(_))
            hash <- auth.hashPassword(cur.password)
            id   <- userRepo
              .create(cur.username, hash, UserRole.asString(cur.role))
              .mapError(ApiError.Db(_))
            _    <- userProfileRepo
              .setProfilesForUser(id, cur.profileIds)
              .mapError(ApiError.Db(_))
          } yield Response.json(s"""{"id":$id}""")
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.GET / "api" / "users"                           ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            _        <- requireAdmin(req, auth).mapError(ApiError.Wrapped(_))
            users    <- userRepo.listAll.mapError(ApiError.Db(_))
            mappings <- userProfileRepo.listAllMappings.mapError(ApiError.Db(_))
            byUser    = mappings.groupBy(_._1).view.mapValues(_.map(_._2)).toMap
            summaries = users.map(u =>
              UserSummary(u.id, u.username, u.role, byUser.getOrElse(u.id, Nil)),
            )
          } yield Response.json(summaries.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.PUT / "api" / "users" / long("id") / "profiles" ->
        handler { (id: Long, req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            _    <- requireAdmin(req, auth).mapError(ApiError.Wrapped(_))
            body <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            r    <- ZIO
              .fromEither(body.fromJson[SetUserProfilesRequest])
              .mapError(ApiError.DecodeFailure(_))
            _    <- userProfileRepo
              .setProfilesForUser(UserId(id), r.profileIds)
              .mapError(ApiError.Db(_))
          } yield Response.ok
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.DELETE / "api" / "users" / long("id")           ->
        handler { (id: Long, req: Request) =>
          val handle: ZIO[Any, ApiError, Response] =
            requireAdmin(req, auth).mapError(ApiError.Wrapped(_)) *>
              userRepo.delete(UserId(id)).mapError(ApiError.Db(_)) *>
              ZIO.succeed(Response.ok)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      // #997: field-scoped partial update. Body is a subset of the User read
      // shape — `username`, `role`, `profileIds` (replace-set, matches the
      // existing PUT /profiles semantics). Password changes stay on the
      // dedicated change-password endpoint.
      Method.PATCH / "api" / "users" / long("id")            ->
        handler { (id: Long, req: Request) =>
          val uid                                  = UserId(id)
          val handle: ZIO[Any, ApiError, Response] = for {
            _    <- requireAdmin(req, auth).mapError(ApiError.Wrapped(_))
            _    <- userRepo
              .findById(uid)
              .mapError(ApiError.Db(_))
              .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("User not found")))
            body <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            obj  <- ZIO.fromEither(FieldPatch.parseObj(body)).mapError(ApiError.BadRequest(_))
            usernamePatch <- ZIO
              .fromEither(FieldPatch.from[String](obj, "username"))
              .mapError(ApiError.BadRequest(_))
            rolePatch     <- ZIO
              .fromEither(FieldPatch.from[UserRole](obj, "role"))
              .mapError(ApiError.BadRequest(_))
            profilesPatch <- ZIO
              .fromEither(FieldPatch.from[List[ProfileId]](obj, "profileIds"))
              .mapError(ApiError.BadRequest(_))
            _             <- (usernamePatch, rolePatch, profilesPatch) match {
              case (FieldPatch.Cleared, _, _) =>
                ZIO.fail(ApiError.BadRequest("username cannot be cleared"))
              case (_, FieldPatch.Cleared, _) =>
                ZIO.fail(ApiError.BadRequest("role cannot be cleared"))
              case (_, _, FieldPatch.Cleared) =>
                ZIO.fail(
                  ApiError.BadRequest("profileIds cannot be cleared (send [] to unassign all)"),
                )
              case _                          => ZIO.unit
            }
            _             <- usernamePatch match {
              case FieldPatch.Set(u) =>
                userRepo.updateUsername(uid, u).mapError(ApiError.Db(_))
              case _                 => ZIO.unit
            }
            _             <- rolePatch match {
              case FieldPatch.Set(r) =>
                userRepo
                  .updateRole(uid, UserRole.asString(r))
                  .mapError(ApiError.Db(_))
              case _                 => ZIO.unit
            }
            _             <- profilesPatch match {
              case FieldPatch.Set(pids) =>
                userProfileRepo
                  .setProfilesForUser(uid, pids)
                  .mapError(ApiError.Db(_))
              case _                    => ZIO.unit
            }
          } yield Response.ok
          handle.mapError(ErrorMapper.errorToResponse)
        },
    )
}

// ── Profile routes ─────────────────────────────────────────────────────────

object ProfileRoutes {
  def routes(
      auth: AuthService,
      profileRepo: ProfileRepo,
      timeLimitRepo: TimeLimitRepo,
      userProfileRepo: UserProfileRepo,
      userRepo: UserRepo,
      namedScheduleRepo: NamedScheduleRepo = NoopNamedScheduleRepo,
      // #1538: the shared per-profile time-status cache, so the schedule-attach/detach PUT below
      // can bust the cached ProfileTimeStatus the same way `/api/time/extend` does. Defaulted so
      // the many test call sites that don't exercise caching keep their existing arity.
      cache: TimeStatusCache = TimeStatusCache.makeUnsafe(),
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "profiles"                            ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims      <- requireAuth(req, auth).mapError(ApiError.Wrapped(_))
            allProfiles <- profileRepo.listAll.mapError(ApiError.Db(_))
            visible     <- visibleProfiles(claims, allProfiles, userProfileRepo)
              .mapError(ApiError.Wrapped(_))
            details     <- ZIO
              .foreach(visible) { p =>
                for {
                  tl      <- timeLimitRepo.findForProfile(p.id)
                  schedId <- namedScheduleRepo.blockScheduleIdsForProfile(p.id)
                } yield ProfileDetail(p, tl, schedId)
              }
              .mapError(ApiError.Db(_))
          } yield Response.json(details.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.GET / "api" / "profiles" / long("id")               ->
        handler { (id: Long, req: Request) =>
          val pid                                  = ProfileId(id)
          val handle: ZIO[Any, ApiError, Response] = for {
            claims  <- requireAuth(req, auth).mapError(ApiError.Wrapped(_))
            _       <- requireProfileReadAccess(claims, pid, userProfileRepo)
              .mapError(ApiError.Wrapped(_))
            p       <- profileRepo
              .findById(pid)
              .mapError(ApiError.Db(_))
              .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("Profile not found")))
            tl      <- timeLimitRepo.findForProfile(pid).mapError(ApiError.Db(_))
            schedId <- namedScheduleRepo
              .blockScheduleIdsForProfile(pid)
              .mapError(ApiError.Db(_))
          } yield Response.json(ProfileDetail(p, tl, schedId).toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      // #1069: replace the set of named schedules attached to this profile as BLOCK schedules
      // (downtime while active). A profile can reference many; allow-mode is deferred. Kept off the
      // profile upsert so an ordinary profile save can't clobber the attachments.
      Method.PUT / "api" / "profiles" / long("id") / "schedules" ->
        handler { (id: Long, req: Request) =>
          val pid                                  = ProfileId(id)
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireWriter(req, auth).mapError(ApiError.Wrapped(_))
            _      <- requireProfileAccess(claims, pid, userProfileRepo)
              .mapError(ApiError.Wrapped(_))
            body   <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            sr     <- ZIO
              .fromEither(body.fromJson[SetProfileSchedulesRequest])
              .mapError(ApiError.DecodeFailure(_))
            _      <- profileRepo
              .findById(pid)
              .mapError(ApiError.Db(_))
              .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("Profile not found")))
            _      <- ZIO.foreachDiscard(sr.scheduleIds.distinct) { sid =>
              namedScheduleRepo
                .findById(sid)
                .mapError(ApiError.Db(_))
                .flatMap(
                  ZIO
                    .fromOption(_)
                    .orElseFail(ApiError.NotFound(s"Schedule ${sid.value} not found")),
                )
            }
            _      <- namedScheduleRepo
              .setProfileBlockSchedules(pid, sr.scheduleIds)
              .mapError(ApiError.Db(_))
            // #1538: attaching/detaching a block schedule changes whether this profile is "paused
            // for schedule", so bust its cached ProfileTimeStatus the same way /api/time/extend
            // does — otherwise a detach keeps showing a stale block for up to the today-TTL.
            _      <- cache.invalidateProfile(pid)
          } yield Response.ok
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.POST / "api" / "profiles"                           ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            _    <- requireAdmin(req, auth).mapError(ApiError.Wrapped(_))
            body <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            upr  <- ZIO
              .fromEither(body.fromJson[UpsertProfileRequest])
              .mapError(ApiError.DecodeFailure(_))
            id   <- profileRepo
              .create(upr.name, upr.blockedCategories)
              .mapError(ApiError.Db(_))
            _    <- profileRepo
              .update(
                Profile(
                  id,
                  upr.name,
                  upr.blockedCategories,
                  upr.paused,
                  // #385: missing failureMode → LastKnownGood (preserves
                  // cached-snapshot enforcement; matches DB column default).
                  // Role-aware defaulting is a UI concern — the server just
                  // persists whatever value the admin sent.
                  upr.failureMode.getOrElse(FailureMode.LastKnownGood),
                  // #424: omitted blockIpOnly defaults to false on create
                  // (matches the DB column default).
                  upr.blockIpOnly.getOrElse(false),
                  // #751: omitted crossDeviceOverlapMode defaults to Sum
                  // (matches the DB column default and preserves
                  // pre-#751 per-profile semantics).
                  upr.crossDeviceOverlapMode.getOrElse(CrossDeviceOverlapMode.Sum),
                  // #1418: omitted pauseMode defaults to Soft (matches the DB
                  // column default and preserves today's pause semantics).
                  upr.pauseMode.getOrElse(PauseMode.Soft),
                  // #1320: omitted defaultDeny defaults to false on create
                  // (matches the DB column default).
                  upr.defaultDeny.getOrElse(false),
                ),
              )
              .mapError(ApiError.Db(_))
            // #1494: profile upsert no longer writes the legacy `schedules`
            // table. Block schedules are attached via PUT
            // /api/profiles/{id}/schedules (named_schedules / profile_schedule_rules).
            _    <- ZIO
              .foreachDiscard(upr.timeLimit)(mins => timeLimitRepo.upsert(id, mins))
              .mapError(ApiError.Db(_))
          } yield Response.json(s"""{"id":${id.value}}""")
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.PUT / "api" / "profiles" / long("id")               ->
        handler { (id: Long, req: Request) =>
          val pid                                  = ProfileId(id)
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireWriter(req, auth).mapError(ApiError.Wrapped(_))
            _      <- requireProfileAccess(claims, pid, userProfileRepo)
              .mapError(ApiError.Wrapped(_))
            body   <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            upr    <- ZIO
              .fromEither(body.fromJson[UpsertProfileRequest])
              .mapError(ApiError.DecodeFailure(_))
            p      <- profileRepo
              .findById(pid)
              .mapError(ApiError.Db(_))
              .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("Profile not found")))
            _      <- profileRepo
              .update(
                p.copy(
                  name = upr.name,
                  blockedCategories = upr.blockedCategories,
                  paused = upr.paused,
                  // #385: if the caller omits failureMode, preserve the
                  // existing value rather than resetting to the column default.
                  failureMode = upr.failureMode.getOrElse(p.failureMode),
                  // #424: if caller omits blockIpOnly, preserve the
                  // existing value rather than clearing it.
                  blockIpOnly = upr.blockIpOnly.getOrElse(p.blockIpOnly),
                  // #751: same preserve-on-omit semantics.
                  crossDeviceOverlapMode =
                    upr.crossDeviceOverlapMode.getOrElse(p.crossDeviceOverlapMode),
                  // #1418: same preserve-on-omit semantics for the pause mode.
                  pauseMode = upr.pauseMode.getOrElse(p.pauseMode),
                  // #1320: same preserve-on-omit semantics for default-deny.
                  defaultDeny = upr.defaultDeny.getOrElse(p.defaultDeny),
                ),
              )
              .mapError(ApiError.Db(_))
            // #481: log mutations that should bump the policy snapshot etag.
            _      <- ZIO.logInfo(
              s"profile updated: id=${pid.value} paused=${p.paused}→${upr.paused} " +
                s"name=${upr.name}",
            )
            // #1494: profile upsert no longer writes the legacy `schedules`
            // table. Block schedules are attached via PUT
            // /api/profiles/{id}/schedules (named_schedules / profile_schedule_rules).
            _      <- (upr.timeLimit match {
              case Some(mins) => timeLimitRepo.upsert(pid, mins)
              case None       => timeLimitRepo.delete(pid)
            }).mapError(ApiError.Db(_))
          } yield Response.ok
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.DELETE / "api" / "profiles" / long("id")            ->
        handler { (id: Long, req: Request) =>
          val handle: ZIO[Any, ApiError, Response] =
            requireAdmin(req, auth).mapError(ApiError.Wrapped(_)) *>
              profileRepo.delete(ProfileId(id)).mapError(ApiError.Db(_)) *>
              ZIO.succeed(Response.ok)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.GET / "api" / "profiles" / long("id") / "users"     ->
        handler { (id: Long, req: Request) =>
          val pid                                  = ProfileId(id)
          val handle: ZIO[Any, ApiError, Response] = for {
            _     <- requireAdmin(req, auth).mapError(ApiError.Wrapped(_))
            uids  <- userProfileRepo
              .listUsersForProfile(pid)
              .mapError(ApiError.Db(_))
            users <- userRepo.listAll.mapError(ApiError.Db(_))
            byId      = users.map(u => u.id -> u).toMap
            summaries = uids.flatMap(byId.get).map { u =>
              UserSummary(u.id, u.username, u.role, List(pid))
            }
          } yield Response.json(summaries.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.PUT / "api" / "profiles" / long("id") / "users"     ->
        handler { (id: Long, req: Request) =>
          val pid                                  = ProfileId(id)
          val handle: ZIO[Any, ApiError, Response] = for {
            _    <- requireAdmin(req, auth).mapError(ApiError.Wrapped(_))
            body <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            r    <- ZIO
              .fromEither(body.fromJson[SetProfileUsersRequest])
              .mapError(ApiError.DecodeFailure(_))
            _    <- userProfileRepo
              .setUsersForProfile(pid, r.userIds)
              .mapError(ApiError.Db(_))
          } yield Response.ok
          handle.mapError(ErrorMapper.errorToResponse)
        },
      // #406: POST /api/profiles/:id/pause used to toggle paused state by
      // reading the row and writing !paused. That race-flips state under
      // concurrent calls (two browser tabs, retry-after-blip). Callers now
      // set `paused` explicitly via PUT /api/profiles/:id. See #423 for the
      // follow-up to add PATCH for race-safe field-scoped updates.
    )
}

// ── Device routes ──────────────────────────────────────────────────────────

object DeviceRoutes {
  def routes(
      auth: AuthService,
      deviceRepo: DeviceRepo,
      userProfileRepo: UserProfileRepo,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "devices"                    ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims  <- requireAuth(req, auth).mapError(ApiError.Wrapped(_))
            all     <- deviceRepo.listAll.mapError(ApiError.Db(_))
            visible <- filterDevices(claims, all, userProfileRepo).mapError(ApiError.Wrapped(_))
          } yield Response.json(visible.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.PUT / "api" / "devices"                    ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireWriter(req, auth).mapError(ApiError.Wrapped(_))
            body   <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            udr    <- ZIO
              .fromEither(body.fromJson[UpsertDeviceRequest])
              .mapError(ApiError.DecodeFailure(_))
            mac = MacAddress.unsafe(normalizeMac(udr.mac.value))
            // #708: profileId is optional — None means "unassigned" (NULL). The
            // access check only fires when the caller supplies a profileId; targeting
            // a profile they can't write to still 403s.
            _  <- udr.profileId match {
              case Some(pid) =>
                requireProfileAccess(claims, pid, userProfileRepo).mapError(ApiError.Wrapped(_))
              case None      => ZIO.unit
            }
            id <- deviceRepo
              .upsert(mac, udr.name, udr.profileId, "")
              .mapError(ApiError.Db(_))
            // #481: log device upsert so the next CI failure makes it obvious
            // whether the mutation reached the API at all.
            _  <- ZIO.logInfo(
              s"device upserted: mac=${mac.value} profileId=${udr.profileId.map(_.value.toString).getOrElse("-")} name=${udr.name}",
            )
          } yield Response.json(s"""{"id":${id.value}}""")
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.DELETE / "api" / "devices" / string("mac") ->
        handler { (mac: String, req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireWriter(req, auth).mapError(ApiError.Wrapped(_))
            normalized = MacAddress.unsafe(normalizeMac(mac))
            existing <- deviceRepo
              .findByMac(normalized)
              .mapError(ApiError.Db(_))
              .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("Device not found")))
            _        <- requireProfileAccess(claims, existing.profileId, userProfileRepo)
              .mapError(ApiError.Wrapped(_))
            _        <- deviceRepo.delete(normalized).mapError(ApiError.Db(_))
            // #481: same rationale as PUT — make the next CI failure diagnostic.
            _        <- ZIO.logInfo(s"device deleted: mac=${normalized.value}")
          } yield Response.ok
          handle.mapError(ErrorMapper.errorToResponse)
        },
      // #996: field-scoped partial update. Body is a subset of the Device read
      // shape — `name` (set), `profileId` (set/null-to-clear). Absent fields
      // preserve their current value. Same auth as DELETE: writer + access to
      // the device's current profile, plus access to the destination profile
      // if `profileId` is being reassigned.
      Method.PATCH / "api" / "devices" / string("mac")  ->
        handler { (mac: String, req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireWriter(req, auth).mapError(ApiError.Wrapped(_))
            normalized = MacAddress.unsafe(normalizeMac(mac))
            existing  <- deviceRepo
              .findByMac(normalized)
              .mapError(ApiError.Db(_))
              .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("Device not found")))
            _         <- requireProfileAccess(claims, existing.profileId, userProfileRepo)
              .mapError(ApiError.Wrapped(_))
            body      <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            obj       <- ZIO.fromEither(FieldPatch.parseObj(body)).mapError(ApiError.BadRequest(_))
            namePatch <- ZIO
              .fromEither(FieldPatch.from[String](obj, "name"))
              .mapError(ApiError.BadRequest(_))
            pidPatch  <- ZIO
              .fromEither(FieldPatch.from[ProfileId](obj, "profileId"))
              .mapError(ApiError.BadRequest(_))
            _         <- namePatch match {
              case FieldPatch.Cleared => ZIO.fail(ApiError.BadRequest("name cannot be cleared"))
              case _                  => ZIO.unit
            }
            _         <- pidPatch match {
              case FieldPatch.Set(pid) =>
                requireProfileAccess(claims, pid, userProfileRepo).mapError(ApiError.Wrapped(_))
              case _                   => ZIO.unit
            }
            newName = namePatch.applyTo(existing.name)
            newPid = pidPatch.applyToNullable(existing.profileId)
            _ <- deviceRepo
              .upsert(normalized, newName, newPid, "")
              .mapError(ApiError.Db(_))
            _ <- ZIO.logInfo(
              s"device patched: mac=${normalized.value} name=$newName profileId=${newPid.map(_.value.toString).getOrElse("-")}",
            )
          } yield Response.ok
          handle.mapError(ErrorMapper.errorToResponse)
        },
    )
}

// ── Time routes ────────────────────────────────────────────────────────────

object TimeRoutes {
  // #802/#1299: today-mode reads are authenticated and mutation-sensitive (a +Time grant
  // must show up immediately), so they're served no-store — the browser HTTP cache must
  // never answer a post-mutation refetch with a stale extensionMins. Past data is logically
  // immutable and safe to cache for an hour.
  private val PastMaxAgeSeconds: Long = 3600L

  // #802: emit a hit-rate summary every N requests. Cheap heuristic — no scheduler.
  private val StatsLogEveryNRequests = 100

  def routes(
      auth: AuthService,
      deviceRepo: DeviceRepo,
      timeLimitRepo: TimeLimitRepo,
      appTimeLimitRepo: AppTimeLimitRepo,
      trafficRepo: TrafficReportRepo,
      extRepo: TimeExtensionRepo,
      profileRepo: ProfileRepo,
      userProfileRepo: UserProfileRepo,
      hsRepo: HouseholdSettingsRepo,
      timeStatusService: wifihaven.api.policy.TimeStatusService,
      clock: Clock,
      cache: TimeStatusCache = TimeStatusCache.makeUnsafe(),
  ): Routes[Any, Response] =
    Routes(
      // #777 — collapsed accordion summary. One batched presence query over ALL devices
      // returns per-profile usedMins / dailyLimitMins / extensionMins / remainingMins. Used
      // by the SPA to render the unexpanded list without fanning out the full rollup.
      Method.GET / "api" / "time" / "status" / "summary"                ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] =
            for {
              claims   <- requireAuth(req, auth).mapError(ApiError.Wrapped(_))
              settings <- hsRepo.get.mapError(ApiError.Db(_))
              now      <- clock.instant
              // #1104: default to household-local "today", not UTC `clock.today`.
              today   = wifihaven.api.policy.PolicyService.householdLocalDate(now, settings)
              dateStr = req.url.queryParam("date").getOrElse(today.toString)
              date    = LocalDate.parse(dateStr)
              allProfiles <- profileRepo.listAll.mapError(ApiError.Db(_))
              visible     <- visibleProfiles(claims, allProfiles, userProfileRepo)
                .mapError(ApiError.Wrapped(_))
              states      <- timeStatusService
                .dayStateAll(now, date, settings)
                .mapError(ApiError.Db(_))
              summaries = visible.map { p =>
                val st = states.getOrElse(
                  p.id,
                  wifihaven.api.policy
                    .ProfileDayState(p.id, date, None, 0, 0, None, false, None, Nil),
                )
                ProfileTimeSummary(
                  p.id,
                  p.name,
                  st.date.toString,
                  st.dailyLimitMinutes,
                  st.usedMinutes,
                  st.extensionMinutes,
                  st.remainingMinutes,
                )
              }
            } yield Response
              .json(summaries.toJson)
              .addHeader(cacheControlFor(isTodayMode = !date.isBefore(today)))
          handle.mapError(ErrorMapper.errorToResponse)
        },
      // #777 — weekly variant of the summary endpoint. Single presence query over the trailing
      // 7-day range, per-mac bucket-deduped totals summed per profile.
      Method.GET / "api" / "time" / "status" / "summary" / "week"       ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] =
            for {
              claims   <- requireAuth(req, auth).mapError(ApiError.Wrapped(_))
              settings <- hsRepo.get.mapError(ApiError.Db(_))
              now      <- clock.instant
              // #1104: anchor on household-local today, not UTC today.
              today = wifihaven.api.policy.PolicyService.householdLocalDate(now, settings)
              toStr = req.url.queryParam("to").getOrElse(today.toString)
              to    = LocalDate.parse(toStr)
              from  = to.minusDays(6)
              allProfiles <- profileRepo.listAll.mapError(ApiError.Db(_))
              allDevices  <- deviceRepo.listAll.mapError(ApiError.Db(_))
              allLimits   <- timeLimitRepo.listAll.mapError(ApiError.Db(_))
              visible     <- visibleProfiles(claims, allProfiles, userProfileRepo)
                .mapError(ApiError.Wrapped(_))
              devicesByPid = allDevices.groupBy(_.profileId)
              allMacs      = visible.iterator
                .flatMap(p => devicesByPid.getOrElse(Some(p.id), Nil))
                .map(_.mac)
                .toList
                .distinct
              presence <- (if allMacs.isEmpty then ZIO.succeed(Nil)
                           else trafficRepo.listPresenceRows(allMacs, from, to))
                .mapError(ApiError.Db(_))
              limitByPid = allLimits.iterator.map(l => l.profileId -> l.dailyMinutes).toMap
              summaries  = visible
                .map { p =>
                  val devices = devicesByPid.getOrElse(Some(p.id), Nil)
                  val macSet  = devices.map(_.mac).toSet
                  val pRows   = presence.filter(r => macSet.contains(r.mac))
                  val perMac  = wifihaven.api.presence.Presence
                    .totalMinutesByMac(
                      pRows,
                      Nil,
                      settings.heartbeatFilter,
                      settings.presenceContinuationSeconds,
                    )
                  // #751: same Sum/Dedup branch as the daily summary.
                  val total   = p.crossDeviceOverlapMode match {
                    case CrossDeviceOverlapMode.Sum   =>
                      devices.iterator.map(d => perMac.getOrElse(d.mac, 0)).sum
                    case CrossDeviceOverlapMode.Dedup =>
                      wifihaven.api.presence.Presence
                        .dedupedTotalMinutes(
                          pRows,
                          Nil,
                          settings.heartbeatFilter,
                          settings.presenceContinuationSeconds,
                        )
                  }
                  ProfileTimeSummaryWeek(
                    p.id,
                    p.name,
                    from.toString,
                    to.toString,
                    limitByPid.get(p.id),
                    total,
                  )
                }
            } yield Response
              .json(summaries.toJson)
              .addHeader(cacheControlFor(isTodayMode = !to.isBefore(today)))
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.GET / "api" / "time" / "status"                            ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] =
            for {
              claims   <- requireAuth(req, auth).mapError(ApiError.Wrapped(_))
              settings <- hsRepo.get.mapError(ApiError.Db(_))
              now      <- clock.instant
              // #1104: household-local today, not UTC.
              today   = wifihaven.api.policy.PolicyService.householdLocalDate(now, settings)
              dateStr = req.url.queryParam("date").getOrElse(today.toString)
              date    = LocalDate.parse(dateStr)
              // #795: ?profileId=N narrows the rollup to a single profile so the
              // SPA can fetch one card's worth of data instead of fanning out N
              // sub-rollups. Auth scope still applies — child tokens can only
              // request profiles they're already entitled to see.
              profileIdOpt <- parseProfileIdParam(req).mapError(ApiError.Wrapped(_))
              allProfiles  <- profileRepo.listAll.mapError(ApiError.Db(_))
              allDevices   <- deviceRepo.listAll.mapError(ApiError.Db(_))
              visible      <- visibleProfiles(claims, allProfiles, userProfileRepo)
                .mapError(ApiError.Wrapped(_))
              scoped       = profileIdOpt match {
                case Some(pid) => visible.filter(_.id == pid)
                case None      => visible
              }
              devicesByPid = allDevices.groupBy(_.profileId)
              statuses <- ZIO
                .foreach(scoped) { p =>
                  // #802: in-process cache keyed by (profileId, date). Cache miss falls through
                  // to the same builder as before; hit returns the prior render without hitting
                  // the DB. The cache layer chooses TTL (today vs past) based on `today`.
                  cache
                    .getOrLoadDaily(p.id, date, today) {
                      buildProfileTimeStatus(
                        p,
                        devicesByPid.getOrElse(Some(p.id), Nil),
                        date,
                        now,
                        settings,
                        timeStatusService,
                        trafficRepo,
                        appTimeLimitRepo,
                      )
                    }
                }
                .mapError(ApiError.Db(_))
              _        <- logCacheStatsPeriodically(cache)
            } yield Response
              .json(statuses.toJson)
              .addHeader(cacheControlFor(isTodayMode = !date.isBefore(today)))
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.GET / "api" / "time" / "status" / "week"                   ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] =
            for {
              claims   <- requireAuth(req, auth).mapError(ApiError.Wrapped(_))
              settings <- hsRepo.get.mapError(ApiError.Db(_))
              now      <- clock.instant
              // #1104: household-local today (was UTC).
              today = wifihaven.api.policy.PolicyService.householdLocalDate(now, settings)
              // ?to=YYYY-MM-DD anchors the trailing 7-day window; defaults to today (household-local).
              // ?bucketOffsetMin=N sets the minute-past-the-hour where the hourly grid starts,
              // so each bucket falls fully within one local day on the caller's side (#794).
              // Accepts 0/15/30/45; defaults to 0 (whole-hour grid, fine for UTC-aligned zones).
              toStr = req.url.queryParam("to").getOrElse(today.toString)
              to    = LocalDate.parse(toStr)
              from  = to.minusDays(6)
              bucketOffsetMin <- parseBucketOffsetMin(req)
              // #795: same single-profile narrowing as the daily endpoint.
              profileIdOpt    <- parseProfileIdParam(req).mapError(ApiError.Wrapped(_))
              allProfiles     <- profileRepo.listAll.mapError(ApiError.Db(_))
              allDevices      <- deviceRepo.listAll.mapError(ApiError.Db(_))
              visible         <- visibleProfiles(claims, allProfiles, userProfileRepo)
                .mapError(ApiError.Wrapped(_))
              scoped       = profileIdOpt match {
                case Some(pid) => visible.filter(_.id == pid)
                case None      => visible
              }
              devicesByPid = allDevices.groupBy(_.profileId)
              statuses <- ZIO
                .foreach(scoped) { p =>
                  // #802: weekly cache keyed by (profileId, from, to, bucketOffsetMin). Same TTL
                  // logic — short TTL if `to` straddles today, long TTL for fully-past windows.
                  // bucketOffsetMin is in the key because different offsets yield different
                  // per-day buckets (#794), so they must not collide.
                  cache
                    .getOrLoadWeekly(p.id, from, to, bucketOffsetMin, today) {
                      buildProfileTimeStatusWeek(
                        p,
                        devicesByPid.getOrElse(Some(p.id), Nil),
                        from,
                        to,
                        timeLimitRepo,
                        trafficRepo,
                        settings.heartbeatFilter,
                        bucketOffsetMin,
                      )
                    }
                }
                .mapError(ApiError.Db(_))
              _        <- logCacheStatsPeriodically(cache)
            } yield Response
              .json(statuses.toJson)
              .addHeader(cacheControlFor(isTodayMode = !to.isBefore(today)))
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.GET / "api" / "time" / "status" / string("mac") / "week"   ->
        handler { (mac: String, req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims   <- requireAuth(req, auth).mapError(ApiError.Wrapped(_))
            settings <- hsRepo.get.mapError(ApiError.Db(_))
            now      <- clock.instant
            // #1104: household-local today.
            today = wifihaven.api.policy.PolicyService.householdLocalDate(now, settings)
            toStr = req.url.queryParam("to").getOrElse(today.toString)
            to    = LocalDate.parse(toStr)
            from  = to.minusDays(6)
            bucketOffsetMin <- parseBucketOffsetMin(req)
            device          <- deviceRepo
              .findByMac(MacAddress.unsafe(normalizeMac(mac)))
              .mapError(ApiError.Db(_))
              .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("Device not found")))
            _               <- requireProfileReadAccess(claims, device.profileId, userProfileRepo)
              .mapError(ApiError.Wrapped(_))
            status          <- buildDeviceTimeStatusWeek(
              device,
              from,
              to,
              profileRepo,
              timeLimitRepo,
              trafficRepo,
              settings.heartbeatFilter,
              bucketOffsetMin,
            )
              .mapError(ApiError.Db(_))
          } yield Response.json(status.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.GET / "api" / "time" / "status" / string("mac")            ->
        handler { (mac: String, req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims   <- requireAuth(req, auth).mapError(ApiError.Wrapped(_))
            settings <- hsRepo.get.mapError(ApiError.Db(_))
            now      <- clock.instant
            // #1104: household-local today.
            today   = wifihaven.api.policy.PolicyService.householdLocalDate(now, settings)
            dateStr = req.url.queryParam("date").getOrElse(today.toString)
            date    = LocalDate.parse(dateStr)
            device <- deviceRepo
              .findByMac(MacAddress.unsafe(normalizeMac(mac)))
              .mapError(ApiError.Db(_))
              .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("Device not found")))
            _      <- requireProfileReadAccess(claims, device.profileId, userProfileRepo)
              .mapError(ApiError.Wrapped(_))
            status <- buildDeviceTimeStatus(
              device,
              date,
              now,
              settings,
              profileRepo,
              timeStatusService,
              trafficRepo,
              appTimeLimitRepo,
            )
              .mapError(ApiError.Db(_))
          } yield Response.json(status.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.GET / "api" / "time" / "heartbeat-explain" / string("mac") ->
        handler { (mac: String, req: Request) =>
          // #714: per-row classification of `traffic_reports` rows that feed into
          // Presence.totalSecondsByMac for this device on `date` (default = today).
          // The classification reflects current household_settings — i.e. the same
          // verdict the live screen-time calculation is using right now. Operators
          // tune `heartbeat_bytes_threshold` against this surface before flipping
          // `heartbeat_filter_enabled` on.
          val handle: ZIO[Any, ApiError, Response] =
            for {
              claims   <- requireAuth(req, auth).mapError(ApiError.Wrapped(_))
              settings <- hsRepo.get.mapError(ApiError.Db(_))
              now      <- clock.instant
              // #1104: household-local today.
              today   = wifihaven.api.policy.PolicyService.householdLocalDate(now, settings)
              dateStr = req.url.queryParam("date").getOrElse(today.toString)
              date    = LocalDate.parse(dateStr)
              device <- deviceRepo
                .findByMac(MacAddress.unsafe(normalizeMac(mac)))
                .mapError(ApiError.Db(_))
                .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("Device not found")))
              _      <- requireProfileReadAccess(claims, device.profileId, userProfileRepo)
                .mapError(ApiError.Wrapped(_))
              rows   <- trafficRepo
                .listPresenceRows(List(device.mac), date)
                .mapError(ApiError.Db(_))
              classified = wifihaven.api.presence.Presence
                .classifyRows(rows, settings.heartbeatFilter)
                .map { c =>
                  HeartbeatExplainRow(
                    mac = c.row.mac,
                    periodStart = c.row.periodStart.toString,
                    host = c.row.host,
                    activeSeconds = c.row.activeSeconds,
                    periodSeconds = c.row.periodSeconds,
                    bytes = c.row.bytes,
                    classified = c.classified,
                    reasons = c.reasons,
                  )
                }
            } yield Response.json(
              HeartbeatExplainResponse(
                mac = device.mac,
                date = date.toString,
                filter = settings.heartbeatFilter,
                rows = classified,
              ).toJson,
            )
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.POST / "api" / "time" / "extend"                           ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims   <- requireWriter(req, auth).mapError(ApiError.Wrapped(_))
            body     <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            ger      <- ZIO
              .fromEither(body.fromJson[GrantExtensionRequest])
              .mapError(ApiError.DecodeFailure(_))
            _        <- requireProfileAccess(claims, ger.profileId, userProfileRepo)
              .mapError(ApiError.Wrapped(_))
            // #1010: bucket the grant under the household-local "today" so the
            // policy-snapshot read path (also household-local) finds it.
            settings <- hsRepo.get.mapError(ApiError.Db(_))
            now      <- clock.instant
            today = wifihaven.api.policy.PolicyService.householdLocalDate(now, settings)
            id <- extRepo
              .grantForProfile(ger.profileId, today, ger.extraMinutes, claims.sub, ger.note)
              .mapError(ApiError.Db(_))
            // #946: bust the cached ProfileTimeStatus for this profile so the SPA's next
            // refetch reflects the new cap immediately instead of waiting up to todayTtl.
            _  <- cache.invalidateProfile(ger.profileId)
          } yield Response.json(s"""{"id":${id.value},"grantedMinutes":${ger.extraMinutes}}""")
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.GET / "api" / "time" / "extensions" / long("profileId")    ->
        handler { (profileId: Long, req: Request) =>
          val pid                                  = ProfileId(profileId)
          val handle: ZIO[Any, ApiError, Response] = for {
            claims   <- requireAuth(req, auth).mapError(ApiError.Wrapped(_))
            _        <- requireProfileAccess(claims, pid, userProfileRepo)
              .mapError(ApiError.Wrapped(_))
            // #1010: same household-local "today" as the grant path.
            settings <- hsRepo.get.mapError(ApiError.Db(_))
            now      <- clock.instant
            date = wifihaven.api.policy.PolicyService.householdLocalDate(now, settings)
            exts <- extRepo
              .listForProfile(pid, date)
              .mapError(ApiError.Db(_))
          } yield Response.json(exts.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
    )

  // #802/#1299: Cache-Control header derived from whether the response covers today.
  // Today: no-store — authenticated and mutation-sensitive, so the SPA's post-mutation
  //        refetch must never be answered from the browser HTTP cache (stale extensionMins).
  // Past:  long max-age — past data is logically immutable.
  // `private` because mutating endpoints (POST /api/time/extend) don't need it.
  private def cacheControlFor(isTodayMode: Boolean): Header.CacheControl =
    if isTodayMode then Header.CacheControl.NoStore
    else Header.CacheControl.MaxAge(PastMaxAgeSeconds.toInt)

  // #802: emit a one-line stats log every N (hits+misses), no scheduler needed. Sampled
  // by the cache itself so concurrent callers don't race on a counter the route holds.
  private def logCacheStatsPeriodically(cache: TimeStatusCache): UIO[Unit] =
    cache.snapshot.flatMap { s =>
      ZIO
        .logInfo(
          f"time-status cache: hits=${s.hits} misses=${s.misses} hitRate=${s.hitRate * 100}%.1f%% " +
            s"todaySize=${s.todaySize} pastSize=${s.pastSize}",
        )
        .when(s.total > 0 && s.total % StatsLogEveryNRequests == 0)
        .unit
    }

  /**
   * #1104: builds the daily per-profile wire shape. Cap/extension/remaining/per-site numbers come
   * from the canonical `TimeStatusService.dayState` so they cannot drift from the policy snapshot's
   * `blocked`/`blockReason`. Per-device summaries and the top-10 host attribution are still
   * computed from the day's presence rows here (snapshot doesn't need them).
   */
  private def buildProfileTimeStatus(
      profile: Profile,
      devices: List[Device],
      date: LocalDate,
      now: java.time.Instant,
      settings: HouseholdSettings,
      timeStatusService: wifihaven.api.policy.TimeStatusService,
      trafficRepo: TrafficReportRepo,
      appTimeLimitRepo: AppTimeLimitRepo,
  ): Task[ProfileTimeStatus] = {
    val macs = devices.map(_.mac)
    for {
      stateOpt <- timeStatusService.dayState(now, date, settings, profile.id)
      state = stateOpt.getOrElse(
        wifihaven.api.policy.ProfileDayState(profile.id, date, None, 0, 0, None, false, None, Nil),
      )
      presence  <- trafficRepo.listPresenceRows(macs, date)
      appLimits <- appTimeLimitRepo.listForProfile(profile.id)
      // #1546: per-device minutes come from the canonical per-mac decomposition of the headline
      // total, so they share one exempt-pattern + overlap definition with `state.usedMinutes` and
      // cannot drift from it (no open-coded `totalMinutesByMac` recompute). Under Dedup this is a
      // disjoint attribution of the union, so the summaries reconcile with the headline instead of
      // summing to >100%.
      perMacSeconds   = wifihaven.api.policy.TimeStatusService
        .usedSecondsByMac(profile, devices, appLimits, presence, settings)
      appUsage        = state.perApp.map { s =>
        AppUsage(
          s.label,
          s.domainPattern,
          s.dailyLimitMinutes,
          s.usedMinutes,
          s.dailyLimitMinutes.map(lim => (lim - s.usedMinutes).max(0)),
        )
      }
      deviceSummaries = devices.map { d =>
        DeviceUsageSummary(d.mac, d.name, (perMacSeconds.getOrElse(d.mac, 0L) / 60L).toInt)
      }
      // #262 — top-N host attribution across all profile devices for the day.
      // `usedMins` is bucket-presence and `proportionalMins` is the #715
      // byte-share-weighted attribution; UI defaults to the latter. Hosts
      // with zero presence are dropped so the top-10 list isn't padded by
      // hosts that exist only because the bucket touched them at all.
      hostUsage       = {
        val presenceMins =
          wifihaven.api.presence.Presence.hostMinutes(presence, settings.heartbeatFilter)
        val proportional = wifihaven.api.presence.Presence
          .proportionalHostMinutes(
            presence,
            profile.crossDeviceOverlapMode,
            settings.heartbeatFilter,
            settings.presenceContinuationSeconds,
          )
        presenceMins.iterator
          .filter(_._2 > 0)
          .map { case (h, m) => HostUsage(h, m, proportional.getOrElse(h, 0)) }
          .toList
          .sortBy(hu => (-hu.proportionalMins, -hu.usedMins, hu.host.value))
          .take(10)
      }
    } yield ProfileTimeStatus(
      profile.id,
      profile.name,
      state.date.toString,
      state.dailyLimitMinutes,
      state.usedMinutes,
      state.extensionMinutes,
      state.remainingMinutes,
      appUsage,
      deviceSummaries,
      hostUsage,
    )
  }

  /**
   * #723 weekly variant. Sums presence rows across the trailing range and bucket-dedupes per-mac
   * and per-host across the WHOLE range — naive day-by-day sums would double-count buckets a device
   * spread across midnight (rare but possible). Per-day totals are computed independently per
   * `date` so each bar in the UI matches what a `Today`-view for that date would report.
   */
  private def buildProfileTimeStatusWeek(
      profile: Profile,
      devices: List[Device],
      from: LocalDate,
      to: LocalDate,
      tlRepo: TimeLimitRepo,
      trafficRepo: TrafficReportRepo,
      heartbeatFilter: HeartbeatFilter,
      bucketOffsetMin: Int,
  ): Task[ProfileTimeStatusWeek] = {
    val macs = devices.map(_.mac)
    for {
      tl       <- tlRepo.findForProfile(profile.id)
      presence <- trafficRepo.listPresenceRows(macs, from, to)
      // Range aggregates: bucket-dedup across the full range, no exempt-pattern filtering
      // (weekly view is informational). Heartbeat filter applied for symmetry with the daily
      // view (#714). Per-FQDN attribution caveats from #715 still apply.
      perMacTotal     = wifihaven.api.presence.Presence
        .totalMinutesByMac(presence, Nil, heartbeatFilter)
      // #751: same Sum/Dedup branch as the daily endpoint, applied across the
      // entire weekly range. perBucket below also respects the mode so the
      // bars reconcile to totalUsed.
      totalUsed       = profile.crossDeviceOverlapMode match {
        case CrossDeviceOverlapMode.Sum   =>
          devices.iterator.map(d => perMacTotal.getOrElse(d.mac, 0)).sum
        case CrossDeviceOverlapMode.Dedup =>
          wifihaven.api.presence.Presence
            .dedupedTotalMinutes(presence, Nil, heartbeatFilter)
      }
      deviceSummaries = devices.map { d =>
        DeviceUsageSummary(d.mac, d.name, perMacTotal.getOrElse(d.mac, 0))
      }
      hostUsage       = {
        val presenceMins = wifihaven.api.presence.Presence.hostMinutes(presence, heartbeatFilter)
        val proportional = wifihaven.api.presence.Presence
          .proportionalHostMinutes(presence, profile.crossDeviceOverlapMode, heartbeatFilter)
        presenceMins.iterator
          .filter(_._2 > 0)
          .map { case (h, m) => HostUsage(h, m, proportional.getOrElse(h, 0)) }
          .toList
          .sortBy(hu => (-hu.proportionalMins, -hu.usedMins, hu.host.value))
          .take(10)
      }
      perBucket       =
        bucketHourlyAligned(
          presence,
          heartbeatFilter,
          bucketOffsetMin,
          profile.crossDeviceOverlapMode,
        )
    } yield ProfileTimeStatusWeek(
      profile.id,
      profile.name,
      from.toString,
      to.toString,
      tl.map(_.dailyMinutes),
      totalUsed,
      perBucket,
      deviceSummaries,
      hostUsage,
    )
  }

  /** Per-device weekly variant of [[buildProfileTimeStatusWeek]]. */
  private def buildDeviceTimeStatusWeek(
      device: Device,
      from: LocalDate,
      to: LocalDate,
      profileRepo: ProfileRepo,
      tlRepo: TimeLimitRepo,
      trafficRepo: TrafficReportRepo,
      heartbeatFilter: HeartbeatFilter,
      bucketOffsetMin: Int,
  ): Task[DeviceTimeStatusWeek] = {
    val pid  = device.profileId
    val macs = List(device.mac)
    for {
      tl       <- pid.fold(ZIO.succeed(Option.empty[TimeLimit]))(tlRepo.findForProfile)
      profile  <- pid.fold(ZIO.succeed("No profile"))(p =>
        profileRepo.findById(p).map(_.map(_.name).getOrElse("Unknown")),
      )
      presence <- trafficRepo.listPresenceRows(macs, from, to)
      perMac    = wifihaven.api.presence.Presence
        .totalMinutesByMac(presence, Nil, heartbeatFilter)
      totalUsed = perMac.getOrElse(device.mac, 0)
      hostUsage = {
        val presenceMins = wifihaven.api.presence.Presence.hostMinutes(presence, heartbeatFilter)
        // Per-device path: only one mac in play, so Sum/Dedup are equivalent.
        val proportional = wifihaven.api.presence.Presence
          .proportionalHostMinutes(presence, CrossDeviceOverlapMode.Sum, heartbeatFilter)
        presenceMins.iterator
          .filter(_._2 > 0)
          .map { case (h, m) => HostUsage(h, m, proportional.getOrElse(h, 0)) }
          .toList
          .sortBy(hu => (-hu.proportionalMins, -hu.usedMins, hu.host.value))
          .take(10)
      }
      // Per-device path: only one mac in play, so the dedup/sum distinction is
      // moot; use the Sum branch to keep the historical wiring.
      perBucket =
        bucketHourlyAligned(presence, heartbeatFilter, bucketOffsetMin, CrossDeviceOverlapMode.Sum)
    } yield DeviceTimeStatusWeek(
      device.mac,
      device.name,
      from.toString,
      to.toString,
      profile,
      pid,
      tl.map(_.dailyMinutes),
      totalUsed,
      perBucket,
      hostUsage,
    )
  }

  /**
   * Parse the `bucketOffsetMin` query param (#794). Accepts the four real-world tz-alignment minute
   * offsets: 0 (UTC / whole-hour zones), 15 (+5:45-style), 30 (+5:30-style), 45 (+12:45). Defaults
   * to 0 when absent. Any other value is a 400 — keeps the bucket grid well-defined and prevents
   * the SPA from accidentally requesting a misaligned grid.
   */
  private def parseBucketOffsetMin(req: Request): IO[ApiError, Int] =
    req.url.queryParam("bucketOffsetMin") match {
      case None      => ZIO.succeed(0)
      case Some(raw) =>
        raw.toIntOption match {
          case Some(n) if Set(0, 15, 30, 45).contains(n) => ZIO.succeed(n)
          case _                                         =>
            ZIO.fail(ApiError.BadRequest(s"bucketOffsetMin must be one of 0/15/30/45, got: $raw"))
        }
    }

  /**
   * Roll the range's presence rows up to hourly UTC buckets aligned at `offsetMin` minutes past the
   * hour (#794). Caller passes the offset that makes each bucket fall fully within one local day (0
   * for whole-hour zones, 30 for half-hour zones, 15/45 for the quarter-hour zones). Each 5-min
   * `period_start` falls in exactly one hour slot of the chosen grid; per-slot minutes go through
   * `Presence.totalMinutesByMac` for per-mac bucket-dedup + heartbeat filter, then sum across macs.
   * Empty slots are omitted (the SPA fills gaps with zero when grouping by local day). Returned
   * list is sorted by `bucketStart` ascending.
   */
  private def bucketHourlyAligned(
      presence: List[wifihaven.api.presence.PresenceRow],
      heartbeatFilter: HeartbeatFilter,
      offsetMin: Int,
      overlap: CrossDeviceOverlapMode,
  ): List[ProfileTimeBucket] = {
    val hourSeconds   = 3600L
    val offsetSeconds = offsetMin.toLong * 60L
    presence
      .groupBy { r =>
        val s    = r.periodStart.getEpochSecond
        val slot =
          java.lang.Math.floorDiv(s - offsetSeconds, hourSeconds) * hourSeconds + offsetSeconds
        java.time.Instant.ofEpochSecond(slot)
      }
      .iterator
      .map { case (bucketStart, rows) =>
        // #751: respect the profile's overlap mode so the hourly bars reconcile
        // with the headline totalUsed.
        val mins = overlap match {
          case CrossDeviceOverlapMode.Sum   =>
            wifihaven.api.presence.Presence
              .totalMinutesByMac(rows, Nil, heartbeatFilter)
              .values
              .sum
          case CrossDeviceOverlapMode.Dedup =>
            wifihaven.api.presence.Presence
              .dedupedTotalMinutes(rows, Nil, heartbeatFilter)
        }
        ProfileTimeBucket(bucketStart, mins)
      }
      .filter(_.usedMins > 0)
      .toList
      .sortBy(_.bucketStart)
  }

  /**
   * #1104: per-device daily wire shape. Cap (`dailyLimitMins`/`extensionMins`/`remainingMins`)
   * comes from the profile-level `ProfileDayState` so the per-device view matches the per-profile
   * view and the snapshot. Per-device active minutes and per-site bars still need this device's
   * presence rows, but the daily cap fields read from `state`.
   */
  private def buildDeviceTimeStatus(
      device: Device,
      date: LocalDate,
      now: java.time.Instant,
      settings: HouseholdSettings,
      profileRepo: ProfileRepo,
      timeStatusService: wifihaven.api.policy.TimeStatusService,
      trafficRepo: TrafficReportRepo,
      appTimeLimitRepo: AppTimeLimitRepo,
  ): Task[DeviceTimeStatus] = {
    val pid = device.profileId
    for {
      stateOpt   <- pid.fold(ZIO.succeed(Option.empty[wifihaven.api.policy.ProfileDayState]))(p =>
        timeStatusService.dayState(now, date, settings, p),
      )
      presence   <- trafficRepo.listPresenceRows(List(device.mac), date)
      profileOpt <- pid.fold(ZIO.succeed(Option.empty[Profile]))(profileRepo.findById)
      appLimits  <- pid.fold(ZIO.succeed(List.empty[AppTimeLimit]))(
        appTimeLimitRepo.listForProfile,
      )
      profile   = profileOpt.map(_.name).getOrElse(if (pid.isEmpty) "No profile" else "Unknown")
      // #1546: the per-device headline reads the same per-mac decomposition the profile view's
      // summaries do, so its exempt-pattern + overlap definition matches the canonical
      // `state.usedMinutes` (no open-coded `totalMinutesByMac` recompute). Single-device, so Sum
      // and Dedup coincide — the device is credited its own engaged time.
      totalUsed = profileOpt.fold(0)(p =>
        (wifihaven.api.policy.TimeStatusService
          .usedSecondsByMac(p, List(device), appLimits, presence, settings)
          .getOrElse(device.mac, 0L) / 60L).toInt,
      )
      // #1505 + #1504: per-device per-app usage via the #1464 session-stitch primitive, aggregated
      // across each app's whole host-set (one bar per app). Single-device view, so cross-device
      // overlap mode is moot — Sum and Dedup coincide.
      perApp    = wifihaven.api.presence.Presence
        .patternGroupMinutesForProfile(
          presence,
          stateOpt.toList.flatMap(_.perApp).map(s => s.label -> s.hosts),
          wifihaven.shared.CrossDeviceOverlapMode.Sum,
          settings.heartbeatFilter,
          settings.presenceContinuationSeconds,
        )
      appUsage  = stateOpt.toList.flatMap(_.perApp).map { s =>
        val used = perApp.getOrElse(s.label, 0)
        AppUsage(
          s.label,
          s.domainPattern,
          s.dailyLimitMinutes,
          used,
          s.dailyLimitMinutes.map(lim => (lim - used).max(0)),
        )
      }
    } yield DeviceTimeStatus(
      device.mac,
      device.name,
      date.toString,
      profile,
      pid,
      stateOpt.flatMap(_.dailyLimitMinutes),
      totalUsed,
      stateOpt.map(_.extensionMinutes).getOrElse(0),
      stateOpt.flatMap(_.remainingMinutes),
      appUsage,
    )
  }

}

// ── Query log routes ───────────────────────────────────────────────────────

object LogRoutes {
  // #1265: choose the source table for /api/connection-events/series. Mirrors
  // UsageRoutes.pickTier on the traffic side: the bucket width caps how coarse
  // a rollup may serve it (via the shared wifihaven.shared.BucketPolicy, so the
  // two endpoints can't drift), and a wide window justifies coarsening on cost
  // grounds. The finer of (cap, window-pref) wins. Raw → None (read the live
  // connection_events table). includeMulticast forces raw, because the reroll
  // excludes multicast/broadcast at write time so the rollups can't serve it.
  private def seriesGrain(
      bucket: ConnectionEventBucket,
      hours: Int,
      includeMulticast: Boolean,
  ): Option[BucketGrain] =
    if (includeMulticast) None
    else {
      val cap                       = BucketPolicy.grainForBucket(bucket.wire)
      val pref                      =
        if (hours <= 24) BucketGrain.Raw
        else if (hours <= 14 * 24) BucketGrain.Hourly
        else BucketGrain.Daily
      def rank(g: BucketGrain): Int = g match {
        case BucketGrain.Raw    => 0
        case BucketGrain.Hourly => 1
        case BucketGrain.Daily  => 2
      }
      val chosen                    = if (rank(pref) <= rank(cap)) pref else cap
      chosen match {
        case BucketGrain.Raw => None
        case g               => Some(g)
      }
    }

  // #862: tiny parse helpers for pagination params. Pulled into top-level so
  // both /api/logs and /api/connection-events/series use the same shape.
  private def parseInstantOpt(req: Request, name: String): IO[ApiError, Option[java.time.Instant]] =
    req.url.queryParam(name) match {
      case None    => ZIO.succeed(None)
      case Some(s) =>
        ZIO
          .attempt(Some(java.time.Instant.parse(s)))
          .orElseFail(ApiError.BadRequest(s"invalid $name: $s"))
    }

  private def parseLimit(req: Request, default: Int, max: Int): IO[ApiError, Int] =
    req.url.queryParam("limit") match {
      case None    => ZIO.succeed(default)
      case Some(s) =>
        s.toIntOption match {
          case None    => ZIO.fail(ApiError.BadRequest(s"invalid limit: $s"))
          case Some(n) =>
            if (n < 1) ZIO.fail(ApiError.BadRequest("limit must be >= 1"))
            else if (n > max) ZIO.fail(ApiError.BadRequest(s"limit must be <= $max"))
            else ZIO.succeed(n)
        }
    }

  private def parseLogCursor(req: Request): IO[ApiError, Option[Cursor.LogCursor]] =
    req.url.queryParam("cursor") match {
      case None    => ZIO.succeed(None)
      case Some(s) =>
        ZIO
          .fromEither(Cursor.decode[Cursor.LogCursor](s))
          .mapBoth(ApiError.BadRequest(_), Some(_))
    }

  private def parseAggCursor(req: Request): IO[ApiError, Option[Cursor.AggCursor]] =
    req.url.queryParam("cursor") match {
      case None    => ZIO.succeed(None)
      case Some(s) =>
        ZIO
          .fromEither(Cursor.decode[Cursor.AggCursor](s))
          .mapBoth(ApiError.BadRequest(_), Some(_))
    }

  // #862: must mirror the SQL's group_key concatenation order exactly
  // (domain, device, profile — only requested columns, US-separator).
  // Separator byte is sourced from `LogAggGroupKey` so the SQL `chr(N) ||`
  // concat in `ConnectionEventRepo` and this builder cannot drift (#1532).
  private def aggGroupKey(r: ConnectionEventAggRow): String =
    List("domain", "device", "profile").iterator
      .flatMap(k => r.groups.get(k))
      .mkString(LogAggGroupKey.Separator)

  def routes(
      auth: AuthService,
      connRepo: ConnectionEventRepo,
      userProfileRepo: UserProfileRepo,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "logs"                         ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims     <- requireAuth(req, auth).mapError(ApiError.Wrapped(_))
            // #865: mac/deviceId/profileId accept comma-separated multi-value lists.
            // Old single-value URLs (e.g. ?profileId=2) parse to a one-element list.
            deviceIds  <- parseMultiDeviceIdParam(req).mapError(ApiError.Wrapped(_))
            profileIds <- parseMultiProfileIdParam(req).mapError(ApiError.Wrapped(_))
            untilOpt   <- parseInstantOpt(req, "until")
            cursorOpt  <- parseLogCursor(req)
            // #862: page cap. 500 max, 200 default. Wider pages would let one
            // tab freeze the SPA when scrolling fast.
            limit      <- parseLimit(req, default = 200, max = 500)
            filter = LogFilter(
              macs = parseMultiValueParam(req, "mac"),
              deviceIds = deviceIds,
              profileIds = profileIds,
              blocked = req.url.queryParam("blocked").map(_ == "true"),
              domain = req.url.queryParam("domain"),
              location = req.url.queryParam("location"),
              hours = req.url.queryParam("hours").flatMap(_.toIntOption).getOrElse(24),
              limit = limit,
              until = untilOpt,
              cursorTs = cursorOpt.map(_.ts),
              cursorId = cursorOpt.map(_.id),
              includeMulticast = req.url.queryParam("includeMulticast").contains("true"),
            )
            logs    <- connRepo.query(filter).mapError(ApiError.Db(_))
            visible <- filterLogs(claims, logs, userProfileRepo).mapError(ApiError.Wrapped(_))
            // #862: nextCursor is built from the *raw* last row, not the
            // post-filter `visible` list — filterLogs may drop rows the child
            // can't see, but the cursor must continue from where the SQL window
            // left off so we don't skip rows on the next page.
            nextCur =
              if (logs.size < limit) None
              else
                logs.lastOption.map(l =>
                  Cursor.encode(Cursor.LogCursor(java.time.Instant.parse(l.ts), l.id.value)),
                )
          } yield Response.json(QueryLogPage(visible, nextCur).toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      // #847: aggregated connection-event series. bucket+groupBy required; only
      // domain grouping is implemented (apex needs PSL #849, app needs apps
      // track #761-#769). Filters mirror /api/logs.
      Method.GET / "api" / "connection-events" / "series" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireAuth(req, auth).mapError(ApiError.Wrapped(_))
            // Aggregated rows don't carry per-row profile_id, so we can't
            // post-filter the way /api/logs does. Restrict to admin/adult;
            // children can still use /api/logs raw view with a profileId
            // filter that goes through filterLogs.
            _      <- ZIO
              .fail(ApiError.Forbidden("aggregated view requires admin or adult role"))
              .when(claims.role != "admin" && claims.role != "adult")
            bktStr <- ZIO
              .fromOption(req.url.queryParam("bucket"))
              .orElseFail(ApiError.BadRequest("bucket query parameter required"))
            bucket <- ZIO
              .fromOption(ConnectionEventBucket.fromWire(bktStr))
              .orElseFail(ApiError.BadRequest(s"unknown bucket: $bktStr"))
            _      <- ZIO
              .fail(ApiError.BadRequest("bucket=off not supported on /series — use /api/logs"))
              .when(bucket == ConnectionEventBucket.Off)
            // #917: groupBy accepts repeated params (?groupBy=host&groupBy=device).
            // For backwards-compat each value is also comma-split. Empty/absent
            // is now valid — yields one row per window. Apex/App still rejected
            // with typed errors (#856 PSL, #857 apps track).
            grpSet <- ZIO
              .foreach(
                req.url.queryParams
                  .getAll("groupBy")
                  .toList
                  .flatMap(_.split(',').toList)
                  .map(_.trim)
                  .filter(_.nonEmpty),
              ) { s =>
                ZIO
                  .fromOption(ConnectionEventGroupBy.fromWire(s))
                  .orElseFail(ApiError.BadRequest(s"unknown groupBy: $s"))
              }
              .map(_.toSet)
            _      <- ZIO
              .fail(ApiError.BadRequest("groupBy=apex not implemented — see #856 (needs PSL)"))
              .when(grpSet.exists(g => g.wire == "apex"))
            // #769: groupBy=app is now implemented (joins through app_hosts).
            groupByCodes = grpSet.map(_.wire)
            deviceIds  <- parseMultiDeviceIdParam(req).mapError(ApiError.Wrapped(_))
            profileIds <- parseMultiProfileIdParam(req).mapError(ApiError.Wrapped(_))
            untilOpt   <- parseInstantOpt(req, "until")
            cursorOpt  <- parseAggCursor(req)
            limit      <- parseLimit(req, default = 500, max = 500)
            filter = LogFilter(
              macs = parseMultiValueParam(req, "mac"),
              deviceIds = deviceIds,
              profileIds = profileIds,
              blocked = req.url.queryParam("blocked").map(_ == "true"),
              domain = req.url.queryParam("domain"),
              location = req.url.queryParam("location"),
              hours = req.url.queryParam("hours").flatMap(_.toIntOption).getOrElse(24),
              limit = limit,
              until = untilOpt,
              cursorWs = cursorOpt.map(_.ws),
              cursorKey = cursorOpt.map(_.key),
              includeMulticast = req.url.queryParam("includeMulticast").contains("true"),
            )
            // #1265: route coarse + wide reads to the rollup tables; fine,
            // short, or multicast-inclusive reads stay on raw connection_events.
            rows <- (seriesGrain(bucket, filter.hours, filter.includeMulticast) match {
              case None    => connRepo.querySeries(filter, bucket.seconds, groupByCodes)
              case Some(g) => connRepo.querySeriesRollup(filter, bucket.seconds, groupByCodes, g)
            }).mapError(ApiError.Db(_))
            nextCur =
              if (rows.size < limit) None
              else
                rows.lastOption.map(r =>
                  Cursor.encode(Cursor.AggCursor(r.windowStart, aggGroupKey(r))),
                )
          } yield Response.json(ConnectionEventSeriesPage(rows, nextCur).toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.GET / "api" / "stats"                        ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] =
            requireAdmin(req, auth).mapError(ApiError.Wrapped(_)) *>
              connRepo.stats
                .map(s => Response.json(s.toJson))
                .mapError(ApiError.Db(_))
          handle.mapError(ErrorMapper.errorToResponse)
        },
    )
}

// ── Blocklist routes ───────────────────────────────────────────────────────

object BlocklistRoutes {
  def routes(
      auth: AuthService,
      blRepo: BlocklistRepo,
      cache: wifihaven.api.BlocklistCache,
      fetcher: wifihaven.api.BlocklistFetcher,
      bundled: Map[BlocklistId, wifihaven.api.BundledBlocklist],
  ): Routes[Any, Response] =
    Routes(
      // #958: list every category with display metadata + host count for
      // the SPA management page. Returns BlocklistSummary[] in declared
      // order (by id).
      Method.GET / "api" / "blocklists"                                 ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] =
            requireAdmin(req, auth).mapError(ApiError.Wrapped(_)) *>
              blRepo.summaries
                .map(rs => Response.json(rs.toJson))
                .mapError(ApiError.Db(_))
          handle.mapError(ErrorMapper.errorToResponse)
        },
      // #958: paginated host list for the "View hosts" disclosure on the
      // SPA page. Returns a JSON object `{ id, hosts: [...] }`. Admin-
      // only; routers use the unrelated GET /api/blocklists/<id> route
      // (RouterRoutes) which returns the plain-text list with ETag.
      Method.GET / "api" / "blocklists" / string("id") / "hosts"        ->
        handler { (id: String, req: Request) =>
          val handle: ZIO[Any, ApiError, Response] =
            requireAdmin(req, auth).mapError(ApiError.Wrapped(_)) *>
              ZIO
                .fromEither(BlocklistId.parse(id))
                .mapError(ApiError.BadRequest(_))
                .flatMap(bid =>
                  blRepo
                    .loadCategory(bid)
                    .map(hs =>
                      Response.json(
                        s"""{"id":${bid.value.toJson},"hosts":${hs.toList.sorted
                            .map(_.value)
                            .toJson}}""",
                      ),
                    )
                    .mapError(ApiError.Db(_)),
                )
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.POST / "api" / "blocklists" / string("category") / "clear" ->
        handler { (cat: String, req: Request) =>
          val handle: ZIO[Any, ApiError, Response] =
            requireAdmin(req, auth).mapError(ApiError.Wrapped(_)) *>
              blRepo.clearCategory(BlocklistId.unsafe(cat)).mapError(ApiError.Db(_)) *>
              ZIO.succeed(Response.ok)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      // #958: trigger an out-of-band re-fetch + re-seed of a bundled list. Returns
      // 200 {refreshedHosts:N} on success, 404 if the id isn't a bundled list, or
      // 502 if the upstream fetch failed (existing DB rows are kept).
      Method.POST / "api" / "blocklists" / string("id") / "refresh"     ->
        handler { (id: String, req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            _   <- requireAdmin(req, auth).mapError(ApiError.Wrapped(_))
            bid <- ZIO.fromEither(BlocklistId.parse(id)).mapError(ApiError.BadRequest(_))
            b   <- ZIO
              .fromOption(bundled.get(bid))
              .orElseFail(
                // 404 + structured JSON body preserved verbatim (the SPA parses it) via Wrapped.
                ApiError.Wrapped(
                  Response
                    .status(Status.NotFound)
                    .copy(body =
                      Body.fromString(s"""{"error":"unknown bundled blocklist '$id'"}"""),
                    ),
                ),
              )
            n   <- wifihaven.api.BundledBlocklists
              .refresh(blRepo, cache, fetcher, b)
              .mapError(ApiError.Db(_))
          } yield n match {
            case Some(count) => Response.json(s"""{"refreshedHosts":$count}""")
            case None        =>
              Response
                .status(Status.BadGateway)
                .copy(body =
                  Body.fromString("""{"error":"upstream fetch failed; rows unchanged"}"""),
                )
          }
          handle.mapError(ErrorMapper.errorToResponse)
        },
    )
}

// ── Household settings (#334) ──────────────────────────────────────────────

object HouseholdSettingsRoutes {
  def routes(
      auth: AuthService,
      repo: HouseholdSettingsRepo,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "household" / "settings"   ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            _ <- requireAuth(req, auth).mapError(ApiError.Wrapped(_))
            s <- repo.get.mapError(ApiError.Db(_))
          } yield Response.json(s.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.PUT / "api" / "household" / "settings"   ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            _    <- requireAdmin(req, auth).mapError(ApiError.Wrapped(_))
            body <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            upd  <- ZIO
              .fromEither(body.fromJson[UpdateHouseholdSettingsRequest])
              .mapError(ApiError.DecodeFailure(_))
            _    <- ZIO
              .fromEither(validateUnmanagedMacPolicy(upd.unmanagedMacPolicy))
              .mapError(ApiError.BadRequest(_))
            _    <- repo
              .update(
                HouseholdSettings(
                  upd.dailyResetTime,
                  upd.dailyResetTz,
                  upd.heartbeatFilter,
                  upd.unmanagedMacPolicy,
                  upd.presenceContinuationSeconds,
                ),
              )
              .mapError(ApiError.Db(_))
          } yield Response.ok
          handle.mapError(ErrorMapper.errorToResponse)
        },
      // #998: field-scoped partial update. Body is a subset of HouseholdSettings.
      // Top-level fields (dailyResetTime, dailyResetTz) follow standard PATCH
      // semantics. `heartbeatFilter` is deep-merged when present so the SPA can
      // autosave a single inner toggle without resending the threshold and
      // patterns alongside it.
      Method.PATCH / "api" / "household" / "settings" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            _         <- requireAdmin(req, auth).mapError(ApiError.Wrapped(_))
            existing  <- repo.get.mapError(ApiError.Db(_))
            body      <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            obj       <- ZIO.fromEither(FieldPatch.parseObj(body)).mapError(ApiError.BadRequest(_))
            timePatch <- ZIO
              .fromEither(FieldPatch.from[LocalTime](obj, "dailyResetTime"))
              .mapError(ApiError.BadRequest(_))
            tzPatch   <- ZIO
              .fromEither(FieldPatch.from[ZoneId](obj, "dailyResetTz"))
              .mapError(ApiError.BadRequest(_))
            _         <- timePatch match {
              case FieldPatch.Cleared =>
                ZIO.fail(ApiError.BadRequest("dailyResetTime cannot be cleared"))
              case _                  => ZIO.unit
            }
            _         <- tzPatch match {
              case FieldPatch.Cleared =>
                ZIO.fail(ApiError.BadRequest("dailyResetTz cannot be cleared"))
              case _                  => ZIO.unit
            }
            mergedFilter <- obj.get("heartbeatFilter") match {
              case None              => ZIO.succeed(existing.heartbeatFilter)
              case Some(Json.Null)   =>
                ZIO.fail(ApiError.BadRequest("heartbeatFilter cannot be cleared"))
              case Some(j: Json.Obj) => mergeHeartbeatFilter(existing.heartbeatFilter, j)
              case Some(_)           =>
                ZIO.fail(ApiError.BadRequest("heartbeatFilter must be a JSON object"))
            }
            mergedUmm    <- obj.get("unmanagedMacPolicy") match {
              case None              => ZIO.succeed(existing.unmanagedMacPolicy)
              case Some(Json.Null)   =>
                ZIO.fail(ApiError.BadRequest("unmanagedMacPolicy cannot be cleared"))
              case Some(j: Json.Obj) => mergeUnmanagedMacPolicy(existing.unmanagedMacPolicy, j)
              case Some(_)           =>
                ZIO.fail(ApiError.BadRequest("unmanagedMacPolicy must be a JSON object"))
            }
            // #1464: presence_continuation_seconds is an API/config-side rollup
            // knob with no SPA surface, so PATCH preserves the stored value
            // rather than exposing it as a patchable field.
            merged = HouseholdSettings(
              dailyResetTime = timePatch.applyTo(existing.dailyResetTime),
              dailyResetTz = tzPatch.applyTo(existing.dailyResetTz),
              heartbeatFilter = mergedFilter,
              unmanagedMacPolicy = mergedUmm,
              presenceContinuationSeconds = existing.presenceContinuationSeconds,
            )
            _            <- repo.update(merged).mapError(ApiError.Db(_))
          } yield Response.ok
          handle.mapError(ErrorMapper.errorToResponse)
        },
    )

  // #1525: `heartbeatHostPatterns` is retired — host-identity suppression now lives in the
  // canonical `shared.types.InfraHosts` code constant. The field stays on the wire for back-compat
  // input tolerance (older SPA builds still send it); we accept any value (including `null`) and
  // discard it without erroring.
  private def mergeHeartbeatFilter(
      existing: HeartbeatFilter,
      obj: Json.Obj,
  ): IO[ApiError, HeartbeatFilter] =
    for {
      enabledP <- ZIO
        .fromEither(FieldPatch.from[Boolean](obj, "enabled"))
        .mapError(ApiError.BadRequest(_))
      bytesP   <- ZIO
        .fromEither(FieldPatch.from[Int](obj, "bytesThreshold"))
        .mapError(ApiError.BadRequest(_))
      _        <- (enabledP, bytesP) match {
        case (FieldPatch.Cleared, _) =>
          ZIO.fail(ApiError.BadRequest("heartbeatFilter.enabled cannot be cleared"))
        case (_, FieldPatch.Cleared) =>
          ZIO.fail(ApiError.BadRequest("heartbeatFilter.bytesThreshold cannot be cleared"))
        case _                       => ZIO.unit
      }
    } yield HeartbeatFilter(
      enabled = enabledP.applyTo(existing.enabled),
      bytesThreshold = bytesP.applyTo(existing.bytesThreshold),
      heartbeatHostPatterns = Nil,
    )

  private def mergeUnmanagedMacPolicy(
      existing: UnmanagedMacPolicy,
      obj: Json.Obj,
  ): IO[ApiError, UnmanagedMacPolicy] =
    for {
      policyP    <- ZIO
        .fromEither(FieldPatch.from[String](obj, "policy"))
        .mapError(ApiError.BadRequest(_))
      blockPageP <- ZIO
        .fromEither(FieldPatch.from[Boolean](obj, "blockPage"))
        .mapError(ApiError.BadRequest(_))
      _          <- (policyP, blockPageP) match {
        case (FieldPatch.Cleared, _) =>
          ZIO.fail(ApiError.BadRequest("unmanagedMacPolicy.policy cannot be cleared"))
        case (_, FieldPatch.Cleared) =>
          ZIO.fail(ApiError.BadRequest("unmanagedMacPolicy.blockPage cannot be cleared"))
        case _                       => ZIO.unit
      }
      merged = UnmanagedMacPolicy(
        policy = policyP.applyTo(existing.policy),
        blockPage = blockPageP.applyTo(existing.blockPage),
      )
      _ <- ZIO.fromEither(validateUnmanagedMacPolicy(merged)).mapError(ApiError.BadRequest(_))
    } yield merged
}

private def validateUnmanagedMacPolicy(p: UnmanagedMacPolicy): Either[String, Unit] =
  if (UnmanagedMacPolicy.ValidPolicies.contains(p.policy)) Right(())
  else
    Left(
      s"unmanagedMacPolicy.policy must be one of ${UnmanagedMacPolicy.ValidPolicies
          .mkString(", ")}; got '${p.policy}'",
    )

// ── Helpers ────────────────────────────────────────────────────────────────

private def bearerToken(req: Request): Option[String] =
  req.header(Header.Authorization).flatMap { h =>
    val v = h.renderedValue
    if v.startsWith("Bearer ") then Some(v.drop(7)) else None
  }

// 403 body emitted when must_change_password is set (#586).
private val passwordChangeRequiredResponse: Response =
  Response
    .json("""{"error":"password_change_required"}""")
    .status(Status.Forbidden)

/**
 * Verify auth token only — does NOT check must_change_password. Used exclusively by POST
 * /api/auth/change-password so that the flag doesn't block the one route that can clear it.
 */
def requireAuthSkipPwCheck(req: Request, auth: AuthService): IO[Response, JwtClaims] =
  ZIO
    .fromOption(bearerToken(req))
    .orElseFail(Response.unauthorized("Missing token"))
    .flatMap(t =>
      auth
        .verify(t)
        .mapError {
          case AuthError.TokenExpired => Response.unauthorized("Token expired")
          case _                      => Response.unauthorized("Invalid token")
        },
    )

/**
 * Verify auth token AND enforce that must_change_password is false. Returns 403
 * {"error":"password_change_required"} if the flag is set (#586). All authenticated routes except
 * change-password use this.
 */
def requireAuth(req: Request, auth: AuthService): IO[Response, JwtClaims] =
  ZIO
    .fromOption(bearerToken(req))
    .orElseFail(Response.unauthorized("Missing token"))
    .flatMap(t =>
      auth
        .requirePasswordChanged(t)
        .mapError {
          case AuthError.Forbidden    => passwordChangeRequiredResponse
          case AuthError.TokenExpired => Response.unauthorized("Token expired")
          case _                      => Response.unauthorized("Invalid token")
        },
    )

def requireAdmin(req: Request, auth: AuthService): IO[Response, JwtClaims] =
  // requireAuth already enforces must_change_password; then we check role.
  requireAuth(req, auth).flatMap { claims =>
    if claims.role == "admin" then ZIO.succeed(claims)
    else ZIO.fail(Response.forbidden("Admin required"))
  }

def requireWriter(req: Request, auth: AuthService): IO[Response, JwtClaims] =
  // requireAuth already enforces must_change_password; then we check role.
  requireAuth(req, auth).flatMap { claims =>
    if claims.role == "admin" || claims.role == "adult" then ZIO.succeed(claims)
    else ZIO.fail(Response.forbidden("Adult or admin required"))
  }

/** Admin and adult see all profiles. Child only sees profiles linked to their user. */
def visibleProfiles(
    claims: JwtClaims,
    all: List[Profile],
    upRepo: UserProfileRepo,
): IO[Response, List[Profile]] =
  if claims.role == "admin" || claims.role == "adult" then ZIO.succeed(all)
  else
    upRepo
      .listProfilesForUsername(claims.sub)
      .mapError(ErrorMapper.dbErrorToResponse)
      .map(pids => all.filter(p => pids.contains(p.id)))

def filterDevices(
    claims: JwtClaims,
    all: List[Device],
    upRepo: UserProfileRepo,
): IO[Response, List[Device]] =
  if claims.role == "admin" || claims.role == "adult" then ZIO.succeed(all)
  else
    upRepo
      .listProfilesForUsername(claims.sub)
      .mapError(ErrorMapper.dbErrorToResponse)
      .map(pids => all.filter(d => d.profileId.exists(pids.contains)))

def filterLogs(
    claims: JwtClaims,
    all: List[QueryLog],
    upRepo: UserProfileRepo,
): IO[Response, List[QueryLog]] =
  if claims.role == "admin" || claims.role == "adult" then ZIO.succeed(all)
  else
    upRepo
      .listProfilesForUsername(claims.sub)
      .mapError(ErrorMapper.dbErrorToResponse)
      .map(pids => all.filter(l => l.profileId.exists(pids.contains)))

/** Allow read access if admin or adult (full visibility); child must be linked to the profile. */
def requireProfileReadAccess(
    claims: JwtClaims,
    profileId: ProfileId,
    upRepo: UserProfileRepo,
): IO[Response, Unit] =
  if claims.role == "admin" || claims.role == "adult" then ZIO.succeed(())
  else
    upRepo
      .listProfilesForUsername(claims.sub)
      .mapError(ErrorMapper.dbErrorToResponse)
      .flatMap { pids =>
        if pids.contains(profileId) then ZIO.succeed(())
        else ZIO.fail(Response.forbidden("Not authorized for this profile"))
      }

def requireProfileReadAccess(
    claims: JwtClaims,
    profileId: Option[ProfileId],
    upRepo: UserProfileRepo,
): IO[Response, Unit] =
  profileId match {
    case None      =>
      if claims.role == "admin" || claims.role == "adult" then ZIO.succeed(())
      else ZIO.fail(Response.forbidden("Device has no assigned profile"))
    case Some(pid) => requireProfileReadAccess(claims, pid, upRepo)
  }

/** Allow write access if admin or adult linked to the profile. Child is always denied. */
def requireProfileAccess(
    claims: JwtClaims,
    profileId: ProfileId,
    upRepo: UserProfileRepo,
): IO[Response, Unit] =
  if claims.role == "admin" then ZIO.succeed(())
  else
    upRepo
      .listProfilesForUsername(claims.sub)
      .mapError(ErrorMapper.dbErrorToResponse)
      .flatMap { pids =>
        if pids.contains(profileId) then ZIO.succeed(())
        else ZIO.fail(Response.forbidden("Not authorized for this profile"))
      }

def requireProfileAccess(
    claims: JwtClaims,
    profileId: Option[ProfileId],
    upRepo: UserProfileRepo,
): IO[Response, Unit] =
  profileId match {
    case None      =>
      if claims.role == "admin" then ZIO.succeed(())
      else ZIO.fail(Response.forbidden("Device has no assigned profile"))
    case Some(pid) => requireProfileAccess(claims, pid, upRepo)
  }

def normalizeMac(mac: String): String = {
  // Path-captured MACs arrive percent-encoded — the SPA builds the URL with
  // encodeURIComponent, which turns ':' into '%3A', and zio-http's string(_)
  // path codec does not decode it. Decode first so the colon form matches the
  // stored MAC; decoding a clean MAC (query- or body-sourced) is a no-op.
  val decoded =
    scala.util
      .Try(java.net.URLDecoder.decode(mac, java.nio.charset.StandardCharsets.UTF_8))
      .getOrElse(mac)
  decoded.toLowerCase.replace("-", ":").trim
}

// #795: parse a ?profileId=N query parameter, used by the per-profile-scoped
// hot read endpoints. Returns None when the param is absent (callers fall back
// to "all visible profiles"); returns a 400 when present but unparseable.
def parseProfileIdParam(req: Request): IO[Response, Option[ProfileId]] =
  req.url.queryParam("profileId") match {
    case None    => ZIO.succeed(None)
    case Some(s) =>
      s.toLongOption
        .map(l => ZIO.succeed(Some(ProfileId(l))))
        .getOrElse(ZIO.fail(Response.badRequest(s"invalid profileId: $s")))
  }

// #865: comma-separated multi-value query params. Old single-value URLs
// ("profileId=2") parse as a one-element list, so previously-shared links
// keep working. Absent param → empty list (no filter).
def parseMultiValueParam(req: Request, name: String): List[String] =
  req.url.queryParam(name) match {
    case None    => Nil
    case Some(s) => s.split(',').toList.map(_.trim).filter(_.nonEmpty)
  }

def parseMultiProfileIdParam(req: Request): IO[Response, List[ProfileId]] =
  ZIO.foreach(parseMultiValueParam(req, "profileId")) { s =>
    ZIO
      .fromOption(s.toLongOption.map(ProfileId(_)))
      .orElseFail(Response.badRequest(s"invalid profileId: $s"))
  }

def parseMultiDeviceIdParam(req: Request): IO[Response, List[DeviceId]] =
  ZIO.foreach(parseMultiValueParam(req, "deviceId")) { s =>
    ZIO
      .fromOption(s.toLongOption.map(DeviceId(_)))
      .orElseFail(Response.badRequest(s"invalid deviceId: $s"))
  }
