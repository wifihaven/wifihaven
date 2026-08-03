package wifihaven.api.db

import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.api.metrics.DbMetrics
import zio.*
import zio.interop.catz.*
import java.time.{Instant, LocalDate, LocalTime, ZoneId}

given Meta[List[String]] = Meta[Array[String]].imap(_.toList)(_.toArray)

/**
 * #2286: the ONE definition of the per-household global-sentinel profile insert. Each household
 * owns exactly one `is_global = TRUE` "Global" profile (the household's global-policy layer,
 * #2108); every other column takes its table default. Returned as a `ConnectionIO` so the caller
 * can run it inside whatever transaction owns the surrounding household-create (the beta
 * [[BetaRequestRepo.approveAndProvision]] txn, [[HouseholdRepoLive.create]], or the boot-time seed
 * in `Main`). Idempotent via V73's `(household_id, is_global) WHERE is_global` partial-unique
 * index: `ON CONFLICT DO NOTHING` guarantees at most one sentinel per household even under a
 * re-run.
 */
object ProfileSeed {
  def insertGlobalSentinel(householdId: HouseholdId): ConnectionIO[Int] =
    sql"""INSERT INTO profiles (name, is_global, household_id)
          VALUES ('Global', TRUE, $householdId)
          ON CONFLICT DO NOTHING""".update.run
}

/**
 * #2355: the ONE household-creation primitive. A household is, by invariant, three rows written as
 * a unit: the `households` row, its `household_billing` row (so `GET /api/billing` never 404s
 * `NoBillingRow` — the bug this fixes), and its global-sentinel profile (#2286). Exposed as a
 * composable `ConnectionIO` so every caller runs it inside whatever transaction owns the
 * surrounding work — the beta [[BetaRequestRepo.approveAndProvision]] txn (which also stamps
 * `beta_requests` in the same txn for its double-approve rollback guard) and
 * [[HouseholdRepoLive.create]]. There is no household-create path that skips any of the three (the
 * drift that let path (2) omit billing is exactly the SSOT failure this collapses; same lesson as
 * #2286's global-sentinel mirror).
 *
 * `insertGlobalSentinel` is idempotent (ON CONFLICT), but the households/billing inserts are not —
 * they always mint fresh rows, so this is a CREATE primitive, not an upsert. The rowless-household
 * *backfill* is the separate idempotent [[backfillMissingBilling]].
 */
object HouseholdSeed {
  // Provisioning-time billing defaults (design §5.1: "minimal at provisioning time: status=beta").
  // `founding=false` is the create()/backfill default; the beta approveAndProvision path passes
  // `founding=true` explicitly for founding members.
  val DefaultBillingStatus: String = "beta"
  val DefaultFounding: Boolean     = false

  def insertHousehold(
      name: String,
      slug: String,
      routerCap: Int,
      billingStatus: String = DefaultBillingStatus,
      founding: Boolean = DefaultFounding,
  ): ConnectionIO[HouseholdId] =
    for {
      hid <-
        sql"INSERT INTO households(name, slug, router_cap) VALUES($name, $slug, $routerCap) RETURNING id"
          .query[HouseholdId]
          .unique
      _   <-
        sql"INSERT INTO household_billing(household_id, status, founding) VALUES($hid, $billingStatus, $founding)".update.run
      _   <- ProfileSeed.insertGlobalSentinel(hid)
      // #2386: the household's OWN household_settings row is part of the atomic creation unit —
      // households + household_billing + global-sentinel + settings, so no create path can leave a
      // household reading another tenant's config. All settings columns have DB defaults, so only
      // household_id is supplied; `id` auto-generates (V82 identity). A fresh household has no
      // settings row yet, so ON CONFLICT is just belt-and-braces against a retried create.
      _   <-
        sql"INSERT INTO household_settings(household_id) VALUES($hid) ON CONFLICT (household_id) DO NOTHING".update.run
    } yield hid

  /**
   * #2355: idempotent boot-time backfill for pre-existing households minted before billing was
   * seeded on every create path. Gives every rowless household a default beta row; the NOT EXISTS
   * guard makes a re-run a no-op and never touches an existing billing row. One-shot cleanup after
   * it deploys is tracked by #2359 (under #1608; see [[wifihaven.api.Main]] boot seed).
   */
  val backfillMissingBilling: ConnectionIO[Int] =
    sql"""INSERT INTO household_billing(household_id, status, founding)
          SELECT h.id, $DefaultBillingStatus, $DefaultFounding
          FROM households h
          WHERE NOT EXISTS (SELECT 1 FROM household_billing hb WHERE hb.household_id = h.id)""".update.run

  /**
   * #2386: idempotent boot-time backfill for households minted before the per-household settings
   * row was seeded on every create path. Gives every rowless household its OWN default settings row
   * (all columns default; `id` auto-generates via V82), so `getForHousehold` never has to fall back
   * to household #1's row — the cross-tenant leak this fix closes. The NOT EXISTS guard makes a
   * re-run a no-op and never touches an existing settings row. One-shot cleanup after it deploys is
   * tracked under #1608 (see [[wifihaven.api.Main]] boot seed).
   */
  val backfillMissingSettings: ConnectionIO[Int] =
    sql"""INSERT INTO household_settings(household_id)
          SELECT h.id
          FROM households h
          WHERE NOT EXISTS (SELECT 1 FROM household_settings hs WHERE hs.household_id = h.id)""".update.run
}

case class DbUser(
    id: UserId,
    username: String,
    passwordHash: String,
    role: UserRole,
    createdAt: Instant,
    mustChangePassword: Boolean = false,
    // #2080: bumped on every password change; stamped into the JWT at login and
    // checked on verify() so a leaked token stops working once the password rotates.
    tokenVersion: Int = 0,
    // #2105 (multi-tenant sub-issue B): the user's tenancy key (users.household_id,
    // V65). Minted into the JWT `hh` claim at login. Defaults to household 1 — the
    // single existing install / default household backfilled by V65 — so pre-#2105
    // callers that don't set it stay in the default tenant. #2106 also reads it
    // in the router-enrollment path to stamp a new router with the creating
    // admin's household.
    householdId: HouseholdId = HouseholdId.Default,
)
// #865: mac/deviceId/profileId became multi-valued so the SPA's column-header
// popovers can filter to a subset. Empty list = no filter on that column.
// #862: cursor-paged. `until` anchors the window's right edge (defaults to
// NOW() when unset). `cursorTs`/`cursorId` come from a decoded raw-log cursor
// — when set, the query is `(ts, id) < (cursorTs, cursorId)` (keyset).
// `cursorWs`/`cursorKey` carry an aggregated-series cursor on
// `(window_start DESC, group_key ASC)`. `offset` is gone — replaced by keyset
// pagination so concurrent inserts can't shift pages.
case class LogFilter(
    macs: List[String] = Nil,
    deviceIds: List[DeviceId] = Nil,
    profileIds: List[ProfileId] = Nil,
    blocked: Option[Boolean] = None,
    domain: Option[String] = None,
    location: Option[String] = None,
    hours: Int = 24,
    limit: Int = 200,
    until: Option[Instant] = None,
    cursorTs: Option[Instant] = None,
    cursorId: Option[Long] = None,
    cursorWs: Option[String] = None,
    cursorKey: Option[String] = None,
    // #969: when false (default), drop rows whose destination is IPv4 multicast
    // (224.0.0.0/4), IPv4 broadcast (255.255.255.255), or IPv6 multicast
    // (ff00::/8) from both the raw and aggregated views. Operators can pass
    // ?includeMulticast=true to see them for diagnostics.
    includeMulticast: Boolean = false,
    // #2108 (multi-tenant sub-issue E): when set, scope the read to this household via the
    // connection_events.router_id → routers.household_id join (connection_events are router_id-keyed,
    // so household is transitive — design §0.1). `None` (default) reads unscoped, preserving the
    // single-household back-compat for every existing caller. Set by `GET /api/logs` and (#2314)
    // `GET /api/connection-events/series`.
    household: Option[HouseholdId] = None,
)

case class TrafficRollupFilter(
    macs: Option[List[MacAddress]] = None, // None = no MAC restriction; Some(Nil) = match nothing
    host: Option[String] = None,
    since: Option[Instant] = None,
    until: Option[Instant] = None,
)

/**
 * One row per traffic_reports usage-report period (the agent's `usage_report_interval`, ~60s and
 * configurable — NOT a fixed 5-min bucket; the real span is `period_start`/`period_end`), with
 * ipv4/ipv6 hosts resolved to their attributed FQDN when possible. Used by [[DashboardNowRoutes]]
 * to compute per-device top hosts.
 */
case class TrafficRollupRow(
    routerId: RouterId,
    mac: MacAddress,
    host: HostId,
    date: LocalDate,
    periodStart: Instant,
    periodEnd: Instant,
    activeSeconds: Int,
    bytesIn: Long,
    bytesOut: Long,
)

trait UserRepo {
  // #2140 (multi-tenant P5-8): keyed on the V65 `UNIQUE(household_id, username)` — the same
  // username can legitimately exist in two households, so a bare-username lookup is ambiguous.
  // Callers resolve the household from `claims.hh` (authenticated paths) or the login request's
  // slug (the one unauthenticated path).
  def findByUsername(householdId: HouseholdId, u: String): Task[Option[DbUser]]

  /**
   * #2164 (single-identifier login, design §4 form 1): resolve a user by their GLOBAL email
   * (`users.email`, V67/#2159 — nullable, globally UNIQUE). Login uses this when the posted
   * identifier contains '@'; the matched row carries the household, so email login needs no
   * household in hand. Match is exact, aligned with the case-sensitive `uq_users_email` constraint.
   *
   * CONTRACT (do NOT lowercase here — the write side owns normalization): every writer of
   * `users.email` MUST store it lowercase-normalized, so this exact-match lookup still finds a
   * differently-cased login attempt. The writer today is #2132's invite-accept (binds the admin's
   * email from `beta_requests.email`); it, and any future email-add path, must `.toLowerCase` on
   * write. A read-side `LOWER(email)=LOWER(?)` is the wrong fix: it needs a functional index and
   * could match two rows differing only by case (the raw-column unique constraint doesn't forbid
   * them), throwing on `.option` — canonical-lowercase storage is the safe single source.
   */
  def findByEmail(email: String): Task[Option[DbUser]]

  /**
   * #2199 (support intake B): resolve a user's `users.email` (V67) by their household + username —
   * the inverse of [[findByEmail]]. An authenticated caller carries `(hh, username)` in their JWT
   * (never an email), and the support-widget identity path needs that user's email to compute the
   * server-signed Plain chat-auth hash. Returns `None` when the user has no email (username-only /
   * self-hosted admin) or no such row — either way the widget stays dark for that caller.
   */
  def emailForUser(householdId: HouseholdId, username: String): Task[Option[String]]
  def findById(id: UserId): Task[Option[DbUser]]
  // #2130: `householdId` stamps the new user with the creating admin's
  // household (resolved from their JWT at the route). Defaults to the
  // single-install backfill household so pre-multi-tenant callers stay
  // tenant-safe (matches the #2106 RouterRepo.create precedent).
  // #2132 (design §3.4, V67): `email` is the global login identifier — set only
  // by the invite-accept path (bound from beta_requests.email). Defaulted to
  // None so every existing admin-create call site (which has no email) is
  // unchanged and the user is created email-less (username login via §4).
  def create(
      u: String,
      h: String,
      r: String,
      householdId: HouseholdId = HouseholdId.Default,
      email: Option[String] = None,
  ): Task[UserId]

  /**
   * The WHOLE of a password rotation, in ONE statement: store the new hash, set
   * `must_change_password` to `mustChange`, and bump `token_version` (#2080 — this is what makes a
   * password change actually invalidate every previously-issued JWT).
   *
   * Atomic on purpose (#2576). These were three separate `.transact` calls until the
   * admin-initiated set (which needs `mustChange = true`) made the failure mode load-bearing: a
   * blip between the writes leaves the credential already rotated with the flag in the WRONG state,
   * while the caller sees a 5xx and reasonably assumes nothing happened. For the handoff path that
   * means the admin-chosen password silently becomes permanent — the one property the feature
   * exists to prevent. One `UPDATE` makes the partial state unrepresentable rather than merely
   * unlikely.
   *
   * `mustChange` is a raw boolean only because this is the storage layer; callers never see it.
   * `AuthService` exposes the two cases as separately-named methods (`setPassword` /
   * `setPasswordAsHandoff`) precisely so nobody passes it backwards.
   */
  def applyPasswordRotation(id: UserId, hash: String, mustChange: Boolean): Task[Unit]
  def updateUsername(id: UserId, u: String): Task[Unit]
  def updateRole(id: UserId, r: String): Task[Unit]
  def clearMustChangePassword(id: UserId): Task[Unit]
  def listAll: Task[List[DbUser]]

  /**
   * #2108 (multi-tenant sub-issue E): household-scoped [[listAll]] — users belonging to
   * `household`. Backs `GET /api/users` so an admin enumerates only their own household's users
   * (design §2 gap 4). Index-backed by V65's idx_users_household.
   */
  def listAllForHousehold(household: HouseholdId): Task[List[DbUser]]

  /**
   * #2512: the household's ONE admin, or `None` if the slot is free. `.option` is exact by
   * construction — V86's partial unique index `uq_users_household_single_admin ON users
   * (household_id) WHERE role = 'admin'` makes a second matching row unrepresentable.
   * (Index-BACKED, not index-ONLY: the projection is the full `userCols`, none of which the index
   * covers, so the plan is an index scan plus a heap fetch.)
   *
   * This is the READ half of the one-admin invariant; the index is the write-side backstop. It
   * exists so `POST /api/users` / `PATCH /api/users/{id}` can refuse a second admin with a readable
   * 409 instead of letting the unique violation surface as `ApiError.Db` → 503 "retry later" (a lie
   * — retrying a permanently-occupied slot never succeeds). Deliberately not `listAllForHousehold`
   * + filter: the predicate belongs next to the index that guarantees it, and the query is
   * index-only.
   */
  def findAdminForHousehold(household: HouseholdId): Task[Option[DbUser]]

  def delete(id: UserId): Task[Unit]
}

/**
 * #2140 (multi-tenant P5-8): the household directory, keyed by the human-facing `households.slug`
 * (V66/#2131 — unique, lowercase; `name` is NOT unique). The one consumer today is household-aware
 * login: the login request carries an optional slug, resolved here to the tenancy key so
 * `UserRepo.findByUsername(hh, u)` disambiguates a shared username. An unknown slug returns `None`,
 * which the auth path collapses into the same `InvalidCredentials` a bad password produces — no
 * household enumeration.
 */
trait HouseholdRepo {
  def findIdBySlug(slug: String): Task[Option[HouseholdId]]

  // #2132 (multi-tenant P5-2): provisioning reads/writes. `create` inserts a new households row
  // (name + de-duped slug + router cap); `findById` reads it back (the invite-accept path resolves
  // the provisioned household's slug for the SPA cookie, design §3.4). The atomic provisioning
  // transaction itself lives in [[BetaRequestRepo.approveAndProvision]]; `create` backs any
  // non-transactional household creation.
  def create(name: String, slug: String, routerCap: Int = 1): Task[HouseholdId]
  def findById(id: HouseholdId): Task[Option[Household]]

  /**
   * #2164: the inverse — a household's slug by id. Consumed by login only, to return the resolved
   * household's slug on [[wifihaven.shared.LoginResponse]] so the SPA can (re)write the
   * `wh_household` cookie (design §4). Returns None for an id with no row (or the default-only
   * shim's non-default ids).
   */
  def findSlugById(id: HouseholdId): Task[Option[String]]
}

object HouseholdRepo {

  /**
   * #2140: a DB-free repo that knows only the default household (slug `default`). It exists so
   * [[wifihaven.api.auth.AuthServiceLive]] can default its `householdRepo` dependency — every
   * pre-multi-tenant `AuthServiceLive(userRepo, jwtConfig, clock)` construction keeps compiling and
   * resolves the single self-hosted household without a real repo. Production and multi-household
   * tests inject the DB-backed [[HouseholdRepoLive]] instead.
   */
  val defaultOnly: HouseholdRepo = new HouseholdRepo {
    // The default household's slug is `default` — backfilled by V66
    // (`V66__beta_requests_billing_entitlements.sql`: `UPDATE households SET slug = 'default'
    // WHERE id = 1`). This shim mirrors only that one row; every other slug resolves via the
    // DB-backed HouseholdRepoLive.
    def findIdBySlug(slug: String): Task[Option[HouseholdId]] =
      ZIO.succeed(Option.when(slug == "default")(HouseholdId.Default))

    // #2132: the default-only shim is the auth-path slug resolver only — it never provisions.
    // Provisioning always uses the DB-backed HouseholdRepoLive, so these fail loudly if misused.
    def create(name: String, slug: String, routerCap: Int): Task[HouseholdId] =
      ZIO.fail(
        new UnsupportedOperationException("defaultOnly HouseholdRepo cannot create households"),
      )
    def findById(id: HouseholdId): Task[Option[Household]]                    =
      ZIO.fail(
        new UnsupportedOperationException("defaultOnly HouseholdRepo cannot read households"),
      )
    // #2164: the default household's slug is `default`; any other id is unknown to this shim.
    def findSlugById(id: HouseholdId): Task[Option[String]]                   =
      ZIO.succeed(Option.when(id == HouseholdId.Default)("default"))
  }
}

/**
 * #2532 (multi-tenant, epic #2085/#622): the tenancy pass `ProfileRepo` / `DeviceRepo` /
 * `NamedScheduleRepo` received across #2107 / #2108 / #2126, applied here.
 *
 * The methods keyed on an id (`listProfilesForUser`, `listUsersForProfile`, `hasAccess`, `addLink`,
 * `removeLink`, `setProfilesForUser`, `setUsersForProfile`) take NO household parameter,
 * deliberately: `users.id` and `profiles.id` are GLOBALLY unique, so the argument already names
 * exactly one row and there is nothing to disambiguate. Keeping a FOREIGN id out is the [[ownUser]]
 * / [[requireProfileInHousehold]] choke points' job, not the repo's — duplicating the check here
 * would make the boundary two places instead of one.
 *
 * TODO(#2531): that choke-point coverage is not yet complete, so do not read the paragraph above as
 * a statement that every argument is guarded today. It holds for every USER id reaching this trait
 * (`PUT /api/users/{id}/profiles` and `PATCH /api/users/{id}` both `ownUser` first; `POST
 * /api/users` mints the id under `claims.hh`) and for BOTH sides of `setUsersForProfile` (`PUT
 * /api/profiles/{id}/users` runs `requireProfileInHousehold` on the profile AND `ownUser` on every
 * body `userId`). It does NOT hold for the `profileIds` argument of `setProfilesForUser`: those
 * same three user routes pass a caller-supplied `List[ProfileId]` straight through with no
 * `requireProfileInHousehold`, so an admin can still bind another household's profile. #2531 fixes
 * that at those routes — the resolution stays at the choke point, not here. Routes named by
 * method+path, not line number, so an edit above them cannot silently rot this paragraph.
 *
 * A USERNAME is different: V65 made it unique only per household (`UNIQUE(household_id, username)`)
 * and V68 dropped the global unique, so a bare username names a SET of users across tenants — which
 * is exactly why #2140 gave [[UserRepo.findByUsername]] a household parameter.
 * [[listProfilesForUsername]] therefore takes one too.
 *
 * There is likewise no cross-tenant `listAllMappings` — the same rule [[ProfileRepo]] states for
 * `listAll`: an all-tenant read must be an EXPLICIT `foreach(distinctHouseholds)` loop so it can
 * never be written accidentally in a request path.
 */
trait UserProfileRepo {
  def listProfilesForUser(userId: UserId): Task[List[ProfileId]]

  /**
   * The profiles linked to `username` WITHIN `household`. Household-scoped because a username is
   * unique only per household (V65/V68) — `BetaService.FirstAdminUsername` means every
   * beta-provisioned household has a user named `admin`.
   */
  def listProfilesForUsername(
      household: HouseholdId,
      username: String,
  ): Task[List[ProfileId]]
  def listUsersForProfile(profileId: ProfileId): Task[List[UserId]]

  /** (userId, profileId) mappings whose USER belongs to `household`. Backs `GET /api/users`. */
  def listMappingsForHousehold(household: HouseholdId): Task[List[(UserId, ProfileId)]]
  def setProfilesForUser(userId: UserId, profileIds: List[ProfileId]): Task[Unit]
  def setUsersForProfile(profileId: ProfileId, userIds: List[UserId]): Task[Unit]
  def addLink(userId: UserId, profileId: ProfileId): Task[Unit]
  def removeLink(userId: UserId, profileId: ProfileId): Task[Unit]
  def hasAccess(userId: UserId, profileId: ProfileId): Task[Boolean]
}

trait ProfileRepo {

  /**
   * #2257 (multi-tenant hardening, epic #2085/#622): the DISTINCT household ids that own at least
   * one profile — the tenant enumeration a genuinely all-tenant batch (rollup / ambient-learn jobs,
   * `TimeStatusService.dayStateAll`) uses to iterate households and union
   * `listAllForHousehold(hh)`. There is deliberately NO cross-tenant `listAll` on devices/profiles:
   * an all-tenant read must be an EXPLICIT `foreach(distinctHouseholds)(listAllForHousehold)` loop,
   * so it can never be written accidentally in a request or push path (the #2251/#2120 leak class).
   * Households with no profile have nothing for a per-profile batch to process, so enumerating from
   * `profiles` is exactly the right set. For the single backfill install this is
   * `List(HouseholdId.Default)`.
   */
  def distinctHouseholds: Task[List[HouseholdId]]

  /** All profiles, including the global sentinel. Used by `PolicyService.snapshot` only. */
  def listAllIncludingGlobal: Task[List[Profile]]

  /**
   * #2107 (multi-tenant, epic #622): household-scoped [[listAllIncludingGlobal]] — all profiles
   * (incl. the global sentinel) belonging to `household`. Used by the household-scoped
   * `PolicyService.snapshot(household)` so a router only ever sees its own household's profiles.
   * For the single backfill household (`HouseholdId.Default`) this returns exactly the same rows as
   * the global variant.
   */
  def listAllIncludingGlobalForHousehold(household: HouseholdId): Task[List[Profile]]

  /**
   * #2108 (multi-tenant sub-issue E): household-scoped [[listAll]] — non-global profiles belonging
   * to `household`. Backs the user-facing `GET /api/profiles` / `/time/status` reads so an
   * admin/adult sees every profile IN THEIR HOUSEHOLD and never across (design §2 gap 4). The
   * `is_global=FALSE` filter is preserved (the sentinel is a wire mechanism, never listed). For the
   * single backfill household (`HouseholdId.Default`) this returns the same rows as `listAll`.
   */
  def listAllForHousehold(household: HouseholdId): Task[List[Profile]]

  /**
   * #2108: the household-scoped access probe used by the write/read guards — the household that
   * owns `id`, or None if the profile does not exist. A guard rejects (404) any target whose
   * household ≠ the caller's `claims.hh`, so an hh-A admin cannot address an hh-B profile even with
   * the `admin` role (design §7 pin 2).
   */
  def householdOf(id: ProfileId): Task[Option[HouseholdId]]

  /** The single `is_global=TRUE` sentinel row, or None if not yet seeded. */
  def getGlobal: Task[Option[Profile]]

  /**
   * #2108 (multi-tenant sub-issue E): household-scoped [[getGlobal]] — the `is_global=TRUE`
   * sentinel belonging to `household`, or None. Backs `GET /api/profiles/global` so an hh-A admin
   * reads only their own household's sentinel (the global-policy layer is per-household, design §2
   * gap 4). For the single backfill household this is the one existing sentinel; a household with
   * no sentinel row yet gets None (404), never another household's.
   */
  def getGlobalForHousehold(household: HouseholdId): Task[Option[Profile]]
  def findById(id: ProfileId): Task[Option[Profile]]
  // #2130: `householdId` stamps the new profile with the creating admin's
  // household (from their JWT). Defaults to the single-install backfill
  // household so pre-multi-tenant callers stay tenant-safe (#2106 precedent).
  def create(
      name: String,
      cats: List[BlocklistId],
      householdId: HouseholdId = HouseholdId.Default,
  ): Task[ProfileId]
  def update(p: Profile): Task[Unit]
  def delete(id: ProfileId): Task[Unit]

  // #423: per-field setters for the PATCH handler so concurrent PATCHes
  // touching disjoint fields don't race via a full-row rewrite. Each method
  // issues a targeted `UPDATE profiles SET <col>=? WHERE id=?` — the absent
  // fields aren't touched by the SQL, so a "tab A patches paused, tab B
  // patches name" interleaving preserves both edits. The full-shape
  // [[update]] above stays for the legacy PUT route, which is documented as
  // a full-replace.
  def setName(id: ProfileId, name: String): Task[Unit]
  def setBlockedCategories(id: ProfileId, cats: List[BlocklistId]): Task[Unit]
  def setPaused(id: ProfileId, paused: Boolean): Task[Unit]
  def setFailureMode(id: ProfileId, mode: FailureMode): Task[Unit]
  def setBlockIpOnly(id: ProfileId, v: Boolean): Task[Unit]
  def setCrossDeviceOverlapMode(id: ProfileId, mode: CrossDeviceOverlapMode): Task[Unit]
  def setPauseMode(id: ProfileId, mode: PauseMode): Task[Unit]
  def setDefaultDeny(id: ProfileId, v: Boolean): Task[Unit]
}

/**
 * #1069: CRUD over the household-scoped `named_schedules` table + its typed `schedule_windows`
 * child rows, plus the `profile_schedule_rules` join that attaches schedules to profiles. No
 * business logic — name validation and snapshot expansion live in the routes / PolicyService. A
 * schedule's windows (and a profile's block-schedule set) are replaced wholesale on write (same
 * shape as [[AppRepo.setHosts]]).
 */
trait NamedScheduleRepo {
  // #2126 (multi-tenant, epic #2085/#622): the household-scoped list read backing
  // `GET /api/schedules`. V72 added `named_schedules.household_id`, so the read filters on the
  // caller's `claims.hh` — an unattached, freshly-created schedule stays visible to ITS OWN
  // household (the authoring-UI requirement) but never leaks across the tenant boundary. There is
  // deliberately NO cross-tenant `listAll`: the bare all-tenant read was the leak (design §2 gap 4).
  def listAllForHousehold(household: HouseholdId): Task[List[NamedSchedule]]
  def findById(id: NamedScheduleId): Task[Option[NamedSchedule]]
  // #2572: the name-taken probe behind the create/rename 409, scoped to the caller's household.
  // Unscoped it answered "taken" from a row in a household the caller cannot see — a cross-tenant
  // namespace collision and a weak enumeration oracle for other households' schedule names. It is
  // also the read half of widening `named_schedules`' global `UNIQUE (name)` to
  // `UNIQUE (household_id, name)`: this reads with Doobie `.option`, which raises on multiple rows,
  // so it must be scoped BEFORE the schema permits two households to hold the same name.
  def findByName(name: String, household: HouseholdId): Task[Option[NamedSchedule]]
  // #2126: the household-scoped access probe used by the per-id route guards — the household that
  // owns `id`, or None if the schedule does not exist. A guard rejects (404) any target whose
  // household ≠ the caller's `claims.hh`, mirroring `ProfileRepo.householdOf`.
  def householdOf(id: NamedScheduleId): Task[Option[HouseholdId]]
  // #2126: `household` stamps the new schedule with the creating admin's household (from their JWT),
  // never left to V72's DEFAULT 1. Defaults to the single-install backfill household so
  // pre-multi-tenant callers stay tenant-safe (#2130 precedent).
  def create(
      name: String,
      description: Option[String],
      windows: List[ScheduleWindow],
      household: HouseholdId = HouseholdId.Default,
  ): Task[
    NamedScheduleId,
  ]
  def update(
      id: NamedScheduleId,
      name: String,
      description: Option[String],
      windows: List[ScheduleWindow],
  ): Task[Unit]
  def delete(id: NamedScheduleId): Task[Unit]

  // ── profile_schedule_rules (block-mode only for now; allow-mode deferred) ──

  /** Ids of the named schedules attached to `pid` as block schedules. */
  def blockScheduleIdsForProfile(pid: ProfileId): Task[List[NamedScheduleId]]

  /** Replace `pid`'s block-mode schedule attachments with exactly `ids` (de-duped). */
  def setProfileBlockSchedules(pid: ProfileId, ids: List[NamedScheduleId]): Task[Unit]

  /**
   * Windows of every block-mode schedule attached to `pid` (flattened across its
   * `profile_schedule_rules`), or `Nil` if none. PolicyService folds these into the profile's
   * scheduled-downtime decision.
   */
  def windowsForProfile(pid: ProfileId): Task[List[ScheduleWindow]]

  /**
   * #2264 (follow-up to #2257, epic #2085/#622): batched [[windowsForProfile]] for the
   * household-scoped `TimeStatusService.dayStateAll` batch — avoids an N+1, and the
   * `profiles.household_id` join (index-backed by `idx_profiles_household`, V65) keeps the read
   * inside the tenant whose day-states are being computed. There is deliberately NO all-tenant
   * variant: the previous `windowsForAllProfiles` read every household's `profile_schedule_rules`
   * rows into memory on the screen-time hot path even though only the scoped household's profiles
   * were ever iterated. A genuinely all-tenant caller must be an explicit
   * `foreach(profileRepo.distinctHouseholds)` loop (see `ProfileRepo.distinctHouseholds`).
   */
  def windowsForHouseholdProfiles(
      household: HouseholdId,
  ): Task[Map[ProfileId, List[ScheduleWindow]]]
}

/**
 * No-named-schedules stub. Lets TimeStatusServiceLive's many direct test constructions keep their
 * existing arity (the real repo is wired via the layer); a profile then has no named-schedule
 * downtime, leaving the legacy `schedules` behaviour exactly as before.
 */
object NoopNamedScheduleRepo extends NamedScheduleRepo {
  def listAllForHousehold(household: HouseholdId)                          = ZIO.succeed(Nil)
  def findById(id: NamedScheduleId)                                        = ZIO.succeed(None)
  def findByName(name: String, household: HouseholdId)                     = ZIO.succeed(None)
  def householdOf(id: NamedScheduleId)                                     = ZIO.succeed(None)
  def create(
      name: String,
      description: Option[String],
      windows: List[ScheduleWindow],
      household: HouseholdId = HouseholdId.Default,
  ) =
    ZIO.succeed(NamedScheduleId(0L))
  def update(
      id: NamedScheduleId,
      name: String,
      description: Option[String],
      windows: List[ScheduleWindow],
  ) = ZIO.unit
  def delete(id: NamedScheduleId)                                          = ZIO.unit
  def blockScheduleIdsForProfile(pid: ProfileId)                           = ZIO.succeed(Nil)
  def setProfileBlockSchedules(pid: ProfileId, ids: List[NamedScheduleId]) = ZIO.unit
  def windowsForProfile(pid: ProfileId)                                    = ZIO.succeed(Nil)
  def windowsForHouseholdProfiles(household: HouseholdId)                  = ZIO.succeed(Map.empty)
}

trait HouseholdSettingsRepo {

  /**
   * #2107 (multi-tenant, epic #622): the settings row for `household` (`WHERE household_id = ?`).
   * Used by `PolicyService.snapshot(household)` / `decide(household, …)` so a router reads only its
   * own household's settings (daily-reset tz, unmanaged-MAC policy, block-encrypted-DNS), and since
   * #2533 by `HouseholdSettingsRoutes` too.
   *
   * #2533: this is the ONLY read. The unscoped `get` (hardcoded `WHERE id=1`) was deleted, not
   * deprecated — while it existed, `HouseholdSettingsRoutes` read and wrote household #1's row for
   * every caller, so enforcement (which already read here) and the SPA silently diverged for every
   * non-default household. A parallel unscoped path is exactly how that regresses; per AGENTS.md
   * §single-source-of-truth the fix is COLLAPSE. A call site that genuinely wants the operator
   * household passes `HouseholdId.Default` explicitly and says why.
   */
  def getForHousehold(household: HouseholdId): Task[HouseholdSettings]

  /**
   * #2533: the write twin of [[getForHousehold]] — replaces `household`'s OWN settings row (`WHERE
   * household_id = ?`). Replaces the unscoped `update(s)`, which wrote `WHERE id=1` regardless of
   * the caller. Fails if `household` owns no row (a provisioning bug — every household gets one
   * from [[HouseholdSeed.insertHousehold]] / [[HouseholdSeed.backfillMissingSettings]]), rather
   * than silently writing nothing.
   */
  def update(household: HouseholdId, s: HouseholdSettings): Task[Unit]

  /**
   * #2382: the server-level per-household "disable enforcement" escape hatch, read straight off the
   * household's OWN `household_settings` row (`WHERE household_id = ?`, no id=1 fallback), so one
   * household's flag can never be read for another (multi-tenant isolation, epic #2085). Every
   * household owns its row post-#2386, so a missing row (a provisioning bug) reads `false` —
   * failing toward ENFORCING, never leaking another tenant's state. This is the ONE narrow field
   * read without the full `getForHousehold` decode, so the [[wifihaven.api.policy.PolicyService]]
   * permissive gate doesn't pay for the whole settings row.
   */
  def enforcementDisabled(household: HouseholdId): Task[Boolean]

  /**
   * #2382: set the escape-hatch flag on the household's OWN row (`WHERE household_id = ?`). Returns
   * true when a row was updated, false when the household has no settings row — the route maps the
   * latter to 404 rather than silently succeeding. (Post-#2386 every household has a row, so
   * `false` signals a genuinely unknown household.)
   */
  def setEnforcementDisabled(household: HouseholdId, disabled: Boolean): Task[Boolean]

  /** Insert the default row if missing, using `defaultZone` as the install-time tz. */
  def ensureDefault(defaultZone: ZoneId): Task[Unit]
}

trait TimeLimitRepo {
  def findForProfile(pid: ProfileId): Task[Option[TimeLimit]]
  def upsert(pid: ProfileId, mins: Int): Task[Unit]
  def delete(pid: ProfileId): Task[Unit]
  def listAll: Task[List[TimeLimit]]

  /**
   * #2108 (multi-tenant sub-issue E): household-scoped [[listAll]] — time limits for profiles in
   * `household`. `time_limits` carries `profile_id` (FK to a now-scoped `profiles`), so it inherits
   * the household transitively via the join (design §0.1 "scoped transitively") — no denormalized
   * column. Backs `GET /api/time/status`.
   */
  def listAllForHousehold(household: HouseholdId): Task[List[TimeLimit]]
}

/**
 * Post-#764 the `site_time_limits` table is dropped. This repo now synthesizes the `AppTimeLimit`
 * shape from `app_policy_assignments` rows — emitting one row per (assignment × host) — that the
 * [[wifihaven.api.policy.ProfileAppDispositions]] collapse folds into every downstream per-app
 * projection. Synthetic `id` is `0L`; the canonical assignment identity is the `assignmentId`
 * field, carried from `app_policy_assignments.id`.
 *
 * Post-#1630 the SQL filter is dropped: rows for EVERY mode (Allowed, Blocked, TimeLimited) are
 * returned, with `mode` carried on the row. The single fold case-analyzes on `mode` to route each
 * disposition to the right bucket. Before #1630 the repo filtered `WHERE mode='time_limited'`,
 * which hid `mode=Allowed, exemptFromDaily=true` assignments from the daily-usage path and let
 * their traffic count against the daily total even though the SPA contract was that "Khan doesn't
 * count". The collapse removes that structural seam.
 */
trait AppTimeLimitRepo {
  def listForProfile(pid: ProfileId): Task[List[AppTimeLimit]]

  /**
   * #2108 (multi-tenant sub-issue E): app-limit rows for profiles in `household`. Scoped
   * transitively via `app_policy_assignments.profile_id → profiles` (design §0.1). Backs `GET
   * /api/time/status` and the dashboard NOW builder.
   *
   * #2568: there is deliberately NO cross-tenant `listAll` here. Its last caller
   * (`DashboardNowRoutes.computeNow`, reached when its `household` was still optional) is gone, and
   * the bare all-tenant read is removed rather than orphaned — the #2257 precedent for devices and
   * profiles. Removing it type-enforces the scoping instead of relying on callers to choose.
   */
  def listAllForHousehold(household: HouseholdId): Task[List[AppTimeLimit]]
}

trait DeviceRepo {

  /**
   * #2107 (multi-tenant, epic #622): household-scoped device read — devices belonging to
   * `household`. Used by `PolicyService.snapshot(household)` and `decide(household, …)` so a router
   * only sees / resolves its own household's devices (a same-MAC row in another household is never
   * returned), and by every user-facing route / SPA push builder so it can never read across the
   * tenant boundary (#2257). There is deliberately NO cross-tenant `listAll` on devices: an
   * all-tenant read must be an EXPLICIT
   * `foreach(profileRepo.distinctHouseholds)(listAllForHousehold)` loop (rollup / ambient-learn
   * jobs, `TimeStatusService.dayStateAll`), so it can never be written accidentally in a request or
   * push path. For the single backfill household (`HouseholdId.Default`) this returns every device.
   */
  def listAllForHousehold(household: HouseholdId): Task[List[Device]]

  /**
   * #2257: the devices assigned to one profile. A profile belongs to exactly one household, so this
   * is inherently household-scoped — it replaces the old `listAll.filter(_.profileId == pid)` in
   * the per-profile `TimeStatusService` / `AppUsedRollupService` paths, which scanned every
   * household's rows only to keep the ones for a single profile. `devices` is a small fleet-bounded
   * table (one row per device, not a traffic-growth table), so the `WHERE profile_id=` filter
   * strictly beats the prior whole-table scan even without a dedicated index.
   */
  def listForProfile(profileId: ProfileId): Task[List[Device]]

  /**
   * #2312: household-scoped. The old global `findByMac` (WHERE d.mac=$mac + `.option`) THREW ("more
   * than one row") once the same MAC existed in two households (V74/V75), crashing the block page
   * ([[BlockedRoutes]]) and the public access-request intake ([[AlertRoutes]]). It now delegates to
   * [[findByMacInHousehold]] — one query, one source of truth; V65's uq_devices_household_mac
   * guarantees ≤1 row per household so `.option` is safe. Defaults to `HouseholdId.Default` for the
   * single-household test call sites; the unauthenticated block-page callers pass
   * `HouseholdId.Default` until per-request household derivation lands — #2322 for the
   * access-request write path, #2569 for the `GET /api/blocked` read path.
   */
  def findByMac(mac: MacAddress, household: HouseholdId = HouseholdId.Default): Task[Option[Device]]

  /**
   * #2108 (multi-tenant sub-issue E): household-scoped [[findByMac]] — the device row for
   * `(household, mac)`, or None. The user-facing device routes (`GET`/`PATCH`/`DELETE
   * /api/devices/:mac`) resolve through this so an hh-A admin gets a clean 404 for an hh-B MAC
   * rather than reading/writing across the tenant boundary (design §7 pin 2). Index-backed by V65's
   * uq_devices_household_mac leading column.
   */
  def findByMacInHousehold(mac: MacAddress, household: HouseholdId): Task[Option[Device]]

  /**
   * #2566/#2322: the household that owns `mac`, lowest id first when several do — the ONLY
   * cross-tenant read on devices, and deliberately so.
   *
   * This is the pre-#2322 `createAccessRequest` COALESCE subquery, lifted out of that INSERT and
   * named. It exists solely as the FALLBACK for the unauthenticated block-page intake when the
   * redirect carried no verifiable `bpt` (a pre-token agent). Without it that path would resolve to
   * household 1, find no device, and reject a request it used to file correctly — which is a
   * regression for every household except #1, exactly during the API-ahead-of-agent window.
   *
   * It is `ORDER BY household_id LIMIT 1`, so for a MAC shared across households it is arbitrary —
   * which is what #2322 is about. That ambiguity is ACCEPTED here and nowhere else: it is strictly
   * what the code already did, it applies only when we have no better answer, and it disappears
   * per-router as agents start stamping the token. Do NOT reach for this from any authenticated
   * path; those have a household in scope already.
   */
  def findOwningHousehold(mac: MacAddress): Task[Option[HouseholdId]]

  /**
   * #2108: `household` keys the row constructively. The user-facing device write passes the
   * caller's `claims.hh`; ingest passes the router's household. `ON CONFLICT (household_id, mac)`
   * (V65's uq_devices_household_mac) so a first-seen MAC lands in the writer's household and the
   * same MAC in another household is a DIFFERENT row — a writer cannot address another household's
   * row by construction (design §3.2.2). Defaults to `HouseholdId.Default` for the single-household
   * test/ seed call sites.
   */
  def upsert(
      mac: MacAddress,
      name: String,
      pid: Option[ProfileId],
      ip: String,
      household: HouseholdId = HouseholdId.Default,
  ): Task[DeviceId]

  /**
   * #2125: household-scoped. Now that the same MAC can exist in two households (V74 dropped the
   * global `devices_mac_key`), the UPDATE is AND-scoped to `household` so it can only touch its own
   * household's row. Defaults to `HouseholdId.Default` for the single-household test call sites.
   * (Production ingest uses `touchLastSeen`/`touchLastSeenBatch`, which are already
   * household-scoped.)
   */
  def updateLastSeen(
      mac: MacAddress,
      ip: String,
      household: HouseholdId = HouseholdId.Default,
  ): Task[Unit]

  /**
   * Update last_seen_ip/at only if the device row exists. Used by router ingest where we don't want
   * to create rows here (events does that).
   */
  def touchLastSeen(
      mac: MacAddress,
      ip: Option[IpAddress],
      at: Instant,
      household: HouseholdId = HouseholdId.Default,
  ): Task[Int]

  /**
   * #1511: batched version of [[touchLastSeen]]. One UPDATE per distinct (mac, ip) pair the caller
   * wants to refresh, issued as a single Doobie `updateMany` instead of N round trips on the
   * `/api/router/usage` hot path. Same per-row UPDATE template — does NOT create rows. Empty input
   * is a no-op. Returns total rows touched across the batch.
   */
  def touchLastSeenBatch(
      items: List[(MacAddress, Option[IpAddress])],
      at: Instant,
      household: HouseholdId = HouseholdId.Default,
  ): Task[Int]

  /**
   * Insert a row for a previously unknown device with NULL profile_id, or refresh last_seen_ip/at
   * on an existing row. Used by /api/router/events.
   */
  def upsertUnknown(
      mac: MacAddress,
      name: String,
      ip: Option[IpAddress],
      at: Instant,
      household: HouseholdId = HouseholdId.Default,
  ): Task[DeviceId]

  /**
   * Rename a device only if its current name is an auto-generated placeholder ("unknown" or
   * "device-XXXXXX"). Used by router ingest to upgrade names once a later DHCP lease carries a real
   * hostname (#249). Returns the number of rows updated (0 if the name was admin-curated).
   */
  def renameIfAutoGenerated(
      mac: MacAddress,
      newName: String,
      household: HouseholdId = HouseholdId.Default,
  ): Task[Int]

  /**
   * #2125 (multi-tenant contract half of #2108): household-scoped delete. Now that the same MAC can
   * exist in two households (V74 dropped the global `devices_mac_key`), a global `WHERE mac=$mac`
   * delete would remove BOTH households' rows — a cross-tenant leak. The user-facing DELETE route
   * passes the caller's `claims.hh` (it already resolves the row via `findByMacInHousehold` first),
   * so a writer can only delete its own household's device. Index-backed by V65's
   * uq_devices_household_mac.
   */
  def delete(mac: MacAddress, household: HouseholdId = HouseholdId.Default): Task[Unit]
}

/**
 * Generic admin-action feed (formerly device_alerts, #711). Every row is something the admin needs
 * to decide about; lifecycle is `pending → approved | denied`. The schema (see V33) supports a
 * second `access_request` kind alongside `new_device`; that kind's writers and side-effects land in
 * #960 — this trait only exposes the new_device path for now.
 */
trait AlertRepo {

  /**
   * #711: raise a new-device alert. Idempotent per `(household, mac)` — if a row already exists for
   * this household's MAC and kind, the existing one wins regardless of its status, so re-ingesting
   * the same first-seen event doesn't resurrect a decided alert or duplicate a pending one.
   *
   * #2283: `household` is the discovering router's household. Dedup is scoped to it (NOT global) so
   * that once V74 lets the SAME MAC be discovered in two households, EACH household raises its own
   * new_device alert instead of one shared row. Production always passes the router's household;
   * the default keeps single-household test call sites terse.
   */
  def raiseNewDevice(
      mac: MacAddress,
      firstSeenAt: Instant,
      household: HouseholdId = HouseholdId.Default,
  ): Task[Unit]

  /**
   * #960: create an access-request alert. `profileId` is denormalised at insert time so the row
   * survives a later device→profile reassignment.
   *
   * #2322: `household` is passed by the caller — derived from the router-bound block-page token on
   * the redirect — rather than inferred in-SQL from the device row. The old `COALESCE((SELECT ...
   * ORDER BY d.household_id LIMIT 1), 1)` was deterministic but arbitrary once a MAC exists in more
   * than one household (V74/V75): it could file a child's request against someone else's household,
   * where their admin sees it and the real one doesn't.
   *
   * `(household, mac)` MUST name a real `devices` row — V79's composite
   * `alerts_household_mac_fkey`. The caller checks that first, so a MAC not enrolled in this
   * household gets a typed 404 rather than a constraint violation surfacing as a 503.
   */
  def createAccessRequest(
      household: HouseholdId,
      mac: MacAddress,
      profileId: Option[ProfileId],
      host: Hostname,
      requestKind: AccessRequestKind,
      note: Option[String],
      createdAt: Instant,
  ): Task[AlertId]

  /**
   * Debounce probe for access-request creates: most recent pending access_request row for `(mac,
   * host)` since `since`, if any.
   *
   * #2566: scoped to `household` (V79's NOT NULL `alerts.household_id`, the same key
   * [[listForHousehold]] reads). Unscoped, this both cross-coupled households — A's pending row
   * suppressed B's genuine request for the same MAC — and returned A's row, note and all, to the
   * unauthenticated caller that hit the debounce.
   */
  def findRecentAccessRequest(
      household: HouseholdId,
      mac: MacAddress,
      host: Hostname,
      since: Instant,
  ): Task[Option[Alert]]

  def findById(id: AlertId): Task[Option[Alert]]

  /**
   * #2564: the household that owns `id`, for the per-id route guards. `findById` above is
   * deliberately unscoped (it is also the post-decide re-read), so `POST /api/alerts/{id}/approve`
   * and `/deny` gate on this instead — see `requireAlertInHousehold`.
   *
   * Reads the alert's OWN `household_id` (V78/V79, NOT NULL), never the joined device's: post-V74 a
   * MAC can exist in two households, so the device join is ambiguous, and an orphaned alert (device
   * deleted) still has an authoritative tenancy key. `None` means no such row.
   */
  def householdOf(id: AlertId): Task[Option[HouseholdId]]

  /** Pending-only when `includeAll=false`. Ordered newest first. */
  def list(includeAll: Boolean): Task[List[Alert]]

  /**
   * #2108 (multi-tenant sub-issue E): household-scoped [[list]] — alerts belonging to `household`.
   * Backs `GET /api/alerts` so an admin sees only their own household's alerts.
   *
   * Scoped on the alert's OWN `household_id` (V78), the same key [[householdOf]] reads. This
   * originally scoped transitively through a join to the household-scoped `devices` row, because
   * `alerts` had no household column; #2283 replaced that when V74 let one MAC exist in two
   * households and made the join ambiguous.
   */
  def listForHousehold(includeAll: Boolean, household: HouseholdId): Task[List[Alert]]

  /**
   * Returns the number of rows that transitioned; 0 means the row was already decided.
   * `grantedMinutes` is recorded by extension approvals when #960 lands; for new_device rows it is
   * always None.
   */
  def decide(
      id: AlertId,
      newStatus: AlertStatus,
      decidedAt: Instant,
      decidedBy: String,
      grantedMinutes: Option[Int],
  ): Task[Int]
}

trait BlocklistRepo {
  def insertBatch(domains: List[(String, String)]): Task[Int]
  def clearCategory(cat: BlocklistId): Task[Unit]
  def listCategories: Task[List[BlocklistId]]
  def countByCategory: Task[List[(BlocklistId, Int)]]
  def loadCategory(cat: BlocklistId): Task[Set[Hostname]]
  def loadAll: Task[Map[BlocklistId, Set[Hostname]]]

  // #1983: for each of the given EXACT domains, which category blocklists
  // contain it. The caller passes the bounded set of app-host apex candidates
  // (each host + its parent suffixes), so this is O(candidates) index probes on
  // `idx_blocklist_domain` — NOT an O(apps × hosts × domains) scan of the large
  // blocklist_domains table. Returns only domains that matched at least one
  // category; absent keys mean "in no blocklist".
  def categoriesForDomains(domains: List[String]): Task[Map[String, List[BlocklistId]]]

  // #958: metadata-table operations backing the SPA management page and
  // the bundled-list startup seeder. `summaries` joins blocklists with
  // a count from blocklist_domains so the SPA renders host counts
  // without N round-trips.
  def upsertMeta(
      id: BlocklistId,
      name: String,
      description: Option[String],
      bundled: Boolean,
      source: Option[String],
      lastBuiltAt: java.time.Instant,
  ): Task[Unit]
  def summaries: Task[List[wifihaven.shared.BlocklistSummary]]
  def findMeta(id: BlocklistId): Task[Option[wifihaven.shared.BlocklistSummary]]
}

trait TimeUsageRepo {

  /**
   * Increment seconds + byte counters for (mac, host, date). Repeats are *additive* — the caller is
   * responsible for idempotency at the request level (traffic_reports unique key).
   *
   * `seconds` is the bucket-presence count (bucket duration credited in full to every host the
   * device touched in the bucket). `proportionalSeconds` is the same bucket duration weighted by
   * this host's byte share of the bucket (#715) — a fairer wall-clock attribution for per-host
   * screen-time UI. Daily-cap math reads neither directly: it goes through the presence-based
   * `totalMinutesByMac` over `traffic_reports`.
   */
  def incrementSecondsAndBytes(
      mac: MacAddress,
      host: HostId,
      date: LocalDate,
      seconds: Long,
      bytesIn: Long,
      bytesOut: Long,
      proportionalSeconds: Long = 0L,
      household: HouseholdId = HouseholdId.Default,
  ): Task[Unit]

  /**
   * #1511: batched upsert of (mac, host, date) increments. Collapses what used to be one round-trip
   * per (mac, host) in the `/api/router/usage` hot path into a single Doobie `updateMany` against
   * the same upsert template `incrementSecondsAndBytes` runs — same ON CONFLICT additive semantics.
   * Empty input is a no-op.
   *
   * #2108 (multi-tenant sub-issue E): `household` (the router's) is stamped into every inserted row
   * and is part of the ON CONFLICT key, so a router's usage only ever accretes onto its own
   * household's `time_usage` rows — the same MAC in another household is a different row (§3.4).
   */
  def incrementSecondsAndBytesBatch(
      rows: List[TimeUsageIncrement],
      household: HouseholdId = HouseholdId.Default,
  ): Task[Unit]

  /** Read seconds_used for a (mac, host, date) row. Returns 0 if no row. */
  def getSecondsUsed(mac: MacAddress, host: HostId, date: LocalDate): Task[Long]

  /** Read (seconds_used, bytes_in, bytes_out) for a (mac, host, date) row. */
  def getSecondsAndBytes(
      mac: MacAddress,
      host: HostId,
      date: LocalDate,
  ): Task[(Long, Long, Long)]

  /**
   * #715: read the byte-share-weighted proportional seconds for a (mac, host, date) row. Returns 0
   * if no row. Cap math doesn't read this; it's stored so the column stays in sync with
   * `seconds_used` for any consumer that wants a fairer per-host attribution.
   */
  def getProportionalSeconds(mac: MacAddress, host: HostId, date: LocalDate): Task[Long]
  def listForDevice(mac: MacAddress, date: LocalDate): Task[List[TimeUsage]]
  def listForDeviceMacs(macs: List[MacAddress], date: LocalDate): Task[List[TimeUsage]]
  def snapshotAll(date: LocalDate): Task[Map[(MacAddress, HostId), Int]]
}

trait TimeExtensionRepo {
  def getTotalExtension(mac: MacAddress, date: LocalDate): Task[Int]
  def grant(
      mac: MacAddress,
      date: LocalDate,
      mins: Int,
      by: String,
      note: Option[String],
      household: HouseholdId = HouseholdId.Default,
  ): Task[TimeExtensionId]
  def listForDevice(mac: MacAddress, date: LocalDate): Task[List[TimeExtension]]
  def snapshotAll(date: LocalDate): Task[Map[MacAddress, Int]]
  // Profile-level extension methods (V5+)
  // #2130: `household` scopes the profile-keyed grant like [[grant]] does for
  // the MAC-keyed one (#2108); defaults to the backfill household.
  def grantForProfile(
      profileId: ProfileId,
      date: LocalDate,
      mins: Int,
      by: String,
      note: Option[String],
      household: HouseholdId = HouseholdId.Default,
  ): Task[TimeExtensionId]
  def getProfileTotalExtension(profileId: ProfileId, date: LocalDate): Task[Int]
  def listForProfile(profileId: ProfileId, date: LocalDate): Task[List[TimeExtension]]

  /**
   * #2264 (follow-up to #2257): per-profile granted extension minutes for `date`, scoped to
   * `household` via the `profiles.household_id` join (index-backed by `idx_profiles_household`,
   * V65). Replaces the all-tenant `snapshotAllByProfile`, which read every household's
   * `time_extensions` rows into memory inside the household-scoped `dayStateAll` batch. No
   * all-tenant variant — see `ProfileRepo.distinctHouseholds`.
   *
   * Scoped on the PROFILE's household rather than on `time_extensions.household_id`, deliberately.
   * `grantForProfile` does NOT derive the household from the profile — it stamps whatever the
   * caller passes, and the parameter DEFAULTS to `HouseholdId.Default`, so a caller that omits it
   * stamps household 1 no matter who owns the profile. The two columns agree today only because the
   * grant routes run behind a household guard that makes caller-household == profile-household. The
   * profile join is the stronger predicate: it stays correct even for a row whose
   * `time_extensions.household_id` was mis-stamped, and it keys the map by exactly the profiles the
   * caller iterates.
   */
  def snapshotByProfileForHousehold(
      household: HouseholdId,
      date: LocalDate,
  ): Task[Map[ProfileId, Int]]
}

/**
 * #1511: row shape for [[TimeUsageRepo.incrementSecondsAndBytesBatch]]. One per (mac, host, date)
 * increment a `/api/router/usage` batch wants to apply; semantics match the per-arg
 * `incrementSecondsAndBytes` exactly.
 */
case class TimeUsageIncrement(
    mac: MacAddress,
    host: HostId,
    date: LocalDate,
    seconds: Long,
    bytesIn: Long,
    bytesOut: Long,
    proportionalSeconds: Long,
)

case class TrafficReportInsert(
    routerId: RouterId,
    mac: MacAddress,
    ip: Option[IpAddress],
    host: HostId,
    date: LocalDate,
    periodStart: Instant,
    periodEnd: Instant,
    activeSeconds: Int,
    bytesIn: Long,
    bytesOut: Long,
    // #730: destination IP the bytes were attributed to. Optional because
    // pre-#730 agents do not emit it; stored as NULL on traffic_reports.dest_ip
    // for those records.
    destIp: Option[IpAddress] = None,
    // #2025: the real activity envelope (first/last growth-sample wall-clock).
    // Optional because pre-#2025 agents do not emit it; stored as NULL on
    // traffic_reports.active_start / active_end for those records, and
    // Presence.spanOf falls back to the [period_start, period_end] flush window.
    activeStart: Option[Instant] = None,
    activeEnd: Option[Instant] = None,
)

case class BlockEventInsert(
    mac: Option[MacAddress],
    host: HostId,
    reason: BlockReason,
)

case class ConnectionEventInsert(
    routerId: RouterId,
    mac: Option[MacAddress],
    host: HostId,
    destIp: Option[IpAddress],
    allowed: Boolean,
    reason: BlockReason,
    ts: Instant,
    // #338: client-supplied idempotency key. None → SQL COALESCEs to
    // gen_random_uuid() so older agents that don't ship an eventId keep
    // inserting cleanly (one fresh UUID per replay, no dedup possible).
    eventId: Option[java.util.UUID] = None,
    // #720: server-side FQDN attribution looked up from prior fqdn-typed
    // events sharing (router_id, dest_ip). NULL when host is already fqdn
    // or when no sibling resolution was found within the lookup window.
    resolvedHost: Option[Hostname] = None,
)

trait RouterRepo {
  def listAll: Task[List[Router]]

  /**
   * #2108 (multi-tenant sub-issue E): household-scoped [[listAll]] — routers belonging to
   * `household`. Backs `GET /api/routers` so an admin enumerates only their own household's routers
   * (design §2 gap 4). Index-backed by V65's idx_routers_household.
   */
  def listAllForHousehold(household: HouseholdId): Task[List[Router]]
  def findById(id: RouterId): Task[Option[Router]]
  def findByEnrollmentTokenHash(h: Sha256Hex): Task[Option[Router]]
  def findByTokenHash(h: Sha256Hex): Task[Option[Router]]
  // #2106: `householdId` stamps the new router with the creating admin's
  // household (resolved from their JWT at the enrollment-creation route).
  // Defaults to the single-install backfill household so pre-multi-tenant
  // callers stay tenant-safe.
  def create(
      name: String,
      enrollmentTokenHash: Sha256Hex,
      householdId: HouseholdId = HouseholdId.Default,
  ): Task[RouterId]
  def completeEnrollment(id: RouterId, tokenHash: Sha256Hex): Task[Unit]
  def touch(id: RouterId, etag: Option[ETag], agentVersion: Option[String]): Task[Unit]

  /**
   * #1204: routers whose last_seen_at is at or after `cutoff`. Drives the agent_connected_routers
   * gauge.
   */
  def countSeenSince(cutoff: Instant): Task[Int]

  def delete(id: RouterId): Task[Unit]
}

trait TrafficReportRepo {
  def insertBatch(reports: List[TrafficReportInsert]): Task[Int]
  // #2313: `household`-scoped — `traffic_reports` is `router_id`-keyed and a MAC can exist in more
  // than one household (post-V74), so a bare-MAC read would surface another tenant's rows.
  def listForDevice(
      household: HouseholdId,
      mac: MacAddress,
      date: LocalDate,
  ): Task[List[TrafficReport]]
  def listForRouter(routerId: RouterId, limit: Int): Task[List[TrafficReport]]

  /**
   * Per-usage-report-period traffic_reports rows for aggregation (one row per agent report period,
   * `usage_report_interval` — ~60s, configurable; not a fixed 5-min bucket): returned ordered by
   * (router_id, mac, hostname, date, period_start). Filters are AND-composed. `macs = Some(Nil)`
   * returns an empty list (no devices match).
   *
   * #2568 (multi-tenant, epic #622): `household` AND-composes the router-scope predicate, like
   * every sibling `traffic_reports` read (#2313). The MAC list alone is NOT sufficient scoping —
   * post-V74 (#2277) the same MAC can be a device in two households, and an unscoped read returned
   * the other tenant's rows for it (`GET /api/dashboard/now` rendered household B's hostnames on
   * household A's device).
   */
  def listTrafficRollupRows(
      household: HouseholdId,
      f: TrafficRollupFilter,
  ): Task[List[TrafficRollupRow]]

  /**
   * Minimal projection used by presence-based minute accounting (see
   * [[wifihaven.api.presence.Presence]]). One row per (mac, period_start, hostname) for the given
   * macs/date; the caller deduplicates by `(mac, period_start)` so per-hostname rows in a single
   * bucket don't inflate total screen time.
   */
  def listPresenceRows(
      household: HouseholdId,
      macs: List[MacAddress],
      date: LocalDate,
  ): Task[List[wifihaven.api.presence.PresenceRow]]

  /**
   * Range variant of [[listPresenceRows]] — inclusive `from`..`to`. Used by the #723 weekly profile
   * screen-time view to compute range-deduped per-mac / per-host totals AND per-period breakdown
   * from one query. Bucketing is UTC end-to-end (storage AND read); household-local-time
   * re-bucketing for the chart happens in the SPA against the UTC `periodStart` instants (#794).
   */
  def listPresenceRows(
      household: HouseholdId,
      macs: List[MacAddress],
      from: LocalDate,
      to: LocalDate,
  ): Task[List[wifihaven.api.presence.PresenceRow]]

  /**
   * #1160: tail-load for the rollup read path. Same row shape as [[listPresenceRows]] but with an
   * additional `period_start >= since` filter, so the caller only pays for the slice of buckets the
   * rollup hasn't yet absorbed.
   */
  def listPresenceRowsSince(
      household: HouseholdId,
      macs: List[MacAddress],
      date: LocalDate,
      since: Instant,
  ): Task[List[wifihaven.api.presence.PresenceRow]]

  /**
   * #1099: presence rows whose `period_start` falls in `[fromInstant, toInstant)` for the given
   * macs. Filtering on `period_start` (the table's RANGE partition key, V41) — rather than the
   * non-key `date` column the day/range variants use — lets Postgres prune to the covering weekly
   * partitions instead of scanning all of history. This is the difference between a sub-second read
   * and the multi-minute full-table scan that wedged the /profiles page (see issue #1099). The
   * query is bounded by a per-statement timeout ([[QueryTimeout.PresenceWindow]]) so a pathological
   * caller fails fast instead of holding a connection. Same row shape and semantics as
   * [[listPresenceRows]]; callers compute the window from the requested local day + zone.
   */
  def listPresenceRowsInWindow(
      household: HouseholdId,
      macs: List[MacAddress],
      fromInstant: Instant,
      toInstant: Instant,
  ): Task[List[wifihaven.api.presence.PresenceRow]]

  /**
   * #846: raw rows in `[fromInstant, toInstant)` for the given macs. `macs = Nil` means "all macs
   * in `household`" (used by the Traffic Usage page in unfiltered mode) — the `household` scope
   * bounds that "all" to the caller's tenant. Returns Instants (not String date columns) so callers
   * can bucket without re-parsing.
   */
  def listRawInRange(
      household: HouseholdId,
      macs: List[MacAddress],
      fromInstant: Instant,
      toInstant: Instant,
      cursor: Option[wifihaven.api.usage.RawTrafficCursorKey] = None,
      limit: Option[Int] = None,
  ): Task[List[wifihaven.api.usage.TrafficUsageDbRow]]

  /**
   * #2174: SQL-side pre-aggregation for the raw-tier *fixed-step* display buckets (1m / 10m / 1h).
   * Same row universe as [[listRawInRange]] (period_start range + optional mac filter + zero-rows
   * dropped), but rows are summed per `(mac, host, floor(period_start / step))` INSIDE Postgres, so
   * the API ships one row per (mac, host, bucket) instead of one per raw report period — the
   * unbounded `listRawInRange` fetch on this path returned 755k rows / 24h at prod volume and took
   * ~24s (dominated by row shipping, a per-raw-row LATERAL host-resolve, and the in-app sort). The
   * IP→FQDN LATERAL resolve runs once per aggregated group, after the GROUP BY.
   *
   * Returned rows carry `periodStart = bucket start` and `periodEnd = bucket start + step`, which
   * is exactly the `[floorTo(..), floor + step)` window `UsageTraffic.buildAggregate` / `windowFor`
   * compute for fixed-step buckets — sub-day buckets are UTC-aligned pure epoch math, so the SQL
   * floor and the Scala floor cannot disagree. `buildAggregate` re-floors (idempotent) and
   * aggregates further (groupBy fan-out, distinct counts) on the much smaller set. NOT used for
   * `bucket=raw` (no fixed step — the row's real report period is the window, #2018).
   *
   * Bounded by a per-statement timeout ([[QueryTimeout]]): a pathological window fails fast with a
   * typed 503 instead of holding a pool connection (#1099 class).
   */
  def listRawAggregatedInRange(
      household: HouseholdId,
      macs: List[MacAddress],
      fromInstant: Instant,
      toInstant: Instant,
      stepSeconds: Long,
  ): Task[List[wifihaven.api.usage.TrafficUsageDbRow]]

  /**
   * #846: earliest `period_start` across all rows. Used by Traffic Usage page to reject `from`
   * instants that fall outside the retention horizon — naïve until #809/#814 land.
   */
  def earliestPeriodStart: Task[Option[Instant]]

  /**
   * #766: per-host aggregates for one device over `[from, to)`, restricted to FQDN-typed rows
   * (after the IP→FQDN LATERAL resolve). One row per resolved hostname, with total `bytes_in +
   * bytes_out` and the hit count (number of usage-report periods the host was observed in). Used by
   * the apps create/edit recently-visited-hosts picker; PSL apex collapse happens in Scala.
   */
  def listFqdnHostAggregatesForDevice(
      household: HouseholdId,
      mac: MacAddress,
      fromInstant: Instant,
      toInstant: Instant,
  ): Task[List[(Hostname, Long, Long)]]
}

trait BlockEventRepo {
  def insertBatch(events: List[BlockEventInsert]): Task[Int]
  def recent(limit: Int): Task[List[BlockEvent]]
  def listForMac(mac: MacAddress, limit: Int): Task[List[BlockEvent]]
}

trait ConnectionEventRepo {
  def insertBatch(events: List[ConnectionEventInsert]): Task[Int]
  def recent(limit: Int): Task[List[ConnectionEvent]]
  def listForMac(mac: MacAddress, limit: Int): Task[List[ConnectionEvent]]
  def listForRouter(routerId: RouterId, limit: Int): Task[List[ConnectionEvent]]
  def query(f: LogFilter): Task[List[QueryLog]]
  // #846: multi-column grouping. `groupBy` is the set of column names from
  // {"domain","device","profile"}; the repo returns one row per
  // (window, *grouped-column-values*) with distinct-counts for the rest.
  def querySeries(
      f: LogFilter,
      bucketSeconds: Int,
      groupBy: Set[String],
  ): Task[List[ConnectionEventAggRow]]

  /**
   * #1265: rollup-backed counterpart of [[querySeries]]. Reads pre-aggregated counts from
   * `connection_events_hourly` / `connection_events_daily` (selected by `grain`) instead of
   * scanning the raw `connection_events` table, re-binning the stored grain up to the requested
   * `bucketSeconds`. Produces the same [[ConnectionEventAggRow]] shape, with two deliberate
   * differences from the raw path (the rollup is lossy by design):
   *   - `lastSeen` is bucket-granular (the rebinned window boundary), since the rollup only stores
   *     bucket starts, not individual event timestamps;
   *   - multicast/broadcast rows are absent because the reroll excludes them at write time, so this
   *     path must only be chosen when `includeMulticast` is false (the route enforces this).
   *
   * `count_succeeded` / `count_blocked` are summed (not re-derived from an `allowed` split, which
   * the rollup does not carry); the `blocked` filter narrows to the matching count column.
   */
  def querySeriesRollup(
      f: LogFilter,
      bucketSeconds: Int,
      groupBy: Set[String],
      grain: BucketGrain,
  ): Task[List[ConnectionEventAggRow]]

  /**
   * #1265: re-aggregate the trailing N hours of `connection_events` into
   * `connection_events_hourly`. Counterpart of [[RollupRepo.rerollHourly]] but for the event-count
   * tier. `since` is the earliest `ts` to re-roll, inclusive; callers truncate it to an hour
   * boundary so every bucket in the window is recomputed in full. Idempotent via UPSERT keyed
   * `(router_id, mac, hostname, bucket_start)` — re-rolling an already-rolled bucket replaces it
   * with the freshly summed counts.
   *
   * Returns `Some(n)` rows touched on success, `None` when another instance held the advisory lock
   * (skip-this-tick). The lock is transaction-scoped (`pg_try_advisory_xact_lock`) and
   * auto-releases on commit/rollback. The source scan is bounded by `idx_conn_events_ts` plus
   * weekly partition pruning, so a tick never scans the full unbounded table (#1254 class).
   *
   * Hostname is post-resolved (`COALESCE(resolved_host_value, host_value)`) so the read path stays
   * join-free for the host dimension; device/profile/app attribution stays a read-time join off
   * `mac`. NULL macs fold into a `''` bucket so window totals stay faithful to raw.
   */
  def rerollConnEventsHourly(since: Instant): Task[Option[Int]]

  /**
   * #1265: daily-grain counterpart of [[rerollConnEventsHourly]] writing `connection_events_daily`.
   * `sinceDate` is the earliest UTC date to re-roll, inclusive. Same advisory-lock + idempotency
   * contract.
   */
  def rerollConnEventsDaily(sinceDate: LocalDate): Task[Option[Int]]

  /**
   * #2282 (multi-tenant, epic #622): the dashboard stat-card counts (total-today, blocked-today,
   * events-hour, blocked-hour, top-blocked) scoped to `household`. Both the raw `connection_events`
   * (1h tiles) and the `connection_events_hourly` rollup (24h totals + top) are router_id-keyed, so
   * the household predicate is transitive via `routers.household_id` — same mechanism as
   * [[lastSeenByMacSince]] / [[LogFilter.household]]. The scope is null-safe (`router_id IN (SELECT
   * id FROM routers WHERE household_id=$household)`), never an INNER JOIN that could drop a
   * legitimately-owned row, so for the single backfill household (`HouseholdId.Default`) it returns
   * exactly the pre-scoping global counts. Fixes a cross-tenant leak: a brand-new household with no
   * routers previously saw the whole install's EVENTS(1H)/BLOCKED(1H).
   */
  def stats(household: HouseholdId): Task[DashboardStats]
  def topBlocked(hours: Int, limit: Int): Task[List[DomainCount]]

  /**
   * Latest `ts` per mac for events strictly newer than `since`. Used by the "now" dashboard to
   * detect devices that produced at least one connection attempt in the recent window.
   *
   * #2251 (multi-tenant, epic #622): when `household` is set, scope to that household via the
   * `connection_events.router_id → routers.household_id` join (connection_events are
   * router_id-keyed → household transitive, design §0.1 — same mechanism as
   * [[LogFilter.household]]). `None` (default) reads unscoped, preserving the single-household
   * back-compat for the existing callers. The dashboard NOW path passes `claims.hh` so the
   * online-now set can never include another household's MAC (e.g. the same MAC enrolled in two
   * households).
   */
  def lastSeenByMacSince(
      since: Instant,
      household: Option[HouseholdId] = None,
  ): Task[Map[MacAddress, Instant]]

  /**
   * #720 backfill: look up the most recent fqdn-typed connection_event with the given (router_id,
   * dest_ip) whose `ts` is at-or-after `since`. Returns the resolved hostname if any. Used at
   * ingest time to attach a resolution to a fresh ipv4/ipv6-typed event whose agent-side DNS cache
   * lost the race.
   */
  def findRecentFqdnFor(
      routerId: RouterId,
      destIp: IpAddress,
      since: Instant,
  ): Task[Option[Hostname]]

  /**
   * #1511: batched version of [[findRecentFqdnFor]]. Resolves a whole batch of dest_ip → recent
   * FQDN lookups in a single SQL — the per-record sequential call dominated the `/api/router/usage`
   * hot path. Empty input returns an empty map. Only dest_ips with a matching fqdn-typed event are
   * present in the result.
   */
  def findRecentFqdnForBatch(
      routerId: RouterId,
      destIps: List[IpAddress],
      since: Instant,
  ): Task[Map[IpAddress, Hostname]]

  /**
   * #720 backfill: patch the `resolved_host_value` column on existing ipv4/ipv6-typed rows for the
   * given (router_id, dest_ip) that landed at-or-after `since` and don't yet carry a resolution.
   * Called when a fqdn-typed event lands and "teaches" the API the IP→hostname mapping
   * retroactively. Returns the row count actually updated.
   */
  def backfillResolvedFor(
      routerId: RouterId,
      destIp: IpAddress,
      fqdn: Hostname,
      since: Instant,
  ): Task[Int]
}

class UserRepoLive(xa: Transactor[Task]) extends UserRepo {
  private val userCols =
    fr"id,username,password_hash,role,created_at,must_change_password,token_version,household_id"
  private type UserRow = (UserId, String, String, UserRole, Instant, Boolean, Int, HouseholdId)
  private def toUser(r: UserRow) = r match {
    case (id, un, ph, role, ca, mcp, tv, hh) => DbUser(id, un, ph, role, ca, mcp, tv, hh)
  }

  def findByUsername(householdId: HouseholdId, u: String)                  =
    DbMetrics.timed("user.findByUsername")(
      // #2140: keyed on the V65 UNIQUE(household_id, username) — never a bare-username lookup.
      (fr"SELECT " ++ userCols ++ fr" FROM users WHERE household_id=$householdId AND username=$u")
        .query[UserRow]
        .map(toUser)
        .option
        .transact(xa),
    )
  def findByEmail(email: String)                                           =
    DbMetrics.timed("user.findByEmail")(
      // #2164: exact match on the globally-unique `users.email` (V67). `.option` is safe — the
      // uq_users_email constraint guarantees at most one row.
      (fr"SELECT " ++ userCols ++ fr" FROM users WHERE email=$email")
        .query[UserRow]
        .map(toUser)
        .option
        .transact(xa),
    )
  def emailForUser(householdId: HouseholdId, username: String)             =
    DbMetrics.timed("user.emailForUser")(
      // #2199: household-scoped username → email. Keyed on the V65 UNIQUE(household_id, username);
      // the inner Option is the nullable `users.email` column (V67), so a row with a NULL email
      // yields `Some(None)` → flattened to `None`.
      sql"SELECT email FROM users WHERE household_id=$householdId AND username=$username"
        .query[Option[String]]
        .option
        .map(_.flatten)
        .transact(xa),
    )
  def findById(id: UserId)                                                 =
    (fr"SELECT " ++ userCols ++ fr" FROM users WHERE id=$id")
      .query[UserRow]
      .map(toUser)
      .option
      .transact(xa)
  def create(u: String, h: String, r: String, householdId: HouseholdId, email: Option[String]) =
    // Always force a password change on first login for admin-created users (#599).
    // #2130: household_id is stamped explicitly — never left to V65's DEFAULT 1.
    // #2132 (V67): `email` is NULL for every path except invite-accept, which binds it from
    // beta_requests.email (the global login identifier, uq_users_email).
    sql"INSERT INTO users(username,password_hash,role,must_change_password,household_id,email) VALUES($u,$h,$r,true,$householdId,$email) RETURNING id"
      .query[UserId]
      .unique
      .transact(xa)
  // #2576: one primary-key point update, so a rotation can never be half-applied. Same access
  // pattern as the single-column updates below — keyed on `id`, three columns written instead of
  // one. No new predicate, so no new index requirement.
  def applyPasswordRotation(id: UserId, hash: String, mustChange: Boolean) =
    sql"""UPDATE users
          SET password_hash=$hash, must_change_password=$mustChange,
              token_version=token_version+1
          WHERE id=$id""".update.run.transact(xa).unit
  def updateUsername(id: UserId, u: String)                                =
    sql"UPDATE users SET username=$u WHERE id=$id".update.run.transact(xa).unit
  def updateRole(id: UserId, r: String)                                    =
    sql"UPDATE users SET role=$r WHERE id=$id".update.run.transact(xa).unit
  def clearMustChangePassword(id: UserId)                                  =
    sql"UPDATE users SET must_change_password=false WHERE id=$id".update.run.transact(xa).unit
  def listAll                                                              =
    (fr"SELECT " ++ userCols ++ fr" FROM users ORDER BY id")
      .query[UserRow]
      .map(toUser)
      .to[List]
      .transact(xa)
  // #2108: same projection as listAll, AND-scoped to one household. Index-backed by
  // V65's idx_users_household.
  def listAllForHousehold(household: HouseholdId)                          =
    (fr"SELECT " ++ userCols ++ fr" FROM users WHERE" ++ SqlFragments.householdEq(
      household,
    ) ++ fr"ORDER BY id")
      .query[UserRow]
      .map(toUser)
      .to[List]
      .transact(xa)
  // #2512: probe for the household's single admin, backed by V86's partial unique index
  // uq_users_household_single_admin (household_id) WHERE role='admin' — which is also what makes
  // `.option` exact rather than a "first row wins" read.
  // `role='admin'` is a LITERAL, not a bind parameter, deliberately: Postgres proves a partial
  // index's predicate at PLAN time, so under a GENERIC plan — where the parameter's value is not
  // visible — the predicate goes unproven and the partial index is unusable for the scan. A custom
  // plan substitutes the actual value and CAN match it, so the literal is a conservative choice
  // rather than a correctness requirement. It is the same string
  // `UserRole.asString(UserRole.Admin)` produces (shared/src/Models.scala) and the same one V1's
  // `CHECK (role IN ('admin','adult','child'))` and V86's predicate spell out.
  def findAdminForHousehold(household: HouseholdId)                        =
    DbMetrics.timed("user.findAdminForHousehold")(
      (fr"SELECT " ++ userCols ++ fr" FROM users WHERE" ++ SqlFragments.householdEq(
        household,
      ) ++ fr"AND role='admin'")
        .query[UserRow]
        .map(toUser)
        .option
        .transact(xa),
    )
  def delete(id: UserId) = sql"DELETE FROM users WHERE id=$id".update.run.transact(xa).unit
}

class HouseholdRepoLive(xa: Transactor[Task]) extends HouseholdRepo {
  // #2140: slug → household id. The unique `uq_households_slug` (V66) makes `.option` exact.
  def findIdBySlug(slug: String) =
    DbMetrics.timed("household.findIdBySlug")(
      sql"SELECT id FROM households WHERE slug=$slug".query[HouseholdId].option.transact(xa),
    )

  // #2132: provisioning create + read-back. `Household` is defined in BetaRepos.scala (same package).
  // #2286: the household's global-sentinel profile is seeded in the SAME transaction as the household
  // row. #2355: so is its household_billing row — via the ONE [[HouseholdSeed.insertHousehold]]
  // primitive (households + billing + global-sentinel as a unit), the same fragment
  // approveAndProvision composes, so no create path can drift into skipping billing.
  def create(name: String, slug: String, routerCap: Int) =
    HouseholdSeed.insertHousehold(name, slug, routerCap).transact(xa)

  def findById(id: HouseholdId) =
    sql"SELECT id, name, slug, router_cap FROM households WHERE id=$id"
      .query[(HouseholdId, String, Option[String], Int)]
      .map { case (i, n, s, rc) => Household(i, n, s, rc) }
      .option
      .transact(xa)

  // #2164: slug by id, for the login response's cookie hint. Index-backed by the primary key.
  // `households.slug` is nullable (V66 backfilled existing rows only), so read it as Option and
  // flatten "row absent" and "row present but slug NULL" both to None.
  def findSlugById(id: HouseholdId) =
    DbMetrics.timed("household.findSlugById")(
      sql"SELECT slug FROM households WHERE id=$id"
        .query[Option[String]]
        .option
        .map(_.flatten)
        .transact(xa),
    )
}

class UserProfileRepoLive(xa: Transactor[Task]) extends UserProfileRepo {
  def listProfilesForUser(userId: UserId)                          =
    sql"SELECT profile_id FROM user_profiles WHERE user_id=$userId ORDER BY profile_id"
      .query[ProfileId]
      .to[List]
      .transact(xa)
  def listProfilesForUsername(household: HouseholdId, u: String)   =
    sql"""SELECT up.profile_id FROM user_profiles up JOIN users us ON us.id=up.user_id
          WHERE us.username=$u AND us.household_id=$household ORDER BY up.profile_id"""
      .query[ProfileId]
      .to[List]
      .transact(xa)
  def listUsersForProfile(profileId: ProfileId)                    =
    sql"SELECT user_id FROM user_profiles WHERE profile_id=$profileId ORDER BY user_id"
      .query[UserId]
      .to[List]
      .transact(xa)
  def listMappingsForHousehold(household: HouseholdId)             =
    sql"""SELECT up.user_id, up.profile_id FROM user_profiles up JOIN users us ON us.id=up.user_id
          WHERE us.household_id=$household"""
      .query[(UserId, ProfileId)]
      .to[List]
      .transact(xa)
  def setProfilesForUser(userId: UserId, pids: List[ProfileId])    = {
    val del = sql"DELETE FROM user_profiles WHERE user_id=$userId".update.run
    val ins = pids.distinct.map(pid =>
      sql"INSERT INTO user_profiles(user_id,profile_id) VALUES($userId,$pid) ON CONFLICT DO NOTHING".update.run,
    )
    (del *> ins.foldLeft(FC.unit)(_ *> _.void)).transact(xa)
  }
  def setUsersForProfile(profileId: ProfileId, uids: List[UserId]) = {
    val del = sql"DELETE FROM user_profiles WHERE profile_id=$profileId".update.run
    val ins = uids.distinct.map(uid =>
      sql"INSERT INTO user_profiles(user_id,profile_id) VALUES($uid,$profileId) ON CONFLICT DO NOTHING".update.run,
    )
    (del *> ins.foldLeft(FC.unit)(_ *> _.void)).transact(xa)
  }
  def addLink(userId: UserId, pid: ProfileId)                      =
    sql"INSERT INTO user_profiles(user_id,profile_id) VALUES($userId,$pid) ON CONFLICT DO NOTHING".update.run
      .transact(xa)
      .unit
  def removeLink(userId: UserId, pid: ProfileId)                   =
    sql"DELETE FROM user_profiles WHERE user_id=$userId AND profile_id=$pid".update.run
      .transact(xa)
      .unit
  def hasAccess(userId: UserId, pid: ProfileId)                    =
    sql"SELECT 1 FROM user_profiles WHERE user_id=$userId AND profile_id=$pid"
      .query[Int]
      .option
      .transact(xa)
      .map(_.isDefined)
}

class ProfileRepoLive(xa: Transactor[Task]) extends ProfileRepo {
  private type R = (
      ProfileId,
      String,
      List[String],
      Boolean,
      String,
      Boolean,
      String,
      String,
      Boolean,
      Boolean,
  )
  private def toP(r: R)                                          = Profile(
    r._1,
    r._2,
    r._3.map(BlocklistId.unsafe),
    r._4,
    FailureMode.parse(r._5).getOrElse(FailureMode.LastKnownGood),
    r._6,
    CrossDeviceOverlapMode.parse(r._7).getOrElse(CrossDeviceOverlapMode.Sum),
    PauseMode.parse(r._8).getOrElse(PauseMode.Soft),
    r._9,
    r._10,
  )
  // #2257: the tenant enumeration for the genuinely all-tenant batch paths — DISTINCT household_id
  // over `profiles`. Include the global sentinel's household too (harmless: `listAllForHousehold`
  // excludes global rows), so a household that has only its sentinel still enumerates. Index-backed
  // by V65's idx_profiles_household.
  def distinctHouseholds                                         =
    DbMetrics.timed("profile.distinctHouseholds")(
      sql"SELECT DISTINCT household_id FROM profiles ORDER BY household_id"
        .query[HouseholdId]
        .to[List]
        .transact(xa),
    )
  def listAllIncludingGlobal                                     =
    DbMetrics.timed("profile.listAllIncludingGlobal")(
      sql"SELECT id,name,blocked_categories,paused,failure_mode,block_ip_only,cross_device_overlap_mode,pause_mode,default_deny,is_global FROM profiles ORDER BY id"
        .query[R]
        .map(toP)
        .to[List]
        .transact(xa),
    )
  // #2107: same projection as listAllIncludingGlobal, AND-scoped to one household. Index-backed by
  // V65's idx_profiles_household.
  def listAllIncludingGlobalForHousehold(household: HouseholdId) =
    DbMetrics.timed("profile.listAllIncludingGlobalForHousehold")(
      (fr"SELECT id,name,blocked_categories,paused,failure_mode,block_ip_only,cross_device_overlap_mode,pause_mode,default_deny,is_global FROM profiles WHERE" ++
        SqlFragments.householdEq(household) ++ fr"ORDER BY id")
        .query[R]
        .map(toP)
        .to[List]
        .transact(xa),
    )
  // #2108: same projection + `is_global=FALSE` filter as listAll, AND-scoped to one household.
  // Index-backed by V65's idx_profiles_household.
  def listAllForHousehold(household: HouseholdId)                =
    DbMetrics.timed("profile.listAllForHousehold")(
      (fr"SELECT id,name,blocked_categories,paused,failure_mode,block_ip_only,cross_device_overlap_mode,pause_mode,default_deny,is_global FROM profiles WHERE is_global=FALSE AND" ++
        SqlFragments.householdEq(household) ++ fr"ORDER BY id")
        .query[R]
        .map(toP)
        .to[List]
        .transact(xa),
    )
  // #2108: the household that owns `id`, for the route guards. Index-backed by the primary key.
  def householdOf(id: ProfileId)                                 =
    DbMetrics.timed("profile.householdOf")(
      sql"SELECT household_id FROM profiles WHERE id=$id"
        .query[HouseholdId]
        .option
        .transact(xa),
    )
  def getGlobal                                                  =
    DbMetrics.timed("profile.getGlobal")(
      sql"SELECT id,name,blocked_categories,paused,failure_mode,block_ip_only,cross_device_overlap_mode,pause_mode,default_deny,is_global FROM profiles WHERE is_global=TRUE"
        .query[R]
        .map(toP)
        .option
        .transact(xa),
    )
  // #2108: the sentinel scoped to one household. `is_global` is a partial-unique per household, so
  // this returns at most one row.
  def getGlobalForHousehold(household: HouseholdId)              =
    DbMetrics.timed("profile.getGlobalForHousehold")(
      (fr"SELECT id,name,blocked_categories,paused,failure_mode,block_ip_only,cross_device_overlap_mode,pause_mode,default_deny,is_global FROM profiles WHERE is_global=TRUE AND" ++
        SqlFragments.householdEq(household))
        .query[R]
        .map(toP)
        .option
        .transact(xa),
    )
  def findById(id: ProfileId)                                    =
    DbMetrics.timed("profile.findById")(
      sql"SELECT id,name,blocked_categories,paused,failure_mode,block_ip_only,cross_device_overlap_mode,pause_mode,default_deny,is_global FROM profiles WHERE id=$id"
        .query[R]
        .map(toP)
        .option
        .transact(xa),
    )
  def create(name: String, cats: List[BlocklistId], householdId: HouseholdId) =
    // #2130: household_id is stamped explicitly — never left to V65's DEFAULT 1.
    sql"INSERT INTO profiles(name,blocked_categories,household_id) VALUES($name,${cats.map(_.value).toArray},$householdId) RETURNING id"
      .query[ProfileId]
      .unique
      .transact(xa)
  def update(p: Profile)                                         =
    sql"""UPDATE profiles SET
            name=${p.name},
            blocked_categories=${p.blockedCategories.map(_.value).toArray},
            paused=${p.paused},
            failure_mode=${FailureMode.asString(p.failureMode)},
            block_ip_only=${p.blockIpOnly},
            cross_device_overlap_mode=${CrossDeviceOverlapMode.asString(p.crossDeviceOverlapMode)},
            pause_mode=${PauseMode.asString(p.pauseMode)},
            default_deny=${p.defaultDeny}
          WHERE id=${p.id}""".update.run
      .transact(xa)
      .unit
  def delete(id: ProfileId) = sql"DELETE FROM profiles WHERE id=$id".update.run.transact(xa).unit

  // #423: targeted per-column setters for the PATCH handler. Each runs an
  // UPDATE that touches exactly one column, so concurrent PATCHes on
  // disjoint fields no longer race through a full-row rewrite.
  def setName(id: ProfileId, name: String)                                   =
    sql"UPDATE profiles SET name=$name WHERE id=$id".update.run.transact(xa).unit
  def setBlockedCategories(id: ProfileId, cats: List[BlocklistId])           =
    sql"UPDATE profiles SET blocked_categories=${cats.map(_.value).toArray} WHERE id=$id".update.run
      .transact(xa)
      .unit
  def setPaused(id: ProfileId, p: Boolean)                                   =
    sql"UPDATE profiles SET paused=$p WHERE id=$id".update.run.transact(xa).unit
  def setFailureMode(id: ProfileId, mode: FailureMode)                       =
    sql"UPDATE profiles SET failure_mode=${FailureMode.asString(mode)} WHERE id=$id".update.run
      .transact(xa)
      .unit
  def setBlockIpOnly(id: ProfileId, v: Boolean)                              =
    sql"UPDATE profiles SET block_ip_only=$v WHERE id=$id".update.run.transact(xa).unit
  def setCrossDeviceOverlapMode(id: ProfileId, mode: CrossDeviceOverlapMode) =
    sql"UPDATE profiles SET cross_device_overlap_mode=${CrossDeviceOverlapMode.asString(mode)} WHERE id=$id".update.run
      .transact(xa)
      .unit
  def setPauseMode(id: ProfileId, mode: PauseMode)                           =
    sql"UPDATE profiles SET pause_mode=${PauseMode.asString(mode)} WHERE id=$id".update.run
      .transact(xa)
      .unit
  def setDefaultDeny(id: ProfileId, v: Boolean)                              =
    sql"UPDATE profiles SET default_deny=$v WHERE id=$id".update.run.transact(xa).unit
}

class HouseholdSettingsRepoLive(xa: Transactor[Task]) extends HouseholdSettingsRepo {
  import zio.json.*
  import wifihaven.shared.UnmanagedMacPolicy

  // #2107: the one SELECT + decoder behind `getForHousehold`, parameterized on its WHERE clause so
  // the column list and mapping live in a single place (AGENTS.md §single-source-of-truth). Returns
  // Option so the caller can distinguish "no row for this household yet" (see `getForHousehold`).
  // #2533: the second caller — the unscoped `get`'s `WHERE id=1` — is gone; the parameterization
  // stays because the WHERE fragment is still built by `SqlFragments.householdEq`.
  private def selectSettings(where: Fragment): Task[Option[HouseholdSettings]] =
    // #1525: `heartbeat_host_patterns` is no longer read — host-identity suppression lives in
    // the canonical `shared.types.InfraHosts` code constant. The column is dropped in a
    // follow-up migration-only PR; until then the SELECT just omits it and `HeartbeatFilter`
    // gets `Nil` for that field.
    (fr"""SELECT daily_reset_time, daily_reset_tz,
                 heartbeat_filter_enabled, heartbeat_bytes_threshold,
                 unmanaged_mac_policy::text,
                 presence_continuation_seconds,
                 block_encrypted_dns,
                 ambient_gate_enabled, ambient_isolation_max_hosts,
                 ambient_min_isolated_days, ambient_learning_window_days,
                 notify_email
            FROM household_settings WHERE""" ++ where)
      .query[
        (
            LocalTime,
            ZoneId,
            Boolean,
            Int,
            String,
            Int,
            Boolean,
            Boolean,
            Int,
            Int,
            Int,
            Option[
              String,
            ],
        ),
      ]
      .option
      .map {
        _.map {
          case (
                t,
                z,
                hbEnabled,
                hbBytes,
                ummJson,
                presenceCont,
                blockEncDns,
                ambEnabled,
                ambIso,
                ambMinDays,
                ambWindow,
                notifyEmail,
              ) =>
            val umm = ummJson.fromJson[UnmanagedMacPolicy].getOrElse(UnmanagedMacPolicy.Default)
            HouseholdSettings(
              t,
              z,
              HeartbeatFilter(hbEnabled, hbBytes, Nil),
              umm,
              presenceCont,
              blockEncDns,
              ambEnabled,
              ambIso,
              ambMinDays,
              ambWindow,
              // #578: NULL notify_email → None (no recipient configured).
              notifyEmail.map(_.trim).filter(_.nonEmpty),
            )
        }
      }
      .transact(xa)

  // #2107/#2386: household-scoped settings read for the router snapshot/decide paths. Every household
  // owns its OWN settings row — seeded atomically at creation ([[HouseholdSeed.insertHousehold]]) and
  // backfilled for pre-existing households at boot ([[backfillMissingSettings]]). A missing row is
  // therefore a provisioning BUG, so this FAILS LOUD (matching `get`'s id=1 contract) rather than
  // falling back to household #1's row — that fallback was a live cross-tenant leak (#2386: a beta
  // household with no row of its own read household #1's block-encrypted-DNS / unmanaged-MAC policy /
  // reset tz / notify-email). No silent default: per no-dark-by-default, a rowless household surfaces
  // as an error instead of silently inheriting another tenant's config.
  def getForHousehold(household: HouseholdId): Task[HouseholdSettings] =
    DbMetrics.timed("householdSettings.getForHousehold")(
      selectSettings(SqlFragments.householdEq(household)).flatMap {
        case Some(s) => ZIO.succeed(s)
        case None    =>
          ZIO.fail(
            new RuntimeException(
              s"household_settings row missing for household ${household.value} " +
                "(every household must own its settings row; see #2386)",
            ),
          )
      },
    )

  // #2382: escape-hatch read/write, scoped to the household's OWN row (`WHERE household_id`, no
  // id=1 fallback). A missing row reads `false` so the permissive gate fails toward enforcing.
  def enforcementDisabled(household: HouseholdId) =
    DbMetrics.timed("householdSettings.enforcementDisabled")(
      (fr"SELECT enforcement_disabled FROM household_settings WHERE" ++
        SqlFragments.householdEq(household))
        .query[Boolean]
        .option
        .map(_.getOrElse(false))
        .transact(xa),
    )

  def setEnforcementDisabled(household: HouseholdId, disabled: Boolean) =
    DbMetrics.timed("householdSettings.setEnforcementDisabled")(
      (fr"UPDATE household_settings SET enforcement_disabled=$disabled, updated_at=NOW() WHERE" ++
        SqlFragments.householdEq(household)).update.run
        .map(_ > 0)
        .transact(xa),
    )

  def update(household: HouseholdId, s: HouseholdSettings): Task[Unit] = {
    val ummJson       = s.unmanagedMacPolicy.toJson
    // #1160 / #1464: invalidate the time-used rollup atomically with the
    // settings update. Any change to the daily-reset boundary (tz / reset hour),
    // the heartbeat filter, or the presence session-stitch knob
    // (`presence_continuation_seconds`) changes the active-minute definition for
    // every cached day; deleting the cache forces the next rollup tick to refill
    // from first principles. #2077: the ambient-gate knobs (`ambient_*`) gate the
    // same aggregation, so they ride the same invalidation. It is unconditional
    // in the FIELD and DAY dimensions — every one of these fields gates the same
    // aggregation, so a per-field or per-day invalidation would only add the risk
    // of missing a code path that mutates one of them. It is NOT unconditional in
    // the HOUSEHOLD dimension: since #2553 it is scoped to the writing
    // household's own profiles (see the DELETEs below).
    // #1525: `heartbeat_host_patterns` is no longer written — the column has a NOT NULL DEFAULT
    // from V24 so existing rows keep their seed value until the follow-up migration drops it
    // entirely; nothing in the API reads it anymore (see `Presence.isHeartbeat` →
    // `InfraHosts.isBackground`).
    val upd =
      // #1912: `block_encrypted_dns` does NOT gate the active-minute definition,
      // so it doesn't strictly need the rollup invalidation below — but the
      // invalidation already covers every field (they share one aggregation
      // path) and an admin toggling it is rare, so refilling this household's
      // cache from first principles is harmless and keeps the update path single.
      (fr"""UPDATE household_settings
              SET daily_reset_time=${s.dailyResetTime},
                  daily_reset_tz=${s.dailyResetTz},
                  heartbeat_filter_enabled=${s.heartbeatFilter.enabled},
                  heartbeat_bytes_threshold=${s.heartbeatFilter.bytesThreshold},
                  unmanaged_mac_policy=${ummJson}::jsonb,
                  presence_continuation_seconds=${s.presenceContinuationSeconds},
                  block_encrypted_dns=${s.blockEncryptedDns},
                  ambient_gate_enabled=${s.ambientGateEnabled},
                  ambient_isolation_max_hosts=${s.ambientIsolationMaxHosts},
                  ambient_min_isolated_days=${s.ambientMinIsolatedDays},
                  ambient_learning_window_days=${s.ambientLearningWindowDays},
                  notify_email=${s.notifyEmail},
                  updated_at=NOW()
            WHERE """ ++ SqlFragments.householdEq(household)).update.run
    // #2553: the invalidation is SCOPED to the writing household's profiles. #2533 deliberately
    // left it wholesale — at that point `TimeUsedRollupJob.doTick` derived EVERY household's rollup
    // from household #1's settings, so household N's cached rows really were a function of household
    // #1's reset tz / heartbeat filter, and a #1 save had to evict them all. #2553 severed that
    // coupling (both all-tenant jobs now read each household's OWN settings inside their
    // per-household loop), so evicting every other tenant's rows on any tenant's save would be a
    // full recompute of every profile in the install. The two changes are not separable, which is
    // why they land together.
    //
    // Precisely what this does NOT claim: a household's cached rows are not a pure function of its
    // own settings row. The `ambient_host_days` baseline the rollup gates on is a single GLOBAL
    // table with no household_id, and what a household LEARNS into it depends on its own
    // `ambient_*` / heartbeat / presence-continuation knobs — so household A's knobs still reach
    // household B's active-minute definition through that table, and B's rows are no longer evicted
    // when A saves. The residual exposure is small and predates this change: the shared baseline
    // only moves when `AmbientLearnJob` next runs (6 h), not at settings-write time, so the
    // wholesale DELETE never invalidated this coupling at the right moment either — it only helped
    // by coincidence, when some tenant happened to save afterwards. `time_used_daily`'s today row is
    // rewritten by the rollup every 15 min regardless. Past-day `app_used_daily` rows are the one
    // thing that can hold old ambient semantics indefinitely. Keying `ambient_host_days` by
    // household is the durable fix — #2583.
    // `time_used_daily` / `app_used_daily` carry no household_id of their own (V43 / V53) — they key
    // on `profile_id`, which does (V65) — so the scope is a semijoin through `profiles`.
    val ownProfiles   =
      fr"profile_id IN (SELECT id FROM profiles WHERE" ++
        SqlFragments.householdEq(household) ++ fr")"
    val invalidate    = (fr"DELETE FROM time_used_daily WHERE" ++ ownProfiles).update.run
    // #1516: the per-app rollup (`app_used_daily`) is gated by the SAME active-minute definition
    // (heartbeat filter, daily-reset boundary, presence session-stitch knob), so invalidate it
    // atomically too — the next tick refills both from first principles.
    val invalidateApp = (fr"DELETE FROM app_used_daily WHERE" ++ ownProfiles).update.run
    // A settings write for a household that owns no row is a provisioning bug, not a silent no-op:
    // every household gets its row from `HouseholdSeed.insertHousehold` / `backfillMissingSettings`
    // (#2386), so 0 rows updated means the caller's household is unprovisioned. Fail loud rather
    // than return success on a write that landed nowhere (AGENTS.md §no-dark-by-default).
    (upd.flatMap {
      case 0 =>
        doobie.free.connection.raiseError[Int](
          new RuntimeException(
            s"household_settings row missing for household ${household.value} " +
              "(every household must own its settings row; see #2386)",
          ),
        )
      case n => doobie.free.connection.pure(n)
    } *> invalidate *> invalidateApp).transact(xa).unit
  }

  def ensureDefault(defaultZone: ZoneId): Task[Unit] =
    // #2130: household_id is stamped explicitly (this seeds the single backfill
    // install's row, so HouseholdId.Default) — never left to V65's DEFAULT 1.
    sql"""INSERT INTO household_settings (id, daily_reset_time, daily_reset_tz, household_id)
          VALUES (1, '00:00', ${defaultZone}, ${HouseholdId.Default})
          ON CONFLICT (id) DO NOTHING""".update.run.transact(xa).unit
}

class TimeLimitRepoLive(xa: Transactor[Task]) extends TimeLimitRepo {
  def findForProfile(pid: ProfileId)    =
    DbMetrics.timed("timeLimit.findForProfile")(
      sql"SELECT id,profile_id,daily_minutes FROM time_limits WHERE profile_id=$pid"
        .query[(TimeLimitId, ProfileId, Int)]
        .map(TimeLimit.apply)
        .option
        .transact(xa),
    )
  def upsert(pid: ProfileId, mins: Int) =
    sql"INSERT INTO time_limits(profile_id,daily_minutes) VALUES($pid,$mins) ON CONFLICT(profile_id) DO UPDATE SET daily_minutes=EXCLUDED.daily_minutes".update.run
      .transact(xa)
      .unit
  def delete(pid: ProfileId)            =
    sql"DELETE FROM time_limits WHERE profile_id=$pid".update.run.transact(xa).unit
  def listAll                           = sql"SELECT id,profile_id,daily_minutes FROM time_limits"
    .query[(TimeLimitId, ProfileId, Int)]
    .map(TimeLimit.apply)
    .to[List]
    .transact(xa)
  // #2108: same projection as listAll, AND-scoped through the profile FK to one household.
  def listAllForHousehold(household: HouseholdId) =
    (fr"SELECT tl.id,tl.profile_id,tl.daily_minutes FROM time_limits tl JOIN profiles p ON p.id=tl.profile_id WHERE" ++
      SqlFragments.householdEq(household, "p.household_id"))
      .query[(TimeLimitId, ProfileId, Int)]
      .map(TimeLimit.apply)
      .to[List]
      .transact(xa)
}

class AppTimeLimitRepoLive(xa: Transactor[Task]) extends AppTimeLimitRepo {
  // #1564 + #1630: (profileId, host, dailyMinutes, slug, exemptFromDaily, appId, assignmentId,
  // mode) — the join already has `apps.id` in scope, so we carry it through as the canonical FK
  // reference instead of throwing it away and re-resolving the slug downstream. Post-#1630 the
  // `apa.id` (assignmentId) and `apa.mode` are also carried so the `ProfileAppDispositions`
  // collapse can route each (assignment × host) row to the right bucket without a second repo
  // read. The Postgres enum `app_mode` round-trips via doobie's string type-class.
  // #1627: `apa.daily_minutes` is projected as Option[Int] (not COALESCE(...,0)). `None` means
  // "no per-app limit configured" and is treated as never-exhausted by the exempt carve-out gate
  // in `PolicyService.exemptUnderCapHosts`; the COALESCE silently collapsed that case to 0 and
  // made the exempt+no-limit carve unreachable.
  // #1679: `apa.allowed_during_schedule_block` carried straight through so the
  // `ProfileAppDispositions` fold can suppress the extraAllowed carve during Schedule blocks.
  // #1897: `ah.shared` is carried through as the 10th column so `ProfileAppDispositions` can
  // partition each app's host-set into distinctive (`shared = false`) vs shared hosts. The
  // engaged-minutes stitch reads the distinctive subset only.
  private type R =
    (
        ProfileId,
        String,
        Option[Int],
        String,
        Boolean,
        AppId,
        AppPolicyAssignmentId,
        String,
        Boolean,
        Boolean,
    )
  private def toS(r: R) =
    AppTimeLimit(
      AppTimeLimitId(0L),
      r._1,
      r._2,
      r._3,
      s"app:${r._4}",
      r._5,
      r._6,
      AppMode.parse(r._8).getOrElse(AppMode.TimeLimited),
      r._7,
      r._9,
      r._10,
    )

  def listForProfile(pid: ProfileId) =
    DbMetrics.timed("appTimeLimit.listForProfile")(
      sql"""SELECT apa.profile_id, ah.host, apa.daily_minutes, a.slug,
                   apa.exempt_from_daily, a.id, apa.id, apa.mode::text,
                   apa.allowed_during_schedule_block, ah.shared
            FROM app_policy_assignments apa
            JOIN apps a       ON a.id = apa.app_id
            JOIN app_hosts ah ON ah.app_id = apa.app_id
           WHERE apa.profile_id = $pid
           ORDER BY apa.id, ah.host"""
        .query[R]
        .map(toS)
        .to[List]
        .transact(xa),
    )

  // #2108: AND-scoped through app_policy_assignments.profile_id → the household-scoped profiles
  // row. #2568 removed the unscoped `listAll` this was once the scoped twin of.
  def listAllForHousehold(household: HouseholdId) =
    (fr"""SELECT apa.profile_id, ah.host, apa.daily_minutes, a.slug,
                 apa.exempt_from_daily, a.id, apa.id, apa.mode::text,
                 apa.allowed_during_schedule_block, ah.shared
            FROM app_policy_assignments apa
            JOIN apps a       ON a.id = apa.app_id
            JOIN app_hosts ah ON ah.app_id = apa.app_id
            JOIN profiles p   ON p.id = apa.profile_id
           WHERE""" ++ SqlFragments.householdEq(household, "p.household_id") ++
      fr"ORDER BY apa.id, ah.host")
      .query[R]
      .map(toS)
      .to[List]
      .transact(xa)
}

class DeviceRepoLive(xa: Transactor[Task]) extends DeviceRepo {
  // #2107: same projection as listAll, AND-scoped to one household. `devices` is aliased `d`, so the
  // predicate is qualified `d.household_id`. Index-backed by V65's idx_devices_household (and the
  // leading column of uq_devices_household_mac).
  def listAllForHousehold(household: HouseholdId)                   =
    DbMetrics.timed("device.listAllForHousehold")(
      (fr"SELECT d.id,d.mac,d.name,d.profile_id,p.name,d.last_seen_ip,d.last_seen_at::TEXT FROM devices d LEFT JOIN profiles p ON p.id=d.profile_id WHERE" ++
        SqlFragments.householdEq(household, "d.household_id") ++ fr"ORDER BY d.name")
        .query[
          (
              DeviceId,
              MacAddress,
              String,
              Option[ProfileId],
              Option[String],
              Option[IpAddress],
              Option[String],
          ),
        ]
        .map(r => Device(r._1, r._2, r._3, r._4, r._5, r._6, r._7))
        .to[List]
        .transact(xa),
    )
  def listForProfile(profileId: ProfileId)                          =
    DbMetrics.timed("device.listForProfile")(
      sql"SELECT d.id,d.mac,d.name,d.profile_id,p.name,d.last_seen_ip,d.last_seen_at::TEXT FROM devices d LEFT JOIN profiles p ON p.id=d.profile_id WHERE d.profile_id=$profileId ORDER BY d.name"
        .query[
          (
              DeviceId,
              MacAddress,
              String,
              Option[ProfileId],
              Option[String],
              Option[IpAddress],
              Option[String],
          ),
        ]
        .map(r => Device(r._1, r._2, r._3, r._4, r._5, r._6, r._7))
        .to[List]
        .transact(xa),
    )
  def findByMac(mac: MacAddress, household: HouseholdId = HouseholdId.Default) =
    // #2312: delegate to the household-scoped primitive. The old global `WHERE d.mac=$mac` + `.option`
    // threw on the 2-row match once the same MAC lived in two households; scoping guarantees ≤1 row
    // (uq_devices_household_mac). One query, one source of truth — no duplicated projection.
    findByMacInHousehold(mac, household)
  // #2108: same projection as findByMac, AND-scoped to one household. The user-facing device routes
  // resolve through this so an hh-A admin gets a clean 404 for an hh-B MAC. Index-backed by V65's
  // uq_devices_household_mac leading column.
  // #2566/#2322: the pre-#2322 `createAccessRequest` COALESCE subquery, lifted out of the INSERT
  // and named. Index-backed by V65's uq_devices_household_mac (mac is the trailing column, so this
  // is the one device read that does not lead with household — see the trait doc for why that is
  // deliberate and confined to the block-page fallback).
  def findOwningHousehold(mac: MacAddress) =
    DbMetrics.timed("device.findOwningHousehold")(
      sql"SELECT household_id FROM devices WHERE mac = $mac ORDER BY household_id LIMIT 1"
        .query[HouseholdId]
        .option
        .transact(xa),
    )

  def findByMacInHousehold(mac: MacAddress, household: HouseholdId) =
    DbMetrics.timed("device.findByMacInHousehold")(
      (fr"SELECT d.id,d.mac,d.name,d.profile_id,p.name,d.last_seen_ip,d.last_seen_at::TEXT FROM devices d LEFT JOIN profiles p ON p.id=d.profile_id WHERE d.mac=$mac AND" ++
        SqlFragments.householdEq(household, "d.household_id"))
        .query[
          (
              DeviceId,
              MacAddress,
              String,
              Option[ProfileId],
              Option[String],
              Option[IpAddress],
              Option[String],
          ),
        ]
        .map(r => Device(r._1, r._2, r._3, r._4, r._5, r._6, r._7))
        .option
        .transact(xa),
    )
  def upsert(
      mac: MacAddress,
      name: String,
      pid: Option[ProfileId],
      ip: String,
      household: HouseholdId = HouseholdId.Default,
  ) = {
    // #708: pid=None writes NULL (device unassigned). Devices without a profile
    // are a supported state — same shape auto-discovery produces.
    // #1771: defensive guard — devices cannot be assigned to the global sentinel
    // profile. The route layer rejects this with a 400 before we get here, but
    // catching it again in the repo means a buggy code path (or a direct SQL
    // call from a future caller) still fails loudly instead of corrupting the
    // device-to-profile graph. The guard and the INSERT run in the SAME doobie
    // transaction so a (today-impossible) concurrent flip of `profiles.is_global`
    // can't slip a device assignment past the check.
    val check = pid.fold(doobie.free.connection.unit) { p =>
      sql"SELECT is_global FROM profiles WHERE id=$p"
        .query[Boolean]
        .option
        .flatMap {
          case Some(true) =>
            doobie.free.connection.raiseError[Unit](
              new IllegalArgumentException(
                s"devices cannot be assigned to the global profile (id=${p.value})",
              ),
            )
          case _          => doobie.free.connection.unit
        }
    }
    // #2108: constructively keyed by `household` (default household 1 for single-household call
    // sites). `ON CONFLICT (household_id, mac)` (V65's uq_devices_household_mac) so a row is created/
    // updated in the writer's household only — the same MAC in another household is a different row.
    (check *>
      sql"INSERT INTO devices(mac,name,profile_id,last_seen_ip,last_seen_at,household_id) VALUES($mac,$name,$pid,NULLIF($ip,''),NOW(),$household) ON CONFLICT(household_id,mac) DO UPDATE SET name=EXCLUDED.name,profile_id=EXCLUDED.profile_id RETURNING id"
        .query[DeviceId]
        .unique).transact(xa)
  }
  def updateLastSeen(mac: MacAddress, ip: String, household: HouseholdId = HouseholdId.Default) =
    // #2125: AND-scoped to `household` so it can only touch its own household's row.
    sql"UPDATE devices SET last_seen_ip=$ip,last_seen_at=NOW() WHERE household_id=$household AND mac=$mac".update.run
      .transact(xa)
      .unit
  def touchLastSeen(
      mac: MacAddress,
      ip: Option[IpAddress],
      at: Instant,
      household: HouseholdId = HouseholdId.Default,
  ) =
    // #1511 SSOT: route through the batch primitive so the UPDATE template lives in one place.
    touchLastSeenBatch(List((mac, ip)), at, household)
  def touchLastSeenBatch(
      items: List[(MacAddress, Option[IpAddress])],
      at: Instant,
      household: HouseholdId = HouseholdId.Default,
  ) =
    if items.isEmpty then ZIO.succeed(0)
    else
      DbMetrics.timed("device.touchLastSeenBatch")(
        // Same per-row UPDATE template as `touchLastSeen`; Doobie's `updateMany` runs them as one
        // JDBC batch. We don't need the per-row affected count, just the sum — that matches what
        // a future operator query would care about. (#1511)
        // #2108: the UPDATE is AND-scoped to `household` (constant per batch — the router's), so an
        // ingest can only refresh its own household's device rows.
        Update[(Option[IpAddress], Instant, HouseholdId, MacAddress)](
          "UPDATE devices SET last_seen_ip=COALESCE(?,last_seen_ip),last_seen_at=? WHERE household_id=? AND mac=?",
        ).updateMany(items.map { case (mac, ip) => (ip, at, household, mac) }).transact(xa),
      )
  def upsertUnknown(
      mac: MacAddress,
      name: String,
      ip: Option[IpAddress],
      at: Instant,
      household: HouseholdId = HouseholdId.Default,
  ) =
    DbMetrics.timed("device.upsertUnknown")(
      // #2108: new-device discovery, constructively keyed by `household` (the router's). A first-seen
      // MAC is created unmanaged (profile_id NULL) in the router's household; the same MAC behind
      // another household's gateway is a DIFFERENT row under ON CONFLICT(household_id,mac) (V65's
      // uq_devices_household_mac). Never lookup-and-reject (design §3.2.2).
      sql"""INSERT INTO devices(mac,name,profile_id,last_seen_ip,last_seen_at,household_id)
          VALUES($mac,$name,NULL,$ip,$at,$household)
          ON CONFLICT(household_id,mac) DO UPDATE
          SET last_seen_ip=COALESCE(EXCLUDED.last_seen_ip,devices.last_seen_ip),
              last_seen_at=EXCLUDED.last_seen_at
          RETURNING id"""
        .query[DeviceId]
        .unique
        .transact(xa),
    )
  def renameIfAutoGenerated(
      mac: MacAddress,
      newName: String,
      household: HouseholdId = HouseholdId.Default,
  ) = {
    val autoNamePattern = "^device-[0-9a-fA-F]+$"
    DbMetrics.timed("device.renameIfAutoGenerated")(
      // #2108: AND-scoped to `household` so an ingest only renames its own household's device.
      sql"""UPDATE devices SET name=$newName
          WHERE mac=$mac AND household_id=$household AND (name='unknown' OR name ~ $autoNamePattern)""".update.run
        .transact(xa),
    )
  }
  def delete(mac: MacAddress, household: HouseholdId = HouseholdId.Default) =
    // #2125: household-scoped so deleting (hhA, mac) never removes another household's (hhB, mac)
    // row. The user-facing DELETE route passes `claims.hh` after a household-scoped lookup.
    sql"DELETE FROM devices WHERE household_id=$household AND mac=$mac".update.run.transact(xa).unit
}

class AlertRepoLive(xa: Transactor[Task]) extends AlertRepo {
  // Row tuple for the joined SELECT used by every read path. Order MUST
  // match `baseSelect` below — the Doobie codec is positional.
  private type R = (
      AlertId,
      String,             // kind
      String,             // status
      MacAddress,
      Option[String],     // device name
      Option[ProfileId],
      Option[String],     // profile name
      Option[Hostname],
      Option[String],     // request_kind
      Option[String],     // note
      Option[Int],        // granted_minutes
      String,             // created_at
      Option[String],     // decided_at
      Option[String],     // decided_by
      Option[HouseholdId],// #2283: the alert's OWN household_id (a.household_id, NOT NULL); Option
      // only because the positional codec keeps the column nullable-tolerant.
  )

  // Reads parse the kind/status strings; values are presumed canonical
  // because the DB CHECK constraints enforce the enum. A parse failure
  // means a schema drift and should crash loudly, not silently degrade.
  private def toAlert(r: R): Alert = Alert(
    id = r._1,
    kind = AlertKind
      .parse(r._2)
      .getOrElse(throw new IllegalStateException(s"DB has unknown alert kind: ${r._2}")),
    status = AlertStatus
      .parse(r._3)
      .getOrElse(throw new IllegalStateException(s"DB has unknown alert status: ${r._3}")),
    mac = r._4,
    deviceName = r._5,
    profileId = r._6,
    profileName = r._7,
    host = r._8,
    requestKind = r._9.map(s =>
      AccessRequestKind
        .parse(s)
        .getOrElse(throw new IllegalStateException(s"DB has unknown request_kind: $s")),
    ),
    note = r._10,
    grantedMinutes = r._11,
    createdAt = r._12,
    decidedAt = r._13,
    decidedBy = r._14,
    householdId = r._15,
  )

  // #2283: read the alert's OWN `a.household_id` (its tenancy key, V78) — NOT the joined device's.
  // The devices join is now qualified `d.mac = a.mac AND d.household_id = a.household_id` so a MAC
  // shared across households (V74) resolves `d.name` to the alert's OWN household's device instead of
  // matching both rows. Reading `a.household_id` also keeps attribution correct for an orphaned alert
  // (device deleted, interim V74→#2283 loss of ON DELETE CASCADE) where the LEFT JOIN yields no `d`.
  private val baseSelect = fr"""
    SELECT a.id, a.kind, a.status, a.mac, d.name, a.profile_id, p.name,
           a.host, a.request_kind, a.note, a.granted_minutes,
           a.created_at::TEXT, a.decided_at::TEXT, a.decided_by, a.household_id
      FROM alerts a
      LEFT JOIN devices  d ON d.mac = a.mac AND d.household_id = a.household_id
      LEFT JOIN profiles p ON p.id = a.profile_id
  """

  def raiseNewDevice(
      mac: MacAddress,
      firstSeenAt: Instant,
      household: HouseholdId,
  ): Task[Unit] =
    // ON CONFLICT-style idempotency: insert only if no row with this
    // (household, mac) already exists for kind='new_device'. We can't use a
    // UNIQUE index because the same mac may legitimately have multiple
    // access_request rows over time. WHERE NOT EXISTS keeps this race-free
    // under the SERIALIZABLE-equivalent semantics of a single-statement insert.
    // #2283: the dedup is scoped to `household_id` (not global), so a MAC
    // discovered in two households (post-V74) raises one alert PER household.
    DbMetrics.timed("alert.raiseNewDevice")(
      sql"""INSERT INTO alerts (kind, status, mac, household_id, created_at)
          SELECT 'new_device', 'pending', $mac, $household, $firstSeenAt
          WHERE NOT EXISTS (
            SELECT 1 FROM alerts
             WHERE mac = $mac AND kind = 'new_device' AND household_id = $household
          )""".update.run.transact(xa).unit,
    )

  def createAccessRequest(
      household: HouseholdId,
      mac: MacAddress,
      profileId: Option[ProfileId],
      host: Hostname,
      requestKind: AccessRequestKind,
      note: Option[String],
      createdAt: Instant,
  ): Task[AlertId] = {
    val rkStr = AccessRequestKind.asString(requestKind)
    // #2283 stamped the alert's tenancy key (V78) by deriving it in-SQL from the device row, because
    // this unauthenticated block-page path had no household in scope. #2322 gives it one: the caller
    // resolves the router-bound block-page token and passes the household in, so a MAC that exists
    // in two households (V74/V75) attributes to the router the request actually came through rather
    // than to whichever household sorts first.
    sql"""INSERT INTO alerts (kind, status, mac, household_id, profile_id, host, request_kind, note, created_at)
          VALUES ('access_request', 'pending', $mac, $household,
                  $profileId, $host, $rkStr, $note, $createdAt)
          RETURNING id"""
      .query[AlertId]
      .unique
      .transact(xa)
  }

  def findRecentAccessRequest(
      household: HouseholdId,
      mac: MacAddress,
      host: Hostname,
      since: Instant,
  ): Task[Option[Alert]] =
    // #2566: `a.household_id` (V79, NOT NULL) — the same tenancy key `listForHousehold` reads.
    (baseSelect ++ fr"""WHERE a.kind = 'access_request'
                           AND a.household_id = $household
                           AND a.mac = $mac
                           AND a.host = $host
                           AND a.created_at >= $since
                         ORDER BY a.created_at DESC
                         LIMIT 1""")
      .query[R]
      .map(toAlert)
      .option
      .transact(xa)

  def findById(id: AlertId): Task[Option[Alert]] =
    (baseSelect ++ fr"WHERE a.id = ${id.value}")
      .query[R]
      .map(toAlert)
      .option
      .transact(xa)

  // #2564: the household that owns `id`, for the per-id route guards. Index-backed by the primary
  // key. `a.household_id` is the alert's own tenancy key (V78/V79, NOT NULL) — no device join.
  def householdOf(id: AlertId): Task[Option[HouseholdId]] =
    DbMetrics.timed("alert.householdOf")(
      sql"SELECT household_id FROM alerts WHERE id = ${id.value}"
        .query[HouseholdId]
        .option
        .transact(xa),
    )

  def list(includeAll: Boolean): Task[List[Alert]] = {
    val filter = if includeAll then fr"" else fr"WHERE a.status = 'pending'"
    (baseSelect ++ filter ++ fr"ORDER BY a.created_at DESC")
      .query[R]
      .map(toAlert)
      .to[List]
      .transact(xa)
  }

  // #2283: scope on the alert's OWN `a.household_id` (V78), NOT the joined `d.household_id`. Once a
  // MAC can exist in two households (V74) the device join is ambiguous, so #2108's transitive scope
  // no longer isolates. `a.household_id = $hh` is the alert's authoritative tenancy key: it isolates
  // correctly even for a shared MAC AND still returns an orphaned alert (device deleted) to its own
  // household — the old `d.household_id` predicate silently dropped those.
  def listForHousehold(includeAll: Boolean, household: HouseholdId): Task[List[Alert]] = {
    val statusFilter = if includeAll then fr"" else fr"AND a.status = 'pending'"
    (baseSelect ++ fr"WHERE" ++ SqlFragments.householdEq(household, "a.household_id") ++
      statusFilter ++ fr"ORDER BY a.created_at DESC")
      .query[R]
      .map(toAlert)
      .to[List]
      .transact(xa)
  }

  def decide(
      id: AlertId,
      newStatus: AlertStatus,
      decidedAt: Instant,
      decidedBy: String,
      grantedMinutes: Option[Int],
  ): Task[Int] = {
    val statusStr = AlertStatus.asString(newStatus)
    sql"""UPDATE alerts
             SET status = $statusStr,
                 decided_at = $decidedAt,
                 decided_by = $decidedBy,
                 granted_minutes = $grantedMinutes
           WHERE id = ${id.value} AND status = 'pending'""".update.run
      .transact(xa)
  }
}

class BlocklistRepoLive(xa: Transactor[Task]) extends BlocklistRepo {
  def insertBatch(ds: List[(String, String)]) = Update[(String, String)](
    "INSERT INTO blocklist_domains(domain,category) VALUES(?,?) ON CONFLICT DO NOTHING",
  ).updateMany(ds).transact(xa)
  def clearCategory(cat: BlocklistId)         =
    sql"DELETE FROM blocklist_domains WHERE category=${cat.value}".update.run.transact(xa).unit
  def listCategories                          = DbMetrics.timed("blocklist.listCategories")(
    sql"SELECT DISTINCT category FROM blocklist_domains ORDER BY category"
      .query[BlocklistId]
      .to[List]
      .transact(xa),
  )
  def countByCategory                         =
    sql"SELECT category,COUNT(*)::INT FROM blocklist_domains GROUP BY category ORDER BY category"
      .query[(BlocklistId, Int)]
      .to[List]
      .transact(xa)
  def loadCategory(cat: BlocklistId)          =
    DbMetrics.timed("blocklist.loadCategory")(
      sql"SELECT domain FROM blocklist_domains WHERE category=${cat.value}"
        .query[Hostname]
        .to[List]
        .transact(xa)
        .map(_.toSet),
    )
  def loadAll                                 = sql"SELECT category,domain FROM blocklist_domains"
    .query[(BlocklistId, Hostname)]
    .to[List]
    .transact(xa)
    .map(_.groupBy(_._1).map((k, vs) => k -> vs.map(_._2).toSet))

  def categoriesForDomains(domains: List[String]) =
    if domains.isEmpty then ZIO.succeed(Map.empty)
    else
      DbMetrics.timed("blocklist.categoriesForDomains") {
        val arr = domains.distinct.toArray
        sql"SELECT domain, category FROM blocklist_domains WHERE domain = ANY($arr)"
          .query[(String, BlocklistId)]
          .to[List]
          .transact(xa)
          .map(_.groupBy(_._1).map((d, vs) => d -> vs.map(_._2).distinct.sorted))
      }

  def upsertMeta(
      id: BlocklistId,
      name: String,
      description: Option[String],
      bundled: Boolean,
      source: Option[String],
      lastBuiltAt: java.time.Instant,
  ) =
    sql"""INSERT INTO blocklists (id, display_name, description, bundled, source_url, last_built_at)
          VALUES (${id.value}, $name, $description, $bundled, $source, $lastBuiltAt)
          ON CONFLICT (id) DO UPDATE SET
            display_name  = EXCLUDED.display_name,
            description   = EXCLUDED.description,
            bundled       = EXCLUDED.bundled,
            source_url    = EXCLUDED.source_url,
            last_built_at = EXCLUDED.last_built_at""".update.run
      .transact(xa)
      .unit

  def summaries =
    sql"""SELECT b.id, b.display_name, b.description, b.bundled, b.source_url,
                 COALESCE(c.n, 0)::INT AS host_count, b.last_built_at
          FROM blocklists b
          LEFT JOIN (
            SELECT category, COUNT(*) AS n
            FROM blocklist_domains
            GROUP BY category
          ) c ON c.category = b.id
          ORDER BY b.id"""
      .query[wifihaven.shared.BlocklistSummary]
      .to[List]
      .transact(xa)

  def findMeta(id: BlocklistId) =
    sql"""SELECT b.id, b.display_name, b.description, b.bundled, b.source_url,
                 COALESCE(c.n, 0)::INT AS host_count, b.last_built_at
          FROM blocklists b
          LEFT JOIN (
            SELECT category, COUNT(*) AS n
            FROM blocklist_domains
            WHERE category = ${id.value}
            GROUP BY category
          ) c ON c.category = b.id
          WHERE b.id = ${id.value}"""
      .query[wifihaven.shared.BlocklistSummary]
      .option
      .transact(xa)
}

class TimeUsageRepoLive(xa: Transactor[Task]) extends TimeUsageRepo {
  def incrementSecondsAndBytes(
      mac: MacAddress,
      host: HostId,
      d: LocalDate,
      seconds: Long,
      bytesIn: Long,
      bytesOut: Long,
      proportionalSeconds: Long = 0L,
      household: HouseholdId = HouseholdId.Default,
  ): Task[Unit] =
    // #1511 SSOT: both the per-row and batch paths share one upsert template, owned by
    // `incrementSecondsAndBytesBatch`. Per-row callers (test seeders, fixtures) pay one extra hop
    // through a singleton list; the production hot path always calls the batch method directly.
    incrementSecondsAndBytesBatch(
      List(TimeUsageIncrement(mac, host, d, seconds, bytesIn, bytesOut, proportionalSeconds)),
      household,
    )
  def incrementSecondsAndBytesBatch(
      rows: List[TimeUsageIncrement],
      household: HouseholdId = HouseholdId.Default,
  ): Task[Unit] =
    if rows.isEmpty then ZIO.unit
    else
      DbMetrics.timed("timeUsage.incrementSecondsAndBytesBatch")(
        // #2108: `household_id` is stamped on every row and is the leading column of the ON CONFLICT
        // key (V65's uq_time_usage_household_mac_host_date), so a router's usage accretes onto its
        // own household's rows only.
        Update[(HouseholdId, MacAddress, String, String, LocalDate, Long, Long, Long, Long)](
          // Same upsert template as the per-row method: one VALUES row per increment, additive
          // ON CONFLICT. Doobie issues a single batched statement, collapsing the per-(mac, host)
          // round trips that dominated the /api/router/usage hot path (#1511).
          "INSERT INTO time_usage(household_id,device_mac,host_type,host_value,date,seconds_used,proportional_seconds,bytes_in,bytes_out,last_seen_at) " +
            "VALUES(?,?,?,?,?,?,?,?,?,NOW()) " +
            "ON CONFLICT(household_id,device_mac,host_type,host_value,date) DO UPDATE " +
            "SET seconds_used=time_usage.seconds_used+EXCLUDED.seconds_used," +
            "    proportional_seconds=time_usage.proportional_seconds+EXCLUDED.proportional_seconds," +
            "    bytes_in=time_usage.bytes_in+EXCLUDED.bytes_in," +
            "    bytes_out=time_usage.bytes_out+EXCLUDED.bytes_out," +
            "    last_seen_at=NOW()",
        ).updateMany(
          rows.map(r =>
            (
              household,
              r.mac,
              r.host.kind,
              r.host.value,
              r.date,
              r.seconds,
              r.proportionalSeconds,
              r.bytesIn,
              r.bytesOut,
            ),
          ),
        ).transact(xa)
          .unit,
      )
  def getProportionalSeconds(mac: MacAddress, host: HostId, d: LocalDate): Task[Long]           =
    sql"SELECT COALESCE(proportional_seconds,0) FROM time_usage WHERE device_mac=$mac AND host_type=${host.kind} AND host_value=${host.value} AND date=$d"
      .query[Long]
      .option
      .transact(xa)
      .map(_.getOrElse(0L))
  def getSecondsUsed(mac: MacAddress, host: HostId, d: LocalDate): Task[Long]                   =
    sql"SELECT COALESCE(seconds_used,0) FROM time_usage WHERE device_mac=$mac AND host_type=${host.kind} AND host_value=${host.value} AND date=$d"
      .query[Long]
      .option
      .transact(xa)
      .map(_.getOrElse(0L))
  def getSecondsAndBytes(mac: MacAddress, host: HostId, d: LocalDate): Task[(Long, Long, Long)] =
    sql"SELECT COALESCE(seconds_used,0),COALESCE(bytes_in,0),COALESCE(bytes_out,0) FROM time_usage WHERE device_mac=$mac AND host_type=${host.kind} AND host_value=${host.value} AND date=$d"
      .query[(Long, Long, Long)]
      .option
      .transact(xa)
      .map(_.getOrElse((0L, 0L, 0L)))
  // TODO(#730): remove this read-side join once usage records carry dest_ip.
  // The LATERAL subquery promotes ipv4-typed time_usage rows to their resolved
  // fqdn by looking up the most recent connection_events row for the same
  // (mac, dest_ip) that has a resolved_host_value. The partial index added in
  // V22 (idx_conn_events_mac_dest_resolved) keeps the join cheap.
  def listForDevice(mac: MacAddress, d: LocalDate)                                              =
    sql"""SELECT tu.id,
                 tu.device_mac,
                 CASE WHEN tu.host_type IN ('ipv4','ipv6') AND ce.resolved_host_value IS NOT NULL
                      THEN 'fqdn' ELSE tu.host_type END,
                 COALESCE(CASE WHEN tu.host_type IN ('ipv4','ipv6') THEN ce.resolved_host_value END,
                          tu.host_value),
                 tu.date::TEXT,
                 (COALESCE(tu.seconds_used,0)/60)::INT,
                 tu.last_seen_at::TEXT
          FROM time_usage tu
          LEFT JOIN LATERAL (
            SELECT resolved_host_value
            FROM connection_events
            WHERE mac          = tu.device_mac
              AND dest_ip      = tu.host_value
              AND resolved_host_value IS NOT NULL
              AND ts >= $d::TIMESTAMPTZ
              AND ts <  ($d::DATE + INTERVAL '1 day')::TIMESTAMPTZ
            ORDER BY ts DESC LIMIT 1
          ) ce ON tu.host_type IN ('ipv4','ipv6')
          WHERE tu.device_mac = $mac AND tu.date = $d
          ORDER BY tu.seconds_used DESC"""
      .query[(TimeUsageId, MacAddress, HostId, String, Int, String)]
      .map(TimeUsage.apply)
      .to[List]
      .transact(xa)
  // TODO(#730): remove this read-side join once usage records carry dest_ip.
  def listForDeviceMacs(macs: List[MacAddress], d: LocalDate)                                   =
    if macs.isEmpty then ZIO.succeed(Nil)
    else {
      val arr = macs.map(_.value).toArray
      sql"""SELECT tu.id,
                   tu.device_mac,
                   CASE WHEN tu.host_type IN ('ipv4','ipv6') AND ce.resolved_host_value IS NOT NULL
                        THEN 'fqdn' ELSE tu.host_type END,
                   COALESCE(CASE WHEN tu.host_type IN ('ipv4','ipv6') THEN ce.resolved_host_value END,
                            tu.host_value),
                   tu.date::TEXT,
                   (COALESCE(tu.seconds_used,0)/60)::INT,
                   tu.last_seen_at::TEXT
            FROM time_usage tu
            LEFT JOIN LATERAL (
              SELECT resolved_host_value
              FROM connection_events
              WHERE mac          = tu.device_mac
                AND dest_ip      = tu.host_value
                AND resolved_host_value IS NOT NULL
                AND ts >= $d::TIMESTAMPTZ
                AND ts <  ($d::DATE + INTERVAL '1 day')::TIMESTAMPTZ
              ORDER BY ts DESC LIMIT 1
            ) ce ON tu.host_type IN ('ipv4','ipv6')
            WHERE tu.device_mac = ANY($arr) AND tu.date = $d
            ORDER BY tu.device_mac, tu.seconds_used DESC"""
        .query[(TimeUsageId, MacAddress, HostId, String, Int, String)]
        .map(TimeUsage.apply)
        .to[List]
        .transact(xa)
    }
  // TODO(#730): remove this read-side join once usage records carry dest_ip.
  def snapshotAll(d: LocalDate)                                                                 =
    sql"""SELECT tu.device_mac,
                 CASE WHEN tu.host_type IN ('ipv4','ipv6') AND ce.resolved_host_value IS NOT NULL
                      THEN 'fqdn' ELSE tu.host_type END,
                 COALESCE(CASE WHEN tu.host_type IN ('ipv4','ipv6') THEN ce.resolved_host_value END,
                          tu.host_value),
                 (COALESCE(tu.seconds_used,0)/60)::INT
          FROM time_usage tu
          LEFT JOIN LATERAL (
            SELECT resolved_host_value
            FROM connection_events
            WHERE mac          = tu.device_mac
              AND dest_ip      = tu.host_value
              AND resolved_host_value IS NOT NULL
              AND ts >= $d::TIMESTAMPTZ
              AND ts <  ($d::DATE + INTERVAL '1 day')::TIMESTAMPTZ
            ORDER BY ts DESC LIMIT 1
          ) ce ON tu.host_type IN ('ipv4','ipv6')
          WHERE tu.date = $d"""
      .query[(MacAddress, HostId, Int)]
      .to[List]
      .transact(xa)
      .map(_.map((m, host, mins) => (m, host) -> mins).toMap)
}

class TimeExtensionRepoLive(xa: Transactor[Task]) extends TimeExtensionRepo {
  def getTotalExtension(mac: MacAddress, d: LocalDate)                    =
    sql"SELECT COALESCE(SUM(extra_minutes),0)::INT FROM time_extensions WHERE device_mac=$mac AND date=$d"
      .query[Int]
      .unique
      .transact(xa)
  def grant(
      mac: MacAddress,
      d: LocalDate,
      mins: Int,
      by: String,
      note: Option[String],
      household: HouseholdId = HouseholdId.Default,
  ) =
    // #2108: stamp `household_id` so a MAC-keyed extension is scoped to the caller's household
    // (V65 added the column; time_extensions has no unique key to widen — the stamp is the scoping).
    sql"INSERT INTO time_extensions(device_mac,date,extra_minutes,granted_by,note,household_id) VALUES($mac,$d,$mins,$by,$note,$household) RETURNING id"
      .query[TimeExtensionId]
      .unique
      .transact(xa)
  def listForDevice(mac: MacAddress, d: LocalDate)                        =
    sql"SELECT id,profile_id,device_mac,date::TEXT,extra_minutes,granted_by,note,created_at::TEXT FROM time_extensions WHERE device_mac=$mac AND date=$d ORDER BY created_at"
      .query[
        (
            TimeExtensionId,
            Option[ProfileId],
            Option[MacAddress],
            String,
            Int,
            String,
            Option[String],
            String,
        ),
      ]
      .map(TimeExtension.apply)
      .to[List]
      .transact(xa)
  def snapshotAll(d: LocalDate)                                           =
    sql"SELECT device_mac,SUM(extra_minutes)::INT FROM time_extensions WHERE date=$d AND device_mac IS NOT NULL GROUP BY device_mac"
      .query[(MacAddress, Int)]
      .to[List]
      .transact(xa)
      .map(_.toMap)
  def grantForProfile(
      pid: ProfileId,
      d: LocalDate,
      mins: Int,
      by: String,
      note: Option[String],
      household: HouseholdId,
  ) =
    // #2130: stamp household_id like [[grant]] does (#2108) — never V65's DEFAULT 1.
    sql"INSERT INTO time_extensions(profile_id,date,extra_minutes,granted_by,note,household_id) VALUES($pid,$d,$mins,$by,$note,$household) RETURNING id"
      .query[TimeExtensionId]
      .unique
      .transact(xa)
  def getProfileTotalExtension(pid: ProfileId, d: LocalDate)              =
    sql"SELECT COALESCE(SUM(extra_minutes),0)::INT FROM time_extensions WHERE profile_id=$pid AND date=$d"
      .query[Int]
      .unique
      .transact(xa)
  def listForProfile(pid: ProfileId, d: LocalDate)                        =
    sql"SELECT id,profile_id,device_mac,date::TEXT,extra_minutes,granted_by,note,created_at::TEXT FROM time_extensions WHERE profile_id=$pid AND date=$d ORDER BY created_at"
      .query[
        (
            TimeExtensionId,
            Option[ProfileId],
            Option[MacAddress],
            String,
            Int,
            String,
            Option[String],
            String,
        ),
      ]
      .map(TimeExtension.apply)
      .to[List]
      .transact(xa)
  // #2264: household-scoped via the `profiles.household_id` join (idx_profiles_household, V65).
  def snapshotByProfileForHousehold(household: HouseholdId, d: LocalDate) =
    DbMetrics.timed("timeExtension.snapshotByProfileForHousehold")(
      // The inner JOIN already drops the MAC-keyed (NULL profile_id) grants, so no
      // `profile_id IS NOT NULL` guard is needed on top of it.
      (fr"""SELECT te.profile_id, SUM(te.extra_minutes)::INT
            FROM time_extensions te
            JOIN profiles p ON p.id = te.profile_id
            WHERE te.date = $d AND """ ++
        SqlFragments.householdEq(household, "p.household_id") ++
        fr"GROUP BY te.profile_id")
        .query[(ProfileId, Int)]
        .to[List]
        .transact(xa)
        .map(_.toMap),
    )
}

class RouterRepoLive(xa: Transactor[Task]) extends RouterRepo {
  private type R =
    (
        RouterId,
        String,
        Option[Sha256Hex],
        Option[Sha256Hex],
        Option[Instant],
        Option[ETag],
        Instant,
        Option[String],
        Option[Instant],
        HouseholdId,
    )
  private def toR(r: R)                           =
    Router(
      r._1,
      r._2,
      r._3,
      r._4,
      r._5.map(_.toString),
      r._6,
      r._7.toString,
      r._8,
      r._9.map(_.toString),
      r._10,
    )
  private val cols                                =
    fr"id,name,enrollment_token_hash,token_hash,last_seen_at,last_etag,created_at,agent_version,enrollment_expires_at,household_id"
  // #2083: default TTL for a freshly-created enrollment token.
  private val EnrollmentTtl                       = fr"INTERVAL '1 hour'"
  def listAll                                     =
    (fr"SELECT " ++ cols ++ fr" FROM routers ORDER BY created_at")
      .query[R]
      .map(toR)
      .to[List]
      .transact(xa)
  // #2108: same projection as listAll, AND-scoped to one household. Index-backed by
  // V65's idx_routers_household.
  def listAllForHousehold(household: HouseholdId) =
    (fr"SELECT " ++ cols ++ fr" FROM routers WHERE" ++ SqlFragments.householdEq(
      household,
    ) ++ fr"ORDER BY created_at")
      .query[R]
      .map(toR)
      .to[List]
      .transact(xa)
  def findById(id: RouterId)                      =
    (fr"SELECT " ++ cols ++ fr" FROM routers WHERE id=$id")
      .query[R]
      .map(toR)
      .option
      .transact(xa)
  // #2083: an enrollment token past its TTL no longer matches, even though the hash
  // column itself isn't cleared until first use — a leaked but never-redeemed token
  // stops being valid after 1h instead of indefinitely.
  def findByEnrollmentTokenHash(h: Sha256Hex)     =
    (fr"SELECT " ++ cols ++
      fr" FROM routers WHERE enrollment_token_hash=$h AND enrollment_expires_at > NOW()")
      .query[R]
      .map(toR)
      .option
      .transact(xa)
  def findByTokenHash(h: Sha256Hex)               =
    DbMetrics.timed("router.findByTokenHash")(
      (fr"SELECT " ++ cols ++ fr" FROM routers WHERE token_hash=$h")
        .query[R]
        .map(toR)
        .option
        .transact(xa),
    )
  def create(name: String, enrollmentTokenHash: Sha256Hex, householdId: HouseholdId) =
    (fr"INSERT INTO routers(name,enrollment_token_hash,enrollment_expires_at,household_id)" ++
      fr"VALUES($name,$enrollmentTokenHash,NOW() + " ++ EnrollmentTtl ++ fr",$householdId) RETURNING id")
      .query[RouterId]
      .unique
      .transact(xa)
  def completeEnrollment(id: RouterId, tokenHash: Sha256Hex)                         =
    sql"UPDATE routers SET token_hash=$tokenHash, enrollment_token_hash=NULL, last_seen_at=NOW() WHERE id=$id".update.run
      .transact(xa)
      .unit
  def touch(id: RouterId, etag: Option[ETag], agentVersion: Option[String])          =
    DbMetrics.timed("router.touch")(
      sql"""UPDATE routers
          SET last_seen_at=NOW(),
              last_etag=COALESCE($etag,last_etag),
              agent_version=COALESCE($agentVersion,agent_version)
          WHERE id=$id""".update.run
        .transact(xa)
        .unit,
    )
  def countSeenSince(cutoff: Instant)                                                =
    DbMetrics.timed("router.countSeenSince")(
      sql"SELECT count(*) FROM routers WHERE last_seen_at >= $cutoff"
        .query[Int]
        .unique
        .transact(xa),
    )
  def delete(id: RouterId)                                                           =
    sql"DELETE FROM routers WHERE id=$id".update.run.transact(xa).unit
}

class TrafficReportRepoLive(xa: Transactor[Task]) extends TrafficReportRepo {
  private type R =
    (
        TrafficReportId,
        RouterId,
        MacAddress,
        Option[IpAddress],
        HostId,
        String,
        String,
        String,
        Int,
        Long,
        Long,
    )
  private def toT(r: R)                                                       =
    TrafficReport(r._1, r._2, r._3, r._4, r._5, r._6, r._7, r._8, r._9, r._10, r._11)
  def insertBatch(reports: List[TrafficReportInsert])                         =
    DbMetrics.timed("traffic.insertBatch")(
      Update[TrafficReportInsert](
        "INSERT INTO traffic_reports(router_id,mac,ip,host_type,host_value,date,period_start,period_end,active_seconds,bytes_in,bytes_out,dest_ip,active_start,active_end) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(router_id,period_start,mac,host_type,host_value) DO NOTHING",
      ).updateMany(reports).transact(xa),
    )
  // TODO(#730): remove this read-side join once usage records carry dest_ip.
  // Promotes ipv4/ipv6-typed traffic_reports rows to their resolved fqdn at
  // SELECT time via the same LATERAL join used in TimeUsageRepoLive.
  def listForDevice(household: HouseholdId, mac: MacAddress, date: LocalDate) =
    (fr"""SELECT tr.id, tr.router_id, tr.mac, tr.ip,
                 CASE WHEN tr.host_type IN ('ipv4','ipv6') AND ce.resolved_host_value IS NOT NULL
                      THEN 'fqdn' ELSE tr.host_type END,
                 COALESCE(CASE WHEN tr.host_type IN ('ipv4','ipv6') THEN ce.resolved_host_value END,
                          tr.host_value),
                 tr.date::TEXT, tr.period_start::TEXT, tr.period_end::TEXT,
                 tr.active_seconds, tr.bytes_in, tr.bytes_out
          FROM traffic_reports tr
          LEFT JOIN LATERAL (
            SELECT resolved_host_value
            FROM connection_events
            WHERE mac          = tr.mac
              AND dest_ip      = tr.host_value
              AND resolved_host_value IS NOT NULL
              AND ts >= $date::TIMESTAMPTZ
              AND ts <  ($date::DATE + INTERVAL '1 day')::TIMESTAMPTZ
            ORDER BY ts DESC LIMIT 1
          ) ce ON tr.host_type IN ('ipv4','ipv6')
          WHERE tr.mac = $mac AND tr.date = $date """
      ++ SqlFragments.householdRouterScope(household, "tr.router_id") ++
      fr"ORDER BY tr.period_start")
      .query[R]
      .map(toT)
      .to[List]
      .transact(xa)
  // TODO(#730): remove this read-side join once usage records carry dest_ip.
  def listForRouter(routerId: RouterId, limit: Int)                           =
    sql"""SELECT tr.id, tr.router_id, tr.mac, tr.ip,
                 CASE WHEN tr.host_type IN ('ipv4','ipv6') AND ce.resolved_host_value IS NOT NULL
                      THEN 'fqdn' ELSE tr.host_type END,
                 COALESCE(CASE WHEN tr.host_type IN ('ipv4','ipv6') THEN ce.resolved_host_value END,
                          tr.host_value),
                 tr.date::TEXT, tr.period_start::TEXT, tr.period_end::TEXT,
                 tr.active_seconds, tr.bytes_in, tr.bytes_out
          FROM traffic_reports tr
          ${SqlFragments.resolvedHostLateral}
          WHERE tr.router_id = $routerId
          ORDER BY tr.period_start DESC LIMIT $limit"""
      .query[R]
      .map(toT)
      .to[List]
      .transact(xa)

  def listPresenceRows(household: HouseholdId, macs: List[MacAddress], date: LocalDate) =
    listPresenceRowsBetween(household, macs, date, date, None)

  def listPresenceRows(
      household: HouseholdId,
      macs: List[MacAddress],
      from: LocalDate,
      to: LocalDate,
  ) =
    listPresenceRowsBetween(household, macs, from, to, None)

  def listPresenceRowsSince(
      household: HouseholdId,
      macs: List[MacAddress],
      date: LocalDate,
      since: Instant,
  ) =
    listPresenceRowsBetween(household, macs, date, date, Some(since))

  def listPresenceRowsInWindow(
      household: HouseholdId,
      macs: List[MacAddress],
      fromInstant: Instant,
      toInstant: Instant,
  ): Task[List[wifihaven.api.presence.PresenceRow]] = {
    type Row = (
        MacAddress,
        LocalDate,
        Instant,
        HostId,
        Int,
        Long,
        Long,
        Instant,
        Instant,
        Option[Instant],
        Option[Instant],
    )
    macs match {
      case Nil => ZIO.succeed(List.empty[wifihaven.api.presence.PresenceRow])
      case ms  =>
        val nel = cats.data.NonEmptyList.fromListUnsafe(ms.map(_.value))
        // Filter on period_start (the partition key) so Postgres can prune to the
        // single day-partition(s) the window touches. The old day path filtered on
        // tr.date — not the partition key — which defeated pruning and let one
        // profile's read scan the whole table for 90s (#1099). Same SELECT/LATERAL
        // shape as listPresenceRowsBetween, so the row set is identical.
        val q   =
          fr"""SELECT tr.mac, tr.date, tr.period_start,
                      CASE WHEN tr.host_type IN ('ipv4','ipv6') AND ce.resolved_host_value IS NOT NULL
                           THEN 'fqdn' ELSE tr.host_type END,
                      COALESCE(CASE WHEN tr.host_type IN ('ipv4','ipv6') THEN ce.resolved_host_value END,
                               tr.host_value),
                      tr.active_seconds, tr.bytes_in, tr.bytes_out, tr.period_start, tr.period_end,
                      tr.active_start, tr.active_end
               FROM traffic_reports tr
               ${SqlFragments.resolvedHostLateral}
               WHERE tr.period_start >= $fromInstant AND tr.period_start < $toInstant
                 AND (tr.active_seconds > 0 OR tr.bytes_in > 0 OR tr.bytes_out > 0)
                 ${SqlFragments.householdRouterScope(household, "tr.router_id")}
                 AND """ ++ Fragments.in(fr"tr.mac", nel)
        val cio =
          q.query[Row]
            .map { case (m, d, ps, host, secs, bin, bout, pStart, pEnd, aStart, aEnd) =>
              val periodSeconds = math.max(0L, pEnd.getEpochSecond - pStart.getEpochSecond).toInt
              wifihaven.api.presence
                .PresenceRow(m, d, ps, host, secs, bin + bout, periodSeconds, aStart, aEnd)
            }
            .to[List]
        // Bound each per-request read: a pathological window fails fast with a typed
        // QueryTimeoutException (→ 503) instead of holding a pool connection open and
        // wedging the whole instance (#1099).
        QueryTimeout.bounded(xa, QueryTimeout.PresenceWindow)(cio)
    }
  }

  // TODO(#730): remove this read-side join once usage records carry dest_ip.
  private def listPresenceRowsBetween(
      household: HouseholdId,
      macs: List[MacAddress],
      from: LocalDate,
      to: LocalDate,
      since: Option[Instant] = None,
  ) = {
    type Row = (
        MacAddress,
        LocalDate,
        Instant,
        HostId,
        Int,
        Long,
        Long,
        Instant,
        Instant,
        Option[Instant],
        Option[Instant],
    )
    macs match {
      case Nil => ZIO.succeed(List.empty[wifihaven.api.presence.PresenceRow])
      case ms  =>
        val nel = cats.data.NonEmptyList.fromListUnsafe(ms.map(_.value))
        val q   =
          fr"""SELECT tr.mac, tr.date, tr.period_start,
                      CASE WHEN tr.host_type IN ('ipv4','ipv6') AND ce.resolved_host_value IS NOT NULL
                           THEN 'fqdn' ELSE tr.host_type END,
                      COALESCE(CASE WHEN tr.host_type IN ('ipv4','ipv6') THEN ce.resolved_host_value END,
                               tr.host_value),
                      tr.active_seconds, tr.bytes_in, tr.bytes_out, tr.period_start, tr.period_end,
                      tr.active_start, tr.active_end
               FROM traffic_reports tr
               ${SqlFragments.resolvedHostLateral}
               WHERE tr.date BETWEEN $from AND $to
                 AND (tr.active_seconds > 0 OR tr.bytes_in > 0 OR tr.bytes_out > 0)
                 ${SqlFragments.householdRouterScope(household, "tr.router_id")}
                 AND """ ++ Fragments.in(fr"tr.mac", nel) ++
            since.fold(fr"")(s => fr"AND tr.period_start >= $s")
        DbMetrics.timed("traffic.listPresenceRows")(
          q.query[Row]
            .map { case (m, d, ps, host, secs, bin, bout, pStart, pEnd, aStart, aEnd) =>
              val periodSeconds = math.max(0L, pEnd.getEpochSecond - pStart.getEpochSecond).toInt
              wifihaven.api.presence
                .PresenceRow(m, d, ps, host, secs, bin + bout, periodSeconds, aStart, aEnd)
            }
            .to[List]
            .transact(xa),
        )
    }
  }

  // #846: raw row range pull. Wide range × all-macs scans traffic_reports — caller
  // (UsageTrafficService) enforces a window cap that keeps this from melting prod.
  // TODO(#730): remove this read-side join once usage records carry dest_ip.
  def listRawInRange(
      household: HouseholdId,
      macs: List[MacAddress],
      fromInstant: Instant,
      toInstant: Instant,
      cursor: Option[wifihaven.api.usage.RawTrafficCursorKey] = None,
      limit: Option[Int] = None,
  ) = {
    type Row =
      (MacAddress, HostId, Instant, Instant, Int, Long, Long)
    val baseSelect =
      fr"""SELECT tr.mac,
                  CASE WHEN tr.host_type IN ('ipv4','ipv6') AND ce.resolved_host_value IS NOT NULL
                       THEN 'fqdn' ELSE tr.host_type END,
                  COALESCE(CASE WHEN tr.host_type IN ('ipv4','ipv6') THEN ce.resolved_host_value END,
                           tr.host_value),
                  tr.period_start, tr.period_end,
                  tr.active_seconds, tr.bytes_in, tr.bytes_out
           FROM traffic_reports tr
           ${SqlFragments.resolvedHostLateral}
           WHERE tr.period_start >= $fromInstant AND tr.period_start < $toInstant
             AND (tr.active_seconds > 0 OR tr.bytes_in > 0 OR tr.bytes_out > 0) """
        ++ SqlFragments.householdRouterScope(household, "tr.router_id") ++ fr" "
    val macFilter  = macs match {
      case Nil => fr""
      case ms  =>
        val nel = cats.data.NonEmptyList.fromListUnsafe(ms.map(_.value))
        fr"AND " ++ Fragments.in(fr"tr.mac", nel)
    }
    // #862: keyset cursor on (period_start DESC, mac, host_value). Stable
    // tiebreak so concurrent inserts can't shift pages mid-scroll.
    val byCursor   = cursor match {
      case Some(c) =>
        fr"AND (tr.period_start, tr.mac::TEXT, COALESCE(CASE WHEN tr.host_type IN ('ipv4','ipv6') THEN ce.resolved_host_value END, tr.host_value)) < (${c.ts}::TIMESTAMPTZ, ${c.mac}, ${c.host})"
      case None    => fr""
    }
    // #846 audit: newest-first ordering so the SPA renders most-recent at top.
    // #862: add stable secondary keys for keyset cursor.
    val limitFr    = limit.fold(fr"")(n => fr"LIMIT $n")
    val select     = baseSelect ++ macFilter ++ byCursor ++
      fr"ORDER BY tr.period_start DESC, tr.mac ASC, COALESCE(CASE WHEN tr.host_type IN ('ipv4','ipv6') THEN ce.resolved_host_value END, tr.host_value) ASC " ++ limitFr
    DbMetrics.timed("traffic.listRawInRange")(
      select
        .query[Row]
        .map { case (m, h, ps, pe, secs, bi, bo) =>
          wifihaven.api.usage.TrafficUsageDbRow(m, h, ps, pe, secs, bi, bo)
        }
        .to[List]
        .transact(xa),
    )
  }

  // #2174: SQL-side pre-aggregation for the raw-tier fixed-step buckets (1m/10m/1h) — see the trait
  // doc. The inner GROUP BY runs BEFORE the IP→FQDN LATERAL, so the connection_events resolve
  // executes once per (mac, host, bucket) group instead of once per raw report row (the prod 24h
  // window had 755k raw rows; the grouped set is ~an order of magnitude smaller at step=600).
  // `date` rides the GROUP BY solely so `SqlFragments.resolvedHostLateral` — which day-bounds its
  // lookup on `tr.date` — applies verbatim to the aliased subquery; a bucket straddling a
  // household-local date boundary yields two grouped rows, which `buildAggregate` re-merges.
  // TODO(#730): remove this read-side join once usage records carry dest_ip.
  def listRawAggregatedInRange(
      household: HouseholdId,
      macs: List[MacAddress],
      fromInstant: Instant,
      toInstant: Instant,
      stepSeconds: Long,
  ) = {
    type Row = (MacAddress, HostId, Instant, Instant, Int, Long, Long)
    val step      = math.max(1L, stepSeconds)
    val macFilter = macs match {
      case Nil => fr""
      case ms  =>
        val nel = cats.data.NonEmptyList.fromListUnsafe(ms.map(_.value))
        fr"AND " ++ Fragments.in(fr"mac", nel)
    }
    val inner     =
      fr"""SELECT mac, host_type, host_value, date,
                  to_timestamp(floor(extract(epoch FROM period_start) / $step) * $step) AS bucket_start,
                  SUM(active_seconds)::INT AS active_seconds,
                  SUM(bytes_in)::BIGINT    AS bytes_in,
                  SUM(bytes_out)::BIGINT   AS bytes_out
           FROM traffic_reports
           WHERE period_start >= $fromInstant AND period_start < $toInstant
             AND (active_seconds > 0 OR bytes_in > 0 OR bytes_out > 0)
           """ ++ SqlFragments.householdRouterScope(household, "router_id") ++ fr" " ++
        macFilter ++ fr"GROUP BY mac, host_type, host_value, date, bucket_start"
    val select    =
      fr"""SELECT tr.mac,
                  CASE WHEN tr.host_type IN ('ipv4','ipv6') AND ce.resolved_host_value IS NOT NULL
                       THEN 'fqdn' ELSE tr.host_type END,
                  COALESCE(CASE WHEN tr.host_type IN ('ipv4','ipv6') THEN ce.resolved_host_value END,
                           tr.host_value),
                  tr.bucket_start,
                  tr.bucket_start + make_interval(secs => $step),
                  tr.active_seconds, tr.bytes_in, tr.bytes_out
           FROM (""" ++ inner ++ fr""") tr
           ${SqlFragments.resolvedHostLateral}"""
    val cio       = select
      .query[Row]
      .map { case (m, h, bs, be, secs, bi, bo) =>
        wifihaven.api.usage.TrafficUsageDbRow(m, h, bs, be, secs, bi, bo)
      }
      .to[List]
    DbMetrics.timed("traffic.listRawAggregatedInRange")(
      QueryTimeout.bounded(xa, QueryTimeout.RawAggregateWindow)(cio),
    )
  }

  def earliestPeriodStart =
    sql"SELECT MIN(period_start) FROM traffic_reports"
      .query[Option[Instant]]
      .unique
      .transact(xa)

  // #766: per-device FQDN-only host aggregates. Bare-IP rows that fail the
  // LATERAL FQDN resolve are filtered (host IS NULL). Indexed by
  // (mac, period_start) — see V25.
  def listFqdnHostAggregatesForDevice(
      household: HouseholdId,
      mac: MacAddress,
      fromInstant: Instant,
      toInstant: Instant,
  ) = {
    type Row = (String, Long, Long)
    (fr"""SELECT host, SUM(bytes)::BIGINT AS bytes, COUNT(*)::BIGINT AS hits
          FROM (
            SELECT COALESCE(
                     CASE WHEN tr.host_type IN ('ipv4','ipv6') THEN ce.resolved_host_value END,
                     CASE WHEN tr.host_type = 'fqdn' THEN tr.host_value END
                   ) AS host,
                   (tr.bytes_in + tr.bytes_out) AS bytes
            FROM traffic_reports tr
            ${SqlFragments.resolvedHostLateral}
            WHERE tr.mac = $mac
              AND tr.period_start >= $fromInstant
              AND tr.period_start <  $toInstant
              AND (tr.bytes_in + tr.bytes_out) > 0 """
      ++ SqlFragments.householdRouterScope(household, "tr.router_id") ++
      fr"""
          ) sub
          WHERE host IS NOT NULL
          GROUP BY host
          ORDER BY bytes DESC""")
      .query[Row]
      .to[List]
      .transact(xa)
      .map(_.flatMap { case (h, b, hits) =>
        Hostname.parse(h).toOption.map(hn => (hn, b, hits))
      })
  }

  // TODO(#730): remove this read-side join once usage records carry dest_ip.
  def listTrafficRollupRows(household: HouseholdId, f: TrafficRollupFilter) = {
    type Row = (RouterId, MacAddress, HostId, LocalDate, Instant, Instant, Int, Long, Long)
    val base    =
      fr"""SELECT tr.router_id, tr.mac,
                  CASE WHEN tr.host_type IN ('ipv4','ipv6') AND ce.resolved_host_value IS NOT NULL
                       THEN 'fqdn' ELSE tr.host_type END,
                  COALESCE(CASE WHEN tr.host_type IN ('ipv4','ipv6') THEN ce.resolved_host_value END,
                           tr.host_value),
                  tr.date, tr.period_start, tr.period_end,
                  tr.active_seconds, tr.bytes_in, tr.bytes_out
           FROM traffic_reports tr
           ${SqlFragments.resolvedHostLateral}
           WHERE 1=1"""
    val byMacs  = f.macs match {
      case None      => fr""
      case Some(Nil) => fr"AND FALSE"
      case Some(ms)  =>
        val nel = cats.data.NonEmptyList.fromListUnsafe(ms.map(_.value))
        fr"AND " ++ Fragments.in(fr"tr.mac", nel)
    }
    val byHost  = f.host.fold(fr"")(h => fr"AND tr.host_value ILIKE ${s"%$h%"}")
    // TODO(#2584): this bound is on `period_end`, which no `traffic_reports` index covers (they are
    // all on `period_start` / `date` / `mac` / `router_id`), so the planner falls back to a parallel
    // sequential scan of every partition. Measured 2026-08-02 on prod (4.6M rows) over the 30-minute
    // dashboard window: ~4.6s warm in THIS scoped shape vs ~4.9s unscoped, so the household predicate
    // is not what costs. A cold run discarded 4,072,036 partition rows by filter to return ~2000
    // (the exact count slides, since the window is relative to now). Pre-dates #2568; the fix is an
    // index migration, which `docs/process/migrations.md#migrations-back-compat` requires ship
    // schema-only.
    val bySince = f.since.fold(fr"")(s => fr"AND tr.period_end > $s")
    val byUntil = f.until.fold(fr"")(u => fr"AND tr.period_start < $u")
    // #2568: the tenancy predicate — the MAC list is NOT scoping. The same shared fragment the six
    // sibling `traffic_reports` reads compose (#2313). It resolves as a cheap semijoin filter over
    // `routers` (2 buffers on prod) and does not change the plan class of the outer scan.
    // `idx_routers_household` (V65) covers the subquery, though at prod's current `routers`
    // cardinality the planner prefers `routers_pkey`.
    val byHh    = SqlFragments.householdRouterScope(household, "tr.router_id")
    val sql_    = base ++ byHh ++ byMacs ++ byHost ++ bySince ++ byUntil ++
      fr"ORDER BY tr.router_id, tr.mac, tr.host_type, tr.host_value, tr.date, tr.period_start"
    sql_
      .query[Row]
      .map { r =>
        TrafficRollupRow(
          routerId = r._1,
          mac = r._2,
          host = r._3,
          date = r._4,
          periodStart = r._5,
          periodEnd = r._6,
          activeSeconds = r._7,
          bytesIn = r._8,
          bytesOut = r._9,
        )
      }
      .to[List]
      .transact(xa)
  }
}

class BlockEventRepoLive(xa: Transactor[Task]) extends BlockEventRepo {
  private type R = (BlockEventId, Option[MacAddress], HostId, BlockReason, String)
  private def toB(r: R)                           = BlockEvent(r._1, r._2, r._3, r._4, r._5)
  // #1176/#1179: dual-write `reason_text` (pre-V40 TEXT wire format) alongside
  // `reason` (post-V40 JSONB). Reads still come from `reason`; the parallel
  // column exists so a Render auto-rollback that lands on a post-V44 image
  // binding the column as TEXT can still serve.
  def insertBatch(events: List[BlockEventInsert]) =
    Update[(BlockEventInsert, String)](
      "INSERT INTO block_events(mac,host_type,host_value,reason,reason_text) " +
        "VALUES(?,?,?,?,?)",
    ).updateMany(events.map(e => (e, BlockReason.asWire(e.reason)))).transact(xa)
  def recent(limit: Int)                          =
    sql"SELECT id,mac,host_type,host_value,reason,ts::TEXT FROM block_events ORDER BY ts DESC LIMIT $limit"
      .query[R]
      .map(toB)
      .to[List]
      .transact(xa)
  def listForMac(mac: MacAddress, limit: Int)     =
    sql"SELECT id,mac,host_type,host_value,reason,ts::TEXT FROM block_events WHERE mac=$mac ORDER BY ts DESC LIMIT $limit"
      .query[R]
      .map(toB)
      .to[List]
      .transact(xa)
}

class ConnectionEventRepoLive(xa: Transactor[Task]) extends ConnectionEventRepo {
  private type R =
    (
        ConnectionEventId,
        RouterId,
        Option[MacAddress],
        HostId,
        Option[IpAddress],
        Boolean,
        BlockReason,
        String,
    )
  private def toC(r: R) =
    ConnectionEvent(r._1, r._2, r._3, r._4, r._5, r._6, r._7, r._8)

  // #338: idempotent insert. Client-supplied eventId is the dedup key for
  // retry-queue replays (#330). NULL → server-generated via gen_random_uuid()
  // (older agents pre-eventId support). ON CONFLICT DO NOTHING collapses
  // replays; updateMany returns count of rows actually inserted.
  // #720: resolved_host_value carries an API-side FQDN attribution for
  // ipv4/ipv6-typed events; ingest sets it from sibling fqdn events.
  // #1176/#1179: dual-write `reason_text` (pre-V40 TEXT wire format) alongside
  // `reason` (post-V40 JSONB). See BlockEventRepoLive for the why.
  def insertBatch(events: List[ConnectionEventInsert]) =
    DbMetrics.timed("connectionEvent.insertBatch")(
      Update[(ConnectionEventInsert, String)](
        "INSERT INTO connection_events(router_id,mac,host_type,host_value,dest_ip,allowed,reason,ts,event_id,resolved_host_value,reason_text) " +
          "VALUES(?,?,?,?,?,?,?,?,COALESCE(?, gen_random_uuid()),?,?) " +
          // #806: unique key widened to (event_id, ts) for ts-range partitioning;
          // a replay carries the same router-supplied ts so dedup is unchanged.
          "ON CONFLICT (event_id, ts) DO NOTHING",
      ).updateMany(events.map(e => (e, BlockReason.asWire(e.reason)))).transact(xa),
    )

  // #720: read paths coalesce a populated resolved_host_value into the host
  // tuple so callers see ('fqdn', resolved) instead of ('ipv4', literal-ip).
  // The raw columns remain untouched on disk; this is purely a render-time
  // promotion. Done in SQL so doobie's tuple decoder receives the already-
  // resolved values.
  private val selectCols: Fragment =
    fr"""id,
         router_id,
         mac,
         CASE WHEN resolved_host_value IS NOT NULL THEN 'fqdn' ELSE host_type END AS host_type,
         COALESCE(resolved_host_value, host_value) AS host_value,
         dest_ip,
         allowed,
         reason,
         ts::TEXT"""

  def recent(limit: Int) =
    (fr"SELECT" ++ selectCols ++ fr"FROM connection_events ORDER BY ts DESC LIMIT $limit")
      .query[R]
      .map(toC)
      .to[List]
      .transact(xa)

  def listForMac(mac: MacAddress, limit: Int) =
    (fr"SELECT" ++ selectCols ++ fr"FROM connection_events WHERE mac=$mac ORDER BY ts DESC LIMIT $limit")
      .query[R]
      .map(toC)
      .to[List]
      .transact(xa)

  def listForRouter(routerId: RouterId, limit: Int) =
    (fr"SELECT" ++ selectCols ++ fr"FROM connection_events WHERE router_id=$routerId ORDER BY ts DESC LIMIT $limit")
      .query[R]
      .map(toC)
      .to[List]
      .transact(xa)

  def findRecentFqdnFor(routerId: RouterId, destIp: IpAddress, since: Instant) =
    DbMetrics.timed("connectionEvent.findRecentFqdnFor")(
      sql"""SELECT host_value FROM connection_events
          WHERE router_id = $routerId
            AND dest_ip   = $destIp
            AND host_type = 'fqdn'
            AND ts >= $since
          ORDER BY ts DESC
          LIMIT 1"""
        .query[Hostname]
        .option
        .transact(xa),
    )

  def findRecentFqdnForBatch(routerId: RouterId, destIps: List[IpAddress], since: Instant) =
    if destIps.isEmpty then ZIO.succeed(Map.empty[IpAddress, Hostname])
    else {
      val arr = destIps.map(_.value).distinct.toArray
      DbMetrics.timed("connectionEvent.findRecentFqdnForBatch")(
        // DISTINCT ON returns the latest (per dest_ip) fqdn within the window in one round-trip.
        // Matches the per-row `findRecentFqdnFor` semantics exactly. (#1511)
        sql"""SELECT DISTINCT ON (dest_ip) dest_ip, host_value
            FROM connection_events
            WHERE router_id = $routerId
              AND host_type = 'fqdn'
              AND ts >= $since
              AND dest_ip = ANY($arr)
            ORDER BY dest_ip, ts DESC"""
          .query[(IpAddress, Hostname)]
          .to[List]
          .transact(xa)
          .map(_.toMap),
      )
    }

  def backfillResolvedFor(
      routerId: RouterId,
      destIp: IpAddress,
      fqdn: Hostname,
      since: Instant,
  ) =
    DbMetrics.timed("connectionEvent.backfillResolvedFor")(
      sql"""UPDATE connection_events
          SET resolved_host_value = ${fqdn.value}
          WHERE router_id = $routerId
            AND dest_ip   = $destIp
            AND host_type IN ('ipv4','ipv6')
            AND resolved_host_value IS NULL
            AND ts >= $since""".update.run.transact(xa),
    )

  // ── Dashboard / log API ──────────────────────────────────────────────────

  // #969: SSDP / UPnP discovery (239.255.255.250) and the rest of the IPv4
  // multicast range, IPv4 broadcast, and IPv6 multicast aren't real per-device
  // connections an operator can act on. Filter pre-aggregation in both /api/logs
  // and /series so counts stay correct. The router-side fix is deferred behind
  // Gate 2 (#654); until then, this read-side filter is the floor.
  //
  // host_type is a strict enum ('fqdn'|'ipv4'|'ipv6') so the regex/literal
  // checks only run on the relevant variant — fqdn rows short-circuit cheaply.
  // We filter on the raw ce.host_value (the actual destination on the wire),
  // not the resolved FQDN: a multicast IP that somehow got a resolved name is
  // still a multicast packet.
  private val multicastFilterSql: Fragment =
    fr"""AND NOT (
           (ce.host_type = 'ipv4' AND (
             ce.host_value ~ '^(22[4-9]|23[0-9])\.'
             OR ce.host_value = '255.255.255.255'
           ))
           OR (ce.host_type = 'ipv6' AND ce.host_value ~* '^ff')
         )"""

  def query(f: LogFilter) = {
    // location is sourced from routers.name until routers.location lands (#136)
    // #720: coalesce resolved_host_value into the returned host tuple so
    // race-loser ipv4 rows show up under their resolved FQDN in the log UI
    // and in domain ILIKE filters.
    val base   =
      fr"""SELECT ce.id, ce.mac, d.name, d.profile_id, p.name,
                  CASE WHEN ce.resolved_host_value IS NOT NULL THEN 'fqdn' ELSE ce.host_type END,
                  COALESCE(ce.resolved_host_value, ce.host_value),
                  1, NOT ce.allowed, ce.reason, r.name,
                  to_char(ce.ts AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
           FROM connection_events ce
           LEFT JOIN devices d  ON d.mac    = ce.mac
           LEFT JOIN profiles p ON p.id     = d.profile_id
           LEFT JOIN routers r  ON r.id     = ce.router_id
           WHERE 1=1"""
    // #862: window anchor moves from "now" to `until` (defaults to NOW()).
    val anchor = f.until.fold(fr"NOW()")(u => fr"$u::TIMESTAMPTZ")
    val window =
      fr"AND ce.ts > " ++ anchor ++ fr"- make_interval(hours => ${f.hours}) AND ce.ts <= " ++ anchor
    // #862: keyset cursor on (ts DESC, id DESC).
    val byCur  = (f.cursorTs, f.cursorId) match {
      case (Some(ts), Some(id)) => fr"AND (ce.ts, ce.id) < ($ts, $id)"
      case _                    => fr""
    }
    // #865: multi-valued mac/deviceId/profileId via IN (...).
    val byMac  = cats.data.NonEmptyList
      .fromList(f.macs)
      .fold(fr"")(nel => fr"AND " ++ Fragments.in(fr"ce.mac", nel))
    val byDev  = cats.data.NonEmptyList
      .fromList(f.deviceIds)
      .fold(fr"")(nel => fr"AND " ++ Fragments.in(fr"d.id", nel))
    val byPid  = cats.data.NonEmptyList
      .fromList(f.profileIds)
      .fold(fr"")(nel => fr"AND " ++ Fragments.in(fr"d.profile_id", nel))
    val byBl   = f.blocked.fold(fr"")(b => fr"AND ce.allowed = ${!b}")
    val byDom  = f.domain.fold(fr"")(d =>
      // #720: domain filter has to look through the resolution too — otherwise
      // a search for "youtube.com" would miss race-loser ipv4 rows we just
      // attributed.
      fr"AND COALESCE(ce.resolved_host_value, ce.host_value) ILIKE ${s"%$d%"}",
    )
    val byLoc  = f.location.fold(fr"")(l => fr"AND r.name = $l")
    val byMc   = if (f.includeMulticast) fr"" else multicastFilterSql
    // #2108: scope to the caller's household via the already-joined `routers r` (connection_events
    // are router_id-keyed → household transitive). Index-backed by idx_routers_household (V65).
    // #2314: shares the one household predicate with querySeries/querySeriesRollup (SSOT).
    val byHh   = SqlFragments.householdFilter(f.household, "r.household_id")
    (base ++ window ++ byCur ++ byMac ++ byDev ++ byPid ++ byBl ++ byDom ++ byLoc ++ byMc ++ byHh ++
      fr"ORDER BY ce.ts DESC, ce.id DESC LIMIT ${f.limit}")
      .query[
        (
            QueryLogId,
            Option[MacAddress],
            Option[String],
            Option[ProfileId],
            Option[String],
            HostId,
            Int,
            Boolean,
            BlockReason,
            Option[String],
            String,
        ),
      ]
      .map(QueryLog.apply)
      .to[List]
      .transact(xa)
  }

  // #847 + #846: multi-column aggregation over connection_events. Buckets are
  // computed in UTC via date_bin — UI re-renders boundaries in household-local
  // tz for display. No rollup table exists yet (#809 in flight); for now even
  // wide buckets compute on-the-fly from connection_events.
  //
  // #846 audit: emit ISO 8601 with T separator + trailing Z so JS Date.parse
  // accepts these without a per-field workaround. Postgres TIMESTAMPTZ::TEXT
  // defaults to "2026-05-21 22:00:00+00" (space separator) which `new Date(...)`
  // rejects as Invalid Date.
  def querySeries(f: LogFilter, bucketSeconds: Int, groupBy: Set[String]) = {
    val domainExpr = fr"COALESCE(ce.resolved_host_value, ce.host_value)"
    val deviceExpr = fr"COALESCE(d.name, ce.mac::TEXT)"
    // Raw path bins on the per-event timestamp.
    val tsBin      = fr"ce.ts"

    val fromJoins = fr"""FROM connection_events ce
           LEFT JOIN devices d  ON d.mac = ce.mac
           LEFT JOIN profiles p ON p.id  = d.profile_id
           LEFT JOIN routers r  ON r.id  = ce.router_id"""
    // #862: window anchor moves from "now" to `until` (defaults to NOW()).
    val anchor    = f.until.fold(fr"NOW()")(u => fr"$u::TIMESTAMPTZ")
    val window    =
      fr"AND ce.ts > " ++ anchor ++ fr"- make_interval(hours => ${f.hours}) AND ce.ts <= " ++ anchor
    val byMac     = cats.data.NonEmptyList
      .fromList(f.macs)
      .fold(fr"")(nel => fr"AND " ++ Fragments.in(fr"ce.mac", nel))
    val byDev     = cats.data.NonEmptyList
      .fromList(f.deviceIds)
      .fold(fr"")(nel => fr"AND " ++ Fragments.in(fr"d.id", nel))
    val byPid     = cats.data.NonEmptyList
      .fromList(f.profileIds)
      .fold(fr"")(nel => fr"AND " ++ Fragments.in(fr"d.profile_id", nel))
    val byBl      = f.blocked.fold(fr"")(b => fr"AND ce.allowed = ${!b}")
    val byDom     = f.domain.fold(fr"")(d =>
      fr"AND COALESCE(ce.resolved_host_value, ce.host_value) ILIKE ${s"%$d%"}",
    )
    val byLoc     = f.location.fold(fr"")(l => fr"AND r.name = $l")
    val byMc      = if (f.includeMulticast) fr"" else multicastFilterSql
    // #2314: scope to the caller's household via the already-joined `routers r` (connection_events
    // are router_id-keyed → household transitive), mirroring `query(LogFilter)`. Without this a
    // shared MAC aggregates + mislabels across households. Index-backed by idx_routers_household (V65).
    val byHh      = SqlFragments.householdFilter(f.household, "r.household_id")
    val filters   = window ++ byMac ++ byDev ++ byPid ++ byBl ++ byDom ++ byLoc ++ byMc ++ byHh

    val succProj     = fr"COUNT(*) FILTER (WHERE ce.allowed)::INT"
    val blkProj      = fr"COUNT(*) FILTER (WHERE NOT ce.allowed)::INT"
    val lastSeenExpr =
      fr"""to_char(MAX(ce.ts) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')"""

    runSeries(
      f,
      bucketSeconds,
      groupBy,
      fromJoins,
      filters,
      domainExpr,
      deviceExpr,
      tsBin,
      succProj,
      blkProj,
      lastSeenExpr,
    )
  }

  // #1265: rollup-backed `/series`. Reads pre-aggregated counts from the hourly
  // or daily rollup (per `grain`) and re-bins them up to `bucketSeconds`. Shares
  // all of runSeries' projection/cursor/decode machinery with the raw path; only
  // the source-specific fragments differ:
  //   - source is the rollup table aliased `cer`, bin on its bucket boundary;
  //   - counts are SUMmed from the stored split columns (no per-event `allowed`);
  //   - the `blocked` filter narrows to the matching count column and zeroes the
  //     opposite projection so a mixed (mac,host,bucket) row contributes one side;
  //   - no multicast filter — the reroll already excluded those at write time, so
  //     this path is only chosen when includeMulticast is false (route enforces).
  def querySeriesRollup(
      f: LogFilter,
      bucketSeconds: Int,
      groupBy: Set[String],
      grain: BucketGrain,
  ) = {
    val isDaily    = grain == BucketGrain.Daily
    val table      =
      if (isDaily) fr"connection_events_daily cer" else fr"connection_events_hourly cer"
    // Bin on the stored bucket boundary (timestamptz). For daily, lift the DATE
    // to a UTC midnight timestamptz so date_bin sees the same type as the hourly
    // bucket_start and the raw ce.ts path.
    val tsBin      =
      if (isDaily) fr"(cer.date::timestamp AT TIME ZONE 'UTC')" else fr"cer.bucket_start"
    val domainExpr = fr"cer.hostname"
    // Rollup folds NULL mac to ''; lift it back to NULL so the device name
    // COALESCE matches the raw path's `COALESCE(d.name, ce.mac::TEXT)`.
    val deviceExpr = fr"COALESCE(d.name, NULLIF(cer.mac, ''))"

    val fromJoins = fr"FROM " ++ table ++ fr"""
           LEFT JOIN devices d  ON d.mac = cer.mac
           LEFT JOIN profiles p ON p.id  = d.profile_id
           LEFT JOIN routers r  ON r.id  = cer.router_id"""
    val anchor    = f.until.fold(fr"NOW()")(u => fr"$u::TIMESTAMPTZ")
    val window    =
      fr"AND " ++ tsBin ++ fr"> " ++ anchor ++ fr"- make_interval(hours => ${f.hours}) AND " ++
        tsBin ++ fr"<= " ++ anchor
    val byMac     = cats.data.NonEmptyList
      .fromList(f.macs)
      .fold(fr"")(nel => fr"AND " ++ Fragments.in(fr"cer.mac", nel))
    val byDev     = cats.data.NonEmptyList
      .fromList(f.deviceIds)
      .fold(fr"")(nel => fr"AND " ++ Fragments.in(fr"d.id", nel))
    val byPid     = cats.data.NonEmptyList
      .fromList(f.profileIds)
      .fold(fr"")(nel => fr"AND " ++ Fragments.in(fr"d.profile_id", nel))
    // Rollup has split counts, not a per-event `allowed` flag: narrow to the
    // matching count column rather than filtering individual events.
    val byBl      = f.blocked.fold(fr"")(b =>
      if (b) fr"AND cer.count_blocked > 0" else fr"AND cer.count_succeeded > 0",
    )
    val byDom     = f.domain.fold(fr"")(d => fr"AND cer.hostname ILIKE ${s"%$d%"}")
    val byLoc     = f.location.fold(fr"")(l => fr"AND r.name = $l")
    // #2314: same household scope as the raw path — the rollup carries `cer.router_id`, joined to
    // `routers r`, so tenancy is transitive. Shared predicate lives in SqlFragments.householdFilter.
    val byHh      = SqlFragments.householdFilter(f.household, "r.household_id")
    // No multicast filter: excluded at reroll write time.
    val filters   = window ++ byMac ++ byDev ++ byPid ++ byBl ++ byDom ++ byLoc ++ byHh

    // Sum the stored split counts. When the caller narrows to blocked/allowed,
    // zero the opposite projection so a row mixing both contributes only the
    // matching side (mirrors the raw path's FILTER collapsing to one side).
    val succProj     =
      if (f.blocked.contains(true)) fr"0::INT" else fr"COALESCE(SUM(cer.count_succeeded), 0)::INT"
    val blkProj      =
      if (f.blocked.contains(false)) fr"0::INT" else fr"COALESCE(SUM(cer.count_blocked), 0)::INT"
    // lastSeen is bucket-granular (the rollup stores no per-event ts): bin
    // MAX(bucket) to the requested window so it equals window_start.
    val bucketIv     = Fragment.const(s"make_interval(secs => $bucketSeconds)")
    val lastSeenExpr =
      fr"""to_char(date_bin($bucketIv, MAX(""" ++ tsBin ++ fr"""), TIMESTAMP '2000-01-01 00:00:00')
                          AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')"""

    runSeries(
      f,
      bucketSeconds,
      groupBy,
      fromJoins,
      filters,
      domainExpr,
      deviceExpr,
      tsBin,
      succProj,
      blkProj,
      lastSeenExpr,
    )
  }

  // #847 + #846 + #1265: shared aggregation engine for both the raw and
  // rollup-backed `/series` reads. Callers supply the source-specific fragments
  // (FROM/joins, row filters, the domain/device group expressions, the
  // timestamp to bin on, the succeeded/blocked count projections, and the
  // last_seen projection); everything else — app resolution, multi-group
  // composition, keyset cursor, ORDER BY, and the result decode — is identical.
  //
  // Buckets are computed in UTC via date_bin — the UI re-renders boundaries in
  // household-local tz for display. #846: emit ISO 8601 with a T separator and
  // trailing Z so JS Date.parse accepts these without a per-field workaround.
  private def runSeries(
      f: LogFilter,
      bucketSeconds: Int,
      groupBy: Set[String],
      fromJoins: Fragment,
      filters: Fragment,
      domainExpr: Fragment,
      deviceExpr: Fragment,
      tsBin: Fragment,
      succProj: Fragment,
      blkProj: Fragment,
      lastSeenExpr: Fragment,
  ): Task[List[ConnectionEventAggRow]] = {
    // #862: inline `bucketSeconds` as a SQL literal (it's a server-controlled
    // enum value, not user input) so the SELECT and GROUP BY copies of the
    // date_bin expression are byte-identical. Postgres matches the SELECT
    // expression against GROUP BY by parse tree; if doobie assigns different
    // parameter positions to the two copies, the match fails and PG raises
    // "must appear in GROUP BY".
    val bucketIv    = Fragment.const(s"make_interval(secs => $bucketSeconds)")
    val profileExpr = fr"COALESCE(p.name, '(unassigned)')"

    // #917: strictly additive — empty set = no drill, one row per window.
    // Multi-group composes freely across {domain, device, profile, app}.
    val wantsDomain  = groupBy.contains("domain")
    val wantsDevice  = groupBy.contains("device")
    val wantsProfile = groupBy.contains("profile")
    val wantsApp     = groupBy.contains("app")

    // #862: the window bucket uses the full date_bin/to_char expression (not
    // the SELECT alias) so the HAVING clause for the keyset cursor below can
    // reference the same expression — PG doesn't recognize SELECT aliases in
    // HAVING.
    val winExpr =
      fr"""to_char(date_bin($bucketIv, """ ++ tsBin ++ fr""", TIMESTAMP '2000-01-01 00:00:00')
                          AT TIME ZONE 'UTC',
                          'YYYY-MM-DD"T"HH24:MI:SS"Z"')"""

    // #857: resolve each in-window host to its app(s) in Scala via
    // HostMatch.lookupApex — the single host→apex matcher shared with the
    // traffic-usage aggregator (#1085) and PolicyService. We deliberately do
    // NOT match in SQL: app_hosts stores apex-form hosts ("youtube.com"), but
    // connection_events carry FQDNs ("www.youtube.com"), so an exact SQL join
    // would drop subdomains into single-host apps. Instead we fetch the apex→app
    // inventory + the distinct in-window hosts, run lookupApex per host, and
    // feed the resolved concrete (fqdn → app) pairs back into the aggregation
    // as a VALUES join. A host in N apps yields N pairs (fan-out preserved).
    // #1091 can later swap this read to a denormalized app_id column without
    // changing semantics, since lookupApex stays the source of truth.
    val resolveAppPairs: ConnectionIO[List[(String, (String, String, Option[String], Long))]] =
      if (!wantsApp)
        List.empty[(String, (String, String, Option[String], Long))].pure[ConnectionIO]
      else
        for {
          memberships <-
            fr"""SELECT ah.host, a.slug, a.name, a.icon, a.id
                 FROM app_hosts ah JOIN apps a ON a.id = ah.app_id"""
              .query[(String, String, String, Option[String], Long)]
              .to[List]
          hosts       <-
            (fr"SELECT DISTINCT " ++ domainExpr ++ fr" " ++ fromJoins ++ fr"WHERE 1=1" ++ filters)
              .query[Option[String]]
              .to[List]
        } yield {
          val byApex: Map[String, List[(String, String, Option[String], Long)]] =
            memberships.groupMap(_._1)(m => (m._2, m._3, m._4, m._5))
          hosts.flatten.distinct.flatMap { h =>
            HostMatch.lookupApex(h, byApex).getOrElse(Nil).map(row => (h, row))
          }
        }

    resolveAppPairs
      .flatMap { appPairs =>
        val hasAppMap = wantsApp && appPairs.nonEmpty

        // App expressions. With resolved pairs we COALESCE off the VALUES alias
        // `am`; #1526: when no app row matches, the host IS its own single-host
        // app — fall back to the domain itself for both slug and display name
        // (NOT a shared "__other__" bucket). Un-drilled path keeps NULLs so the
        // constant shape is preserved.
        val appSlugExpr =
          if (hasAppMap) fr"COALESCE(am.slug, " ++ domainExpr ++ fr")"
          else if (wantsApp) domainExpr
          else fr"NULL::TEXT"
        val appNameExpr =
          if (hasAppMap) fr"COALESCE(am.name, " ++ domainExpr ++ fr")"
          else if (wantsApp) domainExpr
          else fr"NULL::TEXT"
        val appIconExpr = if (hasAppMap) fr"am.icon" else fr"NULL::TEXT"
        val appIdExpr   = if (hasAppMap) fr"am.app_id" else fr"NULL::BIGINT"

        // Always SELECT all group expressions (NULL when not requested) so
        // the result tuple has a constant shape and doobie can decode it.
        val selDomain  = if (wantsDomain) domainExpr else fr"NULL::TEXT"
        val selDevice  = if (wantsDevice) deviceExpr else fr"NULL::TEXT"
        val selProfile = if (wantsProfile) profileExpr else fr"NULL::TEXT"
        val selAppSlug = appSlugExpr
        val selAppName = appNameExpr
        val selAppIcon = appIconExpr
        val selAppId   = appIdExpr

        // GROUP BY only the requested columns, plus the window bucket. App needs
        // (slug, name, icon, id) so the GROUP BY can carry through metadata; in
        // practice (slug) alone uniquely identifies the row but name/icon/id are
        // functionally dependent so adding them is safe and avoids MAX() casts.
        // Gated on hasAppMap: when grouping by app with no matches the app
        // columns are constants, which we leave out of GROUP BY (a constant
        // SELECT is fine ungrouped) so PG never sees a constant in GROUP BY.
        val groupByParts = List(
          Some(winExpr),
          Option.when(wantsDomain)(domainExpr),
          Option.when(wantsDevice)(deviceExpr),
          Option.when(wantsProfile)(profileExpr),
          Option.when(hasAppMap)(appSlugExpr),
          Option.when(hasAppMap)(appNameExpr),
          Option.when(hasAppMap)(appIconExpr),
          Option.when(hasAppMap)(appIdExpr),
        ).flatten
        val groupByCols  = groupByParts.reduce(_ ++ fr"," ++ _)

        // #862: stable lexicographic group key for keyset paging. Concatenates the
        // SELECTed group values (or empty string when not requested) with a
        // delimiter unlikely to collide with hostnames / device names.
        val groupKeyParts = List(
          Option.when(wantsDomain)(fr"COALESCE(" ++ domainExpr ++ fr", '')"),
          Option.when(wantsDevice)(fr"COALESCE(" ++ deviceExpr ++ fr", '')"),
          Option.when(wantsProfile)(fr"COALESCE(" ++ profileExpr ++ fr", '')"),
        ).flatten
        val groupKeyExpr  = groupKeyParts match {
          case Nil   => fr"''"
          case parts =>
            // #1532: separator byte sourced from `LogAggGroupKey` so the SQL
            // `chr(N) ||` concat and the Scala-side `Routes.aggGroupKey` cannot
            // drift; both join with the same ASCII unit-separator.
            val sepFrag = Fragment.const(s"|| chr(${LogAggGroupKey.SeparatorCode}) ||")
            parts.reduce((a, b) => a ++ sepFrag ++ b)
        }

        // #857: resolved (fqdn → app) pairs as a VALUES table. Explicit casts on
        // every row so PG never has to infer a column type from an all-NULL icon.
        val appJoin = if (hasAppMap) {
          val values = appPairs
            .map { case (h, (slug, name, icon, id)) =>
              fr"($h::TEXT, $slug::TEXT, $name::TEXT, $icon::TEXT, $id::BIGINT)"
            }
            .reduce((a, b) => a ++ fr"," ++ b)
          fr"LEFT JOIN (VALUES" ++ values ++ fr") AS am(host, slug, name, icon, app_id) ON am.host = " ++
            domainExpr
        } else fr""

        val base    =
          fr"""SELECT """ ++ selDomain ++ fr"AS grp_domain," ++
            selDevice ++ fr"AS grp_device," ++
            selProfile ++ fr"AS grp_profile," ++
            selAppSlug ++ fr"AS grp_app_slug," ++
            selAppName ++ fr"AS grp_app_name," ++
            selAppIcon ++ fr"AS grp_app_icon," ++
            selAppId ++ fr"AS grp_app_id," ++
            winExpr ++ fr"AS window_start," ++
            succProj ++ fr"AS count_succeeded," ++
            blkProj ++ fr"AS count_blocked," ++
            lastSeenExpr ++ fr"""AS last_seen,
                  mode() WITHIN GROUP (ORDER BY """ ++ deviceExpr ++ fr""") AS top_device,
                  COUNT(DISTINCT """ ++ deviceExpr ++ fr""")::INT          AS distinct_devices,
                  COUNT(DISTINCT """ ++ profileExpr ++ fr""")::INT         AS distinct_profiles,
                  COUNT(DISTINCT """ ++ domainExpr ++ fr""")::INT          AS distinct_domains,
                  COUNT(DISTINCT """ ++ appSlugExpr ++ fr""")::INT         AS distinct_apps,
                  CASE WHEN COUNT(DISTINCT """ ++ deviceExpr ++ fr""") = 1
                       THEN MAX(""" ++ deviceExpr ++ fr""") END            AS sole_device,
                  CASE WHEN COUNT(DISTINCT """ ++ profileExpr ++ fr""") = 1
                       THEN MAX(""" ++ profileExpr ++ fr""") END           AS sole_profile,
                  CASE WHEN COUNT(DISTINCT """ ++ domainExpr ++ fr""") = 1
                       THEN MAX(""" ++ domainExpr ++ fr""") END            AS sole_domain,
                  CASE WHEN COUNT(DISTINCT """ ++ appSlugExpr ++ fr""") = 1
                       THEN MAX(""" ++ appNameExpr ++ fr""") END           AS sole_app
           """ ++ fromJoins ++ appJoin ++ fr"""WHERE 1=1"""
        // #862: keyset cursor on (window_start DESC, group_key ASC). HAVING uses
        // the full window/groupkey expressions (PG doesn't allow SELECT aliases
        // in HAVING).
        // Tuple lex `(a,b) < (x,y)` only matches when both columns sort the same
        // direction. Our order is (window_start DESC, group_key ASC), so we
        // split into an OR: strictly-older window, OR same window with a
        // strictly-greater key.
        val having  = (f.cursorWs, f.cursorKey) match {
          case (Some(ws), Some(k)) =>
            fr"HAVING " ++ winExpr ++ fr"< $ws OR (" ++ winExpr ++ fr"= $ws AND " ++
              groupKeyExpr ++ fr"> $k)"
          case _                   => fr""
        }
        // #862: stable secondary order so keyset cursor is deterministic. Was
        // COUNT(*) DESC which is unstable under inserts. When groupBy is empty
        // (#917 one-row-per-window mode), there's no secondary key and PG rejects
        // a literal '' in ORDER BY ("non-integer constant in ORDER BY"), so we
        // drop the second clause.
        val orderBy =
          if (groupKeyParts.isEmpty) fr"ORDER BY window_start DESC"
          else fr"ORDER BY window_start DESC, " ++ groupKeyExpr ++ fr" ASC"
        (base ++ filters ++
          fr"GROUP BY " ++ groupByCols ++ fr" " ++ having ++
          orderBy ++ fr"LIMIT ${f.limit}")
          .query[
            (
                Option[String], // grp_domain
                Option[String], // grp_device
                Option[String], // grp_profile
                Option[String], // grp_app_slug
                Option[String], // grp_app_name
                Option[String], // grp_app_icon
                Option[Long],   // grp_app_id
                String,         // window_start
                Int,            // count_succeeded
                Int,            // count_blocked
                String,         // last_seen
                Option[String], // top_device
                Int,            // distinct_devices
                Int,            // distinct_profiles
                Int,            // distinct_domains
                Int,            // distinct_apps
                Option[String], // sole_device   (null when distinct != 1)
                Option[String], // sole_profile
                Option[String], // sole_domain
                Option[String], // sole_app
            ),
          ]
          .map {
            case (
                  gd,
                  gv,
                  gp,
                  gas,
                  gan,
                  gai,
                  gid,
                  ws,
                  sc,
                  bl,
                  ls,
                  td,
                  dd,
                  dpr,
                  dm,
                  dap,
                  sde,
                  spr,
                  sdo,
                  sap,
                ) =>
              val groupMap = scala.collection.mutable.LinkedHashMap.empty[String, String]
              gd.foreach(v => groupMap += ("domain" -> v))
              gv.foreach(v => groupMap += ("device" -> v))
              gp.foreach(v => groupMap += ("profile" -> v))
              gas.foreach(v => groupMap += ("app" -> v))
              // Only surface sole* when the column is NOT in groupBy — when it IS,
              // the value is already in `groups`.
              ConnectionEventAggRow(
                groups = groupMap.toMap,
                windowStart = ws,
                countSucceeded = sc,
                countBlocked = bl,
                lastSeen = ls,
                topDevice = td,
                distinctDevices = dd,
                distinctProfiles = dpr,
                distinctDomains = dm,
                distinctApps = dap,
                soleDevice = if (wantsDevice) None else sde,
                soleProfile = if (wantsProfile) None else spr,
                soleDomain = if (wantsDomain) None else sdo,
                soleApp = if (wantsApp) None else sap,
                // appId is the BIGINT primary key for the apps table; #1526:
                // host-keyed single-host apps (unmatched hosts) have no row in
                // apps so app_id is NULL on the wire.
                appId = if (wantsApp) gid.map(AppId(_)) else None,
                appName = if (wantsApp) gan else None,
                appIcon = if (wantsApp) gai else None,
              )
          }
          .to[List]
      }
      .transact(xa)
  }

  // ── Connection-event rollups (#1265) ───────────────────────────────────────
  //
  // The event-count analogue of RollupRepo's traffic rollups. Unlike traffic,
  // there is NO app side table and NO resolve LATERAL: connection_events already
  // carries resolved_host_value, and app/device/profile attribution is a
  // read-time join. So the reroll is a single GROUP BY with split allowed/blocked
  // FILTER counts, matching exactly what querySeries projects.
  //
  // The trailing-window source scan is bounded by idx_conn_events_ts (and weekly
  // partition pruning), so a tick never seq-scans the unbounded base table.

  // Run the upsert inside a tx guarded by a transaction-scoped advisory lock; if
  // another instance holds it, commit immediately with None. The lock
  // auto-releases on commit/rollback — no manual unlock to leak. Mirrors
  // RollupRepoLive.withLock.
  private def withRollupLock(key: Long)(upsert: doobie.ConnectionIO[Int]): Task[Option[Int]] = {
    val tx = for {
      got <- sql"SELECT pg_try_advisory_xact_lock($key)".query[Boolean].unique
      n   <- if (got) upsert.map(Option(_)) else doobie.free.connection.pure(Option.empty[Int])
    } yield n
    tx.transact(xa)
  }

  // date_bin anchor matches querySeries' '2000-01-01 00:00:00' origin so rolled
  // hourly buckets line up exactly with what the on-the-fly read computes.
  def rerollConnEventsHourly(since: Instant): Task[Option[Int]] = {
    val truncSince = since.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
    // #1265 (PR3): exclude multicast/broadcast at write time so the rollup
    // matches /series's DEFAULT (includeMulticast=false) view. The rollup drops
    // host_type/host_value, so the read path can't re-filter — the only place to
    // apply the #969 exclusion is here, against the raw columns. A read that
    // wants multicast (includeMulticast=true) falls back to raw.
    val head       =
      fr"""INSERT INTO connection_events_hourly
          (router_id, mac, hostname, bucket_start, count_succeeded, count_blocked, sample_count, rolled_at)
        SELECT
          ce.router_id,
          COALESCE(ce.mac, ''),
          COALESCE(ce.resolved_host_value, ce.host_value),
          date_bin(INTERVAL '1 hour', ce.ts, TIMESTAMP '2000-01-01 00:00:00'),
          COUNT(*) FILTER (WHERE ce.allowed)::INT,
          COUNT(*) FILTER (WHERE NOT ce.allowed)::INT,
          COUNT(*)::INT,
          NOW()
        FROM connection_events ce
        WHERE ce.ts >= $truncSince"""
    val tail       =
      fr"""GROUP BY ce.router_id, COALESCE(ce.mac, ''),
                 COALESCE(ce.resolved_host_value, ce.host_value),
                 date_bin(INTERVAL '1 hour', ce.ts, TIMESTAMP '2000-01-01 00:00:00')
        ON CONFLICT (router_id, mac, hostname, bucket_start) DO UPDATE SET
          count_succeeded = EXCLUDED.count_succeeded,
          count_blocked   = EXCLUDED.count_blocked,
          sample_count    = EXCLUDED.sample_count,
          rolled_at       = EXCLUDED.rolled_at"""
    val q          = head ++ fr" " ++ multicastFilterSql ++ fr" " ++ tail
    withRollupLock(RollupLockKeys.ConnEventsHourly)(q.update.run)
  }

  def rerollConnEventsDaily(sinceDate: LocalDate): Task[Option[Int]] = {
    // Filter on raw `ts` (not a function of ts) so the source scan stays on
    // idx_conn_events_ts + partition pruning — wrapping ts in
    // `(ts AT TIME ZONE 'UTC')::DATE` in the predicate would force a full-table
    // scan (#1254 class). The lower bound is sinceDate's UTC midnight; any event
    // whose UTC date is >= sinceDate has ts >= that instant.
    val sinceTs = sinceDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant
    // #1265 (PR3): same multicast exclusion as the hourly reroll — see note there.
    val head    =
      fr"""INSERT INTO connection_events_daily
          (router_id, mac, hostname, date, count_succeeded, count_blocked, sample_count, rolled_at)
        SELECT
          ce.router_id,
          COALESCE(ce.mac, ''),
          COALESCE(ce.resolved_host_value, ce.host_value),
          (ce.ts AT TIME ZONE 'UTC')::DATE,
          COUNT(*) FILTER (WHERE ce.allowed)::INT,
          COUNT(*) FILTER (WHERE NOT ce.allowed)::INT,
          COUNT(*)::INT,
          NOW()
        FROM connection_events ce
        WHERE ce.ts >= $sinceTs"""
    val tail    =
      fr"""GROUP BY ce.router_id, COALESCE(ce.mac, ''),
                 COALESCE(ce.resolved_host_value, ce.host_value),
                 (ce.ts AT TIME ZONE 'UTC')::DATE
        ON CONFLICT (router_id, mac, hostname, date) DO UPDATE SET
          count_succeeded = EXCLUDED.count_succeeded,
          count_blocked   = EXCLUDED.count_blocked,
          sample_count    = EXCLUDED.sample_count,
          rolled_at       = EXCLUDED.rolled_at"""
    val q       = head ++ fr" " ++ multicastFilterSql ++ fr" " ++ tail
    withRollupLock(RollupLockKeys.ConnEventsDaily)(q.update.run)
  }

  // #1837: the 24h aggregations (totalToday / blockedToday / topBlocked) read the
  // pre-aggregated `connection_events_hourly` rollup (#1265 / #809) instead of a raw
  // 24h scan of the unbounded, weekly-partitioned `connection_events` table — the
  // #1098 perceived-latency fix. The window is hour-bucket-granular (24 buckets via
  // the idx_ce_hourly_bucket_start range scan); the dashboard 24h panel is
  // explicitly request/response, not realtime (design dashboard-redesign.md §8.2),
  // so the up-to-one-hour tail lag of the hourly reroll (RollupJobs.HourlyInterval)
  // is acceptable. Two behavioural notes vs the old raw scan, both intentional:
  //   - the rollup excludes multicast/broadcast at write time (#1265 PR3), so these
  //     totals omit multicast the raw scan counted — arguably more honest for a
  //     "connection events" number, and equal to raw on a multicast-free window;
  //   - the rollup stores only `hostname` (COALESCE(resolved_host_value, host_value)),
  //     dropping the host_type discriminator, so topBlocked re-infers the HostId kind
  //     from the string (hostIdFromRollup).
  // The 1h tiles (totalHour / blockedHour) and the "Most Recently Blocked" recency
  // feed legitimately need the live raw tail and stay on `connection_events`.
  // perDevice was dropped from the dashboard in #1836 (per-device volume belongs on
  // /devices + /profiles), so its raw 24h scan is removed entirely, not re-pointed.
  // #2282: household scoping is transitive via `routers` — both `connection_events` (raw 1h tiles)
  // and `connection_events_hourly` (24h totals + top) are router_id-keyed. A null-safe `router_id IN
  // (SELECT id FROM routers WHERE household_id=$hh)` subquery (NOT an INNER JOIN) carves the
  // household's rows out; a router with no events contributes nothing, and for the single backfill
  // household this returns exactly the pre-scoping global counts. Index-backed by V65's
  // idx_routers_household.
  private def hhRouterScope(household: HouseholdId): Fragment =
    fr"AND router_id IN (SELECT id FROM routers WHERE" ++ SqlFragments.householdEq(
      household,
    ) ++ fr")"

  def stats(household: HouseholdId) = {
    val hh = hhRouterScope(household)
    for {
      tt  <- DbMetrics.timed("stats.total24h")(
        (fr"""SELECT COALESCE(SUM(count_succeeded + count_blocked), 0)::INT
              FROM connection_events_hourly
              WHERE bucket_start > NOW() - INTERVAL '24 hours'""" ++ hh)
          .query[Int]
          .unique
          .transact(xa),
      )
      bt  <- DbMetrics.timed("stats.blocked24h")(
        (fr"""SELECT COALESCE(SUM(count_blocked), 0)::INT
              FROM connection_events_hourly
              WHERE bucket_start > NOW() - INTERVAL '24 hours'""" ++ hh)
          .query[Int]
          .unique
          .transact(xa),
      )
      th  <-
        (fr"SELECT COUNT(*)::INT FROM connection_events WHERE ts > NOW()-INTERVAL '1 hour'" ++ hh)
          .query[Int]
          .unique
          .transact(xa)
      bh  <-
        (fr"SELECT COUNT(*)::INT FROM connection_events WHERE ts > NOW()-INTERVAL '1 hour' AND NOT allowed" ++ hh)
          .query[Int]
          .unique
          .transact(xa)
      top <- DbMetrics.timed("stats.topBlocked24h")(
        (fr"""SELECT hostname, SUM(count_blocked)::INT AS c
              FROM connection_events_hourly
              WHERE bucket_start > NOW() - INTERVAL '24 hours'""" ++ hh ++
          fr"""GROUP BY hostname HAVING SUM(count_blocked) > 0
              ORDER BY c DESC LIMIT 10""")
          .query[(String, Int)]
          .map { case (h, c) => DomainCount(hostIdFromRollup(h), c) }
          .to[List]
          .transact(xa),
      )
    } yield DashboardStats(tt, bt, th, bh, top)
  }

  // #1837: reconstruct a HostId from the rollup's bare `hostname` string. The
  // hourly/daily rollups drop host_type, so re-infer it: an IP literal → IPv4/IPv6,
  // a parseable hostname → Fqdn, otherwise a synthetic Label (the rare static-ip-
  // range attribution, #1708, which is never pattern-matched). IP is tried first
  // because some IP literals also parse as bare hostname labels.
  private def hostIdFromRollup(s: String): HostId =
    IpAddress
      .parse(s)
      .toOption
      .map(HostId.ip)
      .orElse(Hostname.parse(s).toOption.map(HostId.fqdn))
      .getOrElse(HostId.Label(s))

  def topBlocked(hours: Int, lim: Int) =
    sql"""SELECT CASE WHEN resolved_host_value IS NOT NULL THEN 'fqdn' ELSE host_type END,
                 COALESCE(resolved_host_value, host_value),
                 COUNT(*)::INT
          FROM connection_events
          WHERE NOT allowed AND ts > NOW() - make_interval(hours => $hours)
          GROUP BY 1, 2 ORDER BY COUNT(*) DESC LIMIT $lim"""
      .query[(HostId, Int)]
      .map(DomainCount.apply)
      .to[List]
      .transact(xa)

  def lastSeenByMacSince(
      since: Instant,
      household: Option[HouseholdId] = None,
  ): Task[Map[MacAddress, Instant]] = {
    // #2251: household scoping is transitive via `routers` (connection_events are router_id-keyed).
    // The join is added ONLY when a household is requested so the unscoped path keeps its original
    // single-table plan. Index-backed by idx_routers_household (V65).
    val base  = fr"SELECT ce.mac, MAX(ce.ts) FROM connection_events ce"
    val join  = household.fold(fr"")(_ => fr"JOIN routers r ON r.id = ce.router_id")
    val where = fr"WHERE ce.mac IS NOT NULL AND ce.ts > $since"
    val byHh  =
      household.fold(fr"")(hh => fr"AND" ++ SqlFragments.householdEq(hh, "r.household_id"))
    (base ++ join ++ where ++ byHh ++ fr"GROUP BY ce.mac")
      .query[(MacAddress, Instant)]
      .to[List]
      .transact(xa)
      .map(_.toMap)
  }
}

// ── #761: apps ────────────────────────────────────────────────────────────
//
// CRUD over the apps / app_hosts / app_policy_assignments tables. No business
// logic here: slug derivation, host canonicalization, template seeding, and
// snapshot expansion all live in #762 / #763. Repo just round-trips rows.

trait AppRepo {
  def listAll: Task[List[App]]
  def findById(id: AppId): Task[Option[App]]
  def findBySlug(slug: String): Task[Option[App]]
  def findByTemplateId(templateId: AppTemplateId): Task[Option[App]]
  def create(
      name: String,
      slug: String,
      templateId: Option[AppTemplateId],
      icon: Option[String],
      iconType: IconType = IconType.Emoji,
  ): Task[AppId]
  def update(a: App): Task[Unit]
  def delete(id: AppId): Task[Unit]

  /**
   * #1777: merge app `from` INTO app `to` in one transaction — reattaches every FK reference
   * (`app_hosts`, `app_policy_assignments`, `traffic_hourly_apps`, `traffic_daily_apps`,
   * `app_used_daily`) from `from` to `to`, unions their host-sets, transfers `template_id` to `to`
   * if `to` lacks one, then deletes `from` (cascade drops any remaining duplicate FK rows that
   * conflicted with `to`'s own). Host-set version is bumped once. Idempotent under repeat calls
   * with the same args (a second call no-ops because `from` is gone).
   *
   * Conflict policy is "`to` wins" everywhere `to` already has a row at the merging key: an
   * assignment for the same `profile_id` (and via the V51 cascade, its `app_policy_schedule_rules`)
   * stays on `to`, with `from`'s discarded; a rollup bucket for the same key stays on `to`. For
   * `app_used_daily` overlapping `(profile_id, date)` rows are summed (`engaged_seconds`) instead
   * of dropped so usage isn't lost.
   *
   * Used by `AppReconciler.reconcileTemplates` to collapse the `<slug>-template` row that
   * `AppTemplates.findFreeSlug` falls back to when an operator-added canonical row already owns the
   * template's slug.
   */
  def mergeAppInto(from: AppId, to: AppId): Task[Unit]

  /**
   * Replace the full set of hosts for an app. Wipes prior rows; canonicalization is the caller's
   * responsibility.
   */
  def setHosts(appId: AppId, hosts: List[Hostname]): Task[Unit]
  def getHosts(appId: AppId): Task[List[Hostname]]

  /**
   * #1896: replace the full host-set carrying each host's `shared` flag (`false` == distinctive).
   * Same replace semantics as [[setHosts]] (which is the all-distinctive special case). The flag is
   * stored per-`(app_id, host)` and ignored until S2-S5 — see
   * `docs/design/shared-host-allocation.md`.
   */
  def setHostEntries(appId: AppId, entries: List[AppHostEntry]): Task[Unit]

  /** #1896: the host-set with each host's `shared` flag, ordered by host. */
  def getHostEntries(appId: AppId): Task[List[AppHostEntry]]

  /**
   * Current `app_hosts_version` — a global counter bumped by every host-set mutation ([[setHosts]],
   * [[delete]]). The rollup writer stamps rolled rows with the value it read here; a read path
   * compares it against the value stamped on rollup rows to decide whether the pre-attributed
   * `app_id`s are fresh or must be recomputed in process (#1091).
   */
  def currentHostsVersion: Task[Long]

  /**
   * #769: full (host, app_id) inventory across all apps. Used by the group-by-app aggregation paths
   * for Connection Events + Traffic Usage to bucket rows into their owning app. #1526: a host that
   * matches no app is its own single-host app (keyed by the host itself); there is no semantic
   * "Other" bucket. One row per (host, app) pair — a host that's in two apps yields two entries.
   */
  def listAllHostMappings: Task[List[AppHost]]

  /** Upsert a (app, profile) assignment. Existing row at that key is overwritten. */
  def upsertAssignment(
      appId: AppId,
      profileId: ProfileId,
      mode: AppMode,
      dailyMinutes: Option[Int],
      exemptFromDaily: Boolean,
      // #1679: default true for back-compat — all existing callers keep current behavior.
      allowedDuringScheduleBlock: Boolean = true,
  ): Task[AppPolicyAssignmentId]
  def deleteAssignment(appId: AppId, profileId: ProfileId): Task[Unit]
  def listAssignmentsForApp(appId: AppId): Task[List[AppPolicyAssignment]]
  def listAssignmentsForProfile(profileId: ProfileId): Task[List[AppPolicyAssignment]]

  // ── #1379: per-app schedule rules (`app_policy_schedule_rules`, V51) ──────

  /**
   * Replace the full set of schedule rules on an assignment with exactly `rules` (de-duped on
   * (scheduleId, mode)). Replace semantics, mirroring [[setHosts]] — an empty list clears them.
   */
  def setScheduleRules(
      assignmentId: AppPolicyAssignmentId,
      rules: List[(NamedScheduleId, AppScheduleMode)],
  ): Task[Unit]

  /** The schedule rules attached to a single assignment (no window resolution). */
  def scheduleRulesForAssignment(
      assignmentId: AppPolicyAssignmentId,
  ): Task[List[AppScheduleRule]]

  /**
   * For every assignment under `profileId`, the flattened (mode, window) pairs of its schedule
   * rules — each rule's referenced #1069 named schedule resolved to its `schedule_windows` rows.
   * PolicyService folds these into the per-app effective disposition (design §4.1): an
   * `allowed_during` / `blocked_during` rule is "active at now" iff ANY of its windows is, so the
   * per-rule grouping is irrelevant and we flatten to (mode, window) pairs per assignment.
   * Assignments with no rules are absent from the map.
   */
  def appScheduleWindowsForProfile(
      profileId: ProfileId,
  ): Task[Map[AppPolicyAssignmentId, List[(AppScheduleMode, ScheduleWindow)]]]
}

class AppRepoLive(xa: Transactor[Task]) extends AppRepo {
  private type R =
    (AppId, String, String, Option[AppTemplateId], Option[String], IconType, Instant)
  private def toApp(r: R) = App(r._1, r._2, r._3, r._4, r._5, r._6, r._7)

  def listAll =
    DbMetrics.timed("app.listAll")(
      sql"SELECT id,name,slug,template_id,icon,icon_type,created_at FROM apps ORDER BY id"
        .query[R]
        .map(toApp)
        .to[List]
        .transact(xa),
    )

  def findById(id: AppId) =
    sql"SELECT id,name,slug,template_id,icon,icon_type,created_at FROM apps WHERE id=$id"
      .query[R]
      .map(toApp)
      .option
      .transact(xa)

  def findBySlug(slug: String) =
    sql"SELECT id,name,slug,template_id,icon,icon_type,created_at FROM apps WHERE slug=$slug"
      .query[R]
      .map(toApp)
      .option
      .transact(xa)

  def findByTemplateId(templateId: AppTemplateId) =
    sql"SELECT id,name,slug,template_id,icon,icon_type,created_at FROM apps WHERE template_id=$templateId"
      .query[R]
      .map(toApp)
      .option
      .transact(xa)

  def create(
      name: String,
      slug: String,
      templateId: Option[AppTemplateId],
      icon: Option[String],
      iconType: IconType = IconType.Emoji,
  ) =
    sql"""INSERT INTO apps(name,slug,template_id,icon,icon_type)
          VALUES($name,$slug,$templateId,$icon,$iconType) RETURNING id"""
      .query[AppId]
      .unique
      .transact(xa)

  def update(a: App) =
    sql"""UPDATE apps SET
            name=${a.name},
            slug=${a.slug},
            template_id=${a.templateId},
            icon=${a.icon},
            icon_type=${a.iconType}
          WHERE id=${a.id}""".update.run.transact(xa).unit

  // Every host-set mutation bumps the global app_hosts_version so rollup rows
  // attributed at an older version are detected as stale on read (#1091).
  private val bumpHostsVersion =
    sql"UPDATE app_hosts_version SET version = version + 1".update.run

  def delete(id: AppId) =
    (sql"DELETE FROM apps WHERE id=$id".update.run *> bumpHostsVersion).transact(xa).unit

  def mergeAppInto(from: AppId, to: AppId) = {
    // Steps run inside ONE transaction so a mid-merge crash never strands FK rows referencing a
    // half-deleted app row. Each step handles the PK / UNIQUE conflict its target table can
    // produce: `to` may already own a row at the same key as a `from` row (e.g. an assignment for
    // the same profile, a rollup for the same bucket). Conflicting `from` rows are dropped — `to`
    // wins. Non-conflicting rows are reattached.
    //
    // Order matters only for `app_used_daily`: we INSERT-then-aggregate (so engaged_seconds sum)
    // before the cascade DELETE on `apps` would have wiped the `from` rows. Everything else is
    // safely commutative within the transaction.
    // #1896: carry the `shared` flag through the union so a merged-in shared host isn't silently
    // downgraded to distinctive. `to` wins on conflict, so its own flag is preserved either way.
    val unionHosts =
      sql"""INSERT INTO app_hosts (app_id, host, shared)
            SELECT $to, host, shared FROM app_hosts WHERE app_id = $from
            ON CONFLICT DO NOTHING""".update.run

    val reattachAssignments =
      sql"""UPDATE app_policy_assignments SET app_id = $to
            WHERE app_id = $from
              AND profile_id NOT IN (SELECT profile_id FROM app_policy_assignments WHERE app_id = $to)""".update.run

    val dropConflictingHourly =
      sql"""DELETE FROM traffic_hourly_apps t
            WHERE t.app_id = $from
              AND EXISTS (
                SELECT 1 FROM traffic_hourly_apps c
                 WHERE c.app_id = $to
                   AND c.router_id = t.router_id
                   AND c.mac = t.mac
                   AND c.hostname = t.hostname
                   AND c.bucket_start = t.bucket_start)""".update.run

    val reattachHourly =
      sql"UPDATE traffic_hourly_apps SET app_id = $to WHERE app_id = $from".update.run

    val dropConflictingDaily =
      sql"""DELETE FROM traffic_daily_apps t
            WHERE t.app_id = $from
              AND EXISTS (
                SELECT 1 FROM traffic_daily_apps c
                 WHERE c.app_id = $to
                   AND c.router_id = t.router_id
                   AND c.mac = t.mac
                   AND c.hostname = t.hostname
                   AND c.date = t.date)""".update.run

    val reattachDaily =
      sql"UPDATE traffic_daily_apps SET app_id = $to WHERE app_id = $from".update.run

    // Sum engaged_seconds on (profile_id, date) collisions so we don't drop the duplicate's usage.
    val mergeUsedDaily =
      sql"""INSERT INTO app_used_daily (profile_id, app_id, date, engaged_seconds, rolled_through, rolled_at)
            SELECT profile_id, $to, date, engaged_seconds, rolled_through, rolled_at
              FROM app_used_daily WHERE app_id = $from
            ON CONFLICT (profile_id, app_id, date) DO UPDATE
              SET engaged_seconds = app_used_daily.engaged_seconds + EXCLUDED.engaged_seconds,
                  rolled_through = LEAST(app_used_daily.rolled_through, EXCLUDED.rolled_through),
                  rolled_at      = GREATEST(app_used_daily.rolled_at, EXCLUDED.rolled_at)""".update.run

    // Transfer template_id IFF `to` lacks one — preserves the link `AppTemplates.findByTemplateId`
    // walks on every seed so a future seed pass finds the canonical row instead of falling back to
    // the `<slug>-template` slug again (the whack-a-mole guard).
    val transferTemplateId =
      sql"""UPDATE apps SET template_id = (SELECT template_id FROM apps WHERE id = $from)
            WHERE id = $to
              AND template_id IS NULL
              AND (SELECT template_id FROM apps WHERE id = $from) IS NOT NULL""".update.run

    // Final cascade DELETE drops any FK rows from `from` that we didn't actively reattach.
    val deleteFrom = sql"DELETE FROM apps WHERE id = $from".update.run

    (unionHosts *>
      reattachAssignments *>
      dropConflictingHourly *> reattachHourly *>
      dropConflictingDaily *> reattachDaily *>
      mergeUsedDaily *>
      transferTemplateId *>
      deleteFrom *>
      bumpHostsVersion).transact(xa).unit
  }

  // setHosts is the all-distinctive special case of setHostEntries (#1896): every host shared=false.
  def setHosts(appId: AppId, hosts: List[Hostname]) =
    setHostEntries(appId, hosts.map(AppHostEntry(_, shared = false)))

  def setHostEntries(appId: AppId, entries: List[AppHostEntry]) = {
    val del = sql"DELETE FROM app_hosts WHERE app_id=$appId".update.run
    val ins = entries.distinctBy(_.host).map { e =>
      sql"""INSERT INTO app_hosts(app_id,host,shared) VALUES($appId,${e.host},${e.shared})
            ON CONFLICT DO NOTHING""".update.run
    }
    (del *> ins.foldLeft(FC.unit)(_ *> _.void) *> bumpHostsVersion).transact(xa).unit
  }

  def getHosts(appId: AppId) =
    DbMetrics.timed("app.getHosts")(
      sql"SELECT host FROM app_hosts WHERE app_id=$appId ORDER BY host"
        .query[Hostname]
        .to[List]
        .transact(xa),
    )

  def getHostEntries(appId: AppId) =
    DbMetrics.timed("app.getHostEntries")(
      sql"SELECT host, shared FROM app_hosts WHERE app_id=$appId ORDER BY host"
        .query[(Hostname, Boolean)]
        .map { case (h, s) => AppHostEntry(h, s) }
        .to[List]
        .transact(xa),
    )

  def currentHostsVersion =
    sql"SELECT version FROM app_hosts_version".query[Long].unique.transact(xa)

  def listAllHostMappings =
    sql"SELECT app_id, host, shared FROM app_hosts"
      .query[(AppId, Hostname, Boolean)]
      .map { case (id, h, shared) => AppHost(id, h, shared) }
      .to[List]
      .transact(xa)

  def upsertAssignment(
      appId: AppId,
      profileId: ProfileId,
      mode: AppMode,
      dailyMinutes: Option[Int],
      exemptFromDaily: Boolean,
      allowedDuringScheduleBlock: Boolean = true,
  ) =
    sql"""INSERT INTO app_policy_assignments
            (app_id, profile_id, mode, daily_minutes, exempt_from_daily,
             allowed_during_schedule_block)
          VALUES ($appId, $profileId, ${AppMode.asString(mode)}, $dailyMinutes, $exemptFromDaily,
                  $allowedDuringScheduleBlock)
          ON CONFLICT (app_id, profile_id) DO UPDATE SET
            mode = EXCLUDED.mode,
            daily_minutes = EXCLUDED.daily_minutes,
            exempt_from_daily = EXCLUDED.exempt_from_daily,
            allowed_during_schedule_block = EXCLUDED.allowed_during_schedule_block
          RETURNING id"""
      .query[AppPolicyAssignmentId]
      .unique
      .transact(xa)

  def deleteAssignment(appId: AppId, profileId: ProfileId) =
    sql"DELETE FROM app_policy_assignments WHERE app_id=$appId AND profile_id=$profileId".update.run
      .transact(xa)
      .unit

  private type AR =
    (AppPolicyAssignmentId, AppId, ProfileId, AppMode, Option[Int], Boolean, Boolean)
  private def toAssignment(r: AR) =
    AppPolicyAssignment(r._1, r._2, r._3, r._4, r._5, r._6, r._7)

  def listAssignmentsForApp(appId: AppId) =
    sql"""SELECT id,app_id,profile_id,mode,daily_minutes,exempt_from_daily,
                 allowed_during_schedule_block
          FROM app_policy_assignments WHERE app_id=$appId ORDER BY id"""
      .query[AR]
      .map(toAssignment)
      .to[List]
      .transact(xa)

  def listAssignmentsForProfile(profileId: ProfileId) =
    DbMetrics.timed("app.listAssignmentsForProfile")(
      sql"""SELECT id,app_id,profile_id,mode,daily_minutes,exempt_from_daily,
                   allowed_during_schedule_block
          FROM app_policy_assignments WHERE profile_id=$profileId ORDER BY id"""
        .query[AR]
        .map(toAssignment)
        .to[List]
        .transact(xa),
    )

  // ── #1379: per-app schedule rules ─────────────────────────────────────────

  def setScheduleRules(
      assignmentId: AppPolicyAssignmentId,
      rules: List[(NamedScheduleId, AppScheduleMode)],
  ) = {
    val del =
      sql"DELETE FROM app_policy_schedule_rules WHERE assignment_id=$assignmentId".update.run
    val ins = rules.distinct.foldLeft(FC.unit) { case (acc, (sid, mode)) =>
      acc *> sql"""INSERT INTO app_policy_schedule_rules(assignment_id, schedule_id, mode)
                   VALUES($assignmentId, $sid, ${AppScheduleMode.asString(mode)})
                   ON CONFLICT (assignment_id, schedule_id, mode) DO NOTHING""".update.run.void
    }
    (del *> ins).transact(xa).unit
  }

  def scheduleRulesForAssignment(assignmentId: AppPolicyAssignmentId) =
    sql"""SELECT id, assignment_id, schedule_id, mode
          FROM app_policy_schedule_rules WHERE assignment_id=$assignmentId ORDER BY id"""
      .query[(AppScheduleRuleId, AppPolicyAssignmentId, NamedScheduleId, AppScheduleMode)]
      .map { case (id, aid, sid, mode) => AppScheduleRule(sid, mode, id, aid) }
      .to[List]
      .transact(xa)

  // Resolve each assignment's rules to flattened (mode, window) pairs in one join:
  // app_policy_schedule_rules -> assignment (for the profile filter) -> schedule_windows.
  def appScheduleWindowsForProfile(profileId: ProfileId) =
    sql"""SELECT apsr.assignment_id, apsr.mode, sw.days, sw.start_local, sw.end_local, sw.tz
          FROM app_policy_schedule_rules apsr
          JOIN app_policy_assignments apa ON apa.id = apsr.assignment_id
          JOIN schedule_windows sw        ON sw.schedule_id = apsr.schedule_id
          WHERE apa.profile_id = $profileId
          ORDER BY apsr.assignment_id, apsr.id, sw.id"""
      .query[(AppPolicyAssignmentId, AppScheduleMode, List[String], LocalTime, LocalTime, ZoneId)]
      .to[List]
      .transact(xa)
      .map(_.groupBy(_._1).map { case (aid, rows) =>
        aid -> rows.map(r => (r._2, ScheduleWindow(r._3, r._4, r._5, r._6)))
      })
}

class NamedScheduleRepoLive(xa: Transactor[Task]) extends NamedScheduleRepo {
  // (days, start_local, end_local, tz) — same typed shape as the V1 schedules columns.
  private type W = (List[String], LocalTime, LocalTime, ZoneId)
  private def toWindow(w: W) = ScheduleWindow(w._1, w._2, w._3, w._4)

  private def windowsFor(id: NamedScheduleId): ConnectionIO[List[ScheduleWindow]] =
    sql"SELECT days,start_local,end_local,tz FROM schedule_windows WHERE schedule_id=$id ORDER BY id"
      .query[W]
      .map(toWindow)
      .to[List]

  private def insertWindows(id: NamedScheduleId, ws: List[ScheduleWindow]): ConnectionIO[Unit] =
    ws.foldLeft(FC.unit) { (acc, w) =>
      acc *> sql"""INSERT INTO schedule_windows(schedule_id,days,start_local,end_local,tz)
                   VALUES($id,${w.days.toArray},${w.startLocal},${w.endLocal},${w.tz})""".update.run.void
    }

  // #2126: AND-scoped to one household via the V72 `household_id` column (index-backed by
  // idx_named_schedules_household). Backs `GET /api/schedules` with the caller's `claims.hh`.
  def listAllForHousehold(household: HouseholdId) =
    DbMetrics.timed("namedSchedule.listAllForHousehold")(
      (for {
        rows <-
          (fr"SELECT id,name,description FROM named_schedules WHERE" ++
            SqlFragments.householdEq(household) ++ fr"ORDER BY name")
            .query[(NamedScheduleId, String, Option[String])]
            .to[List]
        out  <- rows.traverse { case (id, name, desc) =>
          windowsFor(id).map(ws => NamedSchedule(id, name, desc, ws))
        }
      } yield out).transact(xa),
    )

  // #2126: the household that owns `id`, for the per-id route guards. Index-backed by the primary key.
  def householdOf(id: NamedScheduleId) =
    sql"SELECT household_id FROM named_schedules WHERE id=$id"
      .query[HouseholdId]
      .option
      .transact(xa)

  def findById(id: NamedScheduleId) =
    (for {
      row <-
        sql"SELECT id,name,description FROM named_schedules WHERE id=$id"
          .query[(NamedScheduleId, String, Option[String])]
          .option
      out <- row.traverse { case (rid, name, desc) =>
        windowsFor(rid).map(ws => NamedSchedule(rid, name, desc, ws))
      }
    } yield out).transact(xa)

  // #2572: AND-scoped to the caller's household, same predicate as listAllForHousehold — the
  // name-taken 409 must be answerable only from rows the caller can see. `.option` is safe here
  // precisely because of the scope: once V87 widens the unique to (household_id, name), a bare
  // `WHERE name=?` could match one row per household and raise.
  def findByName(name: String, household: HouseholdId) =
    (for {
      row <-
        (fr"SELECT id,name,description FROM named_schedules WHERE name=$name AND" ++
          SqlFragments.householdEq(household))
          .query[(NamedScheduleId, String, Option[String])]
          .option
      out <- row.traverse { case (rid, n, desc) =>
        windowsFor(rid).map(ws => NamedSchedule(rid, n, desc, ws))
      }
    } yield out).transact(xa)

  def create(
      name: String,
      description: Option[String],
      windows: List[ScheduleWindow],
      household: HouseholdId = HouseholdId.Default,
  ) =
    // #2126: household_id is stamped explicitly — never left to V72's DEFAULT 1 (#2130 precedent).
    (for {
      id <-
        sql"INSERT INTO named_schedules(name,description,household_id) VALUES($name,$description,$household) RETURNING id"
          .query[NamedScheduleId]
          .unique
      _  <- insertWindows(id, windows)
    } yield id).transact(xa)

  def update(
      id: NamedScheduleId,
      name: String,
      description: Option[String],
      windows: List[ScheduleWindow],
  ) =
    (sql"""UPDATE named_schedules SET name=$name, description=$description, updated_at=NOW()
           WHERE id=$id""".update.run *>
      sql"DELETE FROM schedule_windows WHERE schedule_id=$id".update.run *>
      insertWindows(id, windows)).transact(xa).unit

  def delete(id: NamedScheduleId) =
    sql"DELETE FROM named_schedules WHERE id=$id".update.run.transact(xa).unit

  private val BlockedDuring = "blocked_during"

  def blockScheduleIdsForProfile(pid: ProfileId) =
    sql"""SELECT schedule_id FROM profile_schedule_rules
          WHERE profile_id=$pid AND mode=$BlockedDuring
          ORDER BY schedule_id"""
      .query[NamedScheduleId]
      .to[List]
      .transact(xa)

  def setProfileBlockSchedules(pid: ProfileId, ids: List[NamedScheduleId]) = {
    val del = sql"""DELETE FROM profile_schedule_rules
                    WHERE profile_id=$pid AND mode=$BlockedDuring""".update.run
    val ins = ids.distinct.foldLeft(FC.unit) { (acc, sid) =>
      acc *> sql"""INSERT INTO profile_schedule_rules(profile_id, schedule_id, mode)
                   VALUES($pid, $sid, $BlockedDuring)
                   ON CONFLICT (profile_id, schedule_id, mode) DO NOTHING""".update.run.void
    }
    (del *> ins).transact(xa).unit
  }

  // Block-mode windows attached to a profile: rules -> named_schedules -> windows.
  def windowsForProfile(pid: ProfileId) =
    sql"""SELECT sw.days, sw.start_local, sw.end_local, sw.tz
          FROM schedule_windows sw
          JOIN profile_schedule_rules psr ON psr.schedule_id = sw.schedule_id
          WHERE psr.profile_id = $pid AND psr.mode = $BlockedDuring
          ORDER BY sw.id"""
      .query[W]
      .map(toWindow)
      .to[List]
      .transact(xa)

  // #2264: household-scoped via the `profiles.household_id` join (idx_profiles_household, V65).
  // Timed like its two sibling `dayStateAll` reads so the added join is visible on the
  // `db_query_duration_seconds` by-op p95 panel (the missing-index leading indicator, #809).
  def windowsForHouseholdProfiles(household: HouseholdId) =
    DbMetrics.timed("namedSchedule.windowsForHouseholdProfiles")(
      (fr"""SELECT psr.profile_id, sw.days, sw.start_local, sw.end_local, sw.tz
            FROM schedule_windows sw
            JOIN profile_schedule_rules psr ON psr.schedule_id = sw.schedule_id
            JOIN profiles p ON p.id = psr.profile_id
            WHERE psr.mode = $BlockedDuring AND """ ++
        SqlFragments.householdEq(household, "p.household_id") ++
        fr"ORDER BY psr.profile_id, sw.id")
        .query[(ProfileId, List[String], LocalTime, LocalTime, ZoneId)]
        .to[List]
        .transact(xa)
        .map(_.groupBy(_._1).map { case (pid, rows) =>
          pid -> rows.map(r => ScheduleWindow(r._2, r._3, r._4, r._5))
        }),
    )
}

object Repos {
  val userRepo              = ZLayer.fromFunction(UserRepoLive(_))
  val householdRepo         = ZLayer.fromFunction(HouseholdRepoLive(_))
  val userProfileRepo       = ZLayer.fromFunction(UserProfileRepoLive(_))
  val profileRepo           = ZLayer.fromFunction(ProfileRepoLive(_))
  val namedScheduleRepo     = ZLayer.fromFunction(NamedScheduleRepoLive(_))
  val householdSettingsRepo = ZLayer.fromFunction(HouseholdSettingsRepoLive(_))
  val timeLimitRepo         = ZLayer.fromFunction(TimeLimitRepoLive(_))
  val appTimeLimitRepo      = ZLayer.fromFunction(AppTimeLimitRepoLive(_))
  val deviceRepo            = ZLayer.fromFunction(DeviceRepoLive(_))
  val blocklistRepo         = ZLayer.fromFunction(BlocklistRepoLive(_))
  val timeUsageRepo         = ZLayer.fromFunction(TimeUsageRepoLive(_))
  val timeExtRepo           = ZLayer.fromFunction(TimeExtensionRepoLive(_))
  val routerRepo            = ZLayer.fromFunction(RouterRepoLive(_))
  val trafficReportRepo     = ZLayer.fromFunction(TrafficReportRepoLive(_))
  val blockEventRepo        = ZLayer.fromFunction(BlockEventRepoLive(_))
  val connEventRepo         = ZLayer.fromFunction(ConnectionEventRepoLive(_))
  val alertRepo             = ZLayer.fromFunction(AlertRepoLive(_))
  val appRepo               = ZLayer.fromFunction(AppRepoLive(_))
  val rollupRepo            = ZLayer.fromFunction(RollupRepoLive(_))
  val timeUsedRollupRepo    = ZLayer.fromFunction(TimeUsedRollupRepoLive(_))
  val appUsedRollupRepo     = ZLayer.fromFunction(AppUsedRollupRepoLive(_))
  val partitionRepo         = ZLayer.fromFunction(PartitionRepoLive(_))
  val ambientHostsRepo      = ZLayer.fromFunction(AmbientHostsRepoLive(_))
  // #2132 (multi-tenant P5-2): beta-request pipeline repos (householdRepo is declared above, #2140).
  val householdBillingRepo  = ZLayer.fromFunction(HouseholdBillingRepoLive(_))
  val betaRequestRepo       = ZLayer.fromFunction(BetaRequestRepoLive(_))
  // #2137 (multi-tenant P5-6): the cohort-wide flip clock (beta_cohort, V70).
  val betaCohortRepo        = ZLayer.fromFunction(BetaCohortRepoLive(_))
  // #2134 (multi-tenant P5-4): per-household entitlements (router_cap).
  val entitlementsRepo      = ZLayer.fromFunction(EntitlementsRepoLive(_))
  // #2296 (press correspondence log, epic #2197/#2203): the company-global press message log (V71).
  val pressMessageRepo      = ZLayer.fromFunction(PressMessageRepoLive(_))
  // #2308 (forgot-password, epic #622): single-use, short-TTL password-reset tokens (V77).
  val passwordResetRepo     = ZLayer.fromFunction(PasswordResetTokenRepoLive(_))
  // #2419 (support data-access consent, epic #2197): the per-(household, thread) customer
  // grants that widen the #2241 agent token's data scope (V84).
  val supportConsentRepo    = ZLayer.fromFunction(SupportConsentRepoLive(_))
  val all                   =
    userRepo ++ householdRepo ++ userProfileRepo ++ profileRepo ++ namedScheduleRepo ++ householdSettingsRepo ++ timeLimitRepo ++ appTimeLimitRepo ++ deviceRepo ++ blocklistRepo ++ timeUsageRepo ++ timeExtRepo ++ routerRepo ++ trafficReportRepo ++ blockEventRepo ++ connEventRepo ++ alertRepo ++ appRepo ++ rollupRepo ++ timeUsedRollupRepo ++ appUsedRollupRepo ++ partitionRepo ++ ambientHostsRepo ++ householdBillingRepo ++ betaRequestRepo ++ betaCohortRepo ++ entitlementsRepo ++ pressMessageRepo ++ passwordResetRepo ++ supportConsentRepo
}
