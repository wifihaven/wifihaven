package wifihaven.api.routes

import wifihaven.api.auth.*
import wifihaven.api.cache.TimeStatusCache
import wifihaven.api.db.*
import wifihaven.api.observability.LogContext
import wifihaven.shared.*
import wifihaven.shared.types.*
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*

import java.time.{LocalDate, LocalTime, ZoneId}

import zio.json.ast.Json

// ── Auth routes ────────────────────────────────────────────────────────────

// #1570: handlers fail with a typed [[ApiError]] mapped centrally by
// [[ErrorMapper.errorToResponse]]; the [[wifihaven.api.ErrorBoundary]] logs (4xx WARN / 5xx ERROR) +
// meters each error. Every case reproduces the EXACT status + body the hand-rolled code produced —
// DB failures stay 503 via [[ApiError.Db]]; the `password_change_required` 403 JSON and the
// `{status,db}` 503 auth-failure bodies are preserved verbatim (the latter via
// [[ApiError.Wrapped]] of `ErrorMapper.dbUnavailable`, since the label is a static string).
object AuthRoutes {
  def routes(
      auth: AuthService,
      userRepo: UserRepo,
      userProfileRepo: UserProfileRepo,
      loginRateLimiter: RateLimiter,
  ): Routes[Any, Response] =
    Routes(
      Method.POST / "api" / "auth" / "login"                 ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            // #2079: per-source-IP rate limit ahead of any DB/bcrypt work — online
            // brute-force / credential-stuffing defense on the one internet-facing,
            // unauthenticated, single-known-account login route.
            allowed <- loginRateLimiter.tryAcquire(s"login:${clientIp(req)}")
            _       <- ZIO
              .fail(ApiError.RateLimited("Too many login attempts; try again later"))
              .unless(allowed)
            body    <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            lr      <- ZIO
              .fromEither(body.fromJson[LoginRequest])
              .mapError(ApiError.DecodeFailure(_))
            // #2164: pass the single identifier through; AuthService resolves it to a household by
            // its syntax (email '@' / slug/username '/' / bare → default). `loginIdentifier` prefers
            // the new `identifier` field, falling back to the legacy `username` alias. Any unresolved
            // form fails identically to a bad password, so nothing distinguishes it here.
            resp    <- auth
              .login(lr.loginIdentifier, lr.password)
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
            claims <- requireAuthSkipPwCheck(req, auth)
            body   <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            cpr    <- ZIO
              .fromEither(body.fromJson[ChangePasswordRequest])
              .mapError(ApiError.DecodeFailure(_))
            // #2084: minimum password length — previously any non-empty string was accepted.
            _      <- ZIO
              .fail(
                ApiError.BadRequest(
                  s"password must be at least ${AuthService.MinPasswordLength} characters",
                ),
              )
              .when(!AuthService.isPasswordStrongEnough(cpr.newPassword))
            // #2140: scope the password change to the caller's own household (usernames are unique
            // per household only) — the authenticated token already carries it in `claims.hh`.
            _      <- auth
              .changePassword(claims.sub, cpr.currentPassword, cpr.newPassword, claims.hh)
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
            claims <- requireAuth(req, auth)
            pids   <- userProfileRepo
              .listProfilesForUsername(claims.sub)
              .mapError(ApiError.Db(_))
          } yield Response.json(
            MeResponse(
              claims.sub,
              UserRole.parse(claims.role).getOrElse(UserRole.Child),
              pids,
              // #2133: the operator gate (design §3.2) is admin AND household 1 —
              // the same predicate `requireOperator` enforces server-side. The SPA
              // reads this to show/hide the beta-request queue.
              isOperator =
                claims.role == "admin" && claims.hh == wifihaven.shared.types.HouseholdId.Default,
            ).toJson,
          )
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.POST / "api" / "users"                          ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireAdmin(req, auth)
            body   <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            cur    <- ZIO
              .fromEither(body.fromJson[CreateUserRequest])
              .mapError(ApiError.DecodeFailure(_))
            // #2084: minimum password length — previously any non-empty string was accepted.
            _      <- ZIO
              .fail(
                ApiError.BadRequest(
                  s"password must be at least ${AuthService.MinPasswordLength} characters",
                ),
              )
              .when(!AuthService.isPasswordStrongEnough(cur.password))
            hash   <- auth.hashPassword(cur.password)
            // #2130: the new user lands in the CREATING admin's household, not
            // V65's DEFAULT 1 — a hh-B admin must never plant rows in hh-A.
            id     <- userRepo
              .create(cur.username, hash, UserRole.asString(cur.role), claims.hh)
              .mapError(ApiError.Db(_))
            _      <- userProfileRepo
              .setProfilesForUser(id, cur.profileIds)
              .mapError(ApiError.Db(_))
          } yield Response.json(s"""{"id":$id}""")
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.GET / "api" / "users"                           ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims   <- requireAdmin(req, auth)
            // #2108: an admin enumerates only their own household's users (design §2 gap 4).
            users    <- userRepo.listAllForHousehold(claims.hh).mapError(ApiError.Db(_))
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
            _    <- requireAdmin(req, auth)
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
            requireAdmin(req, auth) *>
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
            _    <- requireAdmin(req, auth)
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
  // #423 — the set of writable Profile fields the PATCH handler recognizes.
  // Kept as a single named source so the PATCH log message can filter to
  // recognized keys, AND so the test pin in ProfilePatchApiSpec can assert
  // it covers every field on the underlying `Profile` case class minus `id`.
  // If you add a writable field to `Profile`, update this set and PATCH's
  // `p.copy(...)` together — the test pin will fail loudly otherwise.
  val PatchableKeys: Set[String] = Set(
    "name",
    "blockedCategories",
    "paused",
    "failureMode",
    "blockIpOnly",
    "crossDeviceOverlapMode",
    "pauseMode",
    "defaultDeny",
    "timeLimit",
  )

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
      // #1849: drop the computed-snapshot cache when this profile's policy changes (pause, schedule
      // attach, default-deny, time limit, categories, create/delete) so the change propagates to the
      // fleet at once rather than waiting for the reconcile ticker. Defaulted to a no-op so test call
      // sites keep their arity; production passes `policy.invalidate`.
      invalidateSnapshot: UIO[Unit] = ZIO.unit,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "profiles"                            ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims      <- requireAuth(req, auth)
            // #2108: household-scoped list — an admin/adult sees every profile IN THEIR HOUSEHOLD,
            // never across (design §2 gaps 3+4). `visibleProfiles` then narrows by role within it.
            allProfiles <- profileRepo.listAllForHousehold(claims.hh).mapError(ApiError.Db(_))
            visible     <- visibleProfiles(claims, allProfiles, userProfileRepo)
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
      // #1773: surface the global sentinel profile to the SPA. `GET /api/profiles` hides it (the
      // RoleAccessSpec invariant from #1771), so this is the SPA's one window into it for editing
      // its app-policy assignments / categories / defaultDeny via the per-profile editor preset to
      // the sentinel's id. Route literal MUST come before the `/profiles/long("id")` matcher so
      // "global" doesn't get path-parsed into a Long. Admin-only because only admins author policy.
      Method.GET / "api" / "profiles" / "global"                 ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims  <- requireAdmin(req, auth)
            // #2108: the sentinel is per-household (the global-policy layer is per-household). An
            // admin reads only their own household's sentinel.
            p       <- profileRepo
              .getGlobalForHousehold(claims.hh)
              .mapError(ApiError.Db(_))
              .flatMap(
                ZIO.fromOption(_).orElseFail(ApiError.NotFound("Global profile not seeded")),
              )
            tl      <- timeLimitRepo.findForProfile(p.id).mapError(ApiError.Db(_))
            schedId <- namedScheduleRepo
              .blockScheduleIdsForProfile(p.id)
              .mapError(ApiError.Db(_))
          } yield Response.json(ProfileDetail(p, tl, schedId).toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.GET / "api" / "profiles" / long("id")               ->
        handler { (id: Long, req: Request) =>
          val pid                                  = ProfileId(id)
          val handle: ZIO[Any, ApiError, Response] = for {
            claims  <- requireAuth(req, auth)
            _       <- requireProfileReadAccess(claims, pid, userProfileRepo, profileRepo)
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
            claims <- requireWriter(req, auth)
            _      <- requireProfileAccess(claims, pid, userProfileRepo, profileRepo)
            // #1771: schedules are meaningless on the global sentinel — block here before any DB work.
            _      <- requireNotGlobalProfile(profileRepo, pid, "schedules")
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
            // #1849: the attached schedule set is snapshot content, so drop the computed-snapshot
            // cache too.
            _      <- invalidateSnapshot
          } yield Response.ok
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.POST / "api" / "profiles"                           ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireAdmin(req, auth)
            body   <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            upr    <- ZIO
              .fromEither(body.fromJson[UpsertProfileRequest])
              .mapError(ApiError.DecodeFailure(_))
            // #2130: the new profile lands in the CREATING admin's household,
            // not V65's DEFAULT 1.
            id     <- profileRepo
              .create(upr.name, upr.blockedCategories, claims.hh)
              .mapError(ApiError.Db(_))
            _      <- profileRepo
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
            _      <- ZIO
              .foreachDiscard(upr.timeLimit)(mins => timeLimitRepo.upsert(id, mins))
              .mapError(ApiError.Db(_))
            // #1849: a new profile changes the snapshot's profile set.
            _      <- invalidateSnapshot
          } yield Response.json(s"""{"id":${id.value}}""")
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.PUT / "api" / "profiles" / long("id")               ->
        handler { (id: Long, req: Request) =>
          val pid                                  = ProfileId(id)
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireWriter(req, auth)
            _      <- requireProfileAccess(claims, pid, userProfileRepo, profileRepo)
            body   <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            upr    <- ZIO
              .fromEither(body.fromJson[UpsertProfileRequest])
              .mapError(ApiError.DecodeFailure(_))
            // #1771: full-replace PUT touches paused / timeLimit / pauseMode — none of which apply
            // to the global sentinel. Reject before any DB work so a misdirected admin tab can't
            // even partially write.
            _      <- requireNotGlobalProfile(profileRepo, pid, "profile (PUT)")
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
            // #1849: a full-replace PUT can move paused / categories / failureMode / blockIpOnly /
            // defaultDeny / timeLimit — all snapshot content.
            _      <- invalidateSnapshot
          } yield Response.ok
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.DELETE / "api" / "profiles" / long("id")            ->
        handler { (id: Long, req: Request) =>
          val pid                                  = ProfileId(id)
          val handle: ZIO[Any, ApiError, Response] =
            requireAdmin(req, auth) *>
              // #1771: the global sentinel is a wire-shape fixture, not an authored profile —
              // never deletable. The partial unique index would let admin recreate it via SQL,
              // but the snapshot would briefly miss the household-wide allow/block lists.
              requireNotGlobalProfile(profileRepo, pid, "profile (DELETE)") *>
              profileRepo.delete(pid).mapError(ApiError.Db(_)) *>
              // #1849: a deleted profile drops out of the snapshot's profile set.
              invalidateSnapshot *>
              ZIO.succeed(Response.ok)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      // #423: field-scoped partial update. Body is a subset of the writable
      // Profile shape — every field optional, omitted fields preserve their
      // current value. `timeLimit` is the only nullable field and accepts
      // explicit null to clear; non-nullable fields reject null with 400.
      //
      // PER-FIELD RACE SAFETY — the whole point of #423. Each present field
      // dispatches to a *targeted* repo setter (`profileRepo.setName`,
      // `setPaused`, …) — NOT a full-row `profileRepo.update(p.copy(...))`.
      // Two concurrent PATCHes touching disjoint fields therefore preserve
      // both edits instead of the second-writer clobbering the first via a
      // load → modify → write-all-columns trip. The Users PATCH already
      // uses this dispatch shape; the Devices/Apps PATCHes still do not and
      // have the same regression — tracked in follow-up issues.
      //
      // Schedules / users still live on their own sub-routes by design
      // (#1494, #406) and are not patchable here.
      Method.PATCH / "api" / "profiles" / long("id")             ->
        handler { (id: Long, req: Request) =>
          val pid                                  = ProfileId(id)
          val handle: ZIO[Any, ApiError, Response] = for {
            claims    <- requireWriter(req, auth)
            _         <- requireProfileAccess(claims, pid, userProfileRepo, profileRepo)
            // Existence check up front so a missing row 404s instead of
            // letting per-field UPDATEs silently no-op. We deliberately do
            // NOT carry the loaded row's mutable columns into the writes below: every SET is
            // column-scoped, so there's no full-row copy to drift. `isGlobal` is the one
            // structural flag we DO carry — it's immutable for the row's lifetime (#1771), so
            // reading it once and reusing below avoids a second findById.
            existing  <- profileRepo
              .findById(pid)
              .mapError(ApiError.Db(_))
              .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("Profile not found")))
            body      <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            obj       <- ZIO.fromEither(FieldPatch.parseObj(body)).mapError(ApiError.BadRequest(_))
            namePatch <- ZIO
              .fromEither(FieldPatch.from[String](obj, "name"))
              .mapError(ApiError.BadRequest(_))
            blockedCategoriesPatch  <- ZIO
              .fromEither(FieldPatch.from[List[BlocklistId]](obj, "blockedCategories"))
              .mapError(ApiError.BadRequest(_))
            pausedPatch             <- ZIO
              .fromEither(FieldPatch.from[Boolean](obj, "paused"))
              .mapError(ApiError.BadRequest(_))
            failureModePatch        <- ZIO
              .fromEither(FieldPatch.from[FailureMode](obj, "failureMode"))
              .mapError(ApiError.BadRequest(_))
            blockIpOnlyPatch        <- ZIO
              .fromEither(FieldPatch.from[Boolean](obj, "blockIpOnly"))
              .mapError(ApiError.BadRequest(_))
            crossDeviceOverlapPatch <- ZIO
              .fromEither(FieldPatch.from[CrossDeviceOverlapMode](obj, "crossDeviceOverlapMode"))
              .mapError(ApiError.BadRequest(_))
            pauseModePatch          <- ZIO
              .fromEither(FieldPatch.from[PauseMode](obj, "pauseMode"))
              .mapError(ApiError.BadRequest(_))
            defaultDenyPatch        <- ZIO
              .fromEither(FieldPatch.from[Boolean](obj, "defaultDeny"))
              .mapError(ApiError.BadRequest(_))
            timeLimitPatch          <- ZIO
              .fromEither(FieldPatch.from[Int](obj, "timeLimit"))
              .mapError(ApiError.BadRequest(_))
            // #1771: paused / pauseMode / timeLimit are meaningless household-wide. Reject the
            // PATCH if any of those are present AND the target is the global sentinel. Other
            // fields (name, blockedCategories, blockIpOnly, …) are fine on the sentinel.
            // Read `isGlobal` from the existing-row check above — no second findById.
            _                       <- ZIO.when(existing.isGlobal) {
              val offending = List(
                "paused"    -> (pausedPatch != FieldPatch.Absent),
                "pauseMode" -> (pauseModePatch != FieldPatch.Absent),
                "timeLimit" -> (timeLimitPatch != FieldPatch.Absent),
              ).collect { case (k, true) => k }
              ZIO.when(offending.nonEmpty)(
                ZIO.fail(
                  ApiError.BadRequest(
                    s"${offending.mkString(",")} cannot be set on the global profile (id=${pid.value})",
                  ),
                ),
              )
            }
            // Reject `field: null` for non-nullable fields — only `timeLimit`
            // accepts an explicit clear. Mirrors the device PATCH convention.
            _                       <- ZIO
              .foreachDiscard(
                List(
                  "name"                   -> namePatch,
                  "blockedCategories"      -> blockedCategoriesPatch,
                  "paused"                 -> pausedPatch,
                  "failureMode"            -> failureModePatch,
                  "blockIpOnly"            -> blockIpOnlyPatch,
                  "crossDeviceOverlapMode" -> crossDeviceOverlapPatch,
                  "pauseMode"              -> pauseModePatch,
                  "defaultDeny"            -> defaultDenyPatch,
                ),
              ) { case (k, fp) =>
                fp match {
                  case FieldPatch.Cleared => ZIO.fail(ApiError.BadRequest(s"$k cannot be cleared"))
                  case _                  => ZIO.unit
                }
              }
            // Per-field dispatch: Absent ⇒ no SQL; Set(v) ⇒ targeted SET.
            // No load-then-write-all-columns step ⇒ disjoint-field PATCH
            // races preserve both writers' edits.
            _                       <- namePatch match {
              case FieldPatch.Set(v) => profileRepo.setName(pid, v).mapError(ApiError.Db(_))
              case _                 => ZIO.unit
            }
            _                       <- blockedCategoriesPatch match {
              case FieldPatch.Set(v) =>
                profileRepo.setBlockedCategories(pid, v).mapError(ApiError.Db(_))
              case _                 => ZIO.unit
            }
            _                       <- pausedPatch match {
              case FieldPatch.Set(v) => profileRepo.setPaused(pid, v).mapError(ApiError.Db(_))
              case _                 => ZIO.unit
            }
            _                       <- failureModePatch match {
              case FieldPatch.Set(v) => profileRepo.setFailureMode(pid, v).mapError(ApiError.Db(_))
              case _                 => ZIO.unit
            }
            _                       <- blockIpOnlyPatch match {
              case FieldPatch.Set(v) => profileRepo.setBlockIpOnly(pid, v).mapError(ApiError.Db(_))
              case _                 => ZIO.unit
            }
            _                       <- crossDeviceOverlapPatch match {
              case FieldPatch.Set(v) =>
                profileRepo.setCrossDeviceOverlapMode(pid, v).mapError(ApiError.Db(_))
              case _                 => ZIO.unit
            }
            _                       <- pauseModePatch match {
              case FieldPatch.Set(v) => profileRepo.setPauseMode(pid, v).mapError(ApiError.Db(_))
              case _                 => ZIO.unit
            }
            _                       <- defaultDenyPatch match {
              case FieldPatch.Set(v) => profileRepo.setDefaultDeny(pid, v).mapError(ApiError.Db(_))
              case _                 => ZIO.unit
            }
            _                       <- timeLimitPatch match {
              case FieldPatch.Absent  => ZIO.unit
              case FieldPatch.Cleared => timeLimitRepo.delete(pid).mapError(ApiError.Db(_))
              case FieldPatch.Set(m)  => timeLimitRepo.upsert(pid, m).mapError(ApiError.Db(_))
            }
            // #1538: paused / timeLimit changes affect ProfileTimeStatus, so
            // bust the per-profile cache the same way schedule attach/detach
            // and /api/time/extend do. Trigger on any presence — a strict
            // "value actually changed" check would require reading the row
            // again post-write to compare, which would reintroduce the
            // load-then-act window this handler exists to avoid. An
            // over-invalidation just refills the cache: cheap and safe.
            _                       <- ZIO.when(
              pausedPatch != FieldPatch.Absent || timeLimitPatch != FieldPatch.Absent,
            )(cache.invalidateProfile(pid))
            // #1849: any recognized patch (pause, pauseMode, defaultDeny, timeLimit, name,
            // categories) is snapshot content, so drop the computed-snapshot cache so the change
            // reaches the fleet at once.
            _                       <- invalidateSnapshot
            // Log only recognized keys; unknown keys are ignored per the
            // backwards-compat rule and would mislead ops triage if echoed.
            recognizedKeys = obj.fields.map(_._1).filter(ProfileRoutes.PatchableKeys.contains)
            _ <- ZIO.logInfo(
              s"profile patched: id=${pid.value} keys=${recognizedKeys.mkString(",")}",
            )
          } yield Response.ok
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.GET / "api" / "profiles" / long("id") / "users"     ->
        handler { (id: Long, req: Request) =>
          val pid                                  = ProfileId(id)
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireAdmin(req, auth)
            // #2108: the profile must be in the admin's household, and users are enumerated scoped.
            _      <- requireProfileInHousehold(claims, pid, profileRepo)
            uids   <- userProfileRepo
              .listUsersForProfile(pid)
              .mapError(ApiError.Db(_))
            users  <- userRepo.listAllForHousehold(claims.hh).mapError(ApiError.Db(_))
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
            claims <- requireAdmin(req, auth)
            // #2108: an admin can only link users to a profile IN THEIR HOUSEHOLD.
            _      <- requireProfileInHousehold(claims, pid, profileRepo)
            body   <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            r      <- ZIO
              .fromEither(body.fromJson[SetProfileUsersRequest])
              .mapError(ApiError.DecodeFailure(_))
            _      <- userProfileRepo
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
      // #1771: profileRepo is consulted to reject device assignments to the global sentinel with
      // a 400 before any DB write. The repo-layer guard (`DeviceRepoLive.upsert`) is a defensive
      // backstop.
      profileRepo: ProfileRepo,
      // #1849: a device's profile assignment (or its existence) is snapshot content — drop the
      // computed-snapshot cache on upsert/delete/reassign so the change reaches the fleet at once.
      // Defaulted to a no-op so test call sites keep their arity; production passes
      // `policy.invalidate`.
      invalidateSnapshot: UIO[Unit] = ZIO.unit,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "devices"                    ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims  <- requireAuth(req, auth)
            // #2108: household-scoped list — filterDevices then narrows by role within it.
            all     <- deviceRepo.listAllForHousehold(claims.hh).mapError(ApiError.Db(_))
            visible <- filterDevices(claims, all, userProfileRepo)
          } yield Response.json(visible.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.PUT / "api" / "devices"                    ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireWriter(req, auth)
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
                requireProfileAccess(claims, pid, userProfileRepo, profileRepo) *>
                  // #1771: devices cannot be assigned to the global sentinel profile. Reject with
                  // 400 before any DB write. The repo-layer guard catches direct callers too.
                  requireNotGlobalProfile(profileRepo, pid, "device.profileId")
              case None      => ZIO.unit
            }
            // #2108: constructively keyed to the caller's household — a user device write lands
            // under `claims.hh` only (ON CONFLICT (household_id, mac)); it cannot address another
            // household's row.
            id <- deviceRepo
              .upsert(mac, udr.name, udr.profileId, "", claims.hh)
              .mapError(ApiError.Db(_))
            // #481: log device upsert so the next CI failure makes it obvious
            // whether the mutation reached the API at all.
            _  <- LogContext.annotate(LogContext.Mac, mac.value) {
              LogContext.annotateOpt(
                LogContext.ProfileId,
                udr.profileId.map(_.value.toString),
              ) {
                ZIO.logInfo(
                  s"device upserted: mac=${mac.value} profileId=${udr.profileId
                      .map(_.value.toString)
                      .getOrElse("-")} name=${udr.name}",
                )
              }
            }
            _  <- invalidateSnapshot
          } yield Response.json(s"""{"id":${id.value}}""")
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.DELETE / "api" / "devices" / string("mac") ->
        handler { (mac: String, req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireWriter(req, auth)
            normalized = MacAddress.unsafe(normalizeMac(mac))
            // #2108: household-scoped lookup — an hh-A admin gets a clean 404 for an hh-B MAC (§7
            // pin 2), never a cross-household delete.
            existing <- deviceRepo
              .findByMacInHousehold(normalized, claims.hh)
              .mapError(ApiError.Db(_))
              .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("Device not found")))
            _ <- requireProfileAccess(claims, existing.profileId, userProfileRepo, profileRepo)
            _ <- deviceRepo.delete(normalized).mapError(ApiError.Db(_))
            // #481: same rationale as PUT — make the next CI failure diagnostic.
            _ <- LogContext.annotate(LogContext.Mac, normalized.value)(
              ZIO.logInfo(s"device deleted: mac=${normalized.value}"),
            )
            _ <- invalidateSnapshot
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
            claims <- requireWriter(req, auth)
            normalized = MacAddress.unsafe(normalizeMac(mac))
            // #2108: household-scoped lookup — a cross-household MAC 404s before any write (§7 pin 2).
            existing <- deviceRepo
              .findByMacInHousehold(normalized, claims.hh)
              .mapError(ApiError.Db(_))
              .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("Device not found")))
            _    <- requireProfileAccess(claims, existing.profileId, userProfileRepo, profileRepo)
            body <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            obj  <- ZIO.fromEither(FieldPatch.parseObj(body)).mapError(ApiError.BadRequest(_))
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
                requireProfileAccess(claims, pid, userProfileRepo, profileRepo) *>
                  // #1771: same guard as PUT — reassigning a device to the global sentinel is
                  // rejected with 400.
                  requireNotGlobalProfile(profileRepo, pid, "device.profileId")
              case _                   => ZIO.unit
            }
            newName = namePatch.applyTo(existing.name)
            newPid = pidPatch.applyToNullable(existing.profileId)
            _ <- deviceRepo
              .upsert(normalized, newName, newPid, "", claims.hh)
              .mapError(ApiError.Db(_))
            _ <- LogContext.annotate(LogContext.Mac, normalized.value) {
              LogContext.annotateOpt(
                LogContext.ProfileId,
                newPid.map(_.value.toString),
              ) {
                ZIO.logInfo(
                  s"device patched: mac=${normalized.value} name=$newName profileId=${newPid.map(_.value.toString).getOrElse("-")}",
                )
              }
            }
            _ <- invalidateSnapshot
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
      // #1849: a +Time grant can flip a profile's daily-limit block off, which is snapshot content —
      // drop the computed-snapshot cache so the fleet sees the unblock at once. Defaulted to a no-op
      // for test call sites; production passes `policy.invalidate`.
      invalidateSnapshot: UIO[Unit] = ZIO.unit,
      // #1974 (SPA-ws S6a): a +Time grant immediately changes remaining-minutes, so it is a
      // `timeStatus` push write site (design §5.2). One-liner publish on the SPA change bus; never
      // blocks/fails the grant (sliding hub, UIO). Defaulted to the noop for test call sites that
      // don't exercise the push path.
      spaBus: SpaEventBus = SpaEventBus.noop,
      // #2077: the ambient anchor gate input. Noop (gate Off) keeps test call sites inert.
      ambientRepo: AmbientHostsRepo = NoopAmbientHostsRepo,
  ): Routes[Any, Response] =
    Routes(
      // #777 — collapsed accordion summary. One batched presence query over ALL devices
      // returns per-profile usedMins / dailyLimitMins / extensionMins / remainingMins. Used
      // by the SPA to render the unexpanded list without fanning out the full rollup.
      Method.GET / "api" / "time" / "status" / "summary"                ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] =
            for {
              claims   <- requireAuth(req, auth)
              settings <- hsRepo.getForHousehold(claims.hh).mapError(ApiError.Db(_))
              now      <- clock.instant
              // #1104: default to household-local "today", not UTC `clock.today`.
              today   = wifihaven.api.policy.PolicyService.householdLocalDate(now, settings)
              dateStr = req.url.queryParam("date").getOrElse(today.toString)
              date    = LocalDate.parse(dateStr)
              allProfiles <- profileRepo.listAllForHousehold(claims.hh).mapError(ApiError.Db(_))
              visible     <- visibleProfiles(claims, allProfiles, userProfileRepo)
              states      <- timeStatusService
                .dayStateAll(claims.hh, now, date, settings)
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
              claims   <- requireAuth(req, auth)
              settings <- hsRepo.getForHousehold(claims.hh).mapError(ApiError.Db(_))
              now      <- clock.instant
              // #1104: anchor on household-local today, not UTC today.
              today = wifihaven.api.policy.PolicyService.householdLocalDate(now, settings)
              toStr = req.url.queryParam("to").getOrElse(today.toString)
              to    = LocalDate.parse(toStr)
              from  = to.minusDays(6)
              allProfiles <- profileRepo.listAllForHousehold(claims.hh).mapError(ApiError.Db(_))
              allDevices  <- deviceRepo.listAllForHousehold(claims.hh).mapError(ApiError.Db(_))
              allLimits   <- timeLimitRepo.listAllForHousehold(claims.hh).mapError(ApiError.Db(_))
              allAppLims <- appTimeLimitRepo.listAllForHousehold(claims.hh).mapError(ApiError.Db(_))
              ambient    <- ambientRepo.gateFor(settings, today).mapError(ApiError.Db(_))
              visible    <- visibleProfiles(claims, allProfiles, userProfileRepo)
              devicesByPid = allDevices.groupBy(_.profileId)
              appLimsByPid = allAppLims.groupBy(_.profileId)
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
                  // #2077: gate the weekly rows with the same profile app-attribution context
                  // as the daily headline, so the weekly bars reconcile with the daily view.
                  val pRows   = wifihaven.api.policy.TimeStatusService.gatedPresence(
                    appLimsByPid.getOrElse(p.id, Nil),
                    presence.filter(r => macSet.contains(r.mac)),
                    settings,
                    ambient,
                  )
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
              claims   <- requireAuth(req, auth)
              settings <- hsRepo.getForHousehold(claims.hh).mapError(ApiError.Db(_))
              now      <- clock.instant
              // #1104: household-local today, not UTC.
              today   = wifihaven.api.policy.PolicyService.householdLocalDate(now, settings)
              dateStr = req.url.queryParam("date").getOrElse(today.toString)
              date    = LocalDate.parse(dateStr)
              // #795: ?profileId=N narrows the rollup to a single profile so the
              // SPA can fetch one card's worth of data instead of fanning out N
              // sub-rollups. Auth scope still applies — child tokens can only
              // request profiles they're already entitled to see.
              profileIdOpt <- parseProfileIdParam(req)
              allProfiles  <- profileRepo.listAllForHousehold(claims.hh).mapError(ApiError.Db(_))
              allDevices   <- deviceRepo.listAllForHousehold(claims.hh).mapError(ApiError.Db(_))
              visible      <- visibleProfiles(claims, allProfiles, userProfileRepo)
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
                        ambientRepo,
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
              claims   <- requireAuth(req, auth)
              settings <- hsRepo.getForHousehold(claims.hh).mapError(ApiError.Db(_))
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
              profileIdOpt    <- parseProfileIdParam(req)
              allProfiles     <- profileRepo.listAllForHousehold(claims.hh).mapError(ApiError.Db(_))
              allDevices      <- deviceRepo.listAllForHousehold(claims.hh).mapError(ApiError.Db(_))
              visible         <- visibleProfiles(claims, allProfiles, userProfileRepo)
              scoped       = profileIdOpt match {
                case Some(pid) => visible.filter(_.id == pid)
                case None      => visible
              }
              devicesByPid = allDevices.groupBy(_.profileId)
              ambient  <- ambientRepo.gateFor(settings, today).mapError(ApiError.Db(_))
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
                        settings,
                        appTimeLimitRepo,
                        ambient,
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
            claims   <- requireAuth(req, auth)
            settings <- hsRepo.getForHousehold(claims.hh).mapError(ApiError.Db(_))
            now      <- clock.instant
            // #1104: household-local today.
            today = wifihaven.api.policy.PolicyService.householdLocalDate(now, settings)
            toStr = req.url.queryParam("to").getOrElse(today.toString)
            to    = LocalDate.parse(toStr)
            from  = to.minusDays(6)
            bucketOffsetMin <- parseBucketOffsetMin(req)
            device          <- deviceRepo
              .findByMacInHousehold(MacAddress.unsafe(normalizeMac(mac)), claims.hh)
              .mapError(ApiError.Db(_))
              .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("Device not found")))
            _ <- requireProfileReadAccess(claims, device.profileId, userProfileRepo, profileRepo)
            ambient <- ambientRepo.gateFor(settings, today).mapError(ApiError.Db(_))
            status  <- buildDeviceTimeStatusWeek(
              device,
              from,
              to,
              profileRepo,
              timeLimitRepo,
              trafficRepo,
              settings.heartbeatFilter,
              bucketOffsetMin,
              settings,
              appTimeLimitRepo,
              ambient,
            )
              .mapError(ApiError.Db(_))
          } yield Response.json(status.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.GET / "api" / "time" / "status" / string("mac")            ->
        handler { (mac: String, req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims   <- requireAuth(req, auth)
            settings <- hsRepo.getForHousehold(claims.hh).mapError(ApiError.Db(_))
            now      <- clock.instant
            // #1104: household-local today.
            today   = wifihaven.api.policy.PolicyService.householdLocalDate(now, settings)
            dateStr = req.url.queryParam("date").getOrElse(today.toString)
            date    = LocalDate.parse(dateStr)
            device <- deviceRepo
              .findByMacInHousehold(MacAddress.unsafe(normalizeMac(mac)), claims.hh)
              .mapError(ApiError.Db(_))
              .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("Device not found")))
            _ <- requireProfileReadAccess(claims, device.profileId, userProfileRepo, profileRepo)
            status <- buildDeviceTimeStatus(
              device,
              date,
              now,
              settings,
              profileRepo,
              timeStatusService,
              trafficRepo,
              appTimeLimitRepo,
              ambientRepo,
            )
              .mapError(ApiError.Db(_))
          } yield Response.json(status.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      // #2077: the ambient-baseline explain surface — the learned (and candidate)
      // ambient hosts with distinct-day counts over the trailing learning window.
      // Operators inspect this before flipping `ambientGateEnabled` on, mirroring
      // the heartbeat-explain tune-before-enable workflow below. Admin-only: the
      // list is household-wide background telemetry, not per-profile data.
      Method.GET / "api" / "presence" / "ambient-hosts"                 ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] =
            for {
              claims   <- requireAdmin(req, auth)
              settings <- hsRepo.getForHousehold(claims.hh).mapError(ApiError.Db(_))
              now      <- clock.instant
              today = wifihaven.api.policy.PolicyService.householdLocalDate(now, settings)
              rows    <- ambientRepo.listWindow(settings, today).mapError(ApiError.Db(_))
              ambient <- ambientRepo.ambientHosts(settings, today).mapError(ApiError.Db(_))
            } yield Response.json(
              AmbientHostsResponse(
                gateEnabled = settings.ambientGateEnabled,
                isolationMaxHosts = settings.ambientIsolationMaxHosts,
                minIsolatedDays = settings.ambientMinIsolatedDays,
                learningWindowDays = settings.ambientLearningWindowDays,
                hosts = rows.map(r =>
                  AmbientHostEntry(
                    host = r.host,
                    isolatedDays = r.isolatedDays,
                    lastIsolatedDay = r.lastIsolatedDay.toString,
                    isolatedSpanCount = r.isolatedSpanCount,
                    ambient = ambient.contains(r.host),
                  ),
                ),
              ).toJson,
            )
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
              claims   <- requireAuth(req, auth)
              settings <- hsRepo.getForHousehold(claims.hh).mapError(ApiError.Db(_))
              now      <- clock.instant
              // #1104: household-local today.
              today   = wifihaven.api.policy.PolicyService.householdLocalDate(now, settings)
              dateStr = req.url.queryParam("date").getOrElse(today.toString)
              date    = LocalDate.parse(dateStr)
              device <- deviceRepo
                .findByMacInHousehold(MacAddress.unsafe(normalizeMac(mac)), claims.hh)
                .mapError(ApiError.Db(_))
                .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("Device not found")))
              _ <- requireProfileReadAccess(claims, device.profileId, userProfileRepo, profileRepo)
              rows <- trafficRepo
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
            claims   <- requireWriter(req, auth)
            body     <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            ger      <- ZIO
              .fromEither(body.fromJson[GrantExtensionRequest])
              .mapError(ApiError.DecodeFailure(_))
            _        <- requireProfileAccess(claims, ger.profileId, userProfileRepo, profileRepo)
            // #1010: bucket the grant under the household-local "today" so the
            // policy-snapshot read path (also household-local) finds it.
            settings <- hsRepo.getForHousehold(claims.hh).mapError(ApiError.Db(_))
            now      <- clock.instant
            today = wifihaven.api.policy.PolicyService.householdLocalDate(now, settings)
            // #2130: stamp the grant with the caller's household (like the
            // MAC-keyed grant, #2108) — never V65's DEFAULT 1.
            id <- extRepo
              .grantForProfile(
                ger.profileId,
                today,
                ger.extraMinutes,
                claims.sub,
                ger.note,
                claims.hh,
              )
              .mapError(ApiError.Db(_))
            // #946: bust the cached ProfileTimeStatus for this profile so the SPA's next
            // refetch reflects the new cap immediately instead of waiting up to todayTtl.
            _  <- cache.invalidateProfile(ger.profileId)
            // #1849: a grant can lift a TimeLimit block — snapshot content — so drop the
            // computed-snapshot cache too.
            _  <- invalidateSnapshot
            // #1974 (S6a): the grant changed remaining-minutes — push fresh `timeStatus`/`appUsage`
            // to live SPA subscribers (design §5.2). Contentless trigger; the consumer rebuilds.
            _  <- spaBus.publish(SpaEvent.TimeStatusChanged)
          } yield Response.json(s"""{"id":${id.value},"grantedMinutes":${ger.extraMinutes}}""")
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.GET / "api" / "time" / "extensions" / long("profileId")    ->
        handler { (profileId: Long, req: Request) =>
          val pid                                  = ProfileId(profileId)
          val handle: ZIO[Any, ApiError, Response] = for {
            claims   <- requireAuth(req, auth)
            _        <- requireProfileAccess(claims, pid, userProfileRepo, profileRepo)
            // #1010: same household-local "today" as the grant path.
            settings <- hsRepo.getForHousehold(claims.hh).mapError(ApiError.Db(_))
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
      ambientRepo: AmbientHostsRepo,
  ): Task[ProfileTimeStatus] = {
    val macs = devices.map(_.mac)
    for {
      stateOpt <- timeStatusService.dayState(now, date, settings, profile.id)
      state = stateOpt.getOrElse(
        wifihaven.api.policy.ProfileDayState(profile.id, date, None, 0, 0, None, false, None, Nil),
      )
      raw       <- trafficRepo.listPresenceRows(macs, date)
      appLimits <- appTimeLimitRepo.listForProfile(profile.id)
      ambient   <- ambientRepo.gateFor(
        settings,
        wifihaven.api.policy.PolicyService.householdLocalDate(now, settings),
      )
      // #2077: gate the presence-derived views (per-device summaries, top-N hosts) with the same
      // definition the headline `state.usedMinutes` was computed under.
      presence = wifihaven.api.policy.TimeStatusService
        .gatedPresence(appLimits, raw, settings, ambient)
      // #1974: the wire shape is assembled by the SINGLE shared builder so the live `timeStatus` ws
      // push and this GET produce byte-identical bodies (AGENTS.md §single-source-of-truth). It reads
      // `state`'s cap/used/remaining verbatim (no minute recompute) and folds in the presence-derived
      // per-device + top-N host views the snapshot doesn't carry.
    } yield wifihaven.api.policy.TimeStatusService
      .assembleProfileTimeStatus(profile, devices, state, presence, appLimits, settings)
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
      settings: HouseholdSettings,
      appTimeLimitRepo: AppTimeLimitRepo,
      ambient: wifihaven.api.presence.AmbientGate,
  ): Task[ProfileTimeStatusWeek] = {
    val macs = devices.map(_.mac)
    for {
      tl        <- tlRepo.findForProfile(profile.id)
      raw       <- trafficRepo.listPresenceRows(macs, from, to)
      appLimits <- appTimeLimitRepo.listForProfile(profile.id)
      // #2077: gate with the same profile app-attribution context as the daily view so the
      // weekly bars reconcile with the daily headline.
      presence        = wifihaven.api.policy.TimeStatusService
        .gatedPresence(appLimits, raw, settings, ambient)
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
      settings: HouseholdSettings,
      appTimeLimitRepo: AppTimeLimitRepo,
      ambient: wifihaven.api.presence.AmbientGate,
  ): Task[DeviceTimeStatusWeek] = {
    val pid  = device.profileId
    val macs = List(device.mac)
    for {
      tl        <- pid.fold(ZIO.succeed(Option.empty[TimeLimit]))(tlRepo.findForProfile)
      profile   <- pid.fold(ZIO.succeed("No profile"))(p =>
        profileRepo.findById(p).map(_.map(_.name).getOrElse("Unknown")),
      )
      raw       <- trafficRepo.listPresenceRows(macs, from, to)
      appLimits <- pid.fold(ZIO.succeed(List.empty[AppTimeLimit]))(
        appTimeLimitRepo.listForProfile,
      )
      presence  = wifihaven.api.policy.TimeStatusService
        .gatedPresence(appLimits, raw, settings, ambient)
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
   * for whole-hour zones, 30 for half-hour zones, 15/45 for the quarter-hour zones). Each
   * usage-report-period `period_start` (the agent's `usage_report_interval`, ~60s) falls in exactly
   * one hour slot of the chosen grid; per-slot minutes go through `Presence.totalMinutesByMac` for
   * per-mac bucket-dedup + heartbeat filter, then sum across macs. Empty slots are omitted (the SPA
   * fills gaps with zero when grouping by local day). Returned list is sorted by `bucketStart`
   * ascending.
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
      ambientRepo: AmbientHostsRepo,
  ): Task[DeviceTimeStatus] = {
    val pid = device.profileId
    for {
      stateOpt   <- pid.fold(ZIO.succeed(Option.empty[wifihaven.api.policy.ProfileDayState]))(p =>
        timeStatusService.dayState(now, date, settings, p),
      )
      raw        <- trafficRepo.listPresenceRows(List(device.mac), date)
      profileOpt <- pid.fold(ZIO.succeed(Option.empty[Profile]))(profileRepo.findById)
      appLimits  <- pid.fold(ZIO.succeed(List.empty[AppTimeLimit]))(
        appTimeLimitRepo.listForProfile,
      )
      ambient    <- ambientRepo.gateFor(
        settings,
        wifihaven.api.policy.PolicyService.householdLocalDate(now, settings),
      )
      presence  = wifihaven.api.policy.TimeStatusService
        .gatedPresence(appLimits, raw, settings, ambient)
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
      // #1505 + #1504: per-device per-app usage via the #1464 session-stitch primitive (one bar per
      // app). Single-device view, so cross-device overlap mode is moot — Sum and Dedup coincide.
      // #1897 (shared-hosts S2): stitch each app's DISTINCTIVE host-set only — the SAME source the
      // canonical per-profile stitch reads (`TimeStatusService.appDayStates` →
      // `ProfileAppDispositions.capGroupLabelDistinctiveHosts`). A shared backend overlapping a
      // distinctive span is already counted there, and a shared firing without distinctive activity
      // must not inflate the app's minutes; reading the full host-set here over-counted. Routes
      // through the one canonical distinctive-host projection (AGENTS.md §single-source-of-truth)
      // rather than re-deriving a host-set locally — `appLimits` is already loaded above.
      perApp    = wifihaven.api.presence.Presence
        .patternGroupMinutesForProfile(
          presence,
          wifihaven.api.policy.ProfileAppDispositions
            .from(appLimits)
            .capGroupLabelDistinctiveHosts,
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
  // #1265: choose the source table for /api/connection-events/series. Both
  // inputs and the rank ordering route through `wifihaven.shared.BucketPolicy`
  // so this picker and `UsageRoutes.pickTier` on the traffic side can't drift
  // (#1744). The bucket width caps how coarse a rollup may serve it, and a
  // wide window justifies coarsening on cost grounds; the finer of
  // (cap, window-pref) wins. Raw → None (read the live connection_events
  // table). includeMulticast forces raw, because the reroll excludes
  // multicast/broadcast at write time so the rollups can't serve it.
  private def seriesGrain(
      bucket: ConnectionEventBucket,
      hours: Int,
      includeMulticast: Boolean,
  ): Option[BucketGrain] =
    if (includeMulticast) None
    else {
      val cap    = BucketPolicy.grainForBucket(bucket.wire)
      val pref   = BucketPolicy.windowGrain(hours.toLong)
      val chosen = if (BucketPolicy.rank(pref) <= BucketPolicy.rank(cap)) pref else cap
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

  // #862: the (domain, device, profile) column order here must match the
  // SQL's group_key concatenation in `ConnectionEventRepo` — that ordering
  // remains the one manual constraint. The separator byte is sourced from
  // `LogAggGroupKey` so the SQL `chr(N) ||` concat and this builder share
  // one source of truth and can't drift on it (#1532).
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
            claims     <- requireAuth(req, auth)
            // #865: mac/deviceId/profileId accept comma-separated multi-value lists.
            // Old single-value URLs (e.g. ?profileId=2) parse to a one-element list.
            deviceIds  <- parseMultiDeviceIdParam(req)
            profileIds <- parseMultiProfileIdParam(req)
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
              // #2108: scope the connection_events read to the caller's household via the
              // router_id → routers.household_id join (design §7 pin 1). Wire-invisible; existing
              // callers that omit it read unscoped (single-household back-compat).
              household = Some(claims.hh),
            )
            logs    <- connRepo.query(filter).mapError(ApiError.Db(_))
            visible <- filterLogs(claims, logs, userProfileRepo)
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
            claims <- requireAuth(req, auth)
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
            deviceIds  <- parseMultiDeviceIdParam(req)
            profileIds <- parseMultiProfileIdParam(req)
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
            // #2282: scope the stat-card counts to the caller's household — the counts leaked across
            // tenants when read globally (a brand-new household saw the whole install's EVENTS/BLOCKED).
            requireAdmin(req, auth).flatMap { claims =>
              connRepo
                .stats(claims.hh)
                .map(s => Response.json(s.toJson))
                .mapError(ApiError.Db(_))
            }
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
            requireAdmin(req, auth) *>
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
            requireAdmin(req, auth) *>
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
            requireAdmin(req, auth) *>
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
            _   <- requireAdmin(req, auth)
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
      // #1849: unmanagedMacPolicy and blockEncryptedDns are snapshot content (the former drives the
      // unmanaged-MAC block rules, the latter the top-level `blockEncryptedDns` flag), so drop the
      // computed-snapshot cache on a settings write. Defaulted to a no-op for test call sites;
      // production passes `policy.invalidate`.
      invalidateSnapshot: UIO[Unit] = ZIO.unit,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "household" / "settings"   ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            _ <- requireAuth(req, auth)
            s <- repo.get.mapError(ApiError.Db(_))
          } yield Response.json(s.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.PUT / "api" / "household" / "settings"   ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            _    <- requireAdmin(req, auth)
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
                  upd.blockEncryptedDns,
                  upd.ambientGateEnabled,
                  upd.ambientIsolationMaxHosts,
                  upd.ambientMinIsolatedDays,
                  upd.ambientLearningWindowDays,
                  // #578: normalize blank → None so an empty field on a full-replace
                  // PUT means "no recipient", not the empty string.
                  upd.notifyEmail.map(_.trim).filter(_.nonEmpty),
                ),
              )
              .mapError(ApiError.Db(_))
            _    <- invalidateSnapshot
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
            _         <- requireAdmin(req, auth)
            existing  <- repo.get.mapError(ApiError.Db(_))
            body      <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            obj       <- ZIO.fromEither(FieldPatch.parseObj(body)).mapError(ApiError.BadRequest(_))
            timePatch <- ZIO
              .fromEither(FieldPatch.from[LocalTime](obj, "dailyResetTime"))
              .mapError(ApiError.BadRequest(_))
            tzPatch   <- ZIO
              .fromEither(FieldPatch.from[ZoneId](obj, "dailyResetTz"))
              .mapError(ApiError.BadRequest(_))
            // #1912: top-level boolean, standard PATCH semantics — absent
            // preserves, present sets, null clears (rejected; a boolean toggle
            // is never nullable).
            bedPatch  <- ZIO
              .fromEither(FieldPatch.from[Boolean](obj, "blockEncryptedDns"))
              .mapError(ApiError.BadRequest(_))
            _         <- bedPatch match {
              case FieldPatch.Cleared =>
                ZIO.fail(ApiError.BadRequest("blockEncryptedDns cannot be cleared"))
              case _                  => ZIO.unit
            }
            // #2077: same top-level-boolean PATCH semantics for the ambient-gate
            // master switch (the SPA autosave toggle). The three ambient
            // thresholds are API/config-side knobs with no SPA surface — PATCH
            // preserves them, mirroring presenceContinuationSeconds below.
            agPatch   <- ZIO
              .fromEither(FieldPatch.from[Boolean](obj, "ambientGateEnabled"))
              .mapError(ApiError.BadRequest(_))
            _         <- agPatch match {
              case FieldPatch.Cleared =>
                ZIO.fail(ApiError.BadRequest("ambientGateEnabled cannot be cleared"))
              case _                  => ZIO.unit
            }
            // #578: notifyEmail is a NULLABLE field — unlike the booleans above,
            // `null` legitimately clears the recipient. Absent preserves, a string
            // sets, null clears (applyToNullable). Blank is normalized to a clear.
            nePatch   <- ZIO
              .fromEither(FieldPatch.from[String](obj, "notifyEmail"))
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
              blockEncryptedDns = bedPatch.applyTo(existing.blockEncryptedDns),
              ambientGateEnabled = agPatch.applyTo(existing.ambientGateEnabled),
              ambientIsolationMaxHosts = existing.ambientIsolationMaxHosts,
              ambientMinIsolatedDays = existing.ambientMinIsolatedDays,
              ambientLearningWindowDays = existing.ambientLearningWindowDays,
              // #578: Set → normalized Some (blank clears), Cleared → None, Absent → preserve.
              notifyEmail = nePatch match {
                case FieldPatch.Set(v)  => Option(v.trim).filter(_.nonEmpty)
                case FieldPatch.Cleared => None
                case FieldPatch.Absent  => existing.notifyEmail
              },
            )
            _            <- repo.update(merged).mapError(ApiError.Db(_))
            _            <- invalidateSnapshot
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

/**
 * #2079/#2081: best-effort client IP for rate-limit keying.
 *
 * Deliberately does NOT trust the first (leftmost) hop of `X-Forwarded-For`: our own reverse-proxy
 * config (docs/install-api.md §7.2) sets it via nginx's `$proxy_add_x_forwarded_for`, which
 * *appends* the real client to whatever the caller already sent — so the leftmost entry is
 * attacker-controlled, and reading it would let anyone bypass the rate limit by sending a fresh
 * fake value on every request. `X-Real-IP` is the value our nginx config sets instead
 * (`$remote_addr`), which nginx *overwrites* rather than appends — not spoofable through it. Falls
 * back to the last (rightmost) `X-Forwarded-For` hop (the proxy-appended one, for any other proxy
 * that only sets that header), then to the raw socket `remoteAddress` (correct when there's no
 * reverse proxy at all), then to a constant so a fully unattributable caller still shares one
 * (still-enforced) bucket rather than bypassing the limit entirely.
 */
private[routes] def clientIp(req: Request): String =
  req.headers
    .get("X-Real-IP")
    .map(_.trim)
    .filter(_.nonEmpty)
    .orElse(
      req.headers
        .get("X-Forwarded-For")
        .map(_.split(",").last.trim)
        .filter(_.nonEmpty),
    )
    .orElse(req.remoteAddress.map(_.getHostAddress))
    .getOrElse("unknown")

// 403 body emitted when must_change_password is set (#586). Wire-shape:
// JSON `{"error":"password_change_required"}`, distinct from ApiError.Forbidden's plain text — kept
// as a Wrapped Response since the SPA sniffs this exact JSON body.
private val passwordChangeRequiredError: ApiError =
  ApiError.Wrapped(
    Response.json("""{"error":"password_change_required"}""").status(Status.Forbidden),
  )

/**
 * Verify auth token only — does NOT check must_change_password. Used exclusively by POST
 * /api/auth/change-password so that the flag doesn't block the one route that can clear it.
 */
def requireAuthSkipPwCheck(req: Request, auth: AuthService): IO[ApiError, JwtClaims] =
  ZIO
    .fromOption(bearerToken(req))
    .orElseFail(ApiError.Unauthorized("Missing token"))
    .flatMap(t =>
      auth
        .verify(t)
        .mapError {
          case AuthError.TokenExpired => ApiError.Unauthorized("Token expired")
          case AuthError.TokenRevoked => ApiError.Unauthorized("Session revoked")
          case _                      => ApiError.Unauthorized("Invalid token")
        },
    )

/**
 * Verify auth token AND enforce that must_change_password is false. Returns 403
 * {"error":"password_change_required"} if the flag is set (#586). All authenticated routes except
 * change-password use this.
 */
def requireAuth(req: Request, auth: AuthService): IO[ApiError, JwtClaims] =
  ZIO
    .fromOption(bearerToken(req))
    .orElseFail(ApiError.Unauthorized("Missing token"))
    .flatMap(t =>
      auth
        .requirePasswordChanged(t)
        .mapError {
          case AuthError.Forbidden    => passwordChangeRequiredError
          case AuthError.TokenExpired => ApiError.Unauthorized("Token expired")
          case AuthError.TokenRevoked => ApiError.Unauthorized("Session revoked")
          case _                      => ApiError.Unauthorized("Invalid token")
        },
    )

def requireAdmin(req: Request, auth: AuthService): IO[ApiError, JwtClaims] =
  // requireAuth already enforces must_change_password; then we check role.
  requireAuth(req, auth).flatMap { claims =>
    if claims.role == "admin" then ZIO.succeed(claims)
    else ZIO.fail(ApiError.Forbidden("Admin required"))
  }

def requireWriter(req: Request, auth: AuthService): IO[ApiError, JwtClaims] =
  // requireAuth already enforces must_change_password; then we check role.
  requireAuth(req, auth).flatMap { claims =>
    if claims.role == "admin" || claims.role == "adult" then ZIO.succeed(claims)
    else ZIO.fail(ApiError.Forbidden("Adult or admin required"))
  }

/**
 * #2132 (multi-tenant P5-2, epic #622): the OPERATOR gate — admin AND a member of household 1 (the
 * deployment operator's household, `HouseholdId.Default`). This is the ONE deliberate, narrow
 * exception to the "no cross-household admin" non-goal (docs/design/multi-tenant-isolation.md §9),
 * scoped tightly: the only surface behind it reads/writes `beta_requests` rows — which belong to no
 * household until approval — never another household's data. Documented as v1-pragmatic; a real ops
 * console remains a future, deliberately-privileged surface. Any non-household-1 admin gets 403,
 * indistinguishable from a plain forbidden-role (no cross-household enumeration signal).
 */
def requireOperator(req: Request, auth: AuthService): IO[ApiError, JwtClaims] =
  requireAdmin(req, auth).flatMap { claims =>
    if claims.hh == wifihaven.shared.types.HouseholdId.Default then ZIO.succeed(claims)
    else ZIO.fail(ApiError.Forbidden("Operator required"))
  }

/** Admin and adult see all profiles. Child only sees profiles linked to their user. */
def visibleProfiles(
    claims: JwtClaims,
    all: List[Profile],
    upRepo: UserProfileRepo,
): IO[ApiError, List[Profile]] =
  if claims.role == "admin" || claims.role == "adult" then ZIO.succeed(all)
  else
    upRepo
      .listProfilesForUsername(claims.sub)
      .mapError(ApiError.Db(_))
      .map(pids => all.filter(p => pids.contains(p.id)))

def filterDevices(
    claims: JwtClaims,
    all: List[Device],
    upRepo: UserProfileRepo,
): IO[ApiError, List[Device]] =
  if claims.role == "admin" || claims.role == "adult" then ZIO.succeed(all)
  else
    upRepo
      .listProfilesForUsername(claims.sub)
      .mapError(ApiError.Db(_))
      .map(pids => all.filter(d => d.profileId.exists(pids.contains)))

def filterLogs(
    claims: JwtClaims,
    all: List[QueryLog],
    upRepo: UserProfileRepo,
): IO[ApiError, List[QueryLog]] =
  if claims.role == "admin" || claims.role == "adult" then ZIO.succeed(all)
  else
    upRepo
      .listProfilesForUsername(claims.sub)
      .mapError(ApiError.Db(_))
      .map(pids => all.filter(l => l.profileId.exists(pids.contains)))

/**
 * #2108 (multi-tenant sub-issue E): the tenancy choke point for profile-targeting routes. Rejects
 * (404 — never leak existence across the tenant boundary) any target profile whose household ≠ the
 * caller's `claims.hh`. Composed FIRST inside every `requireProfile*Access` guard so an admin/adult
 * — who otherwise has full role visibility — still cannot address another household's profile
 * (design §7 pin 2). Runs before the role/link check so an hh-A `admin` and a genuinely-absent
 * profile are indistinguishable to an attacker.
 */
def requireProfileInHousehold(
    claims: JwtClaims,
    profileId: ProfileId,
    profileRepo: ProfileRepo,
): IO[ApiError, Unit] =
  profileRepo.householdOf(profileId).mapError(ApiError.Db(_)).flatMap {
    case Some(hh) if hh == claims.hh => ZIO.succeed(())
    case _                           => ZIO.fail(ApiError.NotFound("Profile not found"))
  }

/**
 * #2126 (multi-tenant, epic #2085/#622): the tenancy choke point for the per-id named-schedule
 * routes (`GET`/`PATCH`/`DELETE /api/schedules/{id}`). Rejects (404 — never leak existence across
 * the tenant boundary) any target schedule whose household ≠ the caller's `claims.hh`. Mirrors
 * [[requireProfileInHousehold]]; the list read is scoped at the repo (`listAllForHousehold`).
 */
def requireScheduleInHousehold(
    claims: JwtClaims,
    scheduleId: NamedScheduleId,
    scheduleRepo: NamedScheduleRepo,
): IO[ApiError, Unit] =
  scheduleRepo.householdOf(scheduleId).mapError(ApiError.Db(_)).flatMap {
    case Some(hh) if hh == claims.hh => ZIO.succeed(())
    case _                           => ZIO.fail(ApiError.NotFound("Schedule not found"))
  }

/** Allow read access if admin or adult (full visibility); child must be linked to the profile. */
def requireProfileReadAccess(
    claims: JwtClaims,
    profileId: ProfileId,
    upRepo: UserProfileRepo,
    profileRepo: ProfileRepo,
): IO[ApiError, Unit] =
  requireProfileInHousehold(claims, profileId, profileRepo) *> {
    if claims.role == "admin" || claims.role == "adult" then ZIO.succeed(())
    else
      upRepo
        .listProfilesForUsername(claims.sub)
        .mapError(ApiError.Db(_))
        .flatMap { pids =>
          if pids.contains(profileId) then ZIO.succeed(())
          else ZIO.fail(ApiError.Forbidden("Not authorized for this profile"))
        }
  }

def requireProfileReadAccess(
    claims: JwtClaims,
    profileId: Option[ProfileId],
    upRepo: UserProfileRepo,
    profileRepo: ProfileRepo,
): IO[ApiError, Unit] =
  profileId match {
    case None      =>
      if claims.role == "admin" || claims.role == "adult" then ZIO.succeed(())
      else ZIO.fail(ApiError.Forbidden("Device has no assigned profile"))
    case Some(pid) => requireProfileReadAccess(claims, pid, upRepo, profileRepo)
  }

/** Allow write access if admin or adult linked to the profile. Child is always denied. */
def requireProfileAccess(
    claims: JwtClaims,
    profileId: ProfileId,
    upRepo: UserProfileRepo,
    profileRepo: ProfileRepo,
): IO[ApiError, Unit] =
  requireProfileInHousehold(claims, profileId, profileRepo) *> {
    if claims.role == "admin" then ZIO.succeed(())
    else
      upRepo
        .listProfilesForUsername(claims.sub)
        .mapError(ApiError.Db(_))
        .flatMap { pids =>
          if pids.contains(profileId) then ZIO.succeed(())
          else ZIO.fail(ApiError.Forbidden("Not authorized for this profile"))
        }
  }

def requireProfileAccess(
    claims: JwtClaims,
    profileId: Option[ProfileId],
    upRepo: UserProfileRepo,
    profileRepo: ProfileRepo,
): IO[ApiError, Unit] =
  profileId match {
    case None      =>
      if claims.role == "admin" then ZIO.succeed(())
      else ZIO.fail(ApiError.Forbidden("Device has no assigned profile"))
    case Some(pid) => requireProfileAccess(claims, pid, upRepo, profileRepo)
  }

/**
 * #1771: reject writes that target the global sentinel profile when the targeted concept is
 * meaningless household-wide (schedules / time limits / paused / pauseMode / manual block /
 * deletion). Returns 422 with a human-readable message naming the offending field. Read paths and
 * writes that DO make sense on the sentinel (its app-policy assignments, blocked_categories via
 * PATCH→blockedCategories, name, blockIpOnly, etc. when introduced) are not gated here.
 *
 * Implemented at the route layer (not the repo) because the repo setters have no context about
 * WHICH field a PATCH is touching — a route knows the user-facing field name and can give a precise
 * error.
 */
def requireNotGlobalProfile(
    profileRepo: ProfileRepo,
    profileId: ProfileId,
    field: String,
): IO[ApiError, Unit] =
  profileRepo
    .findById(profileId)
    .mapError(ApiError.Db(_))
    .flatMap {
      case Some(p) if p.isGlobal =>
        ZIO.fail(
          ApiError.BadRequest(
            s"$field cannot be set on the global profile (id=${profileId.value})",
          ),
        )
      case _                     => ZIO.unit
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
def parseProfileIdParam(req: Request): IO[ApiError, Option[ProfileId]] =
  req.url.queryParam("profileId") match {
    case None    => ZIO.succeed(None)
    case Some(s) =>
      s.toLongOption
        .map(l => ZIO.succeed(Some(ProfileId(l))))
        .getOrElse(ZIO.fail(ApiError.BadRequest(s"invalid profileId: $s")))
  }

// #865: comma-separated multi-value query params. Old single-value URLs
// ("profileId=2") parse as a one-element list, so previously-shared links
// keep working. Absent param → empty list (no filter).
def parseMultiValueParam(req: Request, name: String): List[String] =
  req.url.queryParam(name) match {
    case None    => Nil
    case Some(s) => s.split(',').toList.map(_.trim).filter(_.nonEmpty)
  }

def parseMultiProfileIdParam(req: Request): IO[ApiError, List[ProfileId]] =
  ZIO.foreach(parseMultiValueParam(req, "profileId")) { s =>
    ZIO
      .fromOption(s.toLongOption.map(ProfileId(_)))
      .orElseFail(ApiError.BadRequest(s"invalid profileId: $s"))
  }

def parseMultiDeviceIdParam(req: Request): IO[ApiError, List[DeviceId]] =
  ZIO.foreach(parseMultiValueParam(req, "deviceId")) { s =>
    ZIO
      .fromOption(s.toLongOption.map(DeviceId(_)))
      .orElseFail(ApiError.BadRequest(s"invalid deviceId: $s"))
  }
