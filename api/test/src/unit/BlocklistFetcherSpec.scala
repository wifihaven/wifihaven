package wifihaven.api.unit

import wifihaven.api.{BlocklistFetcher, BlocklistFormat}
import wifihaven.shared.types.Hostname
import zio.test.*

/**
 * #958 (URL-sourced lists): pure parser tests for the upstream payload formats.
 * No network access — `BlocklistFetcher.Parser` is exposed so we can feed it canned strings.
 */
object BlocklistFetcherSpec extends ZIOSpecDefault {

  def spec = suite("BlocklistFetcher.Parser")(
    test("hosts-file: 0.0.0.0 host and 127.0.0.1 host lines") {
      val body =
        """0.0.0.0 doubleclick.net
          |127.0.0.1 tracker.example.com
          |""".stripMargin
      val parsed = BlocklistFetcher.Parser.parse(body, BlocklistFormat.HostsFile)
      assertTrue(parsed == List(Hostname.unsafe("doubleclick.net"), Hostname.unsafe("tracker.example.com")))
    },
    test("hosts-file: bare hostname is accepted") {
      val body =
        """doubleclick.net
          |evil.example
          |""".stripMargin
      val parsed = BlocklistFetcher.Parser.parse(body, BlocklistFormat.HostsFile).toSet
      assertTrue(parsed.contains(Hostname.unsafe("doubleclick.net"))) &&
      assertTrue(parsed.contains(Hostname.unsafe("evil.example")))
    },
    test("hosts-file: whole-line and trailing comments are stripped") {
      val body =
        """# header comment
          |
          |0.0.0.0 doubleclick.net # ad network
          |# inline noise
          |0.0.0.0 tracker.example
          |""".stripMargin
      val parsed = BlocklistFetcher.Parser.parse(body, BlocklistFormat.HostsFile).toSet
      assertTrue(parsed == Set(Hostname.unsafe("doubleclick.net"), Hostname.unsafe("tracker.example")))
    },
    test("hosts-file: malformed entries dropped silently (wildcards, IPv4 literal)") {
      val body =
        """0.0.0.0 *.wildcard.example
          |0.0.0.0 192.168.1.1
          |0.0.0.0 valid.example
          |""".stripMargin
      val parsed = BlocklistFetcher.Parser.parse(body, BlocklistFormat.HostsFile)
      // valid.example passes; the wildcard fails the label regex and the IPv4
      // literal fails Hostname's anti-IPv4 guard. (Single-label hostnames like
      // "localhost" or IPv6-paired second tokens *are* valid per Hostname.parse
      // — upstream hosts files rarely contain them at the apex anyway, but
      // the parser accepts what the regex accepts.)
      assertTrue(parsed.contains(Hostname.unsafe("valid.example"))) &&
      assertTrue(!parsed.map(_.value).exists(_.contains("wildcard"))) &&
      assertTrue(!parsed.map(_.value).contains("192.168.1.1"))
    },
    test("hosts-file: duplicates collapsed, output sorted") {
      val body =
        """0.0.0.0 b.example
          |0.0.0.0 a.example
          |0.0.0.0 b.example
          |""".stripMargin
      val parsed = BlocklistFetcher.Parser.parse(body, BlocklistFormat.HostsFile)
      assertTrue(parsed == List(Hostname.unsafe("a.example"), Hostname.unsafe("b.example")))
    },
    test("domain-list: one host per line, comments and blanks ignored") {
      val body =
        """# OISD-style file
          |
          |doubleclick.net
          |tracker.example   # trailing comment
          |""".stripMargin
      val parsed = BlocklistFetcher.Parser.parse(body, BlocklistFormat.DomainList).toSet
      assertTrue(parsed == Set(Hostname.unsafe("doubleclick.net"), Hostname.unsafe("tracker.example")))
    },
    test("domain-list: drops obviously-bad lines without failing") {
      val body =
        """valid.example
          |not a hostname
          |another-valid.example
          |""".stripMargin
      val parsed = BlocklistFetcher.Parser.parse(body, BlocklistFormat.DomainList).toSet
      assertTrue(parsed == Set(Hostname.unsafe("valid.example"), Hostname.unsafe("another-valid.example")))
    },
  )
}
