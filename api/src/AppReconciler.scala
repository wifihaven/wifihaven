package wifihaven.api

import wifihaven.api.db.AppRepo
import wifihaven.shared.types.*
import zio.*
import zio.json.*

/**
 * #1777: per-template outcome of [[AppReconciler.reconcileTemplates]] — what changed for each
 * template the reconciler walked. Used by the admin route to surface the result to the operator.
 */
final case class AppReconcileSummary(
    mergedSlugs: List[String],
    // #2820: retired template ids whose orphan `apps` row was folded into its surviving template's
    // row on this pass. Empty on a clean DB and on every re-run after the first.
    retiredSlugs: List[String] = Nil,
    renamedSlugs: List[String],
    hostsUnioned: List[String],
    templateIdSet: List[String],
    alreadyClean: List[String],
    created: List[String],
) derives JsonCodec {
  def total: Int =
    mergedSlugs.size + retiredSlugs.size + renamedSlugs.size + hostsUnioned.size +
      templateIdSet.size + alreadyClean.size + created.size
}

/**
 * #1777: idempotent reconciler that collapses every `<slug>-template`-suffixed `apps` row onto its
 * canonical `<slug>` form, with the canonical row gaining the union of both host-sets and every FK
 * reference (assignments, rollups, usage) reattached. After running, no app slug ends in
 * `-template`, and `AppTemplates.findByTemplateId` finds each template's canonical row directly.
 *
 * Why this exists: when `AppTemplates.seed` runs against a DB where an operator already created an
 * app at the template's canonical slug, [[AppTemplates#findFreeSlug]] falls back to
 * `<slug>-template`. The fallback is correct as a uniqueness guard but leaves two rows for the same
 * logical app, splitting assignments and usage. The reconciler restores 1:1 alignment with the
 * template set.
 *
 * Single source of truth: this is the ONLY place the four reconciliation outcomes (#1777's rules
 * table) are computed. The admin route delegates to it; the test seeds the messy state and calls
 * the same function. No duplicate merge logic elsewhere.
 */
object AppReconciler {

  private sealed trait OneOutcome
  private object OneOutcome {
    final case class Merged(slug: String)        extends OneOutcome
    final case class Renamed(slug: String)       extends OneOutcome
    final case class HostsUnioned(slug: String)  extends OneOutcome
    final case class TemplateIdSet(slug: String) extends OneOutcome
    final case class AlreadyClean(slug: String)  extends OneOutcome
    final case class Created(slug: String)       extends OneOutcome
  }

  def reconcileTemplates(
      repo: AppRepo,
      templates: List[AppTemplate],
  ): Task[AppReconcileSummary] =
    for {
      outcomes <- ZIO.foreach(templates)(t => reconcileOne(repo, t))
      // Retirements run AFTER the per-template pass so the surviving row exists (created, renamed
      // or collapsed) before anything is merged into it.
      retired  <- retireSupersededRows(repo, templates)
    } yield AppReconcileSummary(
      mergedSlugs = outcomes.collect { case OneOutcome.Merged(s) => s },
      retiredSlugs = retired,
      renamedSlugs = outcomes.collect { case OneOutcome.Renamed(s) => s },
      hostsUnioned = outcomes.collect { case OneOutcome.HostsUnioned(s) => s },
      templateIdSet = outcomes.collect { case OneOutcome.TemplateIdSet(s) => s },
      alreadyClean = outcomes.collect { case OneOutcome.AlreadyClean(s) => s },
      created = outcomes.collect { case OneOutcome.Created(s) => s },
    )

  /**
   * #2820: fold away the `apps` row left behind by a template that was merged into another and
   * deleted. Nothing else prunes such a row — `reconcileOne` only walks templates that EXIST — so
   * without this the retired row keeps its `template_id`, hosts, per-profile assignments and usage
   * history while being managed by no template.
   *
   * For each `retires:` id on a surviving template, the retired row is merged INTO the survivor
   * with `AppRepo.mergeAppInto` — the same machinery `reconcileOne` uses to collapse a
   * `<slug>-template` duplicate, so hosts are unioned, assignments and rollups are reattached (`to`
   * wins on a per-profile conflict, `app_used_daily` sums on overlap) and the retired row is
   * deleted, all in one transaction. Returns the retired ids actually merged on this pass.
   *
   * Deliberately keyed on `template_id`, not slug: a row that merely occupies the retired slug
   * without carrying its template link is an operator's app, not ours to delete. Idempotent — a
   * second pass finds nothing because the retired row is gone.
   *
   * Called from the boot sequence right after `AppTemplates.seed` (so a deploy that lands a merged
   * template cleans the orphan without operator action) and from `reconcileTemplates`, which the
   * admin reconcile route drives. One implementation, two call sites.
   */
  def retireSupersededRows(
      repo: AppRepo,
      templates: List[AppTemplate],
  ): Task[List[String]] =
    ZIO
      .foreach(templates.filter(_.retires.nonEmpty)) { t =>
        repo.findBySlug(t.slug.value).flatMap {
          // Survivor absent (nothing seeded yet) — there is nothing to merge into.
          case None           => ZIO.succeed(List.empty[String])
          case Some(survivor) =>
            ZIO
              .foreach(t.retires) { retired =>
                // Both the canonical and the `<slug>-template` fallback form can carry the retired
                // template_id, so look each up by slug rather than by template_id — a DB holding
                // both would trip the single-row `findByTemplateId` lookup.
                for {
                  canonical <- repo.findBySlug(retired.value)
                  suffixed  <- repo.findBySlug(s"${retired.value}-template")
                  rows = List(canonical, suffixed).flatten
                    .filter(a => a.templateId.contains(retired) && a.id != survivor.id)
                  _ <- ZIO
                    .foreachDiscard(rows)(r => repo.mergeAppInto(from = r.id, to = survivor.id))
                  _ <- ZIO
                    .logInfo(
                      s"app_templates: retired template_id=${retired.value} merged into " +
                        s"slug=${t.slug.value} (id=${survivor.id.value}, rows=${rows.size})",
                    )
                    .when(rows.nonEmpty)
                } yield if rows.nonEmpty then List(retired.value) else Nil
              }
              .map(_.flatten)
        }
      }
      .map(_.flatten)

  private def reconcileOne(repo: AppRepo, t: AppTemplate): Task[OneOutcome] = {
    val canonicalSlug = t.slug.value
    val suffixedSlug  = s"$canonicalSlug-template"
    for {
      canonical <- repo.findBySlug(canonicalSlug)
      suffixed  <- repo.findBySlug(suffixedSlug)
      outcome   <- (canonical, suffixed) match {
        case (Some(c), Some(s)) =>
          // Both exist: merge suffixed INTO canonical, then top up template hosts.
          repo.mergeAppInto(from = s.id, to = c.id) *>
            unionTemplateHostsInto(repo, c.id, t).as(OneOutcome.Merged(canonicalSlug))

        case (None, Some(s)) =>
          // Only suffixed exists: rename onto canonical, ensure template_id set, top up hosts.
          repo.update(s.copy(slug = canonicalSlug, templateId = Some(t.slug))) *>
            unionTemplateHostsInto(repo, s.id, t).as(OneOutcome.Renamed(canonicalSlug))

        case (Some(c), None) =>
          // Only canonical exists. Set template_id if absent, top up template hosts. The two are
          // tracked separately so the summary tells operators which subset of canonicals needed
          // a template_id reattach vs which gained hosts.
          val needsTemplateId  = c.templateId.isEmpty
          val ensureTemplateId =
            if needsTemplateId then repo.update(c.copy(templateId = Some(t.slug))) else ZIO.unit
          ensureTemplateId *>
            unionTemplateHostsInto(repo, c.id, t).map { added =>
              if added then OneOutcome.HostsUnioned(canonicalSlug)
              else if needsTemplateId then OneOutcome.TemplateIdSet(canonicalSlug)
              else OneOutcome.AlreadyClean(canonicalSlug)
            }

        case (None, None) =>
          // Neither — create the canonical row from the template. Idempotent re-runs land in
          // the (Some(c), None) branch after this and become no-ops.
          for {
            id <- repo.create(t.name, canonicalSlug, Some(t.slug), t.icon, t.iconType)
            _  <- repo.setHosts(id, t.hosts)
          } yield OneOutcome.Created(canonicalSlug)
      }
    } yield outcome
  }

  /** Returns true iff any hosts were added (i.e. the template's set wasn't already a subset). */
  private def unionTemplateHostsInto(
      repo: AppRepo,
      appId: AppId,
      t: AppTemplate,
  ): Task[Boolean] =
    for {
      existing <- repo.getHosts(appId)
      have    = existing.toSet
      missing = t.hosts.filterNot(have.contains)
      _ <-
        if missing.nonEmpty then repo.setHosts(appId, (existing ++ missing).distinct)
        else ZIO.unit
    } yield missing.nonEmpty
}
