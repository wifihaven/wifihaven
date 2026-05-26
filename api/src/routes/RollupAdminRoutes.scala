package wifihaven.api.routes

import wifihaven.api.auth.*
import wifihaven.api.db.{RollupRepo, RollupRun}
import zio.*
import zio.http.*
import zio.json.*

// #809: admin observability for the rollup fibers. One endpoint, returns the
// last N rollup_runs rows so the operator can spot a stuck fiber from the
// admin UI. Read-only.
object RollupAdminRoutes {

  case class RollupRunDto(
      id: Long,
      job: String,
      startedAt: String,
      finishedAt: String,
      status: String,
      error: Option[String],
      rowsUpserted: Int,
      durationMs: Long,
  )

  object RollupRunDto {
    given JsonEncoder[RollupRunDto]      = DeriveJsonEncoder.gen[RollupRunDto]
    def from(r: RollupRun): RollupRunDto = RollupRunDto(
      id = r.id,
      job = r.job,
      startedAt = r.startedAt.toString,
      finishedAt = r.finishedAt.toString,
      status = r.status,
      error = r.error,
      rowsUpserted = r.rowsUpserted,
      durationMs = math.max(0L, r.finishedAt.toEpochMilli - r.startedAt.toEpochMilli),
    )
  }

  case class RollupStatusResponse(runs: List[RollupRunDto])
  object RollupStatusResponse {
    given JsonEncoder[RollupStatusResponse] = DeriveJsonEncoder.gen[RollupStatusResponse]
  }

  def routes(auth: AuthService, repo: RollupRepo): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "admin" / "rollup-status" ->
        handler { (req: Request) =>
          for {
            _ <- requireAdmin(req, auth)
            // Cap at 200 to keep the response tiny — fibers tick every 5 min
            // so even 200 covers ~16h of history.
            limit = req.url.queryParam("limit").flatMap(_.toIntOption).getOrElse(50).max(1).min(200)
            runs <- repo.recentRuns(limit).mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.json(RollupStatusResponse(runs.map(RollupRunDto.from)).toJson)
        },
    )
}
