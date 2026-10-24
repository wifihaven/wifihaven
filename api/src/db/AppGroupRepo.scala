package wifihaven.api.db

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import cats.syntax.all.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.api.metrics.DbMetrics
import wifihaven.shared.{AppGroup, AppGroupRef, Clock, ProfileAppGroup}
import wifihaven.shared.types.*
import zio.*
import zio.interop.catz.*

import java.sql.SQLException
import java.time.LocalDate

/** Why a group or attachment write was refused. The route maps each to a status; none is a 500. */
sealed trait AppGroupError
object AppGroupError {

  /** No such group in the caller's household (also what another household's group looks like). */
  case object GroupNotFound extends AppGroupError

  /** No such profile in the caller's household. */
  case object ProfileNotFound extends AppGroupError

  /** The household already has a group with this name. */
  final case class NameTaken(name: String) extends AppGroupError

  /** D2: an app is in at most one group per household; names the group that already has it. */
  final case class AppInOtherGroup(appId: AppId, appName: String, groupName: String)
      extends AppGroupError

  /** A member id that is not in the app catalog. */
  final case class UnknownApp(appId: AppId) extends AppGroupError
}

/**
 * #2934 (epic #2930, design docs/design/weekly-limits-and-app-groups.md §5.3, §9): household app
 * groups (`app_groups` + `app_group_members`, V92) and each profile's attachment of one
 * (`profile_app_groups`). The group is only a name and a member list; limits live on the
 * attachment, so a group is not a policy tier (#1452).
 *
 * Every method takes the caller's household and filters on it, and the V92 composite FKs make a
 * cross-household member or attachment unrepresentable underneath that. Every write that changes a
 * profile's dispositions drops the profile's current-week settled usage in the SAME transaction
 * through [[SettledUsage.invalidate]] (design §6.4), so no caller can write and forget the drop.
 * Snapshot invalidation is the route's, since it needs the policy service.
 */
trait AppGroupRepo {
  def listForHousehold(household: HouseholdId): Task[List[AppGroup]]
  def find(household: HouseholdId, id: AppGroupId): Task[Option[AppGroup]]

  /** The owning household of a group, for the tenancy choke point. */
  def householdOf(id: AppGroupId): Task[Option[HouseholdId]]

  def create(
      household: HouseholdId,
      name: String,
      members: List[AppId],
  ): Task[Either[AppGroupError, AppGroup]]

  /**
   * `members` present REPLACES the member list. A membership edit drops every attached profile's
   * current week.
   */
  def update(
      household: HouseholdId,
      id: AppGroupId,
      name: Option[String],
      members: Option[List[AppId]],
  ): Task[Either[AppGroupError, AppGroup]]

  /** Deleting detaches the group from every profile that had it, so it drops their week too. */
  def delete(household: HouseholdId, id: AppGroupId): Task[Either[AppGroupError, Unit]]

  def listAttachments(household: HouseholdId, profile: ProfileId): Task[List[ProfileAppGroup]]

  /**
   * Create or replace the attachment. "At least one limit" is the caller's check (V92 CHECKs it
   * too).
   */
  def upsertAttachment(
      household: HouseholdId,
      profile: ProfileId,
      group: AppGroupId,
      dailyMinutes: Option[Int],
      weeklyMinutes: Option[Int],
      exemptFromDaily: Boolean,
  ): Task[Either[AppGroupError, ProfileAppGroup]]

  /** True if an attachment existed. */
  def deleteAttachment(household: HouseholdId, profile: ProfileId, group: AppGroupId): Task[Boolean]

  /**
   * D14: for each `(profile, app)` among `profiles`' covered apps, the attached group that covers
   * it. Scoped to `household`, so a group name never crosses the tenant boundary.
   */
  def coveredBy(
      household: HouseholdId,
      profiles: List[ProfileId],
  ): Task[Map[(ProfileId, AppId), AppGroupRef]]
}

class AppGroupRepoLive(xa: Transactor[Task], clock: Clock) extends AppGroupRepo {
  import AppGroupError.*

  private type Out[A] = Either[AppGroupError, A]

  private def fail[A](e: AppGroupError): ConnectionIO[Out[A]] =
    doobie.free.connection.pure(Left(e))

  // A unique / FK violation here is a race with another writer between our pre-checks and the
  // INSERT. The pre-checks are what name the conflict, so run the whole transaction once more: it
  // now sees the winner and answers with the typed error instead of surfacing a SQLException.
  // `from` is the oldest date a settled-usage drop reaches ([[SettledUsage.weekFloor]]), read once
  // per call from the injected clock so the transaction itself stays clock-free.
  private def txn[A](f: LocalDate => ConnectionIO[Out[A]]): Task[Out[A]] =
    clock.today.map(SettledUsage.weekFloor).flatMap { from =>
      val run = f(from).transact(xa)
      run.catchSome {
        case e: SQLException if e.getSQLState == "23505" || e.getSQLState == "23503" => run
      }
    }

  private def membersOf(ids: List[Long]): ConnectionIO[Map[Long, List[AppId]]] =
    if (ids.isEmpty) doobie.free.connection.pure(Map.empty)
    else {
      val arr = ids.toArray
      sql"SELECT group_id, app_id FROM app_group_members WHERE group_id = ANY($arr) ORDER BY app_id"
        .query[(Long, AppId)]
        .to[List]
        .map(_.groupBy(_._1).view.mapValues(_.map(_._2)).toMap)
    }

  private def findCio(household: HouseholdId, id: AppGroupId): ConnectionIO[Option[AppGroup]] =
    for {
      row <- sql"SELECT name FROM app_groups WHERE id = $id AND household_id = $household"
        .query[String]
        .option
      ms  <- membersOf(List(id.value))
    } yield row.map(AppGroup(id, _, ms.getOrElse(id.value, Nil)))

  def listForHousehold(household: HouseholdId) =
    DbMetrics.timed("appGroup.listForHousehold")(
      (for {
        rows <- sql"SELECT id, name FROM app_groups WHERE household_id = $household ORDER BY name"
          .query[(AppGroupId, String)]
          .to[List]
        ms   <- membersOf(rows.map(_._1.value))
      } yield rows.map((id, n) => AppGroup(id, n, ms.getOrElse(id.value, Nil)))).transact(xa),
    )

  def find(household: HouseholdId, id: AppGroupId) =
    DbMetrics.timed("appGroup.find")(findCio(household, id).transact(xa))

  def householdOf(id: AppGroupId) =
    sql"SELECT household_id FROM app_groups WHERE id = $id".query[HouseholdId].option.transact(xa)

  // The first problem with `members` as the prospective member list of group `self` (None on
  // create): an id outside the catalog, else an app a DIFFERENT group of this household has (D2).
  private def checkMembers(
      household: HouseholdId,
      self: Option[AppGroupId],
      members: List[AppId],
  ): ConnectionIO[Option[AppGroupError]] = {
    val arr = members.map(_.value).toArray
    val me  = self.map(_.value).getOrElse(0L)
    for {
      known    <- sql"SELECT id FROM apps WHERE id = ANY($arr)".query[AppId].to[List]
      clashing <-
        sql"""SELECT m.app_id, a.name, g.name
                FROM app_group_members m
                JOIN app_groups g ON g.id = m.group_id
                JOIN apps a       ON a.id = m.app_id
               WHERE m.household_id = $household AND m.app_id = ANY($arr) AND m.group_id <> $me
               ORDER BY m.app_id"""
          .query[(AppId, String, String)]
          .to[List]
    } yield members
      .find(!known.contains(_))
      .map(a => UnknownApp(a): AppGroupError)
      .orElse(clashing.headOption.map((a, an, gn) => AppInOtherGroup(a, an, gn)))
  }

  private def nameTaken(
      household: HouseholdId,
      name: String,
      self: Option[AppGroupId],
  ): ConnectionIO[Boolean] = {
    val me = self.map(_.value).getOrElse(0L)
    sql"""SELECT EXISTS (SELECT 1 FROM app_groups
                          WHERE household_id = $household AND name = $name AND id <> $me)"""
      .query[Boolean]
      .unique
  }

  private def insertMembers(household: HouseholdId, id: AppGroupId, members: List[AppId]) =
    members.traverse_ { a =>
      sql"INSERT INTO app_group_members(group_id, household_id, app_id) VALUES ($id, $household, $a)".update.run
    }

  def create(household: HouseholdId, name: String, members: List[AppId]) =
    DbMetrics.timed("appGroup.create")(txn[AppGroup] { _ =>
      val ms = members.distinct
      for {
        taken <- nameTaken(household, name, None)
        bad   <-
          if (taken) doobie.free.connection.pure(Option(NameTaken(name): AppGroupError))
          else checkMembers(household, None, ms)
        out   <- bad match {
          case Some(e) => fail[AppGroup](e)
          case None    =>
            for {
              id <-
                sql"INSERT INTO app_groups(household_id, name) VALUES ($household, $name) RETURNING id"
                  .query[AppGroupId]
                  .unique
              _  <- insertMembers(household, id, ms)
            } yield Right(AppGroup(id, name, ms.sortBy(_.value))): Out[AppGroup]
        }
      } yield out
    })

  private def attachedProfiles(id: AppGroupId): ConnectionIO[List[ProfileId]] =
    sql"SELECT profile_id FROM profile_app_groups WHERE group_id = $id".query[ProfileId].to[List]

  def update(
      household: HouseholdId,
      id: AppGroupId,
      name: Option[String],
      members: Option[List[AppId]],
  ) =
    DbMetrics.timed("appGroup.update")(txn[AppGroup] { from =>
      val ms = members.map(_.distinct)
      for {
        existing <- findCio(household, id)
        out      <- existing match {
          case None    => fail[AppGroup](GroupNotFound)
          case Some(g) =>
            for {
              taken <- name.filter(_ != g.name).traverse(nameTaken(household, _, Some(id)))
              bad   <-
                if (taken.contains(true))
                  doobie.free.connection.pure(name.map(NameTaken(_): AppGroupError))
                else
                  ms.traverse(checkMembers(household, Some(id), _)).map(_.flatten)
              out   <- bad match {
                case Some(e) => fail[AppGroup](e)
                case None    =>
                  val changed = ms.exists(_.toSet != g.memberAppIds.toSet)
                  for {
                    _        <- name.traverse_(n =>
                      sql"UPDATE app_groups SET name = $n WHERE id = $id".update.run,
                    )
                    _        <- ms.filter(_ => changed).traverse_ { newMs =>
                      sql"DELETE FROM app_group_members WHERE group_id = $id".update.run *>
                        insertMembers(household, id, newMs)
                    }
                    // Design §6.4: a membership edit changes every attached profile's attribution set.
                    attached <-
                      if (changed) attachedProfiles(id) else doobie.free.connection.pure(Nil)
                    _        <- SettledUsage.invalidate(attached, SettledUsage.Kinds.All, from)
                    g2       <- findCio(household, id)
                  } yield g2.toRight(GroupNotFound: AppGroupError)
              }
            } yield out
        }
      } yield out
    })

  def delete(household: HouseholdId, id: AppGroupId) =
    DbMetrics.timed("appGroup.delete")(txn[Unit] { from =>
      for {
        existing <- findCio(household, id)
        out      <- existing match {
          case None    => fail[Unit](GroupNotFound)
          case Some(_) =>
            for {
              attached <- attachedProfiles(id)
              _        <-
                sql"DELETE FROM app_groups WHERE id = $id AND household_id = $household".update.run
              _        <- SettledUsage.invalidate(attached, SettledUsage.Kinds.All, from)
            } yield Right(()): Out[Unit]
        }
      } yield out
    })

  private type AttRow = (AppGroupId, String, Option[Int], Option[Int], Boolean)

  private def attachmentsCio(
      household: HouseholdId,
      profile: ProfileId,
      only: Option[AppGroupId],
  ): ConnectionIO[List[ProfileAppGroup]] = {
    val one = only.map(_.value).getOrElse(-1L)
    for {
      rows <- sql"""SELECT g.id, g.name, p.daily_minutes, p.weekly_minutes, p.exempt_from_daily
                      FROM profile_app_groups p
                      JOIN app_groups g ON g.id = p.group_id AND g.household_id = p.household_id
                     WHERE p.household_id = $household AND p.profile_id = $profile
                       AND ($one = -1 OR p.group_id = $one)
                     ORDER BY g.name"""
        .query[AttRow]
        .to[List]
      ms   <- membersOf(rows.map(_._1.value))
    } yield rows.map((gid, n, d, w, e) =>
      ProfileAppGroup(profile, gid, n, ms.getOrElse(gid.value, Nil), d, w, e),
    )
  }

  def listAttachments(household: HouseholdId, profile: ProfileId) =
    DbMetrics.timed("appGroup.listAttachments")(
      attachmentsCio(household, profile, None).transact(xa),
    )

  def upsertAttachment(
      household: HouseholdId,
      profile: ProfileId,
      group: AppGroupId,
      dailyMinutes: Option[Int],
      weeklyMinutes: Option[Int],
      exemptFromDaily: Boolean,
  ) =
    DbMetrics.timed("appGroup.upsertAttachment")(txn[ProfileAppGroup] { from =>
      for {
        grp   <- findCio(household, group)
        prof  <-
          sql"SELECT EXISTS (SELECT 1 FROM profiles WHERE id = $profile AND household_id = $household)"
            .query[Boolean]
            .unique
        prior <- sql"""SELECT exempt_from_daily FROM profile_app_groups
                        WHERE profile_id = $profile AND group_id = $group"""
          .query[Boolean]
          .option
        out   <-
          if (grp.isEmpty) fail[ProfileAppGroup](GroupNotFound)
          else if (!prof) fail[ProfileAppGroup](ProfileNotFound)
          else
            for {
              _   <- sql"""INSERT INTO profile_app_groups
                             (household_id, profile_id, group_id, daily_minutes, weekly_minutes,
                              exempt_from_daily)
                           VALUES ($household, $profile, $group, $dailyMinutes, $weeklyMinutes,
                                   $exemptFromDaily)
                           ON CONFLICT (profile_id, group_id) DO UPDATE SET
                             daily_minutes = EXCLUDED.daily_minutes,
                             weekly_minutes = EXCLUDED.weekly_minutes,
                             exempt_from_daily = EXCLUDED.exempt_from_daily""".update.run
              // Design §6.4: a new attachment changes the profile's dispositions (drop all three
              // kinds); an exempt-flag edit moves only the profile daily total; limits move nothing.
              _   <- prior match {
                case None                                =>
                  SettledUsage.invalidate(List(profile), SettledUsage.Kinds.All, from)
                case Some(was) if was != exemptFromDaily =>
                  SettledUsage.invalidate(List(profile), SettledUsage.Kinds.ProfileTotalOnly, from)
                case Some(_)                             => doobie.free.connection.pure(0)
              }
              row <- attachmentsCio(household, profile, Some(group))
            } yield row.headOption.toRight(GroupNotFound: AppGroupError)
      } yield out
    })

  def deleteAttachment(household: HouseholdId, profile: ProfileId, group: AppGroupId) =
    DbMetrics.timed("appGroup.deleteAttachment")(
      clock.today.map(SettledUsage.weekFloor).flatMap { from =>
        (for {
          n <- sql"""DELETE FROM profile_app_groups
                      WHERE household_id = $household AND profile_id = $profile
                        AND group_id = $group""".update.run
          _ <-
            if (n > 0) SettledUsage.invalidate(List(profile), SettledUsage.Kinds.All, from)
            else doobie.free.connection.pure(0)
        } yield n > 0).transact(xa)
      },
    )

  def coveredBy(household: HouseholdId, profiles: List[ProfileId]) =
    if (profiles.isEmpty) ZIO.succeed(Map.empty)
    else {
      val arr = profiles.distinct.map(_.value).toArray
      DbMetrics.timed("appGroup.coveredBy")(
        sql"""SELECT p.profile_id, m.app_id, g.id, g.name
                FROM profile_app_groups p
                JOIN app_group_members m ON m.group_id = p.group_id
                JOIN app_groups g        ON g.id = p.group_id
               WHERE p.household_id = $household AND p.profile_id = ANY($arr)"""
          .query[(ProfileId, AppId, AppGroupId, String)]
          .to[List]
          .map(_.map((p, a, g, n) => (p, a) -> AppGroupRef(g, n)).toMap)
          .transact(xa),
      )
    }
}
