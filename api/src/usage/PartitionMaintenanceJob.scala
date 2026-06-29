package wifihaven.api.usage

import wifihaven.api.db.PartitionRepo
import zio.*

import java.time.Instant

// STUB (#808, red commit): no-op tick. Replaced by the real create-pass + INFO log + runway-gauge
// emission in the green commit.
object PartitionMaintenanceJob {
  def runOnce(repo: PartitionRepo, weeksAhead: Int, now: Instant): UIO[Unit] =
    ZIO.unit
}
