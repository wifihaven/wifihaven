package wifihaven.api.feature

import zio.test.*

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * #2176 (multi-tenant hardening, epic #2085/#622) — the REGRESSION GUARD that encodes the lesson,
 * rather than only fixing instances.
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

  def spec = suite("MultiTenantScopedReadGuardSpec (#2176)")(
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
}
