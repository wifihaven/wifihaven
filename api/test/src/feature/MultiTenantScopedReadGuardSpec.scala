package wifihaven.api.feature

import zio.test.*

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * #2176 (multi-tenant hardening, epic #2085/#622) — the REGRESSION GUARD that encodes the lesson,
 * rather than only fixing instances.
 *
 * TWO LAYERS, both over the REPO surface (the route surface is [[MultiTenantRouteCensusSpec]]):
 *
 *   - **Layer A — the unscoped LIST read scan** (the original #2176 guard, below). Greps all of
 *     `api/src` (widened from the routes plane by #2571) for `listAll` / `listAllIncludingGlobal` /
 *     `listAllMappings` — reads that return EVERY household's rows.
 *   - **Layer B — the SINGLE-ROW read census** (#2589, further down). Every abstract `Task[Option
 *     [_]]` / `Task[Boolean]` declaration on a `*Repo` trait carries an explicit tenancy verdict.
 *     Layer A cannot see these by construction (it matches method NAMES), and the route census
 *     cannot see them either (it only reaches routes whose PATH names a row) — which is how
 *     `NamedScheduleRepo.findByName(name: String)` backed a cross-household name-taken 409 while
 *     passing both guards. See the Layer B header for the full argument.
 *
 * The D/E scoping waves added `…ForHousehold` reads but left a residue of UNSCOPED
 * `deviceRepo.listAll` / `profileRepo.listAll` / etc. in user-facing route files, each of which
 * returns EVERY household's rows. Those are tracked for scoping by
 * [#2126](https://github.com/wifihaven/wifihaven/issues/2126) (usage/analytics/push +
 * `named_schedules`) and [#2120](https://github.com/wifihaven/wifihaven/issues/2120) (ws snapshot
 * push). This guard scans `api/src` for household-relevant unscoped reads and asserts every one is
 * in the tracked allowlist below — so a NEW unscoped read (a fresh feature, or a regression on a
 * since-scoped file) fails the build until it is either scoped (`…ForHousehold`) or consciously
 * added here with its tracking issue.
 *
 * The set is allowed to SHRINK freely (⊆, not ==): as #2126/#2120 scope these reads the scan finds
 * fewer, which stays a subset — the guard never blocks the tracked fixes from landing.
 *
 * #2257 shrank it: every user-facing `deviceRepo.listAll` / `profileRepo.listAll` (the #2120/#2251
 * leak class) is now household-scoped (`listAllForHousehold`) in the request handlers + SPA push
 * builders, and the bare cross-tenant `listAll` on devices/profiles was REMOVED entirely (no
 * replacement method) — the handful of genuinely all-tenant reads (background rollup/learn fibers,
 * the loopback debug dump, `TimeStatusService`'s per-profile paths) are now explicit
 * `foreach(profileRepo.distinctHouseholds)(listAllForHousehold)` loops / `listForProfile` reads, so
 * a cross-tenant read simply cannot be spelled in a route. Only the still-tracked
 * non-device/profile reads remain in the allowlist.
 *
 * #2571 WIDENED the scan from `api/src/routes` to ALL of `api/src`. Until then the guard could only
 * see the request plane, so an unscoped read reintroduced in a background job, the policy plane, or
 * a service would not have tripped it. That was safe to widen only once the last cross-tenant
 * `listAll` / `listAllIncludingGlobal` on a TENANT repo was deleted (#2571 removed `UserRepo`,
 * `RouterRepo`, `TimeLimitRepo`, `ProfileRepo`'s), leaving `appRepo.listAll` — the template-global
 * app catalog — as the only match anywhere in `api/src`.
 *
 * Scope of the scan:
 *   - Every `.scala` file under `api/src`.
 *   - Only the household-relevant list reads that HAVE a `…ForHousehold` sibling: `listAll`,
 *     `listAllIncludingGlobal`, `listAllMappings`. `appRepo.listAll` is EXEMPT — the app catalog is
 *     template-global by design (§0.2), not a tenant table — as is `listAllHostMappings`.
 *     `listAllMappings` itself no longer exists on any repo (#2532 removed `UserProfileRepo`'s),
 *     and post-#2571 neither does `listAllIncludingGlobal` — both tokens are named here for the
 *     scan's own history, and so a reintroduction under the old name still trips.
 */
object MultiTenantScopedReadGuardSpec extends ZIOSpecDefault {

  /** Walk up from the test's working directory to the repo root (the dir containing `api/src`). */
  private val repoRoot: Path = {
    var cur: Path = Paths.get(sys.props.getOrElse("user.dir", ".")).toAbsolutePath
    while cur != null && !Files.isDirectory(cur.resolve("api/src")) do cur = cur.getParent
    if cur == null then sys.error("could not locate repo root (api/src) from user.dir")
    cur
  }

  // #2571: all of `api/src`, not just the routes plane — jobs, services and the policy plane are
  // equally capable of spelling a cross-tenant read.
  private def sourceFiles: List[Path] = {
    val dir = repoRoot.resolve("api/src")
    if !Files.isDirectory(dir) then Nil
    else
      Files
        .walk(dir)
        .iterator()
        .asScala
        .filter(p => Files.isRegularFile(p) && p.toString.endsWith(".scala"))
        .toList
  }

  // Strip `//` line comments so a comment mentioning `profileRepo.listAll` (SpaPush has one) is not
  // scanned as a call site. Block comments are rare in these files and harmless (a match only ever
  // ADDS a token already allowlisted for that file).
  private def stripLineComments(src: String): String =
    src.linesIterator
      .map(l => l.indexOf("//") match { case -1 => l; case i => l.take(i) })
      .mkString("\n")

  // #2571: a token inside a string literal is not a call site. Widening the scan to all of `api/src`
  // brought `Repos.scala`'s `DbMetrics.timed("app.listAll")` into range — a metric NAME, whose
  // `app.` prefix is not the exempt `appRepo` receiver. Strip literals rather than exempt a receiver
  // that does not exist, so the exemption list keeps naming only real repos.
  private def stripStringLiterals(src: String): String =
    """"(?:[^"\\\n]|\\.)*"""".r.replaceAllIn(src, "\"\"")

  // `<receiver>.listAll` / `.listAllIncludingGlobal` / `.listAllMappings`, NOT followed by another
  // identifier char — so `listAllForHousehold`, `listAllIncludingGlobalForHousehold` and
  // `listAllHostMappings` do NOT match. Captures `receiver.method`.
  private val UnscopedRead =
    """([A-Za-z_][A-Za-z0-9_]*)\.(listAllIncludingGlobal|listAllMappings|listAll)(?![A-Za-z])""".r

  // Receivers whose `listAll` is legitimately global (no tenant dimension) — the app catalog (§0.2).
  private val GlobalCatalogReceivers = Set("appRepo")

  /**
   * file name → the household-relevant unscoped read tokens it is KNOWN to contain today. Each is
   * tracked for scoping by #2126 (usage/analytics/push + named_schedules) or #2120 (ws push).
   */
  // #2568 scoped the DashboardNow app-limit read and removed `AppTimeLimitRepo.listAll` outright
  // (the #2257 device/profile precedent). #2532 did the same for the user↔profile mapping read:
  // `listMappingsForHousehold(claims.hh)` replaced it and the bare `listAllMappings` no longer
  // exists on `UserProfileRepo`. #2126 already scoped `scheduleRepo.listAll` (named_schedules) to
  // `listAllForHousehold`. #2257 scoped every user-facing `deviceRepo.listAll` / `profileRepo.listAll`
  // and removed the bare cross-tenant methods entirely. Every previously-tracked unscoped read is now
  // either scoped or removed, so the allowlist is EMPTY — a cross-tenant read can no longer be spelled
  // in a route at all. See the "non-vacuous" test below for how the scan proves it still matches.
  private val Allowlist: Map[String, Set[String]] = Map.empty

  /**
   * The scan pipeline, ONE copy. The liveness anchor below asserts on this same function's output
   * (pre-exemption), so a strip step added HERE is automatically covered by the anchor. Keep new
   * strip steps in this function — one bolted onto `householdRelevantReads` instead would sit
   * outside the anchor's reach, which is the drift this collapse exists to prevent.
   *
   * Literals are stripped FIRST, then comments: `stripLineComments` truncates at the first `//`,
   * including one inside a string, so `val u = "https://x"; someRepo.listAll` would lose its call
   * site if comments went first. Latent while the scan was routes-only; all of `api/src` carries
   * URL literals (Stripe, Plain, blocklist fetch), so the order is load-bearing now.
   */
  private def rawReads(src: String): List[String] =
    UnscopedRead
      .findAllMatchIn(stripLineComments(stripStringLiterals(src)))
      .map(m => s"${m.group(1)}.${m.group(2)}")
      .toList

  private def householdRelevantReads(src: String): Set[String] =
    rawReads(src).filterNot(tok => GlobalCatalogReceivers.contains(tok.takeWhile(_ != '.'))).toSet

  private val layerA = suite("Layer A — unscoped LIST reads in api/src (#2176, widened by #2571)")(
    test("every household-relevant unscoped read in api/src is in the tracked allowlist") {
      val offenders =
        sourceFiles.flatMap { p =>
          val file    = p.getFileName.toString
          val reads   = householdRelevantReads(new String(Files.readAllBytes(p)))
          val allowed = Allowlist.getOrElse(file, Set.empty)
          (reads -- allowed).map(tok => s"$file: $tok")
        }.toSet
      assertTrue(offenders.isEmpty)
    },
    // Prove the scan actually SEES the reads it is guarding — otherwise an empty result would pass
    // vacuously if the regex ever silently stopped matching. #2532 scoped the last tracked route-file
    // read (`userProfileRepo.listAllMappings`), so `Allowlist` is now empty and there is no remaining
    // real call site to anchor on — this asserts against a FIXTURE string instead, matching the regex
    // directly rather than through `sourceFiles`.
    test("the scan is non-vacuous — the regex still matches its target shape") {
      val fixture = "up.listAllMappings"
      assertTrue(householdRelevantReads(fixture).contains("up.listAllMappings"))
    },
    // #2571: the widened scan must not read a metric NAME as a call site — `DbMetrics.timed(
    // "app.listAll")` in Repos.scala is the case that forced this, and it must stay unflagged while
    // the identical token OUTSIDE a literal still trips.
    test("a token inside a string literal is not a call site") {
      assertTrue(householdRelevantReads("""DbMetrics.timed("app.listAll")(q)""").isEmpty) &&
      assertTrue(householdRelevantReads("someRepo.listAll").contains("someRepo.listAll"))
    },
    // #2571: anchor the scan to the REAL tree, not only to fixture strings. With `Allowlist` empty
    // and no offender left in `api/src`, the main test above is satisfied by an empty set — and an
    // empty set is also what a DEAD scan returns (a `repoRoot` that resolves elsewhere, a build CWD
    // change, a walk that yields nothing). These two assertions are what distinguish "clean" from
    // "not looking": files were actually walked, and the raw regex still finds a known-present token
    // in them BEFORE the exemption filter runs. `appRepo.listAll` is that token — it is the one
    // match left anywhere in `api/src` (the template-global catalog, §0.2), so it doubles as the
    // liveness anchor and as proof the exemption is not dead config.
    test("the scan is anchored to real files — it walks api/src and still matches there") {
      val srcs      = sourceFiles.map(p => new String(Files.readAllBytes(p)))
      val rawTokens = srcs.flatMap(rawReads)
      assertTrue(srcs.nonEmpty) &&
      assertTrue(rawTokens.contains("appRepo.listAll"))
    },
    // The exemption for the global app catalog must actually fire (else it is dead config). Paired
    // with the anchor above: that one proves the raw scan DOES surface `appRepo.listAll`, this one
    // proves the filter then removes it — neither is meaningful without the other.
    test("appRepo.listAll (global catalog) is exempt, never flagged") {
      val flaggedAppRepo =
        sourceFiles.exists(p =>
          householdRelevantReads(new String(Files.readAllBytes(p)))
            .exists(_.startsWith("appRepo.")),
        )
      assertTrue(!flaggedAppRepo)
    },
  )

  // ══ Layer B — the SINGLE-ROW read census (#2589) ══════════════════════════════════════════════

  /**
   * #2589. The hole this closes, precisely.
   *
   * Layer A above matches method NAMES (`listAll…`). A single-row read — `findByName`, and any
   * future `findBy*` / `getBy*` / `lookupBy*` with a household-scoped sibling — never matches, so
   * it is invisible to Layer A *by construction*. [[MultiTenantRouteCensusSpec]]'s choke-point
   * invariant does not reach it either: that applies only to routes whose PATH names a row
   * (`long("id")` / `string("mac")`), and `POST /api/schedules` names its row in the request BODY
   * (`{"name": "Bedtime"}`), so it is exempt. A route could therefore read a row by a body-supplied
   * NATURAL KEY, with no household predicate, and pass both guards. That is exactly what shipped:
   * `NamedScheduleRepo.findByName(name: String)` backed the name-taken 409 on `POST /api/schedules`
   * and `PATCH /api/schedules/{id}`, answering "taken" from a row in a household the caller could
   * not see (#2572 / #2586). Found by hand, twice.
   *
   * ── Why the REPO layer, and not a third route-level layer ────────────────────────────────────
   * The tempting fix is to extend the route census to "routes that read by a body key". A source
   * scan cannot decide that: the body key lives in a JSON schema the scanner does not parse, and
   * any name-based approximation is defeated by renaming a field. The repo declaration, by
   * contrast, states the shape completely and syntactically — the arguments and the return type ARE
   * the question. So the invariant is enforced at the SOURCE of the read rather than at one of its
   * callers, and it holds for every caller (routes, services, background fibers, the ws push
   * builders) rather than the subset the census enumerates. Once no unscoped natural-key read can
   * be *declared*, no route can *call* one — the route-level hole closes without the route-level
   * scanner having to detect it. Three partly-overlapping guards with gaps between them is how this
   * hole appeared; this is the second layer of an existing guard, not a fourth surface.
   *
   * ── The invariant ────────────────────────────────────────────────────────────────────────────
   * Every abstract `Task[Option[_]]` / `Task[Boolean]` declaration on a `*Repo` trait — the
   * single-row read shape, including the `Boolean` existence probe that backs a 409, which leaks
   * the same oracle as the row itself — must appear in [[RowReadCensus]] with an explicit
   * [[RowRead]] verdict. `Scoped` requires a `HouseholdId` parameter and is checked, not trusted;
   * every other verdict must say WHY in prose a reviewer reads in the diff.
   *
   * Note what the scan keys on: the SHAPE (return type + parameter types), never the method name.
   * Renaming `findByName` to `lookupBy` or `scheduleNamed` does not evade it — the declaration
   * still matches, and the census key changes, so the rename fails test B1 until re-declared.
   * Adding an unrelated parameter does not evade it either: eligibility for the `SurrogateId`
   * exemption is a property of ALL the parameter types (test B3), not of any one of them.
   */
  enum RowRead {

    /**
     * Takes a `HouseholdId` parameter — the read is scoped in the SQL. Test B2 verifies the
     * parameter is really there rather than taking the declaration on trust.
     */
    case Scoped

    /**
     * Keyed on a globally-unique SURROGATE id, so the argument already names exactly one row across
     * every tenant and there is nothing to disambiguate; keeping a FOREIGN id out is the route's
     * choke point (`householdOf` / `requireXInHousehold` / `ownUser`), not the repo's. Test B3 pins
     * that this may only be claimed when every parameter type is genuinely of that kind — `String`
     * and `Long` keys can never take this exemption, which is what stops a `findByName` from being
     * waved through by re-declaration.
     */
    case SurrogateId(reason: String)

    /**
     * The key ITSELF resolves the household: reading this row is HOW the tenancy is determined (a
     * router bearer-token hash, a Stripe customer id, a household slug, a globally-unique email).
     * Scoping it would be circular. `reason` must name the key and why it is unambiguous.
     */
    case TenancyKey(reason: String)

    /**
     * Install-wide data with no tenancy dimension at all (the bundled blocklist catalog, the
     * template-authored app catalog, partition maintenance, press). Same meaning as the route
     * census's `InstallWide`, and the same caveat: it is a statement about the DATA, not a licence
     * to mutate it from any household.
     */
    case InstallWide(reason: String)

    /**
     * Matched the single-row READ shape but is a write returning a success `Boolean`. Declared
     * rather than filtered out by name, because "is this a read?" decided by method name is exactly
     * the kind of name-keyed judgement that produced this gap.
     */
    case Write(reason: String)

    /** Known-unscoped and tracked by `issue`, fixed by its own chip. Shrink-only (test B5). */
    case Tracked(issue: Int)
  }

  import RowRead.*

  /**
   * Types that name exactly one row across every tenant — the surrogate primary keys. A read keyed
   * ONLY on these (plus the non-identifying filter types below) may take the `SurrogateId`
   * exemption.
   *
   * `MacAddress` is deliberately ABSENT and test B3 pins that: V74 dropped `devices_mac_key`, so
   * post-multi-tenancy a bare MAC names a SET of devices across households (#2125). `String` and
   * `Long` are absent for the same reason at a higher level — an untyped natural key is precisely
   * the shape that shipped #2572.
   */
  private val SurrogateIdTypes: Set[String] = Set(
    "UserId",
    "ProfileId",
    "NamedScheduleId",
    "AlertId",
    "RouterId",
    "AppId",
    "AppTemplateId",
    "BetaRequestId",
    "BlocklistId",
    "HouseholdId",
  )

  /**
   * Types that are filter DIMENSIONS rather than row keys — a date window, an instant, a flag. They
   * narrow a read that some other parameter already keys; on their own they name nothing, so they
   * neither earn the `SurrogateId` exemption nor disqualify it.
   */
  private val NonIdentifyingTypes: Set[String] = Set(
    "Instant",
    "LocalDate",
    "LocalTime",
    "LocalDateTime",
    "ZoneId",
    "Int",
    "Boolean",
    "Duration",
  )

  /** One abstract single-row read declaration on a `*Repo` trait. */
  final case class RepoRead(repo: String, method: String, paramTypes: List[String], file: String) {
    def key: String       = s"$repo.$method"
    def isScoped: Boolean = paramTypes.contains("HouseholdId")

    /** Eligible for [[RowRead.SurrogateId]]: keyed on surrogate ids and filters, nothing else. */
    def surrogateKeyed: Boolean =
      paramTypes.nonEmpty &&
        paramTypes.exists(SurrogateIdTypes.contains) &&
        paramTypes.forall(t => SurrogateIdTypes.contains(t) || NonIdentifyingTypes.contains(t))
  }

  private def stripComments(src: String): String = {
    val noBlock = """(?s)/\*.*?\*/""".r.replaceAllIn(src, _ => "")
    noBlock.linesIterator
      .map(l => l.indexOf("//") match { case -1 => l; case i => l.take(i) })
      .mkString("\n")
  }

  private val RepoTraitStart = """(?m)^(?:sealed\s+)?trait\s+(\w+)\b""".r
  private val TopLevelStart  = """(?m)^\S""".r

  /**
   * An abstract `def` on a trait returning `Task[Option[_]]` or `Task[Boolean]`. The parameter list
   * allows one level of nested parens (a default like `= Map.empty` has none;
   * `RetentionDaysByTable` has none either), and the `Option` payload allows one level of nested
   * brackets.
   */
  private val SingleRowRead =
    """(?s)\bdef\s+(\w+)\s*(\((?:[^()]|\([^()]*\))*\))?\s*:\s*Task\[\s*(?:Option\[(?:[^\[\]]|\[[^\[\]]*\])*\]|Boolean)\s*\]""".r

  /** Split on top-level commas — `Map[String, Int]` is ONE parameter, not two. */
  private def splitParams(s: String): List[String] = {
    val out   = List.newBuilder[String]
    val cur   = new StringBuilder
    var depth = 0
    s.foreach {
      case c @ ('[' | '(')   => depth += 1; cur.append(c)
      case c @ (']' | ')')   => depth -= 1; cur.append(c)
      case ',' if depth == 0 => out += cur.toString; cur.clear()
      case c                 => cur.append(c)
    }
    out += cur.toString
    out.result().map(_.trim).filter(_.nonEmpty)
  }

  /** `mac: MacAddress = HouseholdId.Default` → `MacAddress`. Whitespace-insensitive. */
  private def paramType(p: String): String =
    p.dropWhile(_ != ':').drop(1).takeWhile(_ != '=').replaceAll("\\s+", "")

  /**
   * The scanner, factored over a SOURCE STRING so the tests can drive it with a fixture. That is
   * what makes the "does it catch the bug it was built for" question answerable in CI rather than
   * on a scratch branch — see tests B7/B8.
   */
  private[feature] def scanRepoReads(source: String, file: String): List[RepoRead] = {
    val src    = stripComments(source)
    val tops   = TopLevelStart.findAllMatchIn(src).map(_.start).toList
    val traits = RepoTraitStart.findAllMatchIn(src).filter(_.group(1).endsWith("Repo")).toList
    traits.flatMap { t =>
      val end  = tops.find(_ > t.start).getOrElse(src.length)
      val body = src.substring(t.start, end)
      SingleRowRead
        .findAllMatchIn(body)
        // Abstract declarations only: an implementation (`def f(…): Task[Option[X]] = …`) states
        // no contract of its own, and scanning both would double-count every repo.
        .filterNot(m => body.drop(m.end).dropWhile(_.isWhitespace).startsWith("="))
        .map { m =>
          val params = Option(m.group(2)).map(_.drop(1).dropRight(1)).getOrElse("")
          RepoRead(t.group(1), m.group(1), splitParams(params).map(paramType), file)
        }
        .toList
    }
  }

  private def apiSourceFiles: List[Path] = {
    val dir = repoRoot.resolve("api/src")
    Files
      .walk(dir)
      .iterator()
      .asScala
      .filter(p => Files.isRegularFile(p) && p.toString.endsWith(".scala"))
      .toList
      .sortBy(_.toString)
  }

  private[feature] lazy val repoReads: List[RepoRead] =
    apiSourceFiles.flatMap(p => scanRepoReads(Files.readString(p), p.getFileName.toString))

  /**
   * Every single-row read declared on a `*Repo` trait, keyed `"<Trait>.<method>"`, with its tenancy
   * verdict. Test B1 fails until the live declarations and this map agree EXACTLY — so a new read,
   * a rename, or a removal all force someone to write down what its tenancy is.
   */
  val RowReadCensus: Map[String, RowRead] = Map(
    // ── billing ────────────────────────────────────────────────────────────────────────────────
    "HouseholdBillingRepo.findByHousehold"        -> Scoped,
    "HouseholdBillingRepo.grantFreeForever"       -> Scoped,
    "HouseholdBillingRepo.revokeFreeForever"      -> Scoped,
    "HouseholdBillingRepo.findByStripeCustomerId" -> TenancyKey(
      "stripe_customer_id is issued by Stripe, unique per household, and stored on exactly one " +
        "household_billing row; the webhook has no other way to name the household (#2135)",
    ),

    // ── beta intake — rows that exist BEFORE any household does ────────────────────────────────
    "BetaRequestRepo.findById"         -> SurrogateId(
      "beta_requests.id; the operator approval queue is behind requireOperator (#2131)",
    ),
    "BetaRequestRepo.findByEmail"      -> InstallWide(
      "the pre-household intake queue: a beta_request belongs to no household until approval " +
        "provisions one, and the email is its unique key (#2222 dedup)",
    ),
    "BetaRequestRepo.findByInviteHash" -> TenancyKey(
      "the invite token hash IS the bearer secret, and the row it resolves is what MINTS the " +
        "household — there is no household to scope to yet (#2131)",
    ),
    "BetaRequestRepo.create"           -> Write("intake insert; returns whether the email was new"),
    "BetaRequestRepo.reject"           -> Write("operator decision stamp on a pre-household row"),
    "BetaRequestRepo.remintInvite" -> Write("operator re-issue of a pre-household invite token"),
    "BetaCohortRepo.startClock"    -> Write("install-wide flip-trigger clock latch (#2137)"),
    "BetaCohortRepo.markFlipped"   -> Write("install-wide flip-trigger latch (#2137)"),

    // ── auth / identity — the reads that DERIVE a household ────────────────────────────────────
    "UserRepo.findById"                 -> SurrogateId(
      "users.id is globally unique; ownUser is the route choke point",
    ),
    "UserRepo.findByUsername"           -> Scoped,
    "UserRepo.emailForUser"             -> Scoped,
    "UserRepo.findAdminForHousehold"    -> Scoped,
    "UserRepo.findByEmail"              -> TenancyKey(
      "users.email is globally unique (V65 kept the global key on email while widening username " +
        "to per-household), and forgot-password derives the household FROM this row (#2308)",
    ),
    "HouseholdRepo.findById"            -> Scoped,
    "HouseholdRepo.findSlugById"        -> Scoped,
    "HouseholdRepo.findIdBySlug"        -> TenancyKey(
      "households.slug is the household's own globally-unique name; this read is how a " +
        "slug-qualified login resolves which household to authenticate against (#2140)",
    ),
    "PasswordResetTokenRepo.findByHash" -> TenancyKey(
      "the token hash is a single-use bearer secret; the row it resolves carries the user, and " +
        "the household follows from that user (#2308)",
    ),
    "PasswordResetTokenRepo.markUsed"   -> Write("single-use consumption of the token above"),

    // ── profiles / schedules / devices / alerts — the household-scoped core ─────────────────────
    "UserProfileRepo.hasAccess"                    -> SurrogateId(
      "users.id and profiles.id are both globally unique, so the pair names one link row",
    ),
    "ProfileRepo.findById"                         -> SurrogateId(
      "profiles.id; requireProfileInHousehold is the route choke point",
    ),
    "ProfileRepo.householdOf"                      -> SurrogateId(
      "the choke-point probe itself — it RETURNS the household so the route can compare it",
    ),
    "ProfileRepo.getGlobalForHousehold"            -> Scoped,
    "ProfileRepo.getGlobal"                        -> Tracked(2571),
    "NamedScheduleRepo.findById"                   -> SurrogateId(
      "named_schedules.id; requireScheduleInHousehold guards the routes",
    ),
    "NamedScheduleRepo.householdOf"                -> SurrogateId(
      "the choke-point probe; returns the household to compare",
    ),
    // THE instance this whole layer exists for. Unscoped, this read answered the name-taken 409 on
    // POST /api/schedules from a row in a household the caller could not see (#2572/#2586). Test B7
    // reconstructs the pre-fix signature and asserts the guard catches it.
    "NamedScheduleRepo.findByName"                 -> Scoped,
    "HouseholdSettingsRepo.enforcementDisabled"    -> Scoped,
    "HouseholdSettingsRepo.setEnforcementDisabled" -> Scoped,
    "TimeLimitRepo.findForProfile"                 -> SurrogateId(
      "profiles.id; the limit row hangs off one profile",
    ),
    "DeviceRepo.findByMac"                         -> Scoped,
    "DeviceRepo.findByMacInHousehold"              -> Scoped,
    "DeviceRepo.findOwningHousehold"               -> TenancyKey(
      "the block-page fallback that RESOLVES a household from a bare MAC when no block-page token " +
        "is present. Deliberately not SurrogateId: post-V74 a MAC is NOT unique across households " +
        "(#2125), so for a shared MAC this pick is arbitrary — the residual the route census " +
        "records against POST /api/access-requests (#2322/#2566)",
    ),
    "AlertRepo.findById"                           -> SurrogateId(
      "alerts.id; requireAlertInHousehold guards the routes (#2564)",
    ),
    "AlertRepo.householdOf"                        -> SurrogateId(
      "the choke-point probe; returns the household to compare",
    ),
    "AlertRepo.findRecentAccessRequest"            -> Scoped,

    // ── router plane — a router belongs to exactly one household (#2106) ────────────────────────
    "RouterRepo.findById"                   -> SurrogateId(
      "routers.id; the household comes from routers.household_id",
    ),
    "RouterRepo.findByTokenHash"            -> TenancyKey(
      "the router bearer-token hash; this read is HOW the router plane learns its household " +
        "(routers.household_id), so it cannot be scoped by one (#2106)",
    ),
    "RouterRepo.findByEnrollmentTokenHash"  -> TenancyKey(
      "the single-use enrollment token hash, presented before the router has any identity; the " +
        "row it resolves is what binds the router to its household (#2106)",
    ),
    "ConnectionEventRepo.findRecentFqdnFor" -> TenancyKey(
      "keyed on router_id, and a router belongs to exactly one household (#2106), so the read " +
        "cannot cross the boundary; destIp and since only narrow it further",
    ),

    // ── install-wide catalogs (§0.2) — template-authored, no tenancy dimension ──────────────────
    "AppRepo.findById"          -> InstallWide("template-authored app catalog, #1798"),
    "AppRepo.findBySlug"        -> InstallWide("template-authored app catalog, #1798"),
    "AppRepo.findByTemplateId"  -> InstallWide("template-authored app catalog, #1798"),
    "BlocklistRepo.findMeta"    -> InstallWide("bundled blocklist catalog metadata, #2535"),
    "PressMessageRepo.findById" -> InstallWide(
      "press_messages has no household column at all — press is an install-wide inbox (#2203)",
    ),

    // ── maintenance / rollup fibers — install-wide by construction, no caller-supplied key ──────
    "PartitionRepo.ensureFuturePartitions"       -> Write(
      "DDL maintenance on partitioned tables (#808/#2053)",
    ),
    "PartitionRepo.dropExpiredPartitions"        -> Write(
      "retention DDL on partitioned tables (#808/#2053)",
    ),
    "ConnectionEventRepo.rerollConnEventsHourly" -> Write(
      "install-wide rollup re-derivation fiber",
    ),
    "ConnectionEventRepo.rerollConnEventsDaily" -> Write("install-wide rollup re-derivation fiber"),
    "RollupRepo.rerollHourly"                   -> Write("install-wide rollup re-derivation fiber"),
    "RollupRepo.rerollDaily"                    -> Write("install-wide rollup re-derivation fiber"),
    "TrafficReportRepo.earliestPeriodStart"     -> Tracked(2571),
    "TimeUsedRollupRepo.getDayForProfile"       -> SurrogateId(
      "profiles.id names one profile across every tenant; the date only narrows the row",
    ),

    // ── support ────────────────────────────────────────────────────────────────────────────────
    "SupportConsentRepo.isGranted" -> Scoped,
    "SupportConsentRepo.revoke"    -> Scoped,
  )

  /** Reads the scan sees that the census does not declare (and vice versa). */
  private[feature] def undeclared(reads: List[RepoRead]): List[String] =
    reads.map(_.key).distinct.filterNot(RowReadCensus.contains).sorted

  private[feature] def ghosts(reads: List[RepoRead]): List[String] =
    (RowReadCensus.keySet -- reads.map(_.key).toSet).toList.sorted

  /**
   * The core finding: a read whose declaration and whose signature disagree. Either it claims
   * `Scoped` without a `HouseholdId` parameter (the #2572 shape — what a re-introduced
   * `findByName(name: String)` looks like), or it carries an exemption while actually taking one
   * (dead declaration, or a verdict left behind after a fix).
   */
  private[feature] def misdeclared(reads: List[RepoRead]): List[String] =
    reads.flatMap { r =>
      RowReadCensus.get(r.key).flatMap {
        case Scoped if !r.isScoped          =>
          Some(s"${r.key} (${r.file}) — declared Scoped but takes no HouseholdId parameter")
        case v if v != Scoped && r.isScoped =>
          Some(s"${r.key} (${r.file}) — takes a HouseholdId parameter; declare it Scoped")
        case _                              => None
      }
    }.sorted

  private[feature] def reasonOf(v: RowRead): Option[String] = v match {
    case SurrogateId(r) => Some(r)
    case TenancyKey(r)  => Some(r)
    case InstallWide(r) => Some(r)
    case Write(r)       => Some(r)
    case _              => None
  }

  /** Floor for an exemption's reason, same threshold and same rationale as the route census. */
  private val MinReasonChars = 10

  private val layerB = suite("Layer B — single-row repo reads (#2589)")(
    test(
      "B1 — every single-row repo read declares a tenancy verdict, and the census has no ghosts",
    ) {
      assert(undeclared(repoReads))(
        Assertion.isEmpty ?? (
          "single-row repo reads with NO tenancy verdict. Each returns one row (or a Boolean " +
            "probe over one row) — add it to RowReadCensus. If it takes a HouseholdId it is " +
            "Scoped; if not, say why in a reason a reviewer can judge."
        ),
      ) && assert(ghosts(repoReads))(
        Assertion.isEmpty ?? "census entries with no live declaration — delete them (renamed or removed?)",
      )
    },
    test("B2 — the Scoped verdict is verified against the signature, not taken on trust") {
      assert(misdeclared(repoReads))(
        Assertion.isEmpty ?? (
          "these reads' verdicts contradict their signatures. THIS is the #2572 shape: a read " +
            "keyed on a caller-supplied natural key with no household predicate."
        ),
      )
    },
    test("B3 — the SurrogateId exemption may only be claimed by reads that are actually id-keyed") {
      // The anti-defeat check. Without it, re-introducing `findByName(name: String)` and declaring
      // it `SurrogateId("ids are globally unique")` would pass B1 and B2 — the exemption would be
      // a free-text opt-out. A `String` or `Long` key can never satisfy `surrogateKeyed`, so the
      // only remaining route is `TenancyKey` / `InstallWide`, both of which put a claim about the
      // key's uniqueness in the diff for a reviewer.
      val bogus = repoReads
        .filter { r =>
          RowReadCensus.get(r.key).exists {
            case SurrogateId(_) => !r.surrogateKeyed
            case _              => false
          }
        }
        .map(r => s"${r.key} (${r.file}) — keys: ${r.paramTypes.mkString(", ")}")
        .sorted
      assert(bogus)(
        Assertion.isEmpty ?? (
          "declared SurrogateId but not keyed on globally-unique surrogate ids. A String / Long / " +
            s"MacAddress key names a SET of rows across households. Known id types: ${SurrogateIdTypes.toList.sorted
                .mkString(", ")}"
        ),
      ) &&
      // #2125, pinned: V74 dropped `devices_mac_key`, so a bare MAC is not a surrogate key. If a
      // future edit adds it to the set, every `findByMac`-shaped read silently earns the exemption.
      assertTrue(!SurrogateIdTypes.contains("MacAddress")) &&
      assertTrue(!SurrogateIdTypes.contains("String") && !SurrogateIdTypes.contains("Long"))
    },
    test("B4 — every exemption from the household-parameter rule states a real reason") {
      val blank = RowReadCensus.toList
        .flatMap { case (k, v) => reasonOf(v).map(k -> _) }
        .filter { case (_, r) => r.trim.length < MinReasonChars }
        .map(_._1)
        .sorted
      assert(blank)(
        Assertion.isEmpty ?? (
          "these reads take an exemption with an empty or placeholder reason — say why the key " +
            "names exactly one row across every household, or give the read a HouseholdId"
        ),
      )
    },
    test("B5 — the tracked-unscoped set is explicit, shrink-only, and names real issues") {
      val tracked = RowReadCensus.collect { case (k, Tracked(n)) => k -> n }
      // Opened at 2 (both #2571's dead unscoped reads). Shrink-only, and deliberately not
      // `nonEmpty` — the last fix, the one that empties it, must not read as a red build.
      assertTrue(tracked.size <= 2) &&
      assert(tracked.values.filter(_ <= 0).toList)(
        Assertion.isEmpty ?? "Tracked entries must name a real tracking issue",
      ) &&
      assert(
        tracked.keys.toList.sorted.filter(k => repoReads.exists(r => r.key == k && r.isScoped)),
      )(
        Assertion.isEmpty ?? "these reads are now scoped — delete their Tracked entry",
      )
    },
    test("B6 — the scan is non-vacuous: it sees the live repo surface and its scoped reads") {
      // A static scan's characteristic failure is going VACUOUSLY green — the regex stops matching,
      // reports zero offenders, and that reads as "all clear" (#2546's lesson). Floors, not
      // equalities, so adding a repo method never fails this.
      val scoped = repoReads.count(_.isScoped)
      assertTrue(
        repoReads.size >= 40,
        scoped >= 10,
        repoReads.map(_.repo).distinct.size >= 10,
      )
    },
    test("B7 — it catches the KNOWN instance: the pre-#2586 findByName") {
      // The bug this guard exists for, reconstructed verbatim as a fixture. A guard that cannot be
      // SHOWN to catch the bug it was built for is not evidence of anything.
      val preFix =
        """trait NamedScheduleRepo {
          |  def listAllForHousehold(household: HouseholdId): Task[List[NamedSchedule]]
          |  def findByName(name: String): Task[Option[NamedSchedule]]
          |}
          |""".stripMargin
      val reads  = scanRepoReads(preFix, "fixture-pre-2586.scala")
      val read   = reads.find(_.method == "findByName")
      assertTrue(read.isDefined) &&
      // Seen by the scan, and seen as UNSCOPED — the two halves the old guards each missed.
      assertTrue(read.exists(!_.isScoped)) &&
      // And it fails the census: the live declaration is `findByName(name, household)`, so the
      // unscoped signature contradicts the `Scoped` verdict on record.
      assertTrue(misdeclared(reads).exists(_.contains("NamedScheduleRepo.findByName"))) &&
      // It cannot buy its way out via the id exemption either.
      assertTrue(read.exists(!_.surrogateKeyed))
    },
    test("B8 — it catches a NEW instance of the same shape, under any name") {
      // Not the one method by name. A fresh repo, fresh method names, both read shapes — the
      // row-returning read and the Boolean existence probe that backs the very same 409.
      val planted =
        """trait WidgetRepo {
          |  def lookupByLabel(label: String): Task[Option[Widget]]
          |  def labelTaken(label: String): Task[Boolean]
          |  def widgetNamed(name: String): Task[Option[Widget]]
          |  def forHousehold(household: HouseholdId, label: String): Task[Option[Widget]]
          |}
          |""".stripMargin
      val reads   = scanRepoReads(planted, "fixture-planted.scala")
      assertTrue(reads.size == 4) &&
      // All three unscoped shapes are undeclared offenders, whatever they are called — the scan
      // keys on the SIGNATURE, so renaming is not an escape.
      assertTrue(
        undeclared(reads).contains("WidgetRepo.lookupByLabel"),
        undeclared(reads).contains("WidgetRepo.labelTaken"),
        undeclared(reads).contains("WidgetRepo.widgetNamed"),
      ) &&
      assertTrue(
        reads.filter(_.method != "forHousehold").forall(r => !r.isScoped && !r.surrogateKeyed),
      ) &&
      // And the scoped sibling is seen as scoped — the guard is not simply flagging everything,
      // which is what a "no false positives" claim has to be able to fail on.
      assertTrue(reads.find(_.method == "forHousehold").exists(_.isScoped))
    },
    test("B9 — legitimate shapes are NOT flagged: id-keyed, household-keyed, and filtered reads") {
      // The false-positive half. If these tripped the guard, the reflex would become "add it to the
      // allowlist", which is how an allowlist stops meaning anything.
      val legit =
        """trait ThingRepo {
          |  def findById(id: ProfileId): Task[Option[Thing]]
          |  def findForHousehold(household: HouseholdId): Task[Option[Thing]]
          |  def dayFor(id: ProfileId, date: LocalDate): Task[Option[Thing]]
          |  def counts(household: HouseholdId): Task[Map[String, Int]]
          |  def rows(household: HouseholdId): Task[List[Thing]]
          |}
          |""".stripMargin
      val reads = scanRepoReads(legit, "fixture-legit.scala")
      // `counts` / `rows` are not single-row reads and are outside this layer entirely.
      assertTrue(reads.map(_.method).toSet == Set("findById", "findForHousehold", "dayFor")) &&
      assertTrue(reads.find(_.method == "findById").exists(_.surrogateKeyed)) &&
      // A filter dimension alongside a surrogate id does not disqualify the exemption.
      assertTrue(reads.find(_.method == "dayFor").exists(_.surrogateKeyed)) &&
      assertTrue(reads.filter(_.method == "findForHousehold").forall(_.isScoped))
    },
  )

  def spec = suite("MultiTenantScopedReadGuardSpec (#2176, #2589)")(layerA, layerB)
}
