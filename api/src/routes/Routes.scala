package wifihaven.api.routes

import wifihaven.api.auth.*
import wifihaven.api.cache.TimeStatusCache
import wifihaven.api.db.*
import wifihaven.shared.*
import wifihaven.shared.types.*
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
                  stls   <- siteTimeLimitRepo.listForProfile(p.id)
                } yield ProfileDetail(p, scheds, tl, stls)
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
            stls   <- siteTimeLimitRepo
              .listForProfile(pid)
              .mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.json(ProfileDetail(p, scheds, tl, stls).toJson)
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
                  upr.extraBlocked,
                  upr.extraAllowed,
                  upr.paused,
                  // #385: missing failureMode → LastKnownGood (preserves
                  // cached-snapshot enforcement; matches DB column default).
                  // Role-aware defaulting is a UI concern — the server just
                  // persists whatever value the admin sent.
                  upr.failureMode.getOrElse(FailureMode.LastKnownGood),
                  // #424: omitted blockIpOnly defaults to false on create
                  // (matches the DB column default).
                  upr.blockIpOnly.getOrElse(false),
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
                  extraBlocked = upr.extraBlocked,
                  extraAllowed = upr.extraAllowed,
                  paused = upr.paused,
                  // #385: if the caller omits failureMode, preserve the
                  // existing value rather than resetting to the column default.
                  failureMode = upr.failureMode.getOrElse(p.failureMode),
                  // #424: if caller omits blockIpOnly, preserve the
                  // existing value rather than clearing it.
                  blockIpOnly = upr.blockIpOnly.getOrElse(p.blockIpOnly),
                ),
              )
              .mapError(ErrorMapper.dbErrorToResponse)
            // #481: log mutations that should bump the policy snapshot etag.
            // Without this the only evidence of "did the API even receive the
            // pause flip?" was the absence of logs, which was useless.
            _      <- ZIO.logInfo(
              s"profile updated: id=${pid.value} paused=${p.paused}→${upr.paused} " +
                s"name=${upr.name} extraBlockedCount=${upr.extraBlocked.size} " +
                s"extraAllowedCount=${upr.extraAllowed.size}",
            )
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
            _      <- requireProfileAccess(claims, udr.profileId, userProfileRepo)
            mac = MacAddress.unsafe(normalizeMac(udr.mac.value))
            id <- deviceRepo
              .upsert(mac, udr.name, udr.profileId, "")
              .mapError(ErrorMapper.dbErrorToResponse)
            // #481: log device upsert so the next CI failure makes it obvious
            // whether the mutation reached the API at all.
            _  <- ZIO.logInfo(
              s"device upserted: mac=${mac.value} profileId=${udr.profileId.value} name=${udr.name}",
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
            // ?to=YYYY-MM-DD anchors the trailing 7-day window; defaults to today.
            toStr = req.url.queryParam("to").getOrElse(today.toString)
            to    = LocalDate.parse(toStr)
            from  = to.minusDays(6)
            // #795: same single-profile narrowing as the daily endpoint.
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
                // #802: weekly cache keyed by (profileId, from, to). Same TTL logic — short
                // TTL if `to` straddles today, long TTL for fully-past windows.
                cache
                  .getOrLoadWeekly(p.id, from, to, today) {
                    buildProfileTimeStatusWeek(
                      p,
                      devicesByPid.getOrElse(Some(p.id), Nil),
                      from,
                      to,
                      timeLimitRepo,
                      trafficRepo,
                      settings.heartbeatFilter,
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
            device   <- deviceRepo
              .findByMac(MacAddress.unsafe(normalizeMac(mac)))
              .mapError(ErrorMapper.dbErrorToResponse)
              .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("Device not found")))
            _        <- requireProfileReadAccess(claims, device.profileId, userProfileRepo)
            settings <- hsRepo.get.mapError(ErrorMapper.dbErrorToResponse)
            status   <- buildDeviceTimeStatusWeek(
              device,
              from,
              to,
              profileRepo,
              timeLimitRepo,
              trafficRepo,
              settings.heartbeatFilter,
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
      Method.GET / "api" / "time" / "extensions" / long("profileId")    ->
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
      // #262 — top-N host attribution across all profile devices for the day.
      // Bucket-deduped per host; informational, so all hosts (including
      // exempt-pattern matches) appear. UI shows top 10.
      hostUsage       = wifihaven.api.presence.Presence
        .hostMinutes(presence)
        .iterator
        .filter(_._2 > 0)
        .map { case (h, m) => HostUsage(h, m) }
        .toList
        .sortBy(hu => (-hu.usedMins, hu.host.value))
        .take(10)
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
      totalUsed       = devices.iterator.map(d => perMacTotal.getOrElse(d.mac, 0)).sum
      deviceSummaries = devices.map { d =>
        DeviceUsageSummary(d.mac, d.name, perMacTotal.getOrElse(d.mac, 0))
      }
      hostUsage       = wifihaven.api.presence.Presence
        .hostMinutes(presence)
        .iterator
        .filter(_._2 > 0)
        .map { case (h, m) => HostUsage(h, m) }
        .toList
        .sortBy(hu => (-hu.usedMins, hu.host.value))
        .take(10)
      byDay           = presence.groupBy(_.date)
      perDay          = Iterator
        .iterate(from)(_.plusDays(1))
        .takeWhile(!_.isAfter(to))
        .map { d =>
          val rows = byDay.getOrElse(d, Nil)
          val mins = wifihaven.api.presence.Presence
            .totalMinutesByMac(rows, Nil, heartbeatFilter)
            .values
            .sum
          ProfileTimeDayTotal(d.toString, mins)
        }
        .toList
    } yield ProfileTimeStatusWeek(
      profile.id,
      profile.name,
      from.toString,
      to.toString,
      tl.map(_.dailyMinutes),
      totalUsed,
      perDay,
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
      hostUsage = wifihaven.api.presence.Presence
        .hostMinutes(presence)
        .iterator
        .filter(_._2 > 0)
        .map { case (h, m) => HostUsage(h, m) }
        .toList
        .sortBy(hu => (-hu.usedMins, hu.host.value))
        .take(10)
      byDay     = presence.groupBy(_.date)
      perDay    = Iterator
        .iterate(from)(_.plusDays(1))
        .takeWhile(!_.isAfter(to))
        .map { d =>
          val rows = byDay.getOrElse(d, Nil)
          val mins = wifihaven.api.presence.Presence
            .totalMinutesByMac(rows, Nil, heartbeatFilter)
            .values
            .sum
          ProfileTimeDayTotal(d.toString, mins)
        }
        .toList
    } yield DeviceTimeStatusWeek(
      device.mac,
      device.name,
      from.toString,
      to.toString,
      profile,
      pid,
      tl.map(_.dailyMinutes),
      totalUsed,
      perDay,
      hostUsage,
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
              deviceId = req.url.queryParam("deviceId").flatMap(_.toLongOption).map(DeviceId(_)),
              profileId = req.url.queryParam("profileId").flatMap(_.toLongOption).map(ProfileId(_)),
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

// ── Household settings (#334) ──────────────────────────────────────────────

object HouseholdSettingsRoutes {
  def routes(
      auth: AuthService,
      repo: HouseholdSettingsRepo,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "household" / "settings" ->
        handler { (req: Request) =>
          for {
            _ <- requireAuth(req, auth)
            s <- repo.get.mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.json(s.toJson)
        },
      Method.PUT / "api" / "household" / "settings" ->
        handler { (req: Request) =>
          for {
            _    <- requireAdmin(req, auth)
            body <- req.body.asString.orElseFail(Response.badRequest(""))
            upd  <- ZIO
              .fromEither(body.fromJson[UpdateHouseholdSettingsRequest])
              .mapError(e => Response.badRequest(e))
            _    <- repo
              .update(HouseholdSettings(upd.dailyResetTime, upd.dailyResetTz, upd.heartbeatFilter))
              .mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.ok
        },
    )
}

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
