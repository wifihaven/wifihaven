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
    renamedSlugs: List[String],
    hostsUnioned: List[String],
    templateIdSet: List[String],
    alreadyClean: List[String],
    created: List[String],
) derives JsonCodec {
  def total: Int =
    mergedSlugs.size + renamedSlugs.size + hostsUnioned.size + templateIdSet.size +
      alreadyClean.size + created.size
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
    ZIO
      .foreach(templates)(t => reconcileOne(repo, t))
      .map { outcomes =>
        AppReconcileSummary(
          mergedSlugs = outcomes.collect { case OneOutcome.Merged(s) => s },
          renamedSlugs = outcomes.collect { case OneOutcome.Renamed(s) => s },
          hostsUnioned = outcomes.collect { case OneOutcome.HostsUnioned(s) => s },
          templateIdSet = outcomes.collect { case OneOutcome.TemplateIdSet(s) => s },
          alreadyClean = outcomes.collect { case OneOutcome.AlreadyClean(s) => s },
          created = outcomes.collect { case OneOutcome.Created(s) => s },
        )
      }

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
