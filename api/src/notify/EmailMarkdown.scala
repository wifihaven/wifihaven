package wifihaven.api.notify

import scala.util.matching.Regex

/**
 * #2677 — the ONE renderer for a plain-text/markdown email body into the HTML part, shared by the
 * press responder (`PressResponder.htmlBody`) and the press outreach composer
 * (`PressOutreach.paragraphs`). Both grew the same shape independently — escape, split on blank
 * lines, `<br>` the single newlines — and so both shipped the same defect: the agent writes
 * markdown and the recipient reads literal `**` (the reported bug was in a reply that reached a
 * journalist). Per docs/process/single-source-of-truth.md this is the single implementation; do not
 * re-derive it at a call site.
 *
 * ==Security model — escape first, then render a closed allowlist==
 *
 * The input is UNTRUSTED. For the responder it is the press agent's output, which is derived from a
 * journalist's message (see #2667 on adjacent-text injection), so it must be treated as though the
 * sender wrote it directly. The order below is the whole security property and must not be
 * inverted:
 *
 *   1. HTML-escape `&`, `<`, `>` — after this step the text provably contains no markup;
 *   1. then render a small, fixed set of markdown constructs over the ESCAPED text, emitting only
 *      literal, attribute-free tags this file spells out: `<p> <br> <strong> <em> <code> <ul> <ol>
 *      <li>`.
 *
 * Because every tag is a constant in this file and no input ever reaches an attribute position,
 * there is no path by which the input can become markup. That is also why this does NOT use a
 * general-purpose markdown library: those emit raw HTML from their input, which is exactly the
 * property being excluded here.
 *
 * ==No anchors, deliberately==
 *
 * Nothing renders an `<a href>`. A press reply is sent FROM a DKIM-signing `@wifihaven.net`
 * address, so a clickable link whose visible text and destination differ is a first-class phishing
 * primitive in the hands of an injected agent (#2453 / #2667). Instead a `[text](url)` link with a
 * safe scheme ([[SafeSchemes]]) is unwrapped to `text (url)` — the URL is visible, so what the
 * reader sees is where they would go, and any auto-linking is the mail client's own, applied to the
 * literal URL. A link with any other scheme (`javascript:`, `data:`, …) is left wholly literal:
 * inert, and visible in the correspondence log as a signal that something odd was generated.
 *
 * Anything not listed above stays literal, by design.
 */
object EmailMarkdown {

  /** The only URL schemes a `[text](url)` link is unwrapped for. Everything else stays literal. */
  val SafeSchemes: List[String] = List("http://", "https://", "mailto:")

  /**
   * Render `markdown` into the HTML email body. Blank lines separate paragraphs; single newlines
   * become `<br>`; a block whose every line is a list item becomes `<ul>`/`<ol>`. Empty input
   * renders `<p></p>` (an email body is never the empty string).
   */
  def render(markdown: String): String = {
    val normalized = markdown.replace("\r\n", "\n").replace('\r', '\n')
    val blocks     = escape(normalized)
      .split("\n{2,}")
      .toList
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(renderBlock)
    if blocks.isEmpty then "<p></p>" else blocks.mkString("\n")
  }

  /**
   * The escape the rest of this file depends on: after it, the text contains no `<`, `>` or bare
   * `&`, so every tag in the output is one this file wrote. Quotes are not escaped because no input
   * is ever placed in an attribute — and nothing here emits an attribute at all.
   */
  private def escape(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  // ── Block level ──────────────────────────────────────────────────────────────

  private val Bullet   = """^[-*+]\s+(.+)$""".r
  private val Numbered = """^\d{1,9}[.)]\s+(.+)$""".r
  private val Heading  = """^#{1,6}\s+(.+)$""".r

  private def renderBlock(block: String): String = {
    val lines            = block.split("\n").toList.map(_.trim).filter(_.nonEmpty)
    def items(re: Regex) = lines.flatMap(l => re.findFirstMatchIn(l).map(_.group(1)))
    val bullets          = items(Bullet)
    val numbered         = items(Numbered)
    if lines.nonEmpty && bullets.sizeIs == lines.size then list("ul", bullets)
    else if lines.nonEmpty && numbered.sizeIs == lines.size then list("ol", numbered)
    else s"<p>${lines.map(renderLine).mkString("<br>")}</p>"
  }

  private def list(tag: String, items: List[String]): String =
    items.map(i => s"<li>${renderInline(i)}</li>").mkString(s"<$tag>\n", "\n", s"\n</$tag>")

  /** A heading line becomes bold text — email has no document outline to hang an `<h2>` on. */
  private def renderLine(line: String): String =
    Heading.findFirstMatchIn(line) match {
      case Some(m) => s"<strong>${renderInline(m.group(1))}</strong>"
      case None    => renderInline(line)
    }

  // ── Inline level ─────────────────────────────────────────────────────────────

  private val CodeSpan         = """`([^`\n]+)`""".r
  private val MdLink           = """\[([^\]\n]*)\]\(([^)\s]+)\)""".r
  private val Bold             = """\*\*(?=\S)(.+?)(?<=\S)\*\*""".r
  // Emphasis markers must not sit inside a word: `snake_case_name` and `2*3*4` are not italics.
  private val ItalicStar       = """(?<![\w*])\*(?=\S)([^*\n]+?)(?<=\S)\*(?![\w*])""".r
  private val ItalicUnderscore = """(?<![\w_])_(?=\S)([^_\n]+?)(?<=\S)_(?![\w_])""".r

  /**
   * Inline rendering of one already-escaped line. Code spans are carved out FIRST and their
   * contents passed through untouched, so `` `**not bold**` `` stays literal the way a reader
   * quoting a config snippet expects.
   */
  private def renderInline(escaped: String): String = {
    val m   = CodeSpan.pattern.matcher(escaped)
    val out = new StringBuilder
    var cut = 0
    while m.find() do {
      out.append(emphasis(escaped.substring(cut, m.start())))
      out.append("<code>").append(m.group(1)).append("</code>")
      cut = m.end()
    }
    out.append(emphasis(escaped.substring(cut))).toString
  }

  private def emphasis(s: String): String = {
    val linked = sub(MdLink, s) { m =>
      val (text, url) = (m.group(1), m.group(2))
      // `url` is EMITTED AS TEXT ONLY — never as an href — so this check is not what makes the
      // output safe; it decides whether unwrapping is HONEST. An unsafe-scheme URL stays literal
      // rather than being presented as a destination a reader might act on.
      if SafeSchemes.exists(url.toLowerCase.startsWith) then s"$text ($url)" else m.matched
    }
    val bolded = sub(Bold, linked)(m => s"<strong>${m.group(1)}</strong>")
    val italic = sub(ItalicStar, bolded)(m => s"<em>${m.group(1)}</em>")
    sub(ItalicUnderscore, italic)(m => s"<em>${m.group(1)}</em>")
  }

  /**
   * `replaceAllIn` with the replacement QUOTED — the body text routinely contains `$` (prices in
   * the release) and `\`, both of which Java's replacement syntax would otherwise interpret.
   */
  private def sub(re: Regex, s: String)(f: Regex.Match => String): String =
    re.replaceAllIn(s, m => Regex.quoteReplacement(f(m)))
}
