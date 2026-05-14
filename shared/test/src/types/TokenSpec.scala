package familydns.shared.types

import zio.json.*
import zio.test.*

object TokenSpec extends ZIOSpecDefault {

  def spec = suite("Tokens & opaque strings")(
    suite("ETag")(
      test("accepts our sha256 form") {
        val raw = "\"sha256:" + ("a" * 64) + "\""
        assertTrue(ETag.parse(raw).exists(_.value == raw))
      },
      test("accepts our short-hex+serial form") {
        val raw = "\"abcdef0123456789-7\""
        assertTrue(ETag.parse(raw).isRight)
      },
      test("rejects unquoted") {
        assertTrue(ETag.parse("sha256:abc").isLeft)
      },
      test("rejects empty") {
        assertTrue(ETag.parse("").isLeft) &&
        assertTrue(ETag.parse("\"\"").isLeft)
      },
      test("rejects illegal chars") {
        assertTrue(ETag.parse("\"a b\"").isLeft)
      },
      test("JSON round-trip") {
        val e = ETag.parse("\"sha256:" + ("0" * 64) + "\"").toOption.get
        assertTrue(e.toJson.fromJson[ETag].contains(e))
      },
    ),
    suite("EnrollmentToken")(
      test("accepts well-formed token") {
        val tok = "et_" + ("A" * 32)
        assertTrue(EnrollmentToken.parse(tok).exists(_.value == tok))
      },
      test("rejects missing prefix") {
        assertTrue(EnrollmentToken.parse("A" * 35).isLeft)
      },
      test("rejects wrong body length") {
        assertTrue(EnrollmentToken.parse("et_AAAA").isLeft)
      },
      test("rejects empty") {
        assertTrue(EnrollmentToken.parse("").isLeft)
      },
    ),
    suite("RouterToken")(
      test("accepts well-formed token") {
        val tok = "rt_" + ("A" * 43)
        assertTrue(RouterToken.parse(tok).isRight)
      },
      test("rejects wrong prefix") {
        assertTrue(RouterToken.parse("et_" + ("A" * 43)).isLeft)
      },
      test("rejects empty") {
        assertTrue(RouterToken.parse("").isLeft)
      },
    ),
    suite("JwtToken")(
      test("accepts three-segment compact JWS") {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abc-_DEF"
        assertTrue(JwtToken.parse(jwt).exists(_.value == jwt))
      },
      test("rejects 2-segment") {
        assertTrue(JwtToken.parse("a.b").isLeft)
      },
      test("rejects empty segment") {
        assertTrue(JwtToken.parse("a..b").isLeft)
      },
      test("rejects empty") {
        assertTrue(JwtToken.parse("").isLeft)
      },
    ),
    suite("BlocklistVersion")(
      test("accepts date string") {
        assertTrue(BlocklistVersion.parse("2026-05-14").isRight)
      },
      test("accepts content hash") {
        assertTrue(BlocklistVersion.parse("sha256:abc123").isRight)
      },
      test("rejects empty") {
        assertTrue(BlocklistVersion.parse("").isLeft)
      },
      test("rejects spaces / illegal chars") {
        assertTrue(BlocklistVersion.parse("a b").isLeft) &&
        assertTrue(BlocklistVersion.parse("a/b").isLeft)
      },
      test("rejects > 128 chars") {
        assertTrue(BlocklistVersion.parse("a" * 129).isLeft)
      },
    ),
    suite("Url")(
      test("accepts http") {
        assertTrue(Url.parse("http://example.com/x").isRight)
      },
      test("accepts https") {
        assertTrue(Url.parse("https://example.com/blocklists/ads.rpz").isRight)
      },
      test("rejects ftp / non-http scheme") {
        assertTrue(Url.parse("ftp://example.com").isLeft)
      },
      test("rejects relative path") {
        assertTrue(Url.parse("/api/blocklists/ads.rpz").isLeft)
      },
      test("rejects scheme-only") {
        assertTrue(Url.parse("http://").isLeft)
      },
      test("rejects empty") {
        assertTrue(Url.parse("").isLeft)
      },
    ),
    suite("BlocklistUrl")(
      test("accepts relative path served by API") {
        assertTrue(BlocklistUrl.parse("/api/blocklists/ads.rpz").isRight)
      },
      test("accepts absolute http(s)") {
        assertTrue(BlocklistUrl.parse("https://example.com/ads.rpz").isRight)
      },
      test("rejects empty") {
        assertTrue(BlocklistUrl.parse("").isLeft)
      },
      test("rejects whitespace / control chars") {
        assertTrue(BlocklistUrl.parse("/api/ ads.rpz").isLeft)
      },
    ),
    suite("Sha256Hex")(
      test("accepts 64 lowercase hex chars") {
        assertTrue(Sha256Hex.parse("a" * 64).isRight)
      },
      test("rejects uppercase") {
        assertTrue(Sha256Hex.parse("A" * 64).isLeft)
      },
      test("rejects wrong length") {
        assertTrue(Sha256Hex.parse("a" * 63).isLeft) &&
        assertTrue(Sha256Hex.parse("a" * 65).isLeft)
      },
      test("rejects empty") {
        assertTrue(Sha256Hex.parse("").isLeft)
      },
    ),
  )
}
