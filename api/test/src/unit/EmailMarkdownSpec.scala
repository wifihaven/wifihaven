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
      )
    },
  )
}
