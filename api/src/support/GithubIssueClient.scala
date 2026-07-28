package wifihaven.api.support

import wifihaven.api.SupportConfig
import wifihaven.api.metrics.AppMetrics
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
 * `url` is GitHub's own `html_url` (the browser link) as returned by the create call; the Live
 * transport never reconstructs it from the number, and [[GithubIssueClient.parseCreatedDetailed]]
 * rejects any URL outside the public target repo, so "safe to show a customer" is structural.
 */
final case class IssueRef(number: Int, url: String)

/**
 * Bounded outcome enum. [[Filed]] carries the created issue when GitHub's 2xx body was parseable
 * (#2461) so the agent can quote the link; `None` means the issue WAS created but we could not read
 * back its identity — still a success, just without a link to offer.
 *
 * METRIC LABELS: the sole consumer ([[SupportResponder.agentFileIssue]]) collapses every case to a
 * `SupportResponder.AgentActionResult` and meters `AgentActionResult.label`, so the label space is
 * exactly that enum whatever the payload — a [[Filed]] with no readable ref maps to `OkNoLink`
 * (still a success; see `AgentActionResult.SuccessLabels`, which the Grafana volume panel mirrors).
 * The [[IssueRef]] is response data only — never put an issue number in a metric label (unbounded
 * cardinality — docs/process/instrumentation.md).
 *
 * Shaped for #2458: a future "matched an existing issue instead of creating a duplicate" case adds
 * a constructor carrying the same [[IssueRef]], so the customer is pointed at the canonical issue
 * without another breaking change to this type.
 */
enum IssueOutcome {
  case Filed(issue: Option[IssueRef])

  /**
   * #2458 — the topic is ALREADY tracked by an open `support-agent` issue, so nothing was created
   * and this is the canonical one to point the customer at. A SUCCESS: the customer gets a real
   * link, and it is a better link than a fresh duplicate would have been.
   */
  case Duplicate(issue: IssueRef)
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
   * The one prefix an issue URL may have if we are going to show it to a customer. Derived from
   * [[Repo]], which is a hardcoded constant with no config escape hatch — so a repo rename, org
   * rename, or transfer makes GitHub return a new canonical `html_url`, every filing fails this
   * check, and the agent stops offering links until [[Repo]] is updated. That failure is visible
   * (`outcome=ok_no_link` plus a log line naming this cause) rather than silent.
   */
  private[support] val PublicIssuePrefix: String = s"https://github.com/$Repo/issues/"

  /**
   * Why we have no link to offer for an issue GitHub did create. The metric collapses every failure
   * to `ok_no_link`, so this reason — carried out of the ONE derivation that decides it, never
   * re-inferred by the caller — is what tells an operator which hunt to start.
   */
  enum CreatedParse {
    case Parsed(ref: IssueRef)

    /** Body was truncated, not JSON, or missing `number` / `html_url`. Likely transient. */
    case Unreadable

    /**
     * Body parsed, but `html_url` is not under [[PublicIssuePrefix]] — the repo was renamed or
     * transferred, so EVERY filing loses its link until [[Repo]] is updated.
     */
    case ForeignRepo(url: String)
  }

  /**
   * #2461 — read the created issue's identity out of GitHub's 2xx body. Pure and total: anything we
   * cannot read yields a non-[[CreatedParse.Parsed]] reason, so the agent falls back to "filed, no
   * link" rather than quoting a link we invented.
   *
   * The URL is additionally required to sit under [[PublicIssuePrefix]]. The request always targets
   * [[Repo]] so this holds in practice — the check makes "the link we hand a customer points at our
   * public repo" a property of the code rather than of a comment.
   *
   * This is the SINGLE place a create-response is judged: the reason rides out on the return so no
   * caller has to re-derive it (and mis-attribute it once a third condition exists).
   */
  def parseCreatedDetailed(body: String): CreatedParse =
    body.fromJson[CreatedIssue] match {
      case Left(_)  => CreatedParse.Unreadable
      case Right(c) =>
        if c.htmlUrl.startsWith(PublicIssuePrefix) then
          CreatedParse.Parsed(IssueRef(c.number, c.htmlUrl))
        else CreatedParse.ForeignRepo(c.htmlUrl)
    }

  // ── #2458: search-before-file duplicate detection ───────────────────────────────────────────
  //
  // The agent files AUTONOMOUSLY, so the duplicate rate is driven by how often a topic comes up,
  // not by how often a customer asks for an issue: the first two issues it ever filed (#2455 /
  // #2457, 84s apart, two threads) were the same feature request. The per-thread rate limiter
  // cannot see across threads and the global one bounds volume, not redundancy.
  //
  // Of the three directions in #2458 this is the "search before filing" one, and it is the one that
  // matches the failure. An idempotency marker needs the AGENT to mint a stable topic slug across
  // independent sessions — the same judgement that produced two different titles for one request,
  // so it would have missed this very case. An operator holding queue solves it but puts a human in
  // the loop of an explicitly autonomous feature and leaves the customer with no link to quote.
  // Searching costs one GET against a repo whose open `support-agent` set is tens of issues, uses
  // the token we already have, and needs nothing from the agent.

  /**
   * The bounded label space of `support_issue_dedup_total{outcome}`. A type rather than string
   * literals at the call sites so the metric's vocabulary is enumerable and cannot drift — the same
   * discipline `SupportResponder.AgentActionResult` applies to its series.
   */
  enum DedupOutcome {

    /** An already-open issue covers this topic; nothing was created. */
    case Matched

    /** We looked and found nothing — a real filing followed. */
    case NoMatch

    /**
     * We could NOT look (transport error, non-2xx, unreadable list) and filed unchecked. Never
     * self-heals if it is the token losing `Issues:read` or GitHub's list shape drifting.
     */
    case ScanError

    def label: String = this match {
      case Matched   => "matched"
      case NoMatch   => "no_match"
      case ScanError => "scan_error"
    }
  }

  /** An already-open `support-agent` issue — the candidate set a new filing is matched against. */
  final case class OpenIssue(number: Int, url: String, title: String)

  private final case class ListedIssue(
      number: Int,
      @jsonField("html_url") htmlUrl: String,
      title: String,
  )
  private object ListedIssue {
    given JsonDecoder[ListedIssue] = DeriveJsonDecoder.gen[ListedIssue]
  }

  /**
   * How many open `support-agent` issues we consider. One page — the label's open set is small by
   * design (an operator prunes it), and a dedup that silently stopped looking would be worse than
   * one with a stated ceiling, so [[Live]] logs when the page comes back full.
   */
  val DuplicateScanPageSize: Int = 100

  /**
   * Read the list-issues response into candidates. Same discipline as [[parseCreatedDetailed]]:
   * anything unreadable yields no candidates (we file rather than guess), and every candidate must
   * sit under [[PublicIssuePrefix]] — which also drops the pull requests GitHub's issues endpoint
   * mixes in, since their `html_url` is `/pull/`, not `/issues/`.
   */
  def parseOpenIssues(body: String): List[OpenIssue] =
    body.fromJson[List[ListedIssue]] match {
      case Left(_)  => Nil
      case Right(l) =>
        l.collect {
          case i if i.htmlUrl.startsWith(PublicIssuePrefix) =>
            OpenIssue(i.number, i.htmlUrl, i.title)
        }
    }

  /**
   * Words that carry no topic — dropped before comparison. Without this, every "Feature request:"
   * title shares two tokens with every other one and short titles drift over the threshold on
   * boilerplate alone.
   */
  private val TitleStopWords: Set[String] =
    Set(
      "feature",
      "request",
      "bug",
      "issue",
      "report",
      "support",
      "the",
      "a",
      "an",
      "and",
      "or",
      "for",
      "with",
      "when",
      "not",
      "from",
      "to",
      "in",
      "of",
      "on",
      "at",
      "is",
      "are",
      "it",
      "we",
      "our",
      "eg",
      "ie",
      "please",
      "should",
      "would",
      "can",
      "cannot",
    )

  /**
   * Topic tokens of a title: lowercased, punctuation-split, stop-words and 1-2 character fragments
   * dropped, and a naive plural fold so `holidays` and `holiday` (or `overrides` and `override`)
   * are the same topic. Deliberately crude — it only has to tell "the same gap, phrased twice" from
   * "a different gap", and the pair in #2458 differs by exactly this kind of wording.
   */
  def titleTokens(title: String): Set[String] =
    title.toLowerCase
      .split("[^a-z0-9]+")
      .iterator
      .filter(_.length > 2)
      .map(t => if t.length > 3 && t.endsWith("s") then t.dropRight(1) else t)
      .filterNot(TitleStopWords.contains)
      .toSet

  /** Jaccard overlap of two titles' topic tokens; 0 when either has no topic words at all. */
  def titleSimilarity(a: String, b: String): Double = {
    val (ta, tb) = (titleTokens(a), titleTokens(b))
    val union    = ta union tb
    if union.isEmpty then 0.0 else (ta intersect tb).size.toDouble / union.size.toDouble
  }

  /**
   * How much topic overlap makes two titles the same request. 0.6 sits above the #2458 pair's worst
   * plausible reading and well above unrelated support titles, which typically share nothing once
   * the boilerplate is dropped. Erring HIGH is the safe direction: a missed duplicate is the status
   * quo, whereas a false match silently swallows a genuine new report.
   */
  val DuplicateThreshold: Double = 0.6

  /**
   * The already-open issue `title` duplicates, if any. Two ways to match, and both need the topic
   * words to actually agree:
   *   - identical token sets — the same request typed twice, however short;
   *   - overlap at or above [[DuplicateThreshold]] AND at least two shared topic words, so a
   *     one-word title cannot pull an unrelated one over the line on a single common noun.
   *
   * Ties break on the LOWEST issue number: the oldest open issue is the canonical one, and the
   * choice must not depend on the order GitHub happened to return.
   */
  def findDuplicate(title: String, open: List[OpenIssue]): Option[OpenIssue] = {
    val tokens = titleTokens(title)
    if tokens.isEmpty then None
    else
      open
        .filter { c =>
          val ct     = titleTokens(c.title)
          val shared = (tokens intersect ct).size
          ct == tokens || (shared >= 2 && titleSimilarity(title, c.title) >= DuplicateThreshold)
        }
        .minByOption(_.number)
  }

  /** [[parseCreatedDetailed]] with the reason discarded — for callers that only need the ref. */
  def parseCreated(body: String): Option[IssueRef] =
    parseCreatedDetailed(body) match {
      case CreatedParse.Parsed(ref) => Some(ref)
      case _                        => None
    }

  /**
   * Live GitHub transport. One blocking HTTPS POST to the REST create-issue endpoint (same
   * JDK-HttpClient shape as the other external clients — no new build dependency). The fine-grained
   * bot token rides as `Authorization: Bearer`; the token's Issues:write-only scope is what makes
   * no-PR structural. Any non-2xx / error is logged and mapped to [[IssueOutcome.Error]].
   */
  final class Live(cfg: SupportConfig) extends GithubIssueClient {
    private val client = HttpClient.newBuilder().connectTimeout(ConnectTimeout).build()

    private def get(url: String) =
      ZIO.attemptBlocking {
        val httpReq = HttpRequest
          .newBuilder(URI.create(url))
          .header("Authorization", s"Bearer ${cfg.githubSupportBotTokenTrimmed}")
          .header("Accept", "application/vnd.github+json")
          .header("X-GitHub-Api-Version", ApiVersion)
          .header("User-Agent", UserAgent)
          .timeout(RequestTimeout)
          .GET()
          .build()
        client.send(httpReq, HttpResponse.BodyHandlers.ofString())
      }

    /**
     * #2458 — the already-open `support-agent` issue this filing duplicates, if any. FAIL-OPEN by
     * construction: every failure answers `None` (meter `scan_error`, log the cause) so the filing
     * still happens. Dedup is a quality improvement on top of filing; it must never become a new
     * way for a genuine report to be lost.
     */
    private def findOpenDuplicate(title: String): UIO[Option[OpenIssue]] =
      get(
        s"$ApiBase/repos/$Repo/issues?state=open&labels=$SupportLabel" +
          s"&per_page=$DuplicateScanPageSize",
      ).flatMap { resp =>
        if resp.statusCode() / 100 != 2 then
          ZIO
            .logWarning(
              s"github duplicate scan failed: HTTP ${resp.statusCode()} " +
                s"(${resp.body().take(300)}) — filing WITHOUT a duplicate check; a persistent " +
                "403/404 here means the bot token lost Issues:read on " + Repo,
            )
            .zipRight(AppMetrics.supportIssueDedup(DedupOutcome.ScanError.label))
            .as(None)
        else {
          val open = parseOpenIssues(resp.body())
          if open.isEmpty && resp.body().trim != "[]" then
            ZIO
              .logWarning(
                "github duplicate scan returned an unreadable list body — filing WITHOUT a " +
                  "duplicate check",
              )
              .zipRight(AppMetrics.supportIssueDedup(DedupOutcome.ScanError.label))
              .as(None)
          else
            ZIO
              .logWarning(
                s"github duplicate scan filled its $DuplicateScanPageSize-issue page — older " +
                  s"open `$SupportLabel` issues were NOT considered; prune the label",
              )
              .when(open.sizeIs >= DuplicateScanPageSize)
              .zipRight(ZIO.succeed(findDuplicate(title, open)))
              .tap(m =>
                AppMetrics.supportIssueDedup(
                  if m.isDefined then DedupOutcome.Matched.label else DedupOutcome.NoMatch.label,
                ),
              )
        }
      }.catchAll(e =>
        ZIO
          .logWarning(
            s"github duplicate scan errored: ${e.getMessage} — filing WITHOUT a duplicate check",
          )
          .zipRight(AppMetrics.supportIssueDedup(DedupOutcome.ScanError.label))
          .as(None),
      )

    def fileIssue(reqRaw: IssueFileRequest): UIO[IssueOutcome] = {
      val req = sanitize(reqRaw)
      // Search BEFORE creating (#2458). The scan is matched on the SANITISED title — the same
      // string that would be filed — so a scrubbed title cannot match its own unscrubbed self.
      findOpenDuplicate(req.title).flatMap {
        case Some(dup) =>
          ZIO
            .logInfo(
              s"support-agent issue not filed: already tracked by #${dup.number} " +
                s"(open `$SupportLabel`, title overlap ≥ $DuplicateThreshold)",
            )
            .as(IssueOutcome.Duplicate(IssueRef(dup.number, dup.url)))
        case None      => create(req)
      }
    }

    private def create(req: IssueFileRequest): UIO[IssueOutcome] =
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
          if resp.statusCode() / 100 == 2 then {
            // #2461: GitHub returns the created issue here — read its number + browser URL so the
            // agent can point the customer at it. An unreadable body is still a successful filing.
            // The metric collapses every no-link cause to `ok_no_link`, so the log line is the
            // operator's only discriminator — it reads the reason off the ONE derivation rather
            // than re-inferring which condition failed.
            parseCreatedDetailed(resp.body()) match {
              case CreatedParse.Parsed(ref)    =>
                ZIO.succeed(IssueOutcome.Filed(Some(ref)))
              case CreatedParse.Unreadable     =>
                ZIO
                  .logWarning(
                    "github issue filed but the create response was unreadable; no link to offer",
                  )
                  .as(IssueOutcome.Filed(None))
              case CreatedParse.ForeignRepo(u) =>
                ZIO
                  .logWarning(
                    s"github issue filed but its html_url ($u) is not under $PublicIssuePrefix " +
                      s"— was $Repo renamed or transferred? every filing loses its link until " +
                      "GithubIssueClient.Repo is updated",
                  )
                  .as(IssueOutcome.Filed(None))
            }
          } else
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

  /**
   * Test client: records every (SANITISED) issue request and reports Filed. The recorder stores the
   * scrubbed request so the "issue body contains no raw household data" pin asserts against exactly
   * what would leave the process.
   */
  final case class Recorder(issues: Ref[List[IssueFileRequest]])

  /** First issue number the recorder hands out — arbitrary, but stable so specs can assert it. */
  val RecorderFirstIssueNumber: Int = 9001

  def recording(rec: Recorder): GithubIssueClient = new GithubIssueClient {

    /** The issue number the recorder assigned to the request at index `i`. */
    private def numberAt(i: Int): Int = RecorderFirstIssueNumber + i

    def fileIssue(req: IssueFileRequest): UIO[IssueOutcome] = {
      val sane = sanitize(req)
      // #2458: the recorder runs the SAME `findDuplicate` against what it has already recorded, so
      // the "an open issue already covers this" branch is exercised end-to-end through the route
      // rather than only inside Live's HTTP layer. Keeping the two clients' semantics identical is
      // what makes the feature-level pin meaningful.
      rec.issues.modify { recorded =>
        val open = recorded.zipWithIndex.map { case (r, i) =>
          OpenIssue(numberAt(i), s"$PublicIssuePrefix${numberAt(i)}", r.title)
        }
        findDuplicate(sane.title, open) match {
          case Some(dup) => (IssueOutcome.Duplicate(IssueRef(dup.number, dup.url)), recorded)
          case None      =>
            // #2461: mint a distinct, deterministic ref per filing — same shape the Live client
            // parses out of GitHub's response, so specs can assert what the agent is told.
            val n = numberAt(recorded.size)
            (IssueOutcome.Filed(Some(IssueRef(n, s"$PublicIssuePrefix$n"))), recorded :+ sane)
        }
      }
    }
  }

  /**
   * Test client for the OTHER #2461 branch: GitHub accepted the filing but its response was
   * unreadable, so there is no link to offer. Behaviourally a success — the route must still answer
   * 200, just without `number`/`url`.
   */
  val filedWithoutRef: GithubIssueClient = new GithubIssueClient {
    def fileIssue(req: IssueFileRequest): UIO[IssueOutcome] =
      ZIO.succeed(IssueOutcome.Filed(None))
  }

  def recorder: UIO[Recorder] = Ref.make(List.empty[IssueFileRequest]).map(Recorder.apply)
}
