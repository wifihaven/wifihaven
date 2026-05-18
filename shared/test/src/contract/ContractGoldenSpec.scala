package wifihaven.shared.contract

import wifihaven.shared.*
import zio.*
import zio.json.*
import zio.test.*
import zio.test.Assertion.*

import java.nio.file.{Files, Path, Paths}

/**
 * Bidirectional contract tests (#634). Two halves:
 *
 *   1. API → router: serialize representative response payloads via the production zio-json codecs,
 *      golden them under `shared/contract/api-to-router/`. The router-side lua spec
 *      (`openwrt/test/contract_spec.lua`) consumes these via `luci.jsonc` and asserts render.lua +
 *      the policy fetcher digest each field.
 *
 * 2. Router → API: load hand-curated samples of what the agent actually sends (under
 * `shared/contract/router-to-api/`), decode each through the production decoder, and assert the
 * decoded value re-serializes to a JSON shape equivalent to the input (no fields dropped, no silent
 * fallback to defaults).
 *
 * To regenerate the API-to-router fixtures after an intentional codec change, set
 * WIFIHAVEN_REGEN_CONTRACT=1 (or run `scripts/regen-contract-fixtures.sh`).
 */
object ContractGoldenSpec extends ZIOSpecDefault {

  private val contractDir: Path = locateContractDir()

  private def locateContractDir(): Path = {
    // Walk up from cwd until we find a directory containing `shared/contract`.
    // Mill normally runs tests with cwd = workspace root, but `out/...` dirs
    // can shift this depending on test isolation settings.
    val start = Paths.get(sys.props.getOrElse("user.dir", "."))
    var cur   = start.toAbsolutePath
    while (cur != null) {
      val cand = cur.resolve("shared/contract")
      if (Files.isDirectory(cand)) return cand
      cur = cur.getParent
    }
    sys.error(s"could not locate shared/contract from user.dir=$start")
  }

  private val regenerate: Boolean =
    sys.env.get("WIFIHAVEN_REGEN_CONTRACT").exists(v => v == "1" || v.equalsIgnoreCase("true"))

  // ── Helpers ──────────────────────────────────────────────────────────────

  private def readFixture(rel: String): String =
    new String(
      Files.readAllBytes(contractDir.resolve(rel)),
      java.nio.charset.StandardCharsets.UTF_8,
    )

  private def writeFixture(rel: String, body: String): Unit = {
    val p = contractDir.resolve(rel)
    Files.createDirectories(p.getParent)
    val _ = Files.writeString(p, body)
  }

  /** Normalize JSON for a structural compare that ignores whitespace + key order. */
  private def normalize(json: String): Either[String, String] =
    json.fromJson[ast.Json].map(_.toJson)

  private def assertGoldenMatches(rel: String, generated: String): TestResult = {
    if (regenerate) {
      writeFixture(rel, generated)
      assertCompletes
    } else {
      val expected = readFixture(rel)
      // Compare structurally so trivial formatting drift (line breaks, key
      // order) doesn't break the test, while real shape changes still do.
      val cmp      = for {
        e <- normalize(expected)
        g <- normalize(generated)
      } yield (e, g)
      cmp match {
        case Right((e, g)) if e == g => assertCompletes
        case Right((e, g))           =>
          assert(g)(equalTo(e)) ?? s"$rel mismatch (regenerate with WIFIHAVEN_REGEN_CONTRACT=1)"
        case Left(err)               =>
          assert(err)(isNull) ?? s"$rel JSON parse failure: $err"
      }
    }
  }

  /**
   * For router→API: decode the fixture via the production decoder, re-encode via the production
   * encoder, and assert the re-encoded JSON is structurally equal to the input. This catches:
   *   - codec drops a field (re-encode would be missing it)
   *   - codec falls back to a default (re-encode shows the default value where the input had
   *     something different — caught by structural diff)
   *   - codec renames the wire field (decode fails outright)
   */
  private def assertRoundTrip[A: JsonCodec](rel: String): TestResult = {
    val raw     = readFixture(rel)
    val decoded = raw.fromJson[A]
    decoded match {
      case Left(err)    =>
        assert(err)(isNull) ?? s"$rel decode failed: $err"
      case Right(value) =>
        val reEncoded = value.toJson
        val cmp       = for {
          inJ  <- normalize(raw)
          outJ <- normalize(reEncoded)
        } yield (inJ, outJ)
        cmp match {
          case Right((inJ, outJ)) if inJ == outJ => assertCompletes
          case Right((inJ, outJ))                =>
            assert(outJ)(equalTo(inJ)) ??
              s"$rel round-trip differs (codec dropped or defaulted a field?)"
          case Left(err)                         =>
            assert(err)(isNull) ?? s"$rel normalize failed: $err"
        }
    }
  }

  // ── Spec ─────────────────────────────────────────────────────────────────

  def spec = suite("bidirectional contract (#634)")(
    suite("API → router (golden serialized via production codecs)")(
      test("policy_snapshot.json") {
        assertGoldenMatches(
          "api-to-router/policy_snapshot.json",
          ContractFixtures.pretty(ContractFixtures.policySnapshot),
        )
      },
    ),
    suite("router → API (decode hand-curated samples through production decoders)")(
      test("router_events_request.json decodes round-trip-clean") {
        assertRoundTrip[RouterEventsRequest]("router-to-api/router_events_request.json")
      },
      test("usage_report.json decodes round-trip-clean") {
        assertRoundTrip[UsageReport]("router-to-api/usage_report.json")
      },
      test("register_router_request.json decodes round-trip-clean") {
        assertRoundTrip[RegisterRouterRequest]("router-to-api/register_router_request.json")
      },
    ),
  )
}
