package wifihaven.api.unit

import wifihaven.api.PressOutreachConfig
import wifihaven.api.notify.EmailSender
import wifihaven.api.press.PressOutreach
import wifihaven.api.press.PressOutreach.{Contact, Outcome}
import zio.*
import zio.test.*

/**
 * #2233 — the pure heart of the press-OUTREACH send capability: manifest parsing, release
 * strip/fill/placeholder-guard, per-recipient composition, and the batched idempotent send loop
 * driven by a RECORDING [[EmailSender]] (never a live Resend). The load-bearing SAFETY invariants
 * are pinned here so a regression can't quietly turn dry-run into a send or double-blast a
 * journalist:
 *
 *   - the DEFAULT (confirmed=false) NEVER transmits — every contact is DryRun, the recorder stays
 *     empty;
 *   - a peer already in the ledger is SkippedAlreadySent (idempotent / resumable);
 *   - a form-only contact (no verified email) is SkippedNoEmail — never sent;
 *   - a confirmed send goes out FROM the press address with REPLY-TO the press inbox;
 *   - a testRecipient redirects the real transmit to one safe address while idempotency + recording
 *     still key on the real contact.
 */
object PressOutreachSpec extends ZIOSpecDefault {

  private val cfg = PressOutreachConfig(
    enabled = true,
    fromAddress = "WifiHaven Press <press@wifihaven.net>",
    replyTo = "press@wifihaven.net",
    perSendDelayMillis = 0,
  )

  private val template =
    """# a header comment (fill legend) — stripped before send
      |# city, date
      |---
      |FOR IMMEDIATE RELEASE
      |
      |{{city}} — {{date}} — WifiHaven opened its beta today. Founding $6/month (or $57/year).
      |
      |The beta is at {{betaSignupUrl}}.
      |""".stripMargin

  private val fullFill = Map(
    "city"          -> "San Francisco",
    "date"          -> "July 20, 2026",
    "betaSignupUrl" -> "https://wifihaven.net/beta",
  )

  // Two Priority-1 form-only contacts (no verified email — the real manifest's posture) + one P2.
  private val cForm1   = Contact(
    "alpha",
    "Outlet Alpha",
    "Alice",
    1,
    "your OpenWRT beat",
    "Alpha's authored pitch about the nftables drop.",
    None,
    Some("https://a/tip"),
  )
  private val cForm2   = Contact(
    "bravo",
    "Outlet Bravo",
    "Bob",
    2,
    "your firewall videos",
    "Bravo's authored pitch about DNS never being the enforcer.",
    None,
    Some("https://b/tip"),
  )
  private val contacts = List(cForm2, cForm1) // deliberately out of order to test sorting

  private def recorder = Ref.make(List.empty[EmailSender.Sent])

  def spec = suite("PressOutreach — compose + idempotent send loop (#2233)")(
    test("the BUNDLED manifest + release load, parse, and carry no fabricated emails") {
      for {
        cs   <- PressOutreach.loadContacts()
        tmpl <- PressOutreach.loadReleaseTemplate()
        (body, unresolved) = PressOutreach.resolveRelease(tmpl, Map.empty)
      } yield assertTrue(
        cs.nonEmpty,
        cs.map(_.id).distinct.size == cs.size,
        cs.forall(_.id.nonEmpty),
        // Fabrication guard: NO target ships a verified email in-repo (media-list.md rule) — the
        // operator supplies addresses at send time. A future PR adding a real address must be a
        // deliberate, reviewed change to THIS assertion.
        cs.forall(_.email.isEmpty),
        // Every bundled target has a pitch written FOR IT. Distinctness is the assertion that
        // bites: `pitch` being required already rules out absent, but nothing stops one pitch
        // being pasted into all 21 rows, and that — not a typo — is the failure mode (a blast).
        cs.map(_.pitch).distinct.size == cs.size,
        // The angle is form-submission metadata and must not be the email body.
        cs.forall(c => !c.pitch.contains(c.angle)),
        body.contains("FOR IMMEDIATE RELEASE"),
        // Pin the load-bearing pricing/positioning claims IN THE SENDABLE RESOURCE so a bad edit to
        // the copy (or drift from the authored docs/marketing/press-release.md) is caught in CI —
        // these are the facts a journalist quotes (SHOULD-FIX from the #2233 review).
        body.contains("$6/month or $57/year"),
        body.contains("$10/month or $96/year"),
        body.contains("self-host"),
        body.contains("connection layer"),
        // #2233 staging pass — the two claims most likely to be quoted wrong, and the ones the
        // previous draft got wrong: 25 is the flip TRIGGER (nothing caps signups at 25), and the
        // supported substrate is FLASHED vanilla OpenWrt, not vendor stock firmware.
        body.contains("Once 25 active households are in, a 60-day countdown"),
        body.contains("Vendor stock firmware is not a supported target"),
        // The release still carries exactly the fill tokens the runbook documents.
        unresolved.toSet == Set("date", "founderName", "betaSignupUrl", "pressKitUrl"),
      )
    },
    test("manifest YAML parses; email present only where given") {
      val yaml   =
        """contacts:
          |  - id: withmail
          |    outlet: Mail Outlet
          |    person: Pat
          |    priority: 1
          |    angle: their beat
          |    pitch: Pat's authored pitch.
          |    email: pat@outlet.example
          |  - id: formonly
          |    outlet: Form Outlet
          |    person: Fin
          |    priority: 2
          |    angle: their other beat
          |    pitch: Fin's authored pitch.
          |    contactUrl: https://form
          |""".stripMargin
      val parsed = PressOutreach.parseContactsYaml(yaml)
      assertTrue(
        parsed.isRight,
        parsed.exists(_.size == 2),
        parsed.exists(_.exists(c => c.id == "withmail" && c.email.contains("pat@outlet.example"))),
        parsed.exists(_.exists(c => c.id == "formonly" && c.email.isEmpty)),
      )
    },
    test("a contact with no AUTHORED pitch is a parse error — there is no generic fallback") {
      // #2233 staging pass: the pitch a journalist reads used to be one template with the outlet
      // name and angle interpolated into it, which is what a blast looks like from the receiving
      // end. The per-outlet pitch is now authored in the manifest and REQUIRED, so the composer has
      // no way to fall back to a generic body — a contact added without one fails to load rather
      // than quietly getting boilerplate.
      val missingPitch = PressOutreach.parseContactsYaml(
        """contacts:
          |  - id: nopitch
          |    outlet: Outlet
          |    person: Pat
          |    priority: 1
          |    angle: their beat
          |    contactUrl: https://form
          |""".stripMargin,
      )
      assertTrue(
        missingPitch.isLeft,
        missingPitch.swap.exists(_.contains("pitch")),
      )
    },
    test("sendableBody strips the comment header before the --- fence") {
      val body = PressOutreach.sendableBody(template)
      assertTrue(
        !body.contains("header comment"),
        body.startsWith("FOR IMMEDIATE RELEASE"),
        body.contains("{{city}}"),
      )
    },
    test("resolveRelease reports unresolved tokens until every fill is supplied") {
      val (_, missing)  = PressOutreach.resolveRelease(template, Map("city" -> "SF"))
      val (body, none0) = PressOutreach.resolveRelease(template, fullFill)
      assertTrue(
        missing.contains("date"),
        missing.contains("betaSignupUrl"),
        !missing.contains("city"),
        none0.isEmpty,
        body.contains("San Francisco — July 20, 2026"),
      )
    },
    test("a rendered email carries the pitch, the release, and the press envelope") {
      val (body, _) = PressOutreach.resolveRelease(template, fullFill)
      val email     = PressOutreach.renderEmail(cForm1, body, cfg, Some("to@x"))
      assertTrue(
        email.subject.contains("Outlet Alpha"),
        email.from == "WifiHaven Press <press@wifihaven.net>",
        email.replyTo == "press@wifihaven.net",
        email.htmlBody.contains("Alice"),                  // pitch greets the person
        email.htmlBody.contains("Alpha's authored pitch"), // the AUTHORED body, not a template
        // `angle` is editorial metadata for form submissions — it must NOT leak into the email.
        !email.htmlBody.contains("your OpenWRT beat"),
        email.htmlBody.contains("FOR IMMEDIATE RELEASE"),  // the release below
      )
    },
    test("DEFAULT (confirmed=false) is a dry-run — nothing is transmitted") {
      for {
        ref  <- recorder
        rpt  <- PressOutreach.run(
          contacts,
          template,
          fullFill,
          cfg,
          EmailSender.recording(ref),
          emailOverrides = Map("alpha" -> "a@x", "bravo" -> "b@x"),
          confirmed = false,
        )
        sent <- ref.get
      } yield assertTrue(
        rpt.mode == "preview",
        rpt.results.forall(_.outcome == Outcome.label(Outcome.DryRun)),
        rpt.totalContacts == 2,
        rpt.emailable == 2, // both have overrides
        sent.isEmpty,       // the recorder proves NOTHING went out
      )
    },
    test("a confirmed send transmits FROM press@, REPLY-TO the inbox, in (priority,id) order") {
      for {
        ref  <- recorder
        rpt  <- PressOutreach.run(
          contacts,
          template,
          fullFill,
          cfg,
          EmailSender.recording(ref),
          emailOverrides = Map("alpha" -> "alice@outlet", "bravo" -> "bob@outlet"),
          confirmed = true,
        )
        sent <- ref.get
      } yield assertTrue(
        rpt.results
          .map(_.outcome) == List(Outcome.label(Outcome.Sent), Outcome.label(Outcome.Sent)),
        rpt.results.map(_.contactId) == List("alpha", "bravo"), // P1 before P2
        sent.map(_.to) == List("alice@outlet", "bob@outlet"),
        sent.forall(_.from.exists(_.contains("press@wifihaven.net"))),
        sent.forall(_.replyTo.contains("press@wifihaven.net")),
        rpt.results.forall(_.recordable),
      )
    },
    test("idempotency — a peer already in the ledger is skipped, not re-sent") {
      for {
        ref  <- recorder
        rpt  <- PressOutreach.run(
          contacts,
          template,
          fullFill,
          cfg,
          EmailSender.recording(ref),
          emailOverrides = Map("alpha" -> "alice@outlet", "bravo" -> "bob@outlet"),
          alreadySentPeers = Set("alice@outlet"),
          confirmed = true,
        )
        sent <- ref.get
      } yield {
        val byId = rpt.results.map(r => r.contactId -> r.outcome).toMap
        assertTrue(
          byId("alpha") == Outcome.label(Outcome.SkippedAlreadySent),
          byId("bravo") == Outcome.label(Outcome.Sent),
          sent.map(_.to) == List("bob@outlet"), // alpha never transmitted
        )
      }
    },
    test("a form-only contact (no email, no override) is SkippedNoEmail") {
      for {
        ref  <- recorder
        rpt  <- PressOutreach.run(
          contacts,
          template,
          fullFill,
          cfg,
          EmailSender.recording(ref),
          emailOverrides = Map("alpha" -> "alice@outlet"), // bravo has neither email nor override
          confirmed = true,
        )
        sent <- ref.get
      } yield {
        val byId = rpt.results.map(r => r.contactId -> r.outcome).toMap
        assertTrue(
          byId("bravo") == Outcome.label(Outcome.SkippedNoEmail),
          byId("alpha") == Outcome.label(Outcome.Sent),
          rpt.formOnly == 1,
          sent.map(_.to) == List("alice@outlet"),
        )
      }
    },
    test("testRecipient redirects the real transmit but keeps idempotency on the real contact") {
      for {
        ref  <- recorder
        rpt  <- PressOutreach.run(
          contacts,
          template,
          fullFill,
          cfg,
          EmailSender.recording(ref),
          emailOverrides = Map("alpha" -> "alice@outlet", "bravo" -> "bob@outlet"),
          testRecipient = Some("safe@me.test"),
          confirmed = true,
        )
        sent <- ref.get
      } yield assertTrue(
        sent.map(_.to) == List("safe@me.test", "safe@me.test"), // both went to the test address
        rpt.results.map(_.peerEmail) == List(
          Some("alice@outlet"),
          Some("bob@outlet"),
        ),                                                      // real peers
        rpt.results.forall(!_.recordable), // a test send is never persisted to the ledger
      )
    },
    test("a transport failure is Failed + recordable (so a re-run retries it)") {
      for {
        ref <- recorder
        rpt <- PressOutreach.run(
          contacts,
          template,
          fullFill,
          cfg,
          EmailSender.recordingWithFailures(ref, failFor = Set("bob@outlet")),
          emailOverrides = Map("alpha" -> "alice@outlet", "bravo" -> "bob@outlet"),
          confirmed = true,
        )
      } yield {
        val byId = rpt.results.map(r => r.contactId -> r.outcome).toMap
        assertTrue(
          byId("alpha") == Outcome.label(Outcome.Sent),
          byId("bravo") == Outcome.label(Outcome.Failed),
          rpt.results.forall(
            _.recordable,
          ), // both recordable; the ledger stores outcome=failed for bravo
        )
      }
    },
  )
}
