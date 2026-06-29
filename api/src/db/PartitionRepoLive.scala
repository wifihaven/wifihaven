package wifihaven.api.db

import doobie.Transactor
import zio.*

import java.time.Instant

// STUB (#808, red commit): always reports the lock as held so nothing is created and the runway
// reads 0. Replaced by the real advisory-locked create-and-measure pass in the green commit.
final case class PartitionRepoLive(xa: Transactor[Task]) extends PartitionRepo {
  def ensureFuturePartitions(
      weeksAhead: Int,
      now: Instant,
  ): Task[Option[List[PartitionRepo.TableResult]]] =
    ZIO.succeed(None)
}
