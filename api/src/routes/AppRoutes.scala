package wifihaven.api.routes

import wifihaven.api.AppReconciler
import wifihaven.api.AppTemplate
import wifihaven.api.AppTemplates
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.policy.AppBlocklistOverlap
import wifihaven.shared.*
import wifihaven.shared.types.*
import zio.*
import zio.http.*
import zio.json.*

/**
 * #1570: handlers fail with a typed [[ApiError]] mapped centrally by
 * [[ErrorMapper.errorToResponse]]; the [[wifihaven.api.ErrorBoundary]] logs (4xx WARN / 5xx ERROR)
 * + meters each error. Every case reproduces the EXACT status + body the hand-rolled code produced
 * — the structured `seed_failed` (500), `reconcile_failed` (500), `not_template_derived` (400), and
 * `unknown_template` (404) JSON bodies are preserved verbatim via [[ApiError.Wrapped]] (the SPA
 * parses them), DB failures stay 503 via [[ApiError.Db]].
 *
 * #1798: app *definition* mutators (create / update / replace-hosts / PATCH) were removed — app
 * definitions are authored only via the built-in `AppTemplates` (seed/reconcile/reset routes
 * below). `DELETE /api/apps/:id` stays as a cleanup path but is no longer surfaced in the SPA.
 *
 * #2535/#2567: the four catalog *maintenance* verbs here — `DELETE /api/apps/:id`,
 * `seed-from-templates`, `reset-to-template`, and `admin/apps/reconcile-templates` — are behind
 * `requireOperator`, not `requireWriter`/`requireAdmin`. `apps` / `app_hosts` are a SINGLE
 * install-wide catalog with no `household_id`, so any of these run by a household-B principal
 * rewrites state every other household's enforcement reads. They are operator maintenance verbs,
 * not household-user surfaces — none is reachable from the SPA. The per-profile assignment routes
 * (`PUT|DELETE /api/apps/:id/policy/:profileId`) are unaffected: `app_policy_assignments` IS
 * per-profile, and they already compose `requireWriter` + `requireProfileAccess`.
 */
object AppRoutes {

  /**
   * Validate the limits and settle the stored mode (design §5.2, D11). `Blocked` keeps its mode.
   * Otherwise either limit implies `time_limited` (an `allowed` write that carries one is
   * promoted); `time_limited` with neither is refused, and `allowed` with neither is the demoted
   * state a client reaches by clearing both limits.
   */
  private def validateAssignment(
      mode: AppMode,
      dailyMinutes: Option[Int],
      weeklyMinutes: Option[Int],
  ): Either[String, AppMode] =
    if (dailyMinutes.exists(_ <= 0)) Left("dailyMinutes must be > 0")
    else if (weeklyMinutes.exists(_ <= 0)) Left("weeklyMinutes must be > 0")
    else {
      val limited = dailyMinutes.isDefined || weeklyMinutes.isDefined
      mode match {
        case AppMode.Blocked                 => Right(AppMode.Blocked)
        case AppMode.TimeLimited if !limited =>
          Left("time_limited mode requires dailyMinutes or weeklyMinutes")
        case _ => Right(if (limited) AppMode.TimeLimited else AppMode.Allowed)
      }
    }

  /**
   * #2934 (D14): stamp each assignment with the group of its profile that also covers the app, if
   * any. One query for the whole set (the list route returns every app's assignments), scoped to
   * the caller's household.
   */
  private def withCoveredBy(
      groupRepo: AppGroupRepo,
      household: HouseholdId,
      asgn: List[AppPolicyAssignment],
  ): Task[List[AppPolicyAssignment]] =
    groupRepo.coveredBy(household, asgn.map(_.profileId)).map { covered =>
      asgn.map(a => a.copy(coveredBy = covered.get((a.profileId, a.appId))))
    }

  /**
   * #2751: attach each assignment's #1379 per-app schedule rules. The rules are stored on
   * `app_policy_schedule_rules` and were written by `PUT /api/apps/:id/policy/:profileId` from the
   * start, but nothing read them back — so they vanished from the SPA on reload and the next
   * unrelated edit on the row wiped them (the SPA re-seeds from this read and PUTs the full desired
   * set with replace semantics).
   *
   * The attach is split from the fetch because the two read routes batch at different widths: the
   * detail route resolves one app's assignments, while the list route resolves EVERY app's in a
   * single query (a per-app fetch there would be an N+1 over apps x profiles). Both then attach
   * through this one function, so the shape cannot drift between them.
   */
  private def attachScheduleRules(
      asgn: List[AppPolicyAssignment],
      byId: Map[AppPolicyAssignmentId, List[AppScheduleRule]],
  ): List[AppPolicyAssignment] =
    asgn.map(a => a.copy(scheduleRules = byId.getOrElse(a.id, Nil)))

  private def withScheduleRules(
      appRepo: AppRepo,
      asgn: List[AppPolicyAssignment],
  ): Task[List[AppPolicyAssignment]] =
    appRepo.scheduleRulesForAssignments(asgn.map(_.id)).map(attachScheduleRules(asgn, _))

  private def detail(
      appRepo: AppRepo,
      blocklistRepo: BlocklistRepo,
      groupRepo: AppGroupRepo,
      household: HouseholdId,
      a: App,
  ): Task[AppDetail] =
    for {
      hosts       <- appRepo.getHosts(a.id)
      raw         <- appRepo.listAssignmentsForApp(a.id)
      ruled       <- withScheduleRules(appRepo, raw)
      asgn        <- withCoveredBy(groupRepo, household, ruled)
      // #1983: per-host category-blocklist overlap so the SPA can warn.
      blocklisted <- AppBlocklistOverlap.forHosts(blocklistRepo, a.id, hosts)
    } yield AppDetail(a, hosts, asgn, blocklisted)

  def routes(
      auth: AuthService,
      appRepo: AppRepo,
      profileRepo: ProfileRepo,
      userProfileRepo: UserProfileRepo,
      blocklistRepo: BlocklistRepo,
      groupRepo: AppGroupRepo,
      // #2934: an assignment write changes the profile's dispositions, so production passes
      // `policy.invalidate`; defaulted to a no-op for test call sites that do not read the snapshot.
      invalidateSnapshot: HouseholdId => UIO[Unit] = _ => ZIO.unit,
      templates: Map[AppTemplateId, AppTemplate] = Map.empty,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "apps"                                                ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireAuth(req, auth)
            apps   <- appRepo.listAll.mapError(ApiError.Db(_))
            // Fetch hosts + assignments per app, then compute the blocklist
            // overlap for ALL apps in a single batched query (#1983) rather
            // than one categoriesForDomains round-trip per app.
            // Assignments as stored, before the #2751 schedule-rule attach below.
            raw    <- ZIO
              .foreach(apps) { a =>
                for {
                  hosts <- appRepo.getHosts(a.id)
                  asgn  <- appRepo.listAssignmentsForApp(a.id)
                } yield (a, hosts, asgn)
              }
              .mapError(ApiError.Db(_))
            // #2751: one batched schedule-rule read across EVERY app's assignments, not one per
            // assignment — the list route would otherwise be an N+1 over apps x profiles.
            rules  <- appRepo
              .scheduleRulesForAssignments(raw.flatMap((_, _, asgn) => asgn.map(_.id)))
              .mapError(ApiError.Db(_))
            ruled = raw.map((a, hosts, asgn) => (a, hosts, attachScheduleRules(asgn, rules)))
            // #2934: one group lookup for every app's assignments, not one per app.
            covered <- groupRepo
              .coveredBy(claims.hh, ruled.flatMap((_, _, asgn) => asgn.map(_.profileId)))
              .mapError(ApiError.Db(_))
            base = ruled.map((a, hosts, asgn) =>
              (a, hosts, asgn.map(x => x.copy(coveredBy = covered.get((x.profileId, x.appId))))),
            )
            overlap <- AppBlocklistOverlap
              .forApps(blocklistRepo, base.map((a, hosts, _) => a.id -> hosts).toMap)
              .mapError(ApiError.Db(_))
            detailed = base.map { (a, hosts, asgn) =>
              AppDetail(a, hosts, asgn, overlap.getOrElse(a.id, Nil))
            }
          } yield Response.json(detailed.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.GET / "api" / "apps" / long("id")                                   ->
        handler { (id: Long, req: Request) =>
          val aid                                  = AppId(id)
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireAuth(req, auth)
            a      <- appRepo
              .findById(aid)
              .mapError(ApiError.Db(_))
              .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("App not found")))
            d <- detail(appRepo, blocklistRepo, groupRepo, claims.hh, a).mapError(ApiError.Db(_))
          } yield Response.json(d.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      // #1798: app *definition* mutators (create, update name/icon, replace
      // hosts, PATCH) were retired — app definitions are authored only via the
      // built-in `AppTemplates` in code (seeded/reconciled below). `DELETE
      // /api/apps/:id` is kept as a maintenance path (stray-row cleanup) but is
      // no longer surfaced in the SPA. #2535/#2567: operator-only — `apps` is a single
      // install-wide catalog with no `household_id`, so deleting a row cascades every household's
      // `app_policy_assignments` and rollup rows for that app.
      Method.DELETE / "api" / "apps" / long("id")                                ->
        handler { (id: Long, req: Request) =>
          val handle: ZIO[Any, ApiError, Response] =
            requireOperator(req, auth) *>
              appRepo.delete(AppId(id)).mapError(ApiError.Db(_)) *>
              ZIO.succeed(Response.ok)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.PUT / "api" / "apps" / long("id") / "policy" / long("profileId")    ->
        handler { (id: Long, profileIdRaw: Long, req: Request) =>
          val aid                                  = AppId(id)
          val pid                                  = ProfileId(profileIdRaw)
          val handle: ZIO[Any, ApiError, Response] = for {
            claims   <- requireWriter(req, auth)
            _        <- requireProfileAccess(claims, pid, userProfileRepo, profileRepo)
            body     <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            ar       <- ZIO
              .fromEither(body.fromJson[UpsertAppAssignmentRequest])
              .mapError(ApiError.DecodeFailure(_))
            mode     <- ZIO
              .fromEither(validateAssignment(ar.mode, ar.dailyMinutes, ar.weeklyMinutes))
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
                mode,
                ar.dailyMinutes,
                ar.exemptFromDaily.getOrElse(true),
                // #1679: omitted by existing clients → defaults to true (preserve current behavior).
                ar.allowedDuringScheduleBlock.getOrElse(true),
                ar.weeklyMinutes,
              )
              .mapError(ApiError.Db(_))
            // #1379: replace this assignment's per-app schedule rules with the
            // requested set (additive field; existing clients send `Nil`).
            _        <- appRepo
              .setScheduleRules(assignId, ar.scheduleRules.map(r => (r.scheduleId, r.mode)))
              .mapError(ApiError.Db(_))
            _        <- invalidateSnapshot(claims.hh)
          } yield Response.ok
          handle.mapError(ErrorMapper.errorToResponse)
        },
      // #1777: admin-triggered reconciliation pass that collapses `<slug>-template`-suffixed app
      // rows onto their canonical `<slug>` form, reattaching FK refs and unioning host-sets.
      // Idempotent — operator can re-run; subsequent passes are no-ops once the DB is clean.
      // #2567: the most destructive of the family, and the reason it is `requireOperator` rather
      // than `requireAdmin` — `mergeAppInto` DELETEs an `apps` row and repoints
      // `app_policy_assignments`, `traffic_hourly_apps`, `traffic_daily_apps` and `app_used_daily`
      // across EVERY household, so a household-B admin could collapse two app rows household A had
      // configured separately, silently merging two distinct policies.
      Method.POST / "api" / "admin" / "apps" / "reconcile-templates"             ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            _       <- requireOperator(req, auth)
            summary <- AppReconciler
              .reconcileTemplates(appRepo, templates.values.toList)
              .mapError(e =>
                ApiError.Wrapped(
                  Response
                    .json(s"""{"error":"reconcile_failed","message":${e.getMessage.toJson}}""")
                    .status(Status.InternalServerError),
                ),
              )
          } yield Response.json(summary.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      // #1024: operator-triggered re-run of the startup app-template seeder. Same idempotent
      // semantics as the boot pass (existing rows' host divergence preserved — post-#1798 that
      // divergence comes from reconcile unions / legacy data, no longer operator host edits) —
      // exposed as a route so the operator can backfill without a redeploy when prod is missing
      // the starter set. #2567: writes the install-wide `apps` + `app_hosts`, so operator-only.
      Method.POST / "api" / "apps" / "seed-from-templates"                       ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            _       <- requireOperator(req, auth)
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
      // #2567: `setHosts` replaces the install-wide app's host-set wholesale, so every household
      // with a TimeLimited/Blocked assignment against this app silently gets a different host-set
      // enforced. Operator-only.
      Method.POST / "api" / "apps" / long("id") / "reset-to-template"            ->
        handler { (id: Long, req: Request) =>
          val aid                                  = AppId(id)
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireOperator(req, auth)
            app    <- appRepo
              .findById(aid)
              .mapError(ApiError.Db(_))
              .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("App not found")))
            tid    <- ZIO
              .fromOption(app.templateId)
              .orElseFail(
                ApiError.Wrapped(
                  Response
                    .json("""{"error":"not_template_derived"}""")
                    .status(Status.BadRequest),
                ),
              )
            tmpl   <- ZIO
              .fromOption(templates.get(tid))
              .orElseFail(
                ApiError.Wrapped(
                  Response
                    .json(s"""{"error":"unknown_template","templateId":"${tid.value}"}""")
                    .status(Status.NotFound),
                ),
              )
            _      <- appRepo.setHosts(aid, tmpl.hosts).mapError(ApiError.Db(_))
            d <- detail(appRepo, blocklistRepo, groupRepo, claims.hh, app).mapError(ApiError.Db(_))
          } yield Response.json(d.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.DELETE / "api" / "apps" / long("id") / "policy" / long("profileId") ->
        handler { (id: Long, profileIdRaw: Long, req: Request) =>
          val aid                                  = AppId(id)
          val pid                                  = ProfileId(profileIdRaw)
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireWriter(req, auth)
            _      <- requireProfileAccess(claims, pid, userProfileRepo, profileRepo)
            _      <- appRepo
              .deleteAssignment(aid, pid)
              .mapError(ApiError.Db(_))
            _      <- invalidateSnapshot(claims.hh)
          } yield Response.ok
          handle.mapError(ErrorMapper.errorToResponse)
        },
    )
}
