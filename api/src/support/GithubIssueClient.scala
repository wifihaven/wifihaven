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
 * **Issues ONLY** on `wifihaven/wifihaven` — **no `contents`, no `pull_requests`**. (#2458 added a
 * LIST call for the duplicate check, so the token is read+write on Issues, not write-alone.) That
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
            .logInfo(
              "support-agent issue filing ENABLED (fine-grained Issues-scoped bot token; the " +
                "#2458 duplicate check also LISTS issues, so it needs Issues:read — a refusal " +
                "meters support_issue_dedup_total{outcome=scan_error,reason=permission})",
            )
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

    /** We could NOT look and filed unchecked. WHY is on [[DedupReason]], never folded in here. */
    case ScanError

    def label: String = this match {
      case Matched   => "matched"
      case NoMatch   => "no_match"
      case ScanError => "scan_error"
    }
  }

  /**
   * WHY a scan could not run — the `reason` dimension of `support_issue_dedup_total`.
   *
   * The scan is fail-open, so a permanently blind dedup files successfully and looks healthy
   * everywhere else; this label is the operator's only discriminator, and folding a 403 in with a
   * timeout would tell them to wait out a problem that never resolves. That is the same
   * config-vs-transient boundary `support_dispatch_total{reason}` (#2416) and
   * `support_thread_history_total` (#2430) draw, and this follows them rather than inventing a
   * third vocabulary (#2458 review). [[Permission]] and [[Schema]] are also logged at ERROR with
   * the fix named; [[Transient]] is a warning.
   */
  enum DedupReason {

    /** 401 / 403 / 404: the bot token cannot list issues. A PROVISIONING GAP; never self-heals. */
    case Permission

    /** 2xx whose body we could not decode — GitHub's list shape drifted. Never self-heals. */
    case Schema

    /** Transport, timeout, 5xx, or any other non-2xx. May self-heal. */
    case Transient

    /**
     * Carried on every non-error sample so no series is missing the label. Named `NotApplicable`,
     * not `None`, so it can never be misread as `scala.None` in a file whose neighbouring
     * signatures are `UIO[Option[OpenIssue]]`.
     */
    case NotApplicable

    def label: String = this match {
      case Permission    => "permission"
      case Schema        => "schema"
      case Transient     => "transient"
      case NotApplicable => "none"
    }
  }
  object DedupReason {

    /** True for the causes that will NOT resolve on their own. The ONE place that line is drawn. */
    private def isPermanent(r: DedupReason): Boolean = r match {
      case Permission | Schema       => true
      case Transient | NotApplicable => false
    }

    /**
     * The `reason` values the expect-0 "permanently blind" panel must match, DERIVED from the enum
     * rather than hand-listed in the dashboard JSON. `SupportMetricsContractSpec` pins the panel's
     * `reason=~` alternation against this, so a new never-self-healing cause added here fails that
     * test instead of silently dropping out of the one panel that makes a blind dedup visible — the
     * exact drift the #2460 dead-end panel pin exists to catch (#2458 review, round 2).
     *
     * EQUALITY, not containment: a transient cause counted here would make an expect-0 panel cry
     * wolf on ordinary timeouts, which is how such a panel stops being read.
     *
     * `lazy` because it reads the enum's synthetic `values` from inside the enum's own explicitly
     * declared companion — an eager val there depends on Scala 3 initialisation order.
     */
    lazy val NeverSelfHealing: Set[String] = values.filter(isPermanent).map(_.label).toSet
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
   * The outcome of reading a list-issues response. Mirrors [[CreatedParse]]: "we could not read the
   * body" and "we read it and it held no usable candidates" are DIFFERENT facts, and the caller
   * meters them differently, so the distinction rides out on the return instead of being
   * reconstructed downstream (#2458 review — a `body != "[]"` reconstruction reports a readable
   * list whose entries were all filtered as unreadable, which lands in an expect-0 alert).
   */
  enum ListParse {

    /**
     * @param open
     *   candidates under [[PublicIssuePrefix]]; may be empty on a readable, genuinely empty list.
     * @param decoded
     *   how many entries the body held BEFORE filtering — the number to compare against
     *   [[DuplicateScanPageSize]], since filtering can never make a full page look full.
     */
    case Parsed(open: List[OpenIssue], decoded: Int)

    /** Not JSON, truncated, or not the array-of-issues shape we expect. */
    case Unreadable
  }

  /**
   * Read the list-issues response. Same discipline as [[parseCreatedDetailed]]: pure and total, and
   * every candidate must sit under [[PublicIssuePrefix]] — which also drops the pull requests
   * GitHub's issues endpoint mixes in, since their `html_url` is `/pull/`, not `/issues/`.
   */
  def parseOpenIssuesDetailed(body: String): ListParse =
    body.fromJson[List[ListedIssue]] match {
      case Left(_)  => ListParse.Unreadable
      case Right(l) =>
        ListParse.Parsed(
          l.collect {
            case i if i.htmlUrl.startsWith(PublicIssuePrefix) =>
              OpenIssue(i.number, i.htmlUrl, i.title)
          },
          l.size,
        )
    }

  /**
   * [[parseOpenIssuesDetailed]] with the reason discarded. No production caller — [[Live]] needs
   * the distinction to meter `schema` — so today this serves specs that assert the candidate list
   * without destructuring the ADT. Kept rather than inlined so a future caller that genuinely does
   * not care reaches for this instead of writing a second `fromJson` of its own.
   */
  def parseOpenIssues(body: String): List[OpenIssue] =
    parseOpenIssuesDetailed(body) match {
      case ListParse.Parsed(open, _) => open
      case ListParse.Unreadable      => Nil
    }

  /**
   * Words that carry no topic — dropped before comparison. Without this, every "Feature request:"
   * title shares two tokens with every other one and short titles drift over the threshold on
   * boilerplate alone.
   *
   * The SCRUBBER'S placeholders are handled separately, in [[titleTokens]], by deleting the whole
   * literal before tokenising — NOT by listing `redacted` / `mac` / `email` / `number` here. Adding
   * them to this list would strip those words out of titles that never carried PII, and in this
   * product that throws away the very words that distinguish one report from another: "Cannot
   * change MAC address" and "Cannot change email address" would reduce to the SAME token set and
   * the second customer's report would be silently swallowed (#2458 review, round 2).
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
   * Topic tokens of a title: scrubber placeholders removed, then lowercased, punctuation-split,
   * stop-words and 1-2 character fragments dropped, and a naive plural fold so `holidays` and
   * `holiday` (or `overrides` and `override`) are the same topic. Deliberately crude — it only has
   * to tell "the same gap, phrased twice" from "a different gap", and the pair in #2458 differs by
   * exactly this kind of wording.
   *
   * The placeholder strip comes FIRST and is the whole literal: titles reach the matcher after
   * [[sanitize]], so one that carried PII arrives as `"… reported by [redacted-email]"`, and
   * `redacted`/`email` would otherwise be topic words shared by every PII-bearing title — inflating
   * overlap, and making two titles whose only surviving words are placeholders match EXACTLY (a
   * second customer's genuine report dropped, pointed at an unrelated issue). Deleting the literal
   * closes that without touching `mac` / `email` / `number` where the AGENT actually wrote them.
   *
   * Two residuals, both deliberate and both fail-OPEN (they cost a duplicate, never a lost report):
   *   - deleting the span erases the DIFFERENCE between redaction kinds, so two titles that differ
   *     only inside it ("Cannot change [redacted-mac] address" vs "… [redacted-email] address")
   *     reduce to the same tokens and the second is treated as a duplicate. It only bites when a
   *     TITLE carried PII, which the agent is instructed not to write; substituting a per-kind
   *     opaque token would separate them, at the cost of putting a synthetic shared token back into
   *     every title of that kind. Not worth the trade at this volume — revisit if it is ever seen.
   *   - a title that was mostly PII can fall below [[MinTopicTokens]] once stripped, which disables
   *     dedup for it entirely and files a second issue. That is the safe direction.
   */
  def titleTokens(title: String): Set[String] =
    SupportPrivacy
      .stripPlaceholders(title)
      .toLowerCase
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
   * The fewest topic words a title must have before it can match ANYTHING. A one-word title carries
   * too little to distinguish "the same request" from "another report that happens to share a
   * noun", and the cost of the two failure directions is not symmetric: refusing to dedup a
   * one-word title just files a second issue (the status quo), whereas matching it wrongly drops a
   * real report and points the customer at something unrelated. So the floor applies to the
   * identical-token-sets branch too, not only to the similarity one (#2458 review).
   */
  val MinTopicTokens: Int = 2

  /**
   * The already-open issue `title` duplicates, if any. Both titles need at least [[MinTopicTokens]]
   * topic words, and then either:
   *   - identical token sets — the same request typed twice; or
   *   - overlap at or above [[DuplicateThreshold]] AND at least [[MinTopicTokens]] shared words, so
   *     an unrelated title cannot be pulled over the line on a single common noun.
   *
   * Ties break on the LOWEST issue number: the oldest open issue is the canonical one, and the
   * choice must not depend on the order GitHub happened to return.
   */
  def findDuplicate(title: String, open: List[OpenIssue]): Option[OpenIssue] = {
    val tokens = titleTokens(title)
    if tokens.sizeIs < MinTopicTokens then None
    else
      open
        .filter { c =>
          val ct     = titleTokens(c.title)
          val shared = (tokens intersect ct).size
          ct.sizeIs >= MinTopicTokens &&
          (ct == tokens ||
            (shared >= MinTopicTokens && titleSimilarity(title, c.title) >= DuplicateThreshold))
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
   * Live GitHub transport. Two blocking HTTPS calls to the REST issues endpoint (same
   * JDK-HttpClient shape as the other external clients — no new build dependency): a GET that lists
   * the open `support-agent` issues for the #2458 duplicate check, then the POST that creates. The
   * fine-grained bot token rides as `Authorization: Bearer`; its Issues-only scope (no `contents`,
   * no `pull_requests`) is what makes no-PR structural. Any non-2xx / error on the POST is logged
   * and mapped to [[IssueOutcome.Error]]; the GET is fail-open (see [[findOpenDuplicate]]).
   *
   * NOTE the GET needs Issues:**read**. A fine-grained token granted Issues at the write level also
   * carries read, so the existing credential is expected to work unchanged — but that is an
   * inference about GitHub's permission model, not something this code can assert, so the LIST call
   * is metered with a `permission` reason and logged at ERROR if it is ever refused rather than
   * assumed to succeed.
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

    /** The ONE place a scan failure is metered — reason attribution can't drift across branches. */
    private def scanFailed(reason: DedupReason): UIO[Option[OpenIssue]] =
      AppMetrics
        .supportIssueDedup(DedupOutcome.ScanError.label, reason.label)
        .as(None)

    /**
     * #2458 — the already-open `support-agent` issue this filing duplicates, if any. FAIL-OPEN by
     * construction: every failure answers `None` (metered with its [[DedupReason]], logged with the
     * cause) so the filing still happens. Dedup is a quality improvement on top of filing; it must
     * never become a new way for a genuine report to be lost.
     *
     * Fail-open is what makes the reason label load-bearing rather than decorative: a permanently
     * blind check still files successfully and reads healthy on every other panel, so `permission`
     * / `schema` (never self-heal, logged at ERROR with the fix named) vs `transient` is the
     * operator's only signal.
     */
    private def findOpenDuplicate(title: String): UIO[Option[OpenIssue]] =
      get(
        s"$ApiBase/repos/$Repo/issues?state=open&labels=$SupportLabel" +
          s"&per_page=$DuplicateScanPageSize",
      ).flatMap { resp =>
        val status = resp.statusCode()
        if status / 100 != 2 then
          // 401/403/404 is the bot token's grant, not an outage — the same permanent-vs-transient
          // line the rest of the support subsystem draws, and the reason an ERROR is warranted.
          if status == 401 || status == 403 || status == 404 then
            ZIO
              .logError(
                s"github duplicate scan REFUSED: HTTP $status (${resp.body().take(300)}) — " +
                  s"PROVISIONING GAP: the support bot token cannot LIST issues on $Repo, so every " +
                  "filing is duplicate-checked blind and duplicates will return. Grant the " +
                  "fine-grained token Issues:read on that repo.",
              )
              .zipRight(scanFailed(DedupReason.Permission))
          else
            ZIO
              .logWarning(
                s"github duplicate scan failed: HTTP $status (${resp.body().take(300)}) — filing " +
                  "WITHOUT a duplicate check",
              )
              .zipRight(scanFailed(DedupReason.Transient))
        else
          parseOpenIssuesDetailed(resp.body()) match {
            case ListParse.Unreadable            =>
              ZIO
                .logError(
                  "github duplicate scan returned a 2xx we could not decode — GitHub's " +
                    "list-issues shape drifted; filing WITHOUT a duplicate check until " +
                    "GithubIssueClient.ListedIssue is updated",
                )
                .zipRight(scanFailed(DedupReason.Schema))
            case ListParse.Parsed(open, decoded) =>
              // `decoded` is the PRE-filter count: a full page containing one PR would otherwise
              // never trip this, and silently dropping older issues is the failure it guards.
              ZIO
                .logWarning(
                  s"github duplicate scan filled its $DuplicateScanPageSize-issue page — older " +
                    s"open `$SupportLabel` issues were NOT considered; prune the label",
                )
                .when(decoded >= DuplicateScanPageSize)
                .zipRight(ZIO.succeed(findDuplicate(title, open)))
                .tap(m =>
                  AppMetrics.supportIssueDedup(
                    if m.isDefined then DedupOutcome.Matched.label else DedupOutcome.NoMatch.label,
                    DedupReason.NotApplicable.label,
                  ),
                )
          }
      }.catchAll(e =>
        ZIO
          .logWarning(
            s"github duplicate scan errored: ${e.getMessage} — filing WITHOUT a duplicate check",
          )
          .zipRight(scanFailed(DedupReason.Transient)),
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
