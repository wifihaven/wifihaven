package wifihaven.api.notify

import scala.annotation.tailrec
import scala.util.matching.Regex

/**
 * #2677 — the ONE renderer for a plain-text/markdown email body into the HTML part, used by the
 * press responder (`PressResponder.htmlBody`). It was extracted because that shape had grown twice
 * independently — in `PressResponder` and in the since-removed (#2233) press-outreach composer —
 * escape, split on blank lines, `<br>` the single newlines, and both shipped the same defect: the
 * agent writes markdown and the recipient reads literal `**` (the reported bug was in a reply that
 * reached a journalist). Per docs/process/single-source-of-truth.md this is the single
 * implementation; do not re-derive it at a call site.
 *
 * ==Security model — escape first, then render a closed allowlist==
 *
 * The input is UNTRUSTED. For the responder it is the press agent's output, which is derived from a
 * journalist's message (see #2667 on adjacent-text injection), so it must be treated as though the
 * sender wrote it directly. The order below is the whole security property and must not be
 * inverted:
 *
 *   1. HTML-escape `&`, `<`, `>`, so the text provably contains no markup;
 *   1. then render a fixed set of markdown constructs over the ESCAPED text, emitting only literal,
 *      attribute-free tags this file spells out: `<p> <br> <strong> <em> <code> <pre> <ul> <ol>
 *      <li>`.
 *
 * Because every tag is a constant in this file and no input ever reaches an attribute position,
 * there is no path by which the input can become markup. That is also why this does NOT use a
 * general-purpose markdown library: those emit raw HTML from their input, which is exactly the
 * property being excluded here.
 *
 * The escape is also what makes `<` and `>` reliable tag-boundary sentinels for the inline passes:
 * after step 1 the only angle brackets in the text are the ones this file wrote, so excluding them
 * from every emphasis span is what stops one pass from reaching across a tag another pass emitted.
 *
 * ==Bounded by construction — an unbounded quantifier here is a DoS==
 *
 * The input is not just untrusted, it is untrusted AND large: up to
 * `PressAgentRoutes.MaxAgentBodyBytes` (64 KiB), on a route with no rate limiter, and an agent
 * reply is unwrapped prose so one line can be the whole body. Every inline pattern therefore bounds
 * its quantifiers ([[MaxSpanChars]] / [[MaxLinkTextChars]]) and makes the greedy ones possessive.
 * Unbounded, these backtrack quadratically: a 64 KiB line of `**a ` or of unmatched `[` measured
 * 8–45 s of single-fiber CPU during review of #2677. Beyond the bound a construct simply does not
 * render and the text stays literal, which is the safe direction. `EmailMarkdownSpec` pins the
 * worst cases with a wall-clock budget so a future pattern cannot quietly reintroduce this.
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
 * ==What stays literal==
 *
 * Rendered: `**bold**`, `__bold__`, `***bold italic***`, `*italic*`, `_italic_`, `` `code` ``,
 * fenced code blocks, `#` headings (as bold), `-`/`+` bullet and `1.` numbered lists, paragraphs,
 * single newlines as `<br>`. Everything else is left exactly as written — including
 * `~~strikethrough~~`, `> blockquotes`, tables, and an emphasis span that opens on one line and
 * closes on another. Those are pinned literal in `EmailMarkdownSpec` so the omission stays a
 * decision rather than becoming a gap.
 *
 * Italic nests inside bold (`**a *b* c**`); bold inside italic does not, and a span whose markers
 * CROSS (`**a *b** c*`) renders the pair it can and leaves the stray marker literal. Italic run up
 * against the bold CLOSE (`**Really *important***`) is the one nesting shape that half-renders —
 * the bold close takes two of the three `*` and the third stays literal. That is a CHOICE between
 * two imperfect outputs, not a limit: tightening the close to reject a following `*` removes the
 * stray marker, at the cost of the span not matching at all and the whole thing going literal,
 * which is the #2677 symptom rather than a milder version of it. Half-rendered was judged the
 * better of the two. It is pinned in the spec so it stays a known edge instead of a surprise.
 */
object EmailMarkdown {

  /** The only URL schemes a `[text](url)` link is unwrapped for. Everything else stays literal. */
  val SafeSchemes: List[String] = List("http://", "https://", "mailto:")

  /**
   * The longest span any inline construct renders across. Not a tuning knob: it exists so every
   * quantifier below is bounded (see the DoS note above), and it is set far past any span a human
   * or an agent writes, so the bound is never what a real reply hits.
   */
  private val MaxSpanChars = 2048

  /**
   * Same, for a link's bracketed text — shorter, because link text is a phrase, not a paragraph.
   */
  private val MaxLinkTextChars = 512

  /**
   * Code spans are lifted out of the line before the emphasis passes and put back afterwards, so
   * emphasis can span a code span (`**a `b` c**`) without the emphasis patterns ever seeing the
   * code content. NUL is the placeholder sentinel because it cannot appear in an HTML document at
   * all; [[render]] strips it from the input first, so agent text cannot forge a placeholder.
   */
  private val Nul: Char = 0.toChar

  /**
   * Render `markdown` into the HTML email body. Blank lines separate paragraphs; single newlines
   * become `<br>`; a block whose every line is a list item becomes `<ul>`/`<ol>`; a fenced block
   * becomes `<pre><code>`. Empty input renders `<p></p>` (an email body is never the empty string).
   */
  def render(markdown: String): String = {
    val normalized = markdown.replace("\r\n", "\n").replace('\r', '\n').filter(_ != Nul)
    val rendered   = blocks(escape(normalized).split("\n", -1).toList)
      .map {
        case Block.Fenced(Nil)   => ""
        case Block.Fenced(lines) => s"<pre><code>${lines.mkString("\n")}</code></pre>"
        case Block.Prose(lines)  => renderProse(lines)
      }
      .filter(_.nonEmpty)
    if rendered.isEmpty then "<p></p>" else rendered.mkString("\n")
  }

  /**
   * The escape the rest of this file depends on: after it, the text contains no `<`, `>` or bare
   * `&`, so every tag in the output is one this file wrote. Quotes are not escaped because no input
   * is ever placed in an attribute — and nothing here emits an attribute at all.
   */
  private def escape(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  // ── Block level ──────────────────────────────────────────────────────────────

  private enum Block {
    case Prose(lines: List[String])
    case Fenced(lines: List[String])
  }

  /**
   * A fence line is backticks plus an optional LANGUAGE TAG and nothing else. Both halves of that
   * are load-bearing, because an info string is the one part of a fence that gets DROPPED, and a
   * renderer that drops a line of a reply is the #2677 failure mode one notch worse. Requiring no
   * further backticks keeps `` ```code``` `` (inline code the writer put on its own line) from
   * reading as an opener whose text is swallowed; requiring the tag to look like a language token
   * does the same for `` ```note: see the paragraph below ``.
   *
   * The guarantee this buys is scoped, not absolute: no line that reads as PROSE is dropped. Fences
   * still pair off in order, so a reply carrying an ODD number of fence lines pairs them shifted —
   * text between the second and third reads as code, and the line that becomes the closer is
   * dropped as before. That is a pre-existing property of pairing rather than something the tighter
   * opener introduced, and it needs an agent to write both a non-language info string and a second
   * fenced block in one reply, so it is left as-is rather than papered over.
   */
  private val FenceLine = """^`{3,}[A-Za-z0-9_+#.-]*$""".r

  private def isFence(line: String): Boolean = FenceLine.matches(line.trim)

  /**
   * Split escaped lines into blocks: a fence line opens a verbatim run that ends at the next fence;
   * otherwise a blank line ends a block.
   *
   * An UNTERMINATED fence is deliberately not a fence — CommonMark would run it to the end of the
   * document, which for outbound correspondence means one stray line of backticks swallows the rest
   * of the reply, sign-off included. Here it stays ordinary prose.
   */
  private def blocks(lines: List[String]): List[Block] = {
    // `cur` accumulates the current block's lines in REVERSE and is flipped at flush. Appending
    // with `:+` instead is quadratic, and a 64 KiB body of short lines is 32k appends — measured at
    // seconds and gigabytes of churn during review of #2684, on the same unmetered route the DoS
    // note above is about.
    def flush(cur: List[String], acc: List[Block]): List[Block] =
      if cur.forall(_.trim.isEmpty) then acc else Block.Prose(cur.reverse) :: acc

    @tailrec def go(rest: List[String], acc: List[Block], cur: List[String]): List[Block] =
      rest match {
        case Nil                                             => flush(cur, acc)
        case l :: tail if isFence(l) && tail.exists(isFence) =>
          val (code, after) = tail.span(x => !isFence(x))
          go(after.drop(1), Block.Fenced(code) :: flush(cur, acc), Nil)
        case l :: tail if l.trim.isEmpty                     => go(tail, flush(cur, acc), Nil)
        case l :: tail                                       => go(tail, acc, l :: cur)
      }

    go(lines, Nil, Nil).reverse
  }

  // `*` is deliberately NOT a bullet marker: it collides with `*italic*`, so a paragraph of
  // one-phrase italic lines would otherwise turn into a list. Agents write `-`.
  private val Bullet   = """^[-+]\s+(.+)$""".r
  private val Numbered = """^\d{1,9}[.)]\s+(.+)$""".r
  private val Heading  = """^#{1,6}\s+(.+)$""".r

  private def renderProse(block: List[String]): String = {
    val lines            = block.map(_.trim).filter(_.nonEmpty)
    def items(re: Regex) = lines.flatMap(l => re.findFirstMatchIn(l).map(_.group(1)))
    val bullets          = items(Bullet)
    val numbered         = items(Numbered)
    if lines.isEmpty then ""
    else if bullets.sizeIs == lines.size then list("ul", bullets)
    else if numbered.sizeIs == lines.size then list("ol", numbered)
    else s"<p>${lines.map(renderLine).mkString("<br>")}</p>"
  }

  private def list(tag: String, items: List[String]): String =
    items.map(i => s"<li>${renderInline(i)}</li>").mkString(s"<$tag>\n", "\n", s"\n</$tag>")

  /** A heading line becomes bold text — email has no document outline to hang an `<h2>` on. */
  private def renderLine(line: String): String =
    Heading.findFirstMatchIn(line) match {
      case Some(m) =>
        // The heading is already bold, so drop any `<strong>` the inline pass added inside it
        // rather than nesting the same tag twice.
        val inner = renderInline(m.group(1)).replace("<strong>", "").replace("</strong>", "")
        s"<strong>$inner</strong>"
      case None    => renderInline(line)
    }

  // ── Inline level ─────────────────────────────────────────────────────────────

  // Every span excludes `<` and `>` so it cannot reach across a tag an earlier pass emitted (the
  // input is escaped, so those can only be ours), and every quantifier is bounded — the greedy ones
  // possessively, which is lossless here because each excludes its own terminator.
  // The delimiter is captured and back-referenced so a multi-backtick span (```x```, which a writer
  // may put on its own line) closes on a matching run rather than leaving stray ticks behind.
  private val CodeSpan = s"""(`{1,3})([^`\n]{1,$MaxSpanChars}+)\\1""".r

  private val MdLink =
    s"""\\[([^\\]\n]{0,$MaxLinkTextChars}+)\\]\\(([^)\\s]{1,$MaxSpanChars}+)\\)""".r

  // A bold body admits a LONE marker char but never the doubled one. That is what keeps the scan
  // linear — a `**` with no valid close fails at the next `**` instead of scanning
  // [[MaxSpanChars]] ahead from every start position, and with the body fully open to `*` a 64 KiB
  // line of `**a ` cost seconds, the same shape of cost the bounds exist for. Excluding `*`
  // outright would be linear too, but it would stop `**bold with *italic* inside**` from
  // rendering and ship literal `**` to a journalist, which is the very bug this file fixes.
  private val NotDoubleStar  = """(?:[^*<>\n]|\*(?!\*))"""
  private val NotDoubleScore = """(?:[^_<>\n]|_(?!_))"""

  private val BoldItalic =
    s"""\\*\\*\\*(?=[^\\s<>])($NotDoubleStar{1,$MaxSpanChars}?)(?<=[^\\s<>])\\*\\*\\*""".r
  private val BoldStar   =
    s"""\\*\\*(?=[^\\s<>])($NotDoubleStar{1,$MaxSpanChars}?)(?<=[^\\s<>])\\*\\*""".r
  private val BoldScore  =
    s"""__(?=[^\\s<>])($NotDoubleScore{1,$MaxSpanChars}?)(?<=[^\\s<>])__""".r

  // Emphasis markers must not sit inside a word: `snake_case_name` and `2*3*4` are not italics.
  private val ItalicStar  =
    s"""(?<![\\w*])\\*(?=[^\\s<>])([^*<>\n]{1,$MaxSpanChars}?)(?<=[^\\s<>])\\*(?![\\w*])""".r
  private val ItalicScore =
    s"""(?<![\\w_])_(?=[^\\s<>])([^_<>\n]{1,$MaxSpanChars}?)(?<=[^\\s<>])_(?![\\w_])""".r

  private val CodeToken = {
    val nul = java.util.regex.Pattern.quote(Nul.toString)
    s"$nul(\\d{1,9})$nul".r
  }

  /**
   * Inline rendering of one already-escaped line. Code spans are lifted out first and restored
   * last, so their contents are never seen by the emphasis passes (`` `**not bold**` `` stays
   * literal) while emphasis can still span one (`**a `b` c**` renders as one bold run).
   */
  private def renderInline(escaped: String): String = {
    val codes  = List.newBuilder[String]
    var n      = 0
    val masked = sub(CodeSpan, escaped) { m =>
      codes += m.group(2)
      val token = s"$Nul$n$Nul"
      n += 1
      token
    }
    val spans  = codes.result()
    sub(CodeToken, emphasis(masked))(m => s"<code>${spans(m.group(1).toInt)}</code>")
  }

  private def emphasis(s: String): String = {
    val linked     = sub(MdLink, s) { m =>
      val (text, url) = (m.group(1), m.group(2))
      // `url` is EMITTED AS TEXT ONLY — never as an href — so this check is not what makes the
      // output safe; it decides whether unwrapping is HONEST. An unsafe-scheme URL stays literal
      // rather than being presented as a destination a reader might act on.
      val safe        = SafeSchemes.exists(url.toLowerCase(java.util.Locale.ROOT).startsWith)
      if safe then s"$text ($url)" else m.matched
    }
    // Widest marker first: `***x***` must not be read as `**` wrapping a stray `*`.
    val boldItalic = sub(BoldItalic, linked)(m => s"<strong><em>${m.group(1)}</em></strong>")
    val bolded     = sub(BoldScore, sub(BoldStar, boldItalic)(strong))(strong)
    sub(ItalicScore, sub(ItalicStar, bolded)(em))(em)
  }

  private def strong(m: Regex.Match): String = s"<strong>${m.group(1)}</strong>"
  private def em(m: Regex.Match): String     = s"<em>${m.group(1)}</em>"

  /**
   * `replaceAllIn` with the replacement QUOTED — the body text routinely contains `$` (prices in
   * the release) and `\`, both of which Java's replacement syntax would otherwise interpret.
   */
  private def sub(re: Regex, s: String)(f: Regex.Match => String): String =
    re.replaceAllIn(s, m => Regex.quoteReplacement(f(m)))
}
