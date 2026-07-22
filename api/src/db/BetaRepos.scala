package wifihaven.api.db

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.api.db.TypeMeta.given
import zio.*
import zio.interop.catz.*

import java.time.Instant

// ── Multi-tenant Phase-5 (P5-2, #2132, epic #622) repos ─────────────────────
// The beta request → operator approval → provisioning → invite accept pipeline
// (design docs/design/multi-tenant-launch.md §3). These are the FIRST repos to
// write the `households` and `household_billing` tables from application code —
// before this the single install / default household was V1-seed-only.

/** A `households` row (name + human-facing slug + per-household router entitlement). */
case class Household(
    id: HouseholdId,
    name: String,
    // Nullable in V66 (schema-only PR couldn't SET NOT NULL — TODO(#2142)); provisioning
    // (this issue) always sets it, so new households carry a slug.
    slug: Option[String],
    routerCap: Int,
)

/**
 * A `household_billing` row (design §5.1). Minimal at provisioning time: `status='beta'`,
 * `founding=true`, no Stripe linkage yet — the Stripe Customer/subscription columns are the seam
 * #2135 fills (Customer at conversion, never during beta).
 */
case class HouseholdBilling(
    householdId: HouseholdId,
    stripeCustomerId: Option[String],
    stripeSubscriptionId: Option[String],
    priceId: Option[String],
    status: String,
    founding: Boolean,
    currentPeriodEnd: Option[Instant],
    lapsedAt: Option[Instant],
)

/** A `beta_requests` row (design §3.1). The invite token is stored ONLY as a hash. */
case class DbBetaRequest(
    id: BetaRequestId,
    email: String,
    name: Option[String],
    note: Option[String],
    status: BetaRequestStatus,
    requestedAt: Instant,
    decidedAt: Option[Instant],
    decidedBy: Option[UserId],
    inviteTokenHash: Option[Sha256Hex],
    inviteExpiresAt: Option[Instant],
    householdId: Option[HouseholdId],
)

// NB: `HouseholdRepo` itself (findIdBySlug + the #2132 create/findById provisioning methods) lives
// in Repos.scala alongside the other repo traits — it predates this file (#2140's login-slug
// resolver) and #2132 extended it there. This file adds only the two beta-specific repos below.

// ── HouseholdBillingRepo ─────────────────────────────────────────────────────

/**
 * #2132: the per-household billing state row. The provisioning-time row is now written by the ONE
 * household-creation primitive ([[wifihaven.api.db.HouseholdSeed.insertHousehold]], #2355), so
 * every household is pre-seeded (`status='beta'`). `create` here is the idempotent "set the billing
 * row" seam (ON CONFLICT DO UPDATE) with no production caller today; the Stripe-linkage setters +
 * status transitions land in #2135 (Checkout/Portal/webhook) and #2137 (flip/lapse).
 */
trait HouseholdBillingRepo {
  def create(householdId: HouseholdId, status: String, founding: Boolean): Task[Unit]
  def findByHousehold(householdId: HouseholdId): Task[Option[HouseholdBilling]]

  // ── #2135: Stripe linkage + the ONE beta/active/lapsed status machine ─────────
  // The state machine (design §5.1) shared by #2135 (dunning-lapse + recovery from the webhook)
  // and #2137 (non-conversion flip). These are the transitions; the "why" (which webhook / cohort
  // date) stays in the caller — the row records only the resulting state.

  /** Store the `cus_…` id from the provisioning-time Customer create (#2135 seam). Idempotent. */
  def setStripeCustomer(householdId: HouseholdId, stripeCustomerId: String): Task[Unit]

  /** Map a Stripe customer id back to its household row (webhook events reference the customer). */
  def findByStripeCustomerId(stripeCustomerId: String): Task[Option[HouseholdBilling]]

  /**
   * `checkout.session.completed` recovery/conversion → `status='active'`: store the subscription +
   * price ids and current-period-end, clear `lapsed_at`, bump `updated_at`.
   */
  def markActive(
      householdId: HouseholdId,
      stripeSubscriptionId: Option[String],
      priceId: Option[String],
      currentPeriodEnd: Option[Instant],
      now: Instant,
  ): Task[Unit]

  /**
   * Dunning-terminal → `status='lapsed'`, stamp `lapsed_at` (a record, not a purge timer, §5.3).
   */
  def markLapsed(householdId: HouseholdId, lapsedAt: Instant): Task[Unit]

  /**
   * #2137: household counts by billing status, for the `households_by_billing_status` gauge. A
   * single GROUP-BY read (SSOT) — the caller fills any absent status with 0 so the gauge always
   * publishes all three states.
   */
  def countByStatus: Task[Map[String, Int]]
}

class HouseholdBillingRepoLive(xa: Transactor[Task]) extends HouseholdBillingRepo {
  private val cols =
    fr"household_id, stripe_customer_id, stripe_subscription_id, price_id, status, founding, current_period_end, lapsed_at"
  private type Row =
    (
        HouseholdId,
        Option[String],
        Option[String],
        Option[String],
        String,
        Boolean,
        Option[Instant],
        Option[Instant],
    )
  private def toBilling(r: Row): HouseholdBilling =
    HouseholdBilling(r._1, r._2, r._3, r._4, r._5, r._6, r._7, r._8)

  // #2355: idempotent — set the household's billing row to (status, founding), inserting it if
  // absent. Post-#2355 the row is created as part of the ONE household-creation primitive
  // ([[HouseholdSeed.insertHousehold]]), so a household is always pre-seeded (status='beta') by the
  // time anyone calls this; a plain INSERT would collide on the household_id PK. ON CONFLICT DO
  // UPDATE makes this the safe "override the provisioning billing state" seam. No production caller
  // today (provisioning + status transitions go through HouseholdSeed / markActive / markLapsed);
  // used by tests to steer a household to a specific billing status.
  def create(householdId: HouseholdId, status: String, founding: Boolean) =
    sql"""INSERT INTO household_billing(household_id, status, founding)
          VALUES($householdId, $status, $founding)
          ON CONFLICT (household_id)
          DO UPDATE SET status = EXCLUDED.status, founding = EXCLUDED.founding""".update.run
      .transact(xa)
      .unit

  def findByHousehold(householdId: HouseholdId) =
    (fr"SELECT" ++ cols ++ fr"FROM household_billing WHERE household_id=$householdId")
      .query[Row]
      .map(toBilling)
      .option
      .transact(xa)

  def setStripeCustomer(householdId: HouseholdId, stripeCustomerId: String) =
    sql"""UPDATE household_billing
          SET stripe_customer_id=$stripeCustomerId, updated_at=NOW()
          WHERE household_id=$householdId""".update.run
      .transact(xa)
      .unit

  def findByStripeCustomerId(stripeCustomerId: String) =
    (fr"SELECT" ++ cols ++ fr"FROM household_billing WHERE stripe_customer_id=$stripeCustomerId")
      .query[Row]
      .map(toBilling)
      .option
      .transact(xa)

  def markActive(
      householdId: HouseholdId,
      stripeSubscriptionId: Option[String],
      priceId: Option[String],
      currentPeriodEnd: Option[Instant],
      now: Instant,
  ) =
    // COALESCE keeps a previously-stored subscription/price id when a later event omits it, so a
    // recovery event that carries only the customer doesn't null out the linkage.
    sql"""UPDATE household_billing
          SET status='active',
              stripe_subscription_id=COALESCE($stripeSubscriptionId, stripe_subscription_id),
              price_id=COALESCE($priceId, price_id),
              current_period_end=COALESCE($currentPeriodEnd, current_period_end),
              lapsed_at=NULL,
              updated_at=$now
          WHERE household_id=$householdId""".update.run
      .transact(xa)
      .unit

  def markLapsed(householdId: HouseholdId, lapsedAt: Instant) =
    sql"""UPDATE household_billing
          SET status='lapsed', lapsed_at=$lapsedAt, updated_at=$lapsedAt
          WHERE household_id=$householdId""".update.run
      .transact(xa)
      .unit

  def countByStatus: Task[Map[String, Int]] =
    sql"SELECT status, COUNT(*)::int FROM household_billing GROUP BY status"
      .query[(String, Int)]
      .to[List]
      .map(_.toMap)
      .transact(xa)
}

// ── BetaCohortRepo ────────────────────────────────────────────────────────────

/**
 * #2137 (multi-tenant P5-6, epic #622): the cohort-wide flip clock (`beta_cohort`, V70; design
 * §5.4, EVENT-TRIGGERED model). ONE installation-wide row. `clockStartedAt` latches when the active
 * beta-household count first reaches the threshold; `flippedAt` latches when the window-end
 * beta→lapsed sweep has run exactly once.
 */
case class BetaCohort(
    clockStartedAt: Option[Instant],
    flippedAt: Option[Instant],
)

trait BetaCohortRepo {

  /** The singleton cohort row (always present — V70 seeds it at migration time). */
  def get: Task[BetaCohort]

  /**
   * Start the flip clock — set `clock_started_at` to `at`, but only if it is still NULL (the
   * latch). Returns `true` iff this call actually started it, so a concurrent/duplicate tick can't
   * re-stamp a later start instant over the persisted one. Idempotent.
   */
  def startClock(at: Instant): Task[Boolean]

  /**
   * Latch the window-end flip — set `flipped_at` to `at`, but only if it is still NULL. Returns
   * `true` iff this call ran the latch, so the beta→lapsed sweep executes exactly once even across
   * multiple instances / repeated ticks.
   */
  def markFlipped(at: Instant): Task[Boolean]

  /**
   * Count "active beta households" (design §5.4): households whose
   * `household_billing.status='beta'` that have ≥1 enrolled router which reported traffic since
   * `since` (now − activeLookback). This is the live number the flip clock's threshold is compared
   * against; it can drift down during beta as households go quiet.
   */
  def countActiveBetaHouseholds(since: Instant): Task[Int]

  /** Household ids whose billing status is still `beta` — the unconverted set (notices + flip). */
  def betaHouseholdIds: Task[List[HouseholdId]]
}

class BetaCohortRepoLive(xa: Transactor[Task]) extends BetaCohortRepo {
  def get: Task[BetaCohort] =
    sql"SELECT clock_started_at, flipped_at FROM beta_cohort WHERE id = TRUE"
      .query[(Option[Instant], Option[Instant])]
      .unique
      .map((s, f) => BetaCohort(s, f))
      .transact(xa)

  def startClock(at: Instant): Task[Boolean] =
    sql"""UPDATE beta_cohort SET clock_started_at = $at
          WHERE id = TRUE AND clock_started_at IS NULL""".update.run
      .transact(xa)
      .map(_ == 1)

  def markFlipped(at: Instant): Task[Boolean] =
    sql"""UPDATE beta_cohort SET flipped_at = $at
          WHERE id = TRUE AND flipped_at IS NULL""".update.run
      .transact(xa)
      .map(_ == 1)

  def countActiveBetaHouseholds(since: Instant): Task[Int] =
    // Beta households with ≥1 router that reported traffic in the lookback window.
    //
    // Access pattern (traffic_reports is the unbounded growth table, so this matters): the driving
    // table is `household_billing WHERE status='beta'` — bounded small (threshold ~25 at launch),
    // and the query runs once an hour off the request path (FlipService job), not per request. For
    // each beta household the EXISTS probes `routers` by `idx_routers_household` (V65) then
    // `traffic_reports` by `idx_traffic_reports_router` (V2), and the `period_start >= $since`
    // filter is partition-prunable — traffic_reports is weekly RANGE-partitioned on period_start
    // (V41), so only the ~1-2 partitions covering the 7-day lookback are scanned, never the whole
    // history. So the cost is ~O(beta_households × routers_per_household) index probes against a
    // pruned recent slice, independent of total traffic_reports size. No full scan.
    sql"""SELECT COUNT(*)
          FROM household_billing hb
          WHERE hb.status = 'beta'
            AND EXISTS (
              SELECT 1
              FROM routers r
              JOIN traffic_reports tr ON tr.router_id = r.id
              WHERE r.household_id = hb.household_id
                AND tr.period_start >= $since
            )""".query[Int].unique.transact(xa)

  def betaHouseholdIds: Task[List[HouseholdId]] =
    sql"SELECT household_id FROM household_billing WHERE status = 'beta' ORDER BY household_id"
      .query[HouseholdId]
      .to[List]
      .transact(xa)
}

// ── BetaRequestRepo ──────────────────────────────────────────────────────────

/**
 * #2132: the beta-request queue (design §3). NB deliberately named `beta_requests` / `BetaRequest`,
 * NOT "access requests" — `AccessRequest` is the existing block-page concept (do not collide).
 *
 * [[approveAndProvision]] is the ONE atomic provisioning transaction (design §3.3): it creates the
 * `households` row, its `household_billing` row, and stamps the request (status → approved,
 * decided_*, invite hash + expiry, household_id) in a single DB transaction so a partial failure
 * never leaves an orphan household. It touches three tables because that IS the provisioning unit;
 * the non-SQL orchestration (slug derivation, token mint, TTL) lives in `BetaService`.
 */
trait BetaRequestRepo {

  /**
   * Public-intake insert, idempotent on the unique email (design §3.1 / #2132 scope 1). Returns
   * `true` if a new row was inserted, `false` if the email already had a request — the route
   * returns a generic 200 either way, so this boolean feeds only the outcome metric, never the
   * response.
   */
  def create(email: String, name: Option[String], note: Option[String]): Task[Boolean]
  def findById(id: BetaRequestId): Task[Option[DbBetaRequest]]
  def findByEmail(email: String): Task[Option[DbBetaRequest]]
  def findByInviteHash(hash: Sha256Hex): Task[Option[DbBetaRequest]]
  def listByStatus(status: BetaRequestStatus): Task[List[DbBetaRequest]]

  def approveAndProvision(
      id: BetaRequestId,
      decidedBy: UserId,
      householdName: String,
      slug: String,
      routerCap: Int,
      billingStatus: String,
      founding: Boolean,
      inviteTokenHash: Sha256Hex,
      inviteExpiresAt: Instant,
      decidedAt: Instant,
  ): Task[HouseholdId]

  /** Mark a pending request rejected. Returns `true` if it was pending (and is now rejected). */
  def reject(id: BetaRequestId, decidedBy: UserId, decidedAt: Instant): Task[Boolean]

  /**
   * #2222: re-mint the single-use invite token for an APPROVED, not-yet-accepted request — store a
   * fresh hash + expiry so a new, valid invite link can be re-sent to a returning applicant (only
   * the hash is stored, so the prior raw link can't be recovered). Guarded on `status='approved'
   * AND invite_token_hash IS NOT NULL` so it can never touch a pending/rejected row or re-open an
   * already-consumed invite. Returns `true` iff a row was updated.
   */
  def remintInvite(
      id: BetaRequestId,
      inviteTokenHash: Sha256Hex,
      inviteExpiresAt: Instant,
  ): Task[Boolean]

  /**
   * Invalidate the single-use invite token (design §3.4): null the hash so a replayed token no
   * longer resolves. Idempotent.
   */
  def consumeInvite(id: BetaRequestId): Task[Unit]
}

class BetaRequestRepoLive(xa: Transactor[Task]) extends BetaRequestRepo {
  private val cols =
    fr"id, email, name, note, status, requested_at, decided_at, decided_by, invite_token_hash, invite_expires_at, household_id"

  private type Row = (
      BetaRequestId,
      String,
      Option[String],
      Option[String],
      String,
      Instant,
      Option[Instant],
      Option[UserId],
      Option[Sha256Hex],
      Option[Instant],
      Option[HouseholdId],
  )

  private def toReq(r: Row): DbBetaRequest =
    DbBetaRequest(
      r._1,
      r._2,
      r._3,
      r._4,
      // A DB CHECK guarantees status ∈ {pending,approved,rejected}; an unparseable value is a
      // schema-drift bug, surfaced loudly rather than silently coerced.
      BetaRequestStatus
        .parse(r._5)
        .getOrElse(throw new IllegalStateException(s"bad beta status: ${r._5}")),
      r._6,
      r._7,
      r._8,
      r._9,
      r._10,
      r._11,
    )

  def create(email: String, name: Option[String], note: Option[String]) =
    sql"INSERT INTO beta_requests(email, name, note) VALUES($email, $name, $note) ON CONFLICT (email) DO NOTHING".update.run
      .transact(xa)
      .map(_ > 0)

  def findById(id: BetaRequestId) =
    (fr"SELECT" ++ cols ++ fr"FROM beta_requests WHERE id=$id")
      .query[Row]
      .map(toReq)
      .option
      .transact(xa)

  def findByEmail(email: String) =
    (fr"SELECT" ++ cols ++ fr"FROM beta_requests WHERE email=$email")
      .query[Row]
      .map(toReq)
      .option
      .transact(xa)

  def findByInviteHash(hash: Sha256Hex) =
    (fr"SELECT" ++ cols ++ fr"FROM beta_requests WHERE invite_token_hash=$hash")
      .query[Row]
      .map(toReq)
      .option
      .transact(xa)

  def listByStatus(status: BetaRequestStatus) =
    (fr"SELECT" ++ cols ++ fr"FROM beta_requests WHERE status=${BetaRequestStatus.asString(status)} ORDER BY requested_at DESC")
      .query[Row]
      .map(toReq)
      .to[List]
      .transact(xa)

  def approveAndProvision(
      id: BetaRequestId,
      decidedBy: UserId,
      householdName: String,
      slug: String,
      routerCap: Int,
      billingStatus: String,
      founding: Boolean,
      inviteTokenHash: Sha256Hex,
      inviteExpiresAt: Instant,
      decidedAt: Instant,
  ): Task[HouseholdId] = {
    // One transaction: create household + billing, then stamp the request. The stamping UPDATE is
    // guarded on `status='pending'` and its row count checked — a non-pending target raises, rolling
    // back the household/billing inserts too, so a double-approve can never mint a second household.
    val txn: ConnectionIO[HouseholdId] =
      for {
        // #2355: households + household_billing + global-sentinel via the ONE
        // [[HouseholdSeed.insertHousehold]] primitive — the same fragment HouseholdRepoLive.create
        // composes, so billing can never be seeded on one path and skipped on the other (the drift
        // this collapses). #2286: the global-sentinel is part of that unit, so a provisioned
        // household always has its `Global` policy row. Sequenced INSIDE this txn (not a second
        // .transact) so the beta_requests stamp below shares the transaction — a non-pending target
        // rolls the household/billing/sentinel inserts back too (double-approve guard).
        hid <- HouseholdSeed.insertHousehold(
          householdName,
          slug,
          routerCap,
          billingStatus,
          founding,
        )
        n   <-
          sql"""UPDATE beta_requests
                SET status='approved', decided_at=$decidedAt, decided_by=$decidedBy,
                    invite_token_hash=$inviteTokenHash, invite_expires_at=$inviteExpiresAt,
                    household_id=$hid
                WHERE id=$id AND status='pending'""".update.run
        _   <-
          if n == 1 then doobie.free.connection.unit
          else
            doobie.free.connection
              .raiseError[Unit](new IllegalStateException(s"beta request $id is not pending"))
      } yield hid
    txn.transact(xa)
  }

  def reject(id: BetaRequestId, decidedBy: UserId, decidedAt: Instant) =
    sql"""UPDATE beta_requests SET status='rejected', decided_at=$decidedAt, decided_by=$decidedBy
          WHERE id=$id AND status='pending'""".update.run
      .transact(xa)
      .map(_ == 1)

  def remintInvite(id: BetaRequestId, inviteTokenHash: Sha256Hex, inviteExpiresAt: Instant) =
    sql"""UPDATE beta_requests
          SET invite_token_hash=$inviteTokenHash, invite_expires_at=$inviteExpiresAt
          WHERE id=$id AND status='approved' AND invite_token_hash IS NOT NULL""".update.run
      .transact(xa)
      .map(_ == 1)

  def consumeInvite(id: BetaRequestId) =
    sql"UPDATE beta_requests SET invite_token_hash=NULL WHERE id=$id".update.run
      .transact(xa)
      .unit
}
