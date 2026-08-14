package wifihaven.api.unit

import wifihaven.api.notify.EmailMarkdown
import zio.test.*

/**
 * #2677 — the shared email markdown renderer. Two halves, and the second is the load-bearing one:
 *
 *   - it renders what the press agent actually emits (bold, italic, code, lists, paragraphs), so a
 *     journalist stops receiving literal `**`;
 *   - it renders NOTHING ELSE. The input is untrusted (agent output derived from a stranger's
 *     email), so the escape-then-allowlist order is the security property, and the cases below are
 *     the adversarial half: markup, attributes, and URL schemes must all come out inert.
 */
object EmailMarkdownSpec extends ZIOSpecDefault {

  private def render(s: String) = EmailMarkdown.render(s)

  def spec = suite("EmailMarkdown (#2677)")(
    test("renders the constructs the agent emits") {
      assertTrue(
        render("**bold**") == "<p><strong>bold</strong></p>",
        render("*italic*") == "<p><em>italic</em></p>",
        render("_italic_") == "<p><em>italic</em></p>",
        render("use `nftables`") == "<p>use <code>nftables</code></p>",
        render("## Heading") == "<p><strong>Heading</strong></p>",
        render("one\n\ntwo") == "<p>one</p>\n<p>two</p>",
        render("one\ntwo") == "<p>one<br>two</p>",
        render("- a\n- b") == "<ul>\n<li>a</li>\n<li>b</li>\n</ul>",
        render("1. a\n2. b") == "<ol>\n<li>a</li>\n<li>b</li>\n</ol>",
        render("") == "<p></p>",
        render("   \n\n  ") == "<p></p>",
      )
    },
    test("the reported bug: no `**` survives into the HTML part") {
      val reply = "**How WifiHaven's blocking works:** it enforces at the connection layer."
      assertTrue(
        render(reply).contains("<strong>How WifiHaven's blocking works:</strong>"),
        !render(reply).contains("**"),
      )
    },
    test("markup in the input is escaped, never rendered") {
      val hostile =
        "<script>alert('xss')</script> <img src=x onerror=alert(1)> a & b <b>bold?</b>"
      val out     = render(hostile)
      assertTrue(
        !out.contains("<script"),
        !out.contains("<img"),
        !out.contains("<b>"),
        !out.contains("onerror=alert(1)>"),
        out.contains("&lt;script&gt;alert('xss')&lt;/script&gt;"),
        out.contains("&lt;img src=x onerror=alert(1)&gt;"),
        out.contains("a &amp; b"),
      )
    },
    test("escaping happens BEFORE rendering — markdown cannot smuggle markup through it") {
      // If the order were inverted, the emphasis pass would run on raw text and the escape would
      // then mangle the tags it wrote (or, worse, the input's own). Each case here is markdown
      // syntax wrapped around markup: the emphasis renders, the markup stays text.
      assertTrue(
        render(
          "**<script>x</script>**",
        ) == "<p><strong>&lt;script&gt;x&lt;/script&gt;</strong></p>",
        render("*<b>x</b>*") == "<p><em>&lt;b&gt;x&lt;/b&gt;</em></p>",
        render("- <script>x</script>") == "<ul>\n<li>&lt;script&gt;x&lt;/script&gt;</li>\n</ul>",
        render("`<script>x</script>`") == "<p><code>&lt;script&gt;x&lt;/script&gt;</code></p>",
      )
    },
    test("no anchor is ever emitted, whatever the scheme") {
      val cases = List(
        "[docs](https://wifihaven.net/docs)",
        "[mail](mailto:press@wifihaven.net)",
        "[x](javascript:alert(1))",
        "[x](JaVaScRiPt:alert(1))",
        "[x](data:text/html;base64,PHNjcmlwdD4=)",
        "[x](vbscript:msgbox)",
        "[x](/relative/path)",
        "https://wifihaven.net/docs",
      )
      assertTrue(cases.forall { c =>
        val out = render(c)
        !out.contains("<a ") && !out.contains("href")
      })
    },
    test("a safe-scheme link unwraps to visible text + visible URL; anything else stays literal") {
      assertTrue(
        render("[the docs](https://wifihaven.net/docs)") ==
          "<p>the docs (https://wifihaven.net/docs)</p>",
        render("[mail us](mailto:press@wifihaven.net)") ==
          "<p>mail us (mailto:press@wifihaven.net)</p>",
        // Unsafe or scheme-less: byte-identical to the input, so it is inert AND visibly odd in the
        // correspondence log.
        render("[click](javascript:alert(1))") == "<p>[click](javascript:alert(1))</p>",
        render("[click](data:text/html,x)") == "<p>[click](data:text/html,x)</p>",
        render("[click](/admin)") == "<p>[click](/admin)</p>",
        // A bare URL is left exactly as written — the reader sees the destination itself.
        render("See https://wifihaven.net/docs") == "<p>See https://wifihaven.net/docs</p>",
      )
    },
    test("emphasis markers inside words are not emphasis") {
      assertTrue(
        render("snake_case_name") == "<p>snake_case_name</p>",
        render("2*3*4") == "<p>2*3*4</p>",
        render("https://a.example/b_c_d") == "<p>https://a.example/b_c_d</p>",
        // An unpaired marker is left alone rather than swallowing the rest of the line.
        render("a ** b") == "<p>a ** b</p>",
        render("50% off * terms apply") == "<p>50% off * terms apply</p>",
      )
    },
    test("a code span is verbatim — markdown inside it does not render") {
      assertTrue(
        render("`**not bold**`") == "<p><code>**not bold**</code></p>",
        render("`a` and **b**") == "<p><code>a</code> and <strong>b</strong></p>",
      )
    },
    test("replacement metacharacters in the body survive intact") {
      // `$` and `\` are special in Java's regex replacement syntax; the release copy is full of
      // prices, so an unquoted replacement would corrupt real sent copy (or throw).
      assertTrue(
        render("**$10/month** or $96/year") ==
          "<p><strong>$10/month</strong> or $96/year</p>",
        render("a \\ b **$1**") == "<p>a \\ b <strong>$1</strong></p>",
      )
    },
    test("CRLF input paragraphs the same way as LF") {
      assertTrue(
        render("one\r\n\r\ntwo") == "<p>one</p>\n<p>two</p>",
        render("one\r\ntwo") == "<p>one<br>two</p>",
      )
    },
    test("a mixed block is a paragraph, not a list") {
      // Only a block whose every line is an item becomes a list; a stray dash inside prose does not
      // restructure the paragraph.
      assertTrue(
        render("Intro line\n- a\n- b") == "<p>Intro line<br>- a<br>- b</p>",
        // `*` is not a bullet marker: a paragraph of one-phrase italic lines is not a list.
        render("*one*\n*two*") == "<p><em>one</em><br><em>two</em></p>",
      )
    },
    test("overlapping emphasis never emits interleaved tags") {
      // Three sequential passes over each other's output would cross spans and produce
      // <strong><em>x</strong></em>. `***x***` is ordinary model output, so it gets its own pass,
      // and every span excludes `<`/`>` so no later pass can reach across a tag an earlier one
      // wrote. Malformed input may keep literal markers; it must never produce malformed HTML.
      val cases = List(
        "***bold italic***" -> "<p><strong><em>bold italic</em></strong></p>",
        "***a*** and **b**" -> "<p><strong><em>a</em></strong> and <strong>b</strong></p>",
        "__bold__"          -> "<p><strong>bold</strong></p>",
        // Italic nests INSIDE bold — a bold body admits a lone `*`, just not a doubled one. This
        // is the case that matters: excluding `*` outright is also linear, but it ships literal
        // `**` to the recipient, which is the bug this whole file exists to fix.
        "**bold with *italic* inside**" ->
          "<p><strong>bold with <em>italic</em> inside</strong></p>",
        "__bold with _italic_ inside__" ->
          "<p><strong>bold with <em>italic</em> inside</strong></p>",
        // Crossed markers are malformed input. Bold binds first and italic cannot reach across the
        // tag it wrote, so the leftover marker stays literal — never an interleaved tag pair.
        "**a *b** c*"                   -> "<p><strong>a *b</strong> c*</p>",
        "*a **b* c**"                   -> "<p>*a <strong>b* c</strong></p>",
      )
      assertTrue(cases.forall((in, want) => render(in) == want))
    },
    test("emphasis spans a code span instead of breaking around it") {
      // The carve-out used to split the line, so `**a `b` c**` lost its bold and shipped literal
      // `**` — the exact defect #2677 is about, in the shape the reported reply had (bold headers
      // alongside backticked technical terms).
      assertTrue(
        render("**it uses `nftables` here**") ==
          "<p><strong>it uses <code>nftables</code> here</strong></p>",
        !render("**it uses `nftables` here**").contains("**"),
      )
    },
    test("a fenced block renders verbatim, escaped, with no markdown applied inside") {
      assertTrue(
        render("```\nnft add rule **x**\n```") ==
          "<pre><code>nft add rule **x**</code></pre>",
        // A language tag on the fence line is dropped, and blank lines inside survive.
        render("```lua\na\n\nb\n```") == "<pre><code>a\n\nb</code></pre>",
        // Markup inside a fence is still escaped, like everywhere else.
        render("```\n<script>x</script>\n```") ==
          "<pre><code>&lt;script&gt;x&lt;/script&gt;</code></pre>",
        // Prose either side stays prose.
        render("intro\n\n```\ncode\n```\n\noutro") ==
          "<p>intro</p>\n<pre><code>code</code></pre>\n<p>outro</p>",
        // An empty fence emits nothing rather than an empty <pre>.
        render("```\n```") == "<p></p>",
      )
    },
    test("the fence splitter never deletes a line") {
      // A renderer that silently drops a line of a journalist reply is the #2677 failure mode, one
      // notch worse. Both shapes below would vanish if a fence opener were just "starts with ```".
      assertTrue(
        // Inline code alone on a line: not a fence, because a fence line carries no closing ticks.
        render("```code```") == "<p><code>code</code></p>",
        render("run ```x``` now") == "<p>run <code>x</code> now</p>",
        // An UNTERMINATED fence stays prose instead of swallowing the rest of the reply — the
        // deliberate deviation from CommonMark, because the tail here is the sign-off.
        render("```\nnft add rule\n\nBest,\nSameer") ==
          "<p>```<br>nft add rule</p>\n<p>Best,<br>Sameer</p>",
        // A fence's info string is the one part of a fence that gets dropped, so only a
        // language-token info string opens one. Prose after the ticks is prose, not a lost line.
        render("```note: see the paragraph below\ncode\n```") ==
          "<p>```note: see the paragraph below<br>code<br>```</p>",
      )
    },
    test("what is deliberately left literal stays literal") {
      // Pinned so the omission is a decision, not a gap. Each of these is safe as text; supporting
      // any of them is a separate, deliberate change.
      assertTrue(
        render("~~strike~~") == "<p>~~strike~~</p>",
        render("> quoted") == "<p>&gt; quoted</p>",
        render("| a | b |") == "<p>| a | b |</p>",
        // Emphasis does not span a soft line break inside a paragraph.
        render("**multi\nline**") == "<p>**multi<br>line**</p>",
        // Italic nests inside bold in the MIDDLE of the span (pinned above), but not up against
        // the closing marker: the bold close takes two of the three `*` and the third is left
        // literal. Valid CommonMark and a shape agents write, so it is pinned rather than left to
        // be discovered. It is a CHOICE, not a limit — tightening the close to reject a following
        // `*` drops the stray marker but stops the span matching at all, so the line goes fully
        // literal, which is the #2677 symptom rather than a milder version of it.
        render("**Really *important***") == "<p><strong>Really *important</strong>*</p>",
      )
    },
    test("a NUL in the input cannot forge a code-span placeholder") {
      // Code spans are masked with a NUL-delimited token while emphasis runs. NUL is stripped from
      // the input first, so agent text cannot inject a token that would index into the span list.
      val nul     = 0.toChar
      val hostile = s"before ${nul}7$nul after `real`"
      assertTrue(
        render(hostile) == "<p>before 7 after <code>real</code></p>",
        !render(hostile).contains(nul),
      )
    },
    test("the renderer completes on adversarial input at the route's body cap") {
      // Read the assertion literally: every pathological shape at 64 KiB
      // (PressAgentRoutes.MaxAgentBodyBytes) RENDERS, and the set finishes rather than hanging CI.
      // That is all this claims.
      //
      // It is deliberately not a regression guard, because four attempts at one all failed and the
      // failures are worth recording so nobody rebuilds them. A 5 s budget measured cold-JIT and
      // went red at 5.9 s. A 10 s one went red at 14 s with this suite beside three
      // embedded-Postgres suites. A per-axis 4x-input scaling ratio false-flagged axes whose
      // baseline is 6 ms, where GC noise dwarfs the signal. And review of #2684 measured the
      // remaining coarse budget against the actual regressions: with both bold guards removed the
      // set runs 10–30 s and passes MORE OFTEN THAN NOT, so it does not reliably catch even that.
      //
      // The real guards are elsewhere and are not timing-based: the quantifier bounds and the
      // lone-marker rule in the patterns, the reverse accumulation in `blocks`, and the behavioural
      // pins above. This one only catches a renderer that stops finishing at all.
      def warmThenTime(inputs: List[String]): (Long, List[String]) = {
        inputs.foreach(render) // untimed: the first pass measures JIT, not the renderer
        val started = java.lang.System.nanoTime()
        val out     = inputs.map(render)
        ((java.lang.System.nanoTime() - started) / 1000000L, out)
      }

      val axes = List(
        (n: Int) => "[" * n,           // unmatched brackets — the originally reported ~45 s case
        (n: Int) => "[a](b" * (n / 5), // unmatched parens
        (n: Int) => "**a " * (n / 4),  // unmatched bold opens
        (n: Int) => "*" * n,
        (n: Int) => "_" * n,
        (n: Int) => "`" * n,
        (n: Int) => ("a" * 100 + "**") * (n / 102),
        // Many SHORT lines — the block-splitter's accumulator, not a regex. Every case above is a
        // single line, which is exactly how a quadratic `:+` got past this test into review once.
        (n: Int) => "a\n" * (n / 2),
        (n: Int) => "- a\n" * (n / 4),
      )

      // Warm, the whole set is well under a second, so 30 s is a hang ceiling rather than a
      // threshold anything is expected to approach. It exists so a renderer that has stopped
      // terminating fails the build instead of wedging the runner.
      val (elapsedMs, outputs) = warmThenTime(axes.map(_(64 * 1024)))
      assertTrue(outputs.forall(_.nonEmpty), elapsedMs < 30000L)
    },
  )
}
