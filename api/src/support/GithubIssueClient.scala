package wifihaven.api.support

import wifihaven.api.SupportConfig
import zio.*
import zio.json.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration as JDuration

/**
 * #2200 / #2241 — the CS-agent's GitHub issue-filing capability. Files issues DIRECTLY (operator
 * decision: NOT human-gated) under a dedicated bot identity, via a fine-grained token scoped to
 * **Issues: write ONLY** on `wifihaven/wifihaven` — **no `contents`, no `pull_requests`**. That
 * scoping makes "cannot create or merge PRs" STRUCTURAL (the token lacks the scope), not a policy
 * the model could be argued out of. Every issue is auto-labeled `support-agent` and rate-limited by
 * the caller (per-thread + global) with a volume metric for the operator alert.
 *
 * COMPENSATING CONTROL (#2241): the issue body is ALWAYS run through
 * [[SupportPrivacy.scrubForIssue]] at THIS trait boundary — so no path through the trait (Live,
 * Recorder, or a future agentic caller) can embed raw household-data-query output or obvious PII in
 * an issue. The agent files the symptom/summary; the data stays in the household, not in a public
 * repo.
 *
 * #2265 — no dark-by-default: issue filing runs iff the EXPLICIT `support.issueFilingEnabled` flag
 * is true (which requires the bot token at boot, loudly); flag false ⇒ the [[Disabled]] no-op,
 * logged and health-visible. The agent files an issue only for a genuine product bug/gap per its
 * standing instructions — never as an action ordered by message content (the injection guard).
 */
trait GithubIssueClient {

  /** File a GitHub issue. The body is scrubbed of PII before it leaves the process. Never fails. */
  def fileIssue(req: IssueFileRequest): UIO[IssueOutcome]
}

/** A support-agent issue. `body` is treated as UNTRUSTED and scrubbed before filing. */
final case class IssueFileRequest(title: String, body: String, threadId: String)

/**
 * A pointer to an issue in the PUBLIC target repo — safe to hand to a customer verbatim (#2461).
 * `url` is GitHub's own `html_url` (the browser link), never reconstructed from the number here.
 */
final case class IssueRef(number: Int, url: String)

/**
 * Bounded outcome enum. [[Filed]] carries the created issue when GitHub's 2xx body was parseable
 * (#2461) so the agent can quote the link; `None` means the issue WAS created but we could not read
 * back its identity — still a success, just without a link to offer.
 *
 * METRIC LABELS: the sole consumer ([[SupportResponder.agentFileIssue]]) collapses each case to a
 * bounded `AgentActionResult` and meters `AgentActionResult.label` — so the outcome LABEL is one of
 * three fixed strings whatever the payload. The [[IssueRef]] is response data only: never put an
 * issue number in a metric label (unbounded cardinality — docs/process/instrumentation.md).
 *
 * Shaped for #2458: a future "matched an existing issue instead of creating a duplicate" case adds
 * a constructor carrying the same [[IssueRef]], so the customer is pointed at the canonical issue
 * without another breaking change to this type.
 */
enum IssueOutcome {
  case Filed(issue: Option[IssueRef])
  case Disabled
  case Error
}

object GithubIssueClient {

  val SupportLabel: String              = "support-agent"
  // #2265: the target repo + REST base are constants, not config — the support bot only ever files
  // into wifihaven/wifihaven on public github.com (the fine-grained token is scoped to exactly this
  // repo). Kept out of SupportConfig to hold it under zio-config-magnolia's 16-field ceiling.
  val Repo: String                      = "wifihaven/wifihaven"
  val ApiBase: String                   = "https://api.github.com"
  private val UserAgent: String         = "wifihaven-support-bot/1 (+https://wifihaven.net)"
  private val ApiVersion                = "2022-11-28"
  private val ConnectTimeout: JDuration = JDuration.ofSeconds(10)
  private val RequestTimeout: JDuration = JDuration.ofSeconds(20)

  /**
   * Scrub the body (compensating control) and cap the title. Applied by BOTH Live and Recorder so
   * the no-raw-data guarantee holds at the trait boundary, not per-caller.
   */
  private def sanitize(req: IssueFileRequest): IssueFileRequest =
    req.copy(
      title = SupportPrivacy.scrubForIssue(req.title).take(200).linesIterator.mkString(" "),
      body = SupportPrivacy.scrubForIssue(req.body),
    )

  // #2265: explicit named flag, logged at boot — never inferred from a missing bot token (config
  // validation fails the boot when the flag is true without the token).
  val layer: ZLayer[SupportConfig, Nothing, GithubIssueClient] =
    ZLayer.fromZIO {
      ZIO.serviceWithZIO[SupportConfig] { cfg =>
        if cfg.issueFilingEnabled then
          ZIO
            .logInfo("support-agent issue filing ENABLED (fine-grained Issues:write bot token)")
            .as(new Live(cfg): GithubIssueClient)
        else
          ZIO
            .logInfo("support-agent issue filing DISABLED (support.issueFilingEnabled=false)")
            .as(Disabled)
      }
    }

  val Disabled: GithubIssueClient = new GithubIssueClient {
    def fileIssue(req: IssueFileRequest): UIO[IssueOutcome] = ZIO.succeed(IssueOutcome.Disabled)
  }

  val noop: GithubIssueClient = Disabled

  private final case class CreateIssue(title: String, body: String, labels: List[String])
  private object CreateIssue {
    given JsonEncoder[CreateIssue] = DeriveJsonEncoder.gen[CreateIssue]
  }

  // The two fields we read back out of GitHub's create-issue response; everything else is ignored.
  private final case class CreatedIssue(number: Int, @jsonField("html_url") htmlUrl: String)
  private object CreatedIssue {
    given JsonDecoder[CreatedIssue] = DeriveJsonDecoder.gen[CreatedIssue]
  }

  /**
   * #2461 — read the created issue's identity out of GitHub's 2xx body. Pure and total: any body we
   * cannot read (truncated, non-JSON, either field absent) yields None, so the agent falls back to
   * "filed, no link" rather than quoting a link we invented.
   */
  def parseCreated(body: String): Option[IssueRef] =
    body.fromJson[CreatedIssue].toOption.map(c => IssueRef(c.number, c.htmlUrl))

  /**
   * Live GitHub transport. One blocking HTTPS POST to the REST create-issue endpoint (same
   * JDK-HttpClient shape as the other external clients — no new build dependency). The fine-grained
   * bot token rides as `Authorization: Bearer`; the token's Issues:write-only scope is what makes
   * no-PR structural. Any non-2xx / error is logged and mapped to [[IssueOutcome.Error]].
   */
  final class Live(cfg: SupportConfig) extends GithubIssueClient {
    private val client = HttpClient.newBuilder().connectTimeout(ConnectTimeout).build()

    def fileIssue(reqRaw: IssueFileRequest): UIO[IssueOutcome] = {
      val req = sanitize(reqRaw)
      ZIO
        .attemptBlocking {
          val payload = CreateIssue(req.title, req.body, List(SupportLabel)).toJson
          val httpReq = HttpRequest
            .newBuilder(URI.create(s"$ApiBase/repos/$Repo/issues"))
            .header("Authorization", s"Bearer ${cfg.githubSupportBotTokenTrimmed}")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", ApiVersion)
            .header("User-Agent", UserAgent)
            .timeout(RequestTimeout)
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
          client.send(httpReq, HttpResponse.BodyHandlers.ofString())
        }
        .flatMap { resp =>
          if resp.statusCode() / 100 == 2 then
            // #2461: GitHub returns the created issue here — read its number + browser URL so the
            // agent can point the customer at it. An unreadable body is still a successful filing.
            parseCreated(resp.body()) match {
              case some @ Some(_) => ZIO.succeed(IssueOutcome.Filed(some))
              case None           =>
                ZIO
                  .logWarning(
                    "github issue filed but the create response was unreadable; no link to offer",
                  )
                  .as(IssueOutcome.Filed(None))
            }
          else
            ZIO
              .logWarning(
                s"github issue-filing failed: HTTP ${resp.statusCode()} (${resp.body().take(300)})",
              )
              .as(IssueOutcome.Error)
        }
        .catchAll(e =>
          ZIO.logWarning(s"github issue-filing errored: ${e.getMessage}").as(IssueOutcome.Error),
        )
    }
  }

  /**
   * Test client: records every (SANITISED) issue request and reports Filed. The recorder stores the
   * scrubbed request so the "issue body contains no raw household data" pin asserts against exactly
   * what would leave the process.
   */
  final case class Recorder(issues: Ref[List[IssueFileRequest]])

  /** First issue number the recorder hands out — arbitrary, but stable so specs can assert it. */
  val RecorderFirstIssueNumber: Int = 9001

  def recording(rec: Recorder): GithubIssueClient = new GithubIssueClient {
    def fileIssue(req: IssueFileRequest): UIO[IssueOutcome] =
      // #2461: mint a distinct, deterministic ref per filing — same shape the Live client parses
      // out of GitHub's response, so specs can assert what the agent is told.
      rec.issues.updateAndGet(_ :+ sanitize(req)).map { recorded =>
        val n = RecorderFirstIssueNumber + recorded.size - 1
        IssueOutcome.Filed(Some(IssueRef(n, s"https://github.com/$Repo/issues/$n")))
      }
  }

  def recorder: UIO[Recorder] = Ref.make(List.empty[IssueFileRequest]).map(Recorder.apply)
}
