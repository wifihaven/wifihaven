package wifihaven.api.press

import wifihaven.api.PressOutreachConfig
import wifihaven.api.notify.{EmailMarkdown, EmailOutcome, EmailSender}
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import zio.*
import zio.json.*

import java.io.InputStream
import scala.jdk.CollectionConverters.*

/**
 * #2233 (beta press outreach, epic #2197) — the operator-run press-OUTREACH send capability:
 * compose the launch release + a per-contact personalized pitch from the media list and email them,
 * FROM the press address, with REPLY-TO pointed at the #2203 press inbox so a journalist's reply
 * routes to the autonomous responder.
 *
 * This object is the PURE, DB-free heart of it (loader + composer + the batched send loop over an
 * injected [[EmailSender]]), so the whole contract — dry-run renders the right per-recipient email,
 * the send is idempotent + rate-limited, the default is dry-run and sending needs an explicit
 * confirm — is unit-testable without a network. The admin HTTP surface + the press_messages
 * idempotency ledger + the metric live in [[wifihaven.api.routes.PressOutreachRoutes]].
 *
 * SAFETY (the load-bearing invariants, mirrored by the specs):
 *   - `confirmed=false` (the default, and every preview) NEVER transmits — every contact resolves
 *     to [[Outcome.DryRun]]. Only `confirmed=true` reaches the transport.
 *   - a contact already in `alreadySentPeers` (the press_messages outbound ledger) is skipped, so a
 *     re-run of a partially-completed batch never double-sends.
 *   - the reply target is ALWAYS `cfg.replyTo` (the press inbox), never a per-contact address — a
 *     journalist replies into the responder, not to a random mailbox.
 *   - `testRecipient`, when set, redirects the actual transmission to ONE safe address (for
 *     validating a real send) while idempotency + recording still key on the real contact — so a
 *     test run can never mark a real journalist as "already contacted".
 */
object PressOutreach {

  // ── Models ──────────────────────────────────────────────────────────────────

  /**
   * One media-list target (parsed from media-contacts.yml). `email` is present ONLY where a
   * verified published address exists (none as of the 2026-07-12 research pass — see the manifest
   * header); the operator supplies addresses at send time via `emailOverrides`. `contactUrl` is the
   * form/tip-line to reach a form-only outlet manually.
   */
  final case class Contact(
      id: String,
      outlet: String,
      person: String,
      priority: Int,
      angle: String,
      pitch: String,
      email: Option[String],
      contactUrl: Option[String],
  ) derives JsonCodec

  /** The bounded per-contact result — also the `press_outreach_total{outcome}` label space. */
  enum Outcome   {
    case Sent
    case Failed
    case SkippedAlreadySent
    case SkippedNoEmail
    case SkippedDisabled
    case DryRun
  }
  object Outcome {
    def label(o: Outcome): String = o match {
      case Sent               => "sent"
      case Failed             => "failed"
      case SkippedAlreadySent => "skipped_already_sent"
      case SkippedNoEmail     => "skipped_no_email"
      case SkippedDisabled    => "skipped_disabled"
      case DryRun             => "dry_run"
    }
  }

  /** A fully-rendered email (envelope + composed HTML body) for preview and for the send loop. */
  final case class RenderedEmail(
      contactId: String,
      outlet: String,
      person: String,
      from: String,
      replyTo: String,
      to: Option[String],
      subject: String,
      htmlBody: String,
  ) derives JsonCodec

  /**
   * The outcome of one contact in a run. `peerEmail` is the REAL contact address the idempotency
   * ledger keys on (never the test recipient); `recordable` is true only for a real (non-test)
   * transmit the route should persist to press_messages.
   */
  final case class ContactResult(
      contactId: String,
      outlet: String,
      outcome: String,
      to: Option[String],
      peerEmail: Option[String],
      subject: String,
      recordable: Boolean,
  ) derives JsonCodec

  /** The whole run's report — the endpoint response and the operator's audit surface. */
  final case class Report(
      mode: String,
      totalContacts: Int,
      emailable: Int,
      formOnly: Int,
      unresolvedPlaceholders: List[String],
      results: List[ContactResult],
      emails: List[RenderedEmail],
  ) derives JsonCodec

  // ── Contact manifest loading ─────────────────────────────────────────────────

  val ContactsResource: String = "/press/media-contacts.yml"
  val ReleaseResource: String  = "/press/release.md"

  /**
   * Load + parse the bundled media-contacts manifest from the classpath. Fails fast if malformed.
   */
  def loadContacts(resource: String = ContactsResource): Task[List[Contact]] =
    withResource(resource) { in =>
      parseContacts(parseYaml(in, resource), resource).fold(
        e => throw new RuntimeException(s"$resource: $e"),
        identity,
      )
    }

  /** Load the bundled sendable release template (raw, unresolved) from the classpath. */
  def loadReleaseTemplate(resource: String = ReleaseResource): Task[String] =
    withResource(resource)(in => new String(in.readAllBytes(), "UTF-8"))

  private[api] def parseContacts(
      root: java.util.Map[String, AnyRef],
      source: String,
  ): Either[String, List[Contact]] =
    Option(root.get("contacts")) match {
      case Some(xs: java.util.List[?]) =>
        val parsed = xs.asScala.toList.zipWithIndex.map { case (raw, i) =>
          raw match {
            case m: java.util.Map[?, ?] =>
              parseContact(m.asInstanceOf[java.util.Map[String, AnyRef]], s"$source[$i]")
            case other                  => Left(s"$source[$i]: expected a mapping, got $other")
          }
        }
        parsed.partitionMap(identity) match {
          case (Nil, cs) =>
            val ids = cs.map(_.id)
            if ids.distinct.size != ids.size then
              Left(s"duplicate contact id(s): ${ids.diff(ids.distinct).mkString(",")}")
            else Right(cs)
          case (errs, _) => Left(errs.mkString("; "))
        }
      case other                       => Left(s"expected 'contacts: [...]' list, got $other")
    }

  private def parseContact(
      m: java.util.Map[String, AnyRef],
      where: String,
  ): Either[String, Contact] = {
    def str(key: String): Option[String]            =
      Option(m.get(key)).map(_.toString.trim).filter(_.nonEmpty)
    def reqStr(key: String): Either[String, String] =
      str(key).toRight(s"$where: missing required field '$key'")
    for {
      id     <- reqStr("id")
      outlet <- reqStr("outlet")
      person <- reqStr("person")
      angle  <- reqStr("angle")
      // REQUIRED, deliberately: see `pitchText` — there is no generic body to fall back to, so a
      // contact added without an authored pitch fails to load rather than getting boilerplate.
      pitch  <- reqStr("pitch")
      priority = Option(m.get("priority")).map(_.toString.trim.toIntOption).flatten.getOrElse(3)
    } yield Contact(
      id = id,
      outlet = outlet,
      person = person,
      priority = priority,
      angle = angle,
      pitch = pitch,
      email = str("email"),
      contactUrl = str("contactUrl"),
    )
  }

  // ── Release template: strip / fill / placeholder-guard ───────────────────────

  /**
   * The sendable body of the release template: drop the leading `#` comment/header block and
   * everything up to and including the first `---` fence line, then trim. So the resource can carry
   * an in-file comment header (the fill-token legend) that never reaches a journalist.
   */
  def sendableBody(raw: String): String = {
    val lines = raw.linesIterator.toList
    val idx   = lines.indexWhere(_.trim == "---")
    val body  = if idx >= 0 then lines.drop(idx + 1) else lines
    body.mkString("\n").trim
  }

  /** Substitute `{{token}}` occurrences from `fill` (untouched if a token has no value). */
  def applyFill(template: String, fill: Map[String, String]): String =
    fill.foldLeft(template) { case (acc, (k, v)) => acc.replace(s"{{$k}}", v) }

  private val TokenRe = "\\{\\{([a-zA-Z0-9_]+)\\}\\}".r

  /**
   * The distinct `{{token}}` names still unresolved in `body` — a send REFUSES while any remain.
   */
  def unresolvedTokens(body: String): List[String] =
    TokenRe.findAllMatchIn(body).map(_.group(1)).toList.distinct

  /**
   * Resolve the raw template with `fill`; returns (sendable body, still-unresolved token names).
   */
  def resolveRelease(rawTemplate: String, fill: Map[String, String]): (String, List[String]) = {
    val resolved = applyFill(sendableBody(rawTemplate), fill)
    (resolved, unresolvedTokens(resolved))
  }

  // ── Composition ──────────────────────────────────────────────────────────────

  def subjectFor(c: Contact): String =
    s"WifiHaven: open-source parental controls enforced at the router (for ${c.outlet})"

  /**
   * The personalized pitch that precedes the release.
   *
   * Only the greeting and the sign-off live here. The BODY is `c.pitch`, authored per outlet in
   * media-contacts.yml — there is deliberately no template to interpolate the outlet name into and
   * no fallback if the field is absent (`parseContact` rejects it). The previous version built the
   * body from `outlet` + `angle`, which meant twenty-one journalists would have received the same
   * three paragraphs with two nouns swapped; from the receiving end that is a blast, and a blast is
   * the outcome this whole file exists to avoid.
   */
  private def pitchText(c: Contact): String =
    s"""Hi ${c.person},
       |
       |${c.pitch}
       |
       |The full release is below. We'd be glad to set up a demo, share the press kit, or answer anything.
       |
       |Best,
       |Sameer
       |WifiHaven""".stripMargin

  /**
   * Render a blank-line-separated plain-text/markdown block into escaped paragraphs.
   *
   * #2677: this was a second copy of the responder's `htmlBody` and carried the same defect —
   * escape and wrap, never render — so the release's markdown would have reached journalists as
   * literal markers the first time #2233 sent. Both now call the one renderer.
   */
  private def paragraphs(text: String): String = EmailMarkdown.render(text)

  /**
   * Render one contact's email. `resolvedReleaseBody` is the already-filled release; `to` is the
   * address we would actually transmit to (the test recipient if set, else the effective contact
   * address), or `None` for a form-only contact.
   */
  def renderEmail(
      c: Contact,
      resolvedReleaseBody: String,
      cfg: PressOutreachConfig,
      to: Option[String],
  ): RenderedEmail = {
    val body =
      s"""<div style="font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;font-size:15px;color:#18181b;line-height:1.5;">
         |${paragraphs(pitchText(c))}
         |<hr style="border:none;border-top:1px solid #e4e4e7;margin:24px 0;">
         |${paragraphs(resolvedReleaseBody)}
         |</div>""".stripMargin
    RenderedEmail(
      contactId = c.id,
      outlet = c.outlet,
      person = c.person,
      from = cfg.fromTrimmed,
      replyTo = cfg.replyToTrimmed,
      to = to,
      subject = subjectFor(c),
      htmlBody = body,
    )
  }

  // ── The batched, idempotent, rate-limited send loop ──────────────────────────

  /**
   * Run a preview (`confirmed=false`) or a real send (`confirmed=true`) over `contacts`, in
   * (priority, id) order.
   *
   * @param emailOverrides
   *   contactId -> verified address the operator supplies at send time (so no fabricated address
   *   lives in-repo, and no redeploy is needed to add one).
   * @param alreadySentPeers
   *   the press_messages outbound ledger — a REAL contact address here is skipped
   *   ([[Outcome.SkippedAlreadySent]]).
   * @param testRecipient
   *   when set, the actual transmission goes to this ONE address; idempotency + `recordable` still
   *   key on the real contact so a test never marks a real journalist contacted.
   * @param confirmed
   *   false (default / preview) NEVER transmits — every contact is [[Outcome.DryRun]]. Only true
   *   reaches the transport.
   * @param emailEnabled
   *   the EmailConfig transport gate; false ⇒ [[Outcome.SkippedDisabled]].
   */
  def run(
      contacts: List[Contact],
      rawReleaseTemplate: String,
      fill: Map[String, String],
      cfg: PressOutreachConfig,
      sender: EmailSender,
      emailOverrides: Map[String, String] = Map.empty,
      alreadySentPeers: Set[String] = Set.empty,
      testRecipient: Option[String] = None,
      confirmed: Boolean = false,
      emailEnabled: Boolean = true,
  ): UIO[Report] = {
    val (releaseBody, unresolved) = resolveRelease(rawReleaseTemplate, fill)
    val ordered                   = contacts.sortBy(c => (c.priority, c.id))

    def effectiveEmail(c: Contact): Option[String] =
      emailOverrides.get(c.id).map(_.trim).filter(_.nonEmpty).orElse(c.email)

    // One contact → (result, rendered email, whether we actually transmitted). Never fails.
    def processOne(c: Contact): UIO[(ContactResult, RenderedEmail)] = {
      val peer   = effectiveEmail(c)
      val sendTo = testRecipient.orElse(peer)
      val email  = renderEmail(c, releaseBody, cfg, sendTo)
      val isTest = testRecipient.isDefined
      def result(o: Outcome, recordable: Boolean): ContactResult =
        ContactResult(c.id, c.outlet, Outcome.label(o), sendTo, peer, email.subject, recordable)

      if !confirmed then ZIO.succeed(result(Outcome.DryRun, recordable = false) -> email)
      else if !emailEnabled then
        ZIO.succeed(result(Outcome.SkippedDisabled, recordable = false) -> email)
      else if peer.isEmpty then
        ZIO.succeed(result(Outcome.SkippedNoEmail, recordable = false) -> email)
      else if peer.exists(alreadySentPeers.contains) then
        ZIO.succeed(result(Outcome.SkippedAlreadySent, recordable = false) -> email)
      else
        // Real transmit. Reply-To is ALWAYS the press inbox (cfg.replyTo). Rate-limit AFTER the send.
        sender
          .sendAs(
            cfg.fromTrimmed,
            Some(cfg.replyToTrimmed),
            sendTo.get,
            email.subject,
            email.htmlBody,
          )
          .flatMap { outcome =>
            val o          = outcome match {
              case EmailOutcome.Sent     => Outcome.Sent
              case EmailOutcome.Failed   => Outcome.Failed
              case EmailOutcome.Disabled => Outcome.SkippedDisabled
            }
            // A test transmit is never recorded to the ledger (it didn't reach the journalist); a
            // real Sent/Failed is recordable so the route persists it + idempotency picks it up.
            val recordable = !isTest && (o == Outcome.Sent || o == Outcome.Failed)
            // Rate-limit AFTER a real transmit. Skip the sleep entirely at zero so it never touches
            // the clock (tests run with delay=0 and must not stall on a non-advancing TestClock).
            val throttle   =
              ZIO.sleep(cfg.perSendDelay).when(cfg.perSendDelay > zio.Duration.Zero).unit
            throttle.as(result(o, recordable) -> email)
          }
    }

    for {
      pairs <- ZIO.foreach(ordered)(processOne) // sequential: preserves order + rate-limit spacing
    } yield {
      val results   = pairs.map(_._1)
      val emails    = pairs.map(_._2)
      val emailable = ordered.count(c => effectiveEmail(c).isDefined)
      Report(
        mode = if confirmed then "send" else "preview",
        totalContacts = ordered.size,
        emailable = emailable,
        formOnly = ordered.size - emailable,
        unresolvedPlaceholders = unresolved,
        results = results,
        emails = emails,
      )
    }
  }

  // ── snakeyaml plumbing (mirrors BundledBlocklists) ───────────────────────────

  private def withResource[A](resource: String)(f: InputStream => A): Task[A] =
    ZIO.attempt {
      val in = getClass.getResourceAsStream(resource)
      if in == null then throw new RuntimeException(s"resource not found: $resource")
      try f(in)
      finally in.close()
    }

  private def parseYaml(in: InputStream, source: String): java.util.Map[String, AnyRef] = {
    val opts = new LoaderOptions()
    val yaml = new Yaml(new SafeConstructor(opts))
    yaml.load[AnyRef](in) match {
      case m: java.util.Map[?, ?] => m.asInstanceOf[java.util.Map[String, AnyRef]]
      case other => throw new RuntimeException(s"$source: expected a mapping at root, got $other")
    }
  }

  /** Parse a contacts YAML from a raw string — the seam the unit spec drives without a resource. */
  private[api] def parseContactsYaml(yaml: String): Either[String, List[Contact]] = {
    val opts = new LoaderOptions()
    new Yaml(new SafeConstructor(opts)).load[AnyRef](yaml) match {
      case m: java.util.Map[?, ?] =>
        parseContacts(m.asInstanceOf[java.util.Map[String, AnyRef]], "<inline>")
      case other                  => Left(s"expected a mapping at root, got $other")
    }
  }
}
