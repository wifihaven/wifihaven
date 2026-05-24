package wifihaven.api

import wifihaven.shared.types.*
import zio.*

import java.time.Instant

/**
 * #958 (URL-sourced lists): in-memory cache of the most recent successful upstream fetch for each
 * bundled blocklist id. Single source of truth across the runtime — no disk persistence (the DB's
 * `blocklist_domains` is the durable copy; the cache is just a tap so we can re-seed without
 * re-hitting the network and so an admin "show me what's cached" endpoint has data to return).
 *
 * Entries are populated by `BundledBlocklists.seed` / `.refresh`; nothing in the cache means
 * "never successfully fetched in this process." Misses fall through to the DB on read paths.
 */
trait BlocklistCache {
  def get(id: BlocklistId): UIO[Option[BlocklistCache.Entry]]
  def put(id: BlocklistId, entry: BlocklistCache.Entry): UIO[Unit]
  def snapshot: UIO[Map[BlocklistId, BlocklistCache.Entry]]
}

object BlocklistCache {

  final case class Entry(hosts: List[Hostname], fetchedAt: Instant, source: String)

  val live: ZLayer[Any, Nothing, BlocklistCache] =
    ZLayer.scoped {
      Ref.make(Map.empty[BlocklistId, Entry]).map(new Live(_))
    }

  final class Live(ref: Ref[Map[BlocklistId, Entry]]) extends BlocklistCache {
    override def get(id: BlocklistId): UIO[Option[Entry]]      =
      ref.get.map(_.get(id))
    override def put(id: BlocklistId, entry: Entry): UIO[Unit] =
      ref.update(_.updated(id, entry))
    override def snapshot: UIO[Map[BlocklistId, Entry]]        = ref.get
  }
}
