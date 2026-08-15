package wifihaven.api.unit

import wifihaven.api.press.PressOutreach
import zio.test.*

import java.nio.file.{Files, Path, Paths}

/**
 * #2233 — the CI gate that keeps the THREE copies of the launch release in sync.
 *
 * They exist for different readers:
 *   - `docs/marketing/press-release.md` — the AUTHORED source of truth a human edits. It carries a
 *     review note and an internal fact-check ledger, and spells the operator-input slots as
 *     `[DATE]`-style tokens so a reader sees at a glance what still needs filling.
 *   - `api/resources/press/release.md` — the MACHINE-SENDABLE copy `PressOutreach` pastes below
 *     each pitch, with `{{date}}`-style fill tokens the send request resolves.
 *   - `web-marketing/site/press/index.html` — the PUBLISHED copy on wifihaven.net/press.
 *
 * Same prose, three renderings. Nothing structural stopped them from drifting, and a drifted set is
 * the worst possible failure of this set specifically: the file a human reviews is not the file
 * that reaches a journalist, and neither is the page the journalist is pointed at.
 *
 * ==The normalization is deliberately ONE-SIDED==
 *
 * `[DATE]` → `{{date}}` is applied to the AUTHORED side only. Applying it to both — the first
 * version of this spec — means a literal `[DATE]` accidentally left in the SENDABLE resource is
 * rewritten to `{{date}}` and compares equal. Nothing downstream catches that either:
 * `PressOutreach.unresolvedTokens` matches `{{…}}` only, so the send-refusal never fires and the
 * brackets reach a journalist — #2677 one layer down. The sendable resource is therefore also
 * asserted to carry no bracket token at all.
 */
object PressReleaseSyncSpec extends ZIOSpecDefault {

  /** Walk up from the test's working directory to the repo root (the dir containing `api/src`). */
  private val repoRoot: Path = {
    var cur: Path = Paths.get(sys.props.getOrElse("user.dir", ".")).toAbsolutePath
    while cur != null && !Files.isDirectory(cur.resolve("api/src")) do cur = cur.getParent
    if cur == null then sys.error("could not locate repo root (api/src) from user.dir")
    cur
  }

  private def readAll(rel: String): String =
    new String(Files.readAllBytes(repoRoot.resolve(rel)), "UTF-8")

  private val AuthoredPath = "docs/marketing/press-release.md"
  private val SendablePath = "api/resources/press/release.md"
  private val PagePath     = "web-marketing/site/press/index.html"

  /**
   * The authored doc's `[TOKEN]` spelling → the sendable resource's `{{token}}` spelling. This map
   * is the whole allowed vocabulary of operator-input slots: a token added to one file and not
   * listed here fails the comparison, which is the point.
   */
  private val TokenSpellings: List[(String, String)] = List(
    "[DATE]"            -> "{{date}}",
    "[FOUNDER NAME]"    -> "{{founderName}}",
    "[BETA SIGNUP URL]" -> "{{betaSignupUrl}}",
    "[PRESS KIT URL]"   -> "{{pressKitUrl}}",
  )

  /** Any `[UPPERCASE…]` slot — what must never survive into a sendable or published copy. */
  private val BracketToken = """\[[A-Z][A-Z0-9_ ]+""".r

  /**
   * The authored doc's sendable region: from the `FOR IMMEDIATE RELEASE` line (the first line of
   * the release proper) up to, but not including, the internal fact-check ledger.
   *
   * Anchored on the release's own first line rather than on "the first `---`": a horizontal rule or
   * YAML front matter added above the review note would silently shift a fence-based anchor and
   * change the compared region instead of failing.
   */
  private def authoredBody(raw: String): String = {
    val lines = raw.linesIterator.toList
    val start = lines.indexWhere(_.trim == "FOR IMMEDIATE RELEASE")
    if start < 0 then sys.error(s"$AuthoredPath: no 'FOR IMMEDIATE RELEASE' line")
    val rest  = lines.drop(start)
    val end   = rest.indexWhere(_.trim.startsWith("## Fact-check ledger"))
    if end < 0 then sys.error(s"$AuthoredPath: no '## Fact-check ledger' section to stop at")
    rest.take(end).mkString("\n").trim
  }

  /** Strip markdown decoration and collapse whitespace, then split into non-empty paragraphs. */
  private def paragraphs(body: String): List[String] =
    body
      .split("\n\\s*\n")
      .toList
      .map(p =>
        p.linesIterator
          .map(_.trim.replaceFirst("^#{1,6}\\s+", "").replace("**", ""))
          .mkString(" ")
          .replaceAll("\\s+", " ")
          .trim,
      )
      // A bare `---` is a markdown horizontal rule (the authored doc uses one to fence off the
      // internal ledger). It is decoration, not prose.
      .filter(p => p.nonEmpty && !p.forall(_ == '-'))

  private def authoredParagraphs: List[String] = {
    val translated = TokenSpellings.foldLeft(authoredBody(readAll(AuthoredPath))) {
      case (acc, (bracket, mustache)) => acc.replace(bracket, mustache)
    }
    paragraphs(translated)
  }

  private def sendableParagraphs: List[String] =
    paragraphs(PressOutreach.sendableBody(readAll(SendablePath)))

  /**
   * The published page's visible text, tags stripped and entities unescaped. Crude on purpose: it
   * only has to be good enough to ask "does this paragraph appear on the page", which is the
   * question that catches drift.
   */
  private def pageText(html: String): String =
    html
      .replaceAll("(?s)<!--.*?-->", " ")
      .replaceAll("(?s)<[^>]+>", " ")
      .replace("&amp;", "&")
      .replace("&mdash;", "—")
      .replace("&nbsp;", " ")
      .replaceAll("\\s+", " ")

  def spec = suite("press release — the authored, sendable and published copies stay in sync")(
    test("authored and sendable carry the SAME prose, paragraph for paragraph") {
      val authored  = authoredParagraphs
      val sendable  = sendableParagraphs
      // Report the first divergence rather than a wall of two lists — a drifted pair is usually one
      // edited paragraph, and the reviewer needs to see WHICH.
      val firstDiff = authored.zip(sendable).find { case (a, b) => a != b }
      assertTrue(
        firstDiff.isEmpty,
        // zip() truncates to the shorter list, so the size check is what catches an added or
        // deleted paragraph that the pairwise scan would otherwise never reach.
        authored.size == sendable.size,
        authored.nonEmpty,
      )
    },
    test("the sendable resource carries the documented fill tokens and NO bracket slots") {
      val sendableBody = PressOutreach.sendableBody(readAll(SendablePath))
      assertTrue(
        PressOutreach.unresolvedTokens(sendableBody).toSet ==
          Set("date", "founderName", "betaSignupUrl", "pressKitUrl"),
        // The one-sided normalization above is what makes this necessary: a stray `[DATE]` here
        // would be invisible to the prose comparison AND to the send-time unresolved-token guard.
        BracketToken.findFirstIn(sendableBody).isEmpty,
        // And the authored doc's BODY uses the bracket spelling — a `{{…}}` there means someone
        // pasted the sendable copy over the authored one.
        !authoredBody(readAll(AuthoredPath)).contains("{{"),
      )
    },
    test("the published press page carries the release prose, and no unfilled slot") {
      val page         = pageText(readAll(PagePath))
      // Two paragraphs legitimately differ on the page and are checked separately below: the quote
      // (attributed by role, so the page needs no operator input to publish) and the links line
      // (real URLs on a page, tokens in the sendable copy). Everything else must appear verbatim.
      val tokenBearing = Set("{{founderName}}", "{{betaSignupUrl}}", "{{pressKitUrl}}")
      val mustAppear   =
        sendableParagraphs
          .filterNot(p => tokenBearing.exists(p.contains))
          .filterNot(_.contains("{{date}}"))
      val missing      = mustAppear.filterNot(page.contains)
      assertTrue(
        missing.isEmpty,
        mustAppear.size > 5, // liveness: if the extraction broke, this is what fails
        // The marketing CD job refuses to deploy a page carrying one of these; assert it here too
        // so the failure lands in the API suite on the PR rather than only at deploy time.
        BracketToken.findFirstIn(page).isEmpty,
      )
    },
  )
}
