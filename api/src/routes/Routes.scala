package familydns.api.routes

import familydns.api.auth.*
import familydns.api.db.*
import familydns.shared.*
import familydns.shared.types.*
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*

import java.time.LocalDate

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
            claims <- requireAuth(req, auth)
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
    )
}

// ── Profile routes ─────────────────────────────────────────────────────────

object ProfileRoutes {
  def routes(
      auth: AuthService,
      profileRepo: ProfileRepo,
      scheduleRepo: ScheduleRepo,
      timeLimitRepo: TimeLimitRepo,
      siteTimeLimitRepo: SiteTimeLimitRepo,
      userProfileRepo: UserProfileRepo,
      userRepo: UserRepo,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "profiles"                         ->
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
                  stls   <- siteTimeLimitRepo.listForProfile(p.id)
                } yield ProfileDetail(p, scheds, tl, stls)
              }
              .mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.json(details.toJson)
        },
      Method.GET / "api" / "profiles" / long("id")            ->
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
            stls   <- siteTimeLimitRepo
              .listForProfile(pid)
              .mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.json(ProfileDetail(p, scheds, tl, stls).toJson)
        },
      Method.POST / "api" / "profiles"                        ->
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
                  upr.extraBlocked,
                  upr.extraAllowed,
                  upr.paused,
                  // #311: missing failureMode → Closed (fail-safe).
                  upr.failureMode.getOrElse(FailureMode.Closed),
                ),
              )
              .mapError(ErrorMapper.dbErrorToResponse)
            _    <- scheduleRepo
              .replaceForProfile(id, upr.schedules)
              .mapError(ErrorMapper.dbErrorToResponse)
            _    <- ZIO
              .foreachDiscard(upr.timeLimit)(mins => timeLimitRepo.upsert(id, mins))
              .mapError(ErrorMapper.dbErrorToResponse)
            _    <- siteTimeLimitRepo
              .replaceForProfile(id, upr.siteTimeLimits)
              .mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.json(s"""{"id":${id.value}}""")
        },
      Method.PUT / "api" / "profiles" / long("id")            ->
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
                  extraBlocked = upr.extraBlocked,
                  extraAllowed = upr.extraAllowed,
                  paused = upr.paused,
                  // #311: if the caller omits failureMode, preserve the
                  // existing value rather than resetting to Closed.
                  failureMode = upr.failureMode.getOrElse(p.failureMode),
                ),
              )
              .mapError(ErrorMapper.dbErrorToResponse)
            _      <- scheduleRepo
              .replaceForProfile(pid, upr.schedules)
              .mapError(ErrorMapper.dbErrorToResponse)
            _      <- (upr.timeLimit match {
              case Some(mins) => timeLimitRepo.upsert(pid, mins)
              case None       => timeLimitRepo.delete(pid)
            }).mapError(ErrorMapper.dbErrorToResponse)
            _      <- siteTimeLimitRepo
              .replaceForProfile(pid, upr.siteTimeLimits)
              .mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.ok
        },
      Method.DELETE / "api" / "profiles" / long("id")         ->
        handler { (id: Long, req: Request) =>
          requireAdmin(req, auth) *>
            profileRepo.delete(ProfileId(id)).mapError(ErrorMapper.dbErrorToResponse) *>
            ZIO.succeed(Response.ok)
        },
      Method.GET / "api" / "profiles" / long("id") / "users"  ->
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
      Method.PUT / "api" / "profiles" / long("id") / "users"  ->
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
      Method.POST / "api" / "profiles" / long("id") / "pause" ->
        handler { (id: Long, req: Request) =>
          val pid = ProfileId(id)
          for {
            claims <- requireWriter(req, auth)
            _      <- requireProfileAccess(claims, pid, userProfileRepo)
            p      <- profileRepo
              .findById(pid)
              .mapError(ErrorMapper.dbErrorToResponse)
              .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("")))
            _      <-
              profileRepo.setPaused(pid, !p.paused).mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.json(s"""{"paused":${!p.paused}}""")
        },
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
            _      <- requireProfileAccess(claims, udr.profileId, userProfileRepo)
            mac = MacAddress.unsafe(normalizeMac(udr.mac.value))
            id <- deviceRepo
              .upsert(mac, udr.name, udr.profileId, "")
              .mapError(ErrorMapper.dbErrorToResponse)
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
          } yield Response.ok
        },
    )
}

// ── Time routes ────────────────────────────────────────────────────────────

object TimeRoutes {
  def routes(
      auth: AuthService,
      deviceRepo: DeviceRepo,
      timeLimitRepo: TimeLimitRepo,
      siteTimeLimitRepo: SiteTimeLimitRepo,
      trafficRepo: TrafficReportRepo,
      extRepo: TimeExtensionRepo,
      profileRepo: ProfileRepo,
      userProfileRepo: UserProfileRepo,
      clock: Clock,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "time" / "status"                         ->
        handler { (req: Request) =>
          for {
            claims <- requireAuth(req, auth)
            today  <- clock.today
            dateStr = req.url.queryParam("date").getOrElse(today.toString)
            date    = LocalDate.parse(dateStr)
            allProfiles <- profileRepo.listAll.mapError(ErrorMapper.dbErrorToResponse)
            allDevices  <- deviceRepo.listAll.mapError(ErrorMapper.dbErrorToResponse)
            visible     <- visibleProfiles(claims, allProfiles, userProfileRepo)
            devicesByPid = allDevices.groupBy(_.profileId)
            statuses <- ZIO
              .foreach(visible) { p =>
                buildProfileTimeStatus(
                  p,
                  devicesByPid.getOrElse(Some(p.id), Nil),
                  date,
                  timeLimitRepo,
                  siteTimeLimitRepo,
                  trafficRepo,
                  extRepo,
                )
              }
              .mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.json(statuses.toJson)
        },
      Method.GET / "api" / "time" / "status" / string("mac")         ->
        handler { (mac: String, req: Request) =>
          for {
            claims <- requireAuth(req, auth)
            today  <- clock.today
            dateStr = req.url.queryParam("date").getOrElse(today.toString)
            date    = LocalDate.parse(dateStr)
            device <- deviceRepo
              .findByMac(MacAddress.unsafe(normalizeMac(mac)))
              .mapError(ErrorMapper.dbErrorToResponse)
              .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("Device not found")))
            _      <- requireProfileReadAccess(claims, device.profileId, userProfileRepo)
            status <- buildDeviceTimeStatus(
              device,
              date,
              profileRepo,
              timeLimitRepo,
              siteTimeLimitRepo,
              trafficRepo,
              extRepo,
            )
              .mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.json(status.toJson)
        },
      Method.POST / "api" / "time" / "extend"                        ->
        handler { (req: Request) =>
          for {
            claims <- requireWriter(req, auth)
            body   <- req.body.asString.orElseFail(Response.badRequest(""))
            ger    <- ZIO
              .fromEither(body.fromJson[GrantExtensionRequest])
              .mapError(e => Response.badRequest(e))
            _      <- requireProfileAccess(claims, ger.profileId, userProfileRepo)
            today  <- clock.today
            id     <- extRepo
              .grantForProfile(ger.profileId, today, ger.extraMinutes, claims.sub, ger.note)
              .mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.json(s"""{"id":${id.value},"grantedMinutes":${ger.extraMinutes}}""")
        },
      Method.GET / "api" / "time" / "extensions" / long("profileId") ->
        handler { (profileId: Long, req: Request) =>
          val pid = ProfileId(profileId)
          for {
            claims <- requireAuth(req, auth)
            _      <- requireProfileAccess(claims, pid, userProfileRepo)
            date   <- clock.today
            exts   <- extRepo
              .listForProfile(pid, date)
              .mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.json(exts.toJson)
        },
    )

  private def buildProfileTimeStatus(
      profile: Profile,
      devices: List[Device],
      date: LocalDate,
      tlRepo: TimeLimitRepo,
      stlRepo: SiteTimeLimitRepo,
      trafficRepo: TrafficReportRepo,
      extRepo: TimeExtensionRepo,
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
      perMacTotal     = familydns.api.presence.Presence
        .totalMinutesByMac(presence, exemptPats)
      perMacPat       = familydns.api.presence.Presence
        .patternMinutesByMac(presence, stls.map(_.domainPattern))
      totalUsed       = devices.iterator.map(d => perMacTotal.getOrElse(d.mac, 0)).sum
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
    )
  }

  private def buildDeviceTimeStatus(
      device: Device,
      date: LocalDate,
      profileRepo: ProfileRepo,
      tlRepo: TimeLimitRepo,
      stlRepo: SiteTimeLimitRepo,
      trafficRepo: TrafficReportRepo,
      extRepo: TimeExtensionRepo,
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
      totalUsed  = familydns.api.presence.Presence
        .totalMinutesByMac(presence, exemptPats)
        .getOrElse(device.mac, 0)
      perPat     = familydns.api.presence.Presence
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
  def routes(
      auth: AuthService,
      connRepo: ConnectionEventRepo,
      userProfileRepo: UserProfileRepo,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "logs"  ->
        handler { (req: Request) =>
          for {
            claims <- requireAuth(req, auth)
            filter = LogFilter(
              mac = req.url.queryParam("mac"),
              blocked = req.url.queryParam("blocked").map(_ == "true"),
              domain = req.url.queryParam("domain"),
              location = req.url.queryParam("location"),
              hours = req.url.queryParam("hours").flatMap(_.toIntOption).getOrElse(24),
              limit = req.url.queryParam("limit").flatMap(_.toIntOption).getOrElse(200),
              offset = req.url.queryParam("offset").flatMap(_.toIntOption).getOrElse(0),
            )
            logs    <- connRepo.query(filter).mapError(ErrorMapper.dbErrorToResponse)
            visible <- filterLogs(claims, logs, userProfileRepo)
          } yield Response.json(visible.toJson)
        },
      Method.GET / "api" / "stats" ->
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
  def routes(auth: AuthService, blRepo: BlocklistRepo): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "blocklists"                                 ->
        handler { (req: Request) =>
          requireAdmin(req, auth) *>
            blRepo.countByCategory
              .map(cs =>
                Response.json(
                  cs.map((c, n) => s"""{"category":"$c","count":$n}""").mkString("[", ",", "]"),
                ),
              )
              .mapError(ErrorMapper.dbErrorToResponse)
        },
      Method.POST / "api" / "blocklists" / string("category") / "clear" ->
        handler { (cat: String, req: Request) =>
          requireAdmin(req, auth) *>
            blRepo.clearCategory(BlocklistId.unsafe(cat)).mapError(ErrorMapper.dbErrorToResponse) *>
            ZIO.succeed(Response.ok)
        },
    )
}

// ── Helpers ────────────────────────────────────────────────────────────────

private def bearerToken(req: Request): Option[String] =
  req.header(Header.Authorization).flatMap { h =>
    val v = h.renderedValue
    if v.startsWith("Bearer ") then Some(v.drop(7)) else None
  }

def requireAuth(req: Request, auth: AuthService): IO[Response, JwtClaims] =
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

def requireAdmin(req: Request, auth: AuthService): IO[Response, JwtClaims] =
  ZIO
    .fromOption(bearerToken(req))
    .orElseFail(Response.unauthorized("Missing token"))
    .flatMap(t =>
      auth
        .requireAdmin(t)
        .mapError {
          case AuthError.Forbidden    => Response.forbidden("Admin required")
          case AuthError.TokenExpired => Response.unauthorized("Token expired")
          case _                      => Response.unauthorized("Invalid token")
        },
    )

def requireWriter(req: Request, auth: AuthService): IO[Response, JwtClaims] =
  ZIO
    .fromOption(bearerToken(req))
    .orElseFail(Response.unauthorized("Missing token"))
    .flatMap(t =>
      auth
        .requireWriter(t)
        .mapError {
          case AuthError.Forbidden    => Response.forbidden("Adult or admin required")
          case AuthError.TokenExpired => Response.unauthorized("Token expired")
          case _                      => Response.unauthorized("Invalid token")
        },
    )

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
