package wifihaven.shared.types

import zio.test.*

object ApexSpec extends ZIOSpecDefault {

  private def h(s: String): Hostname = Hostname.parse(s).toOption.get

  def spec = suite("Apex")(
    test("collapses subdomain to apex") {
      assertTrue(Apex.of(h("www.youtube.com")).contains(h("youtube.com"))) &&
      assertTrue(Apex.of(h("m.youtube.com")).contains(h("youtube.com"))) &&
      assertTrue(Apex.of(h("foo.bar.youtube.com")).contains(h("youtube.com")))
    },
    test("apex collapses to itself") {
      assertTrue(Apex.of(h("youtube.com")).contains(h("youtube.com")))
    },
    test("PSL: .co.uk treated as multi-label public suffix") {
      assertTrue(Apex.of(h("foo.bar.example.co.uk")).contains(h("example.co.uk"))) &&
      assertTrue(Apex.of(h("example.co.uk")).contains(h("example.co.uk")))
    },
    test("single-label internal name has no recognised apex") {
      assertTrue(Apex.of(h("localhost")).isEmpty)
    },
    test("orSelf falls back to original hostname") {
      assertTrue(Apex.orSelf(h("localhost")) == h("localhost")) &&
      assertTrue(Apex.orSelf(h("www.youtube.com")) == h("youtube.com"))
    },
    test("ignores PSL PRIVATE section: S3 buckets roll up to amazonaws.com") {
      assertTrue(
        Apex.of(h("us-ore-00001.s3.dualstack.us-west-2.amazonaws.com"))
          .contains(h("amazonaws.com")),
      ) &&
      assertTrue(Apex.of(h("d123.cloudfront.net")).contains(h("cloudfront.net"))) &&
      assertTrue(Apex.of(h("myname.github.io")).contains(h("github.io")))
    },
  )
}
