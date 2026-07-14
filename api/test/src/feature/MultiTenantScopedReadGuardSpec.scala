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
 * push). This guard scans `api/src/routes` for household-relevant unscoped reads and asserts every
 * one is in the tracked allowlist below — so a NEW unscoped read in a route (a fresh feature, or a
 * regression on a since-scoped file) fails the build until it is either scoped (`…ForHousehold`) or
 * consciously added here with its tracking issue.
 *
 * The set is allowed to SHRINK freely (⊆, not ==): as #2126/#2120 scope these reads the scan finds
 * fewer, which stays a subset — the guard never blocks the tracked fixes from landing.
 *
 * Scope of the scan (deliberately narrow, to stay low-false-positive):
 *   - Only the `.scala` files in `api/src/routes` (the user-facing plane; the snapshot/policy plane
 *     already uses `listAllIncludingGlobalForHousehold`).
 *   - Only the household-relevant list reads that HAVE a `…ForHousehold` sibling: `listAll`,
 *     `listAllIncludingGlobal`, `listAllMappings`. `appRepo.listAll` is EXEMPT — the app catalog is
 *     template-global by design (§0.2), not a tenant table — as is `listAllHostMappings`.
 */
object MultiTenantScopedReadGuardSpec extends ZIOSpecDefault {

  /** Walk up from the test's working directory to the repo root (the dir containing `api/src`). */
  private val repoRoot: Path = {
    var cur: Path = Paths.get(sys.props.getOrElse("user.dir", ".")).toAbsolutePath
    while cur != null && !Files.isDirectory(cur.resolve("api/src")) do cur = cur.getParent
    if cur == null then sys.error("could not locate repo root (api/src) from user.dir")
    cur
  }

  private def routeFiles: List[Path] = {
    val dir = repoRoot.resolve("api/src/routes")
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
  private val Allowlist: Map[String, Set[String]] = Map(
    // #2126 — SPA dashboard "now" view: device/profile/app-limit reads.
    "DashboardNowRoutes.scala" -> Set(
      "deviceRepo.listAll",
      "profileRepo.listAll",
      "appTimeLimitRepo.listAll",
    ),
    // #2126 — admin debug surface enumerates all devices.
    "DebugRoutes.scala"        -> Set("deviceRepo.listAll"),
    // #2126 — user↔profile mappings; today filtered by the scoped `users` list it is joined against,
    // so not an active leak, but the read itself is unscoped (defense-in-depth follow-up).
    "Routes.scala"             -> Set("userProfileRepo.listAllMappings"),
    // #2126 — `named_schedules` has no household_id column yet; GET /api/schedules is unscoped.
    "ScheduleRoutes.scala"     -> Set("scheduleRepo.listAll"),
    // #2120 / #2126 — the SPA-ws push builders read the whole fleet.
    "SpaPush.scala"            -> Set("profileRepo.listAll", "deviceRepo.listAll"),
    // #2126 — the usage/analytics endpoints scope by mac-resolution in a later wave (see #2174).
    "UsageRoutes.scala"        -> Set("deviceRepo.listAll", "profileRepo.listAll"),
  )

  private def householdRelevantReads(src: String): Set[String] =
    UnscopedRead
      .findAllMatchIn(stripLineComments(src))
      .map(m => s"${m.group(1)}.${m.group(2)}")
      .filterNot(tok => GlobalCatalogReceivers.contains(tok.takeWhile(_ != '.')))
      .toSet

  def spec = suite("MultiTenantScopedReadGuardSpec (#2176)")(
    test("every household-relevant unscoped read in api/src/routes is in the tracked allowlist") {
      val offenders =
        routeFiles.flatMap { p =>
          val file    = p.getFileName.toString
          val reads   = householdRelevantReads(new String(Files.readAllBytes(p)))
          val allowed = Allowlist.getOrElse(file, Set.empty)
          (reads -- allowed).map(tok => s"$file: $tok")
        }.toSet
      assertTrue(offenders.isEmpty)
    },
    // Prove the scan actually SEES the reads it is guarding — otherwise an empty result would pass
    // vacuously if the regex ever silently stopped matching.
    test("the scan is non-vacuous — it finds the known tracked reads") {
      val all =
        routeFiles.flatMap(p => householdRelevantReads(new String(Files.readAllBytes(p)))).toSet
      assertTrue(all.contains("scheduleRepo.listAll"), all.contains("deviceRepo.listAll"))
    },
    // The exemption for the global app catalog must actually fire (else it is dead config).
    test("appRepo.listAll (global catalog) is exempt, never flagged") {
      val flaggedAppRepo =
        routeFiles.exists(p =>
          householdRelevantReads(new String(Files.readAllBytes(p)))
            .exists(_.startsWith("appRepo.")),
        )
      assertTrue(!flaggedAppRepo)
    },
  )
}
