package wifihaven.api.feature

import wifihaven.api.press.PressOutreach
import zio.test.*

import java.nio.file.{Files, Path, Paths}

/**
 * #2233 — the CI gate that keeps the two copies of the launch release in sync.
 *
 * There are two files on purpose:
 *   - `docs/marketing/press-release.md` — the AUTHORED source of truth a human edits. It carries a
 *     review note and an internal fact-check ledger, and spells the operator-input slots as
 *     `[CITY]`-style tokens so a reader sees at a glance what still needs filling.
 *   - `api/resources/press/release.md` — the MACHINE-SENDABLE copy `PressOutreach` pastes below
 *     each pitch, with `{{city}}`-style fill tokens the send request resolves.
 *
 * Same prose, two spellings. Nothing structural stopped them from drifting, and a drifted pair is
 * the worst possible failure of this pair specifically: the file a human reviews is not the file
 * that reaches a journalist. So the prose equality is asserted here rather than left to care.
 *
 * The comparison is on NORMALIZED paragraphs — markdown decoration (`#` headings, `**bold**`) and
 * whitespace are the two files' legitimate differences, along with the token spelling, and each is
 * normalized away explicitly. Anything else is drift and fails.
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

  /**
   * The authored doc's `[TOKEN]` spelling → the sendable resource's `{{token}}` spelling. This map
   * is the whole allowed vocabulary of operator-input slots: a token added to one file and not
   * listed here fails the comparison, which is the point.
   */
  private val TokenSpellings: List[(String, String)] = List(
    "[FOUNDER NAME]"    -> "{{founderName}}",
    "[BETA SIGNUP URL]" -> "{{betaSignupUrl}}",
    "[PRESS KIT URL]"   -> "{{pressKitUrl}}",
  )

  /**
   * The authored doc's sendable region: everything after the review note's closing `---` fence, up
   * to (not including) the internal fact-check ledger. Both excluded parts are deliberately
   * human-only and must never reach a journalist.
   */
  private def authoredBody(raw: String): String = {
    val lines = raw.linesIterator.toList
    val start = lines.indexWhere(_.trim == "---")
    if start < 0 then sys.error(s"$AuthoredPath: expected a '---' fence closing the review note")
    val rest  = lines.drop(start + 1)
    val end   = rest.indexWhere(_.trim.startsWith("## Fact-check ledger"))
    (if end >= 0 then rest.take(end) else rest).mkString("\n").trim
  }

  /** Strip markdown decoration and the token spelling, then split into non-empty paragraphs. */
  private def paragraphs(body: String): List[String] =
    TokenSpellings
      .foldLeft(body) { case (acc, (bracket, mustache)) => acc.replace(bracket, mustache) }
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

  def spec = suite("press release — authored doc and sendable resource stay in sync (#2233)")(
    test("the two files carry the SAME prose, paragraph for paragraph") {
      val authored  = paragraphs(authoredBody(readAll(AuthoredPath)))
      val sendable  = paragraphs(PressOutreach.sendableBody(readAll(SendablePath)))
      // Report the first divergence rather than a wall of two lists — a drifted pair is usually one
      // edited paragraph, and the reviewer needs to see WHICH.
      val firstDiff =
        authored.zip(sendable).find { case (a, b) => a != b }
      assertTrue(
        firstDiff.isEmpty,
        authored.size == sendable.size,
        authored.nonEmpty,
      )
    },
    test(
      "the sendable resource carries exactly the documented fill tokens, and the authored doc none of them raw",
    ) {
      val sendableRaw = readAll(SendablePath)
      val authoredRaw = readAll(AuthoredPath)
      val tokens = PressOutreach.unresolvedTokens(PressOutreach.sendableBody(sendableRaw)).toSet
      assertTrue(
        // #2233 staging pass: `city` is gone (the dateline carries no city — this is an internet
        // product, not local news), and `date` + `founderQuote` are now literal in the copy because
        // the operator supplied both. What remains is what only the operator can fill.
        tokens == Set("founderName", "betaSignupUrl", "pressKitUrl"),
        // The authored doc uses the bracket spelling in its BODY; a stray `{{…}}` there means
        // someone pasted the sendable copy over the authored one.
        !authoredBody(authoredRaw).contains("{{"),
      )
    },
  )
}
