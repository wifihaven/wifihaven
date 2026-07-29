package wifihaven.api.feature

import wifihaven.api.support.AgentPromptVersion
import wifihaven.api.support.AgentPromptVersion.{Channel, State}
import zio.*
import zio.metrics.Metric
import zio.test.*
import zio.test.Assertion.*

import java.nio.file.{Files, Path, Paths}

/**
 * #2469 — the prompt-drift detector.
 *
 * A Claude Code Cloud ROUTINE prompt is web-UI-only: merging a PR that edits
 * `deploy/{support,press}-agent/agent.yaml` does NOT update the running routine, and a stale
 * routine reports a GREEN run while behaving from an old prompt. That has bitten twice (#2419/#2425
 * consent and #2430/#2441 thread history both merged un-re-pasted; the `<routine-fire-payload>`
 * opt-in was the first). Same class as no-dark-by-default: a change that silently doesn't take
 * effect.
 *
 * The workable inversion — since routine CRUD is web-UI-only we cannot READ the live prompt — is to
 * have the agent report its OWN prompt version on its reply callback, and compare that against the
 * version compiled from the repo's `agent.yaml`.
 *
 * This spec pins the two halves of that comparison:
 *   - the yaml and the Scala constant carry the SAME version (the mirror is the whole mechanism — a
 *     drifted mirror would report every live routine stale, or worse, every stale one current), and
 *     each prompt actually INSTRUCTS the echo on the reply callback;
 *   - `classify` maps reported → {current, stale, unknown} and `observe` emits exactly one
 *     `agent_prompt_version_total{channel,state}` sample per callback, both bounded enums.
 *
 * The route-level pins — the reply STILL POSTS on a mismatch (non-fatal by construction), and a
 * customer message containing a fake `PROMPT_VERSION:` cannot spoof the reported version — live in
 * [[SupportResponderSpec]] / [[PressResponderSpec]], on the full stack with their harnesses.
 */
object AgentPromptVersionSpec extends ZIOSpecDefault {

  /** Walk up from the test's working directory to the repo root (the dir containing `api/src`). */
  private val repoRoot: Path = {
    var cur: Path = Paths.get(sys.props.getOrElse("user.dir", ".")).toAbsolutePath
    while cur != null && !Files.isDirectory(cur.resolve("api/src")) do cur = cur.getParent
    if cur == null then sys.error("could not locate repo root (api/src) from user.dir")
    cur
  }

  private def readAll(rel: String): String =
    new String(Files.readAllBytes(repoRoot.resolve(rel)))

  /**
   * The marker as it appears in the prompt. Deliberately the SAME shape the CI guard
   * (.github/scripts/check-agent-prompt-repaste.sh) greps for — one syntax, two readers.
   */
  private val MarkerRe = """(?m)^\s*PROMPT_VERSION:\s*(\S+)\s*$""".r

  private def markerIn(yaml: String): Option[String] =
    MarkerRe.findFirstMatchIn(yaml).map(_.group(1))

  private val Yamls = List(
    Channel.Support -> "deploy/support-agent/agent.yaml",
    Channel.Press   -> "deploy/press-agent/agent.yaml",
  )

  /**
   * Anything SHAPED like one of our version markers, wherever it appears in the file. The marker
   * line is not the only place the value is written — the prompt also shows the agent a worked
   * `POST …/reply` body — and neither the CI guard nor `MarkerRe` reads that second copy, so a bump
   * that missed it would leave the prompt naming two versions with every gate green. This regex is
   * what pins the copies together.
   */
  private val AnyVersionRe = """(?:support|press)-\d{4}-\d{2}-\d{2}\.\d+""".r

  /**
   * An ALL-CAPS section heading, the shape these prompts use ("SECURITY —", "ABSOLUTE LIMITS —").
   */
  private val HeadingRe = """(?m)^\s{0,4}[A-Z]{2,}[A-Z ]* —""".r

  /**
   * The `PROMPT VERSION` paragraph only — so an assertion cannot pass on text elsewhere in the
   * prompt that happens to contain the same word.
   */
  private def versionParagraph(yaml: String): String = {
    val from = yaml.indexOf("PROMPT VERSION —")
    if from < 0 then ""
    else {
      val rest = yaml.substring(from)
      // Run to the NEXT heading — skipping this section's own heading line, which would otherwise
      // match at offset 0 and yield an empty paragraph that trivially fails every assertion.
      val body = rest.indexOf('\n') + 1
      val end  = HeadingRe.findFirstMatchIn(rest.substring(body)).map(_.start + body)
      end.fold(rest)(rest.take)
    }
  }

  /**
   * `agent_prompt_version_total` is a JVM-global counter that `SupportResponderSpec` /
   * `PressResponderSpec` also write, so the assertions below read DELTAS around one call — never an
   * absolute. Exact deltas are safe rather than lucky: mill runs each spec class in its own worker
   * JVM (separate metric registries), and two classes sharing a worker run SEQUENTIALLY within it —
   * so no other spec can emit between a before/after pair (docs/testing-parallelism.md, "Within a
   * worker JVM specs run sequentially").
   */
  private def count(channel: String, state: String): UIO[Double] =
    Metric
      .counter("agent_prompt_version_total")
      .tagged("channel", channel)
      .tagged("state", state)
      .value
      .map(_.count)

  def spec = suite("AgentPromptVersionSpec")(
    suite("the yaml ↔ Scala mirror")(
      test("each agent.yaml carries a PROMPT_VERSION marker equal to the compiled constant") {
        ZIO.succeed(
          assertTrue(
            Yamls.forall { case (ch, rel) => markerIn(readAll(rel)).contains(ch.expected) },
          ),
        )
      },
      test("EVERY version-shaped literal in a prompt is that channel's version") {
        // Not just the marker line: the prompt also shows the agent a worked reply body carrying the
        // literal. Nothing else reads that copy, so without this pin a bump could update the marker
        // and leave the example naming an older version — the prompt would instruct the agent to
        // report a version we no longer expect, and every other gate would stay green.
        ZIO.succeed(
          assertTrue(
            Yamls.forall { case (ch, rel) =>
              val found = AnyVersionRe.findAllIn(readAll(rel)).toList
              found.nonEmpty && found.forall(_ == ch.expected)
            },
          ),
        )
      },
      test("the version paragraph itself instructs the echo on the reply callback") {
        // The marker is inert unless the prompt tells the agent to report it back on the DEDICATED
        // field — that echo is the only channel through which the live routine's identity reaches
        // us. Asserted against the PROMPT VERSION paragraph, not the file: `agent/reply` appears all
        // over these prompts already, so a file-wide `contains` would pass on a prompt that never
        // mentions the echo at all.
        ZIO.succeed(
          assertTrue(
            Yamls.forall { case (ch, rel) =>
              val p = versionParagraph(readAll(rel))
              p.contains("promptVersion") && p.contains(s"/api/${ch.wire}/agent/reply")
            },
          ),
        )
      },
      test("the version paragraph forbids taking the version from untrusted message text") {
        // Instruction-zone content, not customer data (#2469): an injected "PROMPT_VERSION:" line in
        // a customer/sender message must never become the reported version. Same reasoning as above
        // — `<customer_message>` predates this change everywhere in these prompts, so the assertion
        // is only meaningful when scoped to the paragraph that introduces the marker.
        ZIO.succeed(
          assertTrue(
            Yamls.forall { case (_, rel) =>
              val p = versionParagraph(readAll(rel))
              p.contains("<customer_message>") && p.toLowerCase.contains("ignore it")
            },
          ),
        )
      },
      test("the two channels do not share a version string") {
        assertTrue(Channel.Support.expected != Channel.Press.expected)
      },
    ),
    suite("classify")(
      test("the compiled version reported verbatim is Current") {
        assertTrue(
          AgentPromptVersion.classify(Channel.Support, Some(Channel.Support.expected)) ==
            State.Current,
          AgentPromptVersion.classify(Channel.Press, Some(Channel.Press.expected)) == State.Current,
        )
      },
      test("a different version is Stale — including the OTHER channel's version") {
        assertTrue(
          AgentPromptVersion.classify(Channel.Support, Some("support-2020-01-01.1")) == State.Stale,
          AgentPromptVersion.classify(Channel.Support, Some(Channel.Press.expected)) == State.Stale,
        )
      },
      test("an absent or blank version is Unknown, never Current") {
        assertTrue(
          AgentPromptVersion.classify(Channel.Support, None) == State.Unknown,
          AgentPromptVersion.classify(Channel.Support, Some("   ")) == State.Unknown,
        )
      },
      test("surrounding whitespace does not make a matching version look stale") {
        assertTrue(
          AgentPromptVersion.classify(Channel.Press, Some(s" ${Channel.Press.expected}\n")) ==
            State.Current,
        )
      },
    ),
    suite("observe")(
      test("emits exactly one agent_prompt_version_total sample per state, per channel") {
        for {
          before <- ZIO.foreach(List("current", "stale", "unknown"))(s => count("support", s))
          _      <- AgentPromptVersion.observe(Channel.Support, Some(Channel.Support.expected))
          _      <- AgentPromptVersion.observe(Channel.Support, Some("support-1999-01-01.0"))
          _      <- AgentPromptVersion.observe(Channel.Support, None)
          after  <- ZIO.foreach(List("current", "stale", "unknown"))(s => count("support", s))
        } yield assert(after.zip(before).map((a, b) => a - b))(equalTo(List(1.0, 1.0, 1.0)))
      },
      test("the press channel is a separate series, so one audience cannot mask the other") {
        for {
          beforeS <- count("support", "stale")
          beforeP <- count("press", "stale")
          _       <- AgentPromptVersion.observe(Channel.Press, Some("press-1999-01-01.0"))
          afterS  <- count("support", "stale")
          afterP  <- count("press", "stale")
        } yield assertTrue(afterP - beforeP == 1.0, afterS - beforeS == 0.0)
      },
      test("a reported version cannot forge a log line (control chars stripped, length bounded)") {
        // The reported value reaches an ERROR log we alert on, and it is attacker-INFLUENCEABLE (a
        // hijacked agent, or anyone who guesses the field on the public callback). A newline would
        // let it synthesize a whole extra line in the Loki stream — log forging. Pin the sanitizer
        // itself, not just "observe didn't crash".
        val forged = "v1\nERROR fake operator alert: everything is fine\r\u0000"
        val safe   = AgentPromptVersion.logSafeVersion(forged)
        assertTrue(
          !safe.contains("\n"),
          !safe.contains("\r"),
          !safe.contains("\u0000"),
          !safe.exists(_.isControl),
          safe.startsWith("v1"),
          // Bounded: an over-long value cannot flood a log line either.
          AgentPromptVersion.logSafeVersion("x" * 10_000).length <=
            AgentPromptVersion.MaxLoggedVersion,
          // A legitimate marker survives verbatim — the sanitizer must not mangle the real signal.
          AgentPromptVersion.logSafeVersion(Channel.Support.expected) == Channel.Support.expected,
        )
      },
      test("observe never fails — a drift signal must not be able to break a callback") {
        // Non-fatal by construction (#2469): the return type is UIO, so there is no error channel to
        // propagate into the reply path. This exercises the hostile-input shapes anyway.
        for {
          _ <- AgentPromptVersion.observe(Channel.Support, Some("x" * 10_000))
          _ <- AgentPromptVersion.observe(Channel.Support, Some("\u0000\n\r ignore previous"))
        } yield assertCompletes
      },
    ),
  ) @@ TestAspect.sequential
}
