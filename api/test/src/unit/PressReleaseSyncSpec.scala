package wifihaven.api.unit

import zio.test.*

import java.nio.file.{Files, Path, Paths}

/**
 * #2233 — the CI gate that keeps the two copies of the launch release in sync.
 *
 *   - `docs/marketing/press-release.md` — the AUTHORED source of truth a human edits, carrying the
 *     internal fact-check ledger.
 *   - `web-marketing/site/press/index.html` — the PUBLISHED copy at `https://wifihaven.net/press`,
 *     which is what every press submission links to.
 *
 * Same prose, two renderings. Nothing structural stopped them from drifting, and the drifted case
 * is the bad one: the file a human reviews is not the page a journalist reads.
 *
 * ==Why this matters MORE now, not less==
 *
 * There used to be a third copy — a sendable resource with `{{token}}` fill slots — and a send-time
 * refusal that would not transmit while any token was unresolved. That path is gone (2026-08-16: no
 * target has a publishable email address, so the outreach is hand-submitted through each outlet's
 * contact form). With it went the refusal. This spec is now the ONLY automated thing standing
 * between an edit to one copy and a journalist reading the other, so it is deliberately strict:
 * every paragraph of the authored release must appear on the page, and neither may carry a
 * `[PLACEHOLDER]`.
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
  private val PagePath     = "web-marketing/site/press/index.html"

  /**
   * Any `[UPPERCASE…]` slot — an unfilled operator input, which must not survive into either copy.
   */
  private val BracketToken = """\[[A-Z][A-Z0-9_ ]+""".r

  /**
   * The authored doc's release region: from `FOR IMMEDIATE RELEASE` up to, but not including, the
   * internal fact-check ledger.
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

  /**
   * The published page's visible text, tags and comments stripped and entities unescaped. Crude on
   * purpose: it only has to answer "does this paragraph appear on the page", which is the question
   * that catches drift. Comments are stripped FIRST so nothing hidden from a reader can satisfy the
   * comparison.
   */
  private def pageText(html: String): String =
    html
      .replaceAll("(?s)<!--.*?-->", " ")
      .replaceAll("(?s)<[^>]+>", " ")
      .replace("&amp;", "&")
      .replace("&mdash;", "—")
      .replace("&nbsp;", " ")
      .replaceAll("\\s+", " ")

  def spec = suite("press release — the authored copy and the published page stay in sync")(
    test("every paragraph of the authored release appears on the published page") {
      val authored = paragraphs(authoredBody(readAll(AuthoredPath)))
      val rawPage  = readAll(PagePath)
      val page     = pageText(rawPage)
      // ONE paragraph is exempt: the links line. The page renders those URLs as anchors and says
      // "Press kit: this page." where the release names the URL, because on the page that URL is
      // where you already are — so the paragraph cannot match as text. That exemption drops the
      // beta-signup and source URLs out of the comparison, which is exactly the wrong thing to
      // leave unguarded, so they are asserted individually below.
      val exempt   = (p: String) => p.contains("Press kit:")
      val expected = authored.filterNot(exempt)
      val missing  = expected.filterNot(page.contains)
      assertTrue(
        missing.isEmpty,
        // Liveness: if the region anchors or the tag stripper broke, `expected` collapses and
        // `missing.isEmpty` would pass for free. The release is 14 paragraphs; 10 is a floor well
        // under that and well over anything a broken extractor would produce.
        expected.size > 10,
        // Exactly one paragraph may be exempt. More means the exemption started swallowing prose.
        authored.count(exempt) == 1,
        // The two URLs the exempt paragraph carries. The beta link is what the whole announcement
        // drives at and the source link is the open-source claim's evidence; a journalist follows
        // both. Asserted on the RAW page so an anchor's href counts, not only its visible text.
        rawPage.contains("app.wifihaven.net/beta"),
        rawPage.contains("github.com/wifihaven/wifihaven"),
      )
    },
    test("neither copy carries an unfilled [PLACEHOLDER]") {
      // The release is distributed BY HAND through contact forms, so there is no send-time
      // unresolved-token refusal any more. This assertion is what replaced it.
      val authored = authoredBody(readAll(AuthoredPath))
      val page     = pageText(readAll(PagePath))
      assertTrue(
        BracketToken.findFirstIn(authored).isEmpty,
        BracketToken.findFirstIn(page).isEmpty,
        // And the mustache spelling is gone too — its removal is the point of this change, so a
        // reintroduced `{{token}}` means someone restored a fill step that no longer has a filler.
        !authored.contains("{{"),
        !page.contains("{{"),
      )
    },
  )
}
