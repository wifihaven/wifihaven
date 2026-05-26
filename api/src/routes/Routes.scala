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

object AuthRoutes {
  def routes(
      auth: AuthService,
      userRepo: UserRepo,
      userProfileRepo: UserProfileRepo,
  ): Routes[Any, Response] =
    Routes(
      Method.POST / "api" / "auth" / "login"                 ->
        handler { (req: Request) =>
          for {
            body <- req.body.asString.orElseFail(Response.badRequest(""))
            lr   <- ZIO
              .fromEither(body.fromJson[LoginRequest])
              .mapError(e => Response.badRequest(e))
            resp <- auth
              .login(lr.username, lr.password)
              .mapError {
                case AuthError.InvalidCredentials => Response.unauthorized("Invalid credentials")
                case AuthError.Unexpected(_)      => ErrorMapper.dbUnavailable("Unexpected")
                case _                            => ErrorMapper.dbUnavailable("AuthError")
              }
          } yield Response.json(resp.toJson)
        },
      Method.POST / "api" / "auth" / "change-password"       ->
        handler { (req: Request) =>
          for {
            // Skip the must_change_password guard: this is the one route that
            // clears it. Using requireAuthSkipPwCheck prevents a deadlock where
            // the flag blocks the only endpoint that can reset itself (#586).
            claims <- requireAuthSkipPwCheck(req, auth)
            body   <- req.body.asString.orElseFail(Response.badRequest(""))
            cpr    <- ZIO
              .fromEither(body.fromJson[ChangePasswordRequest])
              .mapError(e => Response.badRequest(e))
            _      <- auth
              .changePassword(claims.sub, cpr.currentPassword, cpr.newPassword)
              .mapError {
                case AuthError.InvalidCredentials =>
                  Response.unauthorized("Current password incorrect")
                case AuthError.Unexpected(_)      => ErrorMapper.dbUnavailable("Unexpected")
                case _                            => ErrorMapper.dbUnavailable("AuthError")
              }
          } yield Response.ok
        },
      Method.GET / "api" / "me"                              ->
        handler { (req: Request) =>
          for {
            claims <- requireAuth(req, auth)
            pids   <- userProfileRepo
              .listProfilesForUsername(claims.sub)
              .mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.json(
            MeResponse(
              claims.sub,
              UserRole.parse(claims.role).getOrElse(UserRole.Child),
              pids,
            ).toJson,
          )
        },
      Method.POST / "api" / "users"                          ->
        handler { (req: Request) =>
          for {
            _    <- requireAdmin(req, auth)
            body <- req.body.asString.orElseFail(Response.badRequest(""))
            cur  <- ZIO
              .fromEither(body.fromJson[CreateUserRequest])
              .mapError(e => Response.badRequest(e))
            hash <- auth.hashPassword(cur.password)
            id   <- userRepo
              .create(cur.username, hash, UserRole.asString(cur.role))
              .mapError(ErrorMapper.dbErrorToResponse)
            _    <- userProfileRepo
              .setProfilesForUser(id, cur.profileIds)
              .mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.json(s"""{"id":$id}""")
        },
      Method.GET / "api" / "users"                           ->
        handler { (req: Request) =>
          for {
            _        <- requireAdmin(req, auth)
            users    <- userRepo.listAll.mapError(ErrorMapper.dbErrorToResponse)
            mappings <- userProfileRepo.listAllMappings.mapError(ErrorMapper.dbErrorToResponse)
            byUser    = mappings.groupBy(_._1).view.mapValues(_.map(_._2)).toMap
            summaries = users.map(u =>
              UserSummary(u.id, u.username, u.role, byUser.getOrElse(u.id, Nil)),
            )
          } yield Response.json(summaries.toJson)
        },
      Method.PUT / "api" / "users" / long("id") / "profiles" ->
        handler { (id: Long, req: Request) =>
          for {
            _    <- requireAdmin(req, auth)
            body <- req.body.asString.orElseFail(Response.badRequest(""))
            r    <- ZIO
              .fromEither(body.fromJson[SetUserProfilesRequest])
              .mapError(e => Response.badRequest(e))
            _    <- userProfileRepo
              .setProfilesForUser(UserId(id), r.profileIds)
              .mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.ok
        },
      Method.DELETE / "api" / "users" / long("id")           ->
        handler { (id: Long, req: Request) =>
          requireAdmin(req, auth) *>
            userRepo.delete(UserId(id)).mapError(ErrorMapper.dbErrorToResponse) *>
            ZIO.succeed(Response.ok)
        },
      // #997: field-scoped partial update. Body is a subset of the User read
      // shape — `username`, `role`, `profileIds` (replace-set, matches the
      // existing PUT /profiles semantics). Password changes stay on the
      // dedicated change-password endpoint.
      Method.PATCH / "api" / "users" / long("id")            ->
        handler { (id: Long, req: Request) =>
          val uid = UserId(id)
          for {
            _    <- requireAdmin(req, auth)
            _    <- userRepo
              .findById(uid)
              .mapError(ErrorMapper.dbErrorToResponse)
              .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("User not found")))
            body <- req.body.asString.orElseFail(Response.badRequest(""))
            obj  <- ZIO.fromEither(FieldPatch.parseObj(body)).mapError(Response.badRequest(_))
            usernamePatch <- ZIO
              .fromEither(FieldPatch.from[String](obj, "username"))
              .mapError(Response.badRequest(_))
            rolePatch     <- ZIO
              .fromEither(FieldPatch.from[UserRole](obj, "role"))
              .mapError(Response.badRequest(_))
            profilesPatch <- ZIO
              .fromEither(FieldPatch.from[List[ProfileId]](obj, "profileIds"))
              .mapError(Response.badRequest(_))
            _             <- (usernamePatch, rolePatch, profilesPatch) match {
              case (FieldPatch.Cleared, _, _) =>
                ZIO.fail(Response.badRequest("username cannot be cleared"))
              case (_, FieldPatch.Cleared, _) =>
                ZIO.fail(Response.badRequest("role cannot be cleared"))
              case (_, _, FieldPatch.Cleared) =>
                ZIO.fail(
                  Response.badRequest("profileIds cannot be cleared (send [] to unassign all)"),
                )
              case _                          => ZIO.unit
            }
            _             <- usernamePatch match {
              case FieldPatch.Set(u) =>
                userRepo.updateUsername(uid, u).mapError(ErrorMapper.dbErrorToResponse)
              case _                 => ZIO.unit
            }
            _             <- rolePatch match {
              case FieldPatch.Set(r) =>
                userRepo
                  .updateRole(uid, UserRole.asString(r))
                  .mapError(ErrorMapper.dbErrorToResponse)
              case _                 => ZIO.unit
            }
            _             <- profilesPatch match {
              case FieldPatch.Set(pids) =>
                userProfileRepo
                  .setProfilesForUser(uid, pids)
                  .mapError(ErrorMapper.dbErrorToResponse)
              case _                    => ZIO.unit
            }
          } yield Response.ok
        },
    )
}

// ── Profile routes ─────────────────────────────────────────────────────────

object ProfileRoutes {
  def routes(
      auth: AuthService,
      profileRepo: ProfileRepo,
      scheduleRepo: ScheduleRepo,
      timeLimitRepo: TimeLimitRepo,
      userProfileRepo: UserProfileRepo,
      userRepo: UserRepo,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "profiles"                        ->
        handler { (req: Request) =>
          for {
            claims      <- requireAuth(req, auth)
            allProfiles <- profileRepo.listAll.mapError(ErrorMapper.dbErrorToResponse)
            visible     <- visibleProfiles(claims, allProfiles, userProfileRepo)
            details     <- ZIO
              .foreach(visible) { p =>
                for {
                  scheds <- scheduleRepo.listForProfile(p.id)
                  tl     <- timeLimitRepo.findForProfile(p.id)
                } yield ProfileDetail(p, scheds, tl)
              }
              .mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.json(details.toJson)
        },
      Method.GET / "api" / "profiles" / long("id")           ->
        handler { (id: Long, req: Request) =>
          val pid = ProfileId(id)
          for {
            claims <- requireAuth(req, auth)
            _      <- requireProfileReadAccess(claims, pid, userProfileRepo)
            p      <- profileRepo
              .findById(pid)
              .mapError(ErrorMapper.dbErrorToResponse)
              .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("Profile not found")))
            scheds <- scheduleRepo.listForProfile(pid).mapError(ErrorMapper.dbErrorToResponse)
            tl     <- timeLimitRepo.findForProfile(pid).mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.json(ProfileDetail(p, scheds, tl).toJson)
        },
      Method.POST / "api" / "profiles"                       ->
        handler { (req: Request) =>
          for {
            _    <- requireAdmin(req, auth)
            body <- req.body.asString.orElseFail(Response.badRequest(""))
            upr  <- ZIO
              .fromEither(body.fromJson[UpsertProfileRequest])
              .mapError(e => Response.badRequest(e))
            id   <- profileRepo
              .create(upr.name, upr.blockedCategories)
              .mapError(ErrorMapper.dbErrorToResponse)
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
                ),
              )
              .mapError(ErrorMapper.dbErrorToResponse)
            _    <- scheduleRepo
              .replaceForProfile(id, upr.schedules)
              .mapError(ErrorMapper.dbErrorToResponse)
            _    <- ZIO
              .foreachDiscard(upr.timeLimit)(mins => timeLimitRepo.upsert(id, mins))
              .mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.json(s"""{"id":${id.value}}""")
        },
      Method.PUT / "api" / "profiles" / long("id")           ->
        handler { (id: Long, req: Request) =>
          val pid = ProfileId(id)
          for {
            claims <- requireWriter(req, auth)
            _      <- requireProfileAccess(claims, pid, userProfileRepo)
            body   <- req.body.asString.orElseFail(Response.badRequest(""))
            upr    <- ZIO
              .fromEither(body.fromJson[UpsertProfileRequest])
              .mapError(e => Response.badRequest(e))
            p      <- profileRepo
              .findById(pid)
              .mapError(ErrorMapper.dbErrorToResponse)
              .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("Profile not found")))
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
                ),
              )
              .mapError(ErrorMapper.dbErrorToResponse)
            // #481: log mutations that should bump the policy snapshot etag.
            _      <- ZIO.logInfo(
              s"profile updated: id=${pid.value} paused=${p.paused}→${upr.paused} " +
                s"name=${upr.name}",
            )
            _      <- scheduleRepo
              .replaceForProfile(pid, upr.schedules)
              .mapError(ErrorMapper.dbErrorToResponse)
            _      <- (upr.timeLimit match {
              case Some(mins) => timeLimitRepo.upsert(pid, mins)
              case None       => timeLimitRepo.delete(pid)
            }).mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.ok
        },
      Method.DELETE / "api" / "profiles" / long("id")        ->
        handler { (id: Long, req: Request) =>
          requireAdmin(req, auth) *>
            profileRepo.delete(ProfileId(id)).mapError(ErrorMapper.dbErrorToResponse) *>
            ZIO.succeed(Response.ok)
        },
      Method.GET / "api" / "profiles" / long("id") / "users" ->
        handler { (id: Long, req: Request) =>
          val pid = ProfileId(id)
          for {
            _     <- requireAdmin(req, auth)
            uids  <- userProfileRepo
              .listUsersForProfile(pid)
              .mapError(ErrorMapper.dbErrorToResponse)
            users <- userRepo.listAll.mapError(ErrorMapper.dbErrorToResponse)
            byId      = users.map(u => u.id -> u).toMap
            summaries = uids.flatMap(byId.get).map { u =>
              UserSummary(u.id, u.username, u.role, List(pid))
            }
          } yield Response.json(summaries.toJson)
        },
      Method.PUT / "api" / "profiles" / long("id") / "users" ->
        handler { (id: Long, req: Request) =>
          val pid = ProfileId(id)
          for {
            _    <- requireAdmin(req, auth)
            body <- req.body.asString.orElseFail(Response.badRequest(""))
            r    <- ZIO
              .fromEither(body.fromJson[SetProfileUsersRequest])
              .mapError(e => Response.badRequest(e))
            _    <- userProfileRepo
              .setUsersForProfile(pid, r.userIds)
              .mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.ok
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
          for {
            claims  <- requireAuth(req, auth)
            all     <- deviceRepo.listAll.mapError(ErrorMapper.dbErrorToResponse)
            visible <- filterDevices(claims, all, userProfileRepo)
          } yield Response.json(visible.toJson)
        },
      Method.PUT / "api" / "devices"                    ->
        handler { (req: Request) =>
          for {
            claims <- requireWriter(req, auth)
            body   <- req.body.asString.orElseFail(Response.badRequest(""))
            udr    <- ZIO
              .fromEither(body.fromJson[UpsertDeviceRequest])
              .mapError(e => Response.badRequest(e))
            mac = MacAddress.unsafe(normalizeMac(udr.mac.value))
            // #708: profileId is optional — None means "unassigned" (NULL). The
            // access check only fires when the caller supplies a profileId; targeting
            // a profile they can't write to still 403s.
            _  <- udr.profileId match {
              case Some(pid) => requireProfileAccess(claims, pid, userProfileRepo)
              case None      => ZIO.unit
            }
            id <- deviceRepo
              .upsert(mac, udr.name, udr.profileId, "")
              .mapError(ErrorMapper.dbErrorToResponse)
            // #481: log device upsert so the next CI failure makes it obvious
            // whether the mutation reached the API at all.
            _  <- ZIO.logInfo(
              s"device upserted: mac=${mac.value} profileId=${udr.profileId.map(_.value.toString).getOrElse("-")} name=${udr.name}",
            )
          } yield Response.json(s"""{"id":${id.value}}""")
        },
      Method.DELETE / "api" / "devices" / string("mac") ->
        handler { (mac: String, req: Request) =>
          for {
            claims <- requireWriter(req, auth)
            normalized = MacAddress.unsafe(normalizeMac(mac))
            existing <- deviceRepo
              .findByMac(normalized)
              .mapError(ErrorMapper.dbErrorToResponse)
              .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("Device not found")))
            _        <- requireProfileAccess(claims, existing.profileId, userProfileRepo)
            _        <- deviceRepo.delete(normalized).mapError(ErrorMapper.dbErrorToResponse)
            // #481: same rationale as PUT — make the next CI failure diagnostic.
            _        <- ZIO.logInfo(s"device deleted: mac=${normalized.value}")
          } yield Response.ok
        },
      // #996: field-scoped partial update. Body is a subset of the Device read
      // shape — `name` (set), `profileId` (set/null-to-clear). Absent fields
      // preserve their current value. Same auth as DELETE: writer + access to
      // the device's current profile, plus access to the destination profile
      // if `profileId` is being reassigned.
      Method.PATCH / "api" / "devices" / string("mac")  ->
        handler { (mac: String, req: Request) =>
          for {
            claims <- requireWriter(req, auth)
            normalized = MacAddress.unsafe(normalizeMac(mac))
            existing  <- deviceRepo
              .findByMac(normalized)
              .mapError(ErrorMapper.dbErrorToResponse)
              .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("Device not found")))
            _         <- requireProfileAccess(claims, existing.profileId, userProfileRepo)
            body      <- req.body.asString.orElseFail(Response.badRequest(""))
            obj       <- ZIO.fromEither(FieldPatch.parseObj(body)).mapError(Response.badRequest(_))
            namePatch <- ZIO
              .fromEither(FieldPatch.from[String](obj, "name"))
              .mapError(Response.badRequest(_))
            pidPatch  <- ZIO
              .fromEither(FieldPatch.from[ProfileId](obj, "profileId"))
              .mapError(Response.badRequest(_))
            _         <- namePatch match {
              case FieldPatch.Cleared => ZIO.fail(Response.badRequest("name cannot be cleared"))
              case _                  => ZIO.unit
            }
            _         <- pidPatch match {
              case FieldPatch.Set(pid) => requireProfileAccess(claims, pid, userProfileRepo)
              case _                   => ZIO.unit
            }
            newName = namePatch.applyTo(existing.name)
            newPid = pidPatch.applyToNullable(existing.profileId)
            _ <- deviceRepo
              .upsert(normalized, newName, newPid, "")
              .mapError(ErrorMapper.dbErrorToResponse)
            _ <- ZIO.logInfo(
              s"device patched: mac=${normalized.value} name=$newName profileId=${newPid.map(_.value.toString).getOrElse("-")}",
            )
          } yield Response.ok
        },
    )
}

// ── Time routes ────────────────────────────────────────────────────────────

object TimeRoutes {
  // #802: cache-control freshness windows mirror the in-process TimeStatusCache TTLs
  // (today=30s, past=1h). SPAs and intermediaries can use these to skip refetches when
  // the local copy is fresh; mutating endpoints don't depend on these for correctness.
  private val TodayMaxAgeSeconds: Long = 30L
  private val PastMaxAgeSeconds: Long  = 3600L

  // #802: emit a hit-rate summary every N requests. Cheap heuristic — no scheduler.
  private val StatsLogEveryNRequests = 100

  def routes(
      auth: AuthService,
      deviceRepo: DeviceRepo,
      timeLimitRepo: TimeLimitRepo,
      siteTimeLimitRepo: SiteTimeLimitRepo,
      trafficRepo: TrafficReportRepo,
      extRepo: TimeExtensionRepo,
      profileRepo: ProfileRepo,
      userProfileRepo: UserProfileRepo,
      hsRepo: HouseholdSettingsRepo,
      clock: Clock,
      cache: TimeStatusCache = TimeStatusCache.makeUnsafe(),
  ): Routes[Any, Response] =
    Routes(
      // #777 — collapsed accordion summary. One batched presence query over ALL devices
      // returns per-profile usedMins / dailyLimitMins / extensionMins / remainingMins. Used
      // by the SPA to render the unexpanded list without fanning out the full rollup.
      Method.GET / "api" / "time" / "status" / "summary"                ->
        handler { (req: Request) =>
          for {
            claims <- requireAuth(req, auth)
            today  <- clock.today
            dateStr = req.url.queryParam("date").getOrElse(today.toString)
            date    = LocalDate.parse(dateStr)
            allProfiles   <- profileRepo.listAll.mapError(ErrorMapper.dbErrorToResponse)
            allDevices    <- deviceRepo.listAll.mapError(ErrorMapper.dbErrorToResponse)
            allLimits     <- timeLimitRepo.listAll.mapError(ErrorMapper.dbErrorToResponse)
            allSiteLimits <- siteTimeLimitRepo.listAll.mapError(ErrorMapper.dbErrorToResponse)
            extsByPid     <- extRepo
              .snapshotAllByProfile(date)
              .mapError(ErrorMapper.dbErrorToResponse)
            settings      <- hsRepo.get.mapError(ErrorMapper.dbErrorToResponse)
            visible       <- visibleProfiles(claims, allProfiles, userProfileRepo)
            devicesByPid = allDevices.groupBy(_.profileId)
            allMacs      = visible.iterator
              .flatMap(p => devicesByPid.getOrElse(Some(p.id), Nil))
              .map(_.mac)
              .toList
              .distinct
            presence <- (if allMacs.isEmpty then ZIO.succeed(Nil)
                         else trafficRepo.listPresenceRows(allMacs, date))
              .mapError(ErrorMapper.dbErrorToResponse)
            limitByPid  = allLimits.iterator.map(l => l.profileId -> l.dailyMinutes).toMap
            exemptByPid = allSiteLimits.iterator
              .filter(_.exemptFromDaily)
              .toList
              .groupBy(_.profileId)
              .view
              .mapValues(_.map(_.domainPattern))
              .toMap
            summaries   = visible.map { p =>
              val devices   = devicesByPid.getOrElse(Some(p.id), Nil)
              val macSet    = devices.map(_.mac).toSet
              val pRows     = presence.filter(r => macSet.contains(r.mac))
              val exempts   = exemptByPid.getOrElse(p.id, Nil)
              val perMac    = wifihaven.api.presence.Presence
                .totalMinutesByMac(pRows, exempts, settings.heartbeatFilter)
              // #751: honor the profile's overlap mode.
              val used      = p.crossDeviceOverlapMode match {
                case CrossDeviceOverlapMode.Sum   =>
                  devices.iterator.map(d => perMac.getOrElse(d.mac, 0)).sum
                case CrossDeviceOverlapMode.Dedup =>
                  wifihaven.api.presence.Presence
                    .dedupedTotalMinutes(pRows, exempts, settings.heartbeatFilter)
              }
              val limit     = limitByPid.get(p.id)
              val extMins   = extsByPid.getOrElse(p.id, 0)
              val remaining = limit.map(l => (l + extMins - used).max(0))
              ProfileTimeSummary(
                p.id,
                p.name,
                date.toString,
                limit,
                used,
                extMins,
                remaining,
              )
            }
          } yield Response
            .json(summaries.toJson)
            .addHeader(cacheControlFor(isTodayMode = !date.isBefore(today)))
        },
      // #777 — weekly variant of the summary endpoint. Single presence query over the trailing
      // 7-day range, per-mac bucket-deduped totals summed per profile.
      Method.GET / "api" / "time" / "status" / "summary" / "week"       ->
        handler { (req: Request) =>
          for {
            claims <- requireAuth(req, auth)
            today  <- clock.today
            toStr = req.url.queryParam("to").getOrElse(today.toString)
            to    = LocalDate.parse(toStr)
            from  = to.minusDays(6)
            allProfiles <- profileRepo.listAll.mapError(ErrorMapper.dbErrorToResponse)
            allDevices  <- deviceRepo.listAll.mapError(ErrorMapper.dbErrorToResponse)
            allLimits   <- timeLimitRepo.listAll.mapError(ErrorMapper.dbErrorToResponse)
            settings    <- hsRepo.get.mapError(ErrorMapper.dbErrorToResponse)
            visible     <- visibleProfiles(claims, allProfiles, userProfileRepo)
            devicesByPid = allDevices.groupBy(_.profileId)
            allMacs      = visible.iterator
              .flatMap(p => devicesByPid.getOrElse(Some(p.id), Nil))
              .map(_.mac)
              .toList
              .distinct
            presence <- (if allMacs.isEmpty then ZIO.succeed(Nil)
                         else trafficRepo.listPresenceRows(allMacs, from, to))
              .mapError(ErrorMapper.dbErrorToResponse)
            limitByPid = allLimits.iterator.map(l => l.profileId -> l.dailyMinutes).toMap
            summaries  = visible.map { p =>
              val devices = devicesByPid.getOrElse(Some(p.id), Nil)
              val macSet  = devices.map(_.mac).toSet
              val pRows   = presence.filter(r => macSet.contains(r.mac))
              val perMac  = wifihaven.api.presence.Presence
                .totalMinutesByMac(pRows, Nil, settings.heartbeatFilter)
              // #751: same Sum/Dedup branch as the daily summary.
              val total   = p.crossDeviceOverlapMode match {
                case CrossDeviceOverlapMode.Sum   =>
                  devices.iterator.map(d => perMac.getOrElse(d.mac, 0)).sum
                case CrossDeviceOverlapMode.Dedup =>
                  wifihaven.api.presence.Presence
                    .dedupedTotalMinutes(pRows, Nil, settings.heartbeatFilter)
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
        },
      Method.GET / "api" / "time" / "status"                            ->
        handler { (req: Request) =>
          for {
            claims <- requireAuth(req, auth)
            today  <- clock.today
            dateStr = req.url.queryParam("date").getOrElse(today.toString)
            date    = LocalDate.parse(dateStr)
            // #795: ?profileId=N narrows the rollup to a single profile so the
            // SPA can fetch one card's worth of data instead of fanning out N
            // sub-rollups. Auth scope still applies — child tokens can only
            // request profiles they're already entitled to see.
            profileIdOpt <- parseProfileIdParam(req)
            allProfiles  <- profileRepo.listAll.mapError(ErrorMapper.dbErrorToResponse)
            allDevices   <- deviceRepo.listAll.mapError(ErrorMapper.dbErrorToResponse)
            settings     <- hsRepo.get.mapError(ErrorMapper.dbErrorToResponse)
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
                      timeLimitRepo,
                      siteTimeLimitRepo,
                      trafficRepo,
                      extRepo,
                      settings.heartbeatFilter,
                    )
                  }
              }
              .mapError(ErrorMapper.dbErrorToResponse)
            _        <- logCacheStatsPeriodically(cache)
          } yield Response
            .json(statuses.toJson)
            .addHeader(cacheControlFor(isTodayMode = !date.isBefore(today)))
        },
      Method.GET / "api" / "time" / "status" / "week"                   ->
        handler { (req: Request) =>
          for {
            claims <- requireAuth(req, auth)
            today  <- clock.today
            // ?to=YYYY-MM-DD anchors the trailing 7-day window; defaults to today (UTC).
            // ?bucketOffsetMin=N sets the minute-past-the-hour where the hourly grid starts,
            // so each bucket falls fully within one local day on the caller's side (#794).
            // Accepts 0/15/30/45; defaults to 0 (whole-hour grid, fine for UTC-aligned zones).
            toStr = req.url.queryParam("to").getOrElse(today.toString)
            to    = LocalDate.parse(toStr)
            from  = to.minusDays(6)
            bucketOffsetMin <- parseBucketOffsetMin(req)
            // #795: same single-profile narrowing as the daily endpoint.
            profileIdOpt    <- parseProfileIdParam(req)
            allProfiles     <- profileRepo.listAll.mapError(ErrorMapper.dbErrorToResponse)
            allDevices      <- deviceRepo.listAll.mapError(ErrorMapper.dbErrorToResponse)
            settings        <- hsRepo.get.mapError(ErrorMapper.dbErrorToResponse)
            visible         <- visibleProfiles(claims, allProfiles, userProfileRepo)
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
              .mapError(ErrorMapper.dbErrorToResponse)
            _        <- logCacheStatsPeriodically(cache)
          } yield Response
            .json(statuses.toJson)
            .addHeader(cacheControlFor(isTodayMode = !to.isBefore(today)))
        },
      Method.GET / "api" / "time" / "status" / string("mac") / "week"   ->
        handler { (mac: String, req: Request) =>
          for {
            claims <- requireAuth(req, auth)
            today  <- clock.today
            toStr = req.url.queryParam("to").getOrElse(today.toString)
            to    = LocalDate.parse(toStr)
            from  = to.minusDays(6)
            bucketOffsetMin <- parseBucketOffsetMin(req)
            device          <- deviceRepo
              .findByMac(MacAddress.unsafe(normalizeMac(mac)))
              .mapError(ErrorMapper.dbErrorToResponse)
              .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("Device not found")))
            _               <- requireProfileReadAccess(claims, device.profileId, userProfileRepo)
            settings        <- hsRepo.get.mapError(ErrorMapper.dbErrorToResponse)
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
              .mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.json(status.toJson)
        },
      Method.GET / "api" / "time" / "status" / string("mac")            ->
        handler { (mac: String, req: Request) =>
          for {
            claims <- requireAuth(req, auth)
            today  <- clock.today
            dateStr = req.url.queryParam("date").getOrElse(today.toString)
            date    = LocalDate.parse(dateStr)
            device   <- deviceRepo
              .findByMac(MacAddress.unsafe(normalizeMac(mac)))
              .mapError(ErrorMapper.dbErrorToResponse)
              .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("Device not found")))
            _        <- requireProfileReadAccess(claims, device.profileId, userProfileRepo)
            settings <- hsRepo.get.mapError(ErrorMapper.dbErrorToResponse)
            status   <- buildDeviceTimeStatus(
              device,
              date,
              profileRepo,
              timeLimitRepo,
              siteTimeLimitRepo,
              trafficRepo,
              extRepo,
              settings.heartbeatFilter,
            )
              .mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.json(status.toJson)
        },
      Method.GET / "api" / "time" / "heartbeat-explain" / string("mac") ->
        handler { (mac: String, req: Request) =>
          // #714: per-row classification of `traffic_reports` rows that feed into
          // Presence.totalSecondsByMac for this device on `date` (default = today).
          // The classification reflects current household_settings — i.e. the same
          // verdict the live screen-time calculation is using right now. Operators
          // tune `heartbeat_bytes_threshold` against this surface before flipping
          // `heartbeat_filter_enabled` on.
          for {
            claims <- requireAuth(req, auth)
            today  <- clock.today
            dateStr = req.url.queryParam("date").getOrElse(today.toString)
            date    = LocalDate.parse(dateStr)
            device   <- deviceRepo
              .findByMac(MacAddress.unsafe(normalizeMac(mac)))
              .mapError(ErrorMapper.dbErrorToResponse)
              .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("Device not found")))
            _        <- requireProfileReadAccess(claims, device.profileId, userProfileRepo)
            settings <- hsRepo.get.mapError(ErrorMapper.dbErrorToResponse)
            rows     <- trafficRepo
              .listPresenceRows(List(device.mac), date)
              .mapError(ErrorMapper.dbErrorToResponse)
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
        },
      Method.POST / "api" / "time" / "extend"                           ->
        handler { (req: Request) =>
          for {
            claims   <- requireWriter(req, auth)
            body     <- req.body.asString.orElseFail(Response.badRequest(""))
            ger      <- ZIO
              .fromEither(body.fromJson[GrantExtensionRequest])
              .mapError(e => Response.badRequest(e))
            _        <- requireProfileAccess(claims, ger.profileId, userProfileRepo)
            // #1010: bucket the grant under the household-local "today" so the
            // policy-snapshot read path (also household-local) finds it.
            settings <- hsRepo.get.mapError(ErrorMapper.dbErrorToResponse)
            now      <- clock.instant
            today = wifihaven.api.policy.PolicyService.householdLocalDate(now, settings)
            id <- extRepo
              .grantForProfile(ger.profileId, today, ger.extraMinutes, claims.sub, ger.note)
              .mapError(ErrorMapper.dbErrorToResponse)
            // #946: bust the cached ProfileTimeStatus for this profile so the SPA's next
            // refetch reflects the new cap immediately instead of waiting up to todayTtl.
            _  <- cache.invalidateProfile(ger.profileId)
          } yield Response.json(s"""{"id":${id.value},"grantedMinutes":${ger.extraMinutes}}""")
        },
      Method.GET / "api" / "time" / "extensions" / long("profileId")    ->
        handler { (profileId: Long, req: Request) =>
          val pid = ProfileId(profileId)
          for {
            claims   <- requireAuth(req, auth)
            _        <- requireProfileAccess(claims, pid, userProfileRepo)
            // #1010: same household-local "today" as the grant path.
            settings <- hsRepo.get.mapError(ErrorMapper.dbErrorToResponse)
            now      <- clock.instant
            date = wifihaven.api.policy.PolicyService.householdLocalDate(now, settings)
            exts <- extRepo
              .listForProfile(pid, date)
              .mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.json(exts.toJson)
        },
    )

  // #802: Cache-Control header derived from whether the response covers today.
  // Today: short max-age so the SPA picks up bucket churn within the cache window.
  // Past:  long max-age — past data is logically immutable.
  // `private` because mutating endpoints (POST /api/time/extend) don't need it and we
  // want to avoid intermediary caching for the JWT-authenticated traffic.
  private def cacheControlFor(isTodayMode: Boolean): Header.CacheControl =
    if isTodayMode then Header.CacheControl.MaxAge(TodayMaxAgeSeconds.toInt)
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

  private def buildProfileTimeStatus(
      profile: Profile,
      devices: List[Device],
      date: LocalDate,
      tlRepo: TimeLimitRepo,
      stlRepo: SiteTimeLimitRepo,
      trafficRepo: TrafficReportRepo,
      extRepo: TimeExtensionRepo,
      heartbeatFilter: HeartbeatFilter,
  ): Task[ProfileTimeStatus] = {
    val macs = devices.map(_.mac)
    for {
      tl       <- tlRepo.findForProfile(profile.id)
      stls     <- stlRepo.listForProfile(profile.id)
      presence <- trafficRepo.listPresenceRows(macs, date)
      extMins  <- extRepo.getProfileTotalExtension(profile.id, date)
      // Only exempt site domains are excluded from the daily total.
      // Included sites (exemptFromDaily=false) count against the daily cap.
      exemptPats      = stls.filter(_.exemptFromDaily).map(_.domainPattern)
      perMacTotal     = wifihaven.api.presence.Presence
        .totalMinutesByMac(presence, exemptPats, heartbeatFilter)
      perMacPat       = wifihaven.api.presence.Presence
        .patternMinutesByMac(presence, stls.map(_.domainPattern))
      // #751: in Dedup mode the profile total unions per-device active buckets
      // (one bucket → one minute, regardless of how many of this profile's
      // devices were active in it). In Sum mode we keep the historical
      // behaviour: per-device totals added.
      totalUsed       = profile.crossDeviceOverlapMode match {
        case CrossDeviceOverlapMode.Sum   =>
          devices.iterator.map(d => perMacTotal.getOrElse(d.mac, 0)).sum
        case CrossDeviceOverlapMode.Dedup =>
          wifihaven.api.presence.Presence
            .dedupedTotalMinutes(presence, exemptPats, heartbeatFilter)
      }
      remaining       = tl.map(l => (l.dailyMinutes + extMins - totalUsed).max(0))
      siteUsage       = stls.map { stl =>
        val used = devices.iterator.map(d => perMacPat.getOrElse((d.mac, stl.domainPattern), 0)).sum
        SiteUsage(
          stl.label,
          stl.domainPattern,
          stl.dailyMinutes,
          used,
          (stl.dailyMinutes - used).max(0),
        )
      }
      deviceSummaries = devices.map { d =>
        DeviceUsageSummary(d.mac, d.name, perMacTotal.getOrElse(d.mac, 0))
      }
      // #262 — top-N host attribution across all profile devices for the day.
      // `usedMins` is bucket-presence and `proportionalMins` is the #715
      // byte-share-weighted attribution; UI defaults to the latter. Hosts
      // with zero presence are dropped so the top-10 list isn't padded by
      // hosts that exist only because the bucket touched them at all.
      hostUsage       = {
        val presenceMins = wifihaven.api.presence.Presence.hostMinutes(presence)
        val proportional = wifihaven.api.presence.Presence.proportionalHostMinutes(presence)
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
      date.toString,
      tl.map(_.dailyMinutes),
      totalUsed,
      extMins,
      remaining,
      siteUsage,
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
        val presenceMins = wifihaven.api.presence.Presence.hostMinutes(presence)
        val proportional = wifihaven.api.presence.Presence.proportionalHostMinutes(presence)
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
        val presenceMins = wifihaven.api.presence.Presence.hostMinutes(presence)
        val proportional = wifihaven.api.presence.Presence.proportionalHostMinutes(presence)
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
  private def parseBucketOffsetMin(req: Request): IO[Response, Int] =
    req.url.queryParam("bucketOffsetMin") match {
      case None      => ZIO.succeed(0)
      case Some(raw) =>
        raw.toIntOption match {
          case Some(n) if Set(0, 15, 30, 45).contains(n) => ZIO.succeed(n)
          case _                                         =>
            ZIO.fail(Response.badRequest(s"bucketOffsetMin must be one of 0/15/30/45, got: $raw"))
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

  private def buildDeviceTimeStatus(
      device: Device,
      date: LocalDate,
      profileRepo: ProfileRepo,
      tlRepo: TimeLimitRepo,
      stlRepo: SiteTimeLimitRepo,
      trafficRepo: TrafficReportRepo,
      extRepo: TimeExtensionRepo,
      heartbeatFilter: HeartbeatFilter,
  ): Task[DeviceTimeStatus] = {
    val pid = device.profileId
    for {
      tl       <- pid.fold(ZIO.succeed(Option.empty[TimeLimit]))(tlRepo.findForProfile)
      stls     <- pid.fold(ZIO.succeed(List.empty[SiteTimeLimit]))(stlRepo.listForProfile)
      presence <- trafficRepo.listPresenceRows(List(device.mac), date)
      extMins  <- pid.fold(ZIO.succeed(0))(extRepo.getProfileTotalExtension(_, date))
      profile  <- pid.fold(ZIO.succeed("No profile"))(p =>
        profileRepo.findById(p).map(_.map(_.name).getOrElse("Unknown")),
      )
      // Only exempt site domains are excluded from the daily total.
      // Included sites (exemptFromDaily=false) count against the daily cap.
      exemptPats = stls.filter(_.exemptFromDaily).map(_.domainPattern)
      totalUsed  = wifihaven.api.presence.Presence
        .totalMinutesByMac(presence, exemptPats, heartbeatFilter)
        .getOrElse(device.mac, 0)
      perPat     = wifihaven.api.presence.Presence
        .patternMinutesByMac(presence, stls.map(_.domainPattern))
      remaining  = tl.map(l => (l.dailyMinutes + extMins - totalUsed).max(0))
      siteUsage  = stls.map { stl =>
        val used = perPat.getOrElse((device.mac, stl.domainPattern), 0)
        SiteUsage(
          stl.label,
          stl.domainPattern,
          stl.dailyMinutes,
          used,
          (stl.dailyMinutes - used).max(0),
        )
      }
    } yield DeviceTimeStatus(
      device.mac,
      device.name,
      date.toString,
      profile,
      pid,
      tl.map(_.dailyMinutes),
      totalUsed,
      extMins,
      remaining,
      siteUsage,
    )
  }

}

// ── Query log routes ───────────────────────────────────────────────────────

object LogRoutes {
  // #862: tiny parse helpers for pagination params. Pulled into top-level so
  // both /api/logs and /api/connection-events/series use the same shape.
  private def parseInstantOpt(req: Request, name: String): IO[Response, Option[java.time.Instant]] =
    req.url.queryParam(name) match {
      case None    => ZIO.succeed(None)
      case Some(s) =>
        ZIO
          .attempt(Some(java.time.Instant.parse(s)))
          .orElseFail(Response.badRequest(s"invalid $name: $s"))
    }

  private def parseLimit(req: Request, default: Int, max: Int): IO[Response, Int] =
    req.url.queryParam("limit") match {
      case None    => ZIO.succeed(default)
      case Some(s) =>
        s.toIntOption match {
          case None    => ZIO.fail(Response.badRequest(s"invalid limit: $s"))
          case Some(n) =>
            if (n < 1) ZIO.fail(Response.badRequest("limit must be >= 1"))
            else if (n > max) ZIO.fail(Response.badRequest(s"limit must be <= $max"))
            else ZIO.succeed(n)
        }
    }

  private def parseLogCursor(req: Request): IO[Response, Option[Cursor.LogCursor]] =
    req.url.queryParam("cursor") match {
      case None    => ZIO.succeed(None)
      case Some(s) =>
        ZIO
          .fromEither(Cursor.decode[Cursor.LogCursor](s))
          .mapBoth(Response.badRequest, Some(_))
    }

  private def parseAggCursor(req: Request): IO[Response, Option[Cursor.AggCursor]] =
    req.url.queryParam("cursor") match {
      case None    => ZIO.succeed(None)
      case Some(s) =>
        ZIO
          .fromEither(Cursor.decode[Cursor.AggCursor](s))
          .mapBoth(Response.badRequest, Some(_))
    }

  // #862: must mirror the SQL's group_key concatenation order exactly
  // (domain, device, profile — only requested columns, US-separator).
  private def aggGroupKey(r: ConnectionEventAggRow): String = {
    val sep = ""
    List("domain", "device", "profile").iterator
      .flatMap(k => r.groups.get(k))
      .mkString(sep)
  }

  def routes(
      auth: AuthService,
      connRepo: ConnectionEventRepo,
      userProfileRepo: UserProfileRepo,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "logs"                         ->
        handler { (req: Request) =>
          for {
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
            )
            logs    <- connRepo.query(filter).mapError(ErrorMapper.dbErrorToResponse)
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
        },
      // #847: aggregated connection-event series. bucket+groupBy required; only
      // domain grouping is implemented (apex needs PSL #849, app needs apps
      // track #761-#769). Filters mirror /api/logs.
      Method.GET / "api" / "connection-events" / "series" ->
        handler { (req: Request) =>
          for {
            claims <- requireAuth(req, auth)
            // Aggregated rows don't carry per-row profile_id, so we can't
            // post-filter the way /api/logs does. Restrict to admin/adult;
            // children can still use /api/logs raw view with a profileId
            // filter that goes through filterLogs.
            _      <- ZIO
              .fail(Response.forbidden("aggregated view requires admin or adult role"))
              .when(claims.role != "admin" && claims.role != "adult")
            bktStr <- ZIO
              .fromOption(req.url.queryParam("bucket"))
              .orElseFail(Response.badRequest("bucket query parameter required"))
            bucket <- ZIO
              .fromOption(ConnectionEventBucket.fromWire(bktStr))
              .orElseFail(Response.badRequest(s"unknown bucket: $bktStr"))
            _      <- ZIO
              .fail(Response.badRequest("bucket=off not supported on /series — use /api/logs"))
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
                  .orElseFail(Response.badRequest(s"unknown groupBy: $s"))
              }
              .map(_.toSet)
            _      <- ZIO
              .fail(Response.badRequest("groupBy=apex not implemented — see #856 (needs PSL)"))
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
            rows <- connRepo
              .querySeries(filter, bucket.seconds, groupByCodes)
              .mapError(ErrorMapper.dbErrorToResponse)
            nextCur =
              if (rows.size < limit) None
              else
                rows.lastOption.map(r =>
                  Cursor.encode(Cursor.AggCursor(r.windowStart, aggGroupKey(r))),
                )
          } yield Response.json(ConnectionEventSeriesPage(rows, nextCur).toJson)
        },
      Method.GET / "api" / "stats"                        ->
        handler { (req: Request) =>
          requireAdmin(req, auth) *>
            connRepo.stats
              .map(s => Response.json(s.toJson))
              .mapError(ErrorMapper.dbErrorToResponse)
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
          requireAdmin(req, auth) *>
            blRepo.summaries
              .map(rs => Response.json(rs.toJson))
              .mapError(ErrorMapper.dbErrorToResponse)
        },
      // #958: paginated host list for the "View hosts" disclosure on the
      // SPA page. Returns a JSON object `{ id, hosts: [...] }`. Admin-
      // only; routers use the unrelated GET /api/blocklists/<id> route
      // (RouterRoutes) which returns the plain-text list with ETag.
      Method.GET / "api" / "blocklists" / string("id") / "hosts"        ->
        handler { (id: String, req: Request) =>
          requireAdmin(req, auth) *>
            ZIO
              .fromEither(BlocklistId.parse(id))
              .mapError(e => Response.badRequest(e))
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
                  .mapError(ErrorMapper.dbErrorToResponse),
              )
        },
      Method.POST / "api" / "blocklists" / string("category") / "clear" ->
        handler { (cat: String, req: Request) =>
          requireAdmin(req, auth) *>
            blRepo.clearCategory(BlocklistId.unsafe(cat)).mapError(ErrorMapper.dbErrorToResponse) *>
            ZIO.succeed(Response.ok)
        },
      // #958: trigger an out-of-band re-fetch + re-seed of a bundled list. Returns
      // 200 {refreshedHosts:N} on success, 404 if the id isn't a bundled list, or
      // 502 if the upstream fetch failed (existing DB rows are kept).
      Method.POST / "api" / "blocklists" / string("id") / "refresh"     ->
        handler { (id: String, req: Request) =>
          for {
            _   <- requireAdmin(req, auth)
            bid <- ZIO.fromEither(BlocklistId.parse(id)).mapError(e => Response.badRequest(e))
            b   <- ZIO
              .fromOption(bundled.get(bid))
              .orElseFail(
                Response
                  .status(Status.NotFound)
                  .copy(body = Body.fromString(s"""{"error":"unknown bundled blocklist '$id'"}""")),
              )
            n   <- wifihaven.api.BundledBlocklists
              .refresh(blRepo, cache, fetcher, b)
              .mapError(ErrorMapper.dbErrorToResponse)
          } yield n match {
            case Some(count) => Response.json(s"""{"refreshedHosts":$count}""")
            case None        =>
              Response
                .status(Status.BadGateway)
                .copy(body =
                  Body.fromString("""{"error":"upstream fetch failed; rows unchanged"}"""),
                )
          }
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
          for {
            _ <- requireAuth(req, auth)
            s <- repo.get.mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.json(s.toJson)
        },
      Method.PUT / "api" / "household" / "settings"   ->
        handler { (req: Request) =>
          for {
            _    <- requireAdmin(req, auth)
            body <- req.body.asString.orElseFail(Response.badRequest(""))
            upd  <- ZIO
              .fromEither(body.fromJson[UpdateHouseholdSettingsRequest])
              .mapError(e => Response.badRequest(e))
            _    <- ZIO
              .fromEither(validateUnmanagedMacPolicy(upd.unmanagedMacPolicy))
              .mapError(Response.badRequest(_))
            _    <- repo
              .update(
                HouseholdSettings(
                  upd.dailyResetTime,
                  upd.dailyResetTz,
                  upd.heartbeatFilter,
                  upd.unmanagedMacPolicy,
                ),
              )
              .mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.ok
        },
      // #998: field-scoped partial update. Body is a subset of HouseholdSettings.
      // Top-level fields (dailyResetTime, dailyResetTz) follow standard PATCH
      // semantics. `heartbeatFilter` is deep-merged when present so the SPA can
      // autosave a single inner toggle without resending the threshold and
      // patterns alongside it.
      Method.PATCH / "api" / "household" / "settings" ->
        handler { (req: Request) =>
          for {
            _         <- requireAdmin(req, auth)
            existing  <- repo.get.mapError(ErrorMapper.dbErrorToResponse)
            body      <- req.body.asString.orElseFail(Response.badRequest(""))
            obj       <- ZIO.fromEither(FieldPatch.parseObj(body)).mapError(Response.badRequest(_))
            timePatch <- ZIO
              .fromEither(FieldPatch.from[LocalTime](obj, "dailyResetTime"))
              .mapError(Response.badRequest(_))
            tzPatch   <- ZIO
              .fromEither(FieldPatch.from[ZoneId](obj, "dailyResetTz"))
              .mapError(Response.badRequest(_))
            _         <- timePatch match {
              case FieldPatch.Cleared =>
                ZIO.fail(Response.badRequest("dailyResetTime cannot be cleared"))
              case _                  => ZIO.unit
            }
            _         <- tzPatch match {
              case FieldPatch.Cleared =>
                ZIO.fail(Response.badRequest("dailyResetTz cannot be cleared"))
              case _                  => ZIO.unit
            }
            mergedFilter <- obj.get("heartbeatFilter") match {
              case None              => ZIO.succeed(existing.heartbeatFilter)
              case Some(Json.Null)   =>
                ZIO.fail(Response.badRequest("heartbeatFilter cannot be cleared"))
              case Some(j: Json.Obj) => mergeHeartbeatFilter(existing.heartbeatFilter, j)
              case Some(_)           =>
                ZIO.fail(Response.badRequest("heartbeatFilter must be a JSON object"))
            }
            mergedUmm    <- obj.get("unmanagedMacPolicy") match {
              case None              => ZIO.succeed(existing.unmanagedMacPolicy)
              case Some(Json.Null)   =>
                ZIO.fail(Response.badRequest("unmanagedMacPolicy cannot be cleared"))
              case Some(j: Json.Obj) => mergeUnmanagedMacPolicy(existing.unmanagedMacPolicy, j)
              case Some(_)           =>
                ZIO.fail(Response.badRequest("unmanagedMacPolicy must be a JSON object"))
            }
            merged = HouseholdSettings(
              dailyResetTime = timePatch.applyTo(existing.dailyResetTime),
              dailyResetTz = tzPatch.applyTo(existing.dailyResetTz),
              heartbeatFilter = mergedFilter,
              unmanagedMacPolicy = mergedUmm,
            )
            _            <- repo.update(merged).mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.ok
        },
    )

  private def mergeHeartbeatFilter(
      existing: HeartbeatFilter,
      obj: Json.Obj,
  ): IO[Response, HeartbeatFilter] =
    for {
      enabledP <- ZIO
        .fromEither(FieldPatch.from[Boolean](obj, "enabled"))
        .mapError(Response.badRequest(_))
      bytesP   <- ZIO
        .fromEither(FieldPatch.from[Int](obj, "bytesThreshold"))
        .mapError(Response.badRequest(_))
      hostsP   <- ZIO
        .fromEither(FieldPatch.from[List[String]](obj, "heartbeatHostPatterns"))
        .mapError(Response.badRequest(_))
      _        <- (enabledP, bytesP, hostsP) match {
        case (FieldPatch.Cleared, _, _) =>
          ZIO.fail(Response.badRequest("heartbeatFilter.enabled cannot be cleared"))
        case (_, FieldPatch.Cleared, _) =>
          ZIO.fail(Response.badRequest("heartbeatFilter.bytesThreshold cannot be cleared"))
        case (_, _, FieldPatch.Cleared) =>
          ZIO.fail(Response.badRequest("heartbeatFilter.heartbeatHostPatterns cannot be cleared"))
        case _                          => ZIO.unit
      }
    } yield HeartbeatFilter(
      enabled = enabledP.applyTo(existing.enabled),
      bytesThreshold = bytesP.applyTo(existing.bytesThreshold),
      heartbeatHostPatterns = hostsP.applyTo(existing.heartbeatHostPatterns),
    )

  private def mergeUnmanagedMacPolicy(
      existing: UnmanagedMacPolicy,
      obj: Json.Obj,
  ): IO[Response, UnmanagedMacPolicy] =
    for {
      policyP    <- ZIO
        .fromEither(FieldPatch.from[String](obj, "policy"))
        .mapError(Response.badRequest(_))
      blockPageP <- ZIO
        .fromEither(FieldPatch.from[Boolean](obj, "blockPage"))
        .mapError(Response.badRequest(_))
      _          <- (policyP, blockPageP) match {
        case (FieldPatch.Cleared, _) =>
          ZIO.fail(Response.badRequest("unmanagedMacPolicy.policy cannot be cleared"))
        case (_, FieldPatch.Cleared) =>
          ZIO.fail(Response.badRequest("unmanagedMacPolicy.blockPage cannot be cleared"))
        case _                       => ZIO.unit
      }
      merged = UnmanagedMacPolicy(
        policy = policyP.applyTo(existing.policy),
        blockPage = blockPageP.applyTo(existing.blockPage),
      )
      _ <- ZIO.fromEither(validateUnmanagedMacPolicy(merged)).mapError(Response.badRequest(_))
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

def normalizeMac(mac: String): String =
  mac.toLowerCase.replace("-", ":").trim

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
