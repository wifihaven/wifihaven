package wifihaven.api.policy

import wifihaven.shared.*
import wifihaven.shared.types.*

import java.time.Instant

/**
 * #1630: the SINGLE fold of a profile's per-app assignments into every downstream projection — the
 * extraAllowed / extraBlocked carve sets (snapshot enforcement), the exempt host-set (excluded from
 * the profile's daily total), the active-app host-set (attribution-beats-suppression in Presence),
 * and the per-app cap groups (the TimeLimited-mode subset that the per-app daily caps key off).
 *
 * Before #1630 these projections were derived in two unrelated places. `expandAppDispositions`
 * (PolicyService) read `List[AppPolicyAssignment]` and produced extraAllowed/extraBlocked, while
 * `exemptPatterns` / `appHostPatterns` / `groupAppLimits` (TimeStatusService) read
 * `List[AppTimeLimit]` — synthesized by a repo query that filtered `WHERE mode='time_limited'` —
 * and produced everything else. That filter meant a `mode=Allowed, exemptFromDaily=true` app
 * reached `extraAllowed` (visible to the snapshot) but never reached `exemptPatterns` (invisible to
 * the daily-usage path), so its traffic was both permitted *and* counted against the daily cap —
 * the user-visible contract was "Khan doesn't count". Collapsing to one fold means the structural
 * seam through which that divergence appeared no longer exists: any reader of these projections
 * goes through `ProfileAppDispositions.from`.
 */
final case class ProfileAppDispositions(
    perApp: List[AppDisposition],
) {

  /**
   * Hosts of every assignment with `exemptFromDaily=true`. Consumed by `TimeStatusService`'s daily
   * total path to filter out exempt traffic before counting. Independent of `mode` and independent
   * of any per-instant schedule state — the exempt flag is a structural property of the assignment,
   * not a function of "what's active right now".
   */
  lazy val exemptPatterns: List[String] =
    perApp.filter(_.exemptFromDaily).flatMap(_.hosts).distinct

  /**
   * Hosts of every assignment. Consumed by `Presence.isHeartbeat` for attribution-beats-
   * suppression (#1506, narrowed by #2815 to claims at least as SPECIFIC as the background entry
   * that also matched): a row whose host matches one of these patterns is not silently dropped as
   * background infra. Mode-agnostic for the same reason as `exemptPatterns` — attribution is a
   * structural relationship, not an enforcement one.
   */
  lazy val appHostPatterns: List[String] =
    perApp.flatMap(_.hosts).distinct

  /**
   * The subset of `perApp` that participates in per-app daily caps — `mode=TimeLimited` only.
   * `mode=Allowed` and `mode=Blocked` apps do not contribute, regardless of `dailyMinutes` (an
   * Allowed app with no cap encodes the rule "no per-app limit", not "0-minute cap fires
   * immediately"; see acceptance test 4 on #1630).
   */
  lazy val capGroups: List[AppDisposition] =
    perApp.filter(_.mode == AppMode.TimeLimited)

  /**
   * #2932 (design `docs/design/weekly-limits-and-app-groups.md` §6.1): every budget on the profile,
   * the generalization of [[capGroups]] as the cap source. One APP budget per TimeLimited
   * assignment (its own daily and weekly limits), and one GROUP budget per attached group, covering
   * the group's members on this profile with the attachment's limits.
   *
   * A covered app with no assignment (`coveredOnly`) is in [[capGroups]], so its minutes are
   * counted and its hosts reach every projection, but it has no app budget of its own.
   *
   * A group's `countHosts` is the union of its TimeLimited-effective members' distinctive hosts, so
   * every host in it is already in some app entry of [[budgetLabelHosts]] and the group entry
   * changes no other budget's minutes (design §6.2, finding 8). A Blocked member (stricter wins,
   * D14) is not counted: it is blocked whatever the group has used.
   */
  lazy val budgets: List[CapBudget] = {
    val appBudgets   = capGroups.filterNot(_.coveredOnly).map { d =>
      CapBudget(
        key = BudgetKey.App(d.appId),
        label = d.label,
        name = d.label,
        members = List(d),
        countHosts = d.distinctiveHosts,
        dailyLimit = d.dailyMinutes,
        weeklyLimit = d.weeklyMinutes,
        exemptFromDaily = d.exemptFromDaily,
      )
    }
    val groupBudgets = perApp
      .flatMap(d => d.coverage.map(_ -> d))
      .groupMap(_._1.groupId)(identity)
      .toList
      .map { case (gid, pairs) =>
        val c       = pairs.head._1
        val members = pairs.map(_._2)
        CapBudget(
          key = BudgetKey.Group(gid),
          label = CapBudget.groupLabel(gid),
          name = c.name,
          members = members,
          countHosts =
            members.filter(_.mode == AppMode.TimeLimited).flatMap(_.distinctiveHosts).distinct,
          dailyLimit = c.dailyMinutes,
          weeklyLimit = c.weeklyMinutes,
          exemptFromDaily = c.exemptFromDaily,
        )
      }
      .sortBy(_.label)
    appBudgets ++ groupBudgets
  }

  /** #2932: the group budgets of [[budgets]], in label order. */
  lazy val groupBudgets: List[CapBudget] =
    budgets.filter(_.key.isInstanceOf[BudgetKey.Group])

  /**
   * #2932 (design §6.2): the `(label -> hosts)` list the ONE per-budget Presence call takes: every
   * [[capGroups]] entry (as [[capGroupLabelDistinctiveHosts]]) followed by one entry per group
   * budget. The per-app entries are unchanged by groups, so each app's minutes are what they were.
   */
  def budgetLabelHosts: List[(String, List[String])] =
    capGroupLabelDistinctiveHosts ++ groupBudgets.map(b => b.label -> b.countHosts)

  /** #2932: re-keys a [[budgetLabelHosts]] label to its [[BudgetKey]]. */
  lazy val labelToBudgetKey: Map[String, BudgetKey] =
    (capGroups.map(d => d.label -> (BudgetKey.App(d.appId): BudgetKey)) ++
      groupBudgets.map(b => b.label -> b.key)).toMap

  /**
   * The (label → hosts) pairs the Presence cap-aggregation primitive
   * (`Presence.patternGroupMinutesForProfile` / `Presence.appSecondsForProfile`) expects. Stable
   * order by label so the cap-group representation downstream consumers carry is deterministic.
   */
  def capGroupLabelHosts: List[(String, List[String])] =
    capGroups.map(d => d.label -> d.hosts)

  /**
   * #1897 (shared-hosts S2): the (label → DISTINCTIVE hosts) pairs for the per-app engaged-minutes
   * stitch. Same shape and order as [[capGroupLabelHosts]] but with each app's `shared` hosts
   * dropped. This is THE single definition of "an app's distinctive host-set for presence" — both
   * engaged-minutes consumers ([[wifihaven.api.policy.TimeStatusService.appDayStates]] /
   * [[wifihaven.api.policy.TimeStatusService.appSecondsByAppWithDropCount]]) and S3's shared-host
   * co-presence overlap test read through it, so the distinctive-span computation lives in exactly
   * one place (AGENTS.md §single-source-of-truth). A shared host overlapping a distinctive span is
   * already counted inside that span, so excluding it from the stitch changes the minutes by zero
   * while guaranteeing a shared host can never inflate, extend, or create an app's engaged time.
   */
  def capGroupLabelDistinctiveHosts: List[(String, List[String])] =
    capGroups.map(d => d.label -> d.distinctiveHosts)

  /**
   * #1898 (shared-hosts S3): the (label → DISTINCTIVE hosts) pairs for EVERY assigned app,
   * mode-agnostic. Same per-(app, host) `shared = false` partition as
   * [[capGroupLabelDistinctiveHosts]], but NOT restricted to `mode = TimeLimited`: the per-host
   * reporting co-presence test attributes a shared host to an app whose distinctive session
   * overlaps it regardless of the app's enforcement mode (an Allowed app like Feeling Great still
   * earns its shared-backend bytes), whereas the per-app daily cap only ticks for TimeLimited apps.
   * Attribution is a structural relationship, not an enforcement one — same reasoning as
   * [[appHostPatterns]] / [[exemptPatterns]] being mode-agnostic. Stable order by label.
   */
  def appLabelDistinctiveHosts: List[(String, List[String])] =
    perApp.map(d => d.label -> d.distinctiveHosts)

  /**
   * #1898: (label → appId) for EVERY assigned app — the mode-agnostic re-key partner of
   * [[appLabelDistinctiveHosts]] used by `TimeStatusService.distinctiveSpansByApp`.
   */
  def appLabelToAppId: Map[String, AppId] =
    perApp.map(d => d.label -> d.appId).toMap

  /**
   * Snapshot enforcement: collapse every assignment into the per-MAC `(extraAllowed, extraBlocked)`
   * BlockRules buckets the router applies, folding each assignment's per-app schedule windows over
   * its base mode (design §4.1):
   *
   *   - any `AllowedDuring` window active → AllowedDuring candidate (carve gate below);
   *   - else any `BlockedDuring` window active → Blocked;
   *   - else the assignment's base [[AppMode]].
   *
   * `AllowedDuring` beats `BlockedDuring` on simultaneous overlap (consistent with #421
   * allow-beats-block). The AllowedDuring carve into `extraAllowed` is gated by the daily cap: a
   * non-exempt app whose cap is exhausted is NOT carved — it stays subject to the whole-MAC block,
   * so the daily limit still bites without any wire/router change (design §5 rows 9a–9c).
   * `TimeLimited` base apps contribute nothing here — they surface via the per-app cap path
   * (`PolicyService.appCapExhaustedHosts`).
   *
   * #1679: `isScheduleBlock` suppresses the `extraAllowed` carve for apps whose
   * `allowedDuringScheduleBlock = false`. Only passes true when the profile's whole-MAC block
   * reason is `Schedule`; Paused/TimeLimit/Manual leave this false so the toggle has no effect on
   * those paths. Default `false` keeps existing call sites (unit tests, legacy paths) correct.
   */
  def enforcement(
      schedWindows: Map[AppPolicyAssignmentId, List[(AppScheduleMode, ScheduleWindow)]],
      capExhausted: Boolean,
      now: Instant,
      isScheduleBlock: Boolean = false,
  ): (List[Hostname], List[Hostname]) = {
    val allowed = scala.collection.mutable.ListBuffer.empty[Hostname]
    val blocked = scala.collection.mutable.ListBuffer.empty[Hostname]
    perApp.foreach { d =>
      val hosts                      = d.hosts.map(Hostname.unsafe)
      // #1899 (shared-hosts S4): the BLOCK side is restricted to an app's DISTINCTIVE hosts. A
      // shared backend (e.g. `elevenlabs.io`) is listed on many apps; dropping it because THIS app
      // is blocked would drop it for every OTHER app on the device — the #1636 collateral failure.
      // The ALLOW side keeps the full host-set (`hosts`): over-allowing a shared backend is safe
      // (extraAllowed already beats every block path at the router; design §"most-permissive"), and
      // a shared host belonging to an allowed app must stay reachable for that app to work.
      val distinctive                = d.distinctiveHosts.map(Hostname.unsafe)
      val pairs                      = schedWindows.getOrElse(d.assignmentId, Nil)
      val allowedActive              = pairs.exists { case (m, w) =>
        m == AppScheduleMode.AllowedDuring && PolicyService.windowActiveAt(w, now)
      }
      val blockedActive              = pairs.exists { case (m, w) =>
        m == AppScheduleMode.BlockedDuring && PolicyService.windowActiveAt(w, now)
      }
      // #1679: suppress extraAllowed carve for this app if we're in a Schedule block and the
      // assignment opts out of the "allowed during bedtime" behaviour.
      val suppressedByScheduleToggle = isScheduleBlock && !d.allowedDuringScheduleBlock
      if (allowedActive) {
        if (!(capExhausted && !d.exemptFromDaily) && !suppressedByScheduleToggle)
          allowed ++= hosts
      } else if (blockedActive) {
        blocked ++= distinctive
      } else
        d.mode match {
          case AppMode.Allowed     =>
            if (!suppressedByScheduleToggle) allowed ++= hosts
          case AppMode.Blocked     => blocked ++= distinctive
          case AppMode.TimeLimited => () // surfaces via the per-app cap path
        }
    }
    (allowed.toList.distinct, blocked.toList.distinct)
  }
}

/**
 * One row per assignment under a profile, collapsed from the per-(assignment × host) shape the repo
 * emits. `hosts` carries every host attached to the underlying app at the time of the read. `label`
 * is the app's `"app:<slug>"` cap-group key — the same value `Presence`'s per-app primitive uses as
 * the group key.
 */
final case class AppDisposition(
    appId: AppId,
    assignmentId: AppPolicyAssignmentId,
    mode: AppMode,
    exemptFromDaily: Boolean,
    // #1627: `None` means "no per-app limit configured" — distinct from a 0-minute cap. Downstream
    // consumers (`PolicyService.exemptUnderCapHosts`, `appCapExhaustedHosts`) treat `None` as
    // never-exhausted, so an exempt-from-daily app with no own cap always carves around whole-MAC
    // blocks; without this the repo's previous COALESCE(...,0) collapsed NULL → 0 and the carve
    // gate (`usedMinutes < 0`) was always false.
    dailyMinutes: Option[Int],
    label: String,
    hosts: List[String],
    // #1897 (shared-hosts S2): the subset of `hosts` whose `app_hosts.shared` flag is false — the
    // app's DISTINCTIVE hosts. Enforcement / exempt / attribution projections keep reading `hosts`
    // (all of them); only the per-app engaged-minutes stitch reads this subset, so a shared backend
    // never inflates or extends an app's time. An app with no shared hosts has
    // `distinctiveHosts == hosts`.
    distinctiveHosts: List[String],
    // #1679: when false, suppress this app's extraAllowed carve-out during Schedule-reason blocks.
    allowedDuringScheduleBlock: Boolean = true,
    // #2932 (design §5.2): the assignment's weekly limit; `None` = no weekly limit.
    weeklyMinutes: Option[Int] = None,
    // #2932 (design §6.1): the attached group that covers this app on the profile, if any.
    coverage: Option[GroupCoverage] = None,
    // #2932: a disposition synthesized for a covered app with no assignment (D6). It has no
    // assignment id of its own and no app budget.
    coveredOnly: Boolean = false,
)

/** #2932 (design §6.1): which budget a figure belongs to. */
enum BudgetKey {
  case App(id: AppId)
  case Group(id: AppGroupId)
}

/**
 * #2932 (design §6.1): one budget, the shared shape of a per-app cap and a group. `label` is the
 * Presence group key (`app:<slug>` or `group:<id>`). `countHosts` is what the per-budget stitch
 * counts: the app's distinctive hosts, or the union of the members' (design §6.2).
 */
final case class CapBudget(
    key: BudgetKey,
    label: String,
    name: String,
    members: List[AppDisposition],
    countHosts: List[String],
    dailyLimit: Option[Int],
    weeklyLimit: Option[Int],
    exemptFromDaily: Boolean,
)

object CapBudget {

  /** The Presence key of a group budget; the block-reason label #2933 uses (design §8). */
  def groupLabel(id: AppGroupId): String = s"group:${id.value}"
}

object ProfileAppDispositions {

  /** Empty (no assignments / no apps). */
  val empty: ProfileAppDispositions = ProfileAppDispositions(Nil)

  /**
   * The fold. Takes `appLimits` — every (assignment × host) row the unfiltered
   * [[wifihaven.api.db.AppTimeLimitRepo.listForProfile]] returns, across all modes — and emits the
   * unified per-profile decomposition. Rows are grouped by `assignmentId` (the canonical
   * per-assignment key, populated from `app_policy_assignments.id` in the repo SELECT); each
   * assignment maps to exactly one [[AppDisposition]]. Stable order by `label`.
   *
   * If an assignment has no hosts the repo emits no rows for it — such an assignment contributes
   * nothing to any projection, which is correct: there's nothing to allow, block, exempt, or
   * attribute on.
   */
  def from(appLimits: List[wifihaven.shared.AppTimeLimit]): ProfileAppDispositions = {
    // #2932: keyed by (assignment, app). A covered-only row has no assignment (id 0), so the app id
    // tells two such apps apart; an assigned app has exactly one assignment per profile (V28's
    // UNIQUE (app_id, profile_id)), so this is still one disposition per assignment.
    val byAssignment = appLimits.groupBy(r => (r.assignmentId, r.appId)).toList
    val perApp       = byAssignment
      .map { case ((assignmentId, _), rows) =>
        val first       = rows.head
        val hosts       = rows.map(_.domainPattern).distinct
        // #1897: distinctive = the host rows whose `shared` flag is false. `shared` is per-(app,
        // host) (one repo row per host), so it is read off each row, not off `first`.
        val distinctive = rows.filterNot(_.shared).map(_.domainPattern).distinct
        // `exemptFromDaily`, `dailyMinutes`, `weeklyMinutes`, `coverage` and
        // `allowedDuringScheduleBlock` are uniform across an assignment's synthesized rows by
        // construction (one assignment row × N host rows from the `app_hosts` join), so any row's
        // value is the assignment's value. Reading off `first` makes that invariant explicit.
        val base        = AppDisposition(
          appId = first.appId,
          assignmentId = assignmentId,
          mode = first.mode,
          exemptFromDaily = first.exemptFromDaily,
          dailyMinutes = first.dailyMinutes,
          label = first.label,
          hosts = hosts,
          distinctiveHosts = distinctive,
          allowedDuringScheduleBlock = first.allowedDuringScheduleBlock,
          weeklyMinutes = first.weeklyMinutes,
        )
        first.coverage match {
          case None    => base
          // #2932 (design §6.1): a covered app. Its effective "counts toward daily" flag is the
          // attachment's (D5). A covered app with no assignment is a synthesized TimeLimited
          // disposition with no limits of its own. One with an assignment stays Blocked if its row
          // is Blocked (stricter wins, D14) and is otherwise TimeLimited, so an Allowed row adds no
          // unconditional carve (finding 2); only a TimeLimited row keeps its own limits.
          case Some(c) =>
            val ownLimits = !first.coveredOnly && first.mode == AppMode.TimeLimited
            base.copy(
              mode =
                if first.mode == AppMode.Blocked && !first.coveredOnly then AppMode.Blocked
                else AppMode.TimeLimited,
              exemptFromDaily = c.exemptFromDaily,
              dailyMinutes = if ownLimits then first.dailyMinutes else None,
              weeklyMinutes = if ownLimits then first.weeklyMinutes else None,
              allowedDuringScheduleBlock =
                if first.coveredOnly then true else first.allowedDuringScheduleBlock,
              coverage = Some(c),
              coveredOnly = first.coveredOnly,
            )
        }
      }
      .sortBy(_.label)
    ProfileAppDispositions(perApp)
  }
}
