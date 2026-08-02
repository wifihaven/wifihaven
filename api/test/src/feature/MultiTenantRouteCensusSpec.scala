package wifihaven.api.feature

import zio.test.*

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * #2563 (multi-tenant isolation audit, epic #2085/#622) — THE STRUCTURAL GUARD.
 *
 * The audit that produced this file is a snapshot. This is what stops the next gap.
 *
 * Seven isolation issues (#2532, #2553, #2535, #2283, #2264, #2142, #2322) were all the same defect
 * class, and every one was found by ACCIDENT — a review of an unrelated PR, a crash, a follow-up —
 * rather than by anything mechanical. The tenancy pass was applied route-by-route, so a route added
 * afterwards inherits nothing: it is tenancy-correct only if its author happened to remember.
 *
 * This spec removes "happened to remember" from the loop, in two layers.
 *
 * ── Layer 1: the CENSUS (`test 1`) ────────────────────────────────────────────────────────────
 * Every route in `api/src/routes` must appear in [[Census]] with an explicit [[Tenancy]] verdict. A
 * new route — or a renamed / removed / re-pathed one — fails the build until someone writes down
 * what its tenancy IS. The verdict is not a comment or a checklist item: it is a value the next
 * test consumes.
 *
 * ── Layer 2: the CHOKE-POINT INVARIANT (`test 2`) ─────────────────────────────────────────────
 * Every route declared [[Tenancy.Scoped]] whose path takes an ENTITY parameter (`long("id")` /
 * `string("mac")` — i.e. the caller names the row) must textually compose one of the known tenancy
 * choke points ([[Checkers]]) in its handler. That is the mechanical statement of the rule the
 * codebase already follows by hand: *a route that lets the caller name a row must prove the row is
 * theirs.*
 *
 * Note what is deliberately NOT a checker: a bare `claims.hh`. Reading the caller's household to
 * STAMP a write (`extRepo.grantForProfile(pid, …, claims.hh)`) is not the same as CHECKING the
 * target, and conflating the two is exactly how #2564 shipped — `POST /api/alerts/{id}/approve`
 * mentions `claims.hh` on the line that stamps the grant, while the alert id it acts on is never
 * checked at all.
 *
 * ── The tracked-broken allowlist ──────────────────────────────────────────────────────────────
 * [[Tenancy.ScopedTracked]] is the escape hatch for routes the audit found ALREADY broken. They are
 * NOT fixed here (the audit PR stays reviewable; each fix is its own chip), so the guard would be
 * red on arrival. Each entry names its tracking issue, and `test 3` asserts every one of them still
 * resolves to a real issue number. The set is allowed to SHRINK freely and never to grow: a fix
 * chip deletes its entry, and a NEW unguarded route cannot be waved through without filing an issue
 * for it first. Same discipline as [[MultiTenantScopedReadGuardSpec]]'s allowlist.
 *
 * ── Why static, and what that buys ──────────────────────────────────────────────────────────── A
 * source scan, not a runtime drive. That is the trade: it cannot prove a checker is composed on the
 * RIGHT id, but it covers 100% of routes in every route file with zero per-route wiring, and it
 * cannot rot the way a hand-maintained behavioural suite does. The behavioural half already exists
 * and stays where it is — [[MultiTenantIsolationSpec]] pins the real cross-household HTTP responses
 * for the representative surfaces. `test 4` here asserts the scan is non-vacuous (it really does
 * see the checkers on the 28 routes that have them), so a regex that silently stopped matching
 * fails loudly instead of turning the whole guard into a no-op.
 */
object MultiTenantRouteCensusSpec extends ZIOSpecDefault {

  // ── the verdict vocabulary ───────────────────────────────────────────────────────────────────

  /**
   * What tenancy means for one route. Every route must be exactly one of these, and the choice is
   * load-bearing: `Scoped` is the only one `test 2` enforces a choke point on.
   */
  enum Tenancy {

    /**
     * Household-scoped: the route reads/writes rows belonging to one household, and derives that
     * household from the authenticated principal (`claims.hh`). If it also takes an entity path
     * param, it MUST compose a [[Checkers]] choke point.
     */
    case Scoped

    /**
     * Household-scoped, and the audit found it MISSING its choke point. Tracked by `issue`, fixed
     * by its own chip. This set may only shrink.
     */
    case ScopedTracked(issue: Int)

    /**
     * Acts on install-wide state that genuinely has no tenancy dimension (the bundled blocklist
     * catalog, the template-authored app catalog). `reason` must say why — and note that
     * "install-wide" is a statement about the DATA, not a licence for any household to MUTATE it;
     * the mutators here are tracked separately by #2535/#2567.
     */
    case InstallWide(reason: String)

    /**
     * Gated by `requireOperator` — admin AND household 1. The one sanctioned cross-household gate.
     */
    case Operator(reason: String)

    /** Authenticated by a router bearer token; the household comes from `routers.household_id`. */
    case RouterToken(reason: String)

    /** Authenticated by a per-session agent/consent token that itself carries the household. */
    case AgentToken(reason: String)

    /**
     * Deliberately unauthenticated. `reason` must say how the household is derived, or that it is
     * not.
     */
    case Unauthenticated(reason: String)

    /** No tenancy dimension at all — health, version, static assets, process-level metrics. */
    case NoTenancy(reason: String)
  }

  import Tenancy.*

  /**
   * The tenancy choke points. A route that lets the caller NAME a row proves the row is theirs by
   * composing one of these.
   *
   *   - `requireProfileInHousehold` / `requireProfile{,Read}Access` — profile ids (the latter two
   *     compose the former FIRST, so either satisfies the invariant).
   *   - `requireScheduleInHousehold` — named-schedule ids.
   *   - `ownUser` — user ids, incl. ids arriving in a request BODY.
   *   - `findByMacInHousehold` — device MACs.
   *   - `householdOf` — the raw probe, for a route that hand-rolls its own comparison.
   *   - `requireOperator` — the sanctioned cross-household gate; a route inside it is allowed to
   *     name an arbitrary household's row on purpose.
   *   - `householdId == claims.hh` — the explicit inline comparison (`DELETE
   *     /api/admin/routers/{id}` does this via `filterOrFail`).
   *
   * `claims.hh` alone is NOT here, on purpose — see the class doc.
   */
  private val Checkers: Set[String] = Set(
    "requireProfileInHousehold",
    "requireProfileAccess",
    "requireProfileReadAccess",
    "requireScheduleInHousehold",
    "ownUser",
    "findByMacInHousehold",
    "householdOf",
    "requireOperator",
    "householdId == claims.hh",
  )

  // ── the census ───────────────────────────────────────────────────────────────────────────────

  /**
   * Every route in `api/src/routes`, keyed `"<METHOD> <path>"` with `{}` for an entity param and
   * `{*}` for a trailing capture. Adding a route here is not optional — `test 1` fails until the
   * live route table and this map agree exactly.
   */
  val Census: Map[String, Tenancy] = Map(
    // ── AlertRoutes ────────────────────────────────────────────────────────────────────────────
    "GET /api/alerts"             -> Scoped,
    "POST /api/access-requests"   -> Unauthenticated(
      "block-page intake; household derived in-SQL from the device row — arbitrary for a shared MAC (#2322), " +
        "and the debounce read is unscoped (#2566)",
    ),
    "POST /api/alerts/{}/approve" -> Scoped,
    "POST /api/alerts/{}/deny"    -> Scoped,

    // ── AppRoutes ──────────────────────────────────────────────────────────────────────────────
    "GET /api/apps"       -> InstallWide("template-authored app catalog, #1798"),
    "GET /api/apps/{}"    -> InstallWide("template-authored app catalog, #1798"),
    "DELETE /api/apps/{}" -> InstallWide("catalog row delete — any household can reach it, #2535"),
    "POST /api/apps/seed-from-templates"       -> InstallWide(
      "catalog re-seed — any household can reach it, #2567",
    ),
    "POST /api/apps/{}/reset-to-template"      -> InstallWide(
      "catalog host-set rewrite — any household can reach it, #2567",
    ),
    "POST /api/admin/apps/reconcile-templates" -> InstallWide(
      "catalog merge/delete — any household admin can reach it, #2567",
    ),
    "PUT /api/apps/{}/policy/{}"               -> Scoped,
    "DELETE /api/apps/{}/policy/{}"            -> Scoped,

    // ── BetaRoutes ─────────────────────────────────────────────────────────────────────────────
    "POST /api/beta/request"                      -> Unauthenticated(
      "intake; the row belongs to no household until approval",
    ),
    "POST /api/beta/accept"                       -> Unauthenticated(
      "invite-token bearer; provisions and binds its OWN new household",
    ),
    "GET /api/operator/beta-requests"             -> Operator("pre-household approval queue"),
    "POST /api/operator/beta-requests/{}/approve" -> Operator("pre-household approval queue"),
    "POST /api/operator/beta-requests/{}/reject"  -> Operator("pre-household approval queue"),

    // ── BillingRoutes ──────────────────────────────────────────────────────────────────────────
    "GET /api/billing"                                -> Scoped,
    "POST /api/billing/checkout"                      -> Scoped,
    "GET /api/billing/portal"                         -> Scoped,
    "POST /api/operator/households/{}/free-forever"   -> Operator(
      "deliberately-privileged grant on an arbitrary household, #2356",
    ),
    "DELETE /api/operator/households/{}/free-forever" -> Operator(
      "deliberately-privileged revoke on an arbitrary household, #2356",
    ),
    "POST /api/billing/webhook"                       -> Unauthenticated(
      "Stripe signature IS the auth; household resolved from stripe_customer_id",
    ),

    // ── BlockedRoutes ──────────────────────────────────────────────────────────────────────────
    "GET /api/blocked" -> Unauthenticated(
      "block page; hardcodes HouseholdId.Default — discloses hh1 state and is wrong elsewhere (#2569)",
    ),

    // ── DashboardNowRoutes ─────────────────────────────────────────────────────────────────────
    "GET /api/dashboard/now" -> Scoped,

    // ── DebugRoutes ────────────────────────────────────────────────────────────────────────────
    "GET /api/debug/config"  -> NoTenancy("loopback-only + WIFIHAVEN_DEBUG=1; feature-state dump"),
    "GET /api/debug/devices" -> NoTenancy(
      "loopback-only + WIFIHAVEN_DEBUG=1; EXPLICIT all-tenant triage dump",
    ),
    "GET /api/debug/events"  -> NoTenancy(
      "loopback-only + WIFIHAVEN_DEBUG=1; all-tenant triage dump",
    ),
    "GET /api/debug/time_usage"         -> NoTenancy(
      "loopback-only + WIFIHAVEN_DEBUG=1; all-tenant triage dump",
    ),
    "GET /api/debug/cache-stats"        -> NoTenancy(
      "loopback-only + WIFIHAVEN_DEBUG=1; process-level counters",
    ),
    "POST /api/debug/cache-stats/reset" -> NoTenancy(
      "loopback-only + WIFIHAVEN_DEBUG=1; process-level cache reset",
    ),
    "GET /api/admin/snapshot"           -> Operator(
      "admin-gated; reads HouseholdId.Default's snapshot explicitly",
    ),

    // ── HealthRoutes / VersionRoutes / MetricsRoutes / OpenApi / Static ─────────────────────────
    "GET /api/health"  -> NoTenancy("liveness"),
    "GET /api/version" -> NoTenancy("build info"),
    "GET /metrics" -> NoTenancy("Prometheus scrape; bounded label enums, no per-household series"),
    "GET /api/openapi.json" -> NoTenancy("static spec"),
    "GET /api/docs"         -> NoTenancy("static docs page"),
    "GET /{*}"              -> NoTenancy("SPA static asset fallback"),

    // ── HouseholdEnforcementRoutes ─────────────────────────────────────────────────────────────
    "GET /api/household/enforcement" -> Scoped,
    "PUT /api/household/enforcement" -> Scoped,

    // ── PasswordResetRoutes ────────────────────────────────────────────────────────────────────
    "POST /api/auth/forgot-password" -> Unauthenticated(
      "household derived from the globally-unique users.email row",
    ),
    "POST /api/auth/reset-password"  -> Unauthenticated(
      "household derived from the token's user row",
    ),

    // ── Press ──────────────────────────────────────────────────────────────────────────────────
    "POST /api/press/inbound"          -> Unauthenticated(
      "CF Email Worker shared secret; press has no household dimension",
    ),
    "POST /api/press/agent/reply"      -> AgentToken(
      "signed PressToken; press has no household dimension",
    ),
    "POST /api/press/agent/escalate"   -> AgentToken(
      "signed PressToken; press has no household dimension",
    ),
    "POST /api/press/outreach/preview" -> Operator("operator-only outreach console"),
    "POST /api/press/outreach/send"    -> Operator("operator-only outreach console"),
    "GET /api/press/messages"          -> Operator("operator-only press inbox (admin AND hh1)"),

    // ── RollupAdminRoutes ──────────────────────────────────────────────────────────────────────
    "GET /api/admin/rollup-status" -> NoTenancy(
      "install-wide job telemetry (rollup_runs); carries no household rows",
    ),

    // ── Router plane ───────────────────────────────────────────────────────────────────────────
    "POST /api/router/usage"    -> RouterToken(
      "household = router.householdId, threaded into every write",
    ),
    "POST /api/router/events"   -> RouterToken(
      "household = router.householdId, threaded into every write",
    ),
    "POST /api/router/metrics"  -> RouterToken("router-scoped metrics ingest"),
    "POST /api/router/register" -> Unauthenticated(
      "single-use enrollment token; household stamped at create (#2106)",
    ),
    "GET /api/router/policy"    -> RouterToken("snapshot(router.householdId)"),
    "POST /api/router/decision" -> RouterToken("decide(router.householdId, …)"),
    "GET /api/router/ws"        -> RouterToken("ws push bound to router.householdId"),
    "GET /api/blocklists/{}"    -> InstallWide("router fetches the shared bundled catalog by id"),
    "POST /api/admin/routers"   -> Scoped,
    "GET /api/admin/routers"    -> Scoped,
    "DELETE /api/admin/routers/{}" -> Scoped,

    // ── Routes.scala: auth / users ─────────────────────────────────────────────────────────────
    "POST /api/auth/login"           -> Unauthenticated(
      "household resolved by identifier form: email / slug-username / default",
    ),
    "POST /api/auth/change-password" -> Scoped,
    "GET /api/me"                    -> Scoped,
    "POST /api/users"                -> Scoped,
    "GET /api/users"                 -> Scoped,
    "PATCH /api/users/{}"            -> Scoped,
    "DELETE /api/users/{}"           -> Scoped,
    "PUT /api/users/{}/profiles"     -> Scoped,

    // ── Routes.scala: profiles ─────────────────────────────────────────────────────────────────
    "GET /api/profiles"              -> Scoped,
    "POST /api/profiles"             -> Scoped,
    "GET /api/profiles/global"       -> Scoped,
    "GET /api/profiles/{}"           -> Scoped,
    "PUT /api/profiles/{}"           -> Scoped,
    "PATCH /api/profiles/{}"         -> Scoped,
    "DELETE /api/profiles/{}"        -> Scoped,
    "PUT /api/profiles/{}/schedules" -> Scoped,
    "GET /api/profiles/{}/users"     -> Scoped,
    "PUT /api/profiles/{}/users"     -> Scoped,

    // ── Routes.scala: devices ──────────────────────────────────────────────────────────────────
    "GET /api/devices"       -> Scoped,
    "PUT /api/devices"       -> Scoped,
    "PATCH /api/devices/{}"  -> Scoped,
    "DELETE /api/devices/{}" -> Scoped,

    // ── Routes.scala: time / presence ──────────────────────────────────────────────────────────
    "GET /api/time/status"               -> Scoped,
    "GET /api/time/status/week"          -> Scoped,
    "GET /api/time/status/summary"       -> Scoped,
    "GET /api/time/status/summary/week"  -> Scoped,
    "GET /api/time/status/{}"            -> Scoped,
    "GET /api/time/status/{}/week"       -> Scoped,
    "GET /api/time/heartbeat-explain/{}" -> Scoped,
    "GET /api/presence/ambient-hosts"    -> Scoped,
    "POST /api/time/extend"              -> Scoped,
    "GET /api/time/extensions/{}"        -> Scoped,

    // ── Routes.scala: logs / stats / blocklists / settings ─────────────────────────────────────
    "GET /api/logs"                     -> Scoped,
    "GET /api/connection-events/series" -> Scoped,
    "GET /api/stats"                    -> Scoped,
    "GET /api/blocklists"               -> InstallWide("shared bundled catalog listing"),
    "GET /api/blocklists/{}/hosts"      -> InstallWide("shared bundled catalog contents"),
    "POST /api/blocklists/{}/clear"     -> InstallWide(
      "catalog wipe — any household can reach it, #2535",
    ),
    "POST /api/blocklists/{}/refresh"   -> InstallWide(
      "catalog re-seed — any household can reach it, #2567",
    ),
    "GET /api/household/settings"       -> Scoped,
    "PUT /api/household/settings"       -> Scoped,
    "PATCH /api/household/settings"     -> Scoped,

    // ── ScheduleRoutes ─────────────────────────────────────────────────────────────────────────
    "GET /api/schedules"       -> Scoped,
    "POST /api/schedules"      -> Scoped,
    "GET /api/schedules/{}"    -> Scoped,
    "PATCH /api/schedules/{}"  -> Scoped,
    "DELETE /api/schedules/{}" -> Scoped,

    // ── SpaWsRoutes ────────────────────────────────────────────────────────────────────────────
    "GET /api/ws" -> Scoped,

    // ── Support ────────────────────────────────────────────────────────────────────────────────
    "POST /api/support/webhook"      -> Unauthenticated(
      "Plain HMAC IS the auth; household resolved from the thread's customer",
    ),
    "POST /api/support/agent/reply"  -> AgentToken("ConsentToken is thread- AND household-bound"),
    "POST /api/support/agent/issues" -> AgentToken("ConsentToken is thread- AND household-bound"),
    "POST /api/support/agent/request-consent" -> AgentToken(
      "ConsentToken is thread- AND household-bound",
    ),
    "POST /api/support/agent/escalate" -> AgentToken("ConsentToken is thread- AND household-bound"),
    "GET /api/support/agent/household" -> AgentToken(
      "the consented single-household read; scope from the token, #2419",
    ),
    "POST /api/support/consent"        -> Scoped,
    "GET /api/support/identity"        -> Scoped,

    // ── UsageRoutes ────────────────────────────────────────────────────────────────────────────
    "GET /api/usage/config"                 -> NoTenancy(
      "compile-time constants (bucket tiers / retention horizons)",
    ),
    "GET /api/usage/traffic"                -> Scoped,
    "GET /api/usage/series"                 -> Scoped,
    "GET /api/usage/series/batch"           -> Scoped,
    "GET /api/devices/{}/recent-apexes"     -> Scoped,
    "GET /api/profiles/{}/usage-by-app"     -> Scoped,
    "GET /api/profiles/{}/usage/app/weekly" -> Scoped,
  )

  // ── the scanner ──────────────────────────────────────────────────────────────────────────────

  /**
   * One route as read out of the source: its key, whether it names an entity, and its handler text.
   */
  final case class ScannedRoute(key: String, file: String, entityParam: Boolean, handler: String) {
    def checkers: Set[String] = Checkers.filter(handler.contains)
  }

  /** Walk up from the test's working directory to the repo root (the dir containing `api/src`). */
  private val repoRoot: Path = {
    var cur: Path = Paths.get(sys.props.getOrElse("user.dir", ".")).toAbsolutePath
    while cur != null && !Files.isDirectory(cur.resolve("api/src")) do cur = cur.getParent
    if cur == null then sys.error("could not locate repo root (api/src) from user.dir")
    cur
  }

  private def routeFiles: List[Path] = {
    val dir = repoRoot.resolve("api/src/routes")
    Files
      .walk(dir)
      .iterator()
      .asScala
      .filter(p => Files.isRegularFile(p) && p.toString.endsWith(".scala"))
      .toList
      .sortBy(_.toString)
  }

  private val RouteStart    = """Method\.(GET|POST|PUT|PATCH|DELETE)\s*/""".r
  // A path segment is either a string literal, a typed capture (`long("id")`), or `trailing`.
  private val Segment       = """"([^"]+)"|(\w+)\("[^"]*"\)|\b(trailing)\b""".r
  // An ENTITY param — the caller names the row. `trailing` (the SPA asset fallback) is not one.
  private val EntityCapture = """\b(long|string|int|uuid)\("""".r
  // A definition starting in column 0 — the end of the last route's handler in a file.
  private val TopLevel      = """(?m)^\S""".r

  private[feature] def scan: List[ScannedRoute] = routeFiles.flatMap { p =>
    val src       = Files.readString(p)
    val name      = p.getFileName.toString
    val starts    = RouteStart.findAllMatchIn(src).map(_.start).toList
    // Offsets where a line begins in column 0 — i.e. a top-level definition closes the enclosing
    // `Routes(...)` block. Computed once per file over the WHOLE source: searching a `substring(s)`
    // instead would make offset 0 a line start and match the route's own (indented) position.
    val topLevels = TopLevel.findAllMatchIn(src).map(_.start).toList
    starts.zipWithIndex.map { case (s, i) =>
      // The handler runs to the next route, or to the first top-level definition after it —
      // without the latter bound, the LAST route in a file swallows every trailing helper `def`
      // and inherits their identifiers, which would make the choke-point check vacuously true.
      val nextRoute = starts.lift(i + 1).getOrElse(src.length)
      val nextTop   = topLevels.find(_ > s).getOrElse(src.length)
      val end       = math.min(nextRoute, nextTop)
      val seg       = src.substring(s, end)
      val arrow     = seg.indexOf("->")
      val pattern   = if arrow > 0 then seg.substring(0, arrow) else seg
      val handler   = if arrow > 0 then seg.substring(arrow) else ""
      val flat      = pattern.split("\\s+").mkString(" ").trim
      val method    = flat.stripPrefix("Method.").takeWhile(_.isLetter)
      val segments  = Segment
        .findAllMatchIn(flat.dropWhile(_ != '/'))
        .map(m =>
          if m.group(1) != null then m.group(1)
          else if m.group(3) != null then "{*}"
          else "{}",
        )
        .toList
      ScannedRoute(
        key = s"$method /${segments.mkString("/")}",
        file = name,
        entityParam = EntityCapture.findFirstIn(flat).isDefined,
        handler = handler,
      )
    }
  }

  // Two files can declare the same route key (OpenApiRoutes declares its pair twice, once per
  // Routes object). Keep one per key; the handlers are equivalent for this analysis.
  private[feature] def scannedByKey: Map[String, ScannedRoute] =
    scan.groupBy(_.key).view.mapValues(_.head).toMap

  // ── the tests ────────────────────────────────────────────────────────────────────────────────

  def spec = suite("Multi-tenant route census — the structural isolation guard (#2563)")(
    test(
      "1 — every route in api/src/routes declares a tenancy verdict, and the census has no ghosts",
    ) {
      val live     = scannedByKey.keySet
      val declared = Census.keySet
      val missing  = (live -- declared).toList.sorted
      val ghosts   = (declared -- live).toList.sorted
      // The failure message is the point: it names exactly what the author has to write down.
      assert(missing)(
        Assertion.isEmpty ?? "routes with NO tenancy verdict — add each to Census with an explicit Tenancy",
      ) && assert(ghosts)(
        Assertion.isEmpty ?? "census entries with no live route — delete them (route renamed or removed?)",
      )
    },
    test("2 — every Scoped route that lets the caller NAME a row composes a tenancy choke point") {
      // The invariant. `Scoped` + entity param ⇒ the handler must prove the row belongs to the
      // caller's household. `ScopedTracked` entries are the audit's known-broken set and are
      // exempt until their fix chip lands (test 3 pins that they are still tracked).
      val offenders = scannedByKey.values.toList
        .filter(_.entityParam)
        .filter(r => Census.get(r.key).contains(Tenancy.Scoped))
        .filter(_.checkers.isEmpty)
        .map(r => s"${r.key}  (${r.file})")
        .sorted
      assert(offenders)(
        Assertion.isEmpty ?? (
          "Scoped routes taking an entity path param but composing NO tenancy checker " +
            s"(one of: ${Checkers.toList.sorted.mkString(", ")}). " +
            "Compose the choke point, or — if it is genuinely already broken — file an issue and " +
            "declare it ScopedTracked(<issue>)."
        ),
      )
    },
    test(
      "3 — the tracked-broken allowlist is explicit, non-empty, and every entry names an issue",
    ) {
      val tracked = Census.collect { case (k, Tenancy.ScopedTracked(n)) => k -> n }
      // Non-empty: if this ever hits zero, the audit's findings are all fixed — delete the
      // ScopedTracked case and this test with them, rather than leaving a dead escape hatch.
      assertTrue(tracked.nonEmpty) &&
      // Every tracked entry must name a plausible issue number, not 0 / a placeholder.
      assert(tracked.values.filter(_ <= 0).toList)(
        Assertion.isEmpty ?? "ScopedTracked entries must name a real tracking issue",
      ) &&
      // Every tracked entry must still be a LIVE route with a genuinely absent checker. If a fix
      // landed and forgot to delete the entry, this fails and tells them to.
      assert(
        tracked.keys.toList.sorted
          .filter(k => scannedByKey.get(k).exists(_.checkers.nonEmpty)),
      )(
        Assertion.isEmpty ?? "these routes now compose a checker — delete their ScopedTracked entry",
      )
    },
    test(
      "4 — the scan is non-vacuous: it really does see the checkers on the routes that have them",
    ) {
      // If a refactor renamed the guards, or the handler segmentation broke, test 2 would silently
      // pass with zero offenders because it saw zero checkers anywhere. Pin the opposite.
      val entityRoutes = scannedByKey.values.filter(_.entityParam).toList
      val guarded      = entityRoutes.filter(_.checkers.nonEmpty)
      assertTrue(
        entityRoutes.size > 30,
        guarded.size >= 25,
      ) &&
      // And pin three specific, known-good compositions by name, so a rename of any ONE guard is
      // caught even if the aggregate count still clears the floor.
      assertTrue(
        scannedByKey("PUT /api/profiles/{}").checkers.contains("requireProfileAccess"),
        scannedByKey("DELETE /api/schedules/{}").checkers.contains("requireScheduleInHousehold"),
        scannedByKey("DELETE /api/users/{}").checkers.contains("ownUser"),
        scannedByKey("PATCH /api/devices/{}").checkers.contains("findByMacInHousehold"),
      )
    },
    test("5 — a bare claims.hh is NOT accepted as a tenancy checker") {
      // The #2564 lesson, pinned. `POST /api/alerts/{}/approve` mentions `claims.hh` (it stamps the
      // extension grant with it) and checks NOTHING. If someone adds "claims.hh" to `Checkers` to
      // quiet the guard, this fails.
      assertTrue(!Checkers.contains("claims.hh")) &&
      assertTrue(scannedByKey("POST /api/alerts/{}/approve").handler.contains("claims.hh")) &&
      assertTrue(scannedByKey("POST /api/alerts/{}/approve").checkers.isEmpty)
    },
  )
}
