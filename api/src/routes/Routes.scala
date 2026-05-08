package familydns.api.routes

import familydns.api.auth.*
import familydns.api.db.*
import familydns.shared.*
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*

import java.time.LocalDate

// ── Auth routes ────────────────────────────────────────────────────────────

object AuthRoutes:
  def routes(
      auth: AuthService,
      userRepo: UserRepo,
      userProfileRepo: UserProfileRepo,
  ): Routes[Any, Response] =
    Routes(
      Method.POST / "api" / "auth" / "login"                 ->
        handler { (req: Request) =>
          for
            body <- req.body.asString.mapError(e => Response.internalServerError(e.getMessage))
            lr   <- ZIO
              .fromEither(body.fromJson[LoginRequest])
              .mapError(e => Response.badRequest(e))
            resp <- auth
              .login(lr.username, lr.password)
              .mapError {
                case AuthError.InvalidCredentials => Response.unauthorized("Invalid credentials")
                case e                            => Response.internalServerError(e.toString)
              }
          yield Response.json(resp.toJson)
        },
      Method.POST / "api" / "auth" / "change-password"       ->
        handler { (req: Request) =>
          for
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
                case e                            => Response.internalServerError(e.toString)
              }
          yield Response.ok
        },
      Method.GET / "api" / "me"                              ->
        handler { (req: Request) =>
          for
            claims <- requireAuth(req, auth)
            pids   <- userProfileRepo
              .listProfilesForUsername(claims.sub)
              .orElseFail(Response.internalServerError(""))
          yield Response.json(MeResponse(claims.sub, claims.role, pids).toJson)
        },
      Method.POST / "api" / "users"                          ->
        handler { (req: Request) =>
          for
            _    <- requireAdmin(req, auth)
            body <- req.body.asString.orElseFail(Response.badRequest(""))
            cur  <- ZIO
              .fromEither(body.fromJson[CreateUserRequest])
              .mapError(e => Response.badRequest(e))
            _    <- ZIO
              .fail(Response.badRequest("invalid role"))
              .when(UserRole.parse(cur.role).isEmpty)
            hash <- auth.hashPassword(cur.password)
            id   <- userRepo
              .create(cur.username, hash, cur.role.toLowerCase)
              .orElseFail(Response.internalServerError(""))
            _    <- userProfileRepo
              .setProfilesForUser(id, cur.profileIds)
              .orElseFail(Response.internalServerError(""))
          yield Response.json(s"""{"id":$id}""")
        },
      Method.GET / "api" / "users"                           ->
        handler { (req: Request) =>
          for
            _        <- requireAdmin(req, auth)
            users    <- userRepo.listAll.orElseFail(Response.internalServerError(""))
            mappings <- userProfileRepo.listAllMappings.orElseFail(Response.internalServerError(""))
            byUser    = mappings.groupBy(_._1).view.mapValues(_.map(_._2)).toMap
            summaries = users.map(u =>
              UserSummary(u.id, u.username, u.role, byUser.getOrElse(u.id, Nil)),
            )
          yield Response.json(summaries.toJson)
        },
      Method.PUT / "api" / "users" / long("id") / "profiles" ->
        handler { (id: Long, req: Request) =>
          for
            _    <- requireAdmin(req, auth)
            body <- req.body.asString.orElseFail(Response.badRequest(""))
            r    <- ZIO
              .fromEither(body.fromJson[SetUserProfilesRequest])
              .mapError(e => Response.badRequest(e))
            _    <- userProfileRepo
              .setProfilesForUser(id, r.profileIds)
              .orElseFail(Response.internalServerError(""))
          yield Response.ok
        },
      Method.DELETE / "api" / "users" / long("id")           ->
        handler { (id: Long, req: Request) =>
          requireAdmin(req, auth) *>
            userRepo.delete(id).orElseFail(Response.internalServerError("")) *>
            ZIO.succeed(Response.ok)
        },
    )

// ── Profile routes ─────────────────────────────────────────────────────────

object ProfileRoutes:
  def routes(
      auth: AuthService,
      profileRepo: ProfileRepo,
      scheduleRepo: ScheduleRepo,
      timeLimitRepo: TimeLimitRepo,
      siteTimeLimitRepo: SiteTimeLimitRepo,
      userProfileRepo: UserProfileRepo,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "profiles"                         ->
        handler { (req: Request) =>
          for
            claims      <- requireAuth(req, auth)
            allProfiles <- profileRepo.listAll.orElseFail(Response.internalServerError(""))
            visible     <- visibleProfiles(claims, allProfiles, userProfileRepo)
            details     <- ZIO
              .foreach(visible) { p =>
                for
                  scheds <- scheduleRepo.listForProfile(p.id)
                  tl     <- timeLimitRepo.findForProfile(p.id)
                  stls   <- siteTimeLimitRepo.listForProfile(p.id)
                yield ProfileDetail(p, scheds, tl, stls)
              }
              .orElseFail(Response.internalServerError(""))
          yield Response.json(details.toJson)
        },
      Method.GET / "api" / "profiles" / long("id")            ->
        handler { (id: Long, req: Request) =>
          for
            claims <- requireAuth(req, auth)
            _      <- requireProfileReadAccess(claims, id, userProfileRepo)
            p      <- profileRepo
              .findById(id)
              .orElseFail(Response.internalServerError(""))
              .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("Profile not found")))
            scheds <- scheduleRepo.listForProfile(id).orElseFail(Response.internalServerError(""))
            tl     <- timeLimitRepo.findForProfile(id).orElseFail(Response.internalServerError(""))
            stls   <- siteTimeLimitRepo
              .listForProfile(id)
              .orElseFail(Response.internalServerError(""))
          yield Response.json(ProfileDetail(p, scheds, tl, stls).toJson)
        },
      Method.POST / "api" / "profiles"                        ->
        handler { (req: Request) =>
          for
            _    <- requireAdmin(req, auth)
            body <- req.body.asString.orElseFail(Response.badRequest(""))
            upr  <- ZIO
              .fromEither(body.fromJson[UpsertProfileRequest])
              .mapError(e => Response.badRequest(e))
            id   <- profileRepo
              .create(upr.name, upr.blockedCategories)
              .orElseFail(Response.internalServerError(""))
            _    <- profileRepo
              .update(
                Profile(
                  id,
                  upr.name,
                  upr.blockedCategories,
                  upr.extraBlocked,
                  upr.extraAllowed,
                  upr.paused,
                ),
              )
              .orElseFail(Response.internalServerError(""))
            _    <- scheduleRepo
              .replaceForProfile(id, upr.schedules)
              .orElseFail(Response.internalServerError(""))
            _    <- ZIO
              .foreachDiscard(upr.timeLimit)(mins => timeLimitRepo.upsert(id, mins))
              .orElseFail(Response.internalServerError(""))
            _    <- siteTimeLimitRepo
              .replaceForProfile(id, upr.siteTimeLimits)
              .orElseFail(Response.internalServerError(""))
          yield Response.json(s"""{"id":$id}""")
        },
      Method.PUT / "api" / "profiles" / long("id")            ->
        handler { (id: Long, req: Request) =>
          for
            claims <- requireWriter(req, auth)
            _      <- requireProfileAccess(claims, id, userProfileRepo)
            body   <- req.body.asString.orElseFail(Response.badRequest(""))
            upr    <- ZIO
              .fromEither(body.fromJson[UpsertProfileRequest])
              .mapError(e => Response.badRequest(e))
            p      <- profileRepo
              .findById(id)
              .orElseFail(Response.internalServerError(""))
              .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("Profile not found")))
            _      <- profileRepo
              .update(
                p.copy(
                  name = upr.name,
                  blockedCategories = upr.blockedCategories,
                  extraBlocked = upr.extraBlocked,
                  extraAllowed = upr.extraAllowed,
                  paused = upr.paused,
                ),
              )
              .orElseFail(Response.internalServerError(""))
            _      <- scheduleRepo
              .replaceForProfile(id, upr.schedules)
              .orElseFail(Response.internalServerError(""))
            _      <- (upr.timeLimit match
              case Some(mins) => timeLimitRepo.upsert(id, mins)
              case None       => timeLimitRepo.delete(id)
            ).orElseFail(Response.internalServerError(""))
            _      <- siteTimeLimitRepo
              .replaceForProfile(id, upr.siteTimeLimits)
              .orElseFail(Response.internalServerError(""))
          yield Response.ok
        },
      Method.DELETE / "api" / "profiles" / long("id")         ->
        handler { (id: Long, req: Request) =>
          requireAdmin(req, auth) *>
            profileRepo.delete(id).orElseFail(Response.internalServerError("")) *>
            ZIO.succeed(Response.ok)
        },
      Method.POST / "api" / "profiles" / long("id") / "pause" ->
        handler { (id: Long, req: Request) =>
          for
            claims <- requireWriter(req, auth)
            _      <- requireProfileAccess(claims, id, userProfileRepo)
            p      <- profileRepo
              .findById(id)
              .orElseFail(Response.internalServerError(""))
              .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("")))
            _      <-
              profileRepo.setPaused(id, !p.paused).orElseFail(Response.internalServerError(""))
          yield Response.json(s"""{"paused":${!p.paused}}""")
        },
    )

// ── Device routes ──────────────────────────────────────────────────────────

object DeviceRoutes:
  def routes(
      auth: AuthService,
      deviceRepo: DeviceRepo,
      userProfileRepo: UserProfileRepo,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "devices"                    ->
        handler { (req: Request) =>
          for
            claims  <- requireAuth(req, auth)
            all     <- deviceRepo.listAll.orElseFail(Response.internalServerError(""))
            visible <- filterDevices(claims, all, userProfileRepo)
          yield Response.json(visible.toJson)
        },
      Method.PUT / "api" / "devices"                    ->
        handler { (req: Request) =>
          for
            claims <- requireWriter(req, auth)
            body   <- req.body.asString.orElseFail(Response.badRequest(""))
            udr    <- ZIO
              .fromEither(body.fromJson[UpsertDeviceRequest])
              .mapError(e => Response.badRequest(e))
            _      <- requireProfileAccess(claims, udr.profileId, userProfileRepo)
            mac = normalizeMac(udr.mac)
            id <- deviceRepo
              .upsert(mac, udr.name, udr.profileId, "")
              .orElseFail(Response.internalServerError(""))
          yield Response.json(s"""{"id":$id}""")
        },
      Method.DELETE / "api" / "devices" / string("mac") ->
        handler { (mac: String, req: Request) =>
          for
            claims <- requireWriter(req, auth)
            normalized = normalizeMac(mac)
            existing <- deviceRepo
              .findByMac(normalized)
              .orElseFail(Response.internalServerError(""))
              .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("Device not found")))
            _        <- requireProfileAccess(claims, existing.profileId, userProfileRepo)
            _        <- deviceRepo.delete(normalized).orElseFail(Response.internalServerError(""))
          yield Response.ok
        },
    )

// ── Time routes ────────────────────────────────────────────────────────────

object TimeRoutes:
  def routes(
      auth: AuthService,
      deviceRepo: DeviceRepo,
      timeLimitRepo: TimeLimitRepo,
      siteTimeLimitRepo: SiteTimeLimitRepo,
      usageRepo: TimeUsageRepo,
      extRepo: TimeExtensionRepo,
      profileRepo: ProfileRepo,
      userProfileRepo: UserProfileRepo,
      clock: Clock,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "time" / "status"                     ->
        handler { (req: Request) =>
          for
            claims <- requireAuth(req, auth)
            today  <- clock.today
            dateStr = req.url.queryParam("date").getOrElse(today.toString)
            date    = LocalDate.parse(dateStr)
            all      <- deviceRepo.listAll.orElseFail(Response.internalServerError(""))
            visible  <- filterDevices(claims, all, userProfileRepo)
            statuses <- ZIO
              .foreach(visible) { d =>
                buildDeviceTimeStatus(
                  d,
                  date,
                  profileRepo,
                  timeLimitRepo,
                  siteTimeLimitRepo,
                  usageRepo,
                  extRepo,
                )
              }
              .orElseFail(Response.internalServerError(""))
          yield Response.json(statuses.toJson)
        },
      Method.GET / "api" / "time" / "status" / string("mac")     ->
        handler { (mac: String, req: Request) =>
          for
            claims <- requireAuth(req, auth)
            today  <- clock.today
            dateStr = req.url.queryParam("date").getOrElse(today.toString)
            date    = LocalDate.parse(dateStr)
            device <- deviceRepo
              .findByMac(normalizeMac(mac))
              .orElseFail(Response.internalServerError(""))
              .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("Device not found")))
            _      <- requireProfileReadAccess(claims, device.profileId, userProfileRepo)
            status <- buildDeviceTimeStatus(
              device,
              date,
              profileRepo,
              timeLimitRepo,
              siteTimeLimitRepo,
              usageRepo,
              extRepo,
            )
              .orElseFail(Response.internalServerError(""))
          yield Response.json(status.toJson)
        },
      Method.POST / "api" / "time" / "extend"                    ->
        handler { (req: Request) =>
          for
            claims <- requireWriter(req, auth)
            body   <- req.body.asString.orElseFail(Response.badRequest(""))
            ger    <- ZIO
              .fromEither(body.fromJson[GrantExtensionRequest])
              .mapError(e => Response.badRequest(e))
            mac = normalizeMac(ger.deviceMac)
            device <- deviceRepo
              .findByMac(mac)
              .orElseFail(Response.internalServerError(""))
              .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("Device not found")))
            _      <- requireProfileAccess(claims, device.profileId, userProfileRepo)
            today  <- clock.today
            id     <- extRepo
              .grant(mac, today, ger.extraMinutes, claims.sub, ger.note)
              .orElseFail(Response.internalServerError(""))
          yield Response.json(s"""{"id":$id,"grantedMinutes":${ger.extraMinutes}}""")
        },
      Method.GET / "api" / "time" / "extensions" / string("mac") ->
        handler { (mac: String, req: Request) =>
          for
            claims <- requireAuth(req, auth)
            normalized = normalizeMac(mac)
            device <- deviceRepo
              .findByMac(normalized)
              .orElseFail(Response.internalServerError(""))
              .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("Device not found")))
            _      <- requireProfileReadAccess(claims, device.profileId, userProfileRepo)
            date   <- clock.today
            exts   <- extRepo
              .listForDevice(normalized, date)
              .orElseFail(Response.internalServerError(""))
          yield Response.json(exts.toJson)
        },
    )

  private def buildDeviceTimeStatus(
      device: Device,
      date: LocalDate,
      profileRepo: ProfileRepo,
      tlRepo: TimeLimitRepo,
      stlRepo: SiteTimeLimitRepo,
      usageRepo: TimeUsageRepo,
      extRepo: TimeExtensionRepo,
  ): Task[DeviceTimeStatus] =
    for
      tl      <- tlRepo.findForProfile(device.profileId)
      stls    <- stlRepo.listForProfile(device.profileId)
      usages  <- usageRepo.listForDevice(device.mac, date)
      extMins <- extRepo.getTotalExtension(device.mac, date)
      profile <- profileRepo.findById(device.profileId).map(_.map(_.name).getOrElse("Unknown"))
      totalUsed = usages
        .filterNot(u => stls.exists(s => matchesPattern(u.domain, s.domainPattern)))
        .map(_.minutesUsed)
        .sum
      remaining = tl.map(l => (l.dailyMinutes + extMins - totalUsed).max(0))
      siteUsage = stls.map { stl =>
        val used = usages
          .filter(u => matchesPattern(u.domain, stl.domainPattern))
          .map(_.minutesUsed)
          .sum
        SiteUsage(
          stl.label,
          stl.domainPattern,
          stl.dailyMinutes,
          used,
          (stl.dailyMinutes - used).max(0),
        )
      }
    yield DeviceTimeStatus(
      device.mac,
      device.name,
      date.toString,
      profile,
      tl.map(_.dailyMinutes),
      totalUsed,
      extMins,
      remaining,
      siteUsage,
    )

  private def matchesPattern(domain: String, pattern: String): Boolean =
    if pattern.startsWith("*.") then domain.endsWith(pattern.drop(1)) || domain == pattern.drop(2)
    else domain == pattern || domain.endsWith(s".$pattern")

// ── Query log routes ───────────────────────────────────────────────────────

object LogRoutes:
  def routes(
      auth: AuthService,
      logRepo: QueryLogRepo,
      userProfileRepo: UserProfileRepo,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "logs"  ->
        handler { (req: Request) =>
          for
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
            logs    <- logRepo.query(filter).orElseFail(Response.internalServerError(""))
            visible <- filterLogs(claims, logs, userProfileRepo)
          yield Response.json(visible.toJson)
        },
      Method.GET / "api" / "stats" ->
        handler { (req: Request) =>
          requireAdmin(req, auth) *>
            logRepo.stats
              .map(s => Response.json(s.toJson))
              .orElseFail(Response.internalServerError(""))
        },
    )

// ── Blocklist routes ───────────────────────────────────────────────────────

object BlocklistRoutes:
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
              .orElseFail(Response.internalServerError(""))
        },
      Method.POST / "api" / "blocklists" / string("category") / "clear" ->
        handler { (cat: String, req: Request) =>
          requireAdmin(req, auth) *>
            blRepo.clearCategory(cat).orElseFail(Response.internalServerError("")) *>
            ZIO.succeed(Response.ok)
        },
    )

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
      .orElseFail(Response.internalServerError(""))
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
      .orElseFail(Response.internalServerError(""))
      .map(pids => all.filter(d => pids.contains(d.profileId)))

def filterLogs(
    claims: JwtClaims,
    all: List[QueryLog],
    upRepo: UserProfileRepo,
): IO[Response, List[QueryLog]] =
  if claims.role == "admin" || claims.role == "adult" then ZIO.succeed(all)
  else
    upRepo
      .listProfilesForUsername(claims.sub)
      .orElseFail(Response.internalServerError(""))
      .map(pids => all.filter(l => l.profileId.exists(pids.contains)))

/** Allow read access if admin or adult (full visibility); child must be linked to the profile. */
def requireProfileReadAccess(
    claims: JwtClaims,
    profileId: Long,
    upRepo: UserProfileRepo,
): IO[Response, Unit] =
  if claims.role == "admin" || claims.role == "adult" then ZIO.succeed(())
  else
    upRepo
      .listProfilesForUsername(claims.sub)
      .orElseFail(Response.internalServerError(""))
      .flatMap { pids =>
        if pids.contains(profileId) then ZIO.succeed(())
        else ZIO.fail(Response.forbidden("Not authorized for this profile"))
      }

/** Allow write access if admin or adult linked to the profile. Child is always denied. */
def requireProfileAccess(
    claims: JwtClaims,
    profileId: Long,
    upRepo: UserProfileRepo,
): IO[Response, Unit] =
  if claims.role == "admin" then ZIO.succeed(())
  else
    upRepo
      .listProfilesForUsername(claims.sub)
      .orElseFail(Response.internalServerError(""))
      .flatMap { pids =>
        if pids.contains(profileId) then ZIO.succeed(())
        else ZIO.fail(Response.forbidden("Not authorized for this profile"))
      }

def normalizeMac(mac: String): String =
  mac.toLowerCase.replace("-", ":").trim
